package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.ClassTypes
import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.DeclaredTypeTextField
import io.johnsonlee.graphite.graph.DeclaredTypeValidationAccess
import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.graph.MethodTypes
import io.johnsonlee.graphite.graph.TypeParameter
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StructuralDeclaredTypeStoreTest {
    private val owner = "fixture.类型🚀"
    private val method = MemberTypeKey(owner, "echo", "(Ljava/lang/Object;)Ljava/lang/Object;")
    private val methodScope = "method:$owner#echo${method.descriptor}"
    private val table = DeclaredTypeTable(
        listOf(
            DeclaredType("class", "java.lang.Object"),
            DeclaredType("variable", "T", "class:$owner"),
            DeclaredType("variable", "T", methodScope),
            DeclaredType("variable", "U", "unresolved:$methodScope"),
            DeclaredType("variable", "V", "unresolved:class:$owner"),
            DeclaredType("wildcard", component = 2, variance = "extends"),
            DeclaredType("wildcard", component = 1, variance = "super"),
            DeclaredType("wildcard", variance = "unbounded"),
            DeclaredType("class", "java.util.List", arguments = listOf(5)),
            DeclaredType("array", component = 8),
            DeclaredType("primitive", "void")
        ),
        mapOf(MemberTypeKey(owner, "value", "Ljava/util/List;") to 8),
        mapOf(method to MethodTypes(listOf(2), 9, listOf(TypeParameter("T", methodScope, listOf(0))))),
        mapOf(owner to ClassTypes(listOf(TypeParameter("T", "class:$owner", listOf(0))), 0, emptyList()))
    )

    @Test
    fun `new writer stores enum and declaration references without composite scope strings`() = directory { dir ->
        save(dir, table)
        val strings = StringTable.load(dir)
        for (text in listOf("class", "variable", "extends", "super", "unbounded", "class:$owner", methodScope,
            "unresolved:$methodScope", "unresolved:class:$owner")) assertEquals(-1, strings.findId(text), text)
        val input = ByteBuffer.wrap(Files.readAllBytes(dir.resolve("graph.types")))
        assertEquals(0x47545904, input.int)
        input.position(68)
        assertEquals(table.types.size, input.int)
        assertEquals(0, input.int) // kind=class, variance=none, scope=none, reserved=0
        assertEquals(strings.findId("java.lang.Object"), input.int)
        assertEquals(-1, input.int)
        verify(table, DeclaredTypeStore.load(dir))
    }

    @Test
    fun `independent encoders for all four versions agree and canonical resaves upgrade`() = directory { dir ->
        for (version in 1..4) {
            DeclaredTypeWireFixture.write(dir, table, version)
            val loaded = DeclaredTypeStore.load(dir)
            verify(table, loaded)
            save(dir, loaded)
            assertEquals(0x47545904, ByteBuffer.wrap(Files.readAllBytes(dir.resolve("graph.types"))).int)
            verify(table, DeclaredTypeStore.load(dir))
            val saved = Files.readAllBytes(dir.resolve("graph.types"))
            save(dir, DeclaredTypeStore.load(dir))
            assertContentEquals(saved, Files.readAllBytes(dir.resolve("graph.types")))
        }
    }

    @Test
    fun `opaque and missing declaration legacy scopes round trip through v3`() = directory { dir ->
        for (scope in listOf("opaque", "class:Missing", "method:Missing#f()V")) {
            val legacy = table.copy(types = table.types.toMutableList().apply { set(1, get(1).copy(scope = scope)) })
            save(dir, legacy)
            assertEquals(0x47545903, ByteBuffer.wrap(Files.readAllBytes(dir.resolve("graph.types"))).int)
            assertTrue(StringTable.load(dir).findId(scope) >= 0)
            verify(legacy, DeclaredTypeStore.load(dir))
        }
    }

    @Test
    fun `invalid enum reserved string scope and child references fail before publication`() = directory { dir ->
        save(dir, table)
        val bytes = Files.readAllBytes(dir.resolve("graph.types"))
        val formals = formalOffsets(bytes)
        val bad = listOf(
            bytes.copyOf().apply { this[72] = 5 }, bytes.copyOf().apply { this[73] = 4 },
            bytes.copyOf().apply { this[74] = 5 }, bytes.copyOf().apply { this[75] = 1 },
            bytes.copyOf().apply { ByteBuffer.wrap(this).putInt(76, -2) },
            bytes.copyOf().apply { ByteBuffer.wrap(this).putInt(80, 0) },
            bytes.copyOf().apply { ByteBuffer.wrap(this).putInt(104, 1) }, // class scope target out of range
            bytes.copyOf().apply { ByteBuffer.wrap(this).putInt(128, 1) }, // method scope target out of range
            bytes.copyOf().apply { ByteBuffer.wrap(this).putInt(84, 0) } // expression cycle
        )
        val malformedFormals = formals.flatMap { offset ->
            listOf(
                bytes.copyOf().apply { this[offset + 1] = 1 },
                bytes.copyOf().apply { this[offset + 2] = 1 },
                bytes.copyOf().apply { this[offset + 3] = 1 },
                bytes.copyOf().apply { this[offset] = 5 },
                bytes.copyOf().apply { ByteBuffer.wrap(this).putInt(offset + 4, Int.MAX_VALUE) }
            )
        }
        for (value in bad + malformedFormals) {
            Files.write(dir.resolve("graph.types"), value)
            DeclaredTypeWireFixture.rebind(dir)
            assertFailsWith<IllegalArgumentException> { DeclaredTypeStore.load(dir) }
        }
    }

    @Test
    fun `negative summary excludes unrelated field and method declaration keys`() = directory { dir ->
        val unrelated = method.copy(name = "ZZZUnrelatedDeclaration")
        val extended = table.copy(
            fields = table.fields + (unrelated to 0),
            methods = table.methods + (unrelated to MethodTypes(emptyList(), 0))
        )
        save(dir, extended)
        val loaded = DeclaredTypeStore.load(dir)
        assertTrue(StringTable.load(dir).findId(unrelated.name) >= 0)
        assertFalse(declaredTextMayMatch(loaded, listOf("ZZZ")))
        assertFalse(DeclaredTypeTextCandidates(loaded.types, listOf("ZZZ"), null).mayMatch())
        assertTrue(declaredTextMayMatch(loaded, listOf("echo")))
        assertTrue(declaredTextMayMatch(loaded, listOf("unresolved")))
    }

    @Test
    fun `structural format binds both exact dictionaries and metadata`() = directory { dir ->
        save(dir, table)
        val bytes = Files.readAllBytes(dir.resolve("graph.types"))
        for (offset in listOf(4, 36)) {
            val changed = bytes.copyOf().apply { this[offset] = (this[offset].toInt() xor 1).toByte() }
            Files.write(dir.resolve("graph.types"), changed)
            DeclaredTypeWireFixture.rebind(dir)
            assertFailsWith<IllegalArgumentException> { DeclaredTypeStore.load(dir) }
        }
        Files.write(dir.resolve("graph.types"), bytes)
        DeclaredTypeWireFixture.rebind(dir)
        assertFailsWith<IllegalArgumentException> { DeclaredTypeStore.load(dir, StringTable.load(dir)) }
    }

    @Test
    fun `scope reference does not evade rendered UTF8 expansion budget`() = directory { dir ->
        val hugeOwner = "界".repeat(333_334)
        val excessive = DeclaredTypeTable(
            listOf(DeclaredType("variable", "T", "class:$hugeOwner")), emptyMap(), emptyMap(),
            mapOf(hugeOwner to ClassTypes(emptyList(), null, emptyList()))
        )
        DeclaredTypeWireFixture.write(dir, excessive, 4)
        assertEquals("Excessive graph.types projection expansion", assertFailsWith<IllegalArgumentException> {
            DeclaredTypeStore.load(dir)
        }.message)
    }

    @Test
    fun `ambiguous legacy scope spellings retain old representation`() = directory { dir ->
        val first = MemberTypeKey("Owner", "a#b", "()V")
        val second = MemberTypeKey("Owner#a", "b", "()V")
        val legacy = DeclaredTypeTable(
            listOf(DeclaredType("variable", "T", "method:Owner#a#b()V")), emptyMap(),
            linkedMapOf(first to MethodTypes(emptyList(), 0), second to MethodTypes(emptyList(), 0)), emptyMap()
        )
        save(dir, legacy)
        assertEquals(0x47545903, ByteBuffer.wrap(Files.readAllBytes(dir.resolve("graph.types"))).int)
        assertEquals(legacy, DeclaredTypeStore.load(dir))
    }

    private fun formalOffsets(bytes: ByteArray): List<Int> {
        val input = ByteBuffer.wrap(bytes).apply { position(68) }
        fun skip(size: Int) { input.position(input.position() + size) }
        fun refs() { skip(input.int * 4) }
        val result = mutableListOf<Int>()
        fun formals() {
            repeat(input.int) { skip(4); result.add(input.position()); skip(8); refs() }
        }
        repeat(input.int) { skip(20); refs() }
        repeat(input.int) { skip(16) }
        repeat(input.int) { skip(12); refs(); skip(4); formals() }
        repeat(input.int) { skip(4); formals(); skip(4); refs() }
        assertEquals(bytes.size, input.position())
        return result
    }

    private fun verify(expected: DeclaredTypeTable, actual: DeclaredTypeTable) {
        assertEquals(expected, actual)
        val access = actual.types as DeclaredTypeValidationAccess
        for (id in expected.types.indices) {
            assertEquals(expected.render(id), actual.render(id))
            assertEquals(expected.info(id), actual.info(id))
            assertEquals(expected.types[id].scope, access.text(id, DeclaredTypeTextField.SCOPE))
            assertEquals(expected.types[id].scope.toByteArray().size, access.textUtf8Length(id, DeclaredTypeTextField.SCOPE))
        }
        for (fragment in listOf("variable", "extends", "super", "unbounded", "method", "unresolved", "echo", "类型")) {
            assertTrue(declaredTextMayMatch(actual, listOf(fragment)), fragment)
            assertTrue(DeclaredTypeTextCandidates(actual.types, listOf(fragment), null).mayMatch(), fragment)
        }
        assertFalse(DeclaredTypeTextCandidates(actual.types, listOf("surelyMissing"), null).mayMatch())
    }

    private fun save(dir: Path, value: DeclaredTypeTable) {
        val names = linkedSetOf<String>()
        DeclaredTypeStore.collectStrings(value, names)
        DeclaredTypeStore.save(value, dir, StringTable.build(names, dir, true))
    }

    private fun directory(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("structural-declared-types")
        try { Files.writeString(dir.resolve("graph.metadata"), "binding"); block(dir) }
        finally { dir.toFile().deleteRecursively() }
    }
}
