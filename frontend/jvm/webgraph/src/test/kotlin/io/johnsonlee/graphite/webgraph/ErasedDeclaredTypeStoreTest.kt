package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.ClassTypes
import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.graph.MethodTypes
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErasedDeclaredTypeStoreTest {
    private val owner = "fixture.Owner"
    private val objectBridge = MemberTypeKey(owner, "bridge", "()Ljava/lang/Object;")
    private val stringBridge = objectBridge.copy(descriptor = "()Ljava/lang/String;")
    private val table = DeclaredTypeTable(
        listOf(
            DeclaredType("class", "java.lang.Object"), // 0
            DeclaredType("class", "java.lang.String"), // 1
            DeclaredType("primitive", "int"), // 2
            DeclaredType("array", component = 2), // 3
            DeclaredType("array", component = 3), // 4
            DeclaredType("primitive", "void"), // 5
            DeclaredType("variable", "T", "method:$owner#bridge()Ljava/lang/Object;"), // 6
            DeclaredType("class", "java.util.List", arguments = listOf(6)), // 7
            DeclaredType("class", "java.util.List"), // 8
            DeclaredType("class", "java.lang.Object") // 9: semantically equal, different ID
        ),
        linkedMapOf(MemberTypeKey(owner, "values", "Ljava/util/List;") to 7,
            MemberTypeKey(owner, "matrix", "[[I") to 4),
        linkedMapOf(objectBridge to MethodTypes(emptyList(), 6), stringBridge to MethodTypes(emptyList(), 1),
            objectBridge.copy(name = "sameSignature") to MethodTypes(emptyList(), 0)),
        mapOf(owner to ClassTypes(emptyList(), 0, emptyList()))
    )

    @Test
    fun `v5 shares erased signatures and preserves bridge return identity without descriptor strings`() = directory { dir ->
        save(dir, table)
        assertEquals(0x47545905, bytes(dir).getInt(0))
        val strings = StringTable.load(dir)
        (table.fields.keys + table.methods.keys).forEach { assertEquals(-1, strings.findId(it.descriptor), it.descriptor) }
        val input = bytes(dir).apply { position(typeEnd(bytes(dir))) }
        assertEquals(2, input.int)
        assertEquals(listOf(0, 0, 0, 1), List(4) { input.int })
        verify(table, DeclaredTypeStore.load(dir))
    }

    @Test
    fun `independent v5 encoding and all old versions preserve complete declared content`() = directory { dir ->
        for (version in 1..5) {
            DeclaredTypeWireFixture.write(dir, table, version)
            val loaded = DeclaredTypeStore.load(dir)
            verify(table, loaded)
            save(dir, loaded)
            assertEquals(0x47545905, bytes(dir).getInt(0))
            verify(table, DeclaredTypeStore.load(dir))
        }
    }

    @Test
    fun `absent raw rows and arbitrary old descriptors retain lossless older format`() = directory { dir ->
        val missingRaw = table.copy(types = table.types.mapIndexed { id, type ->
            if (id == 8) type.copy(arguments = listOf(6)) else type
        })
        val arbitrary = table.copy(fields = mapOf(MemberTypeKey(owner, "value", "not a descriptor") to 7))
        for (value in listOf(missingRaw, arbitrary)) {
            save(dir, value)
            assertEquals(0x47545904, bytes(dir).getInt(0))
            assertEquals(value, DeclaredTypeStore.load(dir))
        }
    }

    @Test
    fun `invalid signature references raw shapes cycles and duplicate semantic signatures are rejected`() = directory { dir ->
        save(dir, table)
        val original = Files.readAllBytes(dir.resolve("graph.types"))
        val signatures = typeEnd(ByteBuffer.wrap(original))
        val fields = signatures + 4 + 2 * 8
        val cases = listOf(
            signatures to -1,
            signatures + 4 to Int.MAX_VALUE,
            signatures + 8 to 6, // variable is not an erased return
            signatures + 16 to 9, // second signature equals the first through another type ID
            fields + 4 + 8 to 5, // void field
            fields + 4 + 8 to 7, // parameterized class is not raw
            fields + 4 + 8 to Int.MAX_VALUE,
            72 + 3 * 24 + 16 to 3 // raw array self cycle
        )
        for ((offset, value) in cases) {
            corrupt(dir, original.copyOf().apply { ByteBuffer.wrap(this).putInt(offset, value) })
            assertFailsWith<IllegalArgumentException>("offset=$offset value=$value") { DeclaredTypeStore.load(dir) }
        }
    }

    @Test
    fun `duplicate fields are detected across equivalent raw IDs`() = directory { dir ->
        val fields = linkedMapOf(MemberTypeKey(owner, "first", "Ljava/lang/Object;") to 0,
            MemberTypeKey(owner, "second", "Ljava/lang/Object;") to 1)
        save(dir, table.copy(fields = fields))
        val original = Files.readAllBytes(dir.resolve("graph.types"))
        val start = typeEnd(ByteBuffer.wrap(original)) + 4 + 2 * 8 + 4
        val input = ByteBuffer.wrap(original)
        input.putInt(start + 16 + 4, input.getInt(start + 4))
        input.putInt(start + 16 + 8, 9)
        corrupt(dir, original)
        assertEquals("Duplicate field in graph.types", assertFailsWith<IllegalArgumentException> {
            DeclaredTypeStore.load(dir)
        }.message)
    }

    @Test
    fun `descriptor only rows cannot add generic candidates or query work`() = directory { dir ->
        save(dir, table)
        val before = DeclaredTypeStore.load(dir)
        val extraTypes = List(100) { DeclaredType("class", "ZZZOnlyErased$it") }
        val extraFields = extraTypes.associate { MemberTypeKey(owner, "ZZZ${it.name}", "L${it.name};") to 6 }
        save(dir, table.copy(types = table.types + extraTypes, fields = table.fields + extraFields))
        val after = DeclaredTypeStore.load(dir)
        assertFalse(declaredTextMayMatch(after, listOf("ZZZ")))
        fun result(value: DeclaredTypeTable): Pair<Boolean, Int> {
            var work = 0
            val match = DeclaredTypeTextCandidates(value.types, listOf("ZZZ"), GraphWorkConsumer { work++ }).mayMatch()
            return match to work
        }
        assertEquals(result(before), result(after))
        assertEquals(table.render(7), after.render(7))
    }

    @Test
    fun `compressed signature length overflow fails without expanding descriptor characters`() = directory { dir ->
        for ((name, repetitions) in listOf("X".repeat(32768) to 65536, "界".repeat(16384) to 50000)) {
            val extra = table.copy(types = table.types + DeclaredType("class", name))
            save(dir, extra)
            val original = Files.readAllBytes(dir.resolve("graph.types"))
            val first = typeEnd(ByteBuffer.wrap(original)) + 4
            val signature = ByteBuffer.allocate(8 + repetitions * 4).apply {
                putInt(repetitions)
                repeat(repetitions) { putInt(table.types.size) }
                putInt(0)
            }.array()
            corrupt(dir, original.copyOfRange(0, first) + signature + original.copyOfRange(first + 8, original.size))
            assertEquals("Erased descriptor exceeds string representation", assertFailsWith<IllegalArgumentException> {
                DeclaredTypeStore.load(dir)
            }.message)
        }
    }

    @Test
    fun `compressed overflow validates each referenced raw row once before rejection`() {
        val reads = IntArray(2)
        val rows = listOf(DeclaredType("class", "X".repeat(32768)), DeclaredType("primitive", "void"))
        val raw = ErasedDeclaredTypes(rows.size) { id ->
            reads[id]++
            rows[id]
        }
        val parameters = 70000
        val input = ByteBuffer.allocate(12 + parameters * 4).apply {
            putInt(1)
            putInt(parameters)
            repeat(parameters) { putInt(0) }
            putInt(1)
            flip()
        }
        assertEquals("Erased descriptor exceeds string representation", assertFailsWith<IllegalArgumentException> {
            ErasedDeclaredTypeContext.read(input, raw)
        }.message)
        assertEquals(listOf(1, 1), reads.toList(), "Repeated references cannot trigger repeated mapped name decoding")
    }

    @Test
    fun `member index hashes reuse raw and signature IDs until indexing finishes`() {
        val reads = IntArray(2)
        val rows = listOf(DeclaredType("class", "example.Name"), DeclaredType("primitive", "void"))
        val raw = ErasedDeclaredTypes(rows.size) { id ->
            reads[id]++
            rows[id]
        }
        val input = ByteBuffer.allocate(16).apply {
            putInt(1)
            putInt(1)
            putInt(0)
            putInt(1)
            flip()
        }
        val context = ErasedDeclaredTypeContext.read(input, raw)
        val fieldHash = "Lexample/Name;".hashCode()
        val methodHash = "(Lexample/Name;)V".hashCode()
        assertEquals(fieldHash, context.hash(0, method = false))
        val initialReads = reads.toList()
        repeat(100) {
            assertEquals(methodHash, context.hash(0, method = true))
            assertEquals(fieldHash, context.hash(0, method = false))
        }
        assertEquals(initialReads, reads.toList(), "Repeated member keys must reuse their already validated hashes")
        context.finishIndexing()
        assertEquals(methodHash, context.hash(0, method = true))
        assertEquals(fieldHash, context.hash(0, method = false))
        assertEquals(listOf(initialReads[0] + 2, initialReads[1] + 1), reads.toList(), "Load-only caches must be released")
    }

    @Test
    fun `method scope expansion budget is checked before rendering its pooled descriptor`() = directory { dir ->
        val name = "X".repeat(32768)
        val descriptor = "(" + "L$name;".repeat(40) + ")V"
        val key = MemberTypeKey(owner, "large", descriptor)
        val value = DeclaredTypeTable(
            listOf(DeclaredType("class", name), DeclaredType("primitive", "void"),
                DeclaredType("variable", "T", "method:$owner#large$descriptor")),
            emptyMap(), mapOf(key to MethodTypes(List(40) { 2 }, 1)), emptyMap()
        )
        DeclaredTypeWireFixture.write(dir, value, 5)
        assertEquals("Excessive graph.types projection expansion", assertFailsWith<IllegalArgumentException> {
            DeclaredTypeStore.load(dir)
        }.message)
    }

    @Test
    fun `class descriptor punctuation Unicode and primitive spelling keep exact key hashes`() = directory { dir ->
        val name = "odd.类型🚀)Part"
        val key = MemberTypeKey(owner, "punctuation", "(L${name.replace('.', '/')};[[II)V")
        val value = table.copy(
            types = table.types + listOf(DeclaredType("class", name), DeclaredType("class", "int")),
            methods = table.methods + (key to MethodTypes(listOf(table.types.size, 4, 2), 5)),
            fields = table.fields + (MemberTypeKey(owner, "classNamedInt", "Lint;") to table.types.size + 1)
        )
        save(dir, value)
        assertEquals(0x47545905, bytes(dir).getInt(0))
        val actual = DeclaredTypeStore.load(dir)
        assertEquals(value, actual)
        assertEquals(5, actual.methods[key]?.returnType)
        assertEquals(listOf(table.types.size, 4, 2), actual.methods[key]?.parameterTypes)
        assertEquals(null, actual.methods[key.copy(descriptor = "(I[[IL${name.replace('.', '/')};)V")])
        assertEquals(table.types.size + 1, actual.fields[MemberTypeKey(owner, "classNamedInt", "Lint;")])
    }

    private fun verify(expected: DeclaredTypeTable, actual: DeclaredTypeTable) {
        assertEquals(expected, actual)
        expected.types.indices.forEach { assertEquals(expected.info(it), actual.info(it)) }
        assertEquals(6, actual.methods[objectBridge]?.returnType)
        assertEquals(1, actual.methods[stringBridge]?.returnType)
        assertEquals(4, actual.fields[MemberTypeKey(owner, "matrix", "[[I")])
        assertTrue(declaredTextMayMatch(actual, listOf("bridge")))
    }

    private fun typeEnd(input: ByteBuffer): Int {
        input.position(68)
        repeat(input.int) {
            input.position(input.position() + 20)
            val count = input.int
            input.position(input.position() + count * 4)
        }
        return input.position()
    }

    private fun bytes(dir: Path): ByteBuffer = ByteBuffer.wrap(Files.readAllBytes(dir.resolve("graph.types")))
    private fun corrupt(dir: Path, value: ByteArray) {
        Files.write(dir.resolve("graph.types"), value)
        DeclaredTypeWireFixture.rebind(dir)
    }
    private fun save(dir: Path, value: DeclaredTypeTable) {
        val names = linkedSetOf<String>()
        DeclaredTypeStore.collectStrings(value, names)
        DeclaredTypeStore.save(value, dir, StringTable.build(names, dir, true))
    }
    private fun directory(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("erased-declared-types")
        try { Files.writeString(dir.resolve("graph.metadata"), "binding"); block(dir) }
        finally { dir.toFile().deleteRecursively() }
    }
}
