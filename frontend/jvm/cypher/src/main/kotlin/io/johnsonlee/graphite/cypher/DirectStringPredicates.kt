package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringValueTransform

internal const val CALLER_CLASS_PROPERTY = "caller_class"
internal const val CALLER_NAME_PROPERTY = "caller_name"
internal const val CALLEE_CLASS_PROPERTY = "callee_class"
internal const val CALLEE_NAME_PROPERTY = "callee_name"
internal const val NODE_CLASS_PROPERTY = "class"
internal const val NODE_VALUE_PROPERTY = "value"

/** Compiled storage predicates; query planning and execution share this semantic model. */
internal val QUALIFIED_NODE_PROPERTIES = setOf(GRAPH_ID_PROPERTY, ELEMENT_ID_PROPERTY, QUALIFIED_ID_PROPERTY)

internal val CALL_SITE_DIRECT_STRING_PROPERTIES = setOf(
    CALLER_CLASS_PROPERTY,
    CALLER_NAME_PROPERTY,
    CALLEE_CLASS_PROPERTY,
    CALLEE_NAME_PROPERTY
)

internal val DIRECT_STRING_NODE_PROPERTIES = listOf(
    StringConstant::class.java to setOf(NODE_VALUE_PROPERTY),
    ResourceValueNode::class.java to setOf(NODE_VALUE_PROPERTY),
    EnumConstant::class.java to setOf("name", NODE_VALUE_PROPERTY),
    LocalVariable::class.java to setOf("name"),
    FieldNode::class.java to setOf(NODE_CLASS_PROPERTY, "name"),
    CallSiteNode::class.java to CALL_SITE_DIRECT_STRING_PROPERTIES,
    AnnotationNode::class.java to setOf(
        NODE_CLASS_PROPERTY,
        "name",
        NODE_VALUE_PROPERTY
    ) + CALL_SITE_DIRECT_STRING_PROPERTIES
)



internal val CALL_SITE_NULL_STRING_PROPERTIES = setOf(NODE_CLASS_PROPERTY, "name")

internal data class DirectStringFilter(
    val property: String,
    val mode: StringMatchMode,
    val expected: String,
    val transform: StringValueTransform? = null,
    val coercesToString: Boolean = false
) {
    fun matches(node: Node, graph: Graph): Boolean {
        val value = NodePropertyAccessor.getProperty(node, property, graph)
        val raw = (if (coercesToString) value?.toString() else value as? String) ?: return false
        val actual = when (transform) {
            null -> raw
            StringValueTransform.LOWERCASE -> raw.lowercase()
        }
        return when (mode) {
            StringMatchMode.EQUALS -> actual == expected
            StringMatchMode.STARTS_WITH -> actual.startsWith(expected)
            StringMatchMode.ENDS_WITH -> actual.endsWith(expected)
            StringMatchMode.CONTAINS -> actual.contains(expected)
        }
    }

    companion object {
        fun compile(
            expression: CypherExpr,
            variable: String,
            parameters: Map<String, Any?> = emptyMap()
        ): DirectStringFilter? {
            val operation = operation(expression) ?: return null
            return compileOperand(operation.first)?.let { operand ->
                val owner = operand.property.expression as? CypherExpr.Variable
                val expected = stringConstant(operation.second, parameters)
                if (owner?.name == variable && expected != null && operand.accepts(expected)) {
                    DirectStringFilter(
                        operand.property.propertyName, operation.third, expected, operand.transform, operand.coercesToString
                    )
                } else {
                    null
                }
            }
        }

        private fun operation(expression: CypherExpr): Triple<CypherExpr, CypherExpr, StringMatchMode>? = when (expression) {
            is CypherExpr.StringOp -> {
                val mode = when (expression.op) {
                    "STARTS WITH" -> StringMatchMode.STARTS_WITH
                    "ENDS WITH" -> StringMatchMode.ENDS_WITH
                    "CONTAINS" -> StringMatchMode.CONTAINS
                    else -> null
                }
                mode?.let { Triple(expression.left, expression.right, it) }
            }
            is CypherExpr.Comparison -> if (expression.op == "=") {
                Triple(expression.left, expression.right, StringMatchMode.EQUALS)
            } else {
                null
            }
            else -> null
        }

        fun compileRegexCandidate(
            expression: CypherExpr,
            variable: String,
            parameters: Map<String, Any?>
        ): DirectStringFilter? {
            val regex = expression as? CypherExpr.RegexMatch ?: return null
            return stringConstant(regex.right, parameters)?.let(::regexContainsLiteral)?.let { expected ->
                compile(CypherExpr.StringOp("CONTAINS", regex.left, CypherExpr.Literal(expected)), variable, parameters)
            }
        }

        private fun stringConstant(expression: CypherExpr, parameters: Map<String, Any?>): String? =
            when (expression) {
                is CypherExpr.Literal -> expression.value as? String
                is CypherExpr.Parameter -> parameters[expression.name] as? String
                else -> null
            }

        private fun regexContainsLiteral(pattern: String): String? {
            val quotedPrefix = ".*\\Q"
            val quotedSuffix = "\\E.*"
            if (!pattern.startsWith(quotedPrefix) || !pattern.endsWith(quotedSuffix)) return null
            val literal = pattern.substring(quotedPrefix.length, pattern.length - quotedSuffix.length)
            return literal.takeIf { it.isNotEmpty() && "\\E" !in it }
        }
        private fun compileOperand(expression: CypherExpr): StringOperand? = when (expression) {
            is CypherExpr.Property -> StringOperand(expression)
            is CypherExpr.FunctionCall -> compileFunctionOperand(expression)
            else -> null
        }

        private fun compileFunctionOperand(call: CypherExpr.FunctionCall): StringOperand? = when {
            call.distinct -> null
            call.name.equals("toString", ignoreCase = true) ->
                (call.args.singleOrNull() as? CypherExpr.Property)
                    ?.takeIf { it.propertyName in CALL_SITE_DIRECT_STRING_PROPERTIES }
                    ?.let { StringOperand(it, coercesToString = true) }
            call.args.size != 1 -> null
            call.name.equals("toLower", ignoreCase = true) || call.name.equals("toLowercase", ignoreCase = true) ->
                compileLowercaseOperand(call.args.single())
            else -> null
        }

        private fun compileLowercaseOperand(expression: CypherExpr): StringOperand? = when (expression) {
            is CypherExpr.Property -> StringOperand(expression, StringValueTransform.LOWERCASE)
            is CypherExpr.FunctionCall -> coalescedProperty(expression)?.let {
                StringOperand(it, StringValueTransform.LOWERCASE, coalescesMissingToEmpty = true)
            }
            else -> null
        }

        private fun coalescedProperty(call: CypherExpr.FunctionCall): CypherExpr.Property? {
            val isCoalesce = !call.distinct && call.name.equals("coalesce", ignoreCase = true)
            val defaultsToEmpty = call.args.size == 2 && call.args[1] == CypherExpr.Literal("")
            return if (isCoalesce && defaultsToEmpty) call.args[0] as? CypherExpr.Property else null
        }

        private data class StringOperand(
            val property: CypherExpr.Property,
            val transform: StringValueTransform? = null,
            val coalescesMissingToEmpty: Boolean = false,
            val coercesToString: Boolean = false
        ) {
            fun accepts(expected: String): Boolean = property.propertyName !in QUALIFIED_NODE_PROPERTIES &&
                !(coalescesMissingToEmpty && expected.isEmpty())
        }
    }
}

internal data class DirectStringCandidateFilters(
    val type: Class<out Node>,
    val filters: List<DirectStringFilter>,
    val predicates: List<StringPropertyPredicate>
)

internal data class DirectStringDisjunction(val filters: List<DirectStringFilter>) {
    val candidateFilters: List<DirectStringCandidateFilters> = DIRECT_STRING_NODE_PROPERTIES.mapNotNull {
            (candidateType, properties) ->
        val supported = filters.filter { filter -> filter.property in properties }
        supported.takeIf(List<*>::isNotEmpty)?.let {
            DirectStringCandidateFilters(
                candidateType,
                supported,
                supported.map { filter ->
                    StringPropertyPredicate(filter.property, filter.transform, filter.mode, filter.expected)
                }
            )
        }
    }

    fun matches(node: Node, graph: Graph): Boolean = filters.any { it.matches(node, graph) }

    companion object {
        fun compile(
            expression: CypherExpr,
            variable: String,
            parameters: Map<String, Any?> = emptyMap()
        ): DirectStringDisjunction? {
            val terms = flattenOr(expression)
            val filters = terms.flatMap { term ->
                guardedFilters(term, variable, parameters) ?: return null
            }.distinct()
            return filters.takeIf { it.isNotEmpty() && it.all(DirectStringFilter::hasSupportedProperty) }
                ?.let(::DirectStringDisjunction)
        }

        private fun flattenOr(expression: CypherExpr): List<CypherExpr> = when (expression) {
            is CypherExpr.Or -> flattenOr(expression.left) + flattenOr(expression.right)
            else -> listOf(expression)
        }
        private fun guardedFilters(
            expression: CypherExpr,
            variable: String,
            parameters: Map<String, Any?>
        ): List<DirectStringFilter>? {
            val direct = DirectStringFilter.compile(expression, variable, parameters)
            return when (expression) {
                is CypherExpr.ListOp -> direct?.let(::listOf) ?: membershipFilters(expression, variable, parameters)
                is CypherExpr.And -> direct?.let(::listOf) ?: existenceGuardedFilters(expression, variable, parameters)
                else -> direct?.let(::listOf)
            }
        }

        private fun membershipFilters(
            membership: CypherExpr.ListOp,
            variable: String,
            parameters: Map<String, Any?>
        ): List<DirectStringFilter>? {
            val values = membership.right as? CypherExpr.ListLiteral
            if (!membership.op.equals("IN", ignoreCase = true) || values == null) return null
            val filters = values.elements.map { value ->
                DirectStringFilter.compile(CypherExpr.Comparison("=", membership.left, value), variable, parameters)
            }
            return if (filters.any { it == null }) null else filters.filterNotNull()
        }

        private fun existenceGuardedFilters(
            and: CypherExpr.And,
            variable: String,
            parameters: Map<String, Any?>
        ): List<DirectStringFilter>? {
            val left = DirectStringFilter.compile(and.left, variable, parameters)
                ?.takeIf { isExistenceGuard(and.right, variable, it.property) }
            val right = if (left == null) DirectStringFilter.compile(and.right, variable, parameters)
                ?.takeIf { isExistenceGuard(and.left, variable, it.property) } else null
            return (left ?: right)?.let(::listOf)
        }

        private fun isExistenceGuard(expression: CypherExpr, variable: String, property: String): Boolean {
            val call = expression as? CypherExpr.FunctionCall ?: return false
            val argument = call.args.singleOrNull() as? CypherExpr.Property
            return call.name.equals("exists", ignoreCase = true) && !call.distinct &&
                argument == CypherExpr.Property(CypherExpr.Variable(variable), property)
        }

    }
}

internal data class DirectStringConjunction(
    val required: DirectStringFilter,
    val anyOf: DirectStringDisjunction
) {
    fun matches(node: Node, graph: Graph): Boolean = required.matches(node, graph) && anyOf.matches(node, graph)

    companion object {
        fun compile(
            expression: CypherExpr,
            variable: String,
            parameters: Map<String, Any?> = emptyMap()
        ): DirectStringConjunction? {
            val and = expression as? CypherExpr.And ?: return null
            val left = DirectStringFilter.compile(and.left, variable, parameters)
            val right = DirectStringFilter.compile(and.right, variable, parameters)
            return if (left != null && right != null) {
                orderFilters(left, right)
            } else {
                compile(and.left, and.right, variable, parameters) ?: compile(and.right, and.left, variable, parameters)
            }
        }

        private fun orderFilters(left: DirectStringFilter, right: DirectStringFilter): DirectStringConjunction? {
            val leftSupported = left.hasSupportedProperty()
            val rightSupported = right.hasSupportedProperty()
            if (!leftSupported && !rightSupported) return null
            val rightIsMoreSelective = right.mode == StringMatchMode.EQUALS && left.mode != StringMatchMode.EQUALS
            val preferRight = !leftSupported || rightSupported && rightIsMoreSelective
            val (required, residual) = if (preferRight) right to left else left to right
            return DirectStringConjunction(required, DirectStringDisjunction(listOf(residual)))
        }

        private fun compile(
            requiredExpression: CypherExpr,
            disjunctionExpression: CypherExpr,
            variable: String,
            parameters: Map<String, Any?>
        ): DirectStringConjunction? = DirectStringFilter.compile(requiredExpression, variable, parameters)
            ?.takeIf { required ->
                required.hasSupportedProperty()
            }
            ?.let { required ->
                DirectStringDisjunction.compile(disjunctionExpression, variable, parameters)
                    ?.let { anyOf -> DirectStringConjunction(required, anyOf) }
            }
    }
}

private const val PREFIX_RANK = 100
private const val SUFFIX_RANK = 200
private const val CONTAINS_RANK = 300
private const val OTHER_PROPERTY_RANK = 20
private const val MAX_TERM_RANK = 64

private fun DirectStringFilter.hasSupportedProperty(): Boolean =
    DIRECT_STRING_NODE_PROPERTIES.any { (_, properties) -> property in properties }

internal data class DirectStringCandidatePlan(val candidates: DirectStringDisjunction) {
    companion object {
        fun compile(
            expression: CypherExpr,
            variable: String,
            parameters: Map<String, Any?>
        ): DirectStringCandidatePlan? {
            val exact = DirectStringFilter.compile(expression, variable, parameters)
                ?.takeIf(DirectStringFilter::hasSupportedProperty)
                ?.let { DirectStringCandidatePlan(DirectStringDisjunction(listOf(it))) }
            return exact ?: DirectStringDisjunction.compile(expression, variable, parameters)?.let(::DirectStringCandidatePlan)
                ?: regexCandidate(expression, variable, parameters)
                ?: conjunctiveCandidate(expression, variable, parameters)
        }

        private fun regexCandidate(
            expression: CypherExpr,
            variable: String,
            parameters: Map<String, Any?>
        ): DirectStringCandidatePlan? = DirectStringFilter.compileRegexCandidate(expression, variable, parameters)
            ?.takeIf(DirectStringFilter::hasSupportedProperty)
            ?.let { DirectStringCandidatePlan(DirectStringDisjunction(listOf(it))) }

        private fun conjunctiveCandidate(
            expression: CypherExpr,
            variable: String,
            parameters: Map<String, Any?>
        ): DirectStringCandidatePlan? = (expression as? CypherExpr.And)?.let { and ->
            listOfNotNull(compile(and.left, variable, parameters), compile(and.right, variable, parameters))
                .minByOrNull(DirectStringCandidatePlan::selectivityRank)
        }
    }

    private fun selectivityRank(): Int = candidates.filters.sumOf { filter ->
        val modeRank = when (filter.mode) {
            StringMatchMode.EQUALS -> 0
            StringMatchMode.STARTS_WITH -> PREFIX_RANK
            StringMatchMode.ENDS_WITH -> SUFFIX_RANK
            StringMatchMode.CONTAINS -> CONTAINS_RANK
        }
        val propertyRank = when (filter.property) {
            CALLER_NAME_PROPERTY, CALLEE_NAME_PROPERTY, "name" -> 0
            else -> OTHER_PROPERTY_RANK
        }
        modeRank + propertyRank - filter.expected.length.coerceAtMost(MAX_TERM_RANK)
    }
}
