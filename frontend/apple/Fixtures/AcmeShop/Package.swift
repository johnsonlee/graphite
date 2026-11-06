// swift-tools-version:5.10
import PackageDescription

// A small storefront module the frontend tests and the CI end-to-end job index, plus a
// command-line tool that lives outside `Sources/`: the frontend has to find it through
// the manifest, not by walking the conventional directory.
let package = Package(
    name: "AcmeShop",
    products: [
        .library(name: "AcmeShop", targets: ["AcmeShop"]),
        .executable(name: "acme-tool", targets: ["AcmeShopTool"]),
    ],
    targets: [
        .target(name: "AcmeShop"),
        .executableTarget(name: "AcmeShopTool", dependencies: ["AcmeShop"], path: "CommandLineTool"),
    ]
)
