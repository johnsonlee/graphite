import Foundation
import XCTest
@testable import GraphiteFrontend
import GraphiteIR

final class FlowEmitterTests: XCTestCase {
    func testCVoidDoesNotBecomeAReturnValueButVoidPointerDoes() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("flow-c-void-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root.appendingPathComponent("Sources/CReset/include"), withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: root.appendingPathComponent("Sources/Probe"), withIntermediateDirectories: true)
        try """
        // swift-tools-version:5.10
        import PackageDescription
        let package = Package(name: "Probe", targets: [.target(name: "CReset"), .target(name: "Probe", dependencies: ["CReset"])])
        """.write(to: root.appendingPathComponent("Package.swift"), atomically: true, encoding: .utf8)
        try "void LegacyReset(int code); void *LegacyPointer(void);"
            .write(to: root.appendingPathComponent("Sources/CReset/include/CReset.h"), atomically: true, encoding: .utf8)
        try "void LegacyReset(int code) {} void *LegacyPointer(void) { return 0; }"
            .write(to: root.appendingPathComponent("Sources/CReset/Reset.c"), atomically: true, encoding: .utf8)
        try """
        import CReset
        public func reset(_ code: Int32) -> Any { return LegacyReset(code) }
        public func pointer() -> UnsafeMutableRawPointer? { return LegacyPointer() }
        """.write(to: root.appendingPathComponent("Sources/Probe/Probe.swift"), atomically: true, encoding: .utf8)
        var options = BuildOptions()
        options.package = root.path
        let output = root.appendingPathComponent("probe.ir")
        _ = try Frontend(options: options).build(to: output)
        let graph = try DecodedIR(url: output)
        let sites = graph.nodes.values.compactMap { node -> (UInt32, GraphiteIRCallSite)? in
            if case .callSite(let call)? = node.kind { return (node.id, call) }; return nil
        }
        let reset = try XCTUnwrap(sites.first { graph.str($0.1.callee.name) == "LegacyReset" })
        XCTAssertEqual(graph.type(reset.1.callee.returnType), "void")
        XCTAssertFalse(graph.edges.contains { $0.from == reset.0 && $0.dataFlow.kind == .returnValue })
        let pointer = try XCTUnwrap(sites.first { graph.str($0.1.callee.name) == "LegacyPointer" })
        XCTAssertEqual(graph.type(pointer.1.callee.returnType), "void *")
        XCTAssertTrue(graph.edges.contains { $0.from == pointer.0 && $0.dataFlow.kind == .returnValue })
    }

    func testCompilerIndexedParametersAndReturnKeepArgumentSlotsAndScope() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("flow-emitter-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root.appendingPathComponent("Sources/FlowProbe"), withIntermediateDirectories: true)
        try """
        // swift-tools-version:5.10
        import PackageDescription
        let package = Package(name: "FlowProbe", targets: [.target(name: "FlowProbe")])
        """.write(to: root.appendingPathComponent("Package.swift"), atomically: true, encoding: .utf8)
        try """
        public func sink(_ left: String, other right: String) -> String { left + right }
        public func wrap(_ key: String) -> String {
            return sink(key, other: key)
        }
        public func shadow(_ key: String) -> String {
            let key = "shadow"
            return sink(key, other: key)
        }
        public func scoped(_ key: String) -> String {
            return [key].map { element in sink(element, other: key) }.joined()
        }
        public func never(_ key: String) -> Never {
            return fatalError(key)
        }
        """.write(to: root.appendingPathComponent("Sources/FlowProbe/Probe.swift"), atomically: true, encoding: .utf8)
        var options = BuildOptions()
        options.package = root.path
        let out = root.appendingPathComponent("flow.graphite-ir")
        _ = try Frontend(options: options).build(to: out)
        let graph = try DecodedIR(url: out)
        let calls = graph.nodes.values.compactMap { node -> (UInt32, GraphiteIRCallSite)? in
            if case .callSite(let site)? = node.kind { return (node.id, site) }; return nil
        }
        let (siteID, wrap) = try XCTUnwrap(calls.first { graph.str($0.1.caller.name) == "wrap(_:)" })
        XCTAssertEqual(wrap.arguments.count, 2)
        XCTAssertNotEqual(wrap.arguments[0], wrap.arguments[1], "each explicit source argument keeps its original reference node")
        XCTAssertEqual(wrap.arguments.map(graph.literal), ["<key>", "<key>"])
        let definitions = graph.edges.filter { wrap.arguments.contains($0.to) && $0.dataFlow.kind == .assign }
        XCTAssertEqual(definitions.count, 2)
        XCTAssertEqual(Set(definitions.map(\.from)).count, 1)
        let parameterID = try XCTUnwrap(definitions.first?.from)
        guard case .parameter(let parameter)? = graph.nodes[parameterID]?.kind else {
            return XCTFail("an indexed formal parameter must define each source reference")
        }
        XCTAssertEqual(parameter.index, 0)
        XCTAssertEqual(graph.type(parameter.type), "Swift.String")
        XCTAssertEqual(parameter.method, wrap.caller)
        for argument in wrap.arguments {
            XCTAssertEqual(graph.edges.filter { $0.from == argument && $0.to == siteID }.map(\.dataFlow.kind), [.parameterPass])
        }
        let returnID = try XCTUnwrap(graph.edges.first { $0.from == siteID && $0.dataFlow.kind == .returnValue }?.to)
        guard case .return(let returned)? = graph.nodes[returnID]?.kind else { return XCTFail("missing wrapper return") }
        XCTAssertEqual(returned.method, wrap.caller)
        let shadow = try XCTUnwrap(calls.first { graph.str($0.1.caller.name) == "shadow(_:)" }?.1)
        XCTAssertEqual(graph.type(shadow.caller.declaringClass), "FlowProbe")
        XCTAssertEqual(graph.str(shadow.callee.name), "sink(_:other:)")
        XCTAssertEqual(shadow.arguments.count, 2)
        XCTAssertFalse(graph.edges.contains { shadow.arguments.contains($0.to) && $0.dataFlow.kind == .assign },
                       "a shadowing local must not be assigned from the outer formal parameter")
        XCTAssertTrue(shadow.arguments.allSatisfy { if case .localVariable? = graph.nodes[$0]?.kind { return true }; return false })
        XCTAssertFalse(graph.edges.contains { edge in
            guard case .parameter(let p)? = graph.nodes[edge.from]?.kind,
                  graph.str(p.method.name) == "scoped(_:)" else { return false }
            return true
        }, "the closure's capture and local binding need independent analysis")
        XCTAssertEqual(graph.header.options["source_dataflow"], "bound-parameters,direct-call-returns")
        XCTAssertFalse(graph.nodes.values.contains { node in
            if case .return(let value)? = node.kind { return graph.str(value.method.name) == "never(_:)" }
            return false
        }, "Never does not produce a returned value")
    }
}
