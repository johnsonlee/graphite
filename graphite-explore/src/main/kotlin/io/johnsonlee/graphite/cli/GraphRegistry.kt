package io.johnsonlee.graphite.cli

import io.javalin.http.Context
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.webgraph.GraphStore
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal const val API_FIELD_GRAPH_ID = "graphId"
internal const val API_FIELD_DATA = "data"
internal const val API_FIELD_GRAPHS = "graphs"
internal const val API_FIELD_LOADED_AT = "loadedAt"
internal const val API_FIELD_LOAD_MODE = "loadMode"
internal const val STANDALONE_GRAPH_ID = "standalone"

internal class GraphNotLoadedException(id: String) : RuntimeException("Graph not loaded: $id")

internal data class GraphStats(
    val nodes: Long,
    val edges: Long,
    val methods: Long,
    val callSites: Long
) {
    fun toApiMap(): Map<String, Long> = mapOf(
        API_FIELD_NODES to nodes,
        API_FIELD_EDGES to edges,
        API_FIELD_METHODS to methods,
        API_FIELD_CALL_SITES to callSites
    )

    operator fun plus(other: GraphStats): GraphStats = GraphStats(
        nodes = nodes + other.nodes,
        edges = edges + other.edges,
        methods = methods + other.methods,
        callSites = callSites + other.callSites
    )

    companion object {
        val EMPTY = GraphStats(0, 0, 0, 0)
    }
}

internal fun graphStats(graph: Graph): GraphStats {
    val nodes = graph.nodeCount(Node::class.java) ?: graph.nodes(Node::class.java).count().toLong()
    val edges = graph.edgeCount()
        ?: graph.nodes(Node::class.java).sumOf { graph.outgoing(it.id).count().toLong() }
    val methods = graph.methodCount() ?: graph.methods(MethodPattern()).count().toLong()
    val callSites = graph.nodeCount(CallSiteNode::class.java)
        ?: graph.nodes(CallSiteNode::class.java).count().toLong()
    return GraphStats(nodes, edges, methods, callSites)
}

internal data class GraphDescriptor(
    val id: String,
    val path: Path,
    val loadMode: GraphStore.LoadMode,
    val loadedAt: Instant,
    val stats: GraphStats
) {
    fun toApiMap(): Map<String, Any?> = mapOf(
        API_FIELD_ID to id,
        API_FIELD_PATH to path.toString(),
        API_FIELD_LOAD_MODE to loadMode.name,
        API_FIELD_LOADED_AT to loadedAt.toString()
    ) + stats.toApiMap()
}

internal interface GraphLease : Closeable {
    val id: String
    val graph: Graph
}

internal fun interface GraphProvider {
    fun acquire(ctx: Context): GraphLease?
}

internal class StaticGraphProvider(
    private val id: String,
    private val graph: Graph
) : GraphProvider {
    override fun acquire(ctx: Context): GraphLease? {
        val requestedId = ctx.pathParamMap()[API_FIELD_GRAPH_ID]
        if (requestedId != null && requestedId != id) return null
        return object : GraphLease {
            override val id: String = this@StaticGraphProvider.id
            override val graph: Graph = this@StaticGraphProvider.graph
            override fun close() = Unit
        }
    }
}

internal class RegistryPathGraphProvider(private val registry: GraphRegistry) : GraphProvider {
    override fun acquire(ctx: Context): GraphLease? =
        registry.acquire(ctx.pathParam(API_FIELD_GRAPH_ID))
}

internal class GraphRegistry(
    val dataDir: Path,
    val defaultLoadMode: GraphStore.LoadMode
) : Closeable {

    private val graphs = ConcurrentHashMap<String, ServedGraph>()

    fun load(
        id: String,
        path: Path,
        loadMode: GraphStore.LoadMode = defaultLoadMode
    ): GraphDescriptor {
        val cleanId = validateGraphId(id)
        val resolvedPath = resolveGraphPath(path)
        require(Files.isDirectory(resolvedPath)) { "Graph path is not a directory: $resolvedPath" }

        val graph = GraphStore.load(resolvedPath, loadMode)
        val served = ServedGraph(cleanId, resolvedPath, loadMode, graph)
        graphs.put(cleanId, served)?.retire()

        return served.descriptor()
    }

    fun unload(id: String): Boolean {
        val cleanId = validateGraphId(id)
        val removed = graphs.remove(cleanId) ?: return false
        removed.retire()
        return true
    }

    fun list(): List<GraphDescriptor> =
        graphs.values.asSequence()
            .map { it.descriptor() }
            .sortedBy { it.id }
            .toList()

    fun describe(id: String): GraphDescriptor? =
        graphs[validateGraphId(id)]?.descriptor()

    fun ids(): List<String> =
        graphs.keys.asSequence()
            .sorted()
            .toList()

    fun acquire(id: String): GraphLease? {
        val cleanId = validateGraphId(id)
        while (true) {
            val served = graphs[cleanId] ?: return null
            served.acquire()?.let { return it }
        }
    }

    fun acquireAll(): List<GraphLease> =
        ids().mapNotNull(::acquire)

    @Suppress("TooGenericExceptionCaught")
    fun acquireAll(ids: List<String>): List<GraphLease> {
        val leases = mutableListOf<GraphLease>()
        try {
            ids.map(::validateGraphId).distinct().forEach { id ->
                leases += acquire(id) ?: throw GraphNotLoadedException(id)
            }
            return leases
        } catch (error: RuntimeException) {
            leases.forEach { it.close() }
            throw error
        }
    }

    fun resolveGraphPath(path: Path): Path =
        if (path.isAbsolute) path.normalize() else dataDir.resolve(path).normalize()

    override fun close() {
        val loaded = graphs.values.toList()
        graphs.clear()
        loaded.forEach { it.retire() }
    }

    private class ServedGraph(
        private val id: String,
        private val path: Path,
        private val loadMode: GraphStore.LoadMode,
        private val graph: Graph
    ) {
        private val loadedAt = Instant.now()
        private val descriptor = GraphDescriptor(id, path, loadMode, loadedAt, graphStats(graph))
        private val leases = AtomicInteger()
        private val retired = AtomicBoolean()
        private val closed = AtomicBoolean()

        fun descriptor(): GraphDescriptor = descriptor

        @Suppress("ReturnCount")
        fun acquire(): GraphLease? {
            while (true) {
                if (retired.get()) return null
                val current = leases.get()
                if (leases.compareAndSet(current, current + 1)) {
                    if (retired.get()) {
                        release()
                        return null
                    }
                    return ServedGraphLease(this, id, graph)
                }
            }
        }

        fun retire() {
            if (retired.compareAndSet(false, true)) {
                closeIfIdle()
            }
        }

        private fun release() {
            if (leases.decrementAndGet() == 0 && retired.get()) {
                closeIfIdle()
            }
        }

        private fun closeIfIdle() {
            if (leases.get() == 0 && closed.compareAndSet(false, true)) {
                (graph as? Closeable)?.close()
            }
        }

        private class ServedGraphLease(
            private val owner: ServedGraph,
            override val id: String,
            override val graph: Graph
        ) : GraphLease {
            private val closed = AtomicBoolean()

            override fun close() {
                if (closed.compareAndSet(false, true)) {
                    owner.release()
                }
            }
        }
    }

    companion object {
        private val GRAPH_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

        fun validateGraphId(id: String): String {
            val cleanId = id.trim()
            require(GRAPH_ID_PATTERN.matches(cleanId)) {
                "Invalid graph id '$id'. Use 1-128 chars: letters, digits, dot, underscore, or dash."
            }
            return cleanId
        }
    }
}
