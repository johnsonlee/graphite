package io.johnsonlee.graphite.cypher

/**
 * Supplementary query parsing for information not easily accessible from the
 * the ANTLR-based Cypher parser: variable bindings, RETURN projections,
 * LIMIT, and relationship patterns with variable-length syntax.
 *
 * This parser handles a practical subset of Cypher patterns used with Graphite:
 * - `MATCH (var:Label)` -- single node
 * - `MATCH (a:Label)-[:TYPE]->(b:Label)` -- single hop
 * - `MATCH (a)-[:TYPE*min..max]->(b)` -- variable-length path
 * - `WHERE var.prop op value` -- property conditions
 * - `RETURN var.prop, var2.prop AS alias` -- projections
 * - `LIMIT N` -- result count limit
 */
data class ParsedQuery(
    val nodeBindings: Map<String, NodeBinding>,
    val relationship: RelationshipPattern?,
    val returnProjections: List<ReturnProjection>,
    val limit: Int?
) {
    companion object {
        fun parse(cypher: String): ParsedQuery {
            val normalized = cypher.trim()

            val nodeBindings = parseNodeBindings(normalized)
            val relationship = parseRelationship(normalized)
            val returnProjections = parseReturn(normalized)
            val limit = parseLimit(normalized)

            // Resolve variable names in WHERE filters from the parsed query
            // The StatementCatalog gives us label-based ownership, but we need
            // variable names to correlate with node bindings
            return ParsedQuery(
                nodeBindings = nodeBindings,
                relationship = relationship,
                returnProjections = returnProjections,
                limit = limit
            )
        }

        // Label can be a simple word or backtick-escaped: `ReturnNode`
        private val LABEL = """(?:`([^`]+)`|(\w+))"""
        private val NODE_PATTERN = Regex("""\((\w+)(?::$LABEL)?\)""")
        private val REL_PATTERN = Regex(
            """\((\w+)(?::$LABEL)?\)\s*-\[(?::(\w+))?(\*(?:(\d+))?(?:\.\.(\d+))?)?\]\s*->\s*\((\w+)(?::$LABEL)?\)"""
        )
        private val RETURN_PATTERN = Regex("""(?i)\bRETURN\b\s+(.+?)(?:\s+LIMIT\s+\d+)?$""")
        private val LIMIT_PATTERN = Regex("""(?i)\bLIMIT\s+(\d+)""")

        /**
         * Extract a label from two capturing groups: one for backtick-escaped, one for plain word.
         * Returns whichever one matched, or null if neither did.
         */
        private fun extractLabel(backtickGroup: String, plainGroup: String): String? {
            return backtickGroup.ifEmpty { null } ?: plainGroup.ifEmpty { null }
        }

        private fun parseNodeBindings(cypher: String): Map<String, NodeBinding> {
            val bindings = mutableMapOf<String, NodeBinding>()

            // Extract from MATCH clause only (up to WHERE or RETURN)
            val matchClause = extractMatchClause(cypher)

            for (match in NODE_PATTERN.findAll(matchClause)) {
                val variable = match.groupValues[1]
                val label = extractLabel(match.groupValues[2], match.groupValues[3])
                bindings[variable] = NodeBinding(variable, label)
            }

            return bindings
        }

        private fun parseRelationship(cypher: String): RelationshipPattern? {
            val matchClause = extractMatchClause(cypher)
            val match = REL_PATTERN.find(matchClause) ?: return null

            // Groups: 1=sourceVar, 2=srcBacktick, 3=srcPlain,
            //         4=relType, 5=varLengthMarker, 6=minHops, 7=maxHops,
            //         8=targetVar, 9=tgtBacktick, 10=tgtPlain
            val sourceVar = match.groupValues[1]
            val sourceLabel = extractLabel(match.groupValues[2], match.groupValues[3])
            val relType = match.groupValues[4].ifEmpty { null }
            val varLengthMarker = match.groupValues[5]
            val minHops = match.groupValues[6].ifEmpty { null }?.toIntOrNull()
            val maxHops = match.groupValues[7].ifEmpty { null }?.toIntOrNull()
            val targetVar = match.groupValues[8]
            val targetLabel = extractLabel(match.groupValues[9], match.groupValues[10])

            return RelationshipPattern(
                sourceVar = sourceVar,
                targetVar = targetVar,
                type = relType,
                variableLength = varLengthMarker.isNotEmpty(),
                minHops = minHops,
                maxHops = maxHops
            )
        }

        private fun parseReturn(cypher: String): List<ReturnProjection> {
            val match = RETURN_PATTERN.find(cypher) ?: return emptyList()
            val returnExpr = match.groupValues[1].trim()

            return returnExpr.split(",").map { part ->
                val trimmed = part.trim()
                val asMatch = Regex("""(?i)(.+?)\s+AS\s+(\w+)""").find(trimmed)
                if (asMatch != null) {
                    ReturnProjection(
                        expression = asMatch.groupValues[1].trim(),
                        alias = asMatch.groupValues[2].trim()
                    )
                } else {
                    ReturnProjection(expression = trimmed, alias = null)
                }
            }
        }

        private fun parseLimit(cypher: String): Int? {
            val match = LIMIT_PATTERN.find(cypher) ?: return null
            return match.groupValues[1].toIntOrNull()
        }

        private fun extractMatchClause(cypher: String): String {
            val matchIdx = cypher.indexOfFirst("MATCH")
            if (matchIdx < 0) return cypher

            val afterMatch = cypher.substring(matchIdx + 5)
            val whereIdx = afterMatch.indexOfKeyword("WHERE")
            val returnIdx = afterMatch.indexOfKeyword("RETURN")

            val endIdx = listOfNotNull(
                if (whereIdx >= 0) whereIdx else null,
                if (returnIdx >= 0) returnIdx else null
            ).minOrNull() ?: afterMatch.length

            return afterMatch.substring(0, endIdx)
        }

        private fun String.indexOfFirst(keyword: String): Int {
            val upper = this.uppercase()
            val keyUpper = keyword.uppercase()
            return upper.indexOf(keyUpper)
        }

        /**
         * Find the index of a keyword that is NOT inside backtick-escaped identifiers.
         * A keyword must be preceded by whitespace (or start of string) and followed
         * by whitespace (or end of string).
         */
        private fun String.indexOfKeyword(keyword: String): Int {
            val pattern = Regex("""(?i)(?<=\s|^)$keyword(?=\s|$)""")
            val match = pattern.find(this)
            return match?.range?.first ?: -1
        }
    }
}

/**
 * A variable binding from a MATCH node pattern, e.g., `(n:CallSiteNode)`.
 */
data class NodeBinding(
    val variable: String,
    val label: String?
)

/**
 * A relationship pattern, e.g., `(a)-[:DATAFLOW*..3]->(b:CallSiteNode)`.
 */
data class RelationshipPattern(
    val sourceVar: String,
    val targetVar: String,
    val type: String?,
    val variableLength: Boolean,
    val minHops: Int?,
    val maxHops: Int?
)

/**
 * A RETURN projection, e.g., `n.callee_name` or `n.value AS val`.
 */
data class ReturnProjection(
    val expression: String,
    val alias: String?
)
