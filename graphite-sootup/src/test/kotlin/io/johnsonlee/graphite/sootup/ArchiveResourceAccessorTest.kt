package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.EmptyResourceAccessor
import io.johnsonlee.graphite.input.JavaArchiveLayout
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArchiveResourceAccessorTest {

    // ========================================================================
    // Directory source
    // ========================================================================

    @Test
    fun `create from directory lists files`() {
        val dir = Files.createTempDirectory("test-resources")
        try {
            Files.write(dir.resolve("a.txt"), "hello".toByteArray())
            Files.createDirectories(dir.resolve("sub"))
            Files.write(dir.resolve("sub/b.json"), "{}".toByteArray())

            val accessor = ArchiveResourceAccessor.create(dir)
            val entries = accessor.list("**").toList()
            assertEquals(2, entries.size)
            assertTrue(entries.any { it.path == "a.txt" })
            assertTrue(entries.any { it.path == "sub/b.json" })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `create from directory open returns content`() {
        val dir = Files.createTempDirectory("test-resources")
        try {
            Files.write(dir.resolve("a.txt"), "hello".toByteArray())

            val accessor = ArchiveResourceAccessor.create(dir)
            val content = accessor.open("a.txt").bufferedReader().readText()
            assertEquals("hello", content)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `create from directory open throws IOException for missing`() {
        val dir = Files.createTempDirectory("test-resources")
        try {
            val accessor = ArchiveResourceAccessor.create(dir)
            assertFailsWith<java.io.IOException> { accessor.open("missing.txt") }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `create from directory filters with glob`() {
        val dir = Files.createTempDirectory("test-resources")
        try {
            Files.write(dir.resolve("a.txt"), "hello".toByteArray())
            Files.createDirectories(dir.resolve("sub"))
            Files.write(dir.resolve("sub/b.json"), "{}".toByteArray())

            val accessor = ArchiveResourceAccessor.create(dir)
            val txtFiles = accessor.list("*.txt").toList()
            assertEquals(1, txtFiles.size)
            assertEquals("a.txt", txtFiles[0].path)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `create from directory supports recursive glob prefix`() {
        val dir = Files.createTempDirectory("test-resources")
        try {
            Files.createDirectories(dir.resolve("sub"))
            Files.write(dir.resolve("sub/b.json"), "{}".toByteArray())

            val accessor = ArchiveResourceAccessor.create(dir)
            val jsonFiles = accessor.list("**/*.json").toList()

            assertEquals(listOf("sub/b.json"), jsonFiles.map { it.path })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `create from directory includes nested jar entries with relative jar source`() {
        val dir = Files.createTempDirectory("test-resource-layout")
        val libDir = Files.createDirectories(dir.resolve("lib"))
        val jarFile = createTempJar(
            mapOf(
                "org/example/Dependency.class" to "fake-class",
                "META-INF/services/example" to "org.example.Dependency"
            )
        )
        try {
            Files.copy(jarFile.toPath(), libDir.resolve("dependency.jar"))

            val accessor = ArchiveResourceAccessor.create(dir)
            val entries = accessor.list("**").toList()

            assertTrue(entries.any { it.path == "org/example/Dependency.class" && it.source == "lib/dependency.jar" })
            assertFalse(entries.any { it.path == "lib/dependency.jar" }, "Archive file itself should not be exposed as a resource")
            assertFailsWith<java.io.IOException> { accessor.open("lib/dependency.jar") }
        } finally {
            jarFile.delete()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `create from directory includes nested zip entries`() {
        val dir = Files.createTempDirectory("test-resource-layout")
        val zipFile = createTempJar(mapOf("config/settings.json" to "{}"))
        try {
            Files.copy(zipFile.toPath(), dir.resolve("bundle.zip"))

            val accessor = ArchiveResourceAccessor.create(dir)
            val entries = accessor.list("config/*.json").toList()

            assertEquals(1, entries.size)
            assertEquals("config/settings.json", entries.single().path)
            assertEquals("bundle.zip", entries.single().source)
        } finally {
            zipFile.delete()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `directory jar collection tolerates missing roots`() {
        val missing = Files.createTempDirectory("missing-root-parent").resolve("missing")
        try {
            val method = ArchiveResourceAccessor.Companion::class.java.getDeclaredMethod("collectDirectoryJars", Path::class.java)
            method.isAccessible = true
            val bundle = method.invoke(ArchiveResourceAccessor.Companion, missing)
            val sources = bundle.javaClass.getDeclaredField("sources").also { it.isAccessible = true }.get(bundle) as List<*>
            val closers = bundle.javaClass.getDeclaredField("closers").also { it.isAccessible = true }.get(bundle) as List<*>

            assertTrue(sources.isEmpty())
            assertTrue(closers.isEmpty())
        } finally {
            missing.parent.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // JAR source
    // ========================================================================

    @Test
    fun `create from JAR lists entries`() {
        val jarFile = createTempJar(
            mapOf(
                JavaArchiveLayout.META_INF_MANIFEST to "Manifest-Version: 1.0",
                "com/example/Foo.class" to "fake-class",
                "resources/data.json" to "{\"key\":\"value\"}"
            )
        )
        try {
            val accessor = ArchiveResourceAccessor.create(jarFile.toPath())
            val entries = accessor.list("**").toList()
            assertTrue(entries.size >= 3)
            assertTrue(entries.any { it.path == "resources/data.json" })
        } finally {
            jarFile.delete()
        }
    }

    @Test
    fun `create from JAR open returns content`() {
        val jarFile = createTempJar(
            mapOf(
                "resources/data.json" to "{\"key\":\"value\"}"
            )
        )
        try {
            val accessor = ArchiveResourceAccessor.create(jarFile.toPath())
            val content = accessor.open("resources/data.json").bufferedReader().readText()
            assertEquals("{\"key\":\"value\"}", content)
        } finally {
            jarFile.delete()
        }
    }

    @Test
    fun `create from JAR open throws IOException for missing entry`() {
        val jarFile = createTempJar(
            mapOf(
                "resources/data.json" to "{}"
            )
        )
        try {
            val accessor = ArchiveResourceAccessor.create(jarFile.toPath())
            assertFailsWith<java.io.IOException> { accessor.open("nonexistent.txt") }
        } finally {
            jarFile.delete()
        }
    }

    @Test
    fun `create from JAR filters with glob`() {
        val jarFile = createTempJar(
            mapOf(
                "com/example/Foo.class" to "fake",
                "resources/data.json" to "{}",
                "resources/schema.json" to "{}"
            )
        )
        try {
            val accessor = ArchiveResourceAccessor.create(jarFile.toPath())
            val jsonFiles = accessor.list("resources/*.json").toList()
            assertEquals(2, jsonFiles.size)
        } finally {
            jarFile.delete()
        }
    }

    // ========================================================================
    // Spring Boot fat JAR (NestedJarSource)
    // ========================================================================

    @Test
    fun `create from Spring Boot JAR lists entries under BOOT-INF classes`() {
        val jarFile = createTempJar(
            mapOf(
                JavaArchiveLayout.BOOT_INF_CLASSES to "",
                JavaArchiveLayout.bootInfClassEntry("application.yml") to "server.port: 8080",
                JavaArchiveLayout.bootInfClassEntry("config/app.yml") to "app.name: graphite"
            )
        )
        try {
            val accessor = ArchiveResourceAccessor.create(jarFile.toPath())
            val entries = accessor.list("**").toList()
            assertTrue(entries.any { it.path == "application.yml" }, "Should strip ${JavaArchiveLayout.BOOT_INF_CLASSES} prefix: $entries")
            assertTrue(entries.any { it.path == "config/app.yml" }, "Should include nested paths: $entries")
        } finally {
            jarFile.delete()
        }
    }

    @Test
    fun `create from Spring Boot JAR reads content from BOOT-INF classes`() {
        val jarFile = createTempJar(
            mapOf(
                JavaArchiveLayout.BOOT_INF_CLASSES to "",
                JavaArchiveLayout.bootInfClassEntry("application.yml") to "server.port: 8080"
            )
        )
        try {
            val accessor = ArchiveResourceAccessor.create(jarFile.toPath())
            val content = accessor.open("application.yml").bufferedReader().readText()
            assertEquals("server.port: 8080", content)
        } finally {
            jarFile.delete()
        }
    }

    @Test
    fun `create from Spring Boot JAR open throws IOException for missing resource`() {
        val jarFile = createTempJar(
            mapOf(
                JavaArchiveLayout.BOOT_INF_CLASSES to "",
                JavaArchiveLayout.bootInfClassEntry("application.yml") to "server.port: 8080"
            )
        )
        try {
            val accessor = ArchiveResourceAccessor.create(jarFile.toPath())
            assertFailsWith<java.io.IOException> { accessor.open("nonexistent.txt") }
        } finally {
            jarFile.delete()
        }
    }

    @Test
    fun `close removes extracted nested jar temp directories`() {
        val nestedJar = createTempJar(mapOf("sample/Inner.class" to "fake"))
        val jarFile = createTempJar(
            mapOf(
                JavaArchiveLayout.BOOT_INF_CLASSES to "",
                JavaArchiveLayout.bootInfClassEntry("application.yml") to "server.port: 8080"
            ),
            binaryEntries = mapOf(JavaArchiveLayout.bootInfLibEntry("nested.jar") to nestedJar.readBytes())
        )
        try {
            val accessor = ArchiveResourceAccessor.create(jarFile.toPath())
            val tempDirs = readTempDirs(accessor)
            assertTrue(tempDirs.isNotEmpty())
            tempDirs.forEach { assertTrue(Files.exists(it)) }

            accessor.close()

            tempDirs.forEach { assertFalse(Files.exists(it)) }
        } finally {
            nestedJar.delete()
            jarFile.delete()
        }
    }

    // ========================================================================
    // EmptyResourceAccessor
    // ========================================================================

    @Test
    fun `EmptyResourceAccessor list returns empty`() {
        assertEquals(0, EmptyResourceAccessor.list("**").toList().size)
    }

    @Test
    fun `EmptyResourceAccessor open throws IOException`() {
        assertFailsWith<java.io.IOException> { EmptyResourceAccessor.open("any") }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun createTempJar(entries: Map<String, String>, binaryEntries: Map<String, ByteArray> = emptyMap()): File {
        val file = File.createTempFile("test", ".jar")
        JarOutputStream(file.outputStream()).use { jos ->
            entries.forEach { (name, content) ->
                jos.putNextEntry(JarEntry(name))
                jos.write(content.toByteArray())
                jos.closeEntry()
            }
            binaryEntries.forEach { (name, content) ->
                jos.putNextEntry(JarEntry(name))
                jos.write(content)
                jos.closeEntry()
            }
        }
        return file
    }

    @Suppress("UNCHECKED_CAST")
    private fun readTempDirs(accessor: ArchiveResourceAccessor): List<Path> {
        val field = accessor.javaClass.getDeclaredField("tempDirs")
        field.isAccessible = true
        return field.get(accessor) as List<Path>
    }
}
