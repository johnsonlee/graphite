// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "graphite-frontend-apple",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "graphite-frontend-apple", targets: ["graphite-frontend-apple"]),
        .library(name: "GraphiteFrontend", targets: ["GraphiteFrontend"]),
        .library(name: "GraphiteIR", targets: ["GraphiteIR"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-protobuf.git", from: "1.28.0"),
        .package(url: "https://github.com/apple/swift-argument-parser.git", from: "1.5.0"),
        .package(url: "https://github.com/swiftlang/swift-syntax.git", from: "601.0.0"),
        .package(url: "https://github.com/swiftlang/indexstore-db.git", branch: "release/6.1"),
    ],
    targets: [
        // Generated bindings for ir/graphite_ir.proto plus the chunked stream writer.
        .target(
            name: "GraphiteIR",
            dependencies: [.product(name: "SwiftProtobuf", package: "swift-protobuf")]
        ),
        // Index store reader, SwiftSyntax literal pass and the Graph IR emitter.
        .target(
            name: "GraphiteFrontend",
            dependencies: [
                "GraphiteIR",
                .product(name: "IndexStoreDB", package: "indexstore-db"),
                .product(name: "SwiftSyntax", package: "swift-syntax"),
                .product(name: "SwiftParser", package: "swift-syntax"),
            ]
        ),
        .executableTarget(
            name: "graphite-frontend-apple",
            dependencies: [
                "GraphiteFrontend",
                .product(name: "ArgumentParser", package: "swift-argument-parser"),
            ]
        ),
        .testTarget(
            name: "GraphiteFrontendTests",
            dependencies: ["GraphiteFrontend"]
        ),
    ]
)
