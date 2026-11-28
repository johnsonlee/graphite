import Foundation
import XCTest
@testable import GraphiteFrontend
@testable import GraphiteIR

/// The completeness evidence of an Apple build (docs/analysis-completeness.md, "The Apple
/// frontend"): which classes the build read, which methods have no body, and which calls the
/// graph does not close, from a package SwiftPM really builds and indexes.
final class CompletenessTests: XCTestCase {
    private func package(_ files: [String: String], run: (URL) throws -> Void) throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("graphite-completeness-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        for (path, text) in files {
            let file = root.appendingPathComponent(path)
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try text.write(to: file, atomically: true, encoding: .utf8)
        }
        try run(root)
    }

    func testClassesBodilessMethodsAndDispatchGaps() throws {
        try package([
            "Package.swift": """
            // swift-tools-version:5.10
            import PackageDescription
            let package = Package(name: "Dispatch", targets: [.target(name: "Dispatch")])
            """,
            "Sources/Dispatch/Dispatch.swift": """
            public protocol Performing {
                func perform()
                func idle()
                func rest()
            }
            public extension Performing {
                func idle() {}
                func rest() {}
            }
            public struct Custom: Performing {
                public func perform() {}
                public func idle() {}
            }
            open class Base: Performing {
                public init() {}
                open func perform() {}
                open func alone() {}
                public final func sealed() {}
            }
            public final class Leaf: Base {
                public override func perform() { super.perform(); sealed() }
            }
            public struct Value {
                public func read() -> Int { 1 }
            }
            public func drive(_ worker: Performing, _ base: Base, _ leaf: Leaf) {
                worker.perform()
                worker.idle()
                worker.rest()
                base.perform()
                base.alone()
                leaf.perform()
                base.sealed()
                _ = Value().read()
                print("done")
            }
            """,
        ]) { root in
            var options = BuildOptions()
            options.package = root.path
            let output = root.appendingPathComponent("dispatch.graphite-ir")
            _ = try Frontend(options: options).build(to: output)
            let graph = try DecodedIR(url: output)
            XCTAssertTrue(graph.trailer.hasCompleteness)
            let evidence = graph.trailer.completeness

            // Every type of the build, and the module that owns the free function, with the
            // module they were read from.
            XCTAssertEqual(
                Set(evidence.classes.map { "\($0.className) \($0.origin) \($0.status)" }),
                [
                    "Dispatch.Performing Dispatch analyzed", "Dispatch.Base Dispatch analyzed",
                    "Dispatch.Leaf Dispatch analyzed", "Dispatch.Value Dispatch analyzed",
                    "Dispatch.Custom Dispatch analyzed", "Dispatch Dispatch analyzed",
                ]
            )
            XCTAssertEqual(evidence.classes.count, 6, "a class is named once")

            // Only a requirement without a default is without a body. A requirement and its
            // default implementation are one method to the graph (one descriptor), and it has
            // a body; so do the overrides and the struct's method.
            // A member the compiler synthesized (the struct's initializer) is called and has no
            // declaration in the sources: the build holds no body for it, and says so.
            XCTAssertEqual(
                evidence.methods.map { "\(graph.signature($0.method)) \($0.status) \($0.message)" },
                [
                    "Dispatch.Performing.perform()() -> Swift.Void abstract a protocol requirement",
                    "Dispatch.Value.init()() -> Dispatch.Value noBody not declared in the indexed sources: synthesized by the compiler, or declared elsewhere",
                ]
            )

            // The calls of drive(), by callee: the gap each one leaves, or none.
            var gaps: [String: String] = [:]
            for (id, node) in graph.nodes {
                guard case .callSite(let site)? = node.kind, graph.str(site.caller.name).hasPrefix("drive") else { continue }
                let gap = evidence.callSiteGaps.first { $0.callSite == id }.map { "\($0.gap)" } ?? "none"
                gaps["\(graph.type(site.callee.declaringClass)).\(graph.str(site.callee.name))"] = gap
            }
            XCTAssertEqual(gaps, [
                // A requirement the build implements: no body is linked to the call.
                "Dispatch.Performing.perform()": "dispatchUnresolved",
                // A default implementation, which is the named method, and Custom's own: the other may run.
                "Dispatch.Performing.idle()": "dispatchPartial",
                // A default implementation nothing in the build replaces: the named body is the one that runs.
                "Dispatch.Performing.rest()": "none",
                // A body the build overrides (Leaf.perform): the named one is in the graph, the other may run.
                "Dispatch.Base.perform()": "dispatchPartial",
                // Dynamically dispatched, and nothing in the build overrides it.
                "Dispatch.Base.alone()": "none",
                // The override itself, a final method, a struct's method, an initializer and an SDK function.
                "Dispatch.Leaf.perform()": "none",
                "Dispatch.Base.sealed()": "none",
                "Dispatch.Value.read()": "none",
                "Dispatch.Value.init()": "none",
                "Swift.print(_:separator:terminator:)": "none",
            ])
            // `super.perform()` names Base.perform, which Leaf overrides, and is bound statically:
            // the named body is the one that runs, so the call leaves no gap.
            let superCall = try XCTUnwrap(graph.nodes.first { _, node in
                guard case .callSite(let site)? = node.kind else { return false }
                return graph.type(site.caller.declaringClass) == "Dispatch.Leaf" && graph.type(site.callee.declaringClass) == "Dispatch.Base"
                    && graph.str(site.callee.name) == "perform()"
            })
            XCTAssertFalse(evidence.callSiteGaps.contains { $0.callSite == superCall.key })
            // A gap names a call site of the stream, once.
            XCTAssertEqual(Set(evidence.callSiteGaps.map(\.callSite)).count, evidence.callSiteGaps.count)
            for gap in evidence.callSiteGaps {
                guard case .callSite? = graph.nodes[gap.callSite]?.kind else { return XCTFail("gap on node \(gap.callSite)") }
            }
        }
    }
}
