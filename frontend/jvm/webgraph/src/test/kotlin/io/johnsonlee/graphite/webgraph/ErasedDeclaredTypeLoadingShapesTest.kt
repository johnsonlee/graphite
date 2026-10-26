package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ErasedDeclaredTypeLoadingShapesTest {
    @Test
    fun `loading shapes reuse raw decoding and release before query access`() {
        val rows = listOf(DeclaredType("class", "fixture.名"), DeclaredType("array", component = 0))
        var reads = 0
        val raw = ErasedDeclaredTypes(rows.size, cacheLoadingShapes = true) { reads++; rows[it] }
        assertEquals("[Lfixture/名;", raw.descriptor(1))
        val initialReads = reads
        raw.length(1, utf8 = true)
        assertEquals("[Lfixture/名;", raw.descriptor(1))
        assertEquals(raw.shape(1), raw.shape(1))
        assertEquals(initialReads, reads)
        raw.finishLoading()
        assertNull(raw.javaClass.getDeclaredField("loadingShapes").apply { isAccessible = true }.get(raw))
        // The decoded Shape objects are released; the validated leaf and dimensions of every row read
        // during loading stay as ints, so spelling a descriptor afterwards decodes no row again.
        assertEquals("[Lfixture/名;", raw.descriptor(1))
        assertEquals(raw.shape(1), raw.shape(1))
        assertEquals(initialReads, reads)
        assertEquals(0, raw.leaf(1))
        assertEquals(1, raw.dimensions(1))
        assertEquals("fixture.名[]", raw.javaName(1))
        assertEquals(initialReads, reads)
    }

    @Test
    fun `cached void return never authorizes field parameter or array`() {
        val rows = listOf(DeclaredType("primitive", "void"), DeclaredType("array", component = 0),
            DeclaredType("class", "void"))
        val raw = ErasedDeclaredTypes(rows.size, cacheLoadingShapes = true) { rows[it] }
        assertFailsWith<IllegalArgumentException> { raw.descriptor(0) }
        assertEquals("V", raw.descriptor(0, allowVoid = true))
        assertFailsWith<IllegalArgumentException> { raw.descriptor(0) }
        assertFailsWith<IllegalArgumentException> { raw.length(0, utf8 = true) }
        repeat(2) { assertFailsWith<IllegalArgumentException> { raw.descriptor(1, allowVoid = true) } }
        assertEquals("Lvoid;", raw.descriptor(2))
    }

    @Test
    fun `failed shapes and excessive array depth never populate root cache`() {
        val invalid = listOf(DeclaredType("array"), DeclaredType("array", "named", component = 0),
            DeclaredType("variable", "T"), DeclaredType("class", "bad/name"),
            DeclaredType("array", component = 99), DeclaredType("class", "Valid", arguments = listOf(0)))
        invalid.forEach { row ->
            var reads = 0
            val raw = ErasedDeclaredTypes(1, cacheLoadingShapes = true) { reads++; row }
            repeat(2) { assertFailsWith<IllegalArgumentException> { raw.shape(0, allowVoid = true) } }
            assertEquals(2, reads)
        }
        val depth = DeclaredTypeTable.MAX_DEPTH
        val raw = ErasedDeclaredTypes(depth + 1, cacheLoadingShapes = true) { id ->
            if (id == depth) DeclaredType("primitive", "int") else DeclaredType("array", component = id + 1)
        }
        raw.shape(1) // A legal child root must not authorize an over-depth parent.
        repeat(2) { assertFailsWith<IllegalArgumentException> { raw.shape(0) } }
        val cycle = ErasedDeclaredTypes(1, cacheLoadingShapes = true) { DeclaredType("array", component = 0) }
        assertFailsWith<IllegalArgumentException> { cycle.shape(0) }
    }
}
