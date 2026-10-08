package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.propertyTextFragments
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DeclaredTypeTextCandidatesTest {
    private val types = listOf(
        DeclaredType("class", "java.lang.String"),
        DeclaredType("variable", "T", "method:example.Owner#scopedMethod()V"),
        DeclaredType("wildcard", component = 0, variance = "extends"),
        DeclaredType("wildcard", component = 1, variance = "super"),
        DeclaredType("wildcard", variance = "unbounded"),
        DeclaredType("class", "example.Outer", arguments = listOf(1)),
        DeclaredType("class", "example.Outer\$Inner\$Name", owner = 5, arguments = listOf(2, 3, 4)),
        DeclaredType("array", component = 6)
    )

    @Test
    fun `raw atoms cover structured keys scopes wildcards and owner render boundaries`() {
        val table = DeclaredTypeTable(types, emptyMap(), emptyMap(), emptyMap())
        for (needle in listOf(
            "Outer<T>.Inner\$Name", "? extends java.lang.", "? super T", "scopedMethod",
            "name=java.lang", "kind=wildcard", "variance=unbounded", "owner={kind=class",
            "component={kind=class", "scope=method:example", "arguments=[{kind=wildcard"
        )) {
            assertTrue(types.indices.any { table.render(it).contains(needle) || table.info(it).toString().contains(needle) }, needle)
            assertTrue(candidates(needle), needle)
        }
        assertFalse(candidates("MissingDeclarationNeedle"))
        assertFalse(candidates("scopedMethod.MissingDeclarationNeedle"))
        assertFalse(candidates("SCOPEDMETHOD"))
        assertFalse(candidates("generic_type"))
        assertFalse(candidates("type_info"))
    }

    @Test
    fun `every substring across rendered and structured delimiters retains its token candidates`() {
        val shortTypes = listOf(
            DeclaredType("class", "java.lang.String"),
            DeclaredType("class", "List", arguments = listOf(0))
        )
        val table = DeclaredTypeTable(shortTypes, emptyMap(), emptyMap(), emptyMap())
        val checked = mutableSetOf<List<String>>()
        for (text in listOf(table.render(1), table.info(1).toString())) {
            assertSubstringCandidates(text, shortTypes, checked)
        }
        assertTrue(checked.size > 100)
    }

    @Test
    fun `two fragments can occur in different branches of the same type`() {
        val branches = listOf(
            DeclaredType("class", "OnlyLeft"), DeclaredType("class", "OnlyRight"),
            DeclaredType("class", "Pair", arguments = listOf(0, 1))
        )
        val fragments = propertyTextFragments("OnlyLeft, OnlyRight")
        assertTrue(DeclaredTypeTextCandidates(branches, fragments, null).mayMatch())
        assertFalse(DeclaredTypeTextCandidates(branches.take(2), fragments, null).mayMatch())
    }

    @Test
    fun `memoized shared DAG scans each row once and accounts every reference`() {
        val shared = listOf(
            DeclaredType("class", "Leaf"),
            DeclaredType("class", "Pair", arguments = listOf(0, 0)),
            DeclaredType("class", "Pair", arguments = listOf(1, 1))
        )
        var work = 0
        assertFalse(DeclaredTypeTextCandidates(shared, listOf("Missing"), GraphWorkConsumer { work++ }).mayMatch())
        assertEquals(shared.size + 4, work)
        assertFalse(DeclaredTypeTextCandidates(emptyList(), listOf("Missing"), null).mayMatch())
    }

    @Test
    fun `metadata work propagates consumer failure and thread interruption`() {
        val marker = IllegalStateException("budget")
        assertSame(marker, assertFailsWith<IllegalStateException> {
            DeclaredTypeTextCandidates(types, listOf("Missing"), GraphWorkConsumer { throw marker }).mayMatch()
        })
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<CancellationException> { candidates("Missing") }
        } finally {
            Thread.interrupted()
        }
    }

    private fun assertSubstringCandidates(text: String, rows: List<DeclaredType>, checked: MutableSet<List<String>>) {
        for (start in text.indices) {
            for (end in start + 1..text.length) {
                val fragments = propertyTextFragments(text.substring(start, end))
                if (fragments.isNotEmpty() && checked.add(fragments)) {
                    assertTrue(DeclaredTypeTextCandidates(rows, fragments, null).mayMatch(), fragments.toString())
                }
            }
        }
    }

    private fun candidates(needle: String): Boolean =
        DeclaredTypeTextCandidates(types, propertyTextFragments(needle), null).mayMatch()
}
