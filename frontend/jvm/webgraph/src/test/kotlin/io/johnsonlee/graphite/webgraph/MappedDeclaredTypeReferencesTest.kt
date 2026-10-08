package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.ClassTypes
import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeReferences
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.graph.MethodTypes
import io.johnsonlee.graphite.graph.TypeParameter
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MappedDeclaredTypeReferencesTest {
    private val first = MemberTypeKey("Owner", "echo", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
    private val second = first.copy(descriptor = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/String;")
    private val parameters = listOf(
        TypeParameter("T", "class:Owner", listOf(0, 0)),
        TypeParameter("类型🚀", "method:Owner#echo", listOf(0))
    )
    private val table = DeclaredTypeTable(
        listOf(DeclaredType("class", "java.lang.Object"), DeclaredType("class", "java.lang.String")),
        mapOf(MemberTypeKey("Owner", "field", "Ljava/lang/Object;") to 0),
        linkedMapOf(first to MethodTypes(listOf(0, 0), 0, parameters), second to MethodTypes(listOf(0), 0, parameters)),
        linkedMapOf("Owner" to ClassTypes(parameters, 0, listOf(0, 0)), "Empty" to ClassTypes(emptyList(), null, emptyList()))
    )

    @Test
    fun `every mapped member reference is rechecked against supplied count in both formats`() = directory { dir ->
        for (version in 1..2) {
            DeclaredTypeWireFixture.write(dir, table, version)
            val valid = Files.readAllBytes(dir.resolve(DeclaredTypeStore.FILE_NAME))
            val references = positions(valid, version).references.filter { it.section == "method" || it.section == "class" }
            assertEquals(18, references.size)
            for (reference in references) {
                // The first scan and normal core validation accept ID1 with the original two types.
                // A new supplied count of1 must fail at this exact position during raw revalidation.
                replace(dir, valid.copyOf().also { ByteBuffer.wrap(it).putInt(reference.offset, 1) })
                val loaded = DeclaredTypeStore.load(dir)
                val map = if (reference.section == "method") loaded.methods else loaded.classes
                val validator = map as DeclaredTypeReferences
                validator.validateTypeReferences(2)
                val failure = assertFailsWith<IllegalArgumentException>("$version $reference") {
                    validator.validateTypeReferences(1)
                }
                assertTrue(failure.message.orEmpty().contains("reference") || failure.message.orEmpty().contains("type ID"))
            }
        }
    }

    @Test
    fun `all raw reference and count positions still reject malformed bound input before exposure`() = directory { dir ->
        for (version in 1..2) {
            DeclaredTypeWireFixture.write(dir, table, version)
            val valid = Files.readAllBytes(dir.resolve(DeclaredTypeStore.FILE_NAME))
            val positions = positions(valid, version)
            for (reference in positions.references) {
                val invalid = if (reference.optional) listOf(-2, table.types.size, Int.MAX_VALUE)
                    else listOf(-2, -1, table.types.size, Int.MAX_VALUE)
                invalid.forEach { value -> assertCorrupt(dir, valid, reference.offset, value) }
            }
            positions.counts.forEach { offset ->
                for (invalid in listOf(-1, Int.MAX_VALUE)) assertCorrupt(dir, valid, offset, invalid)
            }
            replace(dir, valid)
            val restored = DeclaredTypeStore.load(dir)
            assertEquals(table, restored)
            assertEquals(null, restored.classes.getValue("Empty").superType)
        }
    }

    @Test
    fun `concurrent reference validation and decoded iteration preserve full values in both formats`() = directory { dir ->
        for (version in 1..2) {
            DeclaredTypeWireFixture.write(dir, table, version)
            val restored = DeclaredTypeStore.load(dir)
            assertConcurrentProjection(restored)
            assertEquals(table, restored)
            assertEquals(table.methods.keys.toList(), restored.methods.keys.toList())
        }
    }

    private fun assertConcurrentProjection(restored: DeclaredTypeTable) {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = List(32) {
                pool.submit {
                    (restored.methods as DeclaredTypeReferences).validateTypeReferences(table.types.size)
                    (restored.classes as DeclaredTypeReferences).validateTypeReferences(table.types.size)
                    assertEquals(table.methods.entries.toList(), restored.methods.entries.toList())
                    assertEquals(table.classes.values.toList(), restored.classes.values.toList())
                    assertEquals(table.methods[first], restored.methods[first])
                    assertEquals(table.info(1), restored.info(1))
                }
            }
            tasks.forEach { it.get() }
        } finally { pool.shutdownNow() }
    }

    private data class Reference(val offset: Int, val section: String, val optional: Boolean)
    private data class Positions(val references: List<Reference>, val counts: List<Int>)

    /** Independent test-only wire walk; captures every reference and every collection count. */
    private fun positions(bytes: ByteArray, version: Int): Positions {
        val input = ByteBuffer.wrap(bytes).apply { position(36) }
        val references = mutableListOf<Reference>()
        val counts = mutableListOf<Int>()
        fun count(): Int { counts.add(input.position()); return input.int }
        fun inline() { val length = input.int; input.position(input.position() + length) }
        if (version == 2) repeat(count()) { inline() }
        fun text() { if (version == 1) inline() else input.int }
        fun reference(section: String, optional: Boolean = false) {
            references.add(Reference(input.position(), section, optional)); input.int
        }
        fun refs(section: String) { repeat(count()) { reference(section) } }
        fun parameters(section: String) { repeat(count()) { text(); text(); refs(section) } }
        repeat(count()) { text(); text(); text(); reference("type", true); reference("type", true); text(); refs("type") }
        repeat(count()) { text(); text(); text(); reference("field") }
        repeat(count()) { text(); text(); text(); refs("method"); reference("method"); parameters("method") }
        repeat(count()) { text(); parameters("class"); reference("class", true); refs("class") }
        assertEquals(bytes.size, input.position())
        return Positions(references, counts)
    }

    private fun assertCorrupt(dir: Path, valid: ByteArray, offset: Int, value: Int) {
        replace(dir, valid.copyOf().also { ByteBuffer.wrap(it).putInt(offset, value) })
        assertFailsWith<IllegalArgumentException>("offset=$offset value=$value") { DeclaredTypeStore.load(dir) }
    }

    private fun replace(dir: Path, bytes: ByteArray) {
        Files.write(dir.resolve(DeclaredTypeStore.FILE_NAME), bytes)
        DeclaredTypeWireFixture.rebind(dir)
    }

    private fun directory(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("declared-reference-validation")
        try { Files.writeString(dir.resolve("graph.metadata"), "binding"); block(dir) }
        finally { dir.toFile().deleteRecursively() }
    }
}
