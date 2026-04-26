package io.johnsonlee.graphite.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResourceAccessorTest {

    @Test
    fun `EmptyResourceAccessor list returns empty sequence`() {
        val result = EmptyResourceAccessor.list("**/*.json").toList()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `EmptyResourceAccessor open throws IOException`() {
        assertFailsWith<java.io.IOException> { EmptyResourceAccessor.open("any/path.txt") }
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
