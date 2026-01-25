package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.HttpMethod
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Test endpoint extraction from Spring MVC annotations.
 */
class EndpointExtractionTest {

    private val expectedEndpoints = mapOf(
        "GET /api/users/{id}" to "getUser",
        "GET /api/users" to "listUsers",
        "POST /api/users" to "createUser",
        "PUT /api/users/{id}" to "updateUser",
        "DELETE /api/users/{id}" to "deleteUser",
        "GET /api/orders/{orderId}" to "getOrder"
    )

    @Test
    fun `should extract endpoints from Spring MVC annotations`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.api"),
            buildCallGraph = false,
            verbose = { println(it) }
        ))

        val graph = loader.load(testClassesDir)

        // Get all endpoints
        val endpoints = graph.endpoints().toList()

        println("Found ${endpoints.size} endpoints:")
        endpoints.forEach { endpoint ->
            println("  ${endpoint.httpMethod} ${endpoint.path} -> ${endpoint.method.name}")
        }

        assertTrue(endpoints.isNotEmpty(), "Should find at least one endpoint")

        // Verify expected endpoints
        expectedEndpoints.forEach { (expectedKey, expectedMethodName) ->
            val parts = expectedKey.split(" ", limit = 2)
            val httpMethod = HttpMethod.valueOf(parts[0])
            val path = parts[1]

            val found = endpoints.any { endpoint ->
                endpoint.httpMethod == httpMethod &&
                endpoint.path == path &&
                endpoint.method.name == expectedMethodName
            }

            assertTrue(found, "Should find endpoint: $expectedKey -> $expectedMethodName")
        }
    }

    @Test
    fun `should match endpoint patterns with wildcards`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.api"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)

        // Test pattern matching: /api/users/* should match /api/users/{id}
        val usersEndpoints = graph.endpoints("/api/users/*").toList()
        println("Endpoints matching /api/users/*:")
        usersEndpoints.forEach { println("  ${it.httpMethod} ${it.path}") }

        assertTrue(usersEndpoints.isNotEmpty(), "Should find endpoints matching /api/users/*")

        // Test ** wildcard
        val allApiEndpoints = graph.endpoints("/api/**").toList()
        println("Endpoints matching /api/**:")
        allApiEndpoints.forEach { println("  ${it.httpMethod} ${it.path}") }

        assertEquals(expectedEndpoints.size, allApiEndpoints.size,
            "Should find all endpoints with /api/**")
    }

    @Test
    fun `should filter endpoints by HTTP method`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.api"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)

        // Filter by GET method
        val getEndpoints = graph.endpoints(httpMethod = HttpMethod.GET).toList()
        println("GET endpoints:")
        getEndpoints.forEach { println("  ${it.path}") }

        assertTrue(getEndpoints.all { it.httpMethod == HttpMethod.GET },
            "All returned endpoints should be GET")

        val expectedGetCount = expectedEndpoints.count { it.key.startsWith("GET ") }
        assertEquals(expectedGetCount, getEndpoints.size, "Should find $expectedGetCount GET endpoints")
    }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }
}
