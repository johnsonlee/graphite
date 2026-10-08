import Foundation
import XCTest
@testable import GraphiteFrontend

final class SourceDiscoveryTests: XCTestCase {
    func testHiddenFilesDoNotHideNeighboringSourceDirectories() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("graphite-discovery-\(UUID().uuidString)")
        let manager = FileManager.default
        try manager.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? manager.removeItem(at: root) }
        // Signal has ordinary .gitignore/.travis.yml files beside Protocols. Calling
        // skipDescendants for those files skips a later directory on macOS.
        for hidden in [".gitignore", ".travis.yml", ".hidden.swift"] {
            try "ignored".write(to: root.appendingPathComponent(hidden), atomically: true, encoding: .utf8)
        }
        var expected: [String] = []
        for directory in ["Protocols", "Models", "Views", "Services", "GRDB.swift"] {
            let folder = root.appendingPathComponent(directory)
            try manager.createDirectory(at: folder, withIntermediateDirectories: true)
            for file in ["ProfileManagerProtocol.h", "ProfileManager.swift", "ProfileManager.m"] {
                let url = folder.appendingPathComponent(file)
                try "".write(to: url, atomically: true, encoding: .utf8)
                expected.append(SourcePosition.canonical(url.path))
            }
        }
        for directory in [".build", ".hidden"] {
            let folder = root.appendingPathComponent(directory)
            try manager.createDirectory(at: folder, withIntermediateDirectories: true)
            try "".write(to: folder.appendingPathComponent("Excluded.swift"), atomically: true, encoding: .utf8)
        }
        XCTAssertEqual(try Frontend.sourceFiles(under: [root.path]), expected.sorted())
    }
}
