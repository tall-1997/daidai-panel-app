// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "DaidaiPanel",
    platforms: [
        .iOS(.v16)
    ],
    products: [
        .library(
            name: "DaidaiPanel",
            targets: ["DaidaiPanel"]
        )
    ],
    dependencies: [
        // 依赖gomobile生成的框架
    ],
    targets: [
        .target(
            name: "DaidaiPanel",
            dependencies: [],
            path: "DaidaiPanel",
            resources: [
                .process("Resources")
            ]
        )
    ]
)
