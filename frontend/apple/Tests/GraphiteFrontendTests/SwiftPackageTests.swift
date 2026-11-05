import Foundation
import XCTest
@testable import GraphiteFrontend

final class SwiftPackageTests: XCTestCase {
    func testBuiltTargetsContributeTheirSourcesWhereverTheManifestPutsThem() throws {
        let describe = """
        {
          "name": "AcmeShop",
          "path": "/src/AcmeShop",
          "targets": [
            {"name": "AcmeShop", "type": "library", "path": "Sources/AcmeShop", "sources": ["CartService.swift", "Models.swift", "Shaders.metal"]},
            {"name": "Tool", "type": "executable", "path": "CommandLineTool", "sources": ["Tool.swift"]},
            {"name": "Macros", "type": "macro", "path": "Sources/Macros", "sources": ["Macro.swift"]},
            {"name": "AcmeShopTests", "type": "test", "path": "Tests/AcmeShopTests", "sources": ["CartServiceTests.swift"]},
            {"name": "Lint", "type": "plugin", "path": "Plugins/Lint", "sources": ["Plugin.swift"]},
            {"name": "CLib", "type": "library", "module_type": "ClangTarget", "path": "Sources/CLib", "sources": ["lib.c"]},
            {"name": "Vendored", "type": "binary", "path": "Vendored.xcframework"},
            {"name": "Absolute", "type": "library", "path": "/elsewhere/Absolute", "sources": ["A.swift"]}
          ]
        }
        """
        XCTAssertEqual(
            try SwiftPackage.sourceFiles(fromDescribe: Data(describe.utf8), root: "/src/AcmeShop"),
            [
                "/src/AcmeShop/Sources/AcmeShop/CartService.swift",
                "/src/AcmeShop/Sources/AcmeShop/Models.swift",
                "/src/AcmeShop/CommandLineTool/Tool.swift",
                "/src/AcmeShop/Sources/Macros/Macro.swift",
                "/elsewhere/Absolute/A.swift",
            ]
        )
        XCTAssertEqual(
            SwiftPackage.describeArguments(root: "/src/AcmeShop"),
            ["package", "--package-path", "/src/AcmeShop", "describe", "--type", "json"]
        )
    }

    func testUnreadableDescribeOutputIsAnError() {
        for text in ["", "not json", "[]", "{\"targets\": 3}"] {
            XCTAssertThrowsError(try SwiftPackage.sourceFiles(fromDescribe: Data(text.utf8), root: "/src")) {
                XCTAssertEqual("\($0)", FrontendError.packageUnreadable.description)
            }
        }
        XCTAssertEqual(try SwiftPackage.sourceFiles(fromDescribe: Data("{\"targets\": []}".utf8), root: "/src"), [])
        XCTAssertEqual(FrontendError.describeFailed(2).description, "swift package describe failed with exit code 2")
    }

    func testClangHeadersRespectTargetBoundariesAndManifestExcludes() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("graphite-headers-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let paths = [
            "Custom/Legacy/Legacy.m", "Custom/Legacy/Public/Legacy.h", "Custom/Legacy/Private/Details.h",
            "Custom/Legacy/Public/Excluded.h", "Custom/Legacy/Omitted/Hidden.h", "Custom/Legacy/OmittedExtra/Kept.h",
            "Custom/Unbuilt/Other.h", "Tests/LegacyTests/Test.h", "Unrelated.h",
        ]
        for path in paths {
            let file = root.appendingPathComponent(path)
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try "".write(to: file, atomically: true, encoding: .utf8)
        }
        let describe = Data("""
        {"targets": [
            {"name":"Legacy", "type":"library", "module_type":"ClangTarget", "path":"Custom/Legacy", "sources":["Legacy.m"]},
            {"name":"LegacyTests", "type":"test", "module_type":"ClangTarget", "path":"Tests/LegacyTests", "sources":[]}
        ]}
        """.utf8)
        let manifest = Data("""
        {"targets": [{"name":"Legacy", "path":"Custom/Legacy", "publicHeadersPath":"Public", "exclude":["Public/Excluded.h", "Omitted"]}]}
        """.utf8)
        XCTAssertTrue(try SwiftPackage.hasClangTargets(fromDescribe: describe))
        let files = try SwiftPackage.sourceFiles(fromDescribe: describe, root: root.path, manifest: manifest)
        XCTAssertEqual(Set(files.map(SourcePosition.canonical)), Set([
            "Custom/Legacy/Legacy.m", "Custom/Legacy/Public/Legacy.h", "Custom/Legacy/Private/Details.h",
            "Custom/Legacy/OmittedExtra/Kept.h",
        ].map { SourcePosition.canonical(root.appendingPathComponent($0).path) }))
    }
}
