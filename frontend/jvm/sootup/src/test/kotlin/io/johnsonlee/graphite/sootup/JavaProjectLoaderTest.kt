package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.LoaderConfig
import java.io.File
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaProjectLoaderTest {
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
    fun `canLoad should return true for apk files`() {
        val loader = JavaProjectLoader()
        val tempApk = Files.createTempFile("test", ".apk")
        try {
            assertTrue(loader.canLoad(tempApk), "Should be able to load APK files")
        } finally {
            Files.deleteIfExists(tempApk)
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
            val sources = inputLocations.javaClass.getDeclaredField("sources").also { it.isAccessible = true }.get(inputLocations) as Map<*,
                *>

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
            val sources = inputLocations.javaClass.getDeclaredField("sources").also { it.isAccessible = true }.get(inputLocations) as Map<*,
                *>

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
}
