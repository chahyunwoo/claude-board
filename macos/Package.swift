// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "ClaudeBoardMenu",
    // MenuBarExtra 는 macOS 13+. 이 기계는 26.4 다.
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(name: "ClaudeBoardMenu", path: "Sources/ClaudeBoardMenu"),
    ]
)
