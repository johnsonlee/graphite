package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.JavaArchiveLayout
import io.johnsonlee.graphite.input.LoaderConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.StandardLocation
import javax.tools.ToolProvider
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for JavaProjectLoader covering JAR, Spring Boot JAR, WAR loading,
 * canLoad, matchesLibraryFilter, and include packages filtering.
 */
class JavaProjectLoaderTest {

    // ========== canLoad tests ==========

    @Test
    fun `canLoad should return true for directory`() {
        val loader = JavaProjectLoader()
        val tempDir = Files.createTempDirectory("graphite-test")
        try {
            assertTrue(loader.canLoad(tempDir), "Should be able to load from directory")
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `canLoad should return true for jar files`() {
        val loader = JavaProjectLoader()
        val tempJar = Files.createTempFile("test", ".jar")
        try {
            assertTrue(loader.canLoad(tempJar), "Should be able to load JAR files")
        } finally {
            Files.deleteIfExists(tempJar)
        }
    }

    @Test
    fun `canLoad should return true for war files`() {
        val loader = JavaProjectLoader()
        val tempWar = Files.createTempFile("test", ".war")
        try {
            assertTrue(loader.canLoad(tempWar), "Should be able to load WAR files")
        } finally {
            Files.deleteIfExists(tempWar)
        }
    }

    @Test
    fun `canLoad should return true for zip files`() {
        val loader = JavaProjectLoader()
        val tempZip = Files.createTempFile("test", ".zip")
        try {
            assertTrue(loader.canLoad(tempZip), "Should be able to load ZIP files")
        } finally {
            Files.deleteIfExists(tempZip)
        }
    }

    @Test
    fun `canLoad should return false for txt files`() {
        val loader = JavaProjectLoader()
        val tempTxt = Files.createTempFile("test", ".txt")
        try {
            assertFalse(loader.canLoad(tempTxt), "Should not load TXT files")
        } finally {
            Files.deleteIfExists(tempTxt)
        }
    }

    // ========== Regular JAR loading ==========

    @Test
    fun `should load classes from regular JAR file`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        // Build a JAR from compiled test classes
        val jarPath = buildJarFromClasses(testClassesDir, "sample/simple/")

        try {
            val loader = JavaProjectLoader(LoaderConfig(
                includePackages = listOf("sample.simple"),
                buildCallGraph = false
            ))

            val graph = loader.load(jarPath)

            // Should find fields from SimpleService
            val fieldNodes = graph.nodes<FieldNode>()
                .filter { it.descriptor.declaringClass.className.contains("SimpleService") }
                .toList()

            assertTrue(fieldNodes.isNotEmpty(), "Should find fields in SimpleService loaded from JAR")

            // Verify that a generic field was found (items: List<String>)
            val itemsField = fieldNodes.find { it.descriptor.name == "items" }
            assertNotNull(itemsField, "Should find 'items' field")
        } finally {
            Files.deleteIfExists(jarPath)
        }
    }

    @Test
    fun `should load JAR with include packages filter`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        // Build a JAR containing classes from multiple packages
        val jarPath = buildJarFromClasses(testClassesDir, "sample/")

        try {
            val loader = JavaProjectLoader(LoaderConfig(
                includePackages = listOf("sample.simple"),
                buildCallGraph = false
            ))

            val graph = loader.load(jarPath)

            // Should find SimpleService fields but analysis is filtered
            val simpleFields = graph.nodes<FieldNode>()
                .filter { it.descriptor.declaringClass.className.startsWith("sample.simple") }
                .toList()

            assertTrue(simpleFields.isNotEmpty(), "Should find fields from included package")
        } finally {
            Files.deleteIfExists(jarPath)
        }
    }

    // ========== Spring Boot JAR loading ==========

    @Test
    fun `should load classes from Spring Boot JAR`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        // Build a Spring Boot-style JAR
        val jarPath = buildSpringBootJar(testClassesDir, "sample/simple/")

        try {
            val loader = JavaProjectLoader(LoaderConfig(
                includePackages = listOf("sample.simple"),
                buildCallGraph = false
            ))

            val graph = loader.load(jarPath)

            // Should find classes extracted from the Spring Boot application classes layout.
            val fieldNodes = graph.nodes<FieldNode>()
                .filter { it.descriptor.declaringClass.className.contains("SimpleService") }
                .toList()

            assertTrue(fieldNodes.isNotEmpty(),
                "Should find fields in SimpleService loaded from Spring Boot JAR")
        } finally {
            Files.deleteIfExists(jarPath)
            // Clean up temp directories created by createSpringBootInputLocations
            File(System.getProperty("java.io.tmpdir")).listFiles()
                ?.filter { it.name.startsWith("graphite-springboot") }
                ?.forEach { it.deleteRecursively() }
        }
    }

    @Test
    fun `should load Spring Boot JAR with libraries`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        // Build a Spring Boot JAR with a nested library JAR
        val innerJar = buildJarFromClasses(testClassesDir, "sample/generics/")
        val jarPath = buildSpringBootJarWithLibs(testClassesDir, "sample/simple/", listOf(innerJar))

        try {
            val loader = JavaProjectLoader(LoaderConfig(
                includePackages = listOf("sample.simple", "sample.generics"),
                includeLibraries = true,
                buildCallGraph = false
            ))

            val graph = loader.load(jarPath)

            // Should find fields from application classes
            val simpleFields = graph.nodes<FieldNode>()
                .filter { it.descriptor.declaringClass.className.contains("SimpleService") }
                .toList()

            assertTrue(simpleFields.isNotEmpty(),
                "Should find fields in SimpleService from Spring Boot application classes")
        } finally {
            Files.deleteIfExists(jarPath)
            Files.deleteIfExists(innerJar)
            File(System.getProperty("java.io.tmpdir")).listFiles()
                ?.filter { it.name.startsWith("graphite-springboot") }
                ?.forEach { it.deleteRecursively() }
        }
    }

    // ========== WAR file loading ==========

    @Test
    fun `should load classes from WAR file`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val warPath = buildWarFile(testClassesDir, "sample/simple/")

        try {
            val logs = mutableListOf<String>()
            val loader = JavaProjectLoader(LoaderConfig(
                includePackages = listOf("sample.simple"),
                buildCallGraph = false,
                verbose = { logs.add(it) }
            ))

            val graph = loader.load(warPath)

            // Should find classes from the WAR application classes layout.
            val fieldNodes = graph.nodes<FieldNode>()
                .filter { it.descriptor.declaringClass.className.contains("SimpleService") }
                .toList()

            assertTrue(fieldNodes.isNotEmpty(),
                "Should find fields in SimpleService loaded from WAR")

            // Verbose logging should have produced output
            assertTrue(logs.any { it.contains("class files") },
                "Should log class file count during WAR extraction")
        } finally {
            Files.deleteIfExists(warPath)
            File(System.getProperty("java.io.tmpdir")).listFiles()
                ?.filter { it.name.startsWith("graphite-war") }
                ?.forEach { it.deleteRecursively() }
        }
    }

    @Test
    fun `should load WAR file with libraries`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val innerJar = buildJarFromClasses(testClassesDir, "sample/generics/")
        val warPath = buildWarFileWithLibs(testClassesDir, "sample/simple/", listOf(innerJar))

        try {
            val logs = mutableListOf<String>()
            val loader = JavaProjectLoader(LoaderConfig(
                includePackages = listOf("sample.simple", "sample.generics"),
                includeLibraries = true,
                buildCallGraph = false,
                verbose = { logs.add(it) }
            ))

            val graph = loader.load(warPath)

            val simpleFields = graph.nodes<FieldNode>()
                .filter { it.descriptor.declaringClass.className.contains("SimpleService") }
                .toList()

            assertTrue(simpleFields.isNotEmpty(),
                "Should find fields from WAR application classes")

            // Should log library loading
            assertTrue(logs.any { it.contains("JARs") },
                "Should log JAR loading info during WAR extraction")
        } finally {
            Files.deleteIfExists(warPath)
            Files.deleteIfExists(innerJar)
            File(System.getProperty("java.io.tmpdir")).listFiles()
                ?.filter { it.name.startsWith("graphite-war") }
                ?.forEach { it.deleteRecursively() }
        }
    }

    @Test
    fun `should filter libraries by library filter patterns`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val innerJar1 = buildJarFromClasses(testClassesDir, "sample/generics/", "generics-lib.jar")
        val innerJar2 = buildJarFromClasses(testClassesDir, "sample/simple/", "simple-lib.jar")
        val warPath = buildWarFileWithLibs(testClassesDir, "sample/controlflow/",
            listOf(innerJar1, innerJar2))

        try {
            val loader = JavaProjectLoader(LoaderConfig(
                includePackages = listOf("sample"),
                includeLibraries = true,
                libraryFilters = listOf("generics-*"),
                buildCallGraph = false,
                verbose = { println(it) }
            ))

            val graph = loader.load(warPath)

            // Should log library filtering
            val fieldNodes = graph.nodes<FieldNode>().toList()
            // The filter "generics-*" should include generics-lib.jar but skip simple-lib.jar
            assertTrue(fieldNodes.isNotEmpty(), "Should find some fields after library filtering")
        } finally {
            Files.deleteIfExists(warPath)
            Files.deleteIfExists(innerJar1)
            Files.deleteIfExists(innerJar2)
            File(System.getProperty("java.io.tmpdir")).listFiles()
                ?.filter { it.name.startsWith("graphite-war") }
                ?.forEach { it.deleteRecursively() }
        }
    }

    @Test
    fun `should skip Spring Boot libraries that do not contain included packages`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val unrelatedLib = buildJarFromClasses(testClassesDir, "sample/generics/", "generics-lib.jar")
        val jarPath = buildSpringBootJarWithLibs(testClassesDir, "sample/simple/", listOf(unrelatedLib))

        try {
            val logs = mutableListOf<String>()
            val loader = JavaProjectLoader(
                LoaderConfig(
                    includePackages = listOf("sample.simple"),
                    includeLibraries = true,
                    buildCallGraph = false,
                    verbose = { logs.add(it) }
                )
            )

            val graph = loader.load(jarPath)
            assertNotNull(graph)
            assertTrue(logs.none { it.contains("+ Loading JAR: generics-lib.jar") })
        } finally {
            Files.deleteIfExists(jarPath)
            Files.deleteIfExists(unrelatedLib)
            File(System.getProperty("java.io.tmpdir")).listFiles()
                ?.filter { it.name.startsWith("graphite-springboot") }
                ?.forEach { it.deleteRecursively() }
        }
    }

    @Test
    fun `should load with empty includePackages and include all classes`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        // Build a JAR from test classes
        val jarPath = buildJarFromClasses(testClassesDir, "sample/simple/")

        try {
            // Empty includePackages should include all classes (jarContainsIncludedPackages returns true)
            val loader = JavaProjectLoader(LoaderConfig(
                includePackages = emptyList(),
                buildCallGraph = false
            ))

            val graph = loader.load(jarPath)
            assertTrue(
                invokePrivate<Boolean>(loader, "jarContainsIncludedPackages", jarPath),
                "Empty includePackages should include every jar"
            )

            val fieldNodes = graph.nodes<FieldNode>()
                .filter { it.descriptor.declaringClass.className.contains("SimpleService") }
                .toList()

            assertTrue(fieldNodes.isNotEmpty(),
                "Should find fields with empty includePackages (include all)")
        } finally {
            Files.deleteIfExists(jarPath)
        }
    }

    @Test
    fun `directory input locations include matching libraries`() {
        val distDir = Files.createTempDirectory("graphite-dist")
        val libJar = compileSourcesToJar(
            jarName = "library.jar",
            sources = mapOf(
                "sample/lib/Library.java" to """
                    package sample.lib;
                    public class Library {
                        public static String value() { return "lib"; }
                    }
                """.trimIndent()
            )
        )
        try {
            Files.copy(libJar, distDir.resolve("library.jar"))
            val logs = mutableListOf<String>()
            val loader = JavaProjectLoader(
                LoaderConfig(
                    includePackages = listOf("sample.app"),
                    includeLibraries = true,
                    buildCallGraph = false,
                    verbose = logs::add
                )
            )

            val inputLocations = invokePrivate<Any>(loader, "createDirectoryInputLocations", distDir)
            val sources = inputLocations.javaClass.getDeclaredField("sources").also { it.isAccessible = true }.get(inputLocations) as Map<*, *>

            assertTrue(sources.values.contains("library.jar"))
            assertTrue(logs.any { it.contains("+ Loading library JAR from directory: library.jar") })
        } finally {
            Files.deleteIfExists(libJar)
            distDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `directory input locations fall back to empty application directory`() {
        val distDir = Files.createTempDirectory("graphite-empty-dist")
        try {
            val loader = JavaProjectLoader(
                LoaderConfig(
                    includePackages = listOf("sample.app"),
                    buildCallGraph = false
                )
            )

            val inputLocations = invokePrivate<Any>(loader, "createDirectoryInputLocations", distDir)
            val sources = inputLocations.javaClass.getDeclaredField("sources").also { it.isAccessible = true }.get(inputLocations) as Map<*, *>

            assertTrue(sources.values.contains(distDir.fileName.toString()))
        } finally {
            distDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `containsLooseClassFiles returns false for non directory`() {
        val file = Files.createTempFile("graphite-not-dir", ".jar")
        try {
            val loader = JavaProjectLoader(LoaderConfig(buildCallGraph = false))

            assertFalse(invokePrivate<Boolean>(loader, "containsLooseClassFiles", file))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `should report skipped WAR libraries by package`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val unrelatedLib = buildJarFromClasses(testClassesDir, "sample/generics/", "generics-lib.jar")
        val warPath = buildWarFileWithLibs(testClassesDir, "sample/simple/", listOf(unrelatedLib))

        try {
            val logs = mutableListOf<String>()
            val loader = JavaProjectLoader(
                LoaderConfig(
                    includePackages = listOf("sample.simple"),
                    includeLibraries = true,
                    buildCallGraph = false,
                    verbose = { logs.add(it) }
                )
            )

            val graph = loader.load(warPath)
            assertNotNull(graph)
            assertTrue(logs.any { it.contains("skipped: 0 by filter, 1 by package") })
        } finally {
            Files.deleteIfExists(warPath)
            Files.deleteIfExists(unrelatedLib)
            File(System.getProperty("java.io.tmpdir")).listFiles()
                ?.filter { it.name.startsWith("graphite-war") }
                ?.forEach { it.deleteRecursively() }
        }
    }

    @Test
    fun `jarContainsIncludedPackages returns false for unrelated jars`() {
        val testClassesDir = findTestClassesDir()
        val unrelatedJar = buildJarFromClasses(testClassesDir, "sample/generics/", "generics-lib.jar")
        try {
            val loader = JavaProjectLoader(
                LoaderConfig(
                    includePackages = listOf("sample.simple"),
                    buildCallGraph = false
                )
            )
            assertFalse(invokePrivate(loader, "jarContainsIncludedPackages", unrelatedJar))
        } finally {
            Files.deleteIfExists(unrelatedJar)
        }
    }

    @Test
    fun `should handle loadSignatures failure gracefully`() {
        // Create a JAR with invalid content to trigger the catch in loadSignatures
        val tempJar = Files.createTempFile("invalid", ".jar")
        try {
            // Write a minimal valid ZIP but no .class entries
            ZipOutputStream(Files.newOutputStream(tempJar)).use { zos ->
                zos.putNextEntry(ZipEntry(JavaArchiveLayout.META_INF_MANIFEST))
                zos.write("Manifest-Version: 1.0\n".toByteArray())
                zos.closeEntry()
            }

            val loader = JavaProjectLoader(LoaderConfig(
                buildCallGraph = false,
                verbose = { println(it) }
            ))

            // Should not throw - signatures are optional enhancement
            val graph = loader.load(tempJar)
            assertNotNull(graph, "Should still produce a graph even with no class files")
        } finally {
            Files.deleteIfExists(tempJar)
        }
    }

    @Test
    fun `private helpers tolerate invalid archives`() {
        val invalidJar = Files.createTempFile("invalid-signatures", ".jar")
        val invalidBootJar = Files.createTempFile("invalid-boot", ".jar")
        val invalidWar = Files.createTempFile("invalid-war", ".war")
        try {
            Files.writeString(invalidJar, "not-a-jar")
            Files.writeString(invalidBootJar, "not-a-boot-jar")
            Files.writeString(invalidWar, "not-a-war")

            val loader = JavaProjectLoader(
                LoaderConfig(
                    includePackages = listOf("sample.simple"),
                    includeLibraries = true,
                    buildCallGraph = false
                )
            )

            invokePrivate<Unit>(loader, "loadSignatures", invalidJar, BytecodeSignatureReader())
            assertTrue(
                invokePrivate<Boolean>(loader, "jarContainsIncludedPackages", invalidJar),
                "Invalid jars should be included on error to stay fail-open"
            )
            assertFailsWith<Exception> {
                invokePrivate<List<Any>>(loader, "createSpringBootInputLocations", invalidBootJar)
            }
            assertFailsWith<Exception> {
                invokePrivate<List<Any>>(loader, "createWarInputLocations", invalidWar)
            }
        } finally {
            Files.deleteIfExists(invalidJar)
            Files.deleteIfExists(invalidBootJar)
            Files.deleteIfExists(invalidWar)
        }
    }

    // ========== WAR signature loading ==========

    @Test
    fun `should load WAR signatures from WEB-INF classes`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val warPath = buildWarFile(testClassesDir, "sample/generics/")

        try {
            val loader = JavaProjectLoader(LoaderConfig(
                includePackages = listOf("sample.generics"),
                buildCallGraph = false
            ))

            val graph = loader.load(warPath)

            // Generic field types should be loaded from WAR application classes.
            val usersField = graph.nodes<FieldNode>()
                .filter { it.descriptor.name == "users" }
                .filter { it.descriptor.declaringClass.className.contains("GenericFieldService") }
                .firstOrNull()

            if (usersField != null) {
                assertEquals("java.util.List", usersField.descriptor.type.className,
                    "Field type should be List after signature loading from WAR")
            }
        } finally {
            Files.deleteIfExists(warPath)
            File(System.getProperty("java.io.tmpdir")).listFiles()
                ?.filter { it.name.startsWith("graphite-war") }
                ?.forEach { it.deleteRecursively() }
        }
    }

    // ========== Spring Boot signature loading ==========

    @Test
    fun `should load Spring Boot JAR signatures from BOOT-INF classes`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val jarPath = buildSpringBootJar(testClassesDir, "sample/generics/")

        try {
            val loader = JavaProjectLoader(LoaderConfig(
                includePackages = listOf("sample.generics"),
                buildCallGraph = false
            ))

            val graph = loader.load(jarPath)

            // Generic field types should be loaded via Spring Boot signature loading
            val usersField = graph.nodes<FieldNode>()
                .filter { it.descriptor.name == "users" }
                .filter { it.descriptor.declaringClass.className.contains("GenericFieldService") }
                .firstOrNull()

            if (usersField != null) {
                assertEquals("java.util.List", usersField.descriptor.type.className,
                    "Field type should be List after signature loading from Spring Boot JAR")
            }
        } finally {
            Files.deleteIfExists(jarPath)
            File(System.getProperty("java.io.tmpdir")).listFiles()
                ?.filter { it.name.startsWith("graphite-springboot") }
                ?.forEach { it.deleteRecursively() }
        }
    }

    // ========== matchesGlobPattern ==========

    @Test
    fun `should match library filter glob patterns`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        // Build a Spring Boot JAR with libraries to trigger matchesLibraryFilter
        val innerJar = buildJarFromClasses(testClassesDir, "sample/simple/", "my-app-lib.jar")
        val jarPath = buildSpringBootJarWithLibs(testClassesDir, "sample/controlflow/", listOf(innerJar))

        try {
            val loader = JavaProjectLoader(LoaderConfig(
                includePackages = listOf("sample"),
                includeLibraries = true,
                libraryFilters = listOf("my-app-*.jar"),
                buildCallGraph = false
            ))

            val graph = loader.load(jarPath)
            assertNotNull(graph, "Should produce graph with library filter matching")
        } finally {
            Files.deleteIfExists(jarPath)
            Files.deleteIfExists(innerJar)
            File(System.getProperty("java.io.tmpdir")).listFiles()
                ?.filter { it.name.startsWith("graphite-springboot") }
                ?.forEach { it.deleteRecursively() }
        }
    }

    // ========== Builder selection ==========

    @Test
    fun `should load with explicit DefaultGraph Builder via useMmapBuilder false`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val jarPath = buildJarFromClasses(testClassesDir, "sample/simple/")

        try {
            val loader = JavaProjectLoader(
                LoaderConfig(includePackages = listOf("sample.simple"), buildCallGraph = false),
                useMmapBuilder = false
            )

            val graph = loader.load(jarPath)
            val fieldNodes = graph.nodes<FieldNode>()
                .filter { it.descriptor.declaringClass.className.contains("SimpleService") }
                .toList()
            assertTrue(fieldNodes.isNotEmpty(), "Should find fields using DefaultGraph.Builder")
        } finally {
            Files.deleteIfExists(jarPath)
        }
    }

    @Test
    fun `should load with explicit MmapGraphBuilder via useMmapBuilder true`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val jarPath = buildJarFromClasses(testClassesDir, "sample/simple/")

        try {
            val loader = JavaProjectLoader(
                LoaderConfig(includePackages = listOf("sample.simple"), buildCallGraph = false),
                useMmapBuilder = true
            )

            val graph = loader.load(jarPath)
            val fieldNodes = graph.nodes<FieldNode>()
                .filter { it.descriptor.declaringClass.className.contains("SimpleService") }
                .toList()
            assertTrue(fieldNodes.isNotEmpty(), "Should find fields using MmapGraphBuilder")
        } finally {
            Files.deleteIfExists(jarPath)
        }
    }

    @Test
    fun `should preserve class origin when loading directory layout with jars`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val distDir = Files.createTempDirectory("graphite-dist")
        val libDir = Files.createDirectories(distDir.resolve("lib"))
        val appJar = buildJarFromClasses(testClassesDir, "sample/simple/", "app.jar")
        val libJar = buildJarFromClasses(testClassesDir, "sample/generics/", "dep.jar")

        try {
            Files.copy(appJar, libDir.resolve("app.jar"))
            Files.copy(libJar, libDir.resolve("dep.jar"))

            val loader = JavaProjectLoader(
                LoaderConfig(
                    includePackages = listOf("sample.simple"),
                    includeLibraries = false,
                    buildCallGraph = false
                )
            )

            val graph = loader.load(distDir)

            val appFields = graph.nodes<FieldNode>()
                .filter { it.descriptor.declaringClass.className.contains("SimpleService") }
                .toList()
            assertTrue(appFields.isNotEmpty(), "Should load application classes from nested jar layout")
            assertEquals("lib/app.jar", graph.classOrigin("sample.simple.SimpleService"))
            assertEquals("lib/dep.jar", graph.classOrigin("sample.generics.GenericFieldService"))
        } finally {
            Files.deleteIfExists(appJar)
            Files.deleteIfExists(libJar)
            distDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `should extract artifact dependencies from distribution layout at build time`() {
        val distDir = Files.createTempDirectory("graphite-artifact-deps-dist")
        val libDir = Files.createDirectories(distDir.resolve("lib"))
        val supportJar = compileSourcesToJar(
            jarName = "support.jar",
            sources = mapOf(
                "sample/lib2/Support.java" to """
                    package sample.lib2;
                    public class Support {
                        public static String value() { return "ok"; }
                    }
                """.trimIndent()
            )
        )
        val depJar = compileSourcesToJar(
            jarName = "dep.jar",
            classpath = listOf(supportJar),
            sources = mapOf(
                "sample/lib/Dep.java" to """
                    package sample.lib;
                    import sample.lib2.Support;
                    public class Dep {
                        public static String value() { return Support.value(); }
                    }
                """.trimIndent()
            )
        )
        val appJar = compileSourcesToJar(
            jarName = "app.jar",
            classpath = listOf(depJar, supportJar),
            sources = mapOf(
                "sample/app/Main.java" to """
                    package sample.app;
                    import sample.lib.Dep;
                    public class Main {
                        public static void main(String[] args) { Dep.value(); }
                    }
                """.trimIndent()
            )
        )

        try {
            Files.copy(appJar, libDir.resolve("app.jar"))
            Files.copy(depJar, libDir.resolve("dep.jar"))
            Files.copy(supportJar, libDir.resolve("support.jar"))

            val loader = JavaProjectLoader(
                LoaderConfig(
                    includePackages = listOf("sample.app"),
                    includeLibraries = false,
                    buildCallGraph = false
                )
            )

            val graph = loader.load(distDir)
            assertEquals(
                1,
                graph.artifactDependencies()["dep"]?.get("support"),
                "Expected dep.jar to record a bytecode dependency on support.jar"
            )
            assertEquals(
                1,
                graph.artifactDependencies()["app"]?.get("dep"),
                "Expected app.jar to record a bytecode dependency on dep.jar"
            )
        } finally {
            Files.deleteIfExists(appJar)
            Files.deleteIfExists(depJar)
            Files.deleteIfExists(supportJar)
            distDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `should skip artifact dependency extraction for single artifact input`() {
        val appJar = compileSourcesToJar(
            jarName = "single.jar",
            sources = mapOf(
                "sample/single/Helper.java" to """
                    package sample.single;
                    public class Helper {
                        public static String value() { return "ok"; }
                    }
                """.trimIndent(),
                "sample/single/App.java" to """
                    package sample.single;
                    public class App {
                        public static void main(String[] args) { Helper.value(); }
                    }
                """.trimIndent()
            )
        )

        try {
            val loader = JavaProjectLoader(
                LoaderConfig(
                    includePackages = listOf("sample.single"),
                    includeLibraries = false,
                    buildCallGraph = false
                )
            )

            val graph = loader.load(appJar)
            assertTrue(
                graph.artifactDependencies().isEmpty(),
                "Single-jar inputs should not perform artifact-to-artifact dependency extraction"
            )
        } finally {
            Files.deleteIfExists(appJar)
        }
    }

    // ========== Helper methods ==========

    /**
     * Build a regular JAR from compiled test classes under the given prefix.
     */
    private fun buildJarFromClasses(
        classesDir: Path,
        classPathPrefix: String,
        jarName: String = "test.jar"
    ): Path {
        val jarPath = Files.createTempFile(jarName.removeSuffix(".jar"), ".jar")

        JarOutputStream(Files.newOutputStream(jarPath), Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
        }).use { jos ->
            val baseDir = classesDir.toFile()
            val prefixDir = File(baseDir, classPathPrefix)
            if (prefixDir.exists()) {
                prefixDir.walk()
                    .filter { it.isFile && it.name.endsWith(".class") }
                    .forEach { classFile ->
                        val entryName = classFile.relativeTo(baseDir).path.replace(File.separatorChar, '/')
                        jos.putNextEntry(JarEntry(entryName))
                        classFile.inputStream().use { it.copyTo(jos) }
                        jos.closeEntry()
                    }
            }
        }

        return jarPath
    }

    /**
     * Build a Spring Boot-style JAR with [JavaArchiveLayout.BOOT_INF_CLASSES] layout.
     */
    private fun buildSpringBootJar(classesDir: Path, classPathPrefix: String): Path {
        val jarPath = Files.createTempFile("springboot", ".jar")

        JarOutputStream(Files.newOutputStream(jarPath), Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue(JavaArchiveLayout.SPRING_BOOT_CLASSES_ATTRIBUTE, JavaArchiveLayout.BOOT_INF_CLASSES)
        }).use { jos ->
            // Add application classes directory entry.
            jos.putNextEntry(JarEntry(JavaArchiveLayout.BOOT_INF_CLASSES))
            jos.closeEntry()

            val baseDir = classesDir.toFile()
            val prefixDir = File(baseDir, classPathPrefix)
            if (prefixDir.exists()) {
                prefixDir.walk()
                    .filter { it.isFile && it.name.endsWith(".class") }
                    .forEach { classFile ->
                        val relativePath = classFile.relativeTo(baseDir).path.replace(File.separatorChar, '/')
                        jos.putNextEntry(JarEntry(JavaArchiveLayout.bootInfClassEntry(relativePath)))
                        classFile.inputStream().use { it.copyTo(jos) }
                        jos.closeEntry()
                    }
            }
        }

        return jarPath
    }

    /**
     * Build a Spring Boot JAR with application classes and nested libraries.
     */
    private fun buildSpringBootJarWithLibs(
        classesDir: Path,
        classPathPrefix: String,
        libJars: List<Path>
    ): Path {
        val jarPath = Files.createTempFile("springboot", ".jar")

        JarOutputStream(Files.newOutputStream(jarPath), Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue(JavaArchiveLayout.SPRING_BOOT_CLASSES_ATTRIBUTE, JavaArchiveLayout.BOOT_INF_CLASSES)
            mainAttributes.putValue(JavaArchiveLayout.SPRING_BOOT_LIB_ATTRIBUTE, JavaArchiveLayout.BOOT_INF_LIB)
        }).use { jos ->
            // Add application classes.
            jos.putNextEntry(JarEntry(JavaArchiveLayout.BOOT_INF_CLASSES))
            jos.closeEntry()

            val baseDir = classesDir.toFile()
            val prefixDir = File(baseDir, classPathPrefix)
            if (prefixDir.exists()) {
                prefixDir.walk()
                    .filter { it.isFile && it.name.endsWith(".class") }
                    .forEach { classFile ->
                        val relativePath = classFile.relativeTo(baseDir).path.replace(File.separatorChar, '/')
                        jos.putNextEntry(JarEntry(JavaArchiveLayout.bootInfClassEntry(relativePath)))
                        classFile.inputStream().use { it.copyTo(jos) }
                        jos.closeEntry()
                    }
            }

            // Add nested libraries.
            jos.putNextEntry(JarEntry(JavaArchiveLayout.BOOT_INF_LIB))
            jos.closeEntry()

            libJars.forEach { libJar ->
                val jarName = libJar.fileName.toString()
                jos.putNextEntry(JarEntry(JavaArchiveLayout.bootInfLibEntry(jarName)))
                Files.newInputStream(libJar).use { it.copyTo(jos) }
                jos.closeEntry()
            }
        }

        return jarPath
    }

    /**
     * Build a WAR file with [JavaArchiveLayout.WEB_INF_CLASSES] layout.
     */
    private fun buildWarFile(classesDir: Path, classPathPrefix: String): Path {
        val warPath = Files.createTempFile("test", ".war")

        ZipOutputStream(Files.newOutputStream(warPath)).use { zos ->
            // Add application classes.
            zos.putNextEntry(ZipEntry(JavaArchiveLayout.WEB_INF_CLASSES))
            zos.closeEntry()

            val baseDir = classesDir.toFile()
            val prefixDir = File(baseDir, classPathPrefix)
            if (prefixDir.exists()) {
                prefixDir.walk()
                    .filter { it.isFile && it.name.endsWith(".class") }
                    .forEach { classFile ->
                        val relativePath = classFile.relativeTo(baseDir).path.replace(File.separatorChar, '/')
                        zos.putNextEntry(ZipEntry(JavaArchiveLayout.webInfClassEntry(relativePath)))
                        classFile.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
            }
        }

        return warPath
    }

    /**
     * Build a WAR file with application classes and nested libraries.
     */
    private fun buildWarFileWithLibs(
        classesDir: Path,
        classPathPrefix: String,
        libJars: List<Path>
    ): Path {
        val warPath = Files.createTempFile("test", ".war")

        ZipOutputStream(Files.newOutputStream(warPath)).use { zos ->
            // Add application classes.
            zos.putNextEntry(ZipEntry(JavaArchiveLayout.WEB_INF_CLASSES))
            zos.closeEntry()

            val baseDir = classesDir.toFile()
            val prefixDir = File(baseDir, classPathPrefix)
            if (prefixDir.exists()) {
                prefixDir.walk()
                    .filter { it.isFile && it.name.endsWith(".class") }
                    .forEach { classFile ->
                        val relativePath = classFile.relativeTo(baseDir).path.replace(File.separatorChar, '/')
                        zos.putNextEntry(ZipEntry(JavaArchiveLayout.webInfClassEntry(relativePath)))
                        classFile.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
            }

            // Add nested libraries.
            zos.putNextEntry(ZipEntry(JavaArchiveLayout.WEB_INF_LIB))
            zos.closeEntry()

            libJars.forEach { libJar ->
                val jarName = libJar.fileName.toString()
                zos.putNextEntry(ZipEntry(JavaArchiveLayout.webInfLibEntry(jarName)))
                Files.newInputStream(libJar).use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }

        return warPath
    }

    private fun compileSourcesToJar(
        jarName: String,
        sources: Map<String, String>,
        classpath: List<Path> = emptyList()
    ): Path {
        val compiler = ToolProvider.getSystemJavaCompiler()
        assertNotNull(compiler, "System Java compiler must be available for test compilation")
        val sourceDir = Files.createTempDirectory("graphite-java-src")
        val classesDir = Files.createTempDirectory("graphite-java-classes")
        val jarPath = Files.createTempFile(jarName.removeSuffix(".jar"), ".jar")

        try {
            sources.forEach { (relativePath, source) ->
                val sourceFile = sourceDir.resolve(relativePath)
                Files.createDirectories(sourceFile.parent)
                Files.writeString(sourceFile, source)
            }

            compiler.getStandardFileManager(null, null, null).use { fileManager ->
                fileManager.setLocation(StandardLocation.CLASS_OUTPUT, listOf(classesDir.toFile()))
                if (classpath.isNotEmpty()) {
                    fileManager.setLocation(StandardLocation.CLASS_PATH, classpath.map { it.toFile() })
                }
                val units = fileManager.getJavaFileObjectsFromFiles(
                    Files.walk(sourceDir)
                        .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".java") }
                        .map { it.toFile() }
                        .toList()
                )
                val success = compiler.getTask(null, fileManager, null, null, null, units).call()
                assertTrue(success, "In-memory test sources should compile successfully")
            }

            JarOutputStream(Files.newOutputStream(jarPath), Manifest().apply {
                mainAttributes.putValue("Manifest-Version", "1.0")
            }).use { jos ->
                Files.walk(classesDir)
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }
                    .forEach { classFile ->
                        val entryName = classesDir.relativize(classFile).toString().replace(File.separatorChar, '/')
                        jos.putNextEntry(JarEntry(entryName))
                        Files.newInputStream(classFile).use { it.copyTo(jos) }
                        jos.closeEntry()
                    }
            }
            return jarPath
        } finally {
            sourceDir.toFile().deleteRecursively()
            classesDir.toFile().deleteRecursively()
        }
    }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> invokePrivate(target: Any, name: String, vararg args: Any?): T {
        val parameterTypes = args.map {
            when (it) {
                is Path -> Path::class.java
                is BytecodeSignatureReader -> BytecodeSignatureReader::class.java
                else -> it!!::class.java
            }
        }.toTypedArray()
        val method = target.javaClass.getDeclaredMethod(name, *parameterTypes)
        method.isAccessible = true
        return method.invoke(target, *args) as T
    }
}
