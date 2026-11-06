import Foundation
import SwiftParser
import SwiftSyntax

/// A literal written directly as a call argument.
public enum Literal: Equatable {
    case string(String)
    case int(Int64)
    case double(Double)
    case bool(Bool)
    case null
}

/// A literal argument of a call: which argument slot, which literal.
public struct LiteralArgument: Equatable {
    public var index: Int
    public var label: String?
    public var value: Literal
}

/// A call as the syntax tree sees it, keyed by the position of the called name so that it
/// can be matched with the index store's call occurrence at the same position.
public struct SyntaxCall: Equatable {
    public var position: SourcePosition
    /// Every argument's source text, in order.
    public var arguments: [String]
    public var literals: [LiteralArgument]
}

/// An explicit source parameter. The slot is its declaration order, independent of
/// call labels and default values. A wildcard has a slot but no binding in the body.
public struct SyntaxFormalParameter: Equatable {
    public let index: Int
    public let name: String?
    public let position: SourcePosition
}

/// Proven source-level facts about a call; missing slots have unknown provenance.
public struct SyntaxCallFlow: Equatable {
    public let parameterByArgument: [Int: Int]
    public let returnsResult: Bool
}

public struct SyntaxFunctionFlow: Equatable {
    public let parameters: [SyntaxFormalParameter]
    public var calls: [SourcePosition: SyntaxCallFlow]
}

/// An attribute (`@available(...)`, `@objc`, `@Published`) on a declaration, keyed by the
/// position of the declared name.
public struct SyntaxAttribute: Equatable {
    public var position: SourcePosition
    public var name: String
    public var arguments: String?
}

/// A function, initializer or property declaration as written, keyed by the position of
/// the declared name (the position the index store reports for the definition): the
/// parameter and return types as source text, for declarations whose USR the demangler
/// cannot read.
public struct SyntaxDeclaration: Equatable {
    public var position: SourcePosition
    /// Parameter types as written, labels and leading attributes (`@escaping`) removed.
    public var parameterTypes: [String]
    /// The return type or the property type as written; nil when none is written.
    public var returnType: String?
    public var isStatic: Bool
}

/// What one parse of a file contributes: literal call arguments, declaration attributes
/// and declared signatures.
public struct SyntaxFacts {
    public var calls: [SourcePosition: SyntaxCall] = [:]
    /// Keyed by the declared name token, never by a textual method signature.
    public var functionFlows: [SourcePosition: SyntaxFunctionFlow] = [:]
    public var attributes: [SyntaxAttribute] = []
    public var declarations: [SourcePosition: SyntaxDeclaration] = [:]
    /// Objective-C `@synthesize property = ivar`: the property each synthesized instance
    /// variable (at the position of its name) takes its type from.
    public var synthesized: [SourcePosition: String] = [:]

    public init() {}

    public static func parse(path: String) throws -> SyntaxFacts {
        let source = try String(contentsOfFile: path, encoding: .utf8)
        return parse(source: source, path: path)
    }

    public static func parse(source: String, path: String) -> SyntaxFacts {
        let tree = Parser.parse(source: source)
        let visitor = FactVisitor(path: path, converter: SourceLocationConverter(fileName: path, tree: tree))
        visitor.walk(tree)
        return visitor.facts
    }
}

private final class FactVisitor: SyntaxVisitor {
    let path: String
    let converter: SourceLocationConverter
    var facts = SyntaxFacts()

    init(path: String, converter: SourceLocationConverter) {
        self.path = path
        self.converter = converter
        super.init(viewMode: .sourceAccurate)
    }

    private func position(of token: TokenSyntax) -> SourcePosition {
        let location = converter.location(for: token.positionAfterSkippingLeadingTrivia)
        return SourcePosition(path: path, line: location.line, column: location.column)
    }

    // MARK: Calls

    override func visit(_ node: FunctionCallExprSyntax) -> SyntaxVisitorContinueKind {
        guard let name = calledName(of: node.calledExpression) else { return .visitChildren }
        var literals: [LiteralArgument] = []
        var texts: [String] = []
        for (index, argument) in node.arguments.enumerated() {
            texts.append(argument.expression.trimmedDescription)
            if let literal = FactVisitor.literal(of: argument.expression) {
                literals.append(LiteralArgument(index: index, label: argument.label?.text, value: literal))
            }
        }
        if let trailing = node.trailingClosure {
            texts.append(trailing.trimmedDescription)
        }
        for closure in node.additionalTrailingClosures {
            texts.append(closure.closure.trimmedDescription)
        }
        let key = position(of: name)
        facts.calls[key] = SyntaxCall(position: key, arguments: texts, literals: literals)
        return .visitChildren
    }

    /// The token the index store reports a call at: the member name of `a.b.f(...)`, the
    /// name of `f(...)`, the type name of `Type(...)`, the last name of `A.B(...)`.
    private func calledName(of expression: ExprSyntax) -> TokenSyntax? {
        if let member = expression.as(MemberAccessExprSyntax.self) {
            return member.declName.baseName
        }
        if let reference = expression.as(DeclReferenceExprSyntax.self) {
            return reference.baseName
        }
        if let generic = expression.as(GenericSpecializationExprSyntax.self) {
            return calledName(of: generic.expression)
        }
        if let optional = expression.as(OptionalChainingExprSyntax.self) {
            return calledName(of: optional.expression)
        }
        if let forced = expression.as(ForceUnwrapExprSyntax.self) {
            return calledName(of: forced.expression)
        }
        return nil
    }

    static func literal(of expression: ExprSyntax) -> Literal? {
        if let string = expression.as(StringLiteralExprSyntax.self) {
            let pounds = string.openingPounds?.text.count ?? 0
            var value = ""
            for segment in string.segments {
                guard case .stringSegment(let text) = segment else { return nil }
                guard let decoded = decodeStringSegment(text.content.text, pounds: pounds) else { return nil }
                value += decoded
            }
            return .string(value)
        }
        if let integer = expression.as(IntegerLiteralExprSyntax.self) {
            return parseInteger(integer.literal.text).map(Literal.int)
        }
        if let float = expression.as(FloatLiteralExprSyntax.self) {
            return Double(float.literal.text.replacingOccurrences(of: "_", with: "")).map(Literal.double)
        }
        if let bool = expression.as(BooleanLiteralExprSyntax.self) {
            return .bool(bool.literal.tokenKind == .keyword(.true))
        }
        if expression.is(NilLiteralExprSyntax.self) {
            return .null
        }
        if let prefixed = expression.as(PrefixOperatorExprSyntax.self), prefixed.operator.text == "-" {
            switch literal(of: prefixed.expression) {
            case .int(let value)?: return .int(-value)
            case .double(let value)?: return .double(-value)
            default: return nil
            }
        }
        return nil
    }

    /// The runtime value of a string literal segment: escape sequences decoded as Swift
    /// does (`\n`, `\t`, `\r`, `\0`, `\\`, `\"`, `\'`, `\u{XXXX}`, and a backslash before a
    /// line break, which joins the lines of a multiline literal). In a raw literal
    /// (`#"..."#`) an escape needs the same number of `#` after the backslash, and any
    /// other backslash is text. An escape Swift does not define makes the literal not a
    /// constant (`nil`).
    static func decodeStringSegment(_ text: String, pounds: Int) -> String? {
        let escape = "\\" + String(repeating: "#", count: pounds)
        var result = ""
        var index = text.startIndex
        while index < text.endIndex {
            guard text[index...].hasPrefix(escape) else {
                result.append(text[index])
                index = text.index(after: index)
                continue
            }
            var cursor = text.index(index, offsetBy: escape.count)
            guard cursor < text.endIndex else { return nil }
            let code = text[cursor]
            cursor = text.index(after: cursor)
            switch code {
            case "0": result.append("\0")
            case "\\": result.append("\\")
            case "t": result.append("\t")
            case "n": result.append("\n")
            case "r": result.append("\r")
            case "\"": result.append("\"")
            case "'": result.append("'")
            case "\n", "\r\n":
                break  // Line continuation in a multiline literal.
            case "u":
                guard cursor < text.endIndex, text[cursor] == "{",
                      let close = text[cursor...].firstIndex(of: "}") else { return nil }
                let digits = text[text.index(after: cursor)..<close]
                guard (1...8).contains(digits.count), let value = UInt32(digits, radix: 16),
                      let scalar = Unicode.Scalar(value) else { return nil }
                result.unicodeScalars.append(scalar)
                cursor = text.index(after: close)
            default:
                return nil
            }
            index = cursor
        }
        return result
    }

    static func parseInteger(_ text: String) -> Int64? {
        let digits = text.replacingOccurrences(of: "_", with: "")
        if digits.hasPrefix("0x") { return Int64(digits.dropFirst(2), radix: 16) }
        if digits.hasPrefix("0o") { return Int64(digits.dropFirst(2), radix: 8) }
        if digits.hasPrefix("0b") { return Int64(digits.dropFirst(2), radix: 2) }
        return Int64(digits)
    }

    // MARK: Attributes

    private func record(_ attributes: AttributeListSyntax, at name: TokenSyntax) {
        let key = position(of: name)
        for element in attributes {
            guard case .attribute(let attribute) = element else { continue }
            let attributeName = attribute.attributeName.trimmedDescription
            let arguments = attribute.arguments?.trimmedDescription
            facts.attributes.append(SyntaxAttribute(position: key, name: attributeName, arguments: arguments))
        }
    }

    override func visit(_ node: FunctionDeclSyntax) -> SyntaxVisitorContinueKind {
        record(node.attributes, at: node.name)
        recordFlow(at: node.name, parameters: node.signature.parameterClause.parameters, body: node.body)
        declare(at: node.name, parameters: node.signature.parameterClause.parameters,
                returnType: node.signature.returnClause?.type, modifiers: node.modifiers)
        return .visitChildren
    }

    override func visit(_ node: InitializerDeclSyntax) -> SyntaxVisitorContinueKind {
        record(node.attributes, at: node.initKeyword)
        recordFlow(at: node.initKeyword, parameters: node.signature.parameterClause.parameters, body: node.body)
        declare(at: node.initKeyword, parameters: node.signature.parameterClause.parameters, returnType: nil, modifiers: node.modifiers)
        return .visitChildren
    }

    override func visit(_ node: DeinitializerDeclSyntax) -> SyntaxVisitorContinueKind {
        let key = position(of: node.deinitKeyword)
        facts.declarations[key] = SyntaxDeclaration(
            position: key, parameterTypes: [], returnType: "()", isStatic: false
        )
        return .visitChildren
    }

    override func visit(_ node: VariableDeclSyntax) -> SyntaxVisitorContinueKind {
        for binding in node.bindings {
            if let identifier = binding.pattern.as(IdentifierPatternSyntax.self) {
                record(node.attributes, at: identifier.identifier)
                let key = position(of: identifier.identifier)
                facts.declarations[key] = SyntaxDeclaration(
                    position: key,
                    parameterTypes: [],
                    returnType: binding.typeAnnotation?.type.trimmedDescription,
                    isStatic: FactVisitor.isStatic(node.modifiers)
                )
            }
        }
        return .visitChildren
    }

    // MARK: Source-level parameter uses and return calls

    private func bindingName(_ token: TokenSyntax) -> String? {
        guard case .identifier = token.tokenKind else { return nil }
        return token.text.trimmingCharacters(in: CharacterSet(charactersIn: "`"))
    }

    /// Inspect only direct statements in this declaration's own body. A catch,
    /// closure, switch case or nested declaration is a different lexical scope.
    /// The normal visitor still records calls in all of them for the call graph.
    private func recordFlow(at declaration: TokenSyntax, parameters: FunctionParameterListSyntax, body: CodeBlockSyntax?) {
        let formals = parameters.enumerated().map { index, parameter in
            let token = parameter.secondName ?? parameter.firstName
            return SyntaxFormalParameter(index: index, name: bindingName(token), position: position(of: token))
        }
        var flow = SyntaxFunctionFlow(parameters: formals, calls: [:])
        let owner = position(of: declaration)
        defer { facts.functionFlows[owner] = flow }
        guard let body, !body.hasError else { return }
        var visible: [String: Int] = [:]
        for (parameter, formal) in zip(parameters, formals) {
            // Property wrappers can transform the bound value; inout parameters
            // can be overwritten. Do not present them as the incoming value.
            if let name = formal.name, parameter.attributes.isEmpty,
               !parameter.type.tokens(viewMode: .sourceAccurate).contains(where: { $0.tokenKind == .keyword(.inout) }) {
                visible[name] = formal.index
            }
        }
        // A local function can be referenced before its declaration. Other local
        // declarations (including conditional compilation) may introduce names
        // we do not resolve, so make no parameter claim in that case.
        for item in body.statements {
            if let function = item.item.as(FunctionDeclSyntax.self), let name = bindingName(function.name) {
                visible.removeValue(forKey: name)
            } else if item.item.is(DeclSyntax.self), !item.item.is(VariableDeclSyntax.self) {
                visible.removeAll()
            }
        }
        for item in body.statements {
            if let variable = item.item.as(VariableDeclSyntax.self) {
                for binding in variable.bindings {
                    if variable.attributes.isEmpty, let name = binding.pattern.as(IdentifierPatternSyntax.self)?.identifier,
                       let spelling = bindingName(name) {
                        visible.removeValue(forKey: spelling)
                    } else {
                        visible.removeAll()
                    }
                }
                continue
            }
            let returned = item.item.as(ReturnStmtSyntax.self)
            let expression = returned?.expression ?? item.item.as(ExprSyntax.self)
            guard let call = expression?.as(FunctionCallExprSyntax.self), let called = calledName(of: call.calledExpression) else {
                // A guard or a macro can introduce bindings into the subsequent
                // statements. For unsupported syntax stop attributing arguments.
                if !item.item.is(FunctionDeclSyntax.self) { visible.removeAll() }
                continue
            }
            var uses: [Int: Int] = [:]
            for (index, argument) in call.arguments.enumerated() {
                guard let ref = argument.expression.as(DeclReferenceExprSyntax.self), ref.argumentNames == nil,
                      let name = bindingName(ref.baseName), let parameter = visible[name] else { continue }
                uses[index] = parameter
            }
            if !uses.isEmpty || returned != nil {
                flow.calls[position(of: called)] = SyntaxCallFlow(parameterByArgument: uses, returnsResult: returned != nil)
            }
        }
    }

    // MARK: Declared signatures

    private func declare(at name: TokenSyntax, parameters: FunctionParameterListSyntax, returnType: TypeSyntax?, modifiers: DeclModifierListSyntax) {
        let key = position(of: name)
        let types = parameters.map { parameter -> String in
            var text = FactVisitor.strippingAttributes(parameter.type).trimmedDescription
            if parameter.ellipsis != nil { text += "..." }
            return text
        }
        facts.declarations[key] = SyntaxDeclaration(
            position: key,
            parameterTypes: types,
            returnType: returnType?.trimmedDescription,
            isStatic: FactVisitor.isStatic(modifiers)
        )
    }

    /// `@escaping (Int) -> Void` → `(Int) -> Void`; `inout` stays, as the demangler prints it.
    static func strippingAttributes(_ type: TypeSyntax) -> TypeSyntax {
        if let attributed = type.as(AttributedTypeSyntax.self), attributed.specifiers.isEmpty {
            return attributed.baseType
        }
        if let attributed = type.as(AttributedTypeSyntax.self), !attributed.attributes.isEmpty {
            return TypeSyntax(attributed.with(\.attributes, []))
        }
        return type
    }

    static func isStatic(_ modifiers: DeclModifierListSyntax) -> Bool {
        modifiers.contains { $0.name.tokenKind == .keyword(.static) || $0.name.tokenKind == .keyword(.class) }
    }

    override func visit(_ node: ClassDeclSyntax) -> SyntaxVisitorContinueKind {
        record(node.attributes, at: node.name)
        return .visitChildren
    }

    override func visit(_ node: ActorDeclSyntax) -> SyntaxVisitorContinueKind {
        record(node.attributes, at: node.name)
        return .visitChildren
    }

    override func visit(_ node: StructDeclSyntax) -> SyntaxVisitorContinueKind {
        record(node.attributes, at: node.name)
        return .visitChildren
    }

    override func visit(_ node: EnumDeclSyntax) -> SyntaxVisitorContinueKind {
        record(node.attributes, at: node.name)
        return .visitChildren
    }

    override func visit(_ node: ProtocolDeclSyntax) -> SyntaxVisitorContinueKind {
        record(node.attributes, at: node.name)
        return .visitChildren
    }

    override func visit(_ node: EnumCaseDeclSyntax) -> SyntaxVisitorContinueKind {
        for element in node.elements {
            record(node.attributes, at: element.name)
        }
        return .visitChildren
    }
}
