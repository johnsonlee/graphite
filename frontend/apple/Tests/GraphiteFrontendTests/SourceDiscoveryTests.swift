import Foundation
import XCTest
@testable import GraphiteFrontend
@testable import GraphiteIR

final class SourceDiscoveryTests: XCTestCase {
    func testIndexedFrameworkHeaderCopySuppliesTheDeclaredSignature() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("graphite-header-copy-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let original = root.appendingPathComponent("Sources/Model.h")
        let compiled = root.appendingPathComponent("Build/Model.framework/Headers/Model.h")
        for url in [original, compiled] {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        }
        // The compiled copy is the source of truth for the index coordinates. A
        // same-basename source header must not supply a different signature.
        try "@protocol Model\n- (int)count:(int)value;\n@end".write(to: original, atomically: true, encoding: .utf8)
        try "@protocol Model\n- (long)count:(long)value;\n@end".write(to: compiled, atomically: true, encoding: .utf8)
        let sourcePath = SourcePosition.canonical(original.path)
        let compiledPath = SourcePosition.canonical(compiled.path)
        let owner = SymbolInfo(usr: "c:objc(pl)Model", name: "Model", kind: .protocol)
        let method = SymbolInfo(usr: "c:objc(pl)Model(im)count:", name: "count:", kind: .instanceMethod)
        var model = IndexModel()
        model.types = [TypeDecl(symbol: owner, kind: .protocol, module: "", position: SourcePosition(path: compiledPath, line: 1, column: 11), container: nil)]
        model.members = [MemberDecl(symbol: method, kind: .method, isStatic: false, module: "", position: SourcePosition(path: compiledPath, line: 2, column: 9), container: owner.usr, overrides: [])]
        let files = Frontend.sourceFilesForSyntax([sourcePath], model: model)
        XCTAssertEqual(files, [compiledPath, sourcePath].sorted())
        let facts = try Dictionary(uniqueKeysWithValues: files.map { ($0, try ObjectiveCSyntax.parse(path: $0)) })
        let output = root.appendingPathComponent("model.graphite-ir")
        let writer = try IRWriter(url: output, header: GraphiteIRHeader())
        _ = try Emitter(writer: writer, model: model, facts: facts, demangled: [:]).emit()
        let graph = try DecodedIR(url: output)
        XCTAssertEqual(graph.methods.map(graph.signature), ["Model.count:(long) -> long"])
    }

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
