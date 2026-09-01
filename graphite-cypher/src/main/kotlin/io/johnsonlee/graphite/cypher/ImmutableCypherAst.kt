@file:Suppress("CyclomaticComplexMethod")

package io.johnsonlee.graphite.cypher

import java.util.Collections

/** Creates a deeply immutable AST that is safe to return from the process-wide parser cache. */
internal fun List<CypherClause>.toImmutableCypherAst(): List<CypherClause> =
    immutableList { clause -> clause.toImmutableCypherClause() }

private fun CypherClause.toImmutableCypherClause(): CypherClause = when (this) {
    is CypherClause.Match -> copy(
        patterns = patterns.immutableList(CypherPattern::toImmutableCypherPattern),
        where = where?.toImmutableCypherExpr()
    )
    is CypherClause.Where -> copy(condition = condition.toImmutableCypherExpr())
    is CypherClause.Return -> copy(items = items.immutableList(ReturnItem::toImmutableReturnItem))
    is CypherClause.With -> copy(
        items = items.immutableList(ReturnItem::toImmutableReturnItem),
        where = where?.toImmutableCypherExpr()
    )
    is CypherClause.Unwind -> copy(expression = expression.toImmutableCypherExpr())
    is CypherClause.OrderBy -> copy(items = items.immutableList(SortItem::toImmutableSortItem))
    is CypherClause.Skip -> copy(count = count.toImmutableCypherExpr())
    is CypherClause.Limit -> copy(count = count.toImmutableCypherExpr())
    is CypherClause.Union -> this
    is CypherClause.Create -> copy(patterns = patterns.immutableList(CypherPattern::toImmutableCypherPattern))
    is CypherClause.Delete -> copy(expressions = expressions.immutableList(CypherExpr::toImmutableCypherExpr))
    is CypherClause.Set -> copy(items = items.immutableList(SetItem::toImmutableSetItem))
    is CypherClause.Remove -> copy(items = items.immutableList(RemoveItem::toImmutableRemoveItem))
}

private fun ReturnItem.toImmutableReturnItem(): ReturnItem = copy(expression = expression.toImmutableCypherExpr())

private fun SortItem.toImmutableSortItem(): SortItem = copy(expression = expression.toImmutableCypherExpr())

private fun CypherPattern.toImmutableCypherPattern(): CypherPattern = copy(
    elements = elements.immutableList(PatternElement::toImmutablePatternElement)
)

private fun PatternElement.toImmutablePatternElement(): PatternElement = when (this) {
    is PatternElement.NodePattern -> copy(
        labels = labels.immutableList(),
        properties = properties.immutableMapValues(CypherExpr::toImmutableCypherExpr)
    )
    is PatternElement.RelationshipPattern -> copy(
        types = types.immutableList(),
        properties = properties.immutableMapValues(CypherExpr::toImmutableCypherExpr)
    )
}

private fun SetItem.toImmutableSetItem(): SetItem = when (this) {
    is SetItem.PropertySet -> copy(expression = expression.toImmutableCypherExpr())
    is SetItem.AllPropertiesSet -> copy(expression = expression.toImmutableCypherExpr())
    is SetItem.MergePropertiesSet -> copy(expression = expression.toImmutableCypherExpr())
    is SetItem.LabelSet -> copy(labels = labels.immutableList())
}

private fun RemoveItem.toImmutableRemoveItem(): RemoveItem = when (this) {
    is RemoveItem.PropertyRemove -> this
    is RemoveItem.LabelRemove -> copy(labels = labels.immutableList())
}

private fun CypherExpr.toImmutableCypherExpr(): CypherExpr = when (this) {
    is CypherExpr.Literal -> copy(value = value.toImmutableLiteral())
    is CypherExpr.Variable -> this
    is CypherExpr.Property -> copy(expression = expression.toImmutableCypherExpr())
    is CypherExpr.Parameter -> this
    is CypherExpr.FunctionCall -> copy(args = args.immutableList(CypherExpr::toImmutableCypherExpr))
    is CypherExpr.BinaryOp -> copy(left = left.toImmutableCypherExpr(), right = right.toImmutableCypherExpr())
    is CypherExpr.UnaryOp -> copy(expression = expression.toImmutableCypherExpr())
    is CypherExpr.Comparison -> copy(left = left.toImmutableCypherExpr(), right = right.toImmutableCypherExpr())
    is CypherExpr.StringOp -> copy(left = left.toImmutableCypherExpr(), right = right.toImmutableCypherExpr())
    is CypherExpr.ListOp -> copy(left = left.toImmutableCypherExpr(), right = right.toImmutableCypherExpr())
    is CypherExpr.RegexMatch -> copy(left = left.toImmutableCypherExpr(), right = right.toImmutableCypherExpr())
    is CypherExpr.IsNull -> copy(expression = expression.toImmutableCypherExpr())
    is CypherExpr.IsNotNull -> copy(expression = expression.toImmutableCypherExpr())
    is CypherExpr.Not -> copy(expression = expression.toImmutableCypherExpr())
    is CypherExpr.And -> copy(left = left.toImmutableCypherExpr(), right = right.toImmutableCypherExpr())
    is CypherExpr.Or -> copy(left = left.toImmutableCypherExpr(), right = right.toImmutableCypherExpr())
    is CypherExpr.Xor -> copy(left = left.toImmutableCypherExpr(), right = right.toImmutableCypherExpr())
    is CypherExpr.CaseExpr -> copy(
        test = test?.toImmutableCypherExpr(),
        whenClauses = whenClauses.immutableList { (condition, result) ->
            condition.toImmutableCypherExpr() to result.toImmutableCypherExpr()
        },
        elseExpr = elseExpr?.toImmutableCypherExpr()
    )
    is CypherExpr.ListLiteral -> copy(elements = elements.immutableList(CypherExpr::toImmutableCypherExpr))
    is CypherExpr.MapLiteral -> copy(entries = entries.immutableMapValues(CypherExpr::toImmutableCypherExpr))
    is CypherExpr.ListComprehension -> copy(
        listExpr = listExpr.toImmutableCypherExpr(),
        predicate = predicate?.toImmutableCypherExpr(),
        mapExpr = mapExpr?.toImmutableCypherExpr()
    )
    is CypherExpr.PredicateFunction -> copy(
        listExpr = listExpr.toImmutableCypherExpr(),
        predicate = predicate?.toImmutableCypherExpr()
    )
    is CypherExpr.Subscript -> copy(
        expression = expression.toImmutableCypherExpr(),
        index = index.toImmutableCypherExpr()
    )
    is CypherExpr.Slice -> copy(
        expression = expression.toImmutableCypherExpr(),
        from = from?.toImmutableCypherExpr(),
        to = to?.toImmutableCypherExpr()
    )
    is CypherExpr.Distinct -> copy(expression = expression.toImmutableCypherExpr())
    CypherExpr.CountStar -> this
}

private fun Any?.toImmutableLiteral(): Any? = when (this) {
    is List<*> -> immutableList { value -> value.toImmutableLiteral() }
    is Set<*> -> Collections.unmodifiableSet(mapTo(linkedSetOf()) { value -> value.toImmutableLiteral() })
    is Map<*, *> -> Collections.unmodifiableMap(
        entries.associateTo(linkedMapOf()) { (key, value) -> key.toImmutableLiteral() to value.toImmutableLiteral() }
    )
    else -> this
}

private fun <T> Iterable<T>.immutableList(): List<T> = Collections.unmodifiableList(toList())

private fun <T, R> Iterable<T>.immutableList(transform: (T) -> R): List<R> =
    Collections.unmodifiableList(map(transform))

private fun <K, V, R> Map<K, V>.immutableMapValues(transform: (V) -> R): Map<K, R> =
    Collections.unmodifiableMap(entries.associateTo(linkedMapOf()) { (key, value) -> key to transform(value) })
