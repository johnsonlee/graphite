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

/// An attribute (`@available(...)`, `@objc`, `@Published`) on a declaration, keyed by the
/// position of the declared name.
public struct SyntaxAttribute: Equatable {
    public var position: SourcePosition
    public var name: String
    public var arguments: String?
}

/// What one parse of a file contributes: literal call arguments and declaration attributes.
public struct SyntaxFacts {
    public var calls: [SourcePosition: SyntaxCall] = [:]
    public var attributes: [SyntaxAttribute] = []

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
            var value = ""
            for segment in string.segments {
                guard case .stringSegment(let text) = segment else { return nil }
                value += text.content.text
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
        return .visitChildren
    }

    override func visit(_ node: InitializerDeclSyntax) -> SyntaxVisitorContinueKind {
        record(node.attributes, at: node.initKeyword)
        return .visitChildren
    }

    override func visit(_ node: VariableDeclSyntax) -> SyntaxVisitorContinueKind {
        for binding in node.bindings {
            if let identifier = binding.pattern.as(IdentifierPatternSyntax.self) {
                record(node.attributes, at: identifier.identifier)
            }
        }
        return .visitChildren
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
