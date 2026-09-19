import Foundation
import XCTest
@testable import GraphiteIR

final class IRWriterTests: XCTestCase {
    private func temporaryFile() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("ir-\(UUID().uuidString).graphite-ir")
    }

    func testStreamLayoutStringsPrecedeTheirUsersAndTrailerCounts() throws {
        let url = temporaryFile()
        defer { try? FileManager.default.removeItem(at: url) }
        var header = GraphiteIRHeader()
        header.language = "swift"
        let writer = try IRWriter(url: url, header: header)
        let method = writer.methodRef(declaringClass: "M.T", name: "f(x:)", parameterTypes: ["Swift.Int"], returnType: "Swift.Void")
        try writer.addMethod(method)
        var constant = GraphiteIRStringConstant()
        constant.value = writer.intern("hello")
        let a = try writer.addNode(.stringConstant(constant))
        var site = GraphiteIRCallSite()
        site.caller = method
        site.callee = method
        site.arguments = [a]
        let b = try writer.addNode(.callSite(site))
        try writer.addDataFlow(from: a, to: b, .parameterPass)
        try writer.addTypeRelation(subtype: "M.T", supertype: "M.P", relation: .implements)
        try writer.addClassOrigin(className: "M.T", source: "M")
        try writer.addEnumValues(enumClass: "M.E", enumName: "a", values: [])
        try writer.addArtifactDependency(from: "M", to: "N", weight: 2)
        try writer.finish()
        try writer.finish()  // idempotent

        let chunks = try IRStream.chunks(of: try Data(contentsOf: url))
        let kinds = chunks.map { chunk -> String in
            switch chunk.chunk! {
            case .header: return "header"
            case .strings: return "strings"
            case .nodes: return "nodes"
            case .edges: return "edges"
            case .methods: return "methods"
            case .typeRelations: return "typeRelations"
            case .classOrigins: return "classOrigins"
            case .enumValues: return "enumValues"
            case .artifactDependencies: return "artifactDependencies"
            case .trailer: return "trailer"
            }
        }
        XCTAssertEqual(kinds, ["header", "strings", "nodes", "edges", "methods", "typeRelations", "classOrigins", "enumValues", "artifactDependencies", "trailer"])
        XCTAssertEqual(chunks[0].header.schemaVersion, irSchemaVersion)
        XCTAssertEqual(chunks[0].header.language, "swift")
        XCTAssertEqual(chunks[1].strings.values, ["M.T", "f(x:)", "Swift.Int", "Swift.Void", "hello", "M.P"])
        XCTAssertEqual(chunks[2].nodes.nodes.map(\.id), [0, 1])
        XCTAssertEqual(chunks[2].nodes.nodes[1].callSite.arguments, [0])
        XCTAssertEqual(chunks[3].edges.edges.first?.dataFlow.kind, .parameterPass)
        XCTAssertEqual(chunks[4].methods.methods.first?.parameterTypes.first?.name, 2)
        XCTAssertEqual(chunks[5].typeRelations.relations.first?.relation, .implements)
        XCTAssertEqual(chunks[6].classOrigins.origins.first?.source, "M")
        XCTAssertEqual(chunks[7].enumValues.entries.first?.enumName, "a")
        XCTAssertEqual(chunks[8].artifactDependencies.dependencies.first?.weight, 2)
        let trailer = chunks[9].trailer
        XCTAssertEqual(trailer.nodeCount, 2)
        XCTAssertEqual(trailer.edgeCount, 1)
        XCTAssertEqual(trailer.stringCount, 6)
        XCTAssertEqual(writer.nodeCount, 2)
        XCTAssertEqual(writer.stringCount, 6)
    }

    func testLargeBatchesAreSplitAndStringsFlushedFirst() throws {
        let url = temporaryFile()
        defer { try? FileManager.default.removeItem(at: url) }
        let writer = try IRWriter(url: url, header: GraphiteIRHeader())
        for i in 0..<(IRWriter.batchSize + 1) {
            var constant = GraphiteIRStringConstant()
            constant.value = writer.intern("s\(i)")
            try writer.addNode(.stringConstant(constant))
        }
        for i in 0..<IRWriter.batchSize {
            try writer.addDataFlow(from: UInt32(i), to: UInt32(i + 1), .assign)
        }
        try writer.finish()
        let chunks = try IRStream.chunks(of: try Data(contentsOf: url))
        var stringsSeen = 0
        for chunk in chunks {
            switch chunk.chunk! {
            case .strings(let batch): stringsSeen += batch.values.count
            case .nodes(let batch):
                for node in batch.nodes {
                    XCTAssertLessThan(Int(node.stringConstant.value), stringsSeen, "a node must not refer to a string that is still pending")
                }
            default: break
            }
        }
        XCTAssertEqual(chunks.filter { if case .nodes = $0.chunk! { return true } else { return false } }.count, 2)
        XCTAssertEqual(chunks.filter { if case .edges = $0.chunk! { return true } else { return false } }.count, 1)
        XCTAssertEqual(chunks.last?.trailer.nodeCount, UInt64(IRWriter.batchSize + 1))
    }

    func testMalformedStreams() throws {
        XCTAssertThrowsError(try IRStream.chunks(of: Data([0x05, 0x01]))) { XCTAssertEqual($0 as? IRError, .truncated) }
        XCTAssertThrowsError(try IRStream.chunks(of: Data([0x80]))) { XCTAssertEqual($0 as? IRError, .truncated) }
        XCTAssertThrowsError(try IRStream.chunks(of: Data(repeating: 0xFF, count: 12))) { XCTAssertEqual($0 as? IRError, .malformedLength) }
        XCTAssertEqual(try IRStream.chunks(of: Data()).count, 0)
        XCTAssertThrowsError(try IRWriter(url: URL(fileURLWithPath: "/nonexistent/dir/x.graphite-ir"), header: GraphiteIRHeader())) {
            XCTAssertEqual($0 as? IRError, .cannotOpen("/nonexistent/dir/x.graphite-ir"))
        }
        var data = Data()
        IRStream.appendVarint(300, to: &data)
        XCTAssertEqual(Array(data), [0xAC, 0x02])
        XCTAssertEqual(IRError.truncated.description, "IR stream is truncated")
        XCTAssertEqual(IRError.malformedLength.description, "IR stream has a malformed chunk length")
    }
}
