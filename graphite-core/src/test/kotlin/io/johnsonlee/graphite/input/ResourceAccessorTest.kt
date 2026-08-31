package io.johnsonlee.graphite.input

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceAccessorTest {

    @Test
    fun `EmptyResourceAccessor list returns empty sequence`() {
        val result = EmptyResourceAccessor.list("**/*.json").toList()
        assertTrue(result.isEmpty())
        assertNull(EmptyResourceAccessor.unavailableReason)
    }

    @Test
    fun `EmptyResourceAccessor open throws IOException`() {
        assertFailsWith<java.io.IOException> { EmptyResourceAccessor.open("any/path.txt") }
    }

    @Test
    fun `implementation compiled against old accessor ABI remains compatible`() {
        assertEquals(setOf("list", "open"), ResourceAccessor::class.java.declaredMethods.map { it.name }.toSet())
        val root = Files.createTempDirectory("legacy-resource-accessor-test")
        val sources = root.resolve("sources")
        val classes = root.resolve("classes")
        try {
            val oldApi = sources.resolve("io/johnsonlee/graphite/input/ResourceAccessor.java")
            val legacyImplementation = sources.resolve("fixture/LegacyAccessor.java")
            Files.createDirectories(oldApi.parent)
            Files.createDirectories(legacyImplementation.parent)
            Files.createDirectories(classes)
            Files.writeString(
                oldApi,
                """
                package io.johnsonlee.graphite.input;

                public interface ResourceAccessor {
                    kotlin.sequences.Sequence<ResourceEntry> list(String pattern);
                    java.io.InputStream open(String path) throws java.io.IOException;
                }
                """.trimIndent()
            )
            Files.writeString(
                legacyImplementation,
                """
                package fixture;

                public final class LegacyAccessor implements io.johnsonlee.graphite.input.ResourceAccessor {
                    public kotlin.sequences.Sequence<io.johnsonlee.graphite.input.ResourceEntry> list(String pattern) {
                        return null;
                    }

                    public java.io.InputStream open(String path) throws java.io.IOException {
                        throw new java.io.IOException(path);
                    }
                }
                """.trimIndent()
            )

            val compiler = requireNotNull(javax.tools.ToolProvider.getSystemJavaCompiler())
            val compileClasspath = listOf(ResourceEntry::class.java, Sequence::class.java)
                .map { type -> File(requireNotNull(type.protectionDomain.codeSource).location.toURI()).path }
                .distinct()
                .joinToString(File.pathSeparator)
            assertEquals(
                0,
                compiler.run(
                    null,
                    null,
                    null,
                    "-classpath",
                    compileClasspath,
                    "-d",
                    classes.toString(),
                    oldApi.toString(),
                    legacyImplementation.toString()
                ),
                "Legacy accessor fixture should compile against the old two-method interface"
            )

            Files.delete(classes.resolve("io/johnsonlee/graphite/input/ResourceAccessor.class"))
            URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader).use { loader ->
                val accessor = loader.loadClass("fixture.LegacyAccessor")
                    .getDeclaredConstructor()
                    .newInstance() as ResourceAccessor
                assertNull(accessor.unavailableReason)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `ResourceEntry stores path and source`() {
        val entry = ResourceEntry(path = "config/application.yml", source = JavaArchiveLayout.BOOT_INF_CLASSES)
        assertEquals("config/application.yml", entry.path)
        assertEquals(JavaArchiveLayout.BOOT_INF_CLASSES, entry.source)
    }

    @Test
    fun `ResourceEntry equals and hashCode`() {
        val entry1 = ResourceEntry(path = "a.txt", source = "src")
        val entry2 = ResourceEntry(path = "a.txt", source = "src")
        assertEquals(entry1, entry2)
        assertEquals(entry1.hashCode(), entry2.hashCode())
    }

    @Test
    fun `ResourceEntry toString contains fields`() {
        val entry = ResourceEntry(path = "a.txt", source = "src")
        val str = entry.toString()
        assertTrue(str.contains("a.txt"))
        assertTrue(str.contains("src"))
    }
}
