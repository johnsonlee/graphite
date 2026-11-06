package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.cypher.parser.CypherLexer
import io.johnsonlee.graphite.cypher.parser.CypherParser
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer

internal const val OCTAL_PREFIX_LENGTH = 2
internal const val OCTAL_RADIX = 8
internal const val LIST_COMPREHENSION_PREDICATE_INDEX = 1
internal const val LIST_COMPREHENSION_MAP_INDEX = 2
internal const val LIST_COMPREHENSION_PARTIAL_FORM_SIZE = 2
internal const val LIST_COMPREHENSION_FULL_FORM_SIZE = 3
internal const val UNICODE_ESCAPE_DIGITS = 4
internal const val UNICODE_ESCAPE_END_OFFSET = 5
internal const val HEX_RADIX = 16

/**
 * Parses a Cypher query string into a list of [CypherClause] AST nodes.
 *
 * Uses ANTLR-generated [CypherParser] for parsing and a custom visitor
 * to build the internal AST.
 */
object CypherDslAdapter {

    private data class CachedQuery(
        val clauses: List<CypherClause>,
        val retainedBytes: Long
    )

    private val parsedQueries = LinkedHashMap<String, CachedQuery>(
        MAX_PARSED_QUERY_CACHE_ENTRIES + 1,
        PARSED_QUERY_CACHE_LOAD_FACTOR,
        true
    )
    private var parsedQueryCacheBytes = 0L

    /**
     * Parse a Cypher query string into an ordered list of clauses.
     *
     * @param cypher the Cypher query text
     * @return ordered list of [CypherClause] representing the query structure
     * @throws CypherParseException if the query cannot be parsed
     */
    fun parse(cypher: String): List<CypherClause> {
        if (cypher.isBlank()) return emptyList()

        val cached = synchronized(parsedQueries) { parsedQueries[cypher]?.clauses }
        return cached ?: parseUncached(cypher)
    }

    private fun parseUncached(cypher: String): List<CypherClause> {
        // Handle semicolon-separated statements
        val clauses = if (cypher.contains(';')) {
            val parts = cypher.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size > 1) {
                parts.flatMap { parse(it) }
            } else {
                buildAst(cypher)
            }
        } else {
            buildAst(cypher)
        }.toImmutableCypherAst()
        cacheParsedQuery(cypher, clauses)
        return clauses
    }

    private fun cacheParsedQuery(cypher: String, clauses: List<CypherClause>) {
        val entryBytes = estimatedParsedQueryBytes(cypher, clauses)
        if (entryBytes > maxParsedQueryCacheBytes) return
        synchronized(parsedQueries) {
            if (parsedQueries.containsKey(cypher)) return
            while (parsedQueries.isNotEmpty() &&
                (parsedQueries.size >= MAX_PARSED_QUERY_CACHE_ENTRIES ||
                    parsedQueryCacheBytes > maxParsedQueryCacheBytes - entryBytes)
            ) {
                val eldest = parsedQueries.entries.iterator().next()
                parsedQueries.remove(eldest.key)
                parsedQueryCacheBytes -= eldest.value.retainedBytes
            }
            parsedQueries[cypher] = CachedQuery(clauses, entryBytes)
            parsedQueryCacheBytes += entryBytes
        }
    }

    internal fun clearParsedQueryCache() = synchronized(parsedQueries) {
        parsedQueries.clear()
        parsedQueryCacheBytes = 0L
    }

    internal fun parsedQueryCacheSize(): Int = synchronized(parsedQueries) { parsedQueries.size }

    internal fun buildAst(cypher: String): List<CypherClause> {
        val lexer = CypherLexer(CharStreams.fromString(cypher))
        lexer.removeErrorListeners()
        lexer.addErrorListener(ThrowingErrorListener)

        val tokens = CommonTokenStream(lexer)
        val parser = CypherParser(tokens)
        parser.removeErrorListeners()
        parser.addErrorListener(ThrowingErrorListener)

        val tree = parser.script()
        val visitor = CypherAstClauses
        return visitor.visitScript(tree)
    }

    private val maxParsedQueryCacheBytes: Long by lazy {
        minOf(MAX_PARSED_QUERY_CACHE_BYTES, Runtime.getRuntime().maxMemory() / PARSED_QUERY_HEAP_DIVISOR)
    }

    private fun estimatedParsedQueryBytes(cypher: String, clauses: List<CypherClause>): Long = try {
        Math.addExact(
            PARSED_QUERY_CACHE_ENTRY_ESTIMATED_BYTES,
            Math.addExact(
                Math.multiplyExact(cypher.length.toLong(), Char.SIZE_BYTES.toLong()),
                Math.multiplyExact(clauses.size.toLong(), PARSED_QUERY_CLAUSE_ESTIMATED_BYTES)
            )
        )
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
}

private const val MAX_PARSED_QUERY_CACHE_ENTRIES = 1_024
private const val MAX_PARSED_QUERY_CACHE_BYTES = 16L * 1_024 * 1_024
private const val PARSED_QUERY_HEAP_DIVISOR = 128L
private const val PARSED_QUERY_CACHE_LOAD_FACTOR = 0.75f
private const val PARSED_QUERY_CACHE_ENTRY_ESTIMATED_BYTES = 128L
private const val PARSED_QUERY_CLAUSE_ESTIMATED_BYTES = 2_048L

/**
 * Exception thrown when the Cypher parser encounters invalid syntax.
 */
class CypherParseException(message: String) : RuntimeException(message)

/**
 * ANTLR error listener that throws [CypherParseException] on any syntax error.
 */
private object ThrowingErrorListener : BaseErrorListener() {
    override fun syntaxError(
        recognizer: Recognizer<*, *>?,
        offendingSymbol: Any?,
        line: Int,
        charPositionInLine: Int,
        msg: String?,
        e: RecognitionException?
    ) {
        throw CypherParseException("Syntax error at position $charPositionInLine: $msg")
    }
}

// ============================================================================
// ANTLR Visitor that builds internal AST
// ============================================================================
