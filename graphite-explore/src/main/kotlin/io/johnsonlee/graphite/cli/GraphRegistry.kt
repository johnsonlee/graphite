package io.johnsonlee.graphite.cli

import io.javalin.http.Context
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.webgraph.GraphStore
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal const val API_FIELD_GRAPH_ID = "graphId"
internal const val API_FIELD_DATA = "data"
internal const val API_FIELD_GRAPHS = "graphs"
internal const val API_FIELD_LOADED_AT = "loadedAt"
internal const val API_FIELD_LOAD_MODE = "loadMode"
internal const val DEFAULT_GRAPH_ID = "default"

internal data class GraphDescriptor(
    val id: String,
    val path: Path,
    val loadMode: GraphStore.LoadMode,
    val loadedAt: Instant,
    val nodeCount: Long?,
    val edgeCount: Long?
) {
    fun toApiMap(): Map<String, Any?> = mapOf(
        API_FIELD_ID to id,
        API_FIELD_PATH to path.toString(),
        API_FIELD_LOAD_MODE to loadMode.name,
        API_FIELD_LOADED_AT to loadedAt.toString(),
        API_FIELD_NODES to nodeCount,
        API_FIELD_EDGES to edgeCount
    )
}

internal interface GraphLease : Closeable {
    val id: String
    val graph: Graph
}

internal fun interface GraphProvider {
    fun acquire(ctx: Context): GraphLease?
}

internal class StaticGraphProvider(private val graph: Graph) : GraphProvider {
    override fun acquire(ctx: Context): GraphLease = object : GraphLease {
        override val id: String = DEFAULT_GRAPH_ID
        override val graph: Graph = this@StaticGraphProvider.graph
        override fun close() = Unit
    }
}

internal class RegistryDefaultGraphProvider(private val registry: GraphRegistry) : GraphProvider {
    override fun acquire(ctx: Context): GraphLease? = registry.acquireDefault()
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
    private val defaultGraphId = AtomicReference<String?>()

    fun load(
        id: String,
        path: Path,
        loadMode: GraphStore.LoadMode = defaultLoadMode,
        makeDefault: Boolean = false
    ): GraphDescriptor {
        val cleanId = validateGraphId(id)
        val resolvedPath = resolveGraphPath(path)
        require(Files.isDirectory(resolvedPath)) { "Graph path is not a directory: $resolvedPath" }

        val graph = GraphStore.load(resolvedPath, loadMode)
        val served = ServedGraph(cleanId, resolvedPath, loadMode, graph)
        graphs.put(cleanId, served)?.retire()

        if (makeDefault || defaultGraphId.get() == null) {
            defaultGraphId.set(cleanId)
        }

        return served.descriptor()
    }

    fun unload(id: String): Boolean {
        val cleanId = validateGraphId(id)
        val removed = graphs.remove(cleanId) ?: return false
        removed.retire()
        defaultGraphId.compareAndSet(cleanId, graphs.keys.asSequence().sorted().firstOrNull())
        return true
    }

    fun list(): List<GraphDescriptor> =
        graphs.values.asSequence()
            .map { it.descriptor() }
            .sortedBy { it.id }
            .toList()

    fun acquire(id: String): GraphLease? =
        graphs[validateGraphId(id)]?.acquire()

    fun acquireDefault(): GraphLease? =
        defaultGraphId.get()?.let { acquire(it) }

    fun resolveGraphPath(path: Path): Path =
        if (path.isAbsolute) path.normalize() else dataDir.resolve(path).normalize()

    override fun close() {
        val loaded = graphs.values.toList()
        graphs.clear()
        defaultGraphId.set(null)
        loaded.forEach { it.retire() }
    }

    private class ServedGraph(
        private val id: String,
        private val path: Path,
        private val loadMode: GraphStore.LoadMode,
        private val graph: Graph
    ) {
        private val loadedAt = Instant.now()
        private val leases = AtomicInteger()
        private val retired = AtomicBoolean()
        private val closed = AtomicBoolean()

        fun descriptor(): GraphDescriptor =
            GraphDescriptor(
                id = id,
                path = path,
                loadMode = loadMode,
                loadedAt = loadedAt,
                nodeCount = graph.nodeCount(Node::class.java),
                edgeCount = graph.edgeCount()
            )

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
