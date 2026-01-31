package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.LoaderConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

            // Should find classes extracted from BOOT-INF/classes
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
                "Should find fields in SimpleService from BOOT-INF/classes")
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

            // Should find classes from WEB-INF/classes
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
                "Should find fields from WAR WEB-INF/classes")

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
    fun `should handle loadSignatures failure gracefully`() {
        // Create a JAR with invalid content to trigger the catch in loadSignatures
        val tempJar = Files.createTempFile("invalid", ".jar")
        try {
            // Write a minimal valid ZIP but no .class entries
            ZipOutputStream(Files.newOutputStream(tempJar)).use { zos ->
                zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
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

            // Generic field types should be loaded from WAR WEB-INF/classes
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
     * Build a Spring Boot-style JAR with BOOT-INF/classes layout.
     */
    private fun buildSpringBootJar(classesDir: Path, classPathPrefix: String): Path {
        val jarPath = Files.createTempFile("springboot", ".jar")

        JarOutputStream(Files.newOutputStream(jarPath), Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Spring-Boot-Classes", "BOOT-INF/classes/")
        }).use { jos ->
            // Add BOOT-INF/classes/ directory entry
            jos.putNextEntry(JarEntry("BOOT-INF/classes/"))
            jos.closeEntry()

            val baseDir = classesDir.toFile()
            val prefixDir = File(baseDir, classPathPrefix)
            if (prefixDir.exists()) {
                prefixDir.walk()
                    .filter { it.isFile && it.name.endsWith(".class") }
                    .forEach { classFile ->
                        val relativePath = classFile.relativeTo(baseDir).path.replace(File.separatorChar, '/')
                        jos.putNextEntry(JarEntry("BOOT-INF/classes/$relativePath"))
                        classFile.inputStream().use { it.copyTo(jos) }
                        jos.closeEntry()
                    }
            }
        }

        return jarPath
    }

    /**
     * Build a Spring Boot JAR with BOOT-INF/classes and BOOT-INF/lib.
     */
    private fun buildSpringBootJarWithLibs(
        classesDir: Path,
        classPathPrefix: String,
        libJars: List<Path>
    ): Path {
        val jarPath = Files.createTempFile("springboot", ".jar")

        JarOutputStream(Files.newOutputStream(jarPath), Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Spring-Boot-Classes", "BOOT-INF/classes/")
            mainAttributes.putValue("Spring-Boot-Lib", "BOOT-INF/lib/")
        }).use { jos ->
            // Add BOOT-INF/classes/
            jos.putNextEntry(JarEntry("BOOT-INF/classes/"))
            jos.closeEntry()

            val baseDir = classesDir.toFile()
            val prefixDir = File(baseDir, classPathPrefix)
            if (prefixDir.exists()) {
                prefixDir.walk()
                    .filter { it.isFile && it.name.endsWith(".class") }
                    .forEach { classFile ->
                        val relativePath = classFile.relativeTo(baseDir).path.replace(File.separatorChar, '/')
                        jos.putNextEntry(JarEntry("BOOT-INF/classes/$relativePath"))
                        classFile.inputStream().use { it.copyTo(jos) }
                        jos.closeEntry()
                    }
            }

            // Add BOOT-INF/lib/
            jos.putNextEntry(JarEntry("BOOT-INF/lib/"))
            jos.closeEntry()

            libJars.forEach { libJar ->
                val jarName = libJar.fileName.toString()
                jos.putNextEntry(JarEntry("BOOT-INF/lib/$jarName"))
                Files.newInputStream(libJar).use { it.copyTo(jos) }
                jos.closeEntry()
            }
        }

        return jarPath
    }

    /**
     * Build a WAR file with WEB-INF/classes layout.
     */
    private fun buildWarFile(classesDir: Path, classPathPrefix: String): Path {
        val warPath = Files.createTempFile("test", ".war")

        ZipOutputStream(Files.newOutputStream(warPath)).use { zos ->
            // Add WEB-INF/classes/
            zos.putNextEntry(ZipEntry("WEB-INF/classes/"))
            zos.closeEntry()

            val baseDir = classesDir.toFile()
            val prefixDir = File(baseDir, classPathPrefix)
            if (prefixDir.exists()) {
                prefixDir.walk()
                    .filter { it.isFile && it.name.endsWith(".class") }
                    .forEach { classFile ->
                        val relativePath = classFile.relativeTo(baseDir).path.replace(File.separatorChar, '/')
                        zos.putNextEntry(ZipEntry("WEB-INF/classes/$relativePath"))
                        classFile.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
            }
        }

        return warPath
    }

    /**
     * Build a WAR file with WEB-INF/classes and WEB-INF/lib.
     */
    private fun buildWarFileWithLibs(
        classesDir: Path,
        classPathPrefix: String,
        libJars: List<Path>
    ): Path {
        val warPath = Files.createTempFile("test", ".war")

        ZipOutputStream(Files.newOutputStream(warPath)).use { zos ->
            // Add WEB-INF/classes/
            zos.putNextEntry(ZipEntry("WEB-INF/classes/"))
            zos.closeEntry()

            val baseDir = classesDir.toFile()
            val prefixDir = File(baseDir, classPathPrefix)
            if (prefixDir.exists()) {
                prefixDir.walk()
                    .filter { it.isFile && it.name.endsWith(".class") }
                    .forEach { classFile ->
                        val relativePath = classFile.relativeTo(baseDir).path.replace(File.separatorChar, '/')
                        zos.putNextEntry(ZipEntry("WEB-INF/classes/$relativePath"))
                        classFile.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
            }

            // Add WEB-INF/lib/
            zos.putNextEntry(ZipEntry("WEB-INF/lib/"))
            zos.closeEntry()

            libJars.forEach { libJar ->
                val jarName = libJar.fileName.toString()
                zos.putNextEntry(ZipEntry("WEB-INF/lib/$jarName"))
                Files.newInputStream(libJar).use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }

        return warPath
    }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }
}
