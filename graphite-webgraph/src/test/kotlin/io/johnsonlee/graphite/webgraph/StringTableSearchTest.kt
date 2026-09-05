package io.johnsonlee.graphite.webgraph

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StringTableSearchTest {

    private fun withSortedTable(strings: Collection<String>, block: (StringTable) -> Unit) {
        val dir = Files.createTempDirectory("webgraph-string-table-search")
        try {
            StringTable.build(strings, dir)
            block(StringTable.load(dir))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** More strings than one front-coded block so both the head search and the block walk run. */
    private val strings = (0 until 100).map { index -> "example.pkg${index / 10}.Class${index % 10}" } +
        listOf("", "a", "zeta.Last", "example.pkg1.Class5Extra", "example.pkgX")

    @Test
    fun `sorted lookups find every string and reject absent neighbours`() = withSortedTable(strings) { table ->
        val sorted = strings.sorted()
        sorted.forEachIndexed { index, value ->
            assertEquals(index, table.findId(value), value)
            assertEquals(index, table.lowerBound(value), value)
            assertTrue(table.startsWithAt(index, value.take(3)))
        }
        assertEquals(-1, table.findId("example.pkg1.Class5Extr"))
        assertEquals(-1, table.findId("example.pkg1.Class5Extraa"))
        assertEquals(-1, table.findId("zzz"))
        assertEquals(sorted.size, table.lowerBound("zzz"))
        assertEquals(0, table.lowerBound(""))
        assertEquals(sorted.indexOf("a"), table.lowerBound("a"))
        assertEquals(sorted.indexOf("example.pkg0.Class0"), table.lowerBound("b"))
        assertFalse(table.startsWithAt(-1, "a"))
        assertFalse(table.startsWithAt(sorted.size, "a"))
    }

    @Test
    fun `range restricted lookups stay inside the range`() = withSortedTable(strings) { table ->
        val sorted = strings.sorted()
        val range = table.prefixRange("example.pkg1.")
        assertEquals(sorted.indexOf("example.pkg1.Class0"), range.first)
        assertEquals(sorted.indexOf("example.pkg1.Class9"), range.last)
        assertEquals(sorted.indexOf("example.pkg1.Class5"), table.findId("example.pkg1.Class5", range.first, range.last + 1))
        assertEquals(-1, table.findId("example.pkg2.Class0", range.first, range.last + 1))
        assertEquals(-1, table.findId("example.pkg1.Class5", range.first, range.first))
        assertTrue(table.prefixRange("missing.").isEmpty())
        assertEquals(0 until sorted.size, table.prefixRange(""))
        assertEquals(sorted.indexOf("example.pkgX")..sorted.indexOf("example.pkgX"), table.prefixRange("example.pkgX"))
        // A prefix ending in the largest code unit cannot be bounded by a successor string and walks instead.
        assertTrue(table.prefixRange("example.pkg1.Class5\uFFFF").isEmpty())
        assertTrue(table.prefixRange("\uFFFF").isEmpty())
    }

    @Test
    fun `lookups charge one work unit per decoded probe`() = withSortedTable(strings) { table ->
        var charged = 0L
        val consumer = object : io.johnsonlee.graphite.graph.GraphWorkBatchConsumer {
            override fun consume(workUnits: Long) {
                charged += workUnits
            }
        }
        val index = table.lowerBound("example.pkg5.Class5", consumer)
        assertEquals(strings.sorted().indexOf("example.pkg5.Class5"), index)
        assertTrue(charged in 1L..(strings.size.toLong()))
        assertTrue(charged < strings.size / 2, "block-aware search decoded $charged of ${strings.size} strings")
    }
}
