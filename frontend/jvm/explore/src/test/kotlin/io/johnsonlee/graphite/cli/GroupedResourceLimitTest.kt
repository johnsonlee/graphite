package io.johnsonlee.graphite.cli

import com.google.gson.Gson
import io.javalin.Javalin
import io.javalin.json.JavalinGson
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.ResourceEntry
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

@Suppress("DEPRECATION")
internal class GroupedResourceLimitTest : ExploreCommandTestSupport() {
    @Test
    fun `global resource reads reject cumulative bytes and blank paths`() {
        val bytes = ByteArray(MAX_RESOURCE_BYTES / 2 + 1) { 'x'.code.toByte() }
        val graph = DefaultGraph.Builder().setResources(object : ResourceAccessor {
            override fun list(pattern: String) = sequenceOf(ResourceEntry("large.txt", "fixture"))
            override fun open(path: String) = ByteArrayInputStream(bytes)
        }).build()
        val routes = ExploreRoutes()
        val app = Javalin.create { it.jsonMapper(JavalinGson(Gson())) }.start(0)
        try {
            routes.registerAllGraphRoutes(app) {
                listOf("a", "b").map { id -> object : GraphLease {
                    override val id = id
                    override val graph = graph
                    override fun close() = Unit
                } }
            }
            val (status, body) = get(app.port(), "/api/resources/large.txt")
            assertEquals(413, status, body)
            assertEquals("Resource exceeds maximum response size: large.txt", body)
            assertEquals(404, get(app.port(), "/api/resources/%20").first)
        } finally {
            app.stop()
            routes.cypherGuard.close()
        }
    }
}
