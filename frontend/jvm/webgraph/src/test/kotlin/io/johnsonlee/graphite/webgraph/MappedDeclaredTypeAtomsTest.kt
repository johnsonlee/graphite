package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.propertyTextFragments
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MappedDeclaredTypeAtomsTest {
    private val types = listOf(
        DeclaredType("class", "java.lang.String"),
        DeclaredType("variable", "T", "method:泛型🚀.Owner#scopedMethod()V"),
        DeclaredType("wildcard", component = 0, variance = "extends"),
        DeclaredType("wildcard", component = 1, variance = "super"),
        DeclaredType("wildcard", variance = "unbounded"),
        DeclaredType("class", "泛型🚀.Outer", arguments = listOf(1)),
        DeclaredType("class", "泛型🚀.Outer\$Inner\$Name", owner = 5, arguments = listOf(2, 3, 4)),
        DeclaredType("array", component = 6)
    )

    @Test
    fun `validated mapped atoms retain rendered and sparse structured candidates without decoding`() = mapped(types) { rows ->
        val counted = CountingTypes(rows)
        for (needle in listOf(
            "Outer<T>.Inner\$Name", "? extends java.lang.", "? super T", "scopedMethod",
            "name=java.lang", "kind=wildcard", "variance=unbounded", "owner={kind=class",
            "component={kind=class", "scope=method", "arguments=[{kind=wildcard",
            "MissingDeclarationNeedle", "scopedMethod.MissingDeclarationNeedle", "SCOPEDMETHOD",
            "generic_type", "type_info"
        )) {
            val fragments = propertyTextFragments(needle)
            assertEquals(scan(types, fragments), scan(counted, fragments), needle)
        }
        assertEquals(0, counted.decoded, "ASCII scans must not materialize type rows")
        assertFailsWith<IndexOutOfBoundsException> { counted.typeOffset(-1) }
        assertFailsWith<IndexOutOfBoundsException> { counted.typeOffset(rows.size) }
    }

    @Test
    fun `ASCII cannot match within UTF8 continuation bytes and direct Unicode fragments fall back`() = mapped(types) { rows ->
        val counted = CountingTypes(rows)
        assertTrue(scan(counted, listOf("Outer")))
        assertFalse(scan(counted, listOf("OuterInner")))
        assertFalse(scan(counted, listOf("\u007F")))
        assertEquals(0, counted.decoded)
        assertTrue(scan(counted, listOf("泛型🚀")))
        assertTrue(counted.decoded > 0)
        assertEquals(scan(types, listOf("泛型🚀", "Inner")), scan(counted, listOf("泛型🚀", "Inner")))
        assertFalse(scan(counted, listOf("不存在")))
    }

    @Test
    fun `mapped atoms preserve shared DAG reference budgets and two branch fragments`() {
        val shared = listOf(
            DeclaredType("class", "OnlyLeft"),
            DeclaredType("class", "OnlyRight"),
            DeclaredType("class", "Pair", arguments = listOf(0, 1, 0)),
            DeclaredType("array", component = 2)
        )
        mapped(shared) { rows ->
            for (fragments in listOf(listOf("OnlyLeft"), listOf("OnlyLeft", "OnlyRight"), listOf("Missing"))) {
                val expected = resultAndWork(shared, fragments)
                assertEquals(expected, resultAndWork(rows, fragments), fragments.toString())
            }
            assertEquals(false to 8, resultAndWork(rows, listOf("Missing")))
            assertTrue(scan(rows, listOf("OnlyLeft", "OnlyRight")))
            assertBudgetFailure(shared)
            assertBudgetFailure(rows)
        }
    }

    @Test
    fun `optional structured keys remain absent and interruption reaches mapped scans`() {
        mapped(listOf(DeclaredType("class", "List"))) { rows ->
            for (key in listOf("owner", "component", "scope", "variance")) assertFalse(scan(rows, listOf(key)), key)
            for (key in listOf("kind", "name", "arguments")) assertTrue(scan(rows, listOf(key)), key)
            Thread.currentThread().interrupt()
            try {
                assertFailsWith<CancellationException> { scan(rows, listOf("Missing")) }
            } finally {
                Thread.interrupted()
            }
        }
    }

    @Test
    fun `concurrent scans share immutable mapped bytes without cursor interference`() = mapped(types) { rows ->
        val fragments = listOf(listOf("Outer", "String"), listOf("Missing"), listOf("scope", "super"))
        val expected = fragments.map { scan(types, it) }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 12).map { index ->
                pool.submit<Boolean> { scan(rows, fragments[index % fragments.size]) }
            }
            assertEquals((0 until 12).map { expected[it % expected.size] }, futures.map { it.get() })
        } finally {
            pool.shutdownNow()
        }
    }

    private fun assertBudgetFailure(rows: List<DeclaredType>) {
        val marker = IllegalStateException("budget")
        var consumed = 0
        assertSame(marker, assertFailsWith<IllegalStateException> {
            DeclaredTypeTextCandidates(rows, listOf("Missing"), GraphWorkConsumer {
                if (++consumed == 4) throw marker
            }).mayMatch()
        })
        assertEquals(4, consumed)
    }

    private fun resultAndWork(rows: List<DeclaredType>, fragments: List<String>): Pair<Boolean, Int> {
        var consumed = 0
        val result = DeclaredTypeTextCandidates(rows, fragments, GraphWorkConsumer { consumed++ }).mayMatch()
        return result to consumed
    }

    private fun scan(rows: List<DeclaredType>, fragments: List<String>): Boolean =
        DeclaredTypeTextCandidates(rows, fragments, null).mayMatch()

    private fun mapped(rows: List<DeclaredType>, block: (List<DeclaredType>) -> Unit) {
        val directory = Files.createTempDirectory("declared-atoms")
        try {
            Files.writeString(directory.resolve("graph.metadata"), "binding")
            DeclaredTypeStore.save(DeclaredTypeTable(rows, emptyMap(), emptyMap(), emptyMap()), directory)
            val restored = DeclaredTypeStore.load(directory).types
            assertIs<DeclaredTypeAtoms>(restored)
            block(restored)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private class CountingTypes(private val rows: List<DeclaredType>) :
        AbstractList<DeclaredType>(), DeclaredTypeAtoms by (rows as DeclaredTypeAtoms) {
        var decoded = 0
        override val size: Int get() = rows.size
        override fun get(index: Int): DeclaredType {
            decoded++
            return rows[index]
        }
    }
}
