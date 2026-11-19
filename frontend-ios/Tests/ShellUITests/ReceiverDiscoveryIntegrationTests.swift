import Foundation
import Network
import Testing
@testable import ShellUI

@Suite @MainActor struct ReceiverDiscoveryIntegrationTests {
    // Opt in on a host that permits Bonjour. CI still runs the deterministic settings/launch tests.
    @Test(.enabled(if: ProcessInfo.processInfo.environment["NXSTREAM_DISCOVERY_TEST"] == "1"))
    func bonjourFindsReceiverAndReadsItsActualModes() async throws {
        let id = UUID().uuidString
        let reply = try JSONSerialization.data(withJSONObject: ["protocol": "NXDISC01", "id": id, "name": "Discovery test", "width": 2560, "height": 1600, "mediaPort": 48000, "modes": ["2560x1600", "1920x1200"]])
        let listener = try NWListener(using: .udp)
        listener.service = NWListener.Service(name: "NXStream test \(id)", type: "_nxstream._udp")
        listener.newConnectionHandler = { connection in
            connection.start(queue: .main)
            connection.receiveMessage { request, _, _, _ in
                guard request == Data("NXDISC01".utf8) else { connection.cancel(); return }
                connection.send(content: reply, completion: .contentProcessed { _ in connection.cancel() })
            }
        }
        listener.start(queue: .main)
        defer { listener.cancel() }
        let discovery = ReceiverDiscovery()
        discovery.scan()
        defer { discovery.stop() }
        for _ in 0..<100 {
            if discovery.receivers.contains(where: { $0.deviceID == id }) { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        let receiver = try #require(discovery.receivers.first { $0.deviceID == id })
        #expect(receiver.modes == ["2560x1600", "1920x1200"])
        #expect(StreamReceiver.validHost(receiver.host))
    }
}
