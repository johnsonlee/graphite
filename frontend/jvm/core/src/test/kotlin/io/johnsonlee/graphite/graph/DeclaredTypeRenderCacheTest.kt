package io.johnsonlee.graphite.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class DeclaredTypeRenderCacheTest {
    private val rows = listOf(
        DeclaredType("class", "java.lang.String"),
        DeclaredType("class", "java.util.List", arguments = listOf(0)),
        DeclaredType("array", component = 1)
    )
    private val fields = mapOf(MemberTypeKey("example.Box", "items", "Ljava/util/List;") to 1)

    private class Immutable(private val rows: List<DeclaredType>, fields: Map<MemberTypeKey, Int>) :
        AbstractList<DeclaredType>(), ImmutableDeclaredTypeStorage {
        override val immutableFields = fields
        override val immutableMethods: Map<MemberTypeKey, MethodTypes> = emptyMap()
        override val immutableClasses: Map<String, ClassTypes> = emptyMap()
        override val size: Int get() = rows.size
        override fun get(index: Int): DeclaredType = rows[index]
    }

    @Test
    fun `immutable storage renders and projects each type once`() {
        val table = DeclaredTypeTable(Immutable(rows, fields), fields, emptyMap(), emptyMap())
        assertEquals("java.util.List<java.lang.String>[]", table.render(2))
        assertSame(table.render(2), table.render(2))
        assertSame(table.info(2), table.info(2))
        assertEquals(
            mapOf("kind" to "class", "name" to "java.lang.String", "arguments" to emptyList<Any>()), table.info(0)
        )
        val component = table.info(2)["component"] as Map<*, *>
        assertEquals(table.info(0), (component["arguments"] as List<*>).single())
    }

    @Test
    fun `mutable storage is rendered on every read`() {
        val table = DeclaredTypeTable(rows, fields, emptyMap(), emptyMap())
        assertEquals(table.render(2), table.render(2))
        assertNotSame(table.render(2), table.render(2))
        assertEquals(table.info(1), table.info(1))
        assertNotSame(table.info(1), table.info(1))
    }

    @Test
    fun `a copied table over immutable storage is not treated as immutable`() {
        val storage = Immutable(rows, fields)
        val copied = DeclaredTypeTable(storage, HashMap(fields), emptyMap(), emptyMap())
        assertNotSame(copied.render(1), copied.render(1))
    }
}
