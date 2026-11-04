import Foundation
import XCTest
@testable import GraphiteFrontend
import GraphiteIR

final class AppleResourceIntegrationTests: XCTestCase {
    private func plist(_ values: [String: Any], at url: URL) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try PropertyListSerialization.data(fromPropertyList: values, format: .binary, options: 0).write(to: url)
    }

    func testSelectedBundlesSurviveTheFrontendWireFormatWithTheirTypesAndScope() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("resource-integration-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let package = root.appendingPathComponent("Package")
        try FileManager.default.createDirectory(at: package.appendingPathComponent("Sources/Probe"), withIntermediateDirectories: true)
        try """
        // swift-tools-version:5.10
        import PackageDescription
        let package = Package(name: "Probe", targets: [.target(name: "Probe")])
        """.write(to: package.appendingPathComponent("Package.swift"), atomically: true, encoding: .utf8)
        try "public struct Probe {}".write(to: package.appendingPathComponent("Sources/Probe/Probe.swift"), atomically: true, encoding: .utf8)
        let app = root.appendingPathComponent("Probe.app")
        let share = root.appendingPathComponent("Share.appex")
        try plist(["CFBundleIdentifier": "org.example.Probe", "Enabled": true, "Count": 7,
                   "Unsupported": Date(timeIntervalSince1970: 0)], at: app.appendingPathComponent("Info.plist"))
        try plist(["welcome": "Hello"], at: app.appendingPathComponent("en.lproj/Localizable.strings"))
        try plist(["CFBundleIdentifier": "org.example.Probe.Share"], at: share.appendingPathComponent("Info.plist"))
        try plist(["welcome": "Share"], at: share.appendingPathComponent("en.lproj/Localizable.strings"))
        var options = BuildOptions()
        options.package = package.path
        options.resourceBundles = [app.path, app.path, share.path]
        let output = root.appendingPathComponent("probe.graphite-ir")
        let result = try Frontend(options: options).build(to: output)
        let graph = try DecodedIR(url: output)
        XCTAssertEqual(result.summary.resourceFiles, 4)
        XCTAssertEqual(result.summary.resourceValues, 6)
        XCTAssertEqual(result.resourceOmissions.map(\.key), ["Unsupported"])
        XCTAssertEqual(graph.origins.map(\.className), ["Probe.Probe"])
        let files = graph.nodes.values.compactMap { node -> GraphiteIRResourceFile? in
            if case .resourceFile(let file)? = node.kind { return file }; return nil
        }
        XCTAssertEqual(Set(files.map(\.source)), ["org.example.Probe", "org.example.Probe.Share"])
        XCTAssertEqual(Set(files.filter { $0.format == "strings" }.map(\.profile)), ["en"])
        let values = graph.nodes.values.compactMap { node -> GraphiteIRResourceValue? in
            if case .resourceValue(let value)? = node.kind { return value }; return nil
        }
        let enabled = try XCTUnwrap(values.first { $0.key == "Enabled" })
        XCTAssertEqual(enabled.path, "org.example.Probe/Info.plist")
        XCTAssertEqual(enabled.value.value, .boolValue(true))
        XCTAssertEqual(values.first { $0.key == "Count" }?.value.value, .intValue(7))
        XCTAssertEqual(Set(values.filter { $0.key == "welcome" }.map { "\($0.path)=\($0.value.stringValue)" }), [
            "org.example.Probe/en.lproj/Localizable.strings=Hello",
            "org.example.Probe.Share/en.lproj/Localizable.strings=Share"
        ])
        XCTAssertTrue(graph.edges.allSatisfy { if case .resource? = $0.kind { return false }; return true })
        XCTAssertNotNil(graph.header.options["resource_bundles"])
        XCTAssertEqual(graph.trailer.nodeCount, result.summary.nodes)
    }

    func testDifferentBuildVariantsWithOneBundleIdentityAreRejected() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("resource-identity-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let first = root.appendingPathComponent("Debug/Probe.app")
        let second = root.appendingPathComponent("Release/Probe.app")
        for bundle in [first, second] {
            try plist(["CFBundleIdentifier": "org.example.Probe"], at: bundle.appendingPathComponent("Info.plist"))
        }
        let writer = try IRWriter(url: root.appendingPathComponent("invalid.graphite-ir"), header: GraphiteIRHeader())
        let emitter = Emitter(writer: writer, model: IndexModel(), facts: [:], demangled: [:])
        XCTAssertThrowsError(try emitter.emit(resourceBundles: [first, second])) {
            XCTAssertTrue(String(describing: $0).contains("choose one build variant per identifier"))
        }
    }
}
