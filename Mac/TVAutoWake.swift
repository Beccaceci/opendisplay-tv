import Foundation

/// Fast zero-click TV orchestrator.
/// Automatically wakes the TV over ADB (if available on the local network) and brings
/// OpenDisplay to the front so the user never needs to touch the physical remote control.
enum TVAutoWake {
    private static let adbCandidates = [
        "/Users/nicolabeccaceci/Library/Android/sdk/platform-tools/adb",
        "/usr/local/bin/adb",
        "/opt/homebrew/bin/adb"
    ]

    private static var adbPath: String? {
        adbCandidates.first { FileManager.default.isExecutableFile(atPath: $0) }
    }

    static func wakeAndLaunch(host: String) {
        guard let adb = adbPath else { return }
        let target = host.contains(":") ? host : "\(host):5555"

        DispatchQueue.global(qos: .utility).async {
            let proc = Process()
            proc.executableURL = URL(fileURLWithPath: adb)
            proc.arguments = ["connect", target]
            try? proc.run()
            proc.waitUntilExit()

            let wake = Process()
            wake.executableURL = URL(fileURLWithPath: adb)
            wake.arguments = [
                "-s", target,
                "shell",
                "input keyevent KEYCODE_WAKEUP && am start -n com.peetzweg.opendisplay.debug/app.opendisplay.android.ui.MainActivity"
            ]
            try? wake.run()
            wake.waitUntilExit()
            Log.info("TV auto-wake command sent to \(target)")
        }
    }
}
