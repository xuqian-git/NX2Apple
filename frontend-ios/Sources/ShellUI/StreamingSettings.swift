import Foundation

public struct StreamReceiver: Codable, Equatable, Identifiable, Sendable {
    public var deviceID: String? = nil
    public var host: String
    public var name: String
    public var width: Int
    public var height: Int
    public var modes: [String]
    public var id: String { deviceID ?? host }
    public init(deviceID: String? = nil, host: String, name: String, width: Int, height: Int, modes: [String]) {
        self.deviceID = deviceID; self.host = host; self.name = name
        self.width = width; self.height = height; self.modes = modes
    }

    public static func validSize(_ size: String) -> Bool {
        let parts = size.split(separator: "x")
        guard parts.count == 2, let w = Int(parts[0]), let h = Int(parts[1]) else { return false }
        return (320...4096).contains(w) && (240...4096).contains(h) && w % 2 == 0 && h % 2 == 0 && w * h <= 4096 * 2160
    }
    public static func validHost(_ host: String) -> Bool {
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        return parts.count == 4 && parts.allSatisfy { !$0.isEmpty && $0.allSatisfy(\.isNumber) && UInt8($0) != nil }
    }
}

extension ShellSettingsStore {
    public var streamEnabled: Bool { defaults.bool(forKey: "stream.enabled") }
    public func storeStreamEnabled(_ on: Bool) { defaults.set(on, forKey: "stream.enabled") }
    public var streamBlack: Bool { defaults.object(forKey: "stream.black") as? Bool ?? true }
    public func storeStreamBlack(_ on: Bool) { defaults.set(on, forKey: "stream.black") }
    public var streamSize: String { defaults.string(forKey: "stream.size") ?? "auto" }
    public func storeStreamSize(_ size: String) {
        if size == "auto" || StreamReceiver.validSize(size) { defaults.set(size, forKey: "stream.size") }
    }
    public var streamReceiver: StreamReceiver? {
        guard let data = defaults.data(forKey: "stream.receiver") else { return nil }
        return try? JSONDecoder().decode(StreamReceiver.self, from: data)
    }
    public func storeStreamReceiver(_ receiver: StreamReceiver) {
        guard StreamReceiver.validHost(receiver.host), !receiver.modes.isEmpty else { return }
        defaults.set(try? JSONEncoder().encode(receiver), forKey: "stream.receiver")
        if streamSize != "auto" && !receiver.modes.contains(streamSize) { storeStreamSize("auto") }
    }
    public var streamingEnvironment: [String: String] {
        guard streamEnabled, let receiver = streamReceiver, StreamReceiver.validHost(receiver.host) else { return ["DM_STREAM_HOST": ""] }
        let modes = receiver.modes.filter(StreamReceiver.validSize)
        guard !modes.isEmpty else { return ["DM_STREAM_HOST": ""] }
        return ["DM_STREAM_HOST": receiver.host, "DM_STREAM_TOKEN": "0000000000000001",
                "DM_STREAM_SIZE": streamSize == "auto" ? "auto" : (modes.contains(streamSize) ? streamSize : modes[0]),
                "DM_STREAM_MODES": modes.joined(separator: ","), "DM_STREAM_BLACK": streamBlack ? "1" : "0",
                "DM_PRESENT_LINK": "0", "DM_SCREEN_ASPECT": "0"]
    }
}
