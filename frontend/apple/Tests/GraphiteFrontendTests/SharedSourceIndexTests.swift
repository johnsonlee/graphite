import Foundation
import IndexStoreDB
import XCTest
@testable import GraphiteFrontend
@testable import GraphiteIR

final class SharedSourceIndexTests: XCTestCase {
    func testBothModulesOfASharedSwiftSourceSurviveAcrossCheckoutRoots() throws {
        let temporary = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: temporary) }
        let first = try compileAndCheck(at: temporary.appendingPathComponent("one"))
        let second = try compileAndCheck(at: temporary.appendingPathComponent("other/checkout"))
        XCTAssertEqual(first, second)
    }

    func testUnreadableReferencedRecordFailsBeforeReplacingTheIR() throws {
        let fm = FileManager.default
        let root = fm.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? fm.removeItem(at: root) }
        let sources = root.appendingPathComponent("Sources/Probe")
        try fm.createDirectory(at: sources, withIntermediateDirectories: true)
        try """
        // swift-tools-version:5.10
        import PackageDescription
        let package = Package(name: "Probe", targets: [.target(name: "Probe")])
        """.write(to: root.appendingPathComponent("Package.swift"), atomically: true, encoding: .utf8)
        let victim = sources.appendingPathComponent("Victim.swift")
        let kept = sources.appendingPathComponent("Kept.swift")
        try "public func victim() -> Int { kept() }".write(to: victim, atomically: true, encoding: .utf8)
        try "public func kept() -> Int { 42 }".write(to: kept, atomically: true, encoding: .utf8)
        try "// no symbols".write(to: sources.appendingPathComponent("Empty.swift"), atomically: true, encoding: .utf8)
        try "import Swift".write(to: sources.appendingPathComponent("Import.swift"), atomically: true, encoding: .utf8)
        var options = BuildOptions()
        options.package = root.path
        let ir = root.appendingPathComponent("graph.ir")
        let built = try Frontend(options: options).build(to: ir)
        XCTAssertEqual(built.files, 4, "empty and import-only files are valid alongside indexed source")
        let original = try Data(contentsOf: ir)
        let library = try XCTUnwrap(IndexReader.locateLibrary())
        let raw = try RawIndexReader(store: built.indexStore, library: library)
        let path = SourcePosition.canonical(victim.path)
        let refs = try XCTUnwrap(raw.records(files: [path])[path])
        XCTAssertEqual(refs.count, 1)
        let name = try XCTUnwrap(refs.keys.first?.name)
        let store = URL(fileURLWithPath: built.indexStore)
        let files = try XCTUnwrap(fm.enumerator(at: store, includingPropertiesForKeys: nil))
        let matching = files.compactMap { $0 as? URL }.filter { $0.lastPathComponent == name }
        XCTAssertEqual(matching.count, 1)
        let record = try XCTUnwrap(matching.first)
        try fm.removeItem(at: record)
        options.skipBuild = true
        func assertRefused() throws {
            XCTAssertThrowsError(try Frontend(options: options).build(to: ir)) { error in
                XCTAssertTrue(String(describing: error).contains(name), "unexpected diagnostic: \(error)")
            }
            XCTAssertEqual(try Data(contentsOf: ir), original)
            let reader = try IndexReader(storePath: built.indexStore, libraryPath: library)
            XCTAssertFalse(try reader.read(files: [kept.path]).members.isEmpty)
        }
        try assertRefused()
        try Data("not an index record".utf8).write(to: record)
        try assertRefused()
    }

    private func compileAndCheck(at root: URL) throws -> Set<String> {
        let fm = FileManager.default
        for target in ["Core", "Left", "Right"] {
            try fm.createDirectory(at: root.appendingPathComponent("Sources/\(target)"), withIntermediateDirectories: true)
        }
        try """
        // swift-tools-version:5.10
        import PackageDescription
        let package = Package(name: "Shared", targets: [
            .target(name: "Core"), .target(name: "Left", dependencies: ["Core"]),
            .target(name: "Right", dependencies: ["Core"]),
        ])
        """.write(to: root.appendingPathComponent("Package.swift"), atomically: true, encoding: .utf8)
        let common = root.appendingPathComponent("Sources/Core/Core.swift")
        try "public func common(_ value: String) -> String { value }"
            .write(to: common, atomically: true, encoding: .utf8)
        let shared = root.appendingPathComponent("Sources/Left/Shared.swift")
        try """
        import Core
        @available(macOS 10.15, *)
        public struct Shared {
            public let field: String
            public func go(_ value: String) -> String { return common(value) }
        }
        """.write(to: shared, atomically: true, encoding: .utf8)
        let alias = root.appendingPathComponent("Sources/Right/Shared.swift")
        try fm.createSymbolicLink(atPath: alias.path, withDestinationPath: "../Left/Shared.swift")
        let swift = try XCTUnwrap(Frontend.locateSwift(environment: ProcessInfo.processInfo.environment))
        let build = Process()
        build.executableURL = URL(fileURLWithPath: swift)
        build.arguments = ["build", "--package-path", root.path, "--enable-index-store", "--jobs", "2"]
        try build.run()
        build.waitUntilExit()
        XCTAssertEqual(build.terminationStatus, 0)
        let library = try XCTUnwrap(IndexReader.locateLibrary())
        let store = root.appendingPathComponent(".build/debug/index/store").path
        let reader = try IndexReader(storePath: store, libraryPath: library)
        let model = try reader.read(files: [shared.path, alias.path, shared.path, common.path])
        XCTAssertEqual(model.filesWithOccurrences.count, 2)
        let types = model.types.filter { $0.symbol.name == "Shared" }
        let fields = model.members.filter { $0.symbol.name == "field" }
        let calls = model.calls.filter { $0.callee.name == "common(_:)" }
        XCTAssertEqual(types.map(\.module).sorted(), ["Left", "Right"])
        XCTAssertEqual(fields.map(\.module).sorted(), ["Left", "Right"])
        XCTAssertEqual(calls.map(\.module).sorted(), ["Left", "Right"])
        XCTAssertEqual(Set(calls.compactMap { $0.caller?.usr }).count, 2)
        XCTAssertEqual(Set(calls.map(\.callee.usr)).count, 1)
        XCTAssertEqual(Set(calls.map(\.position)).count, 1)

        // A single-module file still has exactly the occurrence and relation
        // USRs produced by IndexStoreDB's established per-file operation.
        let single = try reader.read(files: [common.path])
        let dbPath = root.appendingPathComponent("comparison-db").path
        let old = try IndexStoreDB(storePath: store, databasePath: dbPath,
                                   library: IndexStoreLibrary(dylibPath: library),
                                   waitUntilDoneInitializing: true, readonly: false, listenToUnitEvents: false)
        old.pollForUnitChangesAndWait(isInitialScan: true)
        let oldOccurrences = old.symbolOccurrences(inFilePath: SourcePosition.canonical(common.path))
        let oldUSRs = Set(oldOccurrences.flatMap { [$0.symbol.usr] + $0.relations.map(\.symbol.usr) })
        XCTAssertFalse(oldUSRs.isEmpty)
        XCTAssertEqual(Set(single.symbols.keys), oldUSRs)

        var options = BuildOptions()
        options.package = root.path
        options.skipBuild = true
        let ir = root.appendingPathComponent("graph.ir")
        _ = try Frontend(options: options).build(to: ir)
        let graph = try DecodedIR(url: ir)
        let emitted = graph.callSites.filter { graph.str($0.callee.name) == "common(_:)" }
        XCTAssertEqual(emitted.map { graph.type($0.caller.declaringClass) }.sorted(), ["Left.Shared", "Right.Shared"])
        XCTAssertEqual(emitted.map { graph.type($0.callee.declaringClass) }, ["Core", "Core"])
        try checkFlow(in: graph)
        let annotations = graph.nodes.values.compactMap { node -> String? in
            guard case .annotation(let attribute) = node.kind, attribute.name == "available" else { return nil }
            return "\(attribute.className)#\(attribute.memberName)"
        }
        XCTAssertEqual(annotations.sorted(), ["Left.Shared#<class>", "Right.Shared#<class>"])
        let fieldOwners = graph.nodes.values.compactMap { node -> String? in
            guard case .field(let field) = node.kind, graph.str(field.field.name) == "field" else { return nil }
            return graph.type(field.field.declaringClass)
        }
        XCTAssertEqual(fieldOwners.sorted(), ["Left.Shared", "Right.Shared"])
        return Set(types.map(\.symbol.usr) + fields.map(\.symbol.usr) +
                   calls.map { "\($0.caller?.usr ?? "") -> \($0.callee.usr)" })
    }

    private func checkFlow(in graph: DecodedIR) throws {
        for node in graph.nodes.values {
            guard case .callSite(let site)? = node.kind, graph.str(site.callee.name) == "common(_:)" else { continue }
            XCTAssertEqual(site.arguments.count, 1)
            let reference = try XCTUnwrap(site.arguments.first)
            guard case .localVariable(let local)? = graph.nodes[reference]?.kind else {
                return XCTFail("the source argument must preserve its original local reference")
            }
            XCTAssertEqual(local.method, site.caller)
            let definitions = graph.edges.filter { $0.to == reference && $0.dataFlow.kind == .assign }
            XCTAssertEqual(definitions.count, 1)
            let parameterID = try XCTUnwrap(definitions.first?.from)
            guard case .parameter(let parameter)? = graph.nodes[parameterID]?.kind else {
                return XCTFail("the shared source argument must bind to a formal parameter")
            }
            XCTAssertEqual(parameter.index, 0)
            XCTAssertEqual(parameter.method, site.caller)
            XCTAssertEqual(graph.edges.filter { $0.from == reference && $0.dataFlow.kind == .parameterPass }.map(\.to), [node.id])
            let returns = graph.edges.filter { $0.from == node.id && $0.dataFlow.kind == .returnValue }
            XCTAssertEqual(returns.count, 1)
            let returnID = try XCTUnwrap(returns.first?.to)
            guard case .return(let returned)? = graph.nodes[returnID]?.kind else {
                return XCTFail("each module must return its own call's result")
            }
            XCTAssertEqual(returned.method, site.caller)
        }
    }
}
