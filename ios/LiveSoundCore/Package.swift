// swift-tools-version:5.9
// LiveSoundCore: platform-independent DSP + analysis engine used by the iPhone app.
// It has no Apple-only dependencies, so `swift test` also runs on Linux.
import PackageDescription

let package = Package(
    name: "LiveSoundCore",
    platforms: [.iOS(.v17), .macOS(.v13)],
    products: [
        .library(name: "LiveSoundCore", targets: ["LiveSoundCore"]),
    ],
    targets: [
        .target(
            name: "LiveSoundCore",
            // The DSP runs ~30x per second; unoptimized (Debug) Swift is ~30x slower, so
            // always optimize this module, even when the app is run from Xcode in Debug.
            swiftSettings: [.unsafeFlags(["-O"], .when(configuration: .debug))]
        ),
        .testTarget(name: "LiveSoundCoreTests", dependencies: ["LiveSoundCore"]),
    ]
)
