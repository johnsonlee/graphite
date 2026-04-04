package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.EmptyResourceAccessor
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    // ========================================================================
    // JAR source
    // ========================================================================

    @Test
    fun `create from JAR lists entries`() {
        val jarFile = createTempJar(
            mapOf(
                "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0",
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
                "BOOT-INF/classes/" to "",
                "BOOT-INF/classes/application.yml" to "server.port: 8080",
                "BOOT-INF/classes/config/app.yml" to "app.name: graphite"
            )
        )
        try {
            val accessor = ArchiveResourceAccessor.create(jarFile.toPath())
            val entries = accessor.list("**").toList()
            assertTrue(entries.any { it.path == "application.yml" }, "Should strip BOOT-INF/classes/ prefix: $entries")
            assertTrue(entries.any { it.path == "config/app.yml" }, "Should include nested paths: $entries")
        } finally {
            jarFile.delete()
        }
    }

    @Test
    fun `create from Spring Boot JAR reads content from BOOT-INF classes`() {
        val jarFile = createTempJar(
            mapOf(
                "BOOT-INF/classes/" to "",
                "BOOT-INF/classes/application.yml" to "server.port: 8080"
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
                "BOOT-INF/classes/" to "",
                "BOOT-INF/classes/application.yml" to "server.port: 8080"
            )
        )
        try {
            val accessor = ArchiveResourceAccessor.create(jarFile.toPath())
            assertFailsWith<java.io.IOException> { accessor.open("nonexistent.txt") }
        } finally {
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

    private fun createTempJar(entries: Map<String, String>): File {
        val file = File.createTempFile("test", ".jar")
        JarOutputStream(file.outputStream()).use { jos ->
            entries.forEach { (name, content) ->
                jos.putNextEntry(JarEntry(name))
                jos.write(content.toByteArray())
                jos.closeEntry()
            }
        }
        return file
    }
}
