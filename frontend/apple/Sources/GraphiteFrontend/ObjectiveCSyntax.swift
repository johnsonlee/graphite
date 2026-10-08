import Foundation

/// The Objective-C declaration pass: the types the index store does not carry for a
/// Clang declaration. It reads `@interface`, `@implementation` and `@protocol` blocks of a
/// `.h`, `.m` or `.mm` file for methods (`- (BOOL)storeValue:(NSString *)value
/// forKey:(NSString *)key`), properties and instance variables, and records each under
/// the position of its name, where the index store puts the declaration's occurrence.
///
/// Types are spelled as Clang spells them: `NSString *`, `NSArray<NSString *> *`,
/// `id<Auditing>`, `void (^)(BOOL, NSError *)`, `unsigned long long`, `instancetype`.
/// Nullability is part of the type: `nullable` and `_Nullable` (and the `nonnull` and
/// `null_unspecified` forms) become the `_Nullable`, `_Nonnull` and `_Null_unspecified`
/// suffixes Clang prints (`NSString * _Nullable`, `void (^ _Nonnull)(BOOL)`), and inside
/// an `NS_ASSUME_NONNULL_BEGIN` / `#pragma clang assume_nonnull begin` region an
/// unannotated pointer, `id`, `instancetype`, `Class` or block is `_Nonnull`. Ownership
/// qualifiers (`__weak`, `__strong`) and Interface Builder markers (`IBOutlet`) are not
/// part of the type; `IBAction` is `void`. Macros after a declaration
/// (`NS_SWIFT_NAME(...)`, `API_AVAILABLE(...)`) are skipped. Preprocessor lines, comments
/// and string literals are ignored, so the pass reads a file without compiling it.
public enum ObjectiveCSyntax {
    public static func parse(path: String) throws -> SyntaxFacts {
        let data = try Data(contentsOf: URL(fileURLWithPath: path))
        return parse(bytes: [UInt8](data), path: path)
    }

    public static func parse(source: String, path: String) -> SyntaxFacts {
        parse(bytes: Array(source.utf8), path: path)
    }

    static func parse(bytes: [UInt8], path: String) -> SyntaxFacts {
        var lexer = Lexer(bytes: bytes)
        var parser = Parser(tokens: lexer.tokenize(), path: path)
        parser.parseFile()
        return parser.facts
    }

    // MARK: Lexer

    struct Token: Equatable {
        enum Kind { case identifier, number, punctuation, string, pragma }
        var kind: Kind
        var text: String
        var line: Int
        /// 1-based UTF-8 column, as the index store counts.
        var column: Int
    }

    struct Lexer {
        let bytes: [UInt8]
        var index = 0
        var line = 1
        var lineStart = 0

        init(bytes: [UInt8]) { self.bytes = bytes }

        static func isIdentifierStart(_ byte: UInt8) -> Bool {
            (byte >= 0x41 && byte <= 0x5A) || (byte >= 0x61 && byte <= 0x7A) || byte == 0x5F || byte == 0x24 || byte >= 0x80
        }

        static func isIdentifier(_ byte: UInt8) -> Bool {
            isIdentifierStart(byte) || (byte >= 0x30 && byte <= 0x39)
        }

        static func isDigit(_ byte: UInt8) -> Bool { byte >= 0x30 && byte <= 0x39 }

        private func text(_ range: Range<Int>) -> String {
            String(decoding: bytes[range], as: UTF8.self)
        }

        private mutating func newline() {
            line += 1
            lineStart = index
        }

        mutating func tokenize() -> [Token] {
            var tokens: [Token] = []
            var atLineStart = true
            while index < bytes.count {
                let byte = bytes[index]
                if byte == 0x0A {
                    index += 1
                    newline()
                    atLineStart = true
                    continue
                }
                if byte == 0x20 || byte == 0x09 || byte == 0x0D || byte == 0x0C {
                    index += 1
                    continue
                }
                if byte == 0x2F, index + 1 < bytes.count, bytes[index + 1] == 0x2F {
                    while index < bytes.count, bytes[index] != 0x0A { index += 1 }
                    continue
                }
                if byte == 0x2F, index + 1 < bytes.count, bytes[index + 1] == 0x2A {
                    index += 2
                    while index < bytes.count {
                        if bytes[index] == 0x0A {
                            index += 1
                            newline()
                        } else if bytes[index] == 0x2A, index + 1 < bytes.count, bytes[index + 1] == 0x2F {
                            index += 2
                            break
                        } else {
                            index += 1
                        }
                    }
                    continue
                }
                let column = index - lineStart + 1
                if byte == 0x23, atLineStart {
                    // A preprocessor line, with its continuations; only the nullability pragma matters.
                    let start = index
                    var directive: [UInt8] = []
                    while index < bytes.count, bytes[index] != 0x0A {
                        if bytes[index] == 0x5C, index + 1 < bytes.count, bytes[index + 1] == 0x0A {
                            index += 2
                            newline()
                            directive.append(0x20)
                            continue
                        }
                        directive.append(bytes[index])
                        index += 1
                    }
                    let words = String(decoding: directive, as: UTF8.self).dropFirst().split(whereSeparator: { $0 == " " || $0 == "\t" })
                    if words.count >= 4, words[0] == "pragma", words[1] == "clang", words[2] == "assume_nonnull" {
                        tokens.append(Token(kind: .pragma, text: String(words[3]), line: line, column: start - lineStart + 1))
                    }
                    continue
                }
                atLineStart = false
                if byte == 0x22 || byte == 0x27 {
                    let quote = byte
                    let start = index
                    index += 1
                    while index < bytes.count, bytes[index] != quote {
                        if bytes[index] == 0x5C { index += 1 }
                        if index < bytes.count, bytes[index] == 0x0A { break }
                        index += 1
                    }
                    if index < bytes.count, bytes[index] == quote { index += 1 }
                    tokens.append(Token(kind: .string, text: text(start..<index), line: line, column: column))
                    continue
                }
                if byte == 0x40, index + 1 < bytes.count, Lexer.isIdentifierStart(bytes[index + 1]) {
                    let start = index
                    index += 1
                    while index < bytes.count, Lexer.isIdentifier(bytes[index]) { index += 1 }
                    tokens.append(Token(kind: .identifier, text: text(start..<index), line: line, column: column))
                    continue
                }
                if Lexer.isIdentifierStart(byte) {
                    let start = index
                    while index < bytes.count, Lexer.isIdentifier(bytes[index]) { index += 1 }
                    tokens.append(Token(kind: .identifier, text: text(start..<index), line: line, column: column))
                    continue
                }
                if Lexer.isDigit(byte) || (byte == 0x2E && index + 1 < bytes.count && Lexer.isDigit(bytes[index + 1])) {
                    let start = index
                    while index < bytes.count, Lexer.isIdentifier(bytes[index]) || bytes[index] == 0x2E { index += 1 }
                    tokens.append(Token(kind: .number, text: text(start..<index), line: line, column: column))
                    continue
                }
                if byte == 0x2E, index + 2 < bytes.count, bytes[index + 1] == 0x2E, bytes[index + 2] == 0x2E {
                    tokens.append(Token(kind: .punctuation, text: "...", line: line, column: column))
                    index += 3
                    continue
                }
                tokens.append(Token(kind: .punctuation, text: text(index..<index + 1), line: line, column: column))
                index += 1
            }
            return tokens
        }
    }

    // MARK: Parser

    struct Parser {
        let tokens: [Token]
        let path: String
        var index = 0
        var facts = SyntaxFacts()
        var assumeNonnull = false

        init(tokens: [Token], path: String) {
            self.tokens = tokens
            self.path = path
        }

        /// The next token, after applying any nullability region marker in the way.
        mutating func peek(_ offset: Int = 0) -> Token? {
            while index < tokens.count {
                let token = tokens[index]
                if token.kind == .pragma {
                    assumeNonnull = token.text == "begin"
                } else if token.text == "NS_ASSUME_NONNULL_BEGIN" {
                    assumeNonnull = true
                } else if token.text == "NS_ASSUME_NONNULL_END" {
                    assumeNonnull = false
                } else {
                    break
                }
                index += 1
            }
            var position = index
            var remaining = offset
            while position < tokens.count {
                let token = tokens[position]
                let isMarker = token.kind == .pragma || token.text == "NS_ASSUME_NONNULL_BEGIN" || token.text == "NS_ASSUME_NONNULL_END"
                if !isMarker {
                    if remaining == 0 { return token }
                    remaining -= 1
                }
                position += 1
            }
            return nil
        }

        @discardableResult
        mutating func advance() -> Token? {
            guard let token = peek() else { return nil }
            index += 1
            return token
        }

        mutating func parseFile() {
            while let token = peek() {
                switch token.text {
                case "@interface", "@implementation", "@protocol": parseContainer()
                default: parseCDeclaration()
                }
            }
        }

        /// The tokens that start a container member, at which a C declaration or an
        /// instance variable block that was never closed ends.
        static let memberMarkers: Set<String> = [
            "-", "+", "@property", "@end", "@interface", "@implementation", "@protocol",
            "@synthesize", "@dynamic", "@required", "@optional",
        ]


        mutating func parseContainer() {
            guard let keyword = advance() else { return }
            guard let name = peek(), name.kind == .identifier else { return }
            advance()
            if keyword.text == "@protocol", let next = peek(), next.text == ";" || next.text == "," {
                // A forward declaration: `@protocol A, B;`.
                while let token = advance(), token.text != ";" {}
                return
            }
            // The rest of the header: a category or class extension `(Name)` / `()`, generic
            // parameters `<T>`, a superclass `: Super`, a protocol list `<P, Q>`, in any order.
            // Only a `{` attached to that header opens the instance variable block; a `{`
            // later in the body (`typedef NS_ENUM(...) { ... };`, an `enum` or `struct`
            // declared between the header and the first method) is a declaration body of
            // its own and is skipped as a group.
            var headerEnded = false
            while !headerEnded, let token = peek() {
                switch token.text {
                case "(", "<":
                    skipBalanced()
                case ":":
                    advance()
                    if let superclass = peek(), superclass.kind == .identifier, !superclass.text.hasPrefix("@") { advance() }
                default:
                    headerEnded = true
                }
            }
            if let brace = peek(), brace.text == "{", keyword.text != "@protocol" {
                parseInstanceVariables()
            }
            while let token = peek() {
                switch token.text {
                case "@end":
                    advance()
                    return
                case "@interface", "@implementation", "@protocol":
                    return
                case "-", "+":
                    parseMethod(isStatic: token.text == "+")
                case "@property":
                    parseProperty()
                case "@synthesize":
                    parseSynthesize()
                case "@required", "@optional":
                    advance()
                case "{", "(", "[":
                    skipBalanced()
                default:
                    // A C function or global declared inside an `@implementation`.
                    parseCDeclaration()
                }
            }
        }

        /// The tokens of a balanced group at the cursor, brackets included.
        mutating func groupTokens() -> [Token] {
            guard let open = peek() else { return [] }
            let inner = balancedGroup()
            let close: String
            switch open.text {
            case "(": close = ")"
            case "{": close = "}"
            case "[": close = "]"
            default: close = ">"
            }
            let last = inner.last ?? open
            return [open] + inner + [Token(kind: .punctuation, text: close, line: last.line, column: last.column + last.text.utf8.count)]
        }

        // MARK: C declarations

        static let declarationSpecifiers: Set<String> = [
            "static", "extern", "inline", "__inline", "__inline__", "register", "__extension__", "auto",
        ]
        static let cTypeKeywords: Set<String> = [
            "void", "char", "short", "int", "long", "float", "double", "signed", "unsigned", "_Bool", "bool",
            "struct", "union", "enum", "const", "volatile", "id", "instancetype", "Class", "SEL",
        ]

        /// A C declaration at file scope or inside an `@implementation`: a function
        /// declaration or definition (`void MsgReset(int mode);`, `static inline int helper(int
        /// x) { ... }`), one or more globals (`extern NSString *const kTag;`, `static int a = 1,
        /// b;`, `int (*hook)(int);`), a `struct` or `union` body whose fields are recorded,
        /// or something skipped (`typedef`, an `enum` body, `@class`, `@import`). Consumes at
        /// least one token.
        mutating func parseCDeclaration() {
            let start = index
            guard let first = peek() else { return }
            if first.text.hasPrefix("@") {
                // `@class A, B;`, `@import M;`, `@compatibility_alias X Y;`, `@dynamic p;`
                while let token = advance(), token.text != ";" {}
                if index == start { advance() }
                return
            }
            var tokens: [Token] = []
            var terminated = false
            var sawBody = false
            while let token = peek() {
                if Parser.memberMarkers.contains(token.text) { break }
                if token.text == ";" {
                    advance()
                    terminated = true
                    break
                }
                if token.text == "(" || token.text == "[" {
                    tokens += groupTokens()
                    continue
                }
                if token.text == "{" {
                    if tokens.first?.text != "typedef", tokens.contains(where: { $0.text == "=" }) == false, Parser.functionHeader(tokens) != nil {
                        recordFunction(tokens)
                        skipBalanced()
                        return
                    }
                    if tokens.contains(where: { $0.text == "=" }) {
                        tokens += groupTokens()
                        continue
                    }
                    sawBody = true
                    if tokens.contains(where: { $0.text == "struct" || $0.text == "union" }) {
                        parseInstanceVariables()
                    } else {
                        skipBalanced()
                    }
                    continue
                }
                tokens.append(advance()!)
            }
            if tokens.isEmpty {
                if index == start { advance() }
                return
            }
            guard terminated, !sawBody, tokens[0].text != "typedef" else { return }
            if Parser.functionHeader(tokens) != nil {
                recordFunction(tokens)
            } else {
                recordGlobals(tokens)
            }
        }

        /// Trailing `__attribute__((...))`, `NS_SWIFT_NAME(...)` and bare marker macros removed.
        static func withoutTrailingMacros(_ tokens: [Token]) -> [Token] {
            var tokens = tokens
            while let last = tokens.last {
                if last.text == ")" {
                    var depth = 0
                    var open = tokens.count - 1
                    while open >= 0 {
                        if tokens[open].text == ")" { depth += 1 }
                        if tokens[open].text == "(" {
                            depth -= 1
                            if depth == 0 { break }
                        }
                        open -= 1
                    }
                    guard open > 0, tokens[open - 1].kind == .identifier,
                          tokens[open - 1].text.hasPrefix("__attribute") || isMarkerMacro(tokens[open - 1].text) else { break }
                    tokens.removeSubrange((open - 1)...)
                } else if last.kind == .identifier, isMarkerMacro(last.text) {
                    tokens.removeLast()
                } else {
                    break
                }
            }
            return tokens
        }

        /// `name(params)` at the end of a declaration: the index of the name token and the
        /// parameter tokens, when the tokens declare a function.
        static func functionHeader(_ tokens: [Token]) -> (name: Int, parameters: [Token])? {
            let tokens = withoutTrailingMacros(tokens)
            guard let last = tokens.last, last.text == ")", !tokens.contains(where: { $0.text == "=" }) else { return nil }
            var depth = 0
            var open = tokens.count - 1
            while open >= 0 {
                if tokens[open].text == ")" { depth += 1 }
                if tokens[open].text == "(" {
                    depth -= 1
                    if depth == 0 { break }
                }
                open -= 1
            }
            guard open > 0 else { return nil }
            let name = tokens[open - 1]
            guard name.kind == .identifier, !name.text.hasPrefix("@"), !name.text.hasPrefix("__attribute"),
                  !isMarkerMacro(name.text), !cTypeKeywords.contains(name.text) else { return nil }
            return (open - 1, Array(tokens[(open + 1)..<(tokens.count - 1)]))
        }

        mutating func recordFunction(_ tokens: [Token]) {
            guard let header = Parser.functionHeader(tokens) else { return }
            let name = tokens[header.name]
            let returnTokens = tokens[..<header.name].filter { !Parser.declarationSpecifiers.contains($0.text) }
            let position = SourcePosition(path: path, line: name.line, column: name.column)
            facts.declarations[position] = SyntaxDeclaration(
                position: position,
                parameterTypes: parameterTypes(header.parameters),
                returnType: spell(Array(returnTokens), assumeNonnull: assumeNonnull),
                isStatic: true
            )
        }

        /// The parameter types of a C parameter list: names dropped, `void` and `...` are no
        /// parameters.
        func parameterTypes(_ tokens: [Token]) -> [String] {
            Parser.splitTopLevel(tokens).compactMap { parameter -> String? in
                var parameter = parameter.filter { $0.text != "register" }
                if parameter.isEmpty || parameter.map(\.text) == ["void"] || parameter.map(\.text) == ["..."] { return nil }
                if parameter.last?.text == ")" || parameter.last?.text == "]" {
                    // A block or function pointer names the parameter inside its group; an
                    // array parameter before its brackets.
                    if let split = Parser.splitDeclarator(parameter), !split.type.isEmpty { parameter = split.type }
                } else if parameter.count >= 2, let last = parameter.last, last.kind == .identifier,
                          !Parser.cTypeKeywords.contains(last.text), Parser.nullabilityKeywords[last.text] == nil,
                          parameter[parameter.count - 2].text != "." {
                    parameter.removeLast()
                }
                return spell(parameter, assumeNonnull: assumeNonnull)
            }
        }

        mutating func recordGlobals(_ tokens: [Token]) {
            let tokens = tokens.filter { !Parser.declarationSpecifiers.contains($0.text) }
            for (typeTokens, name) in Parser.declarators(of: tokens) {
                let position = SourcePosition(path: path, line: name.line, column: name.column)
                let type = spell(typeTokens, assumeNonnull: assumeNonnull)
                facts.declarations[position] = SyntaxDeclaration(position: position, parameterTypes: [], returnType: type, isStatic: true)
            }
        }

        /// Splits at top-level commas: `(`, `[`, `{` nest, and `<...>` nests before any `=`
        /// (a generic argument list; after `=` a `<` is a comparison in an initializer).
        static func splitTopLevel(_ tokens: [Token]) -> [[Token]] {
            var pieces: [[Token]] = [[]]
            var depth = 0
            var angle = 0
            var initializer = false
            for token in tokens {
                switch token.text {
                case "(", "[", "{": depth += 1
                case ")", "]", "}": depth -= 1
                case "<" where !initializer: angle += 1
                case ">" where !initializer && angle > 0: angle -= 1
                case "=" where depth == 0 && angle == 0: initializer = true
                case "," where depth == 0 && angle == 0:
                    pieces.append([])
                    initializer = false
                    continue
                default: break
                }
                pieces[pieces.count - 1].append(token)
            }
            return pieces.filter { !$0.isEmpty }
        }

        /// `@synthesize a = _a, b;`: each instance variable's position maps to its property.
        mutating func parseSynthesize() {
            advance()
            var group: [Token] = []
            while let token = peek(), token.text != ";", !Parser.memberMarkers.contains(token.text) { group.append(advance()!) }
            if let token = peek(), token.text == ";" { advance() }
            for piece in Parser.splitTopLevel(group) {
                guard let property = piece.first, property.kind == .identifier else { continue }
                let ivar = piece.count >= 3 && piece[1].text == "=" && piece[2].kind == .identifier ? piece[2] : property
                facts.synthesized[SourcePosition(path: path, line: ivar.line, column: ivar.column)] = property.text
            }
        }

        /// The tokens inside a balanced `(...)`, `{...}`, `[...]` or `<...>` group at the
        /// cursor; the cursor moves past the closing bracket.
        mutating func balancedGroup() -> [Token] {
            guard let open = advance() else { return [] }
            let close: String
            switch open.text {
            case "(": close = ")"
            case "{": close = "}"
            case "[": close = "]"
            default: close = ">"
            }
            var depth = 1
            var inner: [Token] = []
            while let token = advance() {
                if token.text == open.text { depth += 1 }
                if token.text == close {
                    depth -= 1
                    if depth == 0 { return inner }
                }
                inner.append(token)
            }
            return inner
        }

        mutating func skipBalanced() {
            _ = balancedGroup()
        }

        /// Skips a declaration's trailing macros up to its `;`, or an implementation's body.
        mutating func skipToEndOfMember() {
            while let token = peek() {
                switch token.text {
                case ";":
                    advance()
                    return
                case "{":
                    skipBalanced()
                    return
                case "(", "[":
                    skipBalanced()
                case "@end", "-", "+", "@property":
                    return
                default:
                    advance()
                }
            }
        }

        mutating func parseMethod(isStatic: Bool) {
            advance()
            var returnType = spell([], assumeNonnull: assumeNonnull)
            if let open = peek(), open.text == "(" {
                returnType = spell(balancedGroup(), assumeNonnull: assumeNonnull)
            }
            guard let first = peek(), first.kind == .identifier, !first.text.hasPrefix("@") else {
                skipToEndOfMember()
                return
            }
            advance()
            var parameterTypes: [String] = []
            while let colon = peek(), colon.text == ":" {
                advance()
                if let open = peek(), open.text == "(" {
                    parameterTypes.append(spell(balancedGroup(), assumeNonnull: assumeNonnull))
                } else {
                    parameterTypes.append(spell([], assumeNonnull: assumeNonnull))
                }
                if let name = peek(), name.kind == .identifier { advance() }
                // The next selector part is an identifier followed by `:`; an empty part is a bare `:`.
                if let next = peek(), next.kind == .identifier, let after = peek(1), after.text == ":" {
                    advance()
                }
            }
            let position = SourcePosition(path: path, line: first.line, column: first.column)
            facts.declarations[position] = SyntaxDeclaration(position: position, parameterTypes: parameterTypes, returnType: returnType, isStatic: isStatic)
            skipToEndOfMember()
        }

        mutating func parseProperty() {
            advance()
            var isStatic = false
            var nullability: String?
            var getter: Token?
            var setter: Token?
            if let open = peek(), open.text == "(" {
                let attributes = balancedGroup()
                for (offset, attribute) in attributes.enumerated() {
                    if attribute.text == "class" { isStatic = true }
                    if let spelled = Parser.nullabilityKeywords[attribute.text] { nullability = spelled }
                    // `getter=wasRead`, `setter=setRead:`: the accessors the index declares at
                    // these names.
                    if offset + 2 < attributes.count, attributes[offset + 1].text == "=", attributes[offset + 2].kind == .identifier {
                        if attribute.text == "getter" { getter = attributes[offset + 2] }
                        if attribute.text == "setter" { setter = attributes[offset + 2] }
                    }
                }
            }
            for (typeTokens, name) in declarators(until: ";") {
                let position = SourcePosition(path: path, line: name.line, column: name.column)
                let type = spell(typeTokens, assumeNonnull: assumeNonnull, nullability: nullability)
                facts.declarations[position] = SyntaxDeclaration(position: position, parameterTypes: [], returnType: type, isStatic: isStatic)
                if let getter {
                    let at = SourcePosition(path: path, line: getter.line, column: getter.column)
                    facts.declarations[at] = SyntaxDeclaration(position: at, parameterTypes: [], returnType: type, isStatic: isStatic)
                }
                if let setter {
                    let at = SourcePosition(path: path, line: setter.line, column: setter.column)
                    facts.declarations[at] = SyntaxDeclaration(position: at, parameterTypes: [type], returnType: "void", isStatic: isStatic)
                }
                getter = nil
                setter = nil
            }
        }

        /// The `{ ... }` block after an `@interface` or `@implementation` header. Every
        /// turn of the loop consumes at least one token or returns: a member marker
        /// (`-`, `+`, `@property`, `@end`) inside the block means the block was never
        /// closed, and the members are the container's.
        mutating func parseInstanceVariables() {
            advance()
            while let token = peek() {
                switch token.text {
                case "}":
                    advance()
                    return
                case "-", "+", "@property", "@end", "@interface", "@implementation", "@protocol":
                    return
                case "@public", "@private", "@protected", "@package", ";":
                    advance()
                case "{", "(", "[":
                    skipBalanced()
                default:
                    let before = index
                    for (typeTokens, name) in declarators(until: ";") {
                        let position = SourcePosition(path: path, line: name.line, column: name.column)
                        let type = spell(typeTokens, assumeNonnull: assumeNonnull)
                        facts.declarations[position] = SyntaxDeclaration(position: position, parameterTypes: [], returnType: type, isStatic: false)
                    }
                    if index == before { advance() }
                }
            }
        }

        /// The declarators of one declaration up to `terminator`: `int a, *b;` gives
        /// (`int`, a) and (`int *`, b); `void (^done)(BOOL)` gives (`void (^)(BOOL)`, done);
        /// `char name[8]` gives (`char[8]`, name). The cursor moves past the terminator; a
        /// member marker ends an unterminated declaration and stays for the caller.
        mutating func declarators(until terminator: String) -> [(type: [Token], name: Token)] {
            var tokens: [Token] = []
            var depth = 0
            while let next = peek() {
                if Parser.memberMarkers.contains(next.text) { break }
                let token = advance()!
                if token.text == terminator && depth == 0 { break }
                switch token.text {
                case "(", "{", "[", "<": depth += 1
                case ")", "}", "]", ">": depth -= 1
                default: break
                }
                tokens.append(token)
            }
            return Parser.declarators(of: tokens)
        }

        /// The declarators of a declaration's tokens, initializers dropped: the first
        /// declarator's base type (its trailing `*`s and array suffix removed) is shared by
        /// the others.
        static func declarators(of tokens: [Token]) -> [(type: [Token], name: Token)] {
            var result: [(type: [Token], name: Token)] = []
            var base: [Token] = []
            for (position, piece) in splitTopLevel(tokens).enumerated() {
                let declarator = piece.firstIndex(where: { $0.text == "=" }).map { Array(piece[..<$0]) } ?? piece
                guard let split = splitDeclarator(declarator), !(position == 0 && split.type.isEmpty) else { continue }
                var type = split.type
                if position == 0 {
                    base = type
                    while let last = base.last, last.text == "*" || last.text == "[" || last.text == "]" || last.kind == .number { base.removeLast() }
                } else {
                    type = base + type
                }
                result.append((type, split.name))
            }
            return result
        }

        /// The name token of a declarator and the tokens of its type with the name removed.
        static func splitDeclarator(_ tokens: [Token]) -> (type: [Token], name: Token)? {
            // A block `void (^name)(int)` or a function pointer `int (*name)(int)` names the
            // declarator inside the `(^...)` / `(*...)` group.
            var depth = 0
            for (position, token) in tokens.enumerated()
            where (token.text == "^" || token.text == "*") && position > 0 && tokens[position - 1].text == "(" {
                var nameIndex: Int? = nil
                var cursor = position + 1
                depth = 0
                while cursor < tokens.count {
                    let candidate = tokens[cursor]
                    if candidate.text == ")" && depth == 0 { break }
                    if candidate.text == "(" { depth += 1 }
                    if candidate.text == ")" { depth -= 1 }
                    if candidate.kind == .identifier, nullabilityKeywords[candidate.text] == nil, !Parser.droppedQualifiers.contains(candidate.text) {
                        nameIndex = cursor
                    }
                    cursor += 1
                }
                if let nameIndex {
                    var type = tokens
                    type.remove(at: nameIndex)
                    return (type, tokens[nameIndex])
                }
            }
            // Otherwise the last top-level identifier that is neither a macro invocation
            // (`NS_SWIFT_NAME(...)`, `__attribute__((...))`) nor a trailing marker macro
            // (`DEPRECATED_ATTRIBUTE`, `NS_REFINED_FOR_SWIFT`: upper case with underscores,
            // after the name); what follows the name (an array suffix, the macros) is not
            // part of the type, an array suffix is.
            depth = 0
            var nameIndex: Int? = nil
            for (position, token) in tokens.enumerated() {
                switch token.text {
                case "(", "<", "[", "{": depth += 1
                case ")", ">", "]", "}": depth -= 1
                default: break
                }
                guard depth == 0, token.kind == .identifier else { continue }
                if position + 1 < tokens.count, tokens[position + 1].text == "(" { continue }
                if nameIndex != nil, Parser.isMarkerMacro(token.text) { continue }
                nameIndex = position
            }
            guard let nameIndex else { return nil }
            var type = Array(tokens[..<nameIndex])
            var cursor = nameIndex + 1
            while cursor < tokens.count, tokens[cursor].text == "[" {
                while cursor < tokens.count {
                    type.append(tokens[cursor])
                    cursor += 1
                    if type.last?.text == "]" { break }
                }
            }
            return (type, tokens[nameIndex])
        }

        static func isMarkerMacro(_ text: String) -> Bool {
            text.contains("_") && text.unicodeScalars.allSatisfy { $0.properties.isUppercase || $0 == "_" || $0.properties.numericType != nil }
        }

        static let nullabilityKeywords: [String: String] = [
            "nullable": "_Nullable", "_Nullable": "_Nullable", "__nullable": "_Nullable",
            "nonnull": "_Nonnull", "_Nonnull": "_Nonnull", "__nonnull": "_Nonnull",
            "null_unspecified": "_Null_unspecified", "_Null_unspecified": "_Null_unspecified", "__null_unspecified": "_Null_unspecified",
        ]

        /// Not part of the type Clang spells: ownership and Interface Builder markers.
        static let droppedQualifiers: Set<String> = [
            "__weak", "__strong", "__unsafe_unretained", "__autoreleasing", "__block",
            "IBOutlet", "IBInspectable", "NS_NOESCAPE", "NS_SWIFT_SENDABLE", "null_resettable",
        ]

        static let pointerLikeNames: Set<String> = ["id", "instancetype", "Class", "SEL"]

        /// The type the tokens spell, the way Clang prints it, with its nullability.
        func spell(_ tokens: [Token], assumeNonnull: Bool, nullability explicit: String? = nil) -> String {
            var words: [String] = []
            var nullability = explicit
            var inlineNullability = false
            var skipGroup = false
            var depth = 0
            var skipDepth = 0
            for token in tokens {
                if skipGroup {
                    if token.text == "(" { skipDepth += 1 }
                    if token.text == ")" {
                        skipDepth -= 1
                        if skipDepth == 0 { skipGroup = false }
                    }
                    continue
                }
                if let spelled = Parser.nullabilityKeywords[token.text] {
                    if token.text.hasPrefix("_") {
                        // `_Nullable` stays where it is written, normalized; one at the top
                        // level is the type's own.
                        words.append(spelled)
                        if depth == 0 { inlineNullability = true }
                    } else {
                        nullability = spelled
                    }
                    continue
                }
                switch token.text {
                case "(", "<", "[": depth += 1
                case ")", ">", "]": depth -= 1
                default: break
                }
                if Parser.droppedQualifiers.contains(token.text) || Parser.declarationSpecifiers.contains(token.text) { continue }
                if token.text == "IBOutletCollection" || token.text.hasPrefix("__attribute") {
                    skipGroup = true
                    continue
                }
                if Parser.isMarkerMacro(token.text) {
                    // `UIKIT_EXTERN`, `CF_RETURNS_RETAINED`: a marker, with its arguments when it has any.
                    if let next = tokens.firstIndex(where: { $0 == token }), next + 1 < tokens.count, tokens[next + 1].text == "(" { skipGroup = true }
                    continue
                }
                if token.text == "IBAction" {
                    words.append("void")
                    continue
                }
                words.append(token.text)
            }
            if words.isEmpty { words = ["id"] }
            var text = ""
            var previous = ""
            for word in words {
                let identifierLike = Parser.isWord(word)
                var space = false
                if !text.isEmpty {
                    if identifierLike {
                        // `char *const`, as Clang spells a qualified pointer.
                        let qualifier = word == "const" || word == "volatile" || word == "restrict" || word == "__restrict"
                        space = Parser.isWord(previous) || (previous == "*" && !qualifier) || previous == "," || previous == "^" || previous == ")"
                    } else if word == "*" {
                        space = Parser.isWord(previous) || previous == ">" || previous == ")"
                    } else if word == "(" {
                        space = Parser.isWord(previous) || previous == "*"
                    } else if word == "..." {
                        space = previous == ","
                    }
                }
                if space { text += " " }
                text += word
                previous = word
            }
            if !inlineNullability {
                let pointerLike = words.contains("*") || words.contains("^") || Parser.pointerLikeNames.contains(words[0])
                if nullability == nil, assumeNonnull, pointerLike { nullability = "_Nonnull" }
                if let nullability, pointerLike {
                    if let range = text.range(of: "(^)") {
                        text.replaceSubrange(range, with: "(^ \(nullability))")
                    } else {
                        text += " " + nullability
                    }
                }
            }
            return text
        }

        static func isWord(_ text: String) -> Bool {
            guard let first = text.unicodeScalars.first else { return false }
            return first.properties.isAlphabetic || first == "_" || first == "$" || first.properties.numericType != nil
        }
    }
}
