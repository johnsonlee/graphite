package io.johnsonlee.graphite.cypher

/**
 * Parses a Cypher query string into a list of [CypherClause] AST nodes.
 *
 * This is a hand-written recursive descent parser that covers the full Cypher
 * read grammar (MATCH, OPTIONAL MATCH, WHERE, RETURN, WITH, UNWIND, ORDER BY,
 * SKIP, LIMIT, UNION) plus write clauses (CREATE, DELETE, SET, REMOVE) for
 * structural completeness.
 *
 * ## Design rationale
 *
 * The neo4j-cypher-dsl library (version 2025.0.1) exposes parsed queries via
 * `StatementCatalog`, which provides catalog-level metadata (labels, property
 * filters, relationship types) but does not expose the full AST in a form
 * suitable for clause-by-clause execution. Clause ordering, WITH projections,
 * UNWIND bindings, and ORDER BY expressions are not directly accessible.
 *
 * A hand-written parser gives full control over the AST shape and avoids
 * coupling to the internal structure of the DSL builder objects.
 */
object CypherDslAdapter {

    /**
     * Parse a Cypher query string into an ordered list of clauses.
     *
     * @param cypher the Cypher query text
     * @return ordered list of [CypherClause] representing the query structure
     * @throws CypherParseException if the query cannot be parsed
     */
    fun parse(cypher: String): List<CypherClause> {
        val tokens = CypherTokenizer.tokenize(cypher)
        val parser = ClauseParser(tokens)
        return parser.parseClauses()
    }
}

/**
 * Exception thrown when the Cypher parser encounters invalid syntax.
 */
class CypherParseException(message: String) : RuntimeException(message)

// ============================================================================
// Token types
// ============================================================================

internal enum class TokenType {
    MATCH, OPTIONAL, WHERE, RETURN, WITH, UNWIND, ORDER, BY,
    SKIP, LIMIT, UNION, ALL, CREATE, DELETE, DETACH, SET, REMOVE, MERGE,
    AS, DISTINCT, ASC, DESC, ASCENDING, DESCENDING,
    AND, OR, XOR, NOT, IN, IS, NULL, TRUE, FALSE,
    STARTS, ENDS, CONTAINS, CASE, WHEN, THEN, ELSE, END,
    EXISTS,

    IDENTIFIER,
    INTEGER_LITERAL,
    FLOAT_LITERAL,
    STRING_LITERAL,
    PARAMETER,

    LPAREN, RPAREN,
    LBRACKET, RBRACKET,
    LBRACE, RBRACE,
    DOT, COMMA, COLON, SEMICOLON, PIPE,
    EQ, NEQ, LT, GT, LTE, GTE,
    PLUS, MINUS, STAR, SLASH, PERCENT, CARET,
    ARROW_RIGHT,
    ARROW_LEFT,
    DASH,
    DOTDOT,
    REGEX_MATCH,
    PLUS_EQ,

    EOF
}

internal data class Token(
    val type: TokenType,
    val text: String,
    val position: Int
)

// ============================================================================
// Tokenizer
// ============================================================================

internal object CypherTokenizer {

    private val KEYWORDS = mapOf(
        "MATCH" to TokenType.MATCH, "OPTIONAL" to TokenType.OPTIONAL,
        "WHERE" to TokenType.WHERE, "RETURN" to TokenType.RETURN,
        "WITH" to TokenType.WITH, "UNWIND" to TokenType.UNWIND,
        "ORDER" to TokenType.ORDER, "BY" to TokenType.BY,
        "SKIP" to TokenType.SKIP, "LIMIT" to TokenType.LIMIT,
        "UNION" to TokenType.UNION, "ALL" to TokenType.ALL,
        "CREATE" to TokenType.CREATE, "DELETE" to TokenType.DELETE,
        "DETACH" to TokenType.DETACH, "SET" to TokenType.SET,
        "REMOVE" to TokenType.REMOVE, "MERGE" to TokenType.MERGE,
        "AS" to TokenType.AS, "DISTINCT" to TokenType.DISTINCT,
        "ASC" to TokenType.ASC, "DESC" to TokenType.DESC,
        "ASCENDING" to TokenType.ASCENDING, "DESCENDING" to TokenType.DESCENDING,
        "AND" to TokenType.AND, "OR" to TokenType.OR,
        "XOR" to TokenType.XOR, "NOT" to TokenType.NOT,
        "IN" to TokenType.IN, "IS" to TokenType.IS,
        "NULL" to TokenType.NULL, "TRUE" to TokenType.TRUE,
        "FALSE" to TokenType.FALSE, "STARTS" to TokenType.STARTS,
        "ENDS" to TokenType.ENDS, "CONTAINS" to TokenType.CONTAINS,
        "CASE" to TokenType.CASE, "WHEN" to TokenType.WHEN,
        "THEN" to TokenType.THEN, "ELSE" to TokenType.ELSE,
        "END" to TokenType.END, "EXISTS" to TokenType.EXISTS
    )

    fun tokenize(input: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var pos = 0

        while (pos < input.length) {
            val c = input[pos]

            if (c.isWhitespace()) { pos++; continue }

            // Single-line comment
            if (c == '/' && pos + 1 < input.length && input[pos + 1] == '/') {
                pos = input.indexOf('\n', pos).let { if (it < 0) input.length else it + 1 }
                continue
            }

            // Multi-line comment
            if (c == '/' && pos + 1 < input.length && input[pos + 1] == '*') {
                val end = input.indexOf("*/", pos + 2)
                pos = if (end < 0) input.length else end + 2
                continue
            }

            // String literals
            if (c == '\'' || c == '"') {
                val (str, newPos) = readString(input, pos, c)
                tokens.add(Token(TokenType.STRING_LITERAL, str, pos))
                pos = newPos
                continue
            }

            // Backtick-escaped identifier
            if (c == '`') {
                val end = input.indexOf('`', pos + 1)
                if (end < 0) throw CypherParseException("Unterminated backtick at position $pos")
                tokens.add(Token(TokenType.IDENTIFIER, input.substring(pos + 1, end), pos))
                pos = end + 1
                continue
            }

            // Parameter: $name
            if (c == '$') {
                pos++
                val start = pos
                while (pos < input.length && (input[pos].isLetterOrDigit() || input[pos] == '_')) pos++
                tokens.add(Token(TokenType.PARAMETER, input.substring(start, pos), start - 1))
                continue
            }

            // Number
            if (c.isDigit() || (c == '.' && pos + 1 < input.length && input[pos + 1].isDigit())) {
                val (token, newPos) = readNumber(input, pos)
                tokens.add(token)
                pos = newPos
                continue
            }

            // Identifier or keyword
            if (c.isLetter() || c == '_') {
                val start = pos
                while (pos < input.length && (input[pos].isLetterOrDigit() || input[pos] == '_')) pos++
                val word = input.substring(start, pos)
                val kwType = KEYWORDS[word.uppercase()]
                tokens.add(Token(kwType ?: TokenType.IDENTIFIER, word, start))
                continue
            }

            // Multi-character operators
            val next = if (pos + 1 < input.length) input[pos + 1] else '\u0000'
            when {
                c == '<' && next == '>' -> { tokens.add(Token(TokenType.NEQ, "<>", pos)); pos += 2 }
                c == '<' && next == '=' -> { tokens.add(Token(TokenType.LTE, "<=", pos)); pos += 2 }
                c == '>' && next == '=' -> { tokens.add(Token(TokenType.GTE, ">=", pos)); pos += 2 }
                c == '=' && next == '~' -> { tokens.add(Token(TokenType.REGEX_MATCH, "=~", pos)); pos += 2 }
                c == '+' && next == '=' -> { tokens.add(Token(TokenType.PLUS_EQ, "+=", pos)); pos += 2 }
                c == '-' && next == '>' -> { tokens.add(Token(TokenType.ARROW_RIGHT, "->", pos)); pos += 2 }
                c == '<' && next == '-' -> { tokens.add(Token(TokenType.ARROW_LEFT, "<-", pos)); pos += 2 }
                c == '.' && next == '.' -> { tokens.add(Token(TokenType.DOTDOT, "..", pos)); pos += 2 }

                c == '(' -> { tokens.add(Token(TokenType.LPAREN, "(", pos)); pos++ }
                c == ')' -> { tokens.add(Token(TokenType.RPAREN, ")", pos)); pos++ }
                c == '[' -> { tokens.add(Token(TokenType.LBRACKET, "[", pos)); pos++ }
                c == ']' -> { tokens.add(Token(TokenType.RBRACKET, "]", pos)); pos++ }
                c == '{' -> { tokens.add(Token(TokenType.LBRACE, "{", pos)); pos++ }
                c == '}' -> { tokens.add(Token(TokenType.RBRACE, "}", pos)); pos++ }
                c == '.' -> { tokens.add(Token(TokenType.DOT, ".", pos)); pos++ }
                c == ',' -> { tokens.add(Token(TokenType.COMMA, ",", pos)); pos++ }
                c == ':' -> { tokens.add(Token(TokenType.COLON, ":", pos)); pos++ }
                c == ';' -> { tokens.add(Token(TokenType.SEMICOLON, ";", pos)); pos++ }
                c == '|' -> { tokens.add(Token(TokenType.PIPE, "|", pos)); pos++ }
                c == '=' -> { tokens.add(Token(TokenType.EQ, "=", pos)); pos++ }
                c == '<' -> { tokens.add(Token(TokenType.LT, "<", pos)); pos++ }
                c == '>' -> { tokens.add(Token(TokenType.GT, ">", pos)); pos++ }
                c == '+' -> { tokens.add(Token(TokenType.PLUS, "+", pos)); pos++ }
                c == '-' -> { tokens.add(Token(TokenType.DASH, "-", pos)); pos++ }
                c == '*' -> { tokens.add(Token(TokenType.STAR, "*", pos)); pos++ }
                c == '/' -> { tokens.add(Token(TokenType.SLASH, "/", pos)); pos++ }
                c == '%' -> { tokens.add(Token(TokenType.PERCENT, "%", pos)); pos++ }
                c == '^' -> { tokens.add(Token(TokenType.CARET, "^", pos)); pos++ }

                else -> throw CypherParseException("Unexpected character '$c' at position $pos")
            }
        }

        tokens.add(Token(TokenType.EOF, "", input.length))
        return tokens
    }

    private fun readString(input: String, startPos: Int, quote: Char): Pair<String, Int> {
        val sb = StringBuilder()
        var pos = startPos + 1
        while (pos < input.length) {
            val c = input[pos]
            if (c == '\\' && pos + 1 < input.length) {
                pos++
                when (input[pos]) {
                    '\\' -> sb.append('\\')
                    '\'' -> sb.append('\'')
                    '"' -> sb.append('"')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'u' -> {
                        if (pos + 4 < input.length) {
                            sb.append(input.substring(pos + 1, pos + 5).toInt(16).toChar())
                            pos += 4
                        }
                    }
                    else -> { sb.append('\\'); sb.append(input[pos]) }
                }
                pos++
            } else if (c == quote) {
                if (pos + 1 < input.length && input[pos + 1] == quote) {
                    sb.append(quote)
                    pos += 2
                } else {
                    return sb.toString() to (pos + 1)
                }
            } else {
                sb.append(c)
                pos++
            }
        }
        throw CypherParseException("Unterminated string at position $startPos")
    }

    private fun readNumber(input: String, startPos: Int): Pair<Token, Int> {
        var pos = startPos
        var isFloat = false

        if (pos + 1 < input.length && input[pos] == '0' && input[pos + 1].lowercaseChar() == 'x') {
            pos += 2
            while (pos < input.length && input[pos].isLetterOrDigit()) pos++
            return Token(TokenType.INTEGER_LITERAL, input.substring(startPos, pos), startPos) to pos
        }
        if (pos + 1 < input.length && input[pos] == '0' && input[pos + 1].lowercaseChar() == 'o') {
            pos += 2
            while (pos < input.length && input[pos].isDigit()) pos++
            return Token(TokenType.INTEGER_LITERAL, input.substring(startPos, pos), startPos) to pos
        }

        while (pos < input.length && input[pos].isDigit()) pos++
        if (pos < input.length && input[pos] == '.' && pos + 1 < input.length && input[pos + 1].isDigit()) {
            isFloat = true; pos++
            while (pos < input.length && input[pos].isDigit()) pos++
        }
        if (pos < input.length && input[pos].lowercaseChar() == 'e') {
            isFloat = true; pos++
            if (pos < input.length && (input[pos] == '+' || input[pos] == '-')) pos++
            while (pos < input.length && input[pos].isDigit()) pos++
        }

        val text = input.substring(startPos, pos)
        return Token(if (isFloat) TokenType.FLOAT_LITERAL else TokenType.INTEGER_LITERAL, text, startPos) to pos
    }
}

// ============================================================================
// Clause Parser
// ============================================================================

internal class ClauseParser(internal val tokens: List<Token>) {

    internal var pos = 0

    internal fun peek(): Token = tokens[pos]
    internal fun peekType(): TokenType = tokens[pos].type

    internal fun advance(): Token {
        val t = tokens[pos]
        if (pos < tokens.size - 1) pos++
        return t
    }

    internal fun expect(type: TokenType): Token {
        val t = peek()
        if (t.type != type) {
            throw CypherParseException(
                "Expected $type but got ${t.type} '${t.text}' at position ${t.position}"
            )
        }
        return advance()
    }

    internal fun match(type: TokenType): Boolean {
        if (peekType() == type) { advance(); return true }
        return false
    }

    internal fun isAtEnd(): Boolean = peekType() == TokenType.EOF

    fun parseClauses(): List<CypherClause> {
        val clauses = mutableListOf<CypherClause>()
        while (!isAtEnd()) {
            if (match(TokenType.SEMICOLON)) continue
            when (peekType()) {
                TokenType.MATCH -> clauses.add(parseMatch(optional = false))
                TokenType.OPTIONAL -> clauses.add(parseOptionalMatch())
                TokenType.WHERE -> clauses.add(parseWhere())
                TokenType.RETURN -> clauses.addAll(parseReturnWithTrailing())
                TokenType.WITH -> clauses.addAll(parseWith())
                TokenType.UNWIND -> clauses.add(parseUnwind())
                TokenType.ORDER -> clauses.add(parseOrderBy())
                TokenType.SKIP -> clauses.add(parseSkip())
                TokenType.LIMIT -> clauses.add(parseLimit())
                TokenType.UNION -> clauses.add(parseUnion())
                TokenType.CREATE -> clauses.add(parseCreate())
                TokenType.DELETE -> clauses.add(parseDelete(detach = false))
                TokenType.DETACH -> clauses.add(parseDetachDelete())
                TokenType.SET -> clauses.add(parseSet())
                TokenType.REMOVE -> clauses.add(parseRemove())
                TokenType.MERGE -> { advance(); clauses.add(CypherClause.Create(parsePatternList())) }
                else -> throw CypherParseException(
                    "Expected clause keyword but got '${peek().text}' at position ${peek().position}"
                )
            }
        }
        return clauses
    }

    // -- Clause parsers -------------------------------------------------------

    private fun parseMatch(optional: Boolean): CypherClause.Match {
        expect(TokenType.MATCH)
        return CypherClause.Match(parsePatternList(), optional)
    }

    private fun parseOptionalMatch(): CypherClause.Match {
        expect(TokenType.OPTIONAL)
        return parseMatch(optional = true)
    }

    private fun parseWhere(): CypherClause.Where {
        expect(TokenType.WHERE)
        return CypherClause.Where(ExprParser(this).parseExpression())
    }

    private fun parseReturnWithTrailing(): List<CypherClause> {
        expect(TokenType.RETURN)
        val distinct = match(TokenType.DISTINCT)
        val items = parseReturnItems()
        val result = mutableListOf<CypherClause>(CypherClause.Return(items, distinct))
        if (peekType() == TokenType.ORDER) result.add(parseOrderBy())
        if (peekType() == TokenType.SKIP) result.add(parseSkip())
        if (peekType() == TokenType.LIMIT) result.add(parseLimit())
        return result
    }

    private fun parseWith(): List<CypherClause> {
        expect(TokenType.WITH)
        val distinct = match(TokenType.DISTINCT)
        val items = parseReturnItems()
        val where = if (peekType() == TokenType.WHERE) {
            advance(); ExprParser(this).parseExpression()
        } else null
        val result = mutableListOf<CypherClause>(CypherClause.With(items, distinct, where))
        if (peekType() == TokenType.ORDER) result.add(parseOrderBy())
        if (peekType() == TokenType.SKIP) result.add(parseSkip())
        if (peekType() == TokenType.LIMIT) result.add(parseLimit())
        return result
    }

    private fun parseUnwind(): CypherClause.Unwind {
        expect(TokenType.UNWIND)
        val expr = ExprParser(this).parseExpression()
        expect(TokenType.AS)
        val variable = expect(TokenType.IDENTIFIER).text
        return CypherClause.Unwind(expr, variable)
    }

    private fun parseOrderBy(): CypherClause.OrderBy {
        expect(TokenType.ORDER); expect(TokenType.BY)
        val items = mutableListOf<SortItem>()
        do {
            val expr = ExprParser(this).parseExpression()
            val asc = when (peekType()) {
                TokenType.ASC, TokenType.ASCENDING -> { advance(); true }
                TokenType.DESC, TokenType.DESCENDING -> { advance(); false }
                else -> true
            }
            items.add(SortItem(expr, asc))
        } while (match(TokenType.COMMA))
        return CypherClause.OrderBy(items)
    }

    private fun parseSkip(): CypherClause.Skip {
        expect(TokenType.SKIP)
        return CypherClause.Skip(ExprParser(this).parseUnary())
    }

    private fun parseLimit(): CypherClause.Limit {
        expect(TokenType.LIMIT)
        return CypherClause.Limit(ExprParser(this).parseUnary())
    }

    private fun parseUnion(): CypherClause.Union {
        expect(TokenType.UNION)
        return CypherClause.Union(all = match(TokenType.ALL))
    }

    private fun parseCreate(): CypherClause.Create {
        expect(TokenType.CREATE)
        return CypherClause.Create(parsePatternList())
    }

    private fun parseDelete(detach: Boolean): CypherClause.Delete {
        expect(TokenType.DELETE)
        val exprs = mutableListOf<CypherExpr>()
        do { exprs.add(ExprParser(this).parseExpression()) } while (match(TokenType.COMMA))
        return CypherClause.Delete(exprs, detach)
    }

    private fun parseDetachDelete(): CypherClause.Delete {
        expect(TokenType.DETACH); return parseDelete(detach = true)
    }

    private fun parseSet(): CypherClause.Set {
        expect(TokenType.SET)
        val items = mutableListOf<SetItem>()
        do { items.add(parseSetItem()) } while (match(TokenType.COMMA))
        return CypherClause.Set(items)
    }

    private fun parseSetItem(): SetItem {
        val name = expect(TokenType.IDENTIFIER).text
        if (peekType() == TokenType.COLON) {
            val labels = mutableListOf<String>()
            while (match(TokenType.COLON)) labels.add(readIdentOrKeyword())
            return SetItem.LabelSet(name, labels)
        }
        if (peekType() == TokenType.DOT) {
            advance()
            val prop = readIdentOrKeyword()
            expect(TokenType.EQ)
            return SetItem.PropertySet(name, prop, ExprParser(this).parseExpression())
        }
        if (peekType() == TokenType.PLUS_EQ) {
            advance()
            return SetItem.MergePropertiesSet(name, ExprParser(this).parseExpression())
        }
        expect(TokenType.EQ)
        return SetItem.AllPropertiesSet(name, ExprParser(this).parseExpression())
    }

    private fun parseRemove(): CypherClause.Remove {
        expect(TokenType.REMOVE)
        val items = mutableListOf<RemoveItem>()
        do {
            val name = expect(TokenType.IDENTIFIER).text
            if (peekType() == TokenType.DOT) {
                advance(); items.add(RemoveItem.PropertyRemove(name, readIdentOrKeyword()))
            } else if (peekType() == TokenType.COLON) {
                val labels = mutableListOf<String>()
                while (match(TokenType.COLON)) labels.add(readIdentOrKeyword())
                items.add(RemoveItem.LabelRemove(name, labels))
            }
        } while (match(TokenType.COMMA))
        return CypherClause.Remove(items)
    }

    // -- Return items ---------------------------------------------------------

    private fun parseReturnItems(): List<ReturnItem> {
        val items = mutableListOf<ReturnItem>()
        do {
            if (peekType() == TokenType.STAR && items.isEmpty()) {
                advance(); items.add(ReturnItem(CypherExpr.Variable("*"))); continue
            }
            val expr = ExprParser(this).parseExpression()
            val alias = if (peekType() == TokenType.AS) { advance(); readIdentOrKeyword() } else null
            items.add(ReturnItem(expr, alias))
        } while (match(TokenType.COMMA))
        return items
    }

    // -- Pattern parsing ------------------------------------------------------

    internal fun parsePatternList(): List<CypherPattern> {
        val patterns = mutableListOf(parsePattern())
        while (match(TokenType.COMMA)) patterns.add(parsePattern())
        return patterns
    }

    private fun parsePattern(): CypherPattern {
        // Check for path variable assignment: p = (...)
        var pathVariable: String? = null
        if (peekType() == TokenType.IDENTIFIER &&
            pos + 1 < tokens.size && tokens[pos + 1].type == TokenType.EQ &&
            pos + 2 < tokens.size && tokens[pos + 2].type == TokenType.LPAREN
        ) {
            pathVariable = advance().text
            expect(TokenType.EQ)
        }

        val elements = mutableListOf<PatternElement>()
        elements.add(parseNodePattern())
        while (peekType() == TokenType.DASH || peekType() == TokenType.ARROW_LEFT) {
            elements.add(parseRelPattern())
            elements.add(parseNodePattern())
        }
        return CypherPattern(elements, pathVariable)
    }

    private fun parseNodePattern(): PatternElement.NodePattern {
        expect(TokenType.LPAREN)
        var variable: String? = null
        val labels = mutableListOf<String>()
        var props = emptyMap<String, CypherExpr>()

        if (peekType() == TokenType.IDENTIFIER) variable = advance().text
        while (peekType() == TokenType.COLON) { advance(); labels.add(readIdentOrKeyword()) }
        if (peekType() == TokenType.LBRACE) props = parseInlineMap()
        expect(TokenType.RPAREN)
        return PatternElement.NodePattern(variable, labels, props)
    }

    private fun parseRelPattern(): PatternElement.RelationshipPattern {
        val leftArrow = match(TokenType.ARROW_LEFT)
        if (!leftArrow) expect(TokenType.DASH)

        var variable: String? = null
        val types = mutableListOf<String>()
        var props = emptyMap<String, CypherExpr>()
        var varLen = false
        var minH: Int? = null
        var maxH: Int? = null

        if (peekType() == TokenType.LBRACKET) {
            advance()
            if (peekType() == TokenType.IDENTIFIER) variable = advance().text
            if (peekType() == TokenType.COLON) {
                advance(); types.add(readIdentOrKeyword())
                while (peekType() == TokenType.PIPE) { advance(); types.add(readIdentOrKeyword()) }
            }
            if (peekType() == TokenType.LBRACE) props = parseInlineMap()
            if (peekType() == TokenType.STAR) {
                advance(); varLen = true
                if (peekType() == TokenType.INTEGER_LITERAL) {
                    val first = advance().text.toInt()
                    if (peekType() == TokenType.DOTDOT) {
                        advance(); minH = first
                        if (peekType() == TokenType.INTEGER_LITERAL) maxH = advance().text.toInt()
                    } else { minH = first; maxH = first }
                } else if (peekType() == TokenType.DOTDOT) {
                    advance(); minH = 1
                    if (peekType() == TokenType.INTEGER_LITERAL) maxH = advance().text.toInt()
                }
            }
            expect(TokenType.RBRACKET)
        }

        val rightArrow = match(TokenType.ARROW_RIGHT)
        if (!rightArrow && peekType() == TokenType.DASH) advance()

        val dir = when {
            leftArrow && !rightArrow -> Direction.INCOMING
            !leftArrow && rightArrow -> Direction.OUTGOING
            leftArrow && rightArrow -> Direction.BOTH
            else -> Direction.BOTH
        }

        return PatternElement.RelationshipPattern(variable, types, props, dir, minH, maxH, varLen)
    }

    private fun parseInlineMap(): Map<String, CypherExpr> {
        expect(TokenType.LBRACE)
        val map = mutableMapOf<String, CypherExpr>()
        if (peekType() != TokenType.RBRACE) {
            do {
                val key = readIdentOrKeyword()
                expect(TokenType.COLON)
                map[key] = ExprParser(this).parseExpression()
            } while (match(TokenType.COMMA))
        }
        expect(TokenType.RBRACE)
        return map
    }

    // -- Utilities ------------------------------------------------------------

    internal fun readIdentOrKeyword(): String {
        val t = peek()
        if (t.type == TokenType.IDENTIFIER) return advance().text
        if (t.type in KEYWORD_TOKENS) return advance().text
        throw CypherParseException("Expected identifier but got ${t.type} '${t.text}' at position ${t.position}")
    }

    companion object {
        private val SYMBOL_TOKENS = setOf(
            TokenType.LPAREN, TokenType.RPAREN, TokenType.LBRACKET, TokenType.RBRACKET,
            TokenType.LBRACE, TokenType.RBRACE, TokenType.DOT, TokenType.COMMA,
            TokenType.COLON, TokenType.SEMICOLON, TokenType.PIPE,
            TokenType.EQ, TokenType.NEQ, TokenType.LT, TokenType.GT,
            TokenType.LTE, TokenType.GTE, TokenType.PLUS, TokenType.MINUS,
            TokenType.STAR, TokenType.SLASH, TokenType.PERCENT, TokenType.CARET,
            TokenType.ARROW_RIGHT, TokenType.ARROW_LEFT, TokenType.DASH,
            TokenType.DOTDOT, TokenType.REGEX_MATCH, TokenType.PLUS_EQ
        )
        private val NON_KEYWORD_TOKENS = SYMBOL_TOKENS + setOf(
            TokenType.IDENTIFIER, TokenType.INTEGER_LITERAL, TokenType.FLOAT_LITERAL,
            TokenType.STRING_LITERAL, TokenType.PARAMETER, TokenType.EOF
        )
        internal val KEYWORD_TOKENS = TokenType.entries.toSet() - NON_KEYWORD_TOKENS
    }
}

// ============================================================================
// Expression Parser
// ============================================================================

/**
 * Recursive descent expression parser with standard Cypher operator precedence.
 *
 * Precedence (lowest to highest):
 *  1. OR
 *  2. XOR
 *  3. AND
 *  4. NOT
 *  5. Comparison (=, <>, <, >, <=, >=)
 *  6. String/list predicates (STARTS WITH, ENDS WITH, CONTAINS, IN, IS NULL, =~)
 *  7. Addition (+, -)
 *  8. Multiplication (*, /, %)
 *  9. Exponentiation (^)
 * 10. Unary (+, -)
 * 11. Postfix (property access, subscript/slice)
 * 12. Atoms (literals, variables, function calls, parenthesized, list, map, CASE)
 */
internal class ExprParser(private val cp: ClauseParser) {

    fun parseExpression(): CypherExpr = parseOr()

    private fun parseOr(): CypherExpr {
        var left = parseXor()
        while (cp.peekType() == TokenType.OR) { cp.advance(); left = CypherExpr.Or(left, parseXor()) }
        return left
    }

    private fun parseXor(): CypherExpr {
        var left = parseAnd()
        while (cp.peekType() == TokenType.XOR) { cp.advance(); left = CypherExpr.Xor(left, parseAnd()) }
        return left
    }

    private fun parseAnd(): CypherExpr {
        var left = parseNot()
        while (cp.peekType() == TokenType.AND) { cp.advance(); left = CypherExpr.And(left, parseNot()) }
        return left
    }

    private fun parseNot(): CypherExpr {
        if (cp.peekType() == TokenType.NOT) { cp.advance(); return CypherExpr.Not(parseNot()) }
        return parseComparison()
    }

    private fun parseComparison(): CypherExpr {
        var left = parseStringPredicate()
        while (true) {
            val op = when (cp.peekType()) {
                TokenType.EQ -> "="; TokenType.NEQ -> "<>"
                TokenType.LT -> "<"; TokenType.GT -> ">"
                TokenType.LTE -> "<="; TokenType.GTE -> ">="
                else -> break
            }
            cp.advance()
            left = CypherExpr.Comparison(op, left, parseStringPredicate())
        }
        return left
    }

    private fun parseStringPredicate(): CypherExpr {
        var left = parseAddition()
        while (true) {
            when (cp.peekType()) {
                TokenType.STARTS -> {
                    cp.advance(); cp.expect(TokenType.WITH)
                    left = CypherExpr.StringOp("STARTS WITH", left, parseAddition())
                }
                TokenType.ENDS -> {
                    cp.advance(); cp.expect(TokenType.WITH)
                    left = CypherExpr.StringOp("ENDS WITH", left, parseAddition())
                }
                TokenType.CONTAINS -> {
                    cp.advance()
                    left = CypherExpr.StringOp("CONTAINS", left, parseAddition())
                }
                TokenType.IN -> { cp.advance(); left = CypherExpr.ListOp("IN", left, parseAddition()) }
                TokenType.REGEX_MATCH -> { cp.advance(); left = CypherExpr.RegexMatch(left, parseAddition()) }
                TokenType.IS -> {
                    cp.advance()
                    if (cp.peekType() == TokenType.NOT) {
                        cp.advance(); cp.expect(TokenType.NULL); left = CypherExpr.IsNotNull(left)
                    } else {
                        cp.expect(TokenType.NULL); left = CypherExpr.IsNull(left)
                    }
                }
                TokenType.NOT -> {
                    // NOT CONTAINS / NOT STARTS WITH / NOT ENDS WITH
                    when {
                        cp.pos + 1 < cp.tokens.size && cp.tokens[cp.pos + 1].type == TokenType.CONTAINS -> {
                            cp.advance(); cp.advance() // consume NOT, CONTAINS
                            left = CypherExpr.Not(CypherExpr.StringOp("CONTAINS", left, parseAddition()))
                        }
                        cp.pos + 1 < cp.tokens.size && cp.tokens[cp.pos + 1].type == TokenType.STARTS -> {
                            cp.advance(); cp.advance(); cp.expect(TokenType.WITH) // consume NOT, STARTS, WITH
                            left = CypherExpr.Not(CypherExpr.StringOp("STARTS WITH", left, parseAddition()))
                        }
                        cp.pos + 1 < cp.tokens.size && cp.tokens[cp.pos + 1].type == TokenType.ENDS -> {
                            cp.advance(); cp.advance(); cp.expect(TokenType.WITH) // consume NOT, ENDS, WITH
                            left = CypherExpr.Not(CypherExpr.StringOp("ENDS WITH", left, parseAddition()))
                        }
                        else -> break
                    }
                }
                else -> break
            }
        }
        return left
    }

    private fun parseAddition(): CypherExpr {
        var left = parseMultiplication()
        while (true) {
            val op = when (cp.peekType()) {
                TokenType.PLUS -> "+"; TokenType.DASH -> "-"; else -> break
            }
            cp.advance(); left = CypherExpr.BinaryOp(op, left, parseMultiplication())
        }
        return left
    }

    private fun parseMultiplication(): CypherExpr {
        var left = parseExponentiation()
        while (true) {
            val op = when (cp.peekType()) {
                TokenType.STAR -> "*"; TokenType.SLASH -> "/"; TokenType.PERCENT -> "%"; else -> break
            }
            cp.advance(); left = CypherExpr.BinaryOp(op, left, parseExponentiation())
        }
        return left
    }

    private fun parseExponentiation(): CypherExpr {
        val left = parseUnary()
        if (cp.peekType() == TokenType.CARET) {
            cp.advance(); return CypherExpr.BinaryOp("^", left, parseExponentiation())
        }
        return left
    }

    internal fun parseUnary(): CypherExpr {
        if (cp.peekType() == TokenType.DASH) { cp.advance(); return CypherExpr.UnaryOp("-", parseUnary()) }
        if (cp.peekType() == TokenType.PLUS) { cp.advance(); return parseUnary() }
        return parsePostfix()
    }

    private fun parsePostfix(): CypherExpr {
        var expr = parseAtom()
        while (true) {
            when (cp.peekType()) {
                TokenType.DOT -> { cp.advance(); expr = CypherExpr.Property(expr, cp.readIdentOrKeyword()) }
                TokenType.LBRACKET -> { cp.advance(); expr = parseSubscriptOrSlice(expr) }
                else -> break
            }
        }
        return expr
    }

    private fun parseSubscriptOrSlice(target: CypherExpr): CypherExpr {
        if (cp.peekType() == TokenType.DOTDOT) {
            cp.advance()
            val to = parseExpression()
            cp.expect(TokenType.RBRACKET)
            return CypherExpr.Slice(target, null, to)
        }
        val first = parseExpression()
        if (cp.peekType() == TokenType.DOTDOT) {
            cp.advance()
            val to = if (cp.peekType() != TokenType.RBRACKET) parseExpression() else null
            cp.expect(TokenType.RBRACKET)
            return CypherExpr.Slice(target, first, to)
        }
        cp.expect(TokenType.RBRACKET)
        return CypherExpr.Subscript(target, first)
    }

    private fun parseAtom(): CypherExpr {
        val tok = cp.peek()
        return when (tok.type) {
            TokenType.INTEGER_LITERAL -> {
                cp.advance()
                val v: Number = when {
                    tok.text.startsWith("0x", true) -> java.lang.Long.decode(tok.text)
                    tok.text.startsWith("0o", true) -> tok.text.substring(2).toLong(8)
                    else -> tok.text.toLong().let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
                }
                CypherExpr.Literal(v)
            }
            TokenType.FLOAT_LITERAL -> { cp.advance(); CypherExpr.Literal(tok.text.toDouble()) }
            TokenType.STRING_LITERAL -> { cp.advance(); CypherExpr.Literal(tok.text) }
            TokenType.TRUE -> { cp.advance(); CypherExpr.Literal(true) }
            TokenType.FALSE -> { cp.advance(); CypherExpr.Literal(false) }
            TokenType.NULL -> { cp.advance(); CypherExpr.Literal(null) }
            TokenType.PARAMETER -> { cp.advance(); CypherExpr.Parameter(tok.text) }

            TokenType.LPAREN -> { cp.advance(); val e = parseExpression(); cp.expect(TokenType.RPAREN); e }
            TokenType.LBRACKET -> parseListOrComprehension()
            TokenType.LBRACE -> parseMapExpr()
            TokenType.CASE -> parseCaseExpr()
            TokenType.DISTINCT -> { cp.advance(); CypherExpr.Distinct(parseUnary()) }
            TokenType.EXISTS -> {
                cp.advance(); cp.expect(TokenType.LPAREN)
                val e = parseExpression(); cp.expect(TokenType.RPAREN)
                CypherExpr.FunctionCall("exists", listOf(e))
            }
            TokenType.NOT -> { cp.advance(); CypherExpr.Not(parseComparison()) }
            TokenType.IDENTIFIER -> parseIdentExpr()

            else -> {
                if (tok.type in ClauseParser.KEYWORD_TOKENS && cp.peekType() != TokenType.EOF) {
                    // Keywords usable as function names
                    val name = cp.advance().text
                    if (cp.peekType() == TokenType.LPAREN) parseFnCall(name)
                    else CypherExpr.Variable(name)
                } else {
                    throw CypherParseException(
                        "Unexpected '${tok.text}' (${tok.type}) at position ${tok.position}"
                    )
                }
            }
        }
    }

    private fun parseIdentExpr(): CypherExpr {
        val name = cp.advance().text
        if (cp.peekType() == TokenType.LPAREN) return parseFnCall(name)
        return CypherExpr.Variable(name)
    }

    private fun parseFnCall(name: String): CypherExpr {
        cp.expect(TokenType.LPAREN)
        if (name.equals("count", ignoreCase = true) && cp.peekType() == TokenType.STAR) {
            cp.advance(); cp.expect(TokenType.RPAREN); return CypherExpr.CountStar
        }
        val distinct = cp.match(TokenType.DISTINCT)
        val args = mutableListOf<CypherExpr>()
        if (cp.peekType() != TokenType.RPAREN) {
            args.add(parseExpression())
            while (cp.match(TokenType.COMMA)) args.add(parseExpression())
        }
        cp.expect(TokenType.RPAREN)
        return CypherExpr.FunctionCall(name, args, distinct)
    }

    private fun parseListOrComprehension(): CypherExpr {
        cp.expect(TokenType.LBRACKET)
        if (cp.peekType() == TokenType.RBRACKET) { cp.advance(); return CypherExpr.ListLiteral(emptyList()) }

        // Try list comprehension: [x IN list WHERE pred | expr]
        val saved = cp.pos
        if (cp.peekType() == TokenType.IDENTIFIER) {
            val varName = cp.advance().text
            if (cp.peekType() == TokenType.IN) {
                cp.advance()
                val listExpr = parseExpression()
                var pred: CypherExpr? = null
                if (cp.peekType() == TokenType.WHERE) { cp.advance(); pred = parseExpression() }
                var mapExpr: CypherExpr? = null
                if (cp.peekType() == TokenType.PIPE) { cp.advance(); mapExpr = parseExpression() }
                cp.expect(TokenType.RBRACKET)
                return CypherExpr.ListComprehension(varName, listExpr, pred, mapExpr)
            }
            cp.pos = saved // backtrack
        }

        val elems = mutableListOf(parseExpression())
        while (cp.match(TokenType.COMMA)) elems.add(parseExpression())
        cp.expect(TokenType.RBRACKET)
        return CypherExpr.ListLiteral(elems)
    }

    private fun parseMapExpr(): CypherExpr {
        cp.expect(TokenType.LBRACE)
        val entries = mutableMapOf<String, CypherExpr>()
        if (cp.peekType() != TokenType.RBRACE) {
            do {
                val key = cp.readIdentOrKeyword()
                cp.expect(TokenType.COLON)
                entries[key] = parseExpression()
            } while (cp.match(TokenType.COMMA))
        }
        cp.expect(TokenType.RBRACE)
        return CypherExpr.MapLiteral(entries)
    }

    private fun parseCaseExpr(): CypherExpr {
        cp.expect(TokenType.CASE)
        val test = if (cp.peekType() != TokenType.WHEN) parseExpression() else null
        val whens = mutableListOf<Pair<CypherExpr, CypherExpr>>()
        while (cp.peekType() == TokenType.WHEN) {
            cp.advance()
            val cond = parseExpression()
            cp.expect(TokenType.THEN)
            whens.add(cond to parseExpression())
        }
        val elseE = if (cp.peekType() == TokenType.ELSE) { cp.advance(); parseExpression() } else null
        cp.expect(TokenType.END)
        return CypherExpr.CaseExpr(test, whens, elseE)
    }
}
