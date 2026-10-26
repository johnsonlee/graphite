package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.propertyTextFragments
import it.unimi.dsi.lang.MutableString
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SharedDeclaredTypeTextMatcherTest {
    private val types = listOf(
        DeclaredType("class", "OnlyLeft🚀"),
        DeclaredType("class", "OnlyRight"),
        DeclaredType("variable", "T", "method:example.Owner#scope()V"),
        DeclaredType("wildcard", component = 0, variance = "extends"),
        DeclaredType("class", "Pair", arguments = listOf(0, 1, 2, 3, 0)),
        DeclaredType("class", "Pair\$Inner", owner = 4),
        DeclaredType("array", component = 5)
    )

    @Test
    fun `SID facts retain empty values duplicate text and colliding string hashes`() {
        val values = listOf("", "Aa", "BB", "Aa", "🚀AaBB")
        val calls = mutableListOf<Int>()
        var buffer: MutableString? = null
        val matcher = SharedDeclaredTypeTextMatcher(listOf("Aa", "BB")) { id, target ->
            calls += id
            buffer?.let { assertSame(it, target) }
            buffer = target
            target.length(0)
            target.append(values[id])
        }
        repeat(3) {
            assertEquals(listOf(0, 5, 6, 5, 7), values.indices.map(matcher::match))
        }
        assertEquals(values.indices.toList(), calls, "Repeated IDs, including empty text, decode only on misses")
    }

    @Test
    fun `fixed slot eviction and failed decoding never reuse another SID facts`() {
        val values = mapOf(0 to "Left", 1024 to "Right", 2048 to "")
        val calls = mutableListOf<Int>()
        var fail = false
        val marker = IllegalStateException("decode")
        val matcher = SharedDeclaredTypeTextMatcher(listOf("Left", "Right")) { id, target ->
            calls += id
            target.length(0)
            if (fail) throw marker
            target.append(values.getValue(id))
        }
        assertEquals(5, matcher.match(0))
        assertEquals(6, matcher.match(1024))
        assertEquals(6, matcher.match(1024))
        fail = true
        assertSame(marker, assertFailsWith<IllegalStateException> { matcher.match(2048) })
        assertEquals(6, matcher.match(1024), "A failed decode leaves the previous slot usable")
        fail = false
        assertEquals(0, matcher.match(2048))
        assertEquals(5, matcher.match(0))
        assertEquals(listOf(0, 1024, 2048, 2048, 0), calls)
    }

    @Test
    fun `shared ASCII path bypasses length and contains while Unicode keeps decoded fallback`() = mapped(types, 3) { rows ->
        val counted = CountingTypes(rows)
        assertTrue(scan(counted, listOf("OnlyLeft", "OnlyRight")))
        assertFalse(scan(counted, listOf("Missing")))
        assertEquals(0, counted.lengthReads)
        assertEquals(0, counted.containsReads)
        assertEquals(0, counted.decoded)
        assertTrue(scan(counted, listOf("🚀")))
        assertTrue(counted.decoded > 0)
        val atoms = rows as DeclaredTypeAtoms
        assertNotNull(atoms.queryMatcher(listOf("Left")))
        assertNull(atoms.queryMatcher(listOf("one", "two", "three")))
    }

    @Test
    fun `all wire versions preserve keys empty fields two branch fragments and work counts`() {
        val needles = listOf(
            "OnlyLeft, OnlyRight", "owner={kind=class", "component={kind=class", "scope=method",
            "arguments=[{kind=wildcard", "variance=extends", "name=OnlyLeft", "Pair<T>.Inner",
            "Missing", "ONLYLEFT", "generic_type", "type_info"
        )
        for (version in 1..3) mapped(types, version) { rows ->
            val counted = CountingTypes(rows)
            for (needle in needles) {
                val fragments = propertyTextFragments(needle)
                assertEquals(resultAndWork(types, fragments), resultAndWork(counted, fragments), "$version: $needle")
            }
            assertEquals(0, counted.decoded)
            if (version < 3) {
                assertNull((rows as DeclaredTypeAtoms).queryMatcher(listOf("Missing")))
                assertTrue(counted.lengthReads > 0)
                assertTrue(counted.containsReads > 0)
            }
        }
        mapped(listOf(DeclaredType("class", "List")), 3) { rows ->
            for (key in listOf("scope", "variance", "owner", "component")) assertFalse(scan(rows, listOf(key)), key)
            for (key in listOf("kind", "name", "arguments", "")) assertTrue(scan(rows, listOf(key)), key)
        }
    }

    @Test
    fun `rendered and structured delimiter substrings remain conservative across shared DAG`() {
        val shortTypes = listOf(
            DeclaredType("class", "Left"), DeclaredType("class", "Right"),
            DeclaredType("class", "Pair", arguments = listOf(0, 1))
        )
        mapped(shortTypes, 3) { rows ->
            val table = DeclaredTypeTable(shortTypes, emptyMap(), emptyMap(), emptyMap())
            val fragments = mutableSetOf<List<String>>()
            for (text in listOf(table.render(2), table.info(2).toString())) {
                for (start in text.indices) {
                    for (end in start + 1..text.length) {
                        val parts = propertyTextFragments(text.substring(start, end))
                        if (parts.isNotEmpty() && fragments.add(parts)) assertTrue(scan(rows, parts), parts.toString())
                    }
                }
            }
            assertTrue(fragments.size > 100)
        }
    }

    @Test
    fun `concurrent queries isolate same IDs across different graph string tables`() {
        mapped(listOf(DeclaredType("class", "GraphLeftOnly")), 3) { left ->
            mapped(listOf(DeclaredType("class", "GraphRightOnly")), 3) { right ->
                fun nameId(rows: List<DeclaredType>): Int = (rows as DeclaredTypeAtoms).let {
                    it.atomInt(it.nextTextField(it.typeOffset(0)))
                }
                assertEquals(nameId(left), nameId(right), "Graphs deliberately reuse the same SID")
                val pool = Executors.newFixedThreadPool(4)
                try {
                    val futures = (0 until 24).map { index ->
                        pool.submit<Pair<Boolean, Boolean>> {
                            val rows = if (index % 2 == 0) left else right
                            scan(rows, listOf("GraphLeftOnly")) to scan(rows, listOf("GraphRightOnly"))
                        }
                    }
                    assertEquals(
                        (0 until 24).map { if (it % 2 == 0) true to false else false to true },
                        futures.map { it.get(10, TimeUnit.SECONDS) }
                    )
                } finally {
                    pool.shutdownNow()
                }
            }
        }
    }

    @Test
    fun `cache hits preserve every reference budget and interruption during traversal`() = mapped(types, 3) { rows ->
        assertEquals(resultAndWork(types, listOf("Missing")), resultAndWork(rows, listOf("Missing")))
        assertEquals(false to 15, resultAndWork(rows, listOf("Missing")))
        val marker = IllegalStateException("budget")
        var consumed = 0
        assertSame(marker, assertFailsWith<IllegalStateException> {
            DeclaredTypeTextCandidates(rows, listOf("Missing"), GraphWorkConsumer {
                if (++consumed == 4) throw marker
            }).mayMatch()
        })
        assertEquals(4, consumed)
        mapped((0 until 300).map { DeclaredType("class", "Type$it") }, 3) { many ->
            var work = 0
            try {
                assertFailsWith<CancellationException> {
                    DeclaredTypeTextCandidates(many, listOf("Missing"), GraphWorkConsumer {
                        if (++work == 64) Thread.currentThread().interrupt()
                    }).mayMatch()
                }
                assertTrue(work in 64 until many.size)
            } finally {
                Thread.interrupted()
            }
        }
    }

    private fun scan(rows: List<DeclaredType>, fragments: List<String>): Boolean =
        DeclaredTypeTextCandidates(rows, fragments, null).mayMatch()

    private fun resultAndWork(rows: List<DeclaredType>, fragments: List<String>): Pair<Boolean, Int> {
        var work = 0
        return DeclaredTypeTextCandidates(rows, fragments, GraphWorkConsumer { work++ }).mayMatch() to work
    }

    private fun mapped(rows: List<DeclaredType>, version: Int, block: (List<DeclaredType>) -> Unit) {
        val directory = Files.createTempDirectory("shared-type-query")
        try {
            Files.writeString(directory.resolve("graph.metadata"), "binding")
            val table = DeclaredTypeTable(rows, emptyMap(), emptyMap(), emptyMap())
            when (version) {
                1 -> DeclaredTypeWireFixture.write(directory, table, version)
                2 -> DeclaredTypeStore.saveLegacyV2(table, directory)
                else -> {
                    val values = mutableSetOf<String>()
                    DeclaredTypeStore.collectStrings(table, values, maximumVersion = 3)
                    val strings = StringTable.build(values, directory, captureSerializedDigest = true)
                    DeclaredTypeStore.saveLegacyShared(table, directory, strings, version = 3)
                }
            }
            block(DeclaredTypeStore.load(directory).types)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private class CountingTypes(private val rows: List<DeclaredType>) :
        AbstractList<DeclaredType>(), DeclaredTypeAtoms by (rows as DeclaredTypeAtoms) {
        var lengthReads = 0
        var containsReads = 0
        var decoded = 0
        override val size: Int get() = rows.size
        override fun get(index: Int): DeclaredType { decoded++; return rows[index] }
        override fun atomTextLength(position: Int): Int {
            lengthReads++
            return (rows as DeclaredTypeAtoms).atomTextLength(position)
        }
        override fun atomContains(position: Int, fragment: String): Boolean {
            containsReads++
            return (rows as DeclaredTypeAtoms).atomContains(position, fragment)
        }
    }
}
