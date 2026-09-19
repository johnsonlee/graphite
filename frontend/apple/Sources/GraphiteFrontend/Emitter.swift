import Foundation
import GraphiteIR

/// Counts of what one build emitted.
public struct EmitSummary: Equatable {
    public var types = 0
    public var methods = 0
    public var fields = 0
    public var callSites = 0
    public var constants = 0
    public var annotations = 0
    public var typeRelations = 0
    public var nodes: UInt64 = 0
    public var edges: UInt64 = 0
    public var strings: UInt64 = 0

    public init() {}
}

/// Turns the index model and the syntax facts into Graph IR.
///
/// Naming follows the demangler: types are `Module.Outer.Inner`, a method's name is its
/// full Swift name (`checkout(order:method:)`, `init(client:)`), parameter and return
/// types are printed as Swift prints them (`[Swift.String]`, `AcmeShop.Order?`).
public final class Emitter {
    private let writer: IRWriter
    private let model: IndexModel
    private let facts: [String: SyntaxFacts]
    private let demangled: [String: String]
    private var qualifiedNames: [String: String] = [:]
    private var descriptors: [String: GraphiteIRMethodRef] = [:]
    private var declarations: [SourcePosition: (className: String, member: String)] = [:]
    public private(set) var summary = EmitSummary()

    public init(writer: IRWriter, model: IndexModel, facts: [String: SyntaxFacts], demangled: [String: String]) {
        self.writer = writer
        self.model = model
        self.facts = facts
        self.demangled = demangled
    }

    public func emit() throws -> EmitSummary {
        try emitTypes()
        try emitMembers()
        try emitTypeRelations()
        try emitCalls()
        try emitAnnotations()
        try writer.finish()
        summary.nodes = writer.nodeCount
        summary.edges = writer.edgeCount
        summary.strings = writer.stringCount
        return summary
    }

    // MARK: Names

    static func baseName(_ name: String) -> String {
        if let paren = name.firstIndex(of: "(") { return String(name[..<paren]) }
        return name
    }

    /// `Module.Outer.Inner` for a type declaration; the extended type for an extension.
    func qualifiedName(ofType usr: String) -> String {
        if let cached = qualifiedNames[usr] { return cached }
        var name: String
        if let type = model.types.first(where: { $0.symbol.usr == usr }) {
            if type.kind == .extension {
                name = extendedTypeName(of: type)
            } else if let demangledName = demangled[usr], !demangledName.contains("(") {
                name = demangledName
            } else if let container = type.container {
                name = qualifiedName(ofType: container) + "." + type.symbol.name
            } else {
                name = type.module + "." + type.symbol.name
            }
        } else if let demangledName = demangled[usr], !demangledName.contains("(") {
            name = demangledName
        } else if let symbol = model.symbols[usr] {
            name = symbol.name
        } else {
            name = usr
        }
        qualifiedNames[usr] = name
        return name
    }

    private func extendedTypeName(of extension: TypeDecl) -> String {
        // A member of the extension demangles to `(extension in M):Qualified.Type.member...`.
        for member in model.members where member.container == `extension`.symbol.usr {
            if let text = demangled[member.symbol.usr],
               let signature = SwiftSignature.parse(text, name: Emitter.baseName(member.symbol.name)) {
                return signature.declaringType
            }
        }
        if let declared = model.types.first(where: { $0.kind != .extension && $0.symbol.name == `extension`.symbol.name }) {
            return qualifiedName(ofType: declared.symbol.usr)
        }
        return `extension`.module + "." + `extension`.symbol.name
    }

    /// The `MethodDescriptor` for a member or a call target.
    func descriptor(for symbol: SymbolInfo, module: String, container: String? = nil) -> GraphiteIRMethodRef {
        if let cached = descriptors[symbol.usr] { return cached }
        let signature = demangled[symbol.usr].flatMap { SwiftSignature.parse($0, name: Emitter.baseName(symbol.name)) }
        let declaring = signature?.declaringType ?? container.map(qualifiedName(ofType:)) ?? module
        let ref = writer.methodRef(
            declaringClass: declaring,
            name: symbol.name,
            parameterTypes: signature?.parameterTypes ?? [],
            returnType: signature?.returnType ?? ""
        )
        descriptors[symbol.usr] = ref
        return ref
    }

    // MARK: Emission

    private func emitTypes() throws {
        for type in model.types where type.kind != .extension {
            let name = qualifiedName(ofType: type.symbol.usr)
            try writer.addClassOrigin(className: name, source: type.module)
            declarations[type.position] = (name, "<class>")
            summary.types += 1
        }
    }

    private func emitMembers() throws {
        for member in model.members {
            let declaring: String
            let signature = demangled[member.symbol.usr].flatMap { SwiftSignature.parse($0, name: Emitter.baseName(member.symbol.name)) }
            if let signature {
                declaring = signature.declaringType
            } else if let container = member.container {
                declaring = qualifiedName(ofType: container)
            } else {
                declaring = member.module
            }
            declarations[member.position] = (declaring, member.symbol.name)
            switch member.kind {
            case .method, .initializer, .deinitializer, .function:
                try writer.addMethod(descriptor(for: member.symbol, module: member.module, container: member.container))
                summary.methods += 1
            case .property:
                var field = GraphiteIRField()
                field.field = writer.fieldRef(declaringClass: declaring, name: member.symbol.name, type: signature?.returnType ?? "")
                field.isStatic = member.isStatic
                try writer.addNode(.field(field))
                summary.fields += 1
            case .enumCase:
                try writer.addEnumValues(enumClass: declaring, enumName: member.symbol.name, values: [])
            }
        }
    }

    private func emitTypeRelations() throws {
        let protocols = Set(model.types.filter { $0.kind == .protocol }.map(\.symbol.usr))
        for relation in model.supertypes {
            let subtype = qualifiedName(ofType: relation.subtype)
            let supertype = qualifiedName(ofType: relation.supertype.usr)
            let kind: GraphiteIRTypeRelation
            if relation.supertype.kind == .protocol && !protocols.contains(relation.subtype) {
                kind = .implements
            } else {
                kind = .extends
            }
            try writer.addTypeRelation(subtype: subtype, supertype: supertype, relation: kind)
            summary.typeRelations += 1
        }
    }

    private func emitCalls() throws {
        for call in model.calls {
            let callee = descriptor(for: call.callee, module: call.module)
            let caller: GraphiteIRMethodRef
            if let symbol = call.caller {
                caller = descriptor(for: symbol, module: call.module)
            } else {
                caller = writer.methodRef(declaringClass: call.module, name: "<top-level>", parameterTypes: [], returnType: "Swift.Void")
            }
            var arguments: [UInt32] = []
            if let syntax = facts[call.position.path]?.calls[call.position] {
                let literals = Dictionary(uniqueKeysWithValues: syntax.literals.map { ($0.index, $0.value) })
                for (index, text) in syntax.arguments.enumerated() {
                    if let literal = literals[index] {
                        arguments.append(try constant(literal))
                        summary.constants += 1
                    } else {
                        var local = GraphiteIRLocalVariable()
                        local.name = writer.intern(text)
                        local.type = writer.typeRef("")
                        local.method = caller
                        arguments.append(try writer.addNode(.localVariable(local)))
                    }
                }
            }
            var site = GraphiteIRCallSite()
            site.caller = caller
            site.callee = callee
            site.line = Int32(call.position.line)
            site.arguments = arguments
            let id = try writer.addNode(.callSite(site))
            for argument in arguments {
                try writer.addDataFlow(from: argument, to: id, .parameterPass)
            }
            summary.callSites += 1
        }
    }

    private func constant(_ literal: Literal) throws -> UInt32 {
        switch literal {
        case .string(let value):
            var node = GraphiteIRStringConstant()
            node.value = writer.intern(value)
            return try writer.addNode(.stringConstant(node))
        case .int(let value):
            if let small = Int32(exactly: value) {
                var node = GraphiteIRIntConstant()
                node.value = small
                return try writer.addNode(.intConstant(node))
            }
            var node = GraphiteIRLongConstant()
            node.value = value
            return try writer.addNode(.longConstant(node))
        case .double(let value):
            var node = GraphiteIRDoubleConstant()
            node.value = value
            return try writer.addNode(.doubleConstant(node))
        case .bool(let value):
            var node = GraphiteIRBooleanConstant()
            node.value = value
            return try writer.addNode(.booleanConstant(node))
        case .null:
            return try writer.addNode(.nullConstant(GraphiteIRNullConstant()))
        }
    }

    private func emitAnnotations() throws {
        for (_, fileFacts) in facts.sorted(by: { $0.key < $1.key }) {
            for attribute in fileFacts.attributes {
                guard let target = declarations[attribute.position] else { continue }
                var annotation = GraphiteIRAnnotation()
                annotation.name = attribute.name
                annotation.className = target.className
                annotation.memberName = target.member
                if let arguments = attribute.arguments {
                    var named = GraphiteIRNamedValue()
                    named.name = "arguments"
                    named.value.stringValue = arguments
                    annotation.values = [named]
                }
                try writer.addNode(.annotation(annotation))
                summary.annotations += 1
            }
        }
    }
}
