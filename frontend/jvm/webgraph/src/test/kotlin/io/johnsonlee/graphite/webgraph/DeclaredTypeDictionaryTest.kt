package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.ClassTypes
import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeTable
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeclaredTypeDictionaryTest {
    private val owner = "example.泛型🚀"
    private val types = listOf(
        DeclaredType("class", "java.lang.Object"),
        DeclaredType("variable", "T", "class:$owner"),
        DeclaredType("class", "java.lang.Comparable", arguments = listOf(1)),
        DeclaredType("class", "$owner.Outer", arguments = listOf(1)),
        DeclaredType("wildcard", component = 1, variance = "extends"),
        DeclaredType("class", "$owner.Outer\$Inner\$Name", owner = 3, arguments = listOf(4)),
        DeclaredType("array", component = 5),
        DeclaredType("primitive", "void"),
        DeclaredType("wildcard", variance = "unbounded"),
        DeclaredType("wildcard", component = 0, variance = "super"),
        DeclaredType("variable", "T", "method:$owner#echo(Ljava/lang/Object;)Ljava/lang/Object;")
    )
    private val table = DeclaredTypeTable(
        types,
        linkedMapOf(MemberTypeKey(owner, "Aa", "Ljava/lang/Object;") to 1, MemberTypeKey(owner, "BB", "[LInner;") to 6),
        linkedMapOf(
            MemberTypeKey(owner, "<init>", "()V") to MethodTypes(emptyList(), 7),
            MemberTypeKey(owner, "echo", "(Ljava/lang/Object;)Ljava/lang/Object;") to
                MethodTypes(listOf(10), 10, listOf(TypeParameter("T", types[10].scope, listOf(0)))),
            MemberTypeKey(owner, "echo", "()Ljava/lang/Object;") to MethodTypes(emptyList(), 0)
        ),
        mapOf(owner to ClassTypes(listOf(TypeParameter("T", types[1].scope, listOf(2))), 0, listOf(2)))
    )

    @Test
    fun `legacy and pooled declarations preserve all structure and migrate without changing type IDs`() = directory { dir ->
        for (version in 1..2) {
            DeclaredTypeWireFixture.write(dir, table, version)
            val restored = DeclaredTypeStore.load(dir)
            assertEquals(table, restored)
            for (id in types.indices) {
                assertEquals(table.render(id), restored.render(id))
                assertEquals(table.info(id), restored.info(id))
            }
            assertEquals("$owner.Outer<T>.Inner\$Name<? extends T>[]", restored.render(6))
            assertNull(restored.methods[MemberTypeKey(owner, "echo", "()V")])
            DeclaredTypeStore.save(restored, dir)
            val saved = Files.readAllBytes(dir.resolve(DeclaredTypeStore.FILE_NAME))
            assertEquals(0x47545902, ByteBuffer.wrap(saved).int)
            assertEquals(table, DeclaredTypeStore.load(dir))
            DeclaredTypeStore.save(DeclaredTypeStore.load(dir), dir)
            assertContentEquals(saved, Files.readAllBytes(dir.resolve(DeclaredTypeStore.FILE_NAME)))
        }
    }

    @Test
    fun `checked in v1 fixture remains readable without using current writer`() = directory { dir ->
        val bytes = checkNotNull(javaClass.getResourceAsStream("/declared-types-v1.bin")).use { it.readBytes() }
        Files.write(dir.resolve(DeclaredTypeStore.FILE_NAME), bytes)
        DeclaredTypeWireFixture.rebind(dir)
        val restored = DeclaredTypeStore.load(dir)
        assertEquals(listOf(DeclaredType("class", "java.lang.Object")), restored.types)
        assertEquals(mapOf(MemberTypeKey("Owner", "value", "Ljava/lang/Object;") to 0), restored.fields)
        assertEquals(mapOf(MemberTypeKey("Owner", "get", "()Ljava/lang/Object;") to MethodTypes(emptyList(), 0)), restored.methods)
        assertEquals(mapOf("Owner" to ClassTypes(emptyList(), null, emptyList())), restored.classes)
    }

    @Test
    fun `dictionary deduplicates every text position and rejects duplicate unused entries`() = directory { dir ->
        DeclaredTypeStore.save(table, dir)
        val valid = Files.readAllBytes(dir.resolve(DeclaredTypeStore.FILE_NAME))
        val input = ByteBuffer.wrap(valid).apply { position(36) }
        val texts = List(input.int) { val bytes = ByteArray(input.int); input.get(bytes); bytes.toString(Charsets.UTF_8) }
        assertEquals(texts.distinct(), texts)
        assertTrue(texts.containsAll(listOf(owner, "Aa", "BB", "", "class", "extends", types[10].scope)))
        // Aa/BB hash collisions remain distinct, but a second content-equal value must fail, even if unused.
        for (duplicate in listOf("Aa", "BB", "", owner)) {
            val text = duplicate.toByteArray()
            val entry = ByteBuffer.allocate(4 + text.size).putInt(text.size).put(text).array()
            replace(dir, appendDictionary(valid, entry))
            assertEquals("Duplicate string in graph.types dictionary", assertFailsWith<IllegalArgumentException> {
                DeclaredTypeStore.load(dir)
            }.message)
        }
    }

    @Test
    fun `all unused dictionary bytes undergo strict UTF8 validation`() = directory { dir ->
        DeclaredTypeStore.save(table, dir)
        val valid = Files.readAllBytes(dir.resolve(DeclaredTypeStore.FILE_NAME))
        for (text in listOf(
            byteArrayOf(0xff.toByte()), byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()), byteArrayOf(0xc3.toByte())
        )) {
            val entry = ByteBuffer.allocate(4 + text.size).putInt(text.size).put(text).array()
            replace(dir, appendDictionary(valid, entry))
            assertFailsWith<CharacterCodingException> { DeclaredTypeStore.load(dir) }
        }
    }

    @Test
    fun `negative and out of range IDs fail in type member class and type parameter text positions`() = directory { dir ->
        DeclaredTypeStore.save(table, dir)
        val valid = Files.readAllBytes(dir.resolve(DeclaredTypeStore.FILE_NAME))
        val positions = textPositions(valid)
        val count = ByteBuffer.wrap(valid).getInt(36)
        assertTrue(positions.size > types.size * 4 + table.fields.size * 3 + table.methods.size * 3)
        for (position in positions) for (invalid in listOf(-1, count, Int.MAX_VALUE)) {
            replace(dir, valid.copyOf().also { ByteBuffer.wrap(it).putInt(position, invalid) })
            assertEquals("Invalid graph.types string ID", assertFailsWith<IllegalArgumentException> {
                DeclaredTypeStore.load(dir)
            }.message)
        }
    }

    @Test
    fun `empty dictionary works for empty table and invalid scalar input cannot replace saved file`() = directory { dir ->
        DeclaredTypeWireFixture.write(dir, DeclaredTypeTable.EMPTY, 2)
        assertEquals(DeclaredTypeTable.EMPTY, DeclaredTypeStore.load(dir))
        DeclaredTypeStore.save(table, dir)
        val original = Files.readAllBytes(dir.resolve(DeclaredTypeStore.FILE_NAME))
        val binding = Files.readString(dir.resolve("forward.properties"))
        for (invalid in listOf("bad\uD800", "bad\uDC00")) {
            val malformed = table.copy(fields = mapOf(MemberTypeKey(owner, invalid, "I") to 0))
            assertFailsWith<CharacterCodingException> { DeclaredTypeStore.save(malformed, dir) }
            assertContentEquals(original, Files.readAllBytes(dir.resolve(DeclaredTypeStore.FILE_NAME)))
            assertEquals(binding, Files.readString(dir.resolve("forward.properties")))
            assertEquals(table, DeclaredTypeStore.load(dir))
        }
    }

    private fun textPositions(bytes: ByteArray): List<Int> {
        val input = ByteBuffer.wrap(bytes).apply { position(DeclaredTypeWireFixture.dictionaryEnd(bytes)) }
        val positions = mutableListOf<Int>()
        fun text() { positions.add(input.position()); input.int }
        fun refs() { val count = input.int; input.position(input.position() + 4 * count) }
        fun params() { repeat(input.int) { text(); text(); refs() } }
        repeat(input.int) { text(); text(); text(); input.int; input.int; text(); refs() }
        repeat(input.int) { text(); text(); text(); input.int }
        repeat(input.int) { text(); text(); text(); refs(); input.int; params() }
        repeat(input.int) { text(); params(); input.int; refs() }
        assertEquals(bytes.size, input.position())
        return positions
    }

    private fun appendDictionary(bytes: ByteArray, entry: ByteArray): ByteArray {
        val end = DeclaredTypeWireFixture.dictionaryEnd(bytes)
        return (bytes.copyOfRange(0, end) + entry + bytes.copyOfRange(end, bytes.size)).also {
            ByteBuffer.wrap(it).putInt(36, ByteBuffer.wrap(bytes).getInt(36) + 1)
        }
    }

    private fun replace(dir: Path, bytes: ByteArray) {
        Files.write(dir.resolve(DeclaredTypeStore.FILE_NAME), bytes)
        DeclaredTypeWireFixture.rebind(dir)
    }

    private fun directory(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("declared-dictionary")
        try { Files.writeString(dir.resolve("graph.metadata"), "binding"); block(dir) }
        finally { dir.toFile().deleteRecursively() }
    }
}
