package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.DeclaredTypeTextField
import io.johnsonlee.graphite.graph.DeclaredTypeValidationAccess
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MappedDeclaredTypeValidationAccessTest {
    private val types = listOf(
        DeclaredType("class", "泛型🚀.Outer\$Inner", owner = 5, arguments = listOf(2, 3, 4)),
        DeclaredType("variable", "T", "class:泛型🚀.Outer"),
        DeclaredType("wildcard", component = 1, variance = "extends"),
        DeclaredType("wildcard", component = 1, variance = "super"),
        DeclaredType("wildcard", variance = "unbounded"),
        DeclaredType("class", "泛型🚀.Outer", scope = "allowed", arguments = listOf(1)),
        DeclaredType("array", "allowed", "allowed", component = 0),
        DeclaredType("primitive", "void")
    )

    @Test
    fun `all semantic fields match decoded values in both formats without row materialization`() = directory { dir ->
        for (version in 1..2) {
            val restored = load(dir, types, version)
            val access = restored.types as DeclaredTypeValidationAccess
            assertEquals(types.size, access.size)
            types.indices.forEach { id -> assertFields(access, id) }
            restored.copy(types = GuardedTypes(restored.types)).validate()
            assertEquals("泛型🚀.Outer<T>.Inner<? extends T, ? super T, ?>[]", restored.render(6))
            assertEquals(table(types).info(6), restored.info(6))
            assertEquals(types, restored.types)
            assertFailsWith<IndexOutOfBoundsException> { access.text(-1, DeclaredTypeTextField.KIND) }
            assertFailsWith<IndexOutOfBoundsException> { access.owner(types.size) }
            assertFailsWith<IndexOutOfBoundsException> { access.argument(0, -1) }
            assertFailsWith<IndexOutOfBoundsException> { access.argument(0, types[0].arguments.size) }
        }
    }

    @Test
    fun `invalid type shapes and cycles are rejected by the shared rules before returning a table`() = directory { dir ->
        val invalid = listOf(
            listOf(DeclaredType("クラス", "Thing")),
            listOf(DeclaredType("primitive", "inte")),
            listOf(DeclaredType("class")),
            listOf(DeclaredType("variable", "T")),
            listOf(DeclaredType("array")),
            listOf(DeclaredType("wildcard", variance = "superficial")),
            listOf(DeclaredType("wildcard", variance = "extends")),
            listOf(DeclaredType("wildcard", variance = "unbounded", component = 0)),
            listOf(DeclaredType("class", "Loop", owner = 0)),
            listOf(DeclaredType("array", component = 1), DeclaredType("array", component = 0))
        )
        for (version in 1..2) invalid.forEach { assertSameRejection(dir, it, version) }
    }

    @Test
    fun `mapped limits count UTF8 bytes repeated arguments owners and depth in both formats`() = directory { dir ->
        val leaf = DeclaredType("class", "A")
        val depth = mutableListOf(leaf)
        repeat(DeclaredTypeTable.MAX_DEPTH - 1) { depth.add(DeclaredType("array", component = depth.lastIndex)) }
        for (version in 1..2) {
            val bounded = listOf(leaf.copy(name = "界".repeat(333_331) + "ab"))
            assertEquals(bounded, load(dir, bounded, version).types)
            assertSameRejection(dir, listOf(bounded.single().copy(scope = "x")), version)
            val repeated = listOf(leaf, leaf.copy(arguments = List(99_999) { 0 }))
            val repeatedTable = load(dir, repeated, version)
            repeatedTable.copy(types = GuardedTypes(repeatedTable.types)).validate()
            assertSameRejection(dir, listOf(leaf, repeated[1].copy(owner = 0)), version)
            assertEquals(depth, load(dir, depth, version).types)
            assertSameRejection(dir, depth + DeclaredType("array", component = depth.lastIndex), version)
        }
    }

    @Test
    fun `concurrent independent validation and queries cannot disturb absolute read positions`() = directory { dir ->
        for (version in 1..2) {
            val restored = load(dir, types, version)
            val expected = table(types).info(6)
            val pool = Executors.newFixedThreadPool(4)
            try {
                val work = List(24) {
                    pool.submit {
                        restored.copy(types = GuardedTypes(restored.types)).validate()
                        assertEquals(expected, restored.info(6))
                        assertEquals("泛型🚀.Outer<T>.Inner<? extends T, ? super T, ?>[]", restored.render(6))
                    }
                }
                work.forEach { it.get() }
            } finally { pool.shutdownNow() }
        }
    }

    private fun assertFields(access: DeclaredTypeValidationAccess, id: Int) {
        val type = types[id]
        val texts = listOf(type.kind, type.name, type.scope, type.variance)
        DeclaredTypeTextField.entries.forEachIndexed { index, field ->
            val expected = texts[index]
            assertEquals(expected, access.text(id, field))
            assertEquals(expected.isEmpty(), access.textIsEmpty(id, field))
            assertEquals(expected.toByteArray(Charsets.UTF_8).size, access.textUtf8Length(id, field))
            assertTrue(access.textEquals(id, field, expected))
            assertFalse(access.textEquals(id, field, expected + "x"))
            assertFalse(access.textEquals(id, field, "不存在🚀"))
            if (expected.isNotEmpty()) assertFalse(access.textEquals(id, field, expected.dropLast(1)))
            assertFalse(access.textEquals(id, field, "X".repeat(expected.length.coerceAtLeast(1))))
        }
        assertEquals(type.owner, access.owner(id))
        assertEquals(type.component, access.component(id))
        assertEquals(type.arguments.size, access.argumentCount(id))
        assertEquals(type.arguments, List(access.argumentCount(id)) { access.argument(id, it) })
    }

    private fun assertSameRejection(dir: Path, types: List<DeclaredType>, version: Int) {
        val expected = assertFailsWith<IllegalArgumentException> { table(types).validate() }
        DeclaredTypeWireFixture.write(dir, table(types), version)
        val actual = assertFailsWith<IllegalArgumentException> { DeclaredTypeStore.load(dir) }
        assertEquals(expected.message, actual.message)
    }

    private fun load(dir: Path, types: List<DeclaredType>, version: Int): DeclaredTypeTable {
        DeclaredTypeWireFixture.write(dir, table(types), version)
        return DeclaredTypeStore.load(dir)
    }

    private fun table(types: List<DeclaredType>) = DeclaredTypeTable(types, emptyMap(), emptyMap(), emptyMap())

    private class GuardedTypes(private val values: List<DeclaredType>) : AbstractList<DeclaredType>(),
        DeclaredTypeValidationAccess by (values as DeclaredTypeValidationAccess) {
        override val size: Int get() = values.size
        override fun get(index: Int): DeclaredType = error("Validation decoded row $index")
    }

    private fun directory(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("declared-type-validation-access")
        try { Files.writeString(dir.resolve("graph.metadata"), "binding"); block(dir) }
        finally { dir.toFile().deleteRecursively() }
    }
}
