package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.cypher.CrossGraphCypherExecutor
import io.johnsonlee.graphite.cypher.CypherGraph
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

internal const val TOPOLOGY_SOURCE_GRAPH = "sourceGraph"
internal const val TOPOLOGY_TARGET_GRAPH = "targetGraph"
internal const val TOPOLOGY_PROTOCOL = "protocol"
internal const val TOPOLOGY_OPERATION = "operation"
internal const val TOPOLOGY_WEIGHT = "weight"
internal const val TOPOLOGY_EVIDENCE = "evidence"

private const val DEFAULT_TOPOLOGY_PROTOCOL = "call"
private const val MAX_TOPOLOGY_ROWS = 100_000
private const val MAX_EDGE_DETAILS = 100

internal data class TopologyQuery(
    val name: String,
    val cypher: String
)

internal object TopologyQuerySource {
    fun load(path: Path?): List<TopologyQuery> {
        if (path == null) return emptyList()
        val absolute = path.toAbsolutePath().normalize()
        require(Files.exists(absolute)) { "Topology query path does not exist: $absolute" }
        val files = when {
            Files.isRegularFile(absolute) -> listOf(absolute)
            Files.isDirectory(absolute) -> Files.list(absolute).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".cypher") }
                    .sorted()
                    .toList()
            }
            else -> emptyList()
        }
        require(files.isNotEmpty()) { "No .cypher topology queries found at: $absolute" }
        return files.map { file ->
            val cypher = Files.readString(file).trim()
            require(cypher.isNotEmpty()) { "Topology query is empty: $file" }
            TopologyQuery(file.fileName.toString(), cypher)
        }
    }
}

internal data class TopologyNode(
    val id: String,
    val stats: GraphStats
) {
    fun toApiMap(): Map<String, Any> = mapOf(
        API_FIELD_ID to id,
        API_FIELD_GRAPH_ID to id,
        API_FIELD_TYPE to "Graph",
        API_FIELD_LABEL to id
    ) + stats.toApiMap()
}

internal data class TopologyEdge(
    val from: String,
    val to: String,
    val protocol: String,
    val weight: Long,
    val operations: List<String>,
    val evidence: List<String>
) {
    fun toApiMap(): Map<String, Any> = mapOf(
        API_FIELD_FROM to from,
        API_FIELD_TO to to,
        API_FIELD_TYPE to "TopologyCall",
        TOPOLOGY_PROTOCOL to protocol,
        TOPOLOGY_WEIGHT to weight,
        "operations" to operations,
        TOPOLOGY_EVIDENCE to evidence
    )
}

internal data class TopologyGraph(
    val nodes: List<TopologyNode>,
    val edges: List<TopologyEdge>,
    val builtAt: Instant,
    val rules: List<String>,
    val matchedRows: Int
) {
    fun toApiMap(currentGraphIds: List<String>): Map<String, Any> = mapOf(
        API_FIELD_NODES to nodes.map { it.toApiMap() },
        API_FIELD_EDGES to edges.map { it.toApiMap() },
        "graphCount" to nodes.size,
        "relationCount" to edges.size,
        "matchedRows" to matchedRows,
        "builtAt" to builtAt.toString(),
        "rules" to rules,
        "stale" to (nodes.map { it.id } != currentGraphIds.sorted())
    )

    companion object {
        fun nodesOnly(stats: Map<String, GraphStats>): TopologyGraph = TopologyGraph(
            nodes = stats.toSortedMap().map { (id, graphStats) -> TopologyNode(id, graphStats) },
            edges = emptyList(),
            builtAt = Instant.now(),
            rules = emptyList(),
            matchedRows = 0
        )
    }
}

internal object TopologyGraphBuilder {
    fun build(
        graphs: List<CypherGraph>,
        stats: Map<String, GraphStats>,
        queries: List<TopologyQuery>
    ): TopologyGraph {
        if (queries.isEmpty()) return TopologyGraph.nodesOnly(stats)
        require(graphs.map { it.id }.toSet() == stats.keys) {
            "Topology graph catalog must match the loaded graph catalog"
        }

        val executor = CrossGraphCypherExecutor(graphs)
        val loadedIds = stats.keys
        val aggregates = linkedMapOf<TopologyEdgeKey, MutableTopologyEdge>()
        var matchedRows = 0

        for (query in queries) {
            val remainingRows = MAX_TOPOLOGY_ROWS - matchedRows
            val result = executor.execute(query.cypher, remainingRows + 1)
            require(TOPOLOGY_SOURCE_GRAPH in result.columns && TOPOLOGY_TARGET_GRAPH in result.columns) {
                "Topology query '${query.name}' must return '$TOPOLOGY_SOURCE_GRAPH' and '$TOPOLOGY_TARGET_GRAPH'"
            }
            require(result.rows.size <= remainingRows) {
                "Topology queries exceeded the combined $MAX_TOPOLOGY_ROWS row limit at '${query.name}'"
            }
            matchedRows += result.rows.size
            for (row in result.rows) {
                val source = requiredGraphId(query, row, TOPOLOGY_SOURCE_GRAPH, loadedIds)
                val target = requiredGraphId(query, row, TOPOLOGY_TARGET_GRAPH, loadedIds)
                if (source == target) continue
                val protocol = optionalText(row[TOPOLOGY_PROTOCOL]) ?: DEFAULT_TOPOLOGY_PROTOCOL
                val weight = optionalWeight(query, row[TOPOLOGY_WEIGHT])
                val key = TopologyEdgeKey(source, target, protocol)
                val edge = aggregates.getOrPut(key) { MutableTopologyEdge() }
                edge.weight = Math.addExact(edge.weight, weight)
                optionalText(row[TOPOLOGY_OPERATION])?.let { edge.operations.addBounded(it) }
                optionalText(row[TOPOLOGY_EVIDENCE])?.let { edge.evidence.addBounded(it) }
            }
        }

        return TopologyGraph(
            nodes = stats.toSortedMap().map { (id, graphStats) -> TopologyNode(id, graphStats) },
            edges = aggregates.entries.map { (key, value) ->
                TopologyEdge(
                    from = key.from,
                    to = key.to,
                    protocol = key.protocol,
                    weight = value.weight,
                    operations = value.operations.sorted(),
                    evidence = value.evidence.sorted()
                )
            }.sortedWith(compareBy(TopologyEdge::from, TopologyEdge::to, TopologyEdge::protocol)),
            builtAt = Instant.now(),
            rules = queries.map { it.name },
            matchedRows = matchedRows
        )
    }

    private fun requiredGraphId(
        query: TopologyQuery,
        row: Map<String, Any?>,
        column: String,
        loadedIds: Set<String>
    ): String {
        val id = optionalText(row[column])
            ?: error("Topology query '${query.name}' returned a blank '$column'")
        require(id in loadedIds) {
            "Topology query '${query.name}' returned unknown graph '$id' in '$column'"
        }
        return id
    }

    private fun optionalWeight(query: TopologyQuery, value: Any?): Long = when (value) {
        null -> 1L
        is Number -> value.toLong().also {
            require(it > 0 && it.toDouble() == value.toDouble()) {
                "Topology query '${query.name}' returned a non-positive or fractional weight: $value"
            }
        }
        else -> value.toString().toLongOrNull()?.also {
            require(it > 0) { "Topology query '${query.name}' returned a non-positive weight: $value" }
        } ?: error("Topology query '${query.name}' returned an invalid weight: $value")
    }

    private fun optionalText(value: Any?): String? = value?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun MutableSet<String>.addBounded(value: String) {
        if (size < MAX_EDGE_DETAILS) add(value)
    }

    private data class TopologyEdgeKey(val from: String, val to: String, val protocol: String)

    private class MutableTopologyEdge {
        var weight: Long = 0
        val operations = linkedSetOf<String>()
        val evidence = linkedSetOf<String>()
    }
}

internal class TopologyService(
    private val registry: GraphRegistry,
    private val queries: List<TopologyQuery>
) {
    @Volatile
    private var graph: TopologyGraph = TopologyGraph.nodesOnly(emptyMap())

    fun rebuild(): TopologyGraph = synchronized(this) {
        val descriptors = registry.list()
        val stats = descriptors.associate { it.id to it.stats }
        val next = if (queries.isEmpty()) {
            TopologyGraph.nodesOnly(stats)
        } else {
            val leases = registry.acquireAll()
            try {
                TopologyGraphBuilder.build(
                    leases.map { CypherGraph(it.id, it.graph) },
                    stats,
                    queries
                )
            } finally {
                leases.forEach { it.close() }
            }
        }
        graph = next
        next
    }

    fun snapshot(): TopologyGraph = graph

    fun toApiMap(): Map<String, Any> = graph.toApiMap(registry.ids())
}
