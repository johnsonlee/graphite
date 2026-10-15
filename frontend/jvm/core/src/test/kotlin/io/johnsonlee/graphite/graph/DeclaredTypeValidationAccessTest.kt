package io.johnsonlee.graphite.graph

import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeclaredTypeValidationAccessTest {
    private val leaf = DeclaredType("class", "Leaf")

    @Test
    fun `shared shape rules accept incidental fields and reject every forbidden field`() {
        val valid = listOf(
            leaf.copy(scope = "allowed", owner = 0, arguments = listOf(0)),
            DeclaredType("primitive", "int", scope = "allowed"),
            DeclaredType("array", "allowed", "allowed", component = 0),
            DeclaredType("variable", "T", "scope"),
            DeclaredType("wildcard", "allowed", "allowed", component = 0, variance = "extends"),
            DeclaredType("wildcard", component = 0, variance = "super"),
            DeclaredType("wildcard", variance = "unbounded")
        ) + listOf("boolean", "byte", "char", "short", "long", "float", "double", "void")
            .map { DeclaredType("primitive", it) }
        valid.forEach { assertAccepted(listOf(leaf, it)) }
        val invalid = listOf(
            leaf.copy(kind = "Class"), leaf.copy(name = ""), leaf.copy(component = 0), leaf.copy(variance = "super"),
            DeclaredType("primitive", "integer"), DeclaredType("array"), DeclaredType("variable", "", "scope"),
            DeclaredType("variable", "T"), DeclaredType("wildcard", variance = "extends"),
            DeclaredType("wildcard", variance = "super"), DeclaredType("wildcard", variance = ""),
            DeclaredType("wildcard", variance = "superficial"), DeclaredType("wildcard", variance = "unbounded", component = 0)
        ) + forbiddenFields()
        invalid.forEach { assertRejected(listOf(leaf, it), "Invalid graph.types ${it.kind} expression") }
    }

    @Test
    fun `all negative and out of range references fail before traversal without decoding`() {
        for (id in listOf(-2, -1, 2, Int.MAX_VALUE)) {
            assertRejected(listOf(leaf, leaf.copy(owner = id)), "Invalid graph.types type ID $id")
            assertRejected(listOf(leaf, DeclaredType("array", component = id)), "Invalid graph.types type ID $id")
            assertRejected(listOf(leaf, leaf.copy(arguments = listOf(id))), "Invalid graph.types type ID $id")
        }
        assertRejected(listOf(leaf.copy(owner = 0)), "Cyclic or excessive graph.types nesting")
        assertRejected(listOf(DeclaredType("array", component = 1), DeclaredType("array", component = 0)),
            "Cyclic or excessive graph.types nesting")
    }

    @Test
    fun `exact depth and expanded node limits preserve shared edge multiplicity`() {
        val depth = mutableListOf(DeclaredType("primitive", "int"))
        repeat(DeclaredTypeTable.MAX_DEPTH - 1) { depth.add(DeclaredType("array", component = depth.lastIndex)) }
        assertAccepted(depth)
        depth.add(DeclaredType("array", component = depth.lastIndex))
        assertRejected(depth, "Cyclic or excessive graph.types nesting", "Excessive graph.types nesting")
        val small = DeclaredType("class", "A")
        assertAccepted(listOf(small, small.copy(arguments = List(99_999) { 0 })))
        assertRejected(listOf(small, small.copy(arguments = List(100_000) { 0 })), "Excessive graph.types projection expansion")
        // The owner repeats the same child as every argument and must be counted separately.
        assertRejected(listOf(small, small.copy(owner = 0, arguments = List(99_999) { 0 })),
            "Excessive graph.types projection expansion")
        val shared = mutableListOf(small)
        repeat(17) { shared.add(small.copy(arguments = listOf(shared.lastIndex, shared.lastIndex))) }
        assertRejected(shared, "Excessive graph.types projection expansion")
    }

    @Test
    fun `byte limits count all text UTF8 and retain ordinary malformed surrogate behavior`() {
        val names = listOf("a".repeat(999_995), "界".repeat(333_331) + "ab", "🚀".repeat(249_998) + "abc")
        names.forEach { name ->
            assertAccepted(listOf(leaf.copy(name = name)))
            assertRejected(listOf(leaf.copy(name = name + "x")), "Excessive graph.types projection expansion")
        }
        assertAccepted(listOf(DeclaredType("class", "A", scope = "s".repeat(999_994))))
        assertRejected(listOf(DeclaredType("class", "A", scope = "s".repeat(999_995))),
            "Excessive graph.types projection expansion")
        assertAccepted(listOf(leaf.copy(name = "\uD800")))
        assertRejected(listOf(leaf.copy(name = "界".repeat(200_000)), leaf.copy(arguments = listOf(0, 0))),
            "Excessive graph.types projection expansion")
    }

    @Test
    fun `concurrent fresh validations use semantic access and projection preserves values`() {
        val values = listOf(
            DeclaredType("variable", "T", "class:Box"),
            DeclaredType("class", "Box", arguments = listOf(0)),
            DeclaredType("class", "Box\$Inner", owner = 1, arguments = listOf(0)),
            DeclaredType("array", component = 2)
        )
        val access = AccessList(values)
        val shared = table(access)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val work = List(32) { pool.submit { shared.validate(); shared.copy().validate() } }
            work.forEach { it.get() }
        } finally { pool.shutdownNow() }
        access.allowDecoding = true
        assertEquals("Box<T>.Inner<T>[]", shared.render(3))
        assertEquals(table(values).info(3), shared.info(3))
    }

    private fun forbiddenFields(): List<DeclaredType> {
        val primitive = DeclaredType("primitive", "int")
        val array = DeclaredType("array", component = 0)
        val variable = DeclaredType("variable", "T", "scope")
        val wildcard = DeclaredType("wildcard", variance = "unbounded")
        return listOf(
            primitive.copy(owner = 0), primitive.copy(component = 0), primitive.copy(arguments = listOf(0)),
            primitive.copy(variance = "super"), array.copy(owner = 0), array.copy(arguments = listOf(0)),
            array.copy(variance = "super"), variable.copy(owner = 0), variable.copy(component = 0),
            variable.copy(arguments = listOf(0)), variable.copy(variance = "super"),
            wildcard.copy(owner = 0), wildcard.copy(arguments = listOf(0))
        )
    }

    private fun assertAccepted(types: List<DeclaredType>) {
        table(types).validate()
        table(AccessList(types)).validate()
    }

    private fun assertRejected(types: List<DeclaredType>, vararg messages: String) {
        val ordinary = assertFailsWith<IllegalArgumentException> { table(types).validate() }
        val accessed = assertFailsWith<IllegalArgumentException> { table(AccessList(types)).validate() }
        assertEquals(ordinary.message, accessed.message)
        assertEquals(true, ordinary.message in messages)
    }

    private fun table(types: List<DeclaredType>) = DeclaredTypeTable(types, emptyMap(), emptyMap(), emptyMap())

    private class AccessList(private val values: List<DeclaredType>) : AbstractList<DeclaredType>(),
        DeclaredTypeValidationAccess by OrdinaryDeclaredTypeValidationAccess(values) {
        var allowDecoding = false
        override val size: Int get() = values.size
        override fun get(index: Int): DeclaredType {
            check(allowDecoding) { "Validation decoded a declared type row" }
            return values[index]
        }
    }
}
