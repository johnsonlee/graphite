package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.ImmutableDeclaredTypeStorage
import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.graph.MethodTypes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("StringLiteralDuplication")
class ImmutableDeclaredTypePresenceTest {
    private val owner = TypeDescriptor("sample.Owner")
    private val type = TypeDescriptor("java.lang.String")
    private val field = FieldNode(NodeId(1), FieldDescriptor(owner, "value", type), false)
    private val method = MethodDescriptor(owner, "get", emptyList(), type)
    private val key = MemberTypeKey(owner.className, "value", "Ljava/lang/String;")
    private val methodKey = MemberTypeKey(owner.className, method.name, method.descriptor)
    private val table = DeclaredTypeTable(
        listOf(DeclaredType("class", type.className)), mapOf(key to 0),
        mapOf(methodKey to MethodTypes(emptyList(), 0)), emptyMap()
    )

    @Test
    fun `all wire versions authorize only the complete immutable backing tuple`() = directory { dir ->
        for (version in 1..3) {
            val loaded = load(dir, version)
            val storage = loaded.types as ImmutableDeclaredTypeStorage
            assertTrue(storage.isImmutableTable(loaded))
            assertTrue(storage.isImmutableTable(loaded.copy()))
            assertFalse(storage.isImmutableTable(loaded.copy(types = loaded.types.toMutableList())))
            assertFalse(storage.isImmutableTable(loaded.copy(fields = loaded.fields.toMutableMap())))
            assertFalse(storage.isImmutableTable(loaded.copy(methods = loaded.methods.toMutableMap())))
            assertFalse(storage.isImmutableTable(loaded.copy(classes = loaded.classes.toMutableMap())))
            val executor = executor(loaded)
            assertEquals(listOf(mapOf("present" to true, "value" to "java.lang.String")), executor.execute(
                "MATCH (n:Field) RETURN n.type_info IS NOT NULL AS present, n.generic_type AS value"
            ).rows)
            assertEquals(listOf(mapOf("present" to true, "value" to emptyList<String>())), executor.execute(
                "MATCH (m:Method) RETURN m.parameter_type_info IS NOT NULL AS present, m.generic_parameter_types AS value"
            ).rows)
        }
    }

    @Test
    fun `copied mapped tables cannot reuse capability after mutable bindings change`() = directory { dir ->
        for (version in 1..3) {
            val loaded = load(dir, version)
            val fields = loaded.fields.toMutableMap()
            val copy = loaded.copy(fields = fields)
            copy.validate()
            fields[key] = 99
            assertSameFailure(executor(copy), "Field", "type_info")
            assertSameFailure(executor(copy), "Field", "generic_type")

            val params = mutableListOf(0)
            val methodCopy = loaded.copy(methods = mapOf(methodKey to MethodTypes(params, 0)))
            methodCopy.validate()
            params[0] = 99
            assertSameFailure(executor(methodCopy), "Method", "parameter_type_info")
            assertSameFailure(executor(methodCopy), "Method", "generic_parameter_types")
        }
    }

    private fun assertSameFailure(executor: CypherExecutor, label: String, property: String) {
        val full = assertFails { executor.execute("MATCH (n:$label) RETURN n.$property AS value") }
        for (predicate in listOf("IS NULL", "IS NOT NULL")) {
            val presence = assertFails { executor.execute("MATCH (n:$label) RETURN n.$property $predicate AS value") }
            assertEquals(full::class, presence::class)
            assertEquals(full.message, presence.message)
        }
    }

    private fun executor(table: DeclaredTypeTable) = CypherExecutor(DefaultGraph.Builder().apply {
        addNode(field)
        addMethod(method)
        setDeclaredTypes(table)
    }.build())

    private fun load(dir: Path, version: Int): DeclaredTypeTable {
        Files.writeString(dir.resolve("graph.metadata"), "metadata")
        if (version < 3) {
            DeclaredTypeWireFixture.write(dir, table, version)
        } else {
            val strings = linkedSetOf<String>()
            DeclaredTypeStore.collectStrings(table, strings, maximumVersion = 3)
            DeclaredTypeStore.saveLegacyShared(table, dir, StringTable.build(strings, dir, true), version = 3)
        }
        return DeclaredTypeStore.load(dir)
    }

    private fun directory(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("immutable-declaration-presence")
        try { block(dir) } finally { dir.toFile().deleteRecursively() }
    }
}
