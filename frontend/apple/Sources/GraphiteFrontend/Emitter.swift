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
    public var resourceFiles = 0
    public var resourceValues = 0
    public var parameters = 0
    public var returns = 0
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
    private var descriptorReturnTypes: [String: String] = [:]
    private var functionFlows: [String: SyntaxFunctionFlow] = [:]
    private var parameterNodes: [String: [UInt32]] = [:]
    private var returnNodes: [String: UInt32] = [:]
    private var returningFunctions = Set<String>()
    private static let valuelessReturnTypes: Set<String> = ["", "Swift.Void", "()", "void", "Swift.Never"]
    private lazy var typeNames: [String: String] = declaredTypeNames()
    private struct AnnotationTarget: Hashable {
        let className: String
        let member: String
    }
    private var declarations: [SourcePosition: [AnnotationTarget]] = [:]

    private func recordDeclaration(at position: SourcePosition, className: String, member: String) {
        let target = AnnotationTarget(className: className, member: member)
        if !declarations[position, default: []].contains(target) {
            declarations[position, default: []].append(target)
        }
    }
    /// The model's declarations indexed once, so name resolution stays linear overall.
    private let typesByUSR: [String: TypeDecl]
    private let membersByContainer: [String: [MemberDecl]]
    private let membersByUSR: [String: MemberDecl]
    private let supertypesByUSR: [String: [String]]
    private let declaredTypesByName: [String: TypeDecl]
    public private(set) var summary = EmitSummary()
    public private(set) var resourceOmissions: [AppleResources.Omission] = []
    /// Completeness evidence (docs/analysis-completeness.md, "The Apple frontend"): the classes
    /// recorded so far, and the methods some build member overrides or implements.
    private var recordedClasses = Set<String>()
    private var recordedClassIDs = Set<UInt32>()
    private lazy var overriders: [String: [MemberDecl]] = {
        var result: [String: [MemberDecl]] = [:]
        for member in model.members { for usr in member.overrides { result[usr, default: []].append(member) } }
        return result
    }()

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
        var byMemberUSR: [String: MemberDecl] = [:]
        for member in model.members where byMemberUSR[member.symbol.usr] == nil { byMemberUSR[member.symbol.usr] = member }
        membersByUSR = byMemberUSR
        supertypesByUSR = Dictionary(grouping: model.supertypes, by: \.subtype).mapValues { $0.map(\.supertype.usr) }
    }

    public func emit(resourceBundles: [URL] = []) throws -> EmitSummary {
        writer.recordCompleteness()
        try emitTypes()
        try emitMembers()
        try emitTypeRelations()
        try emitFunctionParameters()
        try emitCalls()
        try emitAnnotations()
        var seenPaths = Set<String>()
        var seenIdentifiers = Set<String>()
        for bundle in resourceBundles {
            let path = bundle.resolvingSymlinksInPath().standardizedFileURL.path
            guard seenPaths.insert(path).inserted else { continue }
            let omissions = try AppleResources.read(bundle: bundle) { file, values in
                if file.format == "plist" && !seenIdentifiers.insert(file.source).inserted {
                    throw AppleResources.Invalid(description: "multiple selected resource bundles have identifier \(file.source); choose one build variant per identifier")
                }
                try writer.addNode(.resourceFile(file))
                summary.resourceFiles += 1
                for value in values {
                    guard !containsResourceDictionary(value.value) else {
                        resourceOmissions.append(.init(path: value.path, key: value.key,
                            reason: "the current persisted graph format cannot preserve dictionaries, including dictionaries nested in lists"))
                        continue
                    }
                    try writer.addNode(.resourceValue(value))
                    summary.resourceValues += 1
                }
            }
            resourceOmissions += omissions
        }
        try writer.finish()
        summary.nodes = writer.nodeCount
        summary.edges = writer.edgeCount
        summary.strings = writer.stringCount
        return summary
    }

    private func containsResourceDictionary(_ value: GraphiteIRValue) -> Bool {
        if case .mapValue? = value.value { return true }
        if case .listValue(let list)? = value.value {
            return list.values.contains(where: containsResourceDictionary)
        }
        return false
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
            } else if type.module.isEmpty {
                // A C or Objective-C declaration the compiler indexed without a module name;
                // an anonymous struct (`typedef struct { ... } Point;`) has no name of its
                // own and goes by its typedef's, which its USR (`c:@SA@Point`) carries.
                name = type.symbol.name.isEmpty ? (ClangUSR.parse(usr)?.container ?? usr) : type.symbol.name
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
    /// syntax pass: parameter and return types as written, qualified where known for a
    /// Swift declaration, as Clang spells them for an Objective-C one.
    private func writtenSignature(at position: SourcePosition?) -> SwiftSignature? {
        guard let position, let declaration = facts[position.path]?.declarations[position] else { return nil }
        guard Frontend.isSwift(position.path) else {
            return SwiftSignature(
                declaringType: "",
                parameterTypes: declaration.parameterTypes,
                returnType: declaration.returnType ?? "",
                isStatic: declaration.isStatic
            )
        }
        return SwiftSignature(
            declaringType: "",
            parameterTypes: declaration.parameterTypes.map(qualify),
            returnType: qualify(declaration.returnType ?? "()"),
            isStatic: declaration.isStatic
        )
    }

    /// The signature of a symbol: demangled, or, for a Clang USR, the declaring type from
    /// the USR and the types from the source (the declaration's before the definition's,
    /// as Clang gives an `@implementation` method the nullability its `@interface` wrote).
    private func signature(of symbol: SymbolInfo, container: String?, at position: SourcePosition?, declaredAt declaration: SourcePosition? = nil) -> SwiftSignature? {
        if let text = demangled[symbol.usr], let parsed = SwiftSignature.parse(text, name: Emitter.baseName(symbol.name)) {
            return parsed
        }
        guard let clang = ClangUSR.parse(symbol.usr), clang.member != nil else { return nil }
        // The declaring type: the member's container when the index gave one, else the
        // class the USR names, resolved through the model when that class is declared in
        // the indexed sources so a call site and the declaration agree on its name.
        let declaring: String
        if let container {
            declaring = qualifiedName(ofType: container)
        } else if let containerUSR = ClangUSR.containerUSR(of: symbol.usr), typesByUSR[containerUSR] != nil {
            declaring = qualifiedName(ofType: containerUSR)
        } else {
            declaring = clang.qualifiedContainer ?? ""
        }
        var signature = writtenSignature(at: declaration)
            ?? writtenSignature(at: position)
            ?? synthesizedSignature(of: symbol, clang: clang, at: position)
            ?? SwiftSignature(declaringType: declaring)
        signature.declaringType = declaring
        if signature.isStatic == false {
            signature.isStatic = clang.memberKind == .classMethod || clang.memberKind == .classProperty || clang.memberKind == .function
        }
        return signature
    }

    /// The type of an instance variable `@synthesize` created (`@synthesize contentType =
    /// _contentType;`, or the default `_name` for `@synthesize name;`): the property's,
    /// which the `@synthesize` line names and the `.m` cannot spell itself. The property is
    /// the one the index links to the ivar at the `@synthesize` token (it may be a protocol's
    /// or a superclass's: `c:objc(pl)ProfileManager(py)badgeStore` backs
    /// `c:objc(cs)Fake@_badgeStore`), else the property of that name in the class, its
    /// superclasses or the protocols it adopts.
    private func synthesizedSignature(of symbol: SymbolInfo, clang: ClangUSR, at position: SourcePosition?) -> SwiftSignature? {
        guard clang.memberKind == .instanceVariable, let classUSR = ClangUSR.containerUSR(of: symbol.usr) else { return nil }
        let propertyName = position.flatMap { facts[$0.path]?.synthesized[$0] }
            ?? (symbol.name.hasPrefix("_") ? String(symbol.name.dropFirst()) : symbol.name)
        var candidates: [String] = []
        if let linked = model.synthesized[symbol.usr] { candidates.append(linked) }
        var queue = [classUSR]
        var visited = Set<String>()
        while !queue.isEmpty {
            let type = queue.removeFirst()
            guard visited.insert(type).inserted else { continue }
            candidates.append(type + "(py)" + propertyName)
            queue += supertypesByUSR[type] ?? []
        }
        for candidate in candidates {
            guard let property = membersByUSR[candidate], property.symbol.usr != symbol.usr else { continue }
            guard var signature = signature(of: property.symbol, container: property.container, at: property.position, declaredAt: property.declaration),
                  !signature.returnType.isEmpty else { continue }
            signature.parameterTypes = []
            signature.isStatic = false
            return signature
        }
        return nil
    }

    private func extendedTypeName(of extension: TypeDecl) -> String {
        // A member of the extension demangles to `(extension in M):Qualified.Type.member...`.
        for member in membersByContainer[`extension`.symbol.usr] ?? [] {
            if let text = demangled[member.symbol.usr],
               let signature = SwiftSignature.parse(text, name: Emitter.baseName(member.symbol.name)) {
                return signature.declaringType
            }
        }
        // Prefer a Swift anchor anywhere in the extension before falling back to
        // Clang, so reordering @objc and Swift members cannot change its owner.
        for member in membersByContainer[`extension`.symbol.usr] ?? [] {
            // An @objc-only extension has no demangleable Swift member. Its Clang
            // category USR identifies the owner even when that type is external.
            if let clang = ClangUSR.parse(member.symbol.usr), let owner = clang.container {
                let module = clang.module.map { "@M@\($0)@" } ?? ""
                let ownerUSR = "c:\(module)objc(\(clang.isProtocol ? "pl" : "cs"))\(owner)"
                // A simple-name lookup can pick a different module's same-named
                // class. Preserve the owner encoded in the member's USR.
                if typesByUSR[ownerUSR] != nil { return qualifiedName(ofType: ownerUSR) }
                if let qualified = clang.qualifiedContainer { return qualified }
            }
        }
        if let declared = declaredTypesByName[`extension`.symbol.name] {
            return qualifiedName(ofType: declared.symbol.usr)
        }
        // An Objective-C category (`c:objc(cy)Class@Category`) extends the class it names.
        if let clang = ClangUSR.parse(`extension`.symbol.usr), let container = clang.container {
            if let declared = declaredTypesByName[container] { return qualifiedName(ofType: declared.symbol.usr) }
            return clang.qualifiedContainer ?? container
        }
        if `extension`.module.isEmpty { return `extension`.symbol.name }
        return `extension`.module + "." + `extension`.symbol.name
    }

    /// The `MethodDescriptor` for a member or a call target.
    func descriptor(for symbol: SymbolInfo, module: String, container: String? = nil, at position: SourcePosition? = nil, declaredAt declaration: SourcePosition? = nil) -> GraphiteIRMethodRef {
        if let cached = descriptors[symbol.usr] { return cached }
        let signature = signature(of: symbol, container: container, at: position, declaredAt: declaration)
        let declaring = signature?.declaringType ?? container.map(qualifiedName(ofType:)) ?? module
        let ref = writer.methodRef(
            declaringClass: declaring,
            name: symbol.name,
            parameterTypes: signature?.parameterTypes ?? [],
            returnType: signature?.returnType ?? ""
        )
        descriptors[symbol.usr] = ref
        descriptorReturnTypes[symbol.usr] = signature?.returnType ?? ""
        return ref
    }

    // MARK: Emission

    private func emitTypes() throws {
        for type in model.types where type.kind != .extension {
            let name = qualifiedName(ofType: type.symbol.usr)
            try writer.addClassOrigin(className: name, source: type.module)
            recordAnalyzedClass(name, module: type.module)
            recordDeclaration(at: type.position, className: name, member: "<class>")
            summary.types += 1
        }
    }

    private func emitMembers() throws {
        for member in model.members {
            let declaring: String
            let signature = signature(of: member.symbol, container: member.container, at: member.position, declaredAt: member.declaration)
            if let signature, !signature.declaringType.isEmpty {
                declaring = signature.declaringType
            } else if let container = member.container {
                declaring = qualifiedName(ofType: container)
            } else {
                declaring = member.module
            }
            recordDeclaration(at: member.position, className: declaring, member: member.symbol.name)
            switch member.kind {
            case .method, .initializer, .deinitializer, .function:
                let method = descriptor(for: member.symbol, module: member.module, container: member.container, at: member.position, declaredAt: member.declaration)
                try writer.addMethod(method)
                // A free function's owner is its module: a class of the build like any type.
                if member.container == nil { recordAnalyzedClass(declaring, module: member.module) }
                let key = MethodKey(method)
                methodKeys[member.symbol.usr] = key
                if hasBody(member) {
                    bodied.insert(key)
                } else if bodiless[key] == nil {
                    bodiless[key] = (method, isRequirement(member))
                    bodilessOrder.append(key)
                }
                summary.methods += 1
            case .property:
                var field = GraphiteIRField()
                field.field = writer.fieldRef(declaringClass: declaring, name: member.symbol.name, type: signature?.returnType ?? "")
                field.isStatic = member.isStatic || signature?.isStatic == true
                try writer.addNode(.field(field))
                summary.fields += 1
            case .enumCase:
                try writer.addEnumValues(enumClass: declaring, enumName: member.symbol.name, values: [])
            }
        }
        // A method is keyed by its descriptor, and two members can share one (a requirement and
        // its default implementation in an extension of the protocol): it is without a body only
        // when none of them has one.
        for key in bodilessOrder where !bodied.contains(key) {
            let (method, requirement) = bodiless[key]!
            writer.addMethodCompleteness(
                method: method,
                status: requirement ? .abstract : .noBody,
                message: requirement ? "a protocol requirement" : "declared, with no definition in the indexed sources"
            )
        }
    }

    // MARK: Completeness

    private struct MethodKey: Hashable {
        let owner: UInt32
        let name: UInt32
        let parameters: [UInt32]
        let returns: UInt32

        init(_ method: GraphiteIRMethodRef) {
            owner = method.declaringClass.name
            name = method.name
            parameters = method.parameterTypes.map(\.name)
            returns = method.returnType.name
        }
    }
    /// The descriptor of every method member, by USR, and the descriptors some member gives a body.
    private var methodKeys: [String: MethodKey] = [:]
    private var bodied = Set<MethodKey>()
    private var bodiless: [MethodKey: (method: GraphiteIRMethodRef, requirement: Bool)] = [:]
    private var bodilessOrder: [MethodKey] = []

    /// Clang indexes C and Objective-C without a module: a type then has no origin to name,
    /// and a C function has no owner at all (its descriptor's class is empty), so there is no
    /// class to record for it. The function itself is in the graph, and reads as analysed.
    private func recordAnalyzedClass(_ name: String, module: String) {
        guard !name.isEmpty, recordedClasses.insert(name).inserted else { return }
        recordedClassIDs.insert(writer.intern(name))
        writer.addClassCompleteness(className: name, origin: module.isEmpty ? nil : module, status: .analyzed)
    }

    /// A member declared by a protocol itself (not by an extension of it): a requirement.
    private func isRequirement(_ member: MemberDecl) -> Bool {
        member.container.flatMap { typesByUSR[$0] }?.kind == .protocol
    }

    /// Whether the build holds a body for `member`: the index defines it, and it is not a
    /// requirement, which Swift's index reports as a definition too.
    private func hasBody(_ member: MemberDecl) -> Bool {
        member.hasBody && !isRequirement(member)
    }

    /// A callee the graph holds no declaration of, on a class the build read: a member the
    /// compiler synthesized (a memberwise or default initializer, a derived conformance) or one
    /// declared outside the indexed sources. The build has no body for it, and it says so,
    /// where the class's status alone would answer that the class declares no such member.
    private func recordUndeclaredCallee(_ callee: SymbolInfo, _ method: GraphiteIRMethodRef) {
        let key = MethodKey(method)
        guard membersByUSR[callee.usr] == nil, recordedClassIDs.contains(method.declaringClass.name),
              !bodied.contains(key), bodiless[key] == nil else { return }
        bodiless[key] = (method, false)
        writer.addMethodCompleteness(
            method: method, status: .noBody,
            message: "not declared in the indexed sources: synthesized by the compiler, or declared elsewhere"
        )
    }

    /// The gap of a dynamically dispatched call to `callee`, if the graph leaves one.
    ///
    /// The call site names the method the compiler resolved statically, and the graph links no
    /// override to it. That is the whole story unless the build itself holds another body the
    /// receiver could select: a member with a body that overrides or implements `callee` under
    /// a descriptor of its own (a default implementation shares the requirement's, and is the
    /// named method). Then the named method's body is in the graph and another may run
    /// (`dispatchPartial`), or the named method has no body in the build and none of the
    /// build's implementations is linked (`dispatchUnresolved`). An override outside the build
    /// is outside the scope, as for any frontend: the callee's own status says what the build
    /// knows of it.
    private func dispatchGap(of callee: SymbolInfo) -> GraphiteIRDispatchGap? {
        let named = methodKeys[callee.usr]
        let another = (overriders[callee.usr] ?? []).contains { hasBody($0) && methodKeys[$0.symbol.usr] != named }
        guard another else { return nil }
        return named.map(bodied.contains) == true ? .dispatchPartial : .dispatchUnresolved
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

    private func emitFunctionParameters() throws {
        for member in model.members {
            let usr = member.symbol.usr
            guard functionFlows[usr] == nil,
                  let flow = facts[member.position.path]?.functionFlows[member.position],
                  flow.calls.values.contains(where: { !$0.parameterByArgument.isEmpty || $0.returnsResult }) else { continue }
            let method = descriptor(for: member.symbol, module: member.module, container: member.container,
                                    at: member.position, declaredAt: member.declaration)
            guard method.parameterTypes.count == flow.parameters.count,
                  flow.parameters.map(\.index) == Array(0..<flow.parameters.count) else { continue }
            var nodes: [UInt32] = []
            for parameter in flow.parameters {
                var node = GraphiteIRParameter()
                node.index = UInt32(parameter.index)
                node.type = method.parameterTypes[parameter.index]
                node.method = method
                nodes.append(try writer.addNode(.parameter(node)))
                summary.parameters += 1
            }
            parameterNodes[usr] = nodes
            functionFlows[usr] = flow
            if let type = descriptorReturnTypes[usr], !Self.valuelessReturnTypes.contains(type) {
                returningFunctions.insert(usr)
            }
        }
    }

    private func emitCalls() throws {
        for call in model.calls {
            let callee = descriptor(for: call.callee, module: call.module)
            recordUndeclaredCallee(call.callee, callee)
            let caller: GraphiteIRMethodRef
            if let symbol = call.caller {
                caller = descriptor(for: symbol, module: call.module)
            } else {
                caller = writer.methodRef(declaringClass: call.module, name: "<top-level>", parameterTypes: [], returnType: "Swift.Void")
                recordAnalyzedClass(call.module, module: call.module)
            }
            let callerUSR = call.caller?.usr
            let flow = callerUSR.flatMap { functionFlows[$0]?.calls[call.position] }
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
                        let reference = try writer.addNode(.localVariable(local))
                        arguments.append(reference)
                        if let callerUSR, let slot = flow?.parameterByArgument[index],
                           let parameters = parameterNodes[callerUSR], parameters.indices.contains(slot) {
                            try writer.addDataFlow(from: parameters[slot], to: reference, .assign)
                        }
                    }
                }
            }
            var site = GraphiteIRCallSite()
            site.caller = caller
            site.callee = callee
            site.line = Int32(call.position.line)
            site.arguments = arguments
            let id = try writer.addNode(.callSite(site))
            if call.isDynamic, let gap = dispatchGap(of: call.callee) {
                writer.addCallSiteGap(callSite: id, gap: gap)
            }
            for argument in arguments {
                try writer.addDataFlow(from: argument, to: id, .parameterPass)
            }
            if let callerUSR, flow?.returnsResult == true, returningFunctions.contains(callerUSR),
               let calleeType = descriptorReturnTypes[call.callee.usr],
               !Self.valuelessReturnTypes.contains(calleeType) {
                let returned: UInt32
                if let existing = returnNodes[callerUSR] {
                    returned = existing
                } else {
                    var node = GraphiteIRReturn()
                    node.method = caller
                    returned = try writer.addNode(.return(node))
                    returnNodes[callerUSR] = returned
                    summary.returns += 1
                }
                try writer.addDataFlow(from: id, to: returned, .returnValue)
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
                guard let targets = declarations[attribute.position] else { continue }
                for target in targets {
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
}
