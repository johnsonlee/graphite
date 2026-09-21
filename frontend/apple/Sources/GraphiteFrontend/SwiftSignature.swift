import Foundation

/// The parts of a Swift declaration the core model's `MethodDescriptor` / `FieldDescriptor`
/// need, as recovered from a demangled symbol.
public struct SwiftSignature: Equatable {
    /// Fully qualified declaring type, `Module.Outer.Inner`; the module for free functions.
    public var declaringType: String
    /// Parameter types as the demangler prints them (`Swift.String`, `[Swift.Int]`, `A.Element?`).
    public var parameterTypes: [String]
    /// Return type; `Swift.Void` for `()`, the property type for a property.
    public var returnType: String
    public var isStatic: Bool

    public init(declaringType: String, parameterTypes: [String] = [], returnType: String = "", isStatic: Bool = false) {
        self.declaringType = declaringType
        self.parameterTypes = parameterTypes
        self.returnType = returnType
        self.isStatic = isStatic
    }

    /// Parses `swift-demangle` output for a declaration whose base name is `name`
    /// (`checkout` for `checkout(order:)`, `init`, `<=`, `maxItems`).
    ///
    /// Handles the forms the index store produces for declarations and call targets:
    ///
    ///     AcmeShop.CartService.checkout(order: AcmeShop.Order) -> Swift.Bool
    ///     static AcmeShop.FeatureFlags.isEnabled(_: Swift.String, default: Swift.Bool) -> Swift.Bool
    ///     (extension in Ext):Swift.String.shout(times: Swift.Int) -> Swift.String
    ///     static Swift.Int.<= infix(Swift.Int, Swift.Int) -> Swift.Bool
    ///     (extension in Swift):Swift.Collection.map<A, B where B1: Swift.Error>((A.Element) throws(B1) -> A1) throws(B1) -> [A1]
    ///     AcmeShop.CartService.orders : [Swift.String : AcmeShop.Order]
    ///     AcmeShop.CartService.(analytics in _D6064BEB0F43E13BEFB41E8D8C9242BD) : AcmeShop.Analytics
    ///
    /// Returns nil for closures, parameters and anything else it does not understand.
    public static func parse(_ demangled: String, name: String) -> SwiftSignature? {
        var text = demangled.trimmingCharacters(in: .whitespaces)
        var isStatic = false
        for prefix in ["static ", "class "] where text.hasPrefix(prefix) {
            text.removeFirst(prefix.count)
            isStatic = true
        }
        if text.hasPrefix("(extension in ") {
            guard let colon = text.range(of: "):") else { return nil }
            text = String(text[colon.upperBound...])
        }
        if text.hasPrefix("closure #") || text.hasPrefix("implicit closure #") || text.contains(" in closure #") {
            return nil
        }

        // Property: `Qualified.name : Type`
        if let colon = topLevelRange(of: " : ", in: text), !text[..<colon.lowerBound].contains("(") {
            let qualified = String(text[..<colon.lowerBound])
            guard let declaring = dropLastComponent(of: qualified, expecting: name) else { return nil }
            return SwiftSignature(
                declaringType: declaring,
                parameterTypes: [],
                returnType: normalize(String(text[colon.upperBound...])),
                isStatic: isStatic
            )
        }
        // Private property: `Qualified.(name in _HASH) : Type`
        if let open = text.range(of: ".(\(name) in "), let close = text.range(of: ") : ", range: open.upperBound..<text.endIndex) {
            return SwiftSignature(
                declaringType: String(text[..<open.lowerBound]),
                parameterTypes: [],
                returnType: normalize(String(text[close.upperBound...])),
                isStatic: isStatic
            )
        }

        // Function: `Qualified.name[ infix|prefix|postfix][<generics>](params)[ async][ throws[(E)]] -> Ret`
        guard let head = functionHead(in: text, name: name) else { return nil }
        let declaring = head.qualifier
        var rest = text[head.paramsStart...]
        guard rest.first == "(" , let close = matchingParen(in: rest, from: rest.startIndex) else { return nil }
        let paramList = rest[rest.index(after: rest.startIndex)..<close]
        let parameters = splitTopLevel(paramList, separator: ",").map { stripLabel(normalize($0)) }
        rest = rest[rest.index(after: close)...]
        var returnType = "Swift.Void"
        if let arrow = topLevelRange(of: "->", in: String(rest)) {
            returnType = normalize(String(String(rest)[arrow.upperBound...]))
        }
        return SwiftSignature(
            declaringType: declaring,
            parameterTypes: parameters,
            returnType: returnType,
            isStatic: isStatic
        )
    }

    private struct FunctionHead {
        var qualifier: String
        var paramsStart: String.Index
    }

    /// Finds `.name(`, `.name infix(` or `.name<...>(` and returns the qualifier before it.
    private static func functionHead(in text: String, name: String) -> FunctionHead? {
        let needle = ".\(name)"
        var search = text.startIndex
        while let hit = text.range(of: needle, range: search..<text.endIndex) {
            var cursor = hit.upperBound
            for fixity in [" infix", " prefix", " postfix"] where text[cursor...].hasPrefix(fixity) {
                cursor = text.index(cursor, offsetBy: fixity.count)
            }
            if cursor < text.endIndex, text[cursor] == "<", let close = matchingBracket(in: text, from: cursor) {
                cursor = text.index(after: close)
            }
            if cursor < text.endIndex, text[cursor] == "(" {
                return FunctionHead(qualifier: String(text[..<hit.lowerBound]), paramsStart: cursor)
            }
            search = text.index(after: hit.lowerBound)
        }
        // A free function: `Module.name(` is covered above; `name(` alone is not a Swift symbol.
        return nil
    }

    private static func dropLastComponent(of qualified: String, expecting name: String) -> String? {
        guard qualified.hasSuffix(".\(name)") else { return nil }
        return String(qualified.dropLast(name.count + 1))
    }

    /// `label: Type` → `Type`; a label is an identifier or `_` before the first top-level colon.
    static func stripLabel(_ parameter: String) -> String {
        guard let colon = parameter.firstIndex(of: ":") else { return parameter }
        let label = parameter[..<colon]
        let isLabel = !label.isEmpty && label.allSatisfy { $0.isLetter || $0.isNumber || $0 == "_" }
        guard isLabel else { return parameter }
        return normalize(String(parameter[parameter.index(after: colon)...]))
    }

    static func normalize(_ type: String) -> String {
        let trimmed = type.trimmingCharacters(in: .whitespaces)
        return trimmed == "()" ? "Swift.Void" : trimmed
    }

    /// Splits at top-level occurrences of `separator`, ignoring anything inside (), []
    /// and <>. The `>` of a function arrow (`->`) closes nothing.
    static func splitTopLevel(_ text: Substring, separator: Character) -> [String] {
        var parts: [String] = []
        var depth = 0
        var current = ""
        var previous: Character = " "
        for ch in text {
            switch ch {
            case "(", "[", "<": depth += 1
            case ")", "]": depth -= 1
            case ">" where previous != "-": depth -= 1
            default: break
            }
            if ch == separator && depth == 0 {
                parts.append(current)
                current = ""
            } else {
                current.append(ch)
            }
            previous = ch
        }
        let trimmed = current.trimmingCharacters(in: .whitespaces)
        if !trimmed.isEmpty || !parts.isEmpty { parts.append(current) }
        return parts.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }

    static func topLevelRange(of needle: String, in text: String) -> Range<String.Index>? {
        var depth = 0
        var index = text.startIndex
        while index < text.endIndex {
            let ch = text[index]
            switch ch {
            case "(", "[": depth += 1
            case ")", "]": depth -= 1
            default: break
            }
            if depth == 0, text[index...].hasPrefix(needle) {
                return index..<text.index(index, offsetBy: needle.count)
            }
            index = text.index(after: index)
        }
        return nil
    }

    static func matchingParen(in text: Substring, from open: Substring.Index) -> Substring.Index? {
        var depth = 0
        var index = open
        while index < text.endIndex {
            switch text[index] {
            case "(": depth += 1
            case ")":
                depth -= 1
                if depth == 0 { return index }
            default: break
            }
            index = text.index(after: index)
        }
        return nil
    }

    static func matchingBracket(in text: String, from open: String.Index) -> String.Index? {
        var depth = 0
        var index = open
        var previous: Character = " "
        while index < text.endIndex {
            switch text[index] {
            case "<": depth += 1
            case ">" where previous != "-":
                depth -= 1
                if depth == 0 { return index }
            default: break
            }
            previous = text[index]
            index = text.index(after: index)
        }
        return nil
    }
}
