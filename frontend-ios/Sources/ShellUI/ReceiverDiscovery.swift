import Foundation
import Network
import Combine
import Darwin

@MainActor final class ReceiverDiscovery: ObservableObject {
    @Published var receivers: [StreamReceiver] = []
    @Published var searching = false
    private var browser: NWBrowser?
    private var connections: [NWConnection] = []
    private var endpoints = Set<String>()
    private var generation = UUID()

    func stop() {
        generation = UUID()
        browser?.cancel(); browser = nil
        connections.forEach { $0.cancel() }; connections.removeAll()
        searching = false
    }
    func scan() {
        stop(); receivers = []; endpoints = []; searching = true
        let current = generation
        let browser = NWBrowser(for: .bonjour(type: "_nxstream._udp", domain: nil), using: .udp)
        self.browser = browser
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            Task { @MainActor in
                guard let self, self.generation == current else { return }
                for result in results { self.probe(result.endpoint, generation: current) }
            }
        }
        browser.start(queue: .main)
        // Unicast needs no multicast entitlement and works when hotspot multicast discovery is suppressed.
        // Probe only connected private LAN interfaces. Large LANs use Bonjour plus the local /24 and subnet gateway.
        let hosts = Self.localTargets().sorted()
        Task { [weak self] in
            for (index, host) in hosts.enumerated() {
                guard let self, self.generation == current else { return }
                self.probe(.hostPort(host: .init(host), port: 48001), generation: current)
                if index % 16 == 15 { try? await Task.sleep(for: .milliseconds(250)) }
            }
        }
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(max(6000, hosts.count / 16 * 250 + 2000)))
            guard let self, self.generation == current else { return }
            self.stop()
        }
    }
    private func probe(_ endpoint: NWEndpoint, generation current: UUID) {
        guard endpoints.insert(String(describing: endpoint)).inserted else { return }
        let connection = NWConnection(to: endpoint, using: .udp)
        connections.append(connection)
        connection.stateUpdateHandler = { [weak self] state in
            guard case .ready = state else { return }
            connection.send(content: Data("NXDISC01".utf8), completion: .contentProcessed { _ in })
            connection.receiveMessage { data, _, _, _ in
                Task { @MainActor in
                    defer { connection.stateUpdateHandler = nil; connection.cancel() }
                    guard let self, self.generation == current, let data, data.count <= 4096,
                          case let .hostPort(host, _) = connection.currentPath?.remoteEndpoint ?? endpoint,
                          let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                          object["protocol"] as? String == "NXDISC01", object["mediaPort"] as? Int == 48000,
                          let name = object["name"] as? String, let width = object["width"] as? Int,
                          let height = object["height"] as? Int, let modes = object["modes"] as? [String] else { return }
                    let address = String(describing: host).split(separator: "%")[0].description
                    guard StreamReceiver.validHost(address), width > 0, height > 0 else { return }
                    let valid = Array(modes.prefix(16)).filter(StreamReceiver.validSize)
                    guard !valid.isEmpty else { return }
                    let receiver = StreamReceiver(deviceID: object["id"] as? String, host: address, name: String(name.prefix(80)), width: width, height: height, modes: valid)
                    self.receivers.removeAll { $0.id == receiver.id }
                    self.receivers.append(receiver)
                    self.receivers.sort { $0.name < $1.name }
                }
            }
        }
        connection.start(queue: .main)
        Task {
            let timeout: Double
            if case .service = endpoint { timeout = 3 } else { timeout = 1 }
            try? await Task.sleep(for: .seconds(timeout))
            connection.stateUpdateHandler = nil
            connection.cancel()
        }
    }
    private static func localTargets() -> Set<String> {
        var first: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&first) == 0 else { return [] }
        defer { freeifaddrs(first) }
        var result = Set<String>(), current = first
        while let entry = current {
            defer { current = entry.pointee.ifa_next }
            let name = String(cString: entry.pointee.ifa_name)
            guard name.hasPrefix("en") || name.hasPrefix("bridge") || name.hasPrefix("ap"),
                  let address = entry.pointee.ifa_addr, address.pointee.sa_family == UInt8(AF_INET),
                  let netmask = entry.pointee.ifa_netmask else { continue }
            let ip = address.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
            let mask = netmask.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
            guard ip >> 24 == 10 || ip >> 20 == 0xac1 || ip >> 16 == 0xc0a8 || ip >> 16 == 0xa9fe else { continue }
            let actualNetwork = ip & mask
            let probeMask: UInt32 = ~mask <= 1023 ? mask : 0xffffff00
            let network = ip & probeMask, broadcast = network | ~probeMask
            guard broadcast > network + 1 else { continue }
            var candidates = Array((network + 1)..<broadcast)
            candidates.append(actualNetwork + 1)
            for n in candidates where n != ip {
                result.insert("\(n >> 24).\((n >> 16) & 255).\((n >> 8) & 255).\(n & 255)")
            }
        }
        return result
    }
}
