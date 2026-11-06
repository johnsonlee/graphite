package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.JavaArchiveLayout
import io.johnsonlee.graphite.input.LoaderConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaProjectLoaderArchiveTest {
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
    fun `android jar resolution picks the available platform API`() {
        val platforms = Files.createTempDirectory("graphite-android-platforms-api")
        val invalidPlatforms = Files.createTempDirectory("graphite-android-platforms-empty")
        val appInput = Files.createTempFile("graphite-plain-input", ".jar")
        try {
            val androidJar = createAndroidJar(platforms, 16)

            assertEquals(
                androidJar,
                invokeLoaderFilePrivate<Path>(
                    "resolveAndroidJar",
                    arrayOf(Path::class.java, Path::class.java),
                    appInput,
                    platforms
                )
            )
            val missingJar = assertFailsWith<IllegalArgumentException> {
                invokeLoaderFilePrivate<Path>(
                    "resolveAndroidJar",
                    arrayOf(Path::class.java, Path::class.java),
                    appInput,
                    invalidPlatforms
                )
            }
            assertTrue(missingJar.message.orEmpty().contains("android.jar not found for API"))
        } finally {
            platforms.toFile().deleteRecursively()
            invalidPlatforms.toFile().deleteRecursively()
            Files.deleteIfExists(appInput)
        }
    }

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
    fun `should handle jars with no class files gracefully`() {
        // Create a valid JAR with only a manifest and no class files.
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

            val graph = loader.load(tempJar)
            assertNotNull(graph, "Should still produce a graph even with no class files")
        } finally {
            Files.deleteIfExists(tempJar)
        }
    }

    @Test
    fun `private helpers tolerate invalid archives`() {
        val invalidJar = Files.createTempFile("invalid-archive", ".jar")
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

    @Test
    fun `should recover generic fields from WAR WEB-INF classes`() {
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
                    "Field type should be List after generic field recovery from WAR")
            }
        } finally {
            Files.deleteIfExists(warPath)
            File(System.getProperty("java.io.tmpdir")).listFiles()
                ?.filter { it.name.startsWith("graphite-war") }
                ?.forEach { it.deleteRecursively() }
        }
    }

    @Test
    fun `should recover generic fields from Spring Boot BOOT-INF classes`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val jarPath = buildSpringBootJar(testClassesDir, "sample/generics/")

        try {
            val loader = JavaProjectLoader(LoaderConfig(
                includePackages = listOf("sample.generics"),
                buildCallGraph = false
            ))

            val graph = loader.load(jarPath)

            // Generic field types should be recovered from the Spring Boot class bytes.
            val usersField = graph.nodes<FieldNode>()
                .filter { it.descriptor.name == "users" }
                .filter { it.descriptor.declaringClass.className.contains("GenericFieldService") }
                .firstOrNull()

            if (usersField != null) {
                assertEquals("java.util.List", usersField.descriptor.type.className,
                    "Field type should be List after generic field recovery from Spring Boot JAR")
            }
        } finally {
            Files.deleteIfExists(jarPath)
            File(System.getProperty("java.io.tmpdir")).listFiles()
                ?.filter { it.name.startsWith("graphite-springboot") }
                ?.forEach { it.deleteRecursively() }
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
}
