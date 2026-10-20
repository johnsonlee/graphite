package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.MemberTypeKey
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeclaredTypeTextSummaryTest {
    @Test
    fun `all ASCII substrings including single and paired characters remain candidates`() {
        val text = "AZaz_019OwnerComponent"
        val summary = summary(text)
        for (start in text.indices) assertSubstrings(summary, text, start)
        assertTrue(summary.mayContain("Owner"))
        assertTrue(summary.mayContain("Component"))
        assertFalse(summary.mayContain("Q"))
        assertFalse(summary.mayContain("ZQ"))
        assertFalse(summary.mayContain("OWNER"))
    }

    @Test
    fun `generated map keys are present and Unicode punctuation never joins identifier runs`() {
        val summary = summary("A🚀B", "X.Y", "Z", "Q")
        for (key in listOf("kind", "name", "scope", "owner", "component", "variance", "arguments")) {
            assertTrue(summary.mayContain(key), key)
        }
        assertTrue(summary.mayContain("A"))
        assertTrue(summary.mayContain("B"))
        assertFalse(summary.mayContain("AB"))
        assertFalse(summary.mayContain("XY"))
        assertFalse(summary.mayContain("ZQ"))
        assertTrue(summary.mayContain("unsupported.needle"))
        assertTrue(summary.mayContain("中文"))
    }

    @Test
    fun `distributed triples and fully saturated summaries only produce conservative positives`() {
        val split = summary("ABC", "BCD")
        assertTrue(split.mayContain("ABCD"))
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_0123456789"
        val builder = DeclaredTypeTextSummary.Builder()
        repeat(alphabet.length * alphabet.length * alphabet.length) { code ->
            builder.beginText()
            builder.add(alphabet[code / (alphabet.length * alphabet.length)])
            builder.add(alphabet[code / alphabet.length % alphabet.length])
            builder.add(alphabet[code % alphabet.length])
        }
        val saturated = builder.build()
        for (fragment in listOf("Q", "QZ", MISSING, "Two_Fragment42")) {
            assertTrue(saturated.mayContain(fragment), fragment)
        }
    }

    @Test
    fun `frozen summary cannot change when the builder receives more texts`() {
        val builder = DeclaredTypeTextSummary.Builder()
        builder.beginText()
        "Present".forEach(builder::add)
        val frozen = builder.build()
        builder.beginText()
        MISSING.forEach(builder::add)
        assertFalse(frozen.mayContain(MISSING))
        assertTrue(builder.build().mayContain(MISSING))
    }

    @Test
    fun `summary construction and lookup retain interruption even without a work consumer`() {
        val summary = summary("Existing")
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<CancellationException> { summary.mayContain(MISSING.repeat(1000)) }
            assertFailsWith<CancellationException> { DeclaredTypeTextSummary.Builder() }
            assertFailsWith<CancellationException> { declaredTextMayMatch(DeclaredTypeTable.EMPTY, listOf("Missing")) }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `mapped summary is scoped to the full immutable backing and never borrowed by mutable copies`() = withTable { table ->
        assertFalse(declaredTextMayMatch(table, listOf(MISSING)))
        assertTrue(declaredTextMayMatch(table, listOf("DeclarationOnly", "arguments")))
        val mutableTypes = table.types.toMutableList()
        val copies = listOf(
            table.copy(types = mutableTypes), table.copy(fields = table.fields.toMutableMap()),
            table.copy(methods = table.methods.toMutableMap()), table.copy(classes = table.classes.toMutableMap())
        )
        copies.forEach { assertTrue(declaredTextMayMatch(it, listOf(MISSING))) }
        mutableTypes[0] = DeclaredType("class", MISSING)
        assertEquals(MISSING, copies[0].render(0))
        assertTrue(declaredTextMayMatch(copies[0], listOf(MISSING)))
        assertFalse(declaredTextMayMatch(table, listOf(MISSING)))
    }

    @Test
    fun `mutable and legacy tables remain conservative without constructing a query time index`() {
        val mutable = mutableListOf(DeclaredType("class", "Before"))
        val table = DeclaredTypeTable(mutable, emptyMap(), emptyMap(), emptyMap())
        assertTrue(declaredTextMayMatch(table, listOf("After")))
        mutable[0] = DeclaredType("class", "After")
        assertTrue(declaredTextMayMatch(table, listOf("After")))
        withTable { mapped ->
            val directory = Files.createTempDirectory("declared-summary-legacy")
            try {
                Files.writeString(directory.resolve("graph.metadata"), "metadata")
                DeclaredTypeStore.saveLegacyV2(mapped, directory)
                val legacy = DeclaredTypeStore.load(directory)
                assertEquals(mapped, legacy)
                assertTrue(declaredTextMayMatch(legacy, listOf(MISSING)))
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    }

    private companion object {
        const val MISSING = "MissingNeedle"
    }

    private fun assertSubstrings(summary: DeclaredTypeTextSummary, text: String, start: Int) {
        for (end in start + 1..text.length) assertTrue(summary.mayContain(text.substring(start, end)))
    }

    private fun summary(vararg values: String): DeclaredTypeTextSummary = DeclaredTypeTextSummary.Builder().apply {
        for (value in values) {
            beginText()
            value.forEach(::add)
        }
    }.build()

    private fun withTable(block: (DeclaredTypeTable) -> Unit) {
        val directory = Files.createTempDirectory("declared-summary")
        try {
            val table = DeclaredTypeTable(listOf(DeclaredType("class", "DeclarationOnly")),
                mapOf(MemberTypeKey("Owner", "field", "Ljava/lang/Object;") to 0), emptyMap(), emptyMap())
            GraphStore.save(DefaultGraph.Builder().apply { setDeclaredTypes(table) }.build(), directory)
            block(DeclaredTypeStore.load(directory))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
