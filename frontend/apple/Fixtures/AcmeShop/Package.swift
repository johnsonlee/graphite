// swift-tools-version:5.10
import PackageDescription

// A small storefront module the frontend tests and the CI end-to-end job index.
let package = Package(
    name: "AcmeShop",
    products: [.library(name: "AcmeShop", targets: ["AcmeShop"])],
    targets: [.target(name: "AcmeShop")]
)
