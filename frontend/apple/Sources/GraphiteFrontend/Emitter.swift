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
    private lazy var typeNames: [String: String] = declaredTypeNames()
    private var declarations: [SourcePosition: (className: String, member: String)] = [:]
    /// The model's declarations indexed once, so name resolution stays linear overall.
    private let typesByUSR: [String: TypeDecl]
    private let membersByContainer: [String: [MemberDecl]]
    private let declaredTypesByName: [String: TypeDecl]
    public private(set) var summary = EmitSummary()

    public init(writer: IRWriter, model: IndexModel, facts: [String: SyntaxFacts], demangled: [String: String]) {
        self.writer = writer
        self.model = model
        self.facts = facts
        self.demangled = demangled
        var byUSR: [String: TypeDecl] = [:]
        var byName: [String: TypeDecl] = [:]
        for type in model.types {
            if byUSR[type.symbol.usr] == nil { byUSR[type.symbol.usr] = type }
            if type.kind != .extension, byName[type.symbol.name] == nil { byName[type.symbol.name] = type }
        }
        typesByUSR = byUSR
        declaredTypesByName = byName
        membersByContainer = Dictionary(grouping: model.members.filter { $0.container != nil }, by: { $0.container! })
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
        if let type = typesByUSR[usr] {
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
        } else if let clang = ClangUSR.parse(usr), clang.member == nil, let container = clang.qualifiedContainer {
            name = container
        } else if let symbol = model.symbols[usr] {
            name = symbol.name
        } else {
            name = usr
        }
        qualifiedNames[usr] = name
        return name
    }

    // MARK: Signatures the demangler cannot give

    /// The Swift standard library types a source text names without a module.
    static let standardLibraryTypes: Set<String> = [
        "Bool", "Int", "Int8", "Int16", "Int32", "Int64", "UInt", "UInt8", "UInt16", "UInt32", "UInt64",
        "Float", "Float16", "Double", "String", "Character", "Substring", "Array", "Dictionary", "Set",
        "Optional", "Result", "Error", "Never", "Void", "Range", "ClosedRange", "Sequence", "Collection",
    ]

    /// Every type the index knows by its simple name, when the name is unique: the
    /// project's own types with their module, imported Objective-C types bare.
    private func declaredTypeNames() -> [String: String] {
        var names: [String: [String]] = [:]
        for symbol in model.symbols.values where IndexReader.isTypeLike(symbol.kind) && symbol.kind != .extension {
            let qualified = qualifiedName(ofType: symbol.usr)
            guard qualified != symbol.usr else { continue }
            names[symbol.name, default: []].append(qualified)
        }
        return names.compactMapValues { Set($0).count == 1 ? $0[0] : nil }
    }

    /// A type as written in source, qualified the way the demangler prints it where the
    /// name is known (`Bool` → `Swift.Bool`, `Order` → `AcmeShop.Order`,
    /// `UIApplication.LaunchOptionsKey` → `UIApplication.LaunchOptionsKey` since UIKit's
    /// module is not in the index), `()` → `Swift.Void`, `[K: V]` → `[K : V]`.
    func qualify(_ written: String) -> String {
        let text = SwiftSignature.normalize(written)
        if text == "Swift.Void" { return text }
        var result = ""
        var index = text.startIndex
        var previous: Character? = nil
        var bracketDepth = 0
        while index < text.endIndex {
            let character = text[index]
            if character.isLetter || character == "_" {
                var end = index
                while end < text.endIndex, text[end].isLetter || text[end].isNumber || text[end] == "_" {
                    end = text.index(after: end)
                }
                let identifier = String(text[index..<end])
                if previous == ".", identifier != "Type" {
                    result += identifier
                } else if Emitter.standardLibraryTypes.contains(identifier) {
                    result += "Swift." + identifier
                } else if let qualified = typeNames[identifier] {
                    result += qualified
                } else {
                    result += identifier
                }
                previous = text[text.index(before: end)]
                index = end
                continue
            }
            switch character {
            case "[": bracketDepth += 1
            case "]": bracketDepth -= 1
            case ":" where bracketDepth > 0 && previous != " ":
                result += " "
            default: break
            }
            result.append(character)
            previous = character
            index = text.index(after: index)
        }
        return result
    }

    /// The signature of a declaration the demangler cannot read (a Clang USR), from the
    /// syntax pass: parameter and return types as written, qualified where known.
    private func writtenSignature(at position: SourcePosition?) -> SwiftSignature? {
        guard let position, let declaration = facts[position.path]?.declarations[position] else { return nil }
        return SwiftSignature(
            declaringType: "",
            parameterTypes: declaration.parameterTypes.map(qualify),
            returnType: qualify(declaration.returnType ?? "()"),
            isStatic: declaration.isStatic
        )
    }

    /// The signature of a symbol: demangled, or, for a Clang USR, the declaring type from
    /// the USR and the types from the source.
    private func signature(of symbol: SymbolInfo, container: String?, at position: SourcePosition?) -> SwiftSignature? {
        if let text = demangled[symbol.usr], let parsed = SwiftSignature.parse(text, name: Emitter.baseName(symbol.name)) {
            return parsed
        }
        guard let clang = ClangUSR.parse(symbol.usr), clang.member != nil else { return nil }
        let declaring = container.map(qualifiedName(ofType:)) ?? clang.qualifiedContainer ?? ""
        var signature = writtenSignature(at: position) ?? SwiftSignature(declaringType: declaring)
        signature.declaringType = declaring
        if signature.isStatic == false {
            signature.isStatic = clang.memberKind == .classMethod || clang.memberKind == .function
        }
        return signature
    }

    private func extendedTypeName(of extension: TypeDecl) -> String {
        // A member of the extension demangles to `(extension in M):Qualified.Type.member...`.
        for member in membersByContainer[`extension`.symbol.usr] ?? [] {
            if let text = demangled[member.symbol.usr],
               let signature = SwiftSignature.parse(text, name: Emitter.baseName(member.symbol.name)) {
                return signature.declaringType
            }
        }
        if let declared = declaredTypesByName[`extension`.symbol.name] {
            return qualifiedName(ofType: declared.symbol.usr)
        }
        return `extension`.module + "." + `extension`.symbol.name
    }

    /// The `MethodDescriptor` for a member or a call target.
    func descriptor(for symbol: SymbolInfo, module: String, container: String? = nil, at position: SourcePosition? = nil) -> GraphiteIRMethodRef {
        if let cached = descriptors[symbol.usr] { return cached }
        let signature = signature(of: symbol, container: container, at: position)
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
            let signature = signature(of: member.symbol, container: member.container, at: member.position)
            if let signature, !signature.declaringType.isEmpty {
                declaring = signature.declaringType
            } else if let container = member.container {
                declaring = qualifiedName(ofType: container)
            } else {
                declaring = member.module
            }
            declarations[member.position] = (declaring, member.symbol.name)
            switch member.kind {
            case .method, .initializer, .deinitializer, .function:
                try writer.addMethod(descriptor(for: member.symbol, module: member.module, container: member.container, at: member.position))
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
