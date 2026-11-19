import Foundation
import Testing
import ShellUI
@testable import ShellHost

@Suite struct StreamingSettingsTests {
    private func store() -> ShellSettingsStore {
        ShellSettingsStore(defaults: UserDefaults(suiteName: "stream-test-\(UUID().uuidString)")!)
    }
    @Test func automaticSizeAndReceiverSurviveRestartWithoutUserCredentials() {
        let settings = store()
        #expect(settings.streamingEnvironment == ["DM_STREAM_HOST": ""])
        let receiver = StreamReceiver(host: "172.20.10.2", name: "Car", width: 2560, height: 1600, modes: ["2560x1600", "1920x1200"])
        settings.storeStreamReceiver(receiver)
        settings.storeStreamEnabled(true)
        let again = ShellSettingsStore(defaults: settings.defaults)
        #expect(again.streamReceiver == receiver)
        #expect(again.streamingEnvironment["DM_STREAM_SIZE"] == "auto")
        #expect(again.streamingEnvironment["DM_STREAM_MODES"] == "2560x1600,1920x1200")
        #expect(again.streamingEnvironment["DM_STREAM_BLACK"] == "1")
        settings.storeStreamBlack(false)
        settings.storeStreamSize("1920x1200")
        #expect(again.streamingEnvironment["DM_STREAM_SIZE"] == "1920x1200")
        #expect(again.streamingEnvironment["DM_STREAM_BLACK"] == "0")
        settings.storeStreamEnabled(false)
        #expect(again.streamingEnvironment == ["DM_STREAM_HOST": ""])
    }
    @Test func boundsRejectMalformedAndUnsupportedDimensions() {
        for size in ["2560x1600", "3840x2160", "1280x800", "1080x1920"] { #expect(StreamReceiver.validSize(size)) }
        for size in ["0x0", "99999x1000", "1921x1080", "4096x4096", "1920x1080x1", "-1x240"] { #expect(!StreamReceiver.validSize(size)) }
        #expect(!StreamReceiver.validHost("192.168.1.999"))
        #expect(!StreamReceiver.validHost("192.168.1.1\nDM_STREAM_BLACK=1"))
    }
    @Test func appSwitchOverridesLegacyFilesAndOffCannotLeaveStreamingEnabled() throws {
        var rig = try EngineLaunchPlanTests.Rig()
        try rig.box.write("DM_STREAM_HOST=192.168.1.22\nDM_STREAM_TOKEN=123456789abcdef0\n", to: rig.gdir.appendingPathComponent("env.txt"))
        let settings = store()
        rig.game.streamingEnvironment = settings.streamingEnvironment
        #expect(rig.plan().environment["DM_STREAM_HOST"] == "")
        settings.storeStreamReceiver(StreamReceiver(host: "172.20.10.2", name: "Car", width: 2560, height: 1600, modes: ["2560x1600"]))
        settings.storeStreamEnabled(true)
        rig.game.streamingEnvironment = settings.streamingEnvironment
        let env = rig.plan().environment
        #expect(env["DM_STREAM_HOST"] == "172.20.10.2")
        #expect(env["DM_STREAM_TOKEN"] == "0000000000000001")
        #expect(env["DM_STREAM_SIZE"] == "auto")
        #expect(!(env["DM_ENV_FILE_KEYS"] ?? "").contains("DM_STREAM_HOST"))
    }
}
