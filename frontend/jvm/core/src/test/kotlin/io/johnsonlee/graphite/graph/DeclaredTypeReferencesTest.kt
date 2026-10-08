package io.johnsonlee.graphite.graph

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeclaredTypeReferencesTest {
    private val key = MemberTypeKey("Owner", "echo", "()Ljava/lang/Object;")
    private val types = listOf(DeclaredType("class", "java.lang.Object"), DeclaredType("array", component = 0))

    @Test
    fun `compact maps validate references without requesting decoded values and validate once across readers`() {
        val fields = References<MemberTypeKey, Int>(intArrayOf(0, 0))
        val methods = References<MemberTypeKey, MethodTypes>(intArrayOf(0, 1, 1))
        val classes = References<String, ClassTypes>(intArrayOf(1, 0))
        val table = DeclaredTypeTable(types, fields, methods, classes)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = List(32) {
                pool.submit<String> {
                    table.validate()
                    assertEquals("java.lang.Object", (table.info(1)["component"] as Map<*, *>)["name"])
                    table.render(1)
                }
            }
            tasks.forEach { assertEquals("java.lang.Object[]", it.get()) }
        } finally { pool.shutdownNow() }
        assertEquals(1, fields.calls.get())
        assertEquals(2, fields.lastCount)
        assertEquals(1, methods.calls.get())
        assertEquals(1, classes.calls.get())
        assertEquals(2, methods.lastCount)
        assertEquals(2, classes.lastCount)
        // A copied table must validate again with its own count, not trust the map's prior success.
        assertFailsWith<IllegalArgumentException> { table.copy(types = types.take(1)).validate() }
        assertEquals(2, fields.calls.get())
        assertEquals(1, fields.lastCount)
        assertEquals(1, methods.lastCount)
    }

    @Test
    fun `ordinary and compact fields reject every bad reference and recheck copied bounds`() {
        val last = key.copy(name = "last")
        for (bad in listOf(-1, types.size, Int.MAX_VALUE)) {
            for (ids in listOf(intArrayOf(bad, 0), intArrayOf(0, bad))) {
                val ordinary = linkedMapOf(key to ids[0], last to ids[1])
                val compact = References<MemberTypeKey, Int>(ids)
                for (fields in listOf(ordinary, compact)) {
                    assertFailsWith<IllegalArgumentException> {
                        DeclaredTypeTable(types, fields, emptyMap(), emptyMap()).validate()
                    }
                }
                assertEquals(1, compact.calls.get())
                assertEquals(types.size, compact.lastCount)
            }
        }
        val fields = References<MemberTypeKey, Int>(intArrayOf(0, 1))
        val valid = DeclaredTypeTable(types, fields, emptyMap(), emptyMap())
        valid.validate()
        assertEquals("java.lang.Object[]", valid.render(1))
        assertFailsWith<IllegalArgumentException> { valid.copy(types = types.take(1)).validate() }
        assertEquals(2, fields.calls.get())
        assertEquals(1, fields.lastCount)
        val mixed = valid.copy(methods = mapOf(key to MethodTypes(emptyList(), 0)))
        mixed.validate()
        assertFailsWith<IllegalArgumentException> {
            mixed.copy(methods = mapOf(key to MethodTypes(emptyList(), types.size))).validate()
        }
    }

    @Test
    fun `ordinary maps check every method and class reference including parameter bounds`() {
        val parameter = TypeParameter("T", "class:Owner", listOf(0))
        val method = MethodTypes(listOf(0), 0, listOf(parameter))
        val klass = ClassTypes(listOf(parameter), 0, listOf(0))
        for (bad in listOf(-1, types.size, Int.MAX_VALUE)) {
            for (invalid in listOf(
                method.copy(parameterTypes = listOf(0, bad)), method.copy(returnType = bad),
                method.copy(typeParameters = listOf(parameter.copy(bounds = listOf(0, bad))))
            )) {
                assertFailsWith<IllegalArgumentException> {
                    DeclaredTypeTable(types, emptyMap(), mapOf(key to invalid), emptyMap()).validate()
                }
            }
            for (invalid in listOf(
                klass.copy(superType = bad), klass.copy(interfaces = listOf(0, bad)),
                klass.copy(typeParameters = listOf(parameter.copy(bounds = listOf(0, bad))))
            )) {
                assertFailsWith<IllegalArgumentException> {
                    DeclaredTypeTable(types, emptyMap(), emptyMap(), mapOf("Owner" to invalid)).validate()
                }
            }
        }
        val valid = DeclaredTypeTable(types, mapOf(key to 1), mapOf(key to method), mapOf("Owner" to klass.copy(superType = null)))
        valid.validate()
        assertEquals("java.lang.Object[]", valid.render(1))
    }

    @Test
    fun `mixed compact and ordinary maps retain both validation paths`() {
        val rawMethods = References<MemberTypeKey, MethodTypes>(intArrayOf(0))
        val klass = ClassTypes(emptyList(), null, listOf(0))
        val mixed = DeclaredTypeTable(types, emptyMap(), rawMethods, mapOf("Owner" to klass))
        mixed.validate()
        assertEquals(1, rawMethods.calls.get())
        assertEquals("java.lang.Object[]", mixed.render(1))
        assertFailsWith<IllegalArgumentException> {
            mixed.copy(classes = mapOf("Owner" to klass.copy(interfaces = listOf(types.size)))).validate()
        }
        val rawClasses = References<String, ClassTypes>(intArrayOf(0))
        val inverse = DeclaredTypeTable(types, emptyMap(), mapOf(key to MethodTypes(emptyList(), 0)), rawClasses)
        inverse.validate()
        assertEquals(1, rawClasses.calls.get())
        assertFailsWith<IllegalArgumentException> {
            inverse.copy(methods = mapOf(key to MethodTypes(emptyList(), types.size))).validate()
        }
        DeclaredTypeTable.EMPTY.validate()
    }

    @Test
    fun `compact member path preserves ordinary field and type expression validation`() {
        fun table(expressions: List<DeclaredType>, fields: Map<MemberTypeKey, Int> = emptyMap()) =
            DeclaredTypeTable(expressions, fields, References<MemberTypeKey, MethodTypes>(intArrayOf(0)),
                References<String, ClassTypes>(intArrayOf(0)))
        assertFailsWith<IllegalArgumentException> { table(types, mapOf(key to types.size)).validate() }
        assertFailsWith<IllegalArgumentException> { table(listOf(DeclaredType("variable", "T"))).validate() }
        assertFailsWith<IllegalArgumentException> { table(listOf(DeclaredType("array", component = 0))).validate() }
        val dag = mutableListOf(types[0])
        repeat(17) { dag.add(DeclaredType("class", "Pair", arguments = listOf(dag.lastIndex, dag.lastIndex))) }
        assertFailsWith<IllegalArgumentException> { table(dag).validate() }
        val depth = mutableListOf(types[0])
        repeat(DeclaredTypeTable.MAX_DEPTH) { depth.add(DeclaredType("array", component = depth.lastIndex)) }
        assertFailsWith<IllegalArgumentException> { table(depth).validate() }
        val bytes = listOf(DeclaredType("class", "界".repeat(200_000)), DeclaredType("class", "Pair", arguments = listOf(0, 0)))
        assertFailsWith<IllegalArgumentException> { table(bytes).validate() }
    }

    private class References<K, V>(private val ids: IntArray) : AbstractMap<K, V>(), DeclaredTypeReferences {
        val calls = AtomicInteger()
        var lastCount = -1
        override val entries: Set<Map.Entry<K, V>> get() = error("Decoded entries must not be requested")
        override val values: Collection<V> get() = error("Decoded values must not be requested")
        override fun validateTypeReferences(typeCount: Int) {
            calls.incrementAndGet()
            lastCount = typeCount
            ids.forEach { require(it in 0 until typeCount) }
        }
    }
}
