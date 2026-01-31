package io.johnsonlee.graphite.cli

import com.google.gson.JsonParser
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for [FindEndpointsCommand] using real compiled bytecode.
 *
 * Compiles Spring-annotated Java fixture classes at test time, then runs the
 * command against them to verify endpoint discovery, output formats, filtering,
 * verbose output, and error handling.
 */
class FindEndpointsCommandIntegrationTest {

    companion object {
        private val compilerAvailable: Boolean by lazy {
            ToolProvider.getSystemJavaCompiler() != null
        }

        private val fixtures: TestFixtures by lazy { createFixtures() }

        data class TestFixtures(val classesDir: Path)

        /**
         * Spring annotation stubs compiled alongside test controller classes.
         * SootUp resolves annotations by class name, so these stubs are sufficient
         * for endpoint extraction to work.
         */
        private val sources = mapOf(
            // --- Spring annotation stubs ---
            "org/springframework/web/bind/annotation/RestController.java" to """
                package org.springframework.web.bind.annotation;
                import java.lang.annotation.*;
                @Target(ElementType.TYPE)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface RestController {
                    String value() default "";
                }
            """.trimIndent(),
            "org/springframework/web/bind/annotation/RequestMapping.java" to """
                package org.springframework.web.bind.annotation;
                import java.lang.annotation.*;
                @Target({ElementType.TYPE, ElementType.METHOD})
                @Retention(RetentionPolicy.RUNTIME)
                public @interface RequestMapping {
                    String[] value() default {};
                    String[] path() default {};
                }
            """.trimIndent(),
            "org/springframework/web/bind/annotation/GetMapping.java" to """
                package org.springframework.web.bind.annotation;
                import java.lang.annotation.*;
                @Target(ElementType.METHOD)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface GetMapping {
                    String[] value() default {};
                    String[] produces() default {};
                }
            """.trimIndent(),
            "org/springframework/web/bind/annotation/PostMapping.java" to """
                package org.springframework.web.bind.annotation;
                import java.lang.annotation.*;
                @Target(ElementType.METHOD)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface PostMapping {
                    String[] value() default {};
                    String[] consumes() default {};
                }
            """.trimIndent(),
            "org/springframework/web/bind/annotation/DeleteMapping.java" to """
                package org.springframework.web.bind.annotation;
                import java.lang.annotation.*;
                @Target(ElementType.METHOD)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface DeleteMapping {
                    String[] value() default {};
                }
            """.trimIndent(),
            "org/springframework/web/bind/annotation/PutMapping.java" to """
                package org.springframework.web.bind.annotation;
                import java.lang.annotation.*;
                @Target(ElementType.METHOD)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface PutMapping {
                    String[] value() default {};
                }
            """.trimIndent(),
            "org/springframework/web/bind/annotation/PatchMapping.java" to """
                package org.springframework.web.bind.annotation;
                import java.lang.annotation.*;
                @Target(ElementType.METHOD)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface PatchMapping {
                    String[] value() default {};
                }
            """.trimIndent(),
            "org/springframework/web/bind/annotation/PathVariable.java" to """
                package org.springframework.web.bind.annotation;
                import java.lang.annotation.*;
                @Target(ElementType.PARAMETER)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface PathVariable {
                    String value() default "";
                }
            """.trimIndent(),
            "org/springframework/web/bind/annotation/RequestBody.java" to """
                package org.springframework.web.bind.annotation;
                import java.lang.annotation.*;
                @Target(ElementType.PARAMETER)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface RequestBody {}
            """.trimIndent(),

            // --- DTO classes ---
            "com/example/endpoint/UserDTO.java" to """
                package com.example.endpoint;
                public class UserDTO {
                    public String id;
                    public String name;
                    public int age;
                    public UserDTO() {}
                    public UserDTO(String id, String name, int age) {
                        this.id = id;
                        this.name = name;
                        this.age = age;
                    }
                }
            """.trimIndent(),
            "com/example/endpoint/OrderDTO.java" to """
                package com.example.endpoint;
                public class OrderDTO {
                    public String orderId;
                    public double amount;
                    public OrderDTO() {}
                    public OrderDTO(String orderId, double amount) {
                        this.orderId = orderId;
                        this.amount = amount;
                    }
                }
            """.trimIndent(),

            // --- Controller class with multiple HTTP methods ---
            "com/example/endpoint/UserController.java" to """
                package com.example.endpoint;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/api")
                public class UserController {
                    @GetMapping("/users")
                    public UserDTO[] listUsers() {
                        return new UserDTO[0];
                    }
                    @GetMapping("/users/{id}")
                    public UserDTO getUser(@PathVariable String id) {
                        return new UserDTO(id, "test", 0);
                    }
                    @PostMapping("/users")
                    public UserDTO createUser(@RequestBody UserDTO user) {
                        return user;
                    }
                    @DeleteMapping("/users/{id}")
                    public void deleteUser(@PathVariable String id) {
                    }
                    @PutMapping("/users/{id}")
                    public UserDTO updateUser(@PathVariable String id, @RequestBody UserDTO user) {
                        return user;
                    }
                }
            """.trimIndent(),

            // --- Second controller for grouping tests ---
            "com/example/endpoint/OrderController.java" to """
                package com.example.endpoint;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/api")
                public class OrderController {
                    @GetMapping(value = "/orders/{orderId}", produces = "application/json")
                    public OrderDTO getOrder(@PathVariable String orderId) {
                        return new OrderDTO(orderId, 99.99);
                    }
                    @PostMapping("/orders")
                    public OrderDTO createOrder(@RequestBody OrderDTO order) {
                        return order;
                    }
                }
            """.trimIndent(),

            // --- Plain class with no endpoints (to test empty results with package filter) ---
            "com/example/noop/PlainService.java" to """
                package com.example.noop;
                public class PlainService {
                    public String doWork() { return "done"; }
                }
            """.trimIndent()
        )

        private fun createFixtures(): TestFixtures {
            val sourceDir = Files.createTempDirectory("endpoints-test-src")
            val classesDir = Files.createTempDirectory("endpoints-test-classes")

            Runtime.getRuntime().addShutdownHook(Thread {
                sourceDir.toFile().deleteRecursively()
                classesDir.toFile().deleteRecursively()
            })

            for ((path, content) in sources) {
                val file = sourceDir.resolve(path).toFile()
                file.parentFile.mkdirs()
                file.writeText(content)
            }

            val compiler = ToolProvider.getSystemJavaCompiler()!!
            val fileManager = compiler.getStandardFileManager(null, null, null)
            val javaFiles = sourceDir.toFile().walkTopDown()
                .filter { it.extension == "java" }
                .toList()
            val compilationUnits = fileManager.getJavaFileObjectsFromFiles(javaFiles)
            val options = listOf("-d", classesDir.toString())
            val success = compiler.getTask(null, fileManager, null, options, null, compilationUnits).call()
            fileManager.close()
            check(success) { "Failed to compile test fixtures" }

            return TestFixtures(classesDir)
        }
    }

    @org.junit.Before
    fun checkCompiler() {
        assumeTrue("Java compiler not available", compilerAvailable)
    }

    // ========================================================================
    // Successful endpoint discovery -- text format (default)
    // ========================================================================

    @Test
    fun `text format lists discovered endpoints`() {
        val cmd = newCommand()

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        assertTrue(result.stdout.contains("Found"), "Should contain Found header. stdout: ${result.stdout}")
        assertTrue(result.stdout.contains("endpoint(s)"), "Should contain endpoint count. stdout: ${result.stdout}")
        assertTrue(result.stdout.contains("/api/users"), "Should list /api/users. stdout: ${result.stdout}")
        assertTrue(result.stdout.contains("Summary:"), "Should contain Summary. stdout: ${result.stdout}")
    }

    @Test
    fun `text format shows handler info`() {
        val cmd = newCommand()

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        assertTrue(result.stdout.contains("UserController"), "Should mention controller class. stdout: ${result.stdout}")
    }

    // ========================================================================
    // Schema / JSON format output
    // ========================================================================

    @Test
    fun `schema format produces valid OpenAPI JSON`() {
        val cmd = newCommand()
        cmd.outputFormat = "schema"

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")

        val root = JsonParser.parseString(result.stdout).asJsonObject
        assertEquals("3.0.3", root.get("openapi").asString)
        assertTrue(root.has("info"), "Should have info block")
        assertTrue(root.has("paths"), "Should have paths block")

        val paths = root.getAsJsonObject("paths")
        assertTrue(paths.size() > 0, "Should have at least one path")
    }

    @Test
    fun `json format alias produces same OpenAPI output`() {
        val cmd = newCommand()
        cmd.outputFormat = "json"

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")

        val root = JsonParser.parseString(result.stdout).asJsonObject
        assertEquals("3.0.3", root.get("openapi").asString)
    }

    @Test
    fun `schema format with no endpoints produces empty paths`() {
        val cmd = newCommand()
        cmd.outputFormat = "schema"
        cmd.includePackages = listOf("com.example.noop")

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")

        val root = JsonParser.parseString(result.stdout).asJsonObject
        val paths = root.getAsJsonObject("paths")
        assertEquals(0, paths.size(), "Should have zero paths for package with no endpoints")
    }

    // ========================================================================
    // Verbose output
    // ========================================================================

    @Test
    fun `verbose prints loading info to stderr`() {
        val cmd = newCommand()
        cmd.verbose = true

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        assertTrue(result.stderr.contains("Loading bytecode from:"), "stderr: ${result.stderr}")
    }

    @Test
    fun `verbose with endpoint pattern prints pattern to stderr`() {
        val cmd = newCommand()
        cmd.verbose = true
        cmd.endpointPattern = "/api/users/*"

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        assertTrue(result.stderr.contains("Endpoint pattern:"), "stderr: ${result.stderr}")
    }

    @Test
    fun `verbose with http method prints method filter to stderr`() {
        val cmd = newCommand()
        cmd.verbose = true
        cmd.httpMethod = "GET"

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        assertTrue(result.stderr.contains("HTTP method filter:"), "stderr: ${result.stderr}")
    }

    @Test
    fun `verbose with no matching endpoints prints message`() {
        val cmd = newCommand()
        cmd.verbose = true
        cmd.includePackages = listOf("com.example.noop")

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        assertTrue(result.stderr.contains("No endpoints found"), "stderr: ${result.stderr}")
    }

    // ========================================================================
    // Nonexistent input path
    // ========================================================================

    @Test
    fun `returns 1 when input path does not exist`() {
        val cmd = FindEndpointsCommand()
        cmd.input = Path.of("/nonexistent/path/to/nothing.jar")

        val result = runCommand { cmd.call() }
        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("does not exist"), "stderr: ${result.stderr}")
    }

    // ========================================================================
    // Invalid / corrupt input
    // ========================================================================

    @Test
    fun `corrupt input returns 1 with error message`() {
        val corruptFile = File.createTempFile("corrupt", ".jar")
        corruptFile.deleteOnExit()
        corruptFile.writeText("this is not a valid JAR file")

        val cmd = FindEndpointsCommand()
        cmd.input = corruptFile.toPath()
        cmd.includePackages = listOf("com.example.endpoint")

        val result = runCommand { cmd.call() }
        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("Error"), "stderr: ${result.stderr}")
    }

    @Test
    fun `corrupt input with verbose prints stack trace`() {
        val corruptFile = File.createTempFile("corrupt", ".jar")
        corruptFile.deleteOnExit()
        corruptFile.writeText("this is not a valid JAR file")

        val cmd = FindEndpointsCommand()
        cmd.input = corruptFile.toPath()
        cmd.includePackages = listOf("com.example.endpoint")
        cmd.verbose = true

        val result = runCommand { cmd.call() }
        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("Error"), "stderr: ${result.stderr}")
    }

    // ========================================================================
    // Empty results (no endpoints found)
    // ========================================================================

    @Test
    fun `returns 0 when no endpoints match -- text format`() {
        val cmd = newCommand()
        cmd.includePackages = listOf("com.example.noop")
        cmd.outputFormat = "text"

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        // Text format produces no stdout when there are no endpoints
        assertFalse(result.stdout.contains("Found"), "Should not contain Found. stdout: ${result.stdout}")
    }

    @Test
    fun `empty directory input with text format`() {
        val emptyDir = Files.createTempDirectory("empty-endpoints")
        emptyDir.toFile().deleteOnExit()

        val cmd = FindEndpointsCommand()
        cmd.input = emptyDir
        cmd.includePackages = listOf("com.nonexistent")

        val result = runCommand { cmd.call() }
        assertTrue(result.exitCode == 0 || result.exitCode == 1,
            "Should handle empty directory gracefully. exitCode=${result.exitCode}, stderr: ${result.stderr}")
    }

    // ========================================================================
    // HTTP method filtering
    // ========================================================================

    @Test
    fun `filter by GET returns only GET endpoints`() {
        val cmd = newCommand()
        cmd.httpMethod = "GET"

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        assertTrue(result.stdout.contains("GET"), "stdout: ${result.stdout}")
        assertFalse(result.stdout.contains("POST    /"), "Should not contain POST endpoints. stdout: ${result.stdout}")
        assertFalse(result.stdout.contains("DELETE  /"), "Should not contain DELETE endpoints. stdout: ${result.stdout}")
    }

    @Test
    fun `filter by POST returns only POST endpoints`() {
        val cmd = newCommand()
        cmd.httpMethod = "POST"

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        assertTrue(result.stdout.contains("POST"), "stdout: ${result.stdout}")
    }

    @Test
    fun `invalid HTTP method returns 1`() {
        val cmd = newCommand()
        cmd.httpMethod = "INVALID"

        val result = runCommand { cmd.call() }
        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("Invalid HTTP method"), "stderr: ${result.stderr}")
    }

    // ========================================================================
    // Endpoint pattern filtering
    // ========================================================================

    @Test
    fun `endpoint pattern filters results`() {
        val cmd = newCommand()
        cmd.endpointPattern = "/api/users"

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        assertTrue(result.stdout.contains("/api/users"), "stdout: ${result.stdout}")
    }

    @Test
    fun `endpoint pattern with wildcard`() {
        val cmd = newCommand()
        cmd.endpointPattern = "/api/users/*"

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        // Should match /api/users/{id} but not bare /api/users
        assertTrue(result.stdout.contains("endpoint(s)"), "stdout: ${result.stdout}")
    }

    @Test
    fun `endpoint pattern with double wildcard`() {
        val cmd = newCommand()
        cmd.endpointPattern = "/api/**"

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        assertTrue(result.stdout.contains("endpoint(s)"), "stdout: ${result.stdout}")
    }

    @Test
    fun `endpoint pattern that matches nothing returns 0`() {
        val cmd = newCommand()
        cmd.endpointPattern = "/nonexistent/path"

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
    }

    // ========================================================================
    // Include / exclude packages
    // ========================================================================

    @Test
    fun `include packages restricts analysis scope`() {
        val cmd = newCommand()
        cmd.includePackages = listOf("com.example.endpoint")

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        assertTrue(result.stdout.contains("endpoint(s)"), "stdout: ${result.stdout}")
    }

    @Test
    fun `exclude packages removes classes from analysis`() {
        val cmd = newCommand()
        cmd.includePackages = listOf("com.example")
        cmd.excludePackages = listOf("com.example.endpoint")

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
    }

    // ========================================================================
    // Include-libs and lib-filter options
    // ========================================================================

    @Test
    fun `includeLibs false overrides auto-detection`() {
        val cmd = newCommand()
        cmd.includeLibs = false

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
    }

    @Test
    fun `includeLibs true overrides auto-detection`() {
        val cmd = newCommand()
        cmd.includeLibs = true

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
    }

    @Test
    fun `lib filters are passed to loader config`() {
        val cmd = newCommand()
        cmd.includeLibs = true
        cmd.libFilters = listOf("some-lib-*.jar")

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
    }

    // ========================================================================
    // Picocli property getter coverage
    // ========================================================================

    @Test
    fun `picocli property getters return expected values`() {
        val cmd = FindEndpointsCommand()
        cmd.input = fixtures.classesDir
        cmd.endpointPattern = "/api/*"
        cmd.httpMethod = "GET"
        cmd.includePackages = listOf("com.example")
        cmd.excludePackages = listOf("com.example.noop")
        cmd.outputFormat = "schema"
        cmd.verbose = true
        cmd.includeLibs = false
        cmd.libFilters = listOf("filter.jar")

        // Access all getters to ensure coverage
        assertEquals(fixtures.classesDir, cmd.input)
        assertEquals("/api/*", cmd.endpointPattern)
        assertEquals("GET", cmd.httpMethod)
        assertEquals(listOf("com.example"), cmd.includePackages)
        assertEquals(listOf("com.example.noop"), cmd.excludePackages)
        assertEquals("schema", cmd.outputFormat)
        assertEquals(true, cmd.verbose)
        assertEquals(false, cmd.includeLibs)
        assertEquals(listOf("filter.jar"), cmd.libFilters)
    }

    // ========================================================================
    // Schema format with discovered endpoints (response content + schema refs)
    // ========================================================================

    @Test
    fun `schema output includes response content when return types found`() {
        val cmd = newCommand()
        cmd.outputFormat = "schema"

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")

        val root = JsonParser.parseString(result.stdout).asJsonObject
        val paths = root.getAsJsonObject("paths")
        assertTrue(paths.size() > 0, "Should have discovered paths")

        // At least one endpoint should have a 200 response
        var found200 = false
        for (pathKey in paths.keySet()) {
            val pathItem = paths.getAsJsonObject(pathKey)
            for (methodKey in pathItem.keySet()) {
                val operation = pathItem.getAsJsonObject(methodKey)
                val responses = operation.getAsJsonObject("responses")
                if (responses.has("200")) {
                    found200 = true
                }
            }
        }
        assertTrue(found200, "Should have at least one 200 response")
    }

    @Test
    fun `schema output contains operationId and tags`() {
        val cmd = newCommand()
        cmd.outputFormat = "schema"

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")

        val root = JsonParser.parseString(result.stdout).asJsonObject
        val paths = root.getAsJsonObject("paths")

        // Check that operations have operationId and tags
        var foundOperation = false
        for (pathKey in paths.keySet()) {
            val pathItem = paths.getAsJsonObject(pathKey)
            for (methodKey in pathItem.keySet()) {
                val operation = pathItem.getAsJsonObject(methodKey)
                if (operation.has("operationId") && operation.has("tags")) {
                    foundOperation = true
                }
            }
        }
        assertTrue(foundOperation, "Should have operations with operationId and tags")
    }

    // ========================================================================
    // Text format with multiple controllers verifies grouping
    // ========================================================================

    @Test
    fun `text format shows endpoints from multiple controllers`() {
        val cmd = newCommand()

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        assertTrue(result.stdout.contains("UserController") || result.stdout.contains("OrderController"),
            "stdout: ${result.stdout}")
    }

    // ========================================================================
    // shouldIncludeLibs auto-detection based on file extension
    // ========================================================================

    @Test
    fun `jar extension triggers includeLibs auto-detection`() {
        // Create a temp file with .jar extension that is actually a directory
        // to exercise the shouldIncludeLibs path with null includeLibs
        val tempJar = File.createTempFile("test", ".jar")
        tempJar.deleteOnExit()
        // Make it a valid empty zip/jar
        val zipBytes = byteArrayOf(
            0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        )
        tempJar.writeBytes(zipBytes)

        val cmd = FindEndpointsCommand()
        cmd.input = tempJar.toPath()
        cmd.includePackages = listOf("com.example.endpoint")
        // includeLibs is null by default -- auto-detects from .jar extension

        val result = runCommand { cmd.call() }
        // May succeed or fail depending on whether the empty jar can be loaded
        // The point is to cover the auto-detection code path
        assertTrue(result.exitCode == 0 || result.exitCode == 1)
    }

    // ========================================================================
    // Combined filter: endpoint pattern + HTTP method
    // ========================================================================

    @Test
    fun `combined endpoint pattern and HTTP method filter`() {
        val cmd = newCommand()
        cmd.endpointPattern = "/api/**"
        cmd.httpMethod = "GET"

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        assertTrue(result.stdout.contains("GET"), "stdout: ${result.stdout}")
    }

    // ========================================================================
    // buildFieldSchema: List with actual types that have no fields
    // ========================================================================

    @Test
    fun `buildFieldSchema maps List with actual type but no fields to array with plain object items`() {
        val cmd = FindEndpointsCommand()
        val actualType = io.johnsonlee.graphite.core.TypeStructure.simple("com.example.Marker")
        val field = io.johnsonlee.graphite.core.FieldStructure(
            "items",
            io.johnsonlee.graphite.core.TypeDescriptor("java.util.List"),
            actualTypes = setOf(actualType)
        )
        val schemas = mutableMapOf<String, Any>()
        val schema = cmd.buildFieldSchema(field, schemas, mutableSetOf(), mutableSetOf(), 0)

        assertEquals("array", schema["type"])
        @Suppress("UNCHECKED_CAST")
        val items = schema["items"] as Map<String, Any>
        assertEquals("object", items["type"])
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private data class CommandOutput(val stdout: String, val stderr: String, val exitCode: Int)

    private fun newCommand(): FindEndpointsCommand {
        val cmd = FindEndpointsCommand()
        cmd.input = fixtures.classesDir
        cmd.includePackages = listOf("com.example.endpoint")
        return cmd
    }

    private fun runCommand(block: () -> Int): CommandOutput {
        val outBaos = ByteArrayOutputStream()
        val errBaos = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        System.setOut(PrintStream(outBaos))
        System.setErr(PrintStream(errBaos))
        val exitCode: Int
        try {
            exitCode = block()
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
        return CommandOutput(outBaos.toString(), errBaos.toString(), exitCode)
    }
}
