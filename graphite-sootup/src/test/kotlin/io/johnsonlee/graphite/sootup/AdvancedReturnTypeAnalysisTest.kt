package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.Graphite
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Advanced test cases for return type analysis:
 * 1. Generic return types (List<T>, Map<K,V>, Optional<T>)
 * 2. Multi-level nested method calls (2, 3, 4+ levels)
 * 3. Lombok-generated code (@Data, @Builder, @Value, static of())
 */
class AdvancedReturnTypeAnalysisTest {

    // ========== Generic Return Types ==========

    @Test
    fun `should find actual types for generic List returns`() {
        val graph = loadGraph("sample.generics")
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findActualReturnTypes {
                method {
                    declaringClass = "sample.generics.GenericReturnService"
                    name = "getUsers"
                }
            }
        }

        println("Generic List results:")
        results.forEach { result ->
            println("  ${result.method.name}(): declared=${result.declaredType.className}, actual=${result.actualTypes.map { it.className }}")
        }

        assertTrue(results.isNotEmpty(), "Should find getUsers method")

        // The ArrayList type should be detected
        val actualTypes = results.first().actualTypes.map { it.className }
        assertTrue(
            actualTypes.any { it.contains("ArrayList") || it.contains("List") },
            "Should find ArrayList/List type. Found: $actualTypes"
        )
    }

    @Test
    fun `should find actual types for generic Map returns`() {
        val graph = loadGraph("sample.generics")
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findActualReturnTypes {
                method {
                    declaringClass = "sample.generics.GenericReturnService"
                    name = "getOrderMap"
                }
            }
        }

        println("Generic Map results:")
        results.forEach { result ->
            println("  ${result.method.name}(): declared=${result.declaredType.className}, actual=${result.actualTypes.map { it.className }}")
        }

        assertTrue(results.isNotEmpty(), "Should find getOrderMap method")

        val actualTypes = results.first().actualTypes.map { it.className }
        assertTrue(
            actualTypes.any { it.contains("HashMap") || it.contains("Map") },
            "Should find HashMap/Map type. Found: $actualTypes"
        )
    }

    @Test
    fun `should find actual types for Optional returns`() {
        val graph = loadGraph("sample.generics")
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findActualReturnTypes {
                method {
                    declaringClass = "sample.generics.GenericReturnService"
                    name = "findUserById"
                }
            }
        }

        println("Optional results:")
        results.forEach { result ->
            println("  ${result.method.name}(): declared=${result.declaredType.className}, actual=${result.actualTypes.map { it.className }}")
        }

        assertTrue(results.isNotEmpty(), "Should find findUserById method")

        val actualTypes = results.first().actualTypes.map { it.className }
        assertTrue(
            actualTypes.any { it.contains("Optional") },
            "Should find Optional type. Found: $actualTypes"
        )
    }

    // ========== Multi-level Nested Method Calls ==========

    @Test
    fun `should trace 2-level nested method calls`() {
        val graph = loadGraph("sample.nested")
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findActualReturnTypes {
                method {
                    declaringClass = "sample.nested.NestedCallService"
                    name = "getUser"
                }
            }
        }

        println("2-level nested results:")
        results.forEach { result ->
            println("  ${result.method.name}(): declared=${result.declaredType.className}, actual=${result.actualTypes.map { it.className }}")
        }

        assertTrue(results.isNotEmpty(), "Should find getUser method")
        assertEquals("java.lang.Object", results.first().declaredType.className)

        val actualTypes = results.first().actualTypes.map { it.className }
        assertTrue(
            actualTypes.contains("sample.nested.NestedCallService\$User"),
            "Should find User type through 2-level call. Found: $actualTypes"
        )
    }

    @Test
    fun `should trace 3-level nested method calls`() {
        val graph = loadGraph("sample.nested")
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findActualReturnTypes {
                method {
                    declaringClass = "sample.nested.NestedCallService"
                    name = "getUserWrapped"
                }
            }
        }

        println("3-level nested results:")
        results.forEach { result ->
            println("  ${result.method.name}(): declared=${result.declaredType.className}, actual=${result.actualTypes.map { it.className }}")
        }

        assertTrue(results.isNotEmpty(), "Should find getUserWrapped method")
        assertEquals("java.lang.Object", results.first().declaredType.className)

        val actualTypes = results.first().actualTypes.map { it.className }
        assertTrue(
            actualTypes.contains("sample.nested.NestedCallService\$User"),
            "Should find User type through 3-level call chain. Found: $actualTypes"
        )
    }

    @Test
    fun `should trace 4-level nested method calls`() {
        val graph = loadGraph("sample.nested")
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findActualReturnTypes {
                method {
                    declaringClass = "sample.nested.NestedCallService"
                    name = "getDeepUser"
                }
            }
        }

        println("4-level nested results:")
        results.forEach { result ->
            println("  ${result.method.name}(): declared=${result.declaredType.className}, actual=${result.actualTypes.map { it.className }}")
        }

        assertTrue(results.isNotEmpty(), "Should find getDeepUser method")
        assertEquals("java.lang.Object", results.first().declaredType.className)

        val actualTypes = results.first().actualTypes.map { it.className }
        assertTrue(
            actualTypes.contains("sample.nested.NestedCallService\$User"),
            "Should find User type through 4-level call chain. Found: $actualTypes"
        )
    }

    @Test
    fun `should trace cross-class method calls`() {
        val graph = loadGraph("sample.nested")
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findActualReturnTypes {
                method {
                    declaringClass = "sample.nested.NestedCallService"
                    name = "getRepoUser"
                }
            }
        }

        println("Cross-class call results:")
        results.forEach { result ->
            println("  ${result.method.name}(): declared=${result.declaredType.className}, actual=${result.actualTypes.map { it.className }}")
        }

        assertTrue(results.isNotEmpty(), "Should find getRepoUser method")

        val actualTypes = results.first().actualTypes.map { it.className }
        assertTrue(
            actualTypes.contains("sample.nested.NestedCallService\$User"),
            "Should find User type through cross-class call. Found: $actualTypes"
        )
    }

    @Test
    fun `should trace multi-level cross-class method calls`() {
        val graph = loadGraph("sample.nested")
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findActualReturnTypes {
                method {
                    declaringClass = "sample.nested.NestedCallService"
                    name = "getRepoUserWrapped"
                }
            }
        }

        println("Multi-level cross-class results:")
        results.forEach { result ->
            println("  ${result.method.name}(): declared=${result.declaredType.className}, actual=${result.actualTypes.map { it.className }}")
        }

        assertTrue(results.isNotEmpty(), "Should find getRepoUserWrapped method")

        val actualTypes = results.first().actualTypes.map { it.className }
        assertTrue(
            actualTypes.contains("sample.nested.NestedCallService\$User"),
            "Should find User type through multi-level cross-class call. Found: $actualTypes"
        )
    }

    // ========== Lombok Generated Code ==========

    @Test
    fun `should find actual types for Lombok @Data class`() {
        val graph = loadGraph("sample.lombok")
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findActualReturnTypes {
                method {
                    declaringClass = "sample.lombok.LombokService"
                    name = "createUserData"
                }
            }
        }

        println("Lombok @Data results:")
        results.forEach { result ->
            println("  ${result.method.name}(): declared=${result.declaredType.className}, actual=${result.actualTypes.map { it.className }}")
        }

        assertTrue(results.isNotEmpty(), "Should find createUserData method")

        val actualTypes = results.first().actualTypes.map { it.className }
        assertTrue(
            actualTypes.contains("sample.lombok.LombokService\$UserData"),
            "Should find UserData type. Found: $actualTypes"
        )
    }

    @Test
    fun `should find actual types for Lombok @Builder`() {
        val graph = loadGraph("sample.lombok")
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findActualReturnTypes {
                method {
                    declaringClass = "sample.lombok.LombokService"
                    name = "createUserWithBuilder"
                }
            }
        }

        println("Lombok @Builder results:")
        results.forEach { result ->
            println("  ${result.method.name}(): declared=${result.declaredType.className}, actual=${result.actualTypes.map { it.className }}")
        }

        assertTrue(results.isNotEmpty(), "Should find createUserWithBuilder method")

        val actualTypes = results.first().actualTypes.map { it.className }
        assertTrue(
            actualTypes.contains("sample.lombok.LombokService\$UserWithBuilder"),
            "Should find UserWithBuilder type from builder.build(). Found: $actualTypes"
        )
    }

    @Test
    fun `should find actual types for Lombok @Builder via nested call`() {
        val graph = loadGraph("sample.lombok")
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findActualReturnTypes {
                method {
                    declaringClass = "sample.lombok.LombokService"
                    name = "createNestedBuilderUser"
                }
            }
        }

        println("Lombok @Builder nested results:")
        results.forEach { result ->
            println("  ${result.method.name}(): declared=${result.declaredType.className}, actual=${result.actualTypes.map { it.className }}")
        }

        assertTrue(results.isNotEmpty(), "Should find createNestedBuilderUser method")

        val actualTypes = results.first().actualTypes.map { it.className }
        assertTrue(
            actualTypes.contains("sample.lombok.LombokService\$UserWithBuilder"),
            "Should find UserWithBuilder type through nested builder call. Found: $actualTypes"
        )
    }

    @Test
    fun `should find actual types for Lombok @Value with static of()`() {
        val graph = loadGraph("sample.lombok")
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findActualReturnTypes {
                method {
                    declaringClass = "sample.lombok.LombokService"
                    name = "createImmutableUser"
                }
            }
        }

        println("Lombok @Value with of() results:")
        results.forEach { result ->
            println("  ${result.method.name}(): declared=${result.declaredType.className}, actual=${result.actualTypes.map { it.className }}")
        }

        assertTrue(results.isNotEmpty(), "Should find createImmutableUser method")

        val actualTypes = results.first().actualTypes.map { it.className }
        assertTrue(
            actualTypes.contains("sample.lombok.LombokService\$ImmutableUser"),
            "Should find ImmutableUser type from static of(). Found: $actualTypes"
        )
    }

    @Test
    fun `should find actual types for Lombok @With`() {
        val graph = loadGraph("sample.lombok")
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findActualReturnTypes {
                method {
                    declaringClass = "sample.lombok.LombokService"
                    name = "updateUserName"
                }
            }
        }

        println("Lombok @With results:")
        results.forEach { result ->
            println("  ${result.method.name}(): declared=${result.declaredType.className}, actual=${result.actualTypes.map { it.className }}")
        }

        assertTrue(results.isNotEmpty(), "Should find updateUserName method")

        val actualTypes = results.first().actualTypes.map { it.className }
        assertTrue(
            actualTypes.contains("sample.lombok.LombokService\$ImmutableUser"),
            "Should find ImmutableUser type from withName(). Found: $actualTypes"
        )
    }

    // ========== Summary Test ==========

    @Test
    fun `summary - all nested call patterns should work`() {
        val graph = loadGraph("sample.nested")
        val graphite = Graphite.from(graph)

        val methodsToTest = listOf(
            "getUser" to 2,           // 2-level
            "getUserWrapped" to 3,    // 3-level
            "getDeepUser" to 4,       // 4-level
            "getRepoUser" to 2,       // cross-class
            "getRepoUserWrapped" to 3, // multi-level cross-class
            "buildUser" to 3          // chain pattern
        )

        println("\n=== Summary: Multi-level Nested Call Analysis ===")
        println("%-25s | %-6s | %-40s | %s".format("Method", "Levels", "Actual Types", "Status"))
        println("-".repeat(100))

        var passCount = 0
        methodsToTest.forEach { (methodName, levels) ->
            val results = graphite.query {
                findActualReturnTypes {
                    method {
                        declaringClass = "sample.nested.NestedCallService"
                        name = methodName
                    }
                }
            }

            val actualTypes = results.firstOrNull()?.actualTypes?.map { it.className } ?: emptyList()
            val hasUserType = actualTypes.any { it.contains("User") }
            val status = if (hasUserType) "PASS" else "FAIL"
            if (hasUserType) passCount++

            println("%-25s | %-6d | %-40s | %s".format(
                methodName,
                levels,
                actualTypes.joinToString().take(40),
                status
            ))
        }

        println("-".repeat(100))
        println("Total: $passCount/${methodsToTest.size} passed")

        assertEquals(methodsToTest.size, passCount, "All nested call patterns should find User type")
    }

    // ========== Helper ==========

    private fun loadGraph(packagePrefix: String): io.johnsonlee.graphite.graph.Graph {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf(packagePrefix),
            buildCallGraph = false
        ))

        return loader.load(testClassesDir)
    }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }
}
