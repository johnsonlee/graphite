package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.PreparedStringPropertyDisjunctionLookup
import io.johnsonlee.graphite.graph.RetainedStringPropertyDisjunctionLookup
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionLookupStrategy
import io.johnsonlee.graphite.graph.StringPropertyPredicate

private const val RAW_LEADING_MAX_TERM_LENGTH = 4

private const val RAW_LEADING_MAX_LIMIT = 200

/** Chooses graph/segment parallelism from storage capabilities without executing a query. */
internal class StringScanPolicy(private val sources: List<CypherGraph>, private val qualified: Boolean) {
    fun hasTypedDirectStringCandidate(nodeClass: Class<out Node>, filter: DirectStringFilter): Boolean =
        DIRECT_STRING_NODE_PROPERTIES.any { (candidateType, properties) ->
            // Concrete value labels already have single-property index admission and budget semantics.
            // Only polymorphic value discovery needs the new merge of typed candidate streams.
            nodeClass.isAssignableFrom(candidateType) &&
                filter.property in properties &&
                (filter.property != "value" || nodeClass != candidateType)
        }

    fun canExecuteDirectStringDisjunctionInParallel(
        nodeClass: Class<out Node>,
        variable: String,
        filter: DirectStringDisjunction,
        items: List<ReturnItem>,
        candidateSources: List<CypherGraph>
    ): Boolean =
        qualified &&
            candidateSources.size > 1 &&
            directStringGraphParallelism(candidateSources.size) > 1 &&
            !isDirectStringWorker &&
            shouldParallelizeStringScan(nodeClass, filter, candidateSources) &&
            items.all { item ->
                when (val expression = item.expression) {
                    is CypherExpr.Literal -> true
                    is CypherExpr.Property -> expression.expression == CypherExpr.Variable(variable)
                    else -> false
                }
            }

    fun shouldParallelizeStringScan(
        nodeClass: Class<out Node>,
        filter: DirectStringDisjunction,
        candidateSources: List<CypherGraph> = sources
    ): Boolean =
        DIRECT_STRING_NODE_PROPERTIES.any { (candidateType, properties) ->
            if (!nodeClass.isAssignableFrom(candidateType)) return@any false
            val predicates =
                filter.filters
                    .filter { it.property in properties }
                    .map { StringPropertyPredicate(it.property, it.transform, it.mode, it.expected) }
            predicates.isNotEmpty() &&
                candidateSources.any { source ->
                    if (source.graph.nodeCount(candidateType) == 0L) return@any false
                    val strategy = source.graph as? StringPropertyDisjunctionLookupStrategy
                    when (strategy?.prefersSerialStringPropertyDisjunction(candidateType, predicates)) {
                        true -> false
                        false -> true
                        null -> {
                            val prepared = source.graph as? PreparedStringPropertyDisjunctionLookup
                            prepared?.hasPreparedStringPropertyDisjunction(candidateType, predicates) != true
                        }
                    }
                }
        }

    fun hasPreparedWideStringDisjunction(
        nodeClass: Class<out Node>,
        filter: DirectStringDisjunction,
        candidateSources: List<CypherGraph>
    ): Boolean {
        if (!usesBalancedStringSplit(candidateSources.size)) return false
        val candidates =
            DIRECT_STRING_NODE_PROPERTIES.mapNotNull { (candidateType, properties) ->
                if (!nodeClass.isAssignableFrom(candidateType)) return@mapNotNull null
                val predicates =
                    filter.filters
                        .filter { it.property in properties }
                        .map { StringPropertyPredicate(it.property, it.transform, it.mode, it.expected) }
                (candidateType to predicates).takeIf { predicates.isNotEmpty() }
            }
        return candidates.isNotEmpty() &&
            candidateSources.all { source ->
                val prepared = source.graph as? PreparedStringPropertyDisjunctionLookup ?: return@all false
                val strategy = source.graph as? StringPropertyDisjunctionLookupStrategy
                candidates.all { (candidateType, predicates) ->
                    source.graph.nodeCount(candidateType) == 0L ||
                        strategy?.prefersSerialStringPropertyDisjunction(candidateType, predicates) == true ||
                        prepared.hasPreparedStringPropertyDisjunction(candidateType, predicates)
                }
            }
    }

    fun hasRetainedStringDisjunction(
        nodeClass: Class<out Node>,
        filter: DirectStringDisjunction,
        candidateSources: List<CypherGraph>
    ): Boolean {
        val candidates =
            DIRECT_STRING_NODE_PROPERTIES.mapNotNull { (candidateType, properties) ->
                if (!nodeClass.isAssignableFrom(candidateType)) return@mapNotNull null
                val predicates =
                    filter.filters
                        .filter { it.property in properties }
                        .map { StringPropertyPredicate(it.property, it.transform, it.mode, it.expected) }
                (candidateType to predicates).takeIf { predicates.isNotEmpty() }
            }
        if (candidates.isEmpty()) return false
        return candidateSources.all { source ->
            val retained = source.graph as? RetainedStringPropertyDisjunctionLookup ?: return@all false
            candidates.all { (candidateType, predicates) ->
                source.graph.nodeCount(candidateType) == 0L || retained.hasRetainedStringPropertyDisjunction(candidateType, predicates)
            }
        }
    }

    fun prefersBoundedRawLeadingProbe(filter: DirectStringDisjunction, limit: Int): Boolean =
        limit in 1..RAW_LEADING_MAX_LIMIT &&
            filter.filters.isNotEmpty() &&
            filter.filters.all { filter ->
                filter.mode == StringMatchMode.CONTAINS && filter.expected.length in 1..RAW_LEADING_MAX_TERM_LENGTH
            }
}
