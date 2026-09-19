import Foundation
import SwiftProtobuf

/// The schema version this writer produces (`ir/graphite_ir.proto`).
public let irSchemaVersion: UInt32 = 1

public enum IRError: Error, CustomStringConvertible, Equatable {
    case cannotOpen(String)
    case truncated
    case malformedLength

    public var description: String {
        switch self {
        case .cannotOpen(let path): return "cannot open \(path) for writing"
        case .truncated: return "IR stream is truncated"
        case .malformedLength: return "IR stream has a malformed chunk length"
        }
    }
}

/// Writes a Graph IR stream chunk by chunk.
///
/// Strings are interned and flushed ahead of the first chunk that refers to them, node
/// ids are handed out densely from 0, and nothing but the current batch is held in
/// memory. Call ``finish()`` to write the trailer; a stream without one is truncated.
public final class IRWriter {
    public static let batchSize = 4096

    private let handle: FileHandle
    private var strings: [String: UInt32] = [:]
    private var pendingStrings: [String] = []
    private var nodes: [GraphiteIRNode] = []
    private var edges: [GraphiteIREdge] = []
    private var methods: [GraphiteIRMethodRef] = []
    private var typeRelations: [GraphiteIRTypeRelationEntry] = []
    private var classOrigins: [GraphiteIRClassOrigin] = []
    private var enumValues: [GraphiteIREnumValueEntry] = []
    private var artifacts: [GraphiteIRArtifactDependency] = []
    private var finished = false

    public private(set) var nodeCount: UInt64 = 0
    public private(set) var edgeCount: UInt64 = 0
    public var stringCount: UInt64 { UInt64(strings.count) }

    public init(url: URL, header: GraphiteIRHeader) throws {
        let path = url.path
        guard FileManager.default.createFile(atPath: path, contents: nil),
              let handle = FileHandle(forWritingAtPath: path) else {
            throw IRError.cannotOpen(path)
        }
        self.handle = handle
        var header = header
        if header.schemaVersion == 0 { header.schemaVersion = irSchemaVersion }
        try write(chunk: .header(header))
    }

    // MARK: Interning

    public func intern(_ value: String) -> UInt32 {
        if let id = strings[value] { return id }
        let id = UInt32(strings.count)
        strings[value] = id
        pendingStrings.append(value)
        return id
    }

    public func typeRef(_ name: String, arguments: [GraphiteIRTypeRef] = []) -> GraphiteIRTypeRef {
        var ref = GraphiteIRTypeRef()
        ref.name = intern(name)
        ref.typeArguments = arguments
        return ref
    }

    public func methodRef(
        declaringClass: String, name: String, parameterTypes: [String], returnType: String
    ) -> GraphiteIRMethodRef {
        var ref = GraphiteIRMethodRef()
        ref.declaringClass = typeRef(declaringClass)
        ref.name = intern(name)
        ref.parameterTypes = parameterTypes.map { typeRef($0) }
        ref.returnType = typeRef(returnType)
        return ref
    }

    public func fieldRef(declaringClass: String, name: String, type: String) -> GraphiteIRFieldRef {
        var ref = GraphiteIRFieldRef()
        ref.declaringClass = typeRef(declaringClass)
        ref.name = intern(name)
        ref.type = typeRef(type)
        return ref
    }

    // MARK: Graph elements

    /// Adds a node and returns its id.
    @discardableResult
    public func addNode(_ kind: GraphiteIRNode.OneOf_Kind) throws -> UInt32 {
        var node = GraphiteIRNode()
        node.id = UInt32(nodeCount)
        node.kind = kind
        nodeCount += 1
        nodes.append(node)
        if nodes.count >= IRWriter.batchSize { try flushNodes() }
        return node.id
    }

    public func addEdge(from: UInt32, to: UInt32, kind: GraphiteIREdge.OneOf_Kind) throws {
        var edge = GraphiteIREdge()
        edge.from = from
        edge.to = to
        edge.kind = kind
        edgeCount += 1
        edges.append(edge)
        if edges.count >= IRWriter.batchSize { try flushEdges() }
    }

    public func addDataFlow(from: UInt32, to: UInt32, _ kind: GraphiteIRDataFlowKind) throws {
        var flow = GraphiteIRDataFlowEdge()
        flow.kind = kind
        try addEdge(from: from, to: to, kind: .dataFlow(flow))
    }

    public func addMethod(_ method: GraphiteIRMethodRef) throws {
        methods.append(method)
        if methods.count >= IRWriter.batchSize { try flushMethods() }
    }

    public func addTypeRelation(subtype: String, supertype: String, relation: GraphiteIRTypeRelation) throws {
        var entry = GraphiteIRTypeRelationEntry()
        entry.subtype = typeRef(subtype)
        entry.supertype = typeRef(supertype)
        entry.relation = relation
        typeRelations.append(entry)
        if typeRelations.count >= IRWriter.batchSize { try flushTypeRelations() }
    }

    public func addClassOrigin(className: String, source: String) throws {
        var origin = GraphiteIRClassOrigin()
        origin.className = className
        origin.source = source
        classOrigins.append(origin)
        if classOrigins.count >= IRWriter.batchSize { try flushClassOrigins() }
    }

    public func addEnumValues(enumClass: String, enumName: String, values: [GraphiteIRValue]) throws {
        var entry = GraphiteIREnumValueEntry()
        entry.enumClass = enumClass
        entry.enumName = enumName
        entry.values = values
        enumValues.append(entry)
        if enumValues.count >= IRWriter.batchSize { try flushEnumValues() }
    }

    public func addArtifactDependency(from: String, to: String, weight: UInt32) throws {
        var dependency = GraphiteIRArtifactDependency()
        dependency.fromArtifact = from
        dependency.toArtifact = to
        dependency.weight = weight
        artifacts.append(dependency)
        if artifacts.count >= IRWriter.batchSize { try flushArtifacts() }
    }

    /// Flushes every pending batch, writes the trailer and closes the file.
    public func finish() throws {
        guard !finished else { return }
        try flushNodes()
        try flushEdges()
        try flushMethods()
        try flushTypeRelations()
        try flushClassOrigins()
        try flushEnumValues()
        try flushArtifacts()
        try flushStrings()
        var trailer = GraphiteIRTrailer()
        trailer.nodeCount = nodeCount
        trailer.edgeCount = edgeCount
        trailer.stringCount = stringCount
        try write(chunk: .trailer(trailer))
        try handle.close()
        finished = true
    }

    // MARK: Flushing

    private func flushStrings() throws {
        guard !pendingStrings.isEmpty else { return }
        var batch = GraphiteIRStringBatch()
        batch.values = pendingStrings
        pendingStrings.removeAll(keepingCapacity: true)
        try write(chunk: .strings(batch))
    }

    private func flushNodes() throws {
        guard !nodes.isEmpty else { return }
        var batch = GraphiteIRNodeBatch()
        batch.nodes = nodes
        nodes.removeAll(keepingCapacity: true)
        try write(chunk: .nodes(batch))
    }

    private func flushEdges() throws {
        guard !edges.isEmpty else { return }
        var batch = GraphiteIREdgeBatch()
        batch.edges = edges
        edges.removeAll(keepingCapacity: true)
        try write(chunk: .edges(batch))
    }

    private func flushMethods() throws {
        guard !methods.isEmpty else { return }
        var batch = GraphiteIRMethodBatch()
        batch.methods = methods
        methods.removeAll(keepingCapacity: true)
        try write(chunk: .methods(batch))
    }

    private func flushTypeRelations() throws {
        guard !typeRelations.isEmpty else { return }
        var batch = GraphiteIRTypeRelationBatch()
        batch.relations = typeRelations
        typeRelations.removeAll(keepingCapacity: true)
        try write(chunk: .typeRelations(batch))
    }

    private func flushClassOrigins() throws {
        guard !classOrigins.isEmpty else { return }
        var batch = GraphiteIRClassOriginBatch()
        batch.origins = classOrigins
        classOrigins.removeAll(keepingCapacity: true)
        try write(chunk: .classOrigins(batch))
    }

    private func flushEnumValues() throws {
        guard !enumValues.isEmpty else { return }
        var batch = GraphiteIREnumValueBatch()
        batch.entries = enumValues
        enumValues.removeAll(keepingCapacity: true)
        try write(chunk: .enumValues(batch))
    }

    private func flushArtifacts() throws {
        guard !artifacts.isEmpty else { return }
        var batch = GraphiteIRArtifactDependencyBatch()
        batch.dependencies = artifacts
        artifacts.removeAll(keepingCapacity: true)
        try write(chunk: .artifactDependencies(batch))
    }

    /// Every non-string chunk is preceded by the strings it may refer to.
    private func write(chunk: GraphiteIRChunk.OneOf_Chunk) throws {
        if case .strings = chunk {} else { try flushStrings() }
        var message = GraphiteIRChunk()
        message.chunk = chunk
        let body = try message.serializedData()
        var out = Data()
        IRStream.appendVarint(UInt64(body.count), to: &out)
        out.append(body)
        try handle.write(contentsOf: out)
    }
}

/// The length-delimited carrier: varint byte length, then the serialized chunk.
public enum IRStream {
    public static func appendVarint(_ value: UInt64, to data: inout Data) {
        var value = value
        while value >= 0x80 {
            data.append(UInt8(value & 0x7F) | 0x80)
            value >>= 7
        }
        data.append(UInt8(value))
    }

    /// Parses every chunk of a complete stream (tests and `ir` tooling; the JVM reader
    /// consumes streams incrementally).
    public static func chunks(of data: Data) throws -> [GraphiteIRChunk] {
        var chunks: [GraphiteIRChunk] = []
        var offset = data.startIndex
        while offset < data.endIndex {
            var length: UInt64 = 0
            var shift: UInt64 = 0
            while true {
                guard offset < data.endIndex else { throw IRError.truncated }
                let byte = data[offset]
                offset += 1
                length |= UInt64(byte & 0x7F) << shift
                if byte & 0x80 == 0 { break }
                shift += 7
                if shift > 63 { throw IRError.malformedLength }
            }
            guard length <= UInt64(data.endIndex - offset) else { throw IRError.truncated }
            let end = offset + Int(length)
            chunks.append(try GraphiteIRChunk(serializedBytes: data[offset..<end]))
            offset = end
        }
        return chunks
    }
}
