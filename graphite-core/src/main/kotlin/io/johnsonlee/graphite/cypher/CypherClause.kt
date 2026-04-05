package io.johnsonlee.graphite.cypher

/**
 * AST for Cypher clauses -- the top-level structural elements of a Cypher query.
 *
 * A Cypher query is a sequence of clauses such as MATCH, WHERE, RETURN, WITH, etc.
 * Each clause is represented by a subtype of [CypherClause].
 */
sealed class CypherClause {

    /**
     * `MATCH pattern [, pattern ...]`
     *
     * @property patterns one or more graph patterns to match
     * @property optional true when this is an `OPTIONAL MATCH`
     */
    data class Match(
        val patterns: List<CypherPattern>,
        val optional: Boolean = false
    ) : CypherClause()

    /**
     * `WHERE condition`
     *
     * @property condition a boolean expression that filters matched rows
     */
    data class Where(val condition: CypherExpr) : CypherClause()

    /**
     * `RETURN item [, item ...] [DISTINCT]`
     *
     * @property items projection items with optional aliases
     * @property distinct true when `RETURN DISTINCT`
     */
    data class Return(
        val items: List<ReturnItem>,
        val distinct: Boolean = false
    ) : CypherClause()

    /**
     * `WITH item [, item ...] [DISTINCT] [WHERE condition]`
     *
     * @property items projection items with optional aliases
     * @property distinct true when `WITH DISTINCT`
     * @property where optional inline WHERE condition
     */
    data class With(
        val items: List<ReturnItem>,
        val distinct: Boolean = false,
        val where: CypherExpr? = null
    ) : CypherClause()

    /**
     * `UNWIND expression AS variable`
     *
     * @property expression the list expression to unwind
     * @property variable the variable name bound to each element
     */
    data class Unwind(
        val expression: CypherExpr,
        val variable: String
    ) : CypherClause()

    /**
     * `ORDER BY item [ASC|DESC] [, ...]`
     *
     * @property items sort keys with direction
     */
    data class OrderBy(val items: List<SortItem>) : CypherClause()

    /**
     * `SKIP expression`
     *
     * @property count the number of rows to skip
     */
    data class Skip(val count: CypherExpr) : CypherClause()

    /**
     * `LIMIT expression`
     *
     * @property count the maximum number of rows to return
     */
    data class Limit(val count: CypherExpr) : CypherClause()

    /**
     * `UNION [ALL]`
     *
     * @property all true for `UNION ALL` (preserves duplicates)
     */
    data class Union(val all: Boolean = false) : CypherClause()

    /**
     * `CREATE pattern [, pattern ...]`
     *
     * Not executed against a read-only Graphite graph, but parsed for completeness.
     */
    data class Create(val patterns: List<CypherPattern>) : CypherClause()

    /**
     * `DELETE expression [, ...]` or `DETACH DELETE expression [, ...]`
     *
     * Not executed against a read-only Graphite graph, but parsed for completeness.
     */
    data class Delete(
        val expressions: List<CypherExpr>,
        val detach: Boolean = false
    ) : CypherClause()

    /**
     * `SET item [, item ...]`
     *
     * Not executed against a read-only Graphite graph, but parsed for completeness.
     */
    data class Set(val items: List<SetItem>) : CypherClause()

    /**
     * `REMOVE item [, item ...]`
     *
     * Not executed against a read-only Graphite graph, but parsed for completeness.
     */
    data class Remove(val items: List<RemoveItem>) : CypherClause()
}

/**
 * A RETURN or WITH projection item: `expression [AS alias]`.
 */
data class ReturnItem(
    val expression: CypherExpr,
    val alias: String? = null
)

/**
 * An ORDER BY sort item: `expression [ASC|DESC]`.
 */
data class SortItem(
    val expression: CypherExpr,
    val ascending: Boolean = true
)

/**
 * A SET clause item.
 */
sealed class SetItem {
    /** `variable.property = expression` */
    data class PropertySet(
        val variable: String,
        val property: String,
        val expression: CypherExpr
    ) : SetItem()

    /** `variable = expression` (replace all properties) */
    data class AllPropertiesSet(
        val variable: String,
        val expression: CypherExpr
    ) : SetItem()

    /** `variable += expression` (merge properties) */
    data class MergePropertiesSet(
        val variable: String,
        val expression: CypherExpr
    ) : SetItem()

    /** `variable:Label` */
    data class LabelSet(
        val variable: String,
        val labels: List<String>
    ) : SetItem()
}

/**
 * A REMOVE clause item.
 */
sealed class RemoveItem {
    /** `variable.property` */
    data class PropertyRemove(val variable: String, val property: String) : RemoveItem()

    /** `variable:Label` */
    data class LabelRemove(val variable: String, val labels: List<String>) : RemoveItem()
}
