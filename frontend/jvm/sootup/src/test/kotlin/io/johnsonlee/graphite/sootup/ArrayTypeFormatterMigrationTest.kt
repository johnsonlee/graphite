package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.input.LoaderConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import sootup.core.jimple.common.Local
import sootup.core.types.ArrayType
import sootup.core.types.Type
import sootup.java.core.JavaIdentifierFactory
import sootup.java.core.jimple.basic.JavaLocal
import sootup.java.core.views.JavaView

class ArrayTypeFormatterMigrationTest {
    private val factory = JavaIdentifierFactory.getInstance()

    @Test
    fun `production formatter and descriptor preserve each JVM array rank`() {
        val adapter = adapter()
        val names = listOf("boolean", "byte", "char", "short", "int", "long", "float", "double", "p.Outer\$Inner")
        for (name in names) {
            val base = factory.getType(name)
            assertEquals(name, graphTypeName(base))
            assertEquals(TypeDescriptor(name), descriptor(adapter, base))
            for (rank in 1..MAX_JVM_ARRAY_RANK) {
                val expected = name + "[]".repeat(rank)
                val type = ArrayType(base, rank)
                assertEquals(expected, graphTypeName(type), "$name rank $rank")
                assertEquals(TypeDescriptor(expected), descriptor(adapter, type))
            }
        }
    }

    @Test
    fun `nested ArrayType preserves every layer instead of assuming a flat base`() {
        val nested = ArrayType(ArrayType(factory.getType("byte"), 2), 3)
        // ArrayType's public constructor permits nested bases. The old function
        // produced byte[][] here, not byte[]; a universal flat projection is invalid.
        assertEquals("byte[][][][][]", graphTypeName(nested))
        assertEquals(TypeDescriptor("byte[][][][][]"), descriptor(adapter(), nested))
    }

    @Test
    fun `descriptor cache preserves dimension and element identity across Type objects`() {
        val adapter = adapter()
        val type = ArrayType(factory.getType("byte"), 2)
        val first = descriptor(adapter, type)
        assertSame(first, descriptor(adapter, type))
        assertEquals(first, descriptor(adapter, ArrayType(factory.getType("byte"), 2)))
        val otherRank = descriptor(adapter, ArrayType(factory.getType("byte"), 3))
        val otherBase = descriptor(adapter, ArrayType(factory.getType("int"), 2))
        assertNotSame(first, otherRank)
        assertNotSame(first, otherBase)
        assertEquals("byte[][][]", otherRank.className)
        assertEquals("int[][]", otherBase.className)
    }

    @Test
    fun `local cache keeps first encounter and separates full caller identities`() {
        val builder = DefaultGraph.Builder()
        val adapter = adapter(builder)
        val caller = caller("p.Owner", "int[]")
        val original = local(adapter, caller, "same", ArrayType(factory.getType("byte"), 2))
        assertSame(original, local(adapter, caller, "same", ArrayType(factory.getType("byte"), 3)))
        assertEquals("byte[][]", original.type.className)
        val otherRank = local(
            adapter, caller("p.Owner", "int[][]"), "same", ArrayType(factory.getType("byte"), 3)
        )
        val otherOwner = local(
            adapter, caller("p.Other", "int[]"), "same", ArrayType(factory.getType("int"), 2)
        )
        assertNotEquals(original.id, otherRank.id)
        assertNotEquals(original.id, otherOwner.id)
        assertEquals("byte[][][]", otherRank.type.className)
        assertEquals("int[][]", otherOwner.type.className)
        assertSame(caller, original.method)
        assertEquals(setOf(original, otherRank, otherOwner), builder.build().nodes(LocalVariable::class.java).toSet())
    }

    private fun adapter(builder: DefaultGraph.Builder = DefaultGraph.Builder()) = SootUpAdapter(
        view = JavaView(emptyList()),
        config = LoaderConfig(buildCallGraph = false),
        extensions = emptyList(),
        inputLocationSources = emptyMap(),
        graphBuilder = builder
    )

    private fun caller(owner: String, parameter: String) = MethodDescriptor(
        TypeDescriptor(owner), "m", listOf(TypeDescriptor(parameter)), TypeDescriptor("void")
    )

    private fun descriptor(adapter: SootUpAdapter, type: Type): TypeDescriptor {
        val method = SootUpAdapter::class.java.getDeclaredMethod("toTypeDescriptor", Type::class.java)
        method.isAccessible = true
        return method.invoke(adapter, type) as TypeDescriptor
    }

    private fun local(adapter: SootUpAdapter, caller: MethodDescriptor, name: String, type: Type): LocalVariable {
        val method = SootUpAdapter::class.java.getDeclaredMethod(
            "getOrCreateLocal", Local::class.java, MethodDescriptor::class.java
        )
        method.isAccessible = true
        return method.invoke(adapter, JavaLocal(name, type, emptyList()), caller) as LocalVariable
    }

    private companion object {
        const val MAX_JVM_ARRAY_RANK = 255
    }
}
