import Foundation
import XCTest
@testable import GraphiteFrontend
import GraphiteIR

final class AppleResourcesTests: XCTestCase {
    private let fm = FileManager.default

    private func fixture(_ run: (URL) throws -> Void) throws {
        let root = fm.temporaryDirectory.appendingPathComponent("apple-resources-\(UUID())")
        let app = root.appendingPathComponent("Example.app")
        try fm.createDirectory(at: app, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: root) }
        try run(app)
    }

    private func write(_ value: Any, to file: URL, format: PropertyListSerialization.PropertyListFormat = .binary) throws {
        try fm.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try PropertyListSerialization.data(fromPropertyList: value, format: format, options: 0).write(to: file)
    }

    func testBundleScopeTypedMetadataAndLocalizedTables() throws {
        try fixture { app in
            let id = "org.example.App"
            try write(["CFBundleIdentifier": id, "Numbers": [true, 1, 1.0, -7, 0] as [Any],
                       "URLTypes": [["Schemes": ["example"], "Name": "Demo"]],
                       "WithDate": ["preserve": "whole", "unsupported": Date()] as [String: Any],
                       "Bytes": Data([0, 255])], to: app.appendingPathComponent("Info.plist"))
            let english = app.appendingPathComponent("en.lproj/Localizable.strings")
            try fm.createDirectory(at: english.deletingLastPathComponent(), withIntermediateDirectories: true)
            try "/* comment */\n\"welcome\" = \"Hello\";".write(to: english, atomically: true, encoding: .utf16)
            try write(["welcome": "Bonjour"], to: app.appendingPathComponent("fr.lproj/Localizable.strings"), format: .xml)
            try write(["welcome": "Nested must not leak"], to: app.appendingPathComponent("Items.bundle/en.lproj/Localizable.strings"))
            let ext = app.appendingPathComponent("PlugIns/Example.appex")
            try write(["CFBundleIdentifier": "org.example.App.Share"], to: ext.appendingPathComponent("Info.plist"))
            try write(["welcome": "Share"], to: ext.appendingPathComponent("en.lproj/Localizable.strings"))
            try fm.createSymbolicLink(at: app.appendingPathComponent("alias.lproj"), withDestinationURL: ext.appendingPathComponent("en.lproj"))
            try fm.createSymbolicLink(at: english.deletingLastPathComponent().appendingPathComponent("Foreign.strings"),
                                      withDestinationURL: ext.appendingPathComponent("en.lproj/Localizable.strings"))

            var files: [GraphiteIRResourceFile] = []
            var entries: [GraphiteIRResourceValue] = []
            let omissions = try AppleResources.read(bundle: app) { files.append($0); entries += $1 }
            XCTAssertEqual(files.map(\.path), ["\(id)/Info.plist", "\(id)/en.lproj/Localizable.strings", "\(id)/fr.lproj/Localizable.strings"])
            XCTAssertEqual(files.map(\.source), [id, id, id])
            XCTAssertFalse(files[0].hasProfile)
            XCTAssertEqual(files.map(\.format), ["plist", "strings", "strings"])
            XCTAssertEqual(files.dropFirst().map(\.profile), ["en", "fr"])
            XCTAssertEqual(omissions.map(\.key), ["Bytes", "WithDate"])
            XCTAssertEqual(omissions.map(\.path), ["\(id)/Info.plist", "\(id)/Info.plist"])
            XCTAssertEqual(omissions.map(\.reason), ["plist data has no IR value type", "plist date has no IR value type"])
            XCTAssertFalse(entries.contains { ["WithDate", "Bytes"].contains($0.key) })
            let numbers = try XCTUnwrap(entries.first { $0.key == "Numbers" }).value.listValue.values
            XCTAssertEqual(numbers.map(\.value), [.boolValue(true), .intValue(1), .doubleValue(1.0), .intValue(-7), .intValue(0)])
            let nested = try XCTUnwrap(entries.first { $0.key == "URLTypes" }).value.listValue.values[0].mapValue.entries
            XCTAssertEqual(nested.map(\.name), ["Name", "Schemes"])
            XCTAssertEqual(nested[0].value.value, .stringValue("Demo"))
            XCTAssertEqual(nested[1].value.listValue.values.map(\.value), [.stringValue("example")])
            let localized = entries.filter { $0.key == "welcome" }
            XCTAssertEqual(localized.map(\.profile), ["en", "fr"])
            XCTAssertEqual(localized.map(\.path), Array(files.dropFirst().map(\.path)))
            XCTAssertEqual(localized.map { $0.value.value }, [.stringValue("Hello"), .stringValue("Bonjour")])

            var share: [GraphiteIRResourceValue] = []
            try AppleResources.read(bundle: ext) { _, values in share += values }
            XCTAssertEqual(share.map(\.path), ["org.example.App.Share/Info.plist", "org.example.App.Share/en.lproj/Localizable.strings"])
            XCTAssertEqual(share.last?.value.value, .stringValue("Share"))
        }
    }

    func testInvalidBundlesAndMalformedTablesAreIdentified() throws {
        try fixture { app in
            func read(_ url: URL) throws { try AppleResources.read(bundle: url) { _, _ in } }
            XCTAssertThrowsError(try read(app.deletingLastPathComponent()))
            let info = app.appendingPathComponent("Info.plist")
            try write(["CFBundleIdentifier": 3], to: info)
            XCTAssertThrowsError(try read(app)) { XCTAssertTrue("\($0)".contains("CFBundleIdentifier")) }
            try write(["CFBundleIdentifier": "org.example.App"], to: info)
            let table = app.appendingPathComponent("en.lproj/Broken.strings")
            try write(["bad": 42], to: table)
            XCTAssertThrowsError(try read(app)) { XCTAssertTrue("\($0)".contains("expected a string value for bad in org.example.App/en.lproj/Broken.strings")) }
            try write(["not a dictionary"], to: table)
            XCTAssertThrowsError(try read(app)) { XCTAssertTrue("\($0)".contains("expected a property list dictionary")) }
            try Data([255, 0, 17]).write(to: table)
            XCTAssertThrowsError(try read(app)) { XCTAssertTrue("\($0)".contains("Broken.strings")) }
        }
    }

    /// Opt-in correctness oracle for the locally built Signal 7.19.1.208 simulator product.
    func testBuiltSignalBundle() throws {
        guard let path = ProcessInfo.processInfo.environment["GRAPHITE_TEST_SIGNAL_APP"] else {
            throw XCTSkip("set GRAPHITE_TEST_SIGNAL_APP to the built Signal 7.19.1.208 .app")
        }
        let id = "org.whispersystems.signal"
        var metadata: [String: GraphiteIRValue] = [:]
        var title: String?
        try AppleResources.read(bundle: URL(fileURLWithPath: path)) { file, values in
            XCTAssertEqual(file.source, id)
            if file.path == "\(id)/Info.plist" {
                metadata = Dictionary(uniqueKeysWithValues: values.map { ($0.key, $0.value) })
            }
            if file.path == "\(id)/en.lproj/Localizable.strings" {
                title = values.first { $0.profile == "en" && $0.key == "DATABASE_VIEW_OVERLAY_TITLE" }?.value.stringValue
            }
        }
        XCTAssertEqual(metadata["CFBundleIdentifier"]?.value, .stringValue(id))
        XCTAssertEqual(metadata["CFBundlePackageType"]?.value, .stringValue("APPL"))
        XCTAssertEqual(title, "Optimizing Database")
    }
}
