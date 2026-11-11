package io.johnsonlee.graphite.cli

import io.javalin.http.Context
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.ResourceEntry
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

internal class GroupedRouteFailureTest {
    @Test
    fun `graph acquisition failures preserve client errors and never invoke query block`() {
        val routes = ExploreRoutes()
        try {
            val failures = listOf(IllegalArgumentException("invalid graph") to 400, GraphNotLoadedException("gone") to 404)
            for ((error, expectedStatus) in failures) {
                val response = Response()
                routes.withAllGraphs(response.context, { throw error }) { error("query must not run") }
                assertEquals(expectedStatus, response.status)
                assertEquals(mapOf("error" to error.message), response.body)
            }
            val invalid = Response()
            routes.withGraph(invalid.context, GraphProvider { throw IllegalArgumentException("invalid graph") }) {
                error("query must not run")
            }
            assertEquals(400, invalid.status)
            assertEquals(mapOf("error" to "invalid graph"), invalid.body)
            val missing = Response()
            routes.withGraph(missing.context, GraphProvider { null }) { error("query must not run") }
            assertEquals(404, missing.status)
            assertEquals(mapOf("error" to "Graph not loaded"), missing.body)
            assertEquals(0, routes.defaultPerGraphLimit(0, 100))
            assertEquals(SubgraphDirection.INCOMING, routes.resolveSubgraphDirection("incoming"))
        } finally {
            routes.cypherGuard.close()
        }
    }

    @Test
    fun `resource disappearing between listing and reading is treated as absent`() {
        val graph = DefaultGraph.Builder().setResources(object : ResourceAccessor {
            override fun list(pattern: String) = sequenceOf(ResourceEntry("config.yml", "fixture"))
            override fun open(path: String) = throw IOException("removed while opening")
        }).build()
        val routes = ExploreRoutes()
        try {
            assertNull(routes.readResource(graph, "config.yml", 100))
        } finally {
            routes.cypherGuard.close()
        }
    }

    @Test
    fun `failed topology response releases mapped stream so retired files are deleted`() {
        val root = java.nio.file.Files.createTempDirectory("topology-response-failure")
        val registry = GraphRegistry(root, io.johnsonlee.graphite.webgraph.GraphStore.LoadMode.MAPPED)
        val topology = TopologyService(registry, emptyList(), root)
        try {
            topology.rebuild()
            val previous = topology.currentBuildDir()
            val ctx = Proxy.newProxyInstance(Context::class.java.classLoader, arrayOf(Context::class.java)) { proxy, method, _ ->
                when (method.name) {
                    "contentType", "header" -> proxy
                    "result" -> error("response rejected")
                    else -> error("Unexpected response operation ${method.name}")
                }
            } as Context
            assertEquals("response rejected", assertFailsWith<IllegalStateException> { respondTopology(ctx, topology) }.message)
            topology.rebuild()
            assertFalse(java.nio.file.Files.exists(previous), "failed response must not retain its old mapped snapshot")
        } finally {
            topology.close()
            registry.close()
            root.toFile().deleteRecursively()
        }
    }

    private class Response {
        var status: Int? = null
        var body: Any? = null
        val context: Context = Proxy.newProxyInstance(
            Context::class.java.classLoader, arrayOf(Context::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "status" -> { status = args!![0] as Int; proxy }
                "json" -> { body = args!![0]; proxy }
                else -> error("Unexpected response operation: ${method.name}")
            }
        } as Context
    }
}
