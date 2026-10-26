package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.ClassTypes
import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.graph.MethodTypes
import io.johnsonlee.graphite.graph.TypeParameter
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeclaredTypeSavePlanTest {
    private val owner = "fixture.Owner"
    private val scope = "class:$owner"
    private val descriptor = "Ljava/util/List;"
    private val table = DeclaredTypeTable(
        listOf(
            DeclaredType("class", "java.lang.Object"),
            DeclaredType("variable", "T", scope),
            DeclaredType("class", "java.util.List", arguments = listOf(1)),
            DeclaredType("class", "java.util.List")
        ),
        mapOf(MemberTypeKey(owner, "values", descriptor) to 2),
        mapOf(MemberTypeKey(owner, "echo", "($descriptor)$descriptor") to MethodTypes(listOf(2), 2)),
        mapOf(owner to ClassTypes(listOf(TypeParameter("T", scope, listOf(0))), 0, emptyList()))
    )

    @Test
    fun `prepared write reads each type only for serialization and releases its indexes`() = directory { dir ->
        val reads = IntArray(table.types.size)
        val observed = table.copy(types = object : AbstractList<DeclaredType>() {
            override val size = table.types.size
            override fun get(index: Int): DeclaredType { reads[index]++; return table.types[index] }
        })
        observed.validate()
        val names = linkedSetOf<String>()
        val plan = DeclaredTypeStore.collectStrings(observed, names)
        val strings = StringTable.build(names, dir, true)
        reads.fill(0)
        DeclaredTypeStore.save(plan, dir, strings)
        assertContentEquals(IntArray(reads.size) { 1 }, reads)
        assertNull(plan.structural)
        assertNull(plan.erased)
        assertFailsWith<IllegalStateException> { DeclaredTypeStore.save(plan, dir, strings) }
        assertEquals(table, DeclaredTypeStore.load(dir))
    }

    @Test
    fun `prepared dictionary and wire share capped and fallback format decisions`() = directory { root ->
        val missingRaw = table.copy(types = table.types.dropLast(1))
        val opaque = table.copy(types = table.types.map { if (it.kind == "variable") it.copy(scope = "opaque") else it })
        val cases = listOf(
            Triple(table, 5, 5), Triple(table, 4, 4), Triple(table, 3, 3),
            Triple(missingRaw, 5, 4), Triple(opaque, 5, 3)
        )
        cases.forEachIndexed { index, (value, maximum, expected) ->
            val dir = Files.createDirectory(root.resolve(index.toString()))
            Files.writeString(dir.resolve("graph.metadata"), "binding")
            val names = linkedSetOf<String>()
            val plan = DeclaredTypeStore.collectStrings(value, names, maximum)
            assertEquals(expected < 5, descriptor in names)
            assertEquals(expected < 4, value.types[1].scope in names)
            assertEquals(expected < 4, "class" in names)
            val strings = StringTable.build(names, dir, true)
            DeclaredTypeStore.save(plan, dir, strings)
            assertEquals(HEADER_BASE + expected, header(dir))
            assertEquals(value, DeclaredTypeStore.load(dir))
            assertEquals(value.render(2), DeclaredTypeStore.load(dir).render(2))
        }
    }

    @Test
    fun `GraphStore reuses its format plan and upgrades both loaders when resaving the same directory`() = directory { dir ->
        val reads = IntArray(table.types.size)
        val observed = table.copy(types = object : AbstractList<DeclaredType>() {
            override val size = table.types.size
            override fun get(index: Int): DeclaredType { reads[index]++; return table.types[index] }
        })
        observed.validate()
        val graph = DefaultGraph.Builder().apply { setDeclaredTypes(observed) }.build()
        reads.fill(0)
        GraphStore.save(graph, dir)
        // Scope selection, erased selection, dictionary collection, and wire rows: one pass each.
        assertContentEquals(IntArray(reads.size) { 4 }, reads)
        for (version in 3..5) {
            for (load in listOf({ GraphStore.load(dir) }, { GraphStore.loadMapped(dir) })) {
                saveVersion(graph, dir, version)
                assertEquals(HEADER_BASE + version, header(dir))
                val restored = load()
                try {
                    assertEquals(table, restored.declaredTypes())
                    GraphStore.save(restored, dir)
                    assertEquals(HEADER_BASE + 5, header(dir))
                    assertEquals(table, DeclaredTypeStore.load(dir))
                    assertEquals(-1, StringTable.load(dir).findId(descriptor))
                } finally { (restored as? AutoCloseable)?.close() }
            }
        }
    }

    @Test
    fun `prepared writes retain missing dictionary and malformed UTF16 rejection`() = directory { dir ->
        val names = linkedSetOf<String>()
        val missing = DeclaredTypeStore.collectStrings(table, names)
        assertTrue(names.remove("java.util.List"))
        assertFailsWith<IllegalArgumentException> {
            DeclaredTypeStore.save(missing, dir, StringTable.build(names, dir, true))
        }
        assertNull(missing.erased)
        assertFalse(Files.exists(dir.resolve("graph.types")))
        val malformed = table.copy(fields = mapOf(MemberTypeKey("bad\uD800", "values", descriptor) to 2))
        names.clear()
        val invalidText = DeclaredTypeStore.collectStrings(malformed, names)
        val strings = StringTable.build(names, dir, true)
        assertFailsWith<CharacterCodingException> { DeclaredTypeStore.save(invalidText, dir, strings) }
        assertNull(invalidText.erased)
        assertFalse(Files.exists(dir.resolve("graph.types")))
    }

    @Test
    fun `prepared write still validates the complete expression table before replacing data`() = directory { dir ->
        val names = linkedSetOf<String>()
        val original = DeclaredTypeStore.collectStrings(table, names)
        DeclaredTypeStore.save(original, dir, StringTable.build(names, dir, true))
        val before = Files.readAllBytes(dir.resolve("graph.types"))
        val invalid = table.copy(types = table.types + DeclaredType("array", component = table.types.size))
        names.clear()
        val plan = DeclaredTypeStore.collectStrings(invalid, names)
        assertFailsWith<IllegalArgumentException> {
            DeclaredTypeStore.save(plan, dir, StringTable.build(names, dir, true))
        }
        assertNull(plan.erased)
        assertContentEquals(before, Files.readAllBytes(dir.resolve("graph.types")))
    }

    private fun saveVersion(graph: Graph, dir: Path, version: Int) {
        when (version) {
            3 -> GraphStore.saveLegacyDeclaredTypesV3(graph, dir)
            4 -> GraphStore.saveLegacyDeclaredTypesV4(graph, dir)
            else -> GraphStore.save(graph, dir)
        }
    }

    private fun header(dir: Path): Int = ByteBuffer.wrap(Files.readAllBytes(dir.resolve("graph.types"))).int

    private fun directory(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("declared-type-save-plan")
        try { Files.writeString(dir.resolve("graph.metadata"), "binding"); block(dir) }
        finally { dir.toFile().deleteRecursively() }
    }

    private companion object {
        const val HEADER_BASE = 0x47545900
    }
}
