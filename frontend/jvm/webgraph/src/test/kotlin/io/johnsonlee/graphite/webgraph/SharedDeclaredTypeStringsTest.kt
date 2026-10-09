package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.ClassTypes
import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.graph.MethodTypes
import io.johnsonlee.graphite.graph.TypeParameter
import it.unimi.dsi.fastutil.io.BinIO
import it.unimi.dsi.util.FrontCodedStringList
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SharedDeclaredTypeStringsTest {
    private val key = MemberTypeKey("shared.Owner", "echo", "(Ljava/util/List;)Ljava/util/List;")
    private val table = DeclaredTypeTable(
        listOf(
            DeclaredType("class", "shared.类型🚀"),
            DeclaredType("class", "java.util.List", arguments = listOf(0)),
            DeclaredType("variable", "T", "method:${key.owner}.${key.name}${key.descriptor}")
        ),
        linkedMapOf(key.copy(name = "Aa") to 1, key.copy(name = "BB") to 2),
        mapOf(key to MethodTypes(listOf(1, 2), 1, listOf(TypeParameter("T", "method:echo", listOf(0))))),
        mapOf("shared.Owner" to ClassTypes(listOf(TypeParameter("U", "class:shared.Owner", listOf(1))), 0, listOf(1)))
    )

    @Test
    fun `wire uses global IDs and exact serialized identity without a local dictionary`() = directory { dir ->
        val strings = save(dir)
        val bytes = Files.readAllBytes(dir.resolve("graph.types"))
        val input = ByteBuffer.wrap(bytes)
        assertEquals(0x47545903, input.int)
        assertContentEquals(hash(dir.resolve("graph.metadata")), ByteArray(32).also(input::get))
        assertContentEquals(hash(dir.resolve("graph.strings")), ByteArray(32).also(input::get))
        assertEquals(table.types.size, input.int)
        assertEquals(strings.findId("class"), input.int)
        assertEquals(strings.findId("shared.类型🚀"), input.int)
        assertEquals(strings.findId(""), input.int)
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("java.util.List"))
        val loaded = DeclaredTypeStore.load(dir, StringTable.load(dir, true))
        assertEquals(table, loaded)
        assertEquals(table.fields.keys.toList(), loaded.fields.keys.toList())
        assertEquals(table.info(1), loaded.info(1))
        assertEquals("java.util.List<shared.类型🚀>", loaded.render(1))
        assertTrue(DeclaredTypeTextCandidates(loaded.types, listOf("List"), null).mayMatch())
        assertFalse(DeclaredTypeTextCandidates(loaded.types, listOf("definitelyAbsent"), null).mayMatch())
        assertTrue(DeclaredTypeTextCandidates(loaded.types, listOf("类型"), null).mayMatch())
    }

    @Test
    fun `unverified tables and forged semantic identity cannot authorize shared IDs`() = directory { dir ->
        save(dir)
        assertFailsWith<IllegalArgumentException> { DeclaredTypeStore.load(dir, StringTable.load(dir)) }
        val identity = Files.readAllBytes(dir.resolve("graph.strings.identity"))
        StringTable.build(listOf("", "wrong", "dictionary"), dir)
        Files.write(dir.resolve("graph.strings.identity"), identity)
        assertFailsWith<IllegalArgumentException> { DeclaredTypeStore.load(dir) }
        Files.delete(dir.resolve("graph.strings"))
        assertFailsWith<java.nio.file.NoSuchFileException> { DeclaredTypeStore.load(dir) }
    }

    @Test
    fun `all serialized trailing bytes are bound without changing legacy trailing behavior`() = directory { dir ->
        save(dir)
        Files.write(dir.resolve("graph.strings"), byteArrayOf(0x70, 0x79, 1, 2), StandardOpenOption.APPEND)
        assertEquals("class", StringTable.load(dir).get(StringTable.load(dir).findId("class")))
        val verified = StringTable.load(dir, true)
        assertContentEquals(hash(dir.resolve("graph.strings")), verified.serializedDigest())
        assertFailsWith<IllegalArgumentException> { DeclaredTypeStore.load(dir, verified) }
        val bytes = Files.readAllBytes(dir.resolve("graph.types"))
        hash(dir.resolve("graph.strings")).copyInto(bytes, 36)
        writeTypes(dir, bytes)
        assertEquals(table, DeclaredTypeStore.load(dir, verified))
    }

    @Test
    fun `invalid global IDs and referenced unpaired surrogates fail before publication`() = directory { dir ->
        val strings = save(dir)
        val original = Files.readAllBytes(dir.resolve("graph.types"))
        for (id in listOf(-1, strings.size(), Int.MAX_VALUE)) {
            val corrupt = original.copyOf()
            ByteBuffer.wrap(corrupt).putInt(72, id)
            writeTypes(dir, corrupt)
            assertFailsWith<IllegalArgumentException> { DeclaredTypeStore.load(dir) }
        }
        val values = (0 until strings.size()).map(strings::get).toMutableList()
        values[strings.findId("shared.类型🚀")] = "bad\uD800"
        BinIO.storeObject(FrontCodedStringList(values.iterator(), 4, false), dir.resolve("graph.strings").toString())
        hash(dir.resolve("graph.strings")).copyInto(original, 36)
        writeTypes(dir, original)
        assertFailsWith<CharacterCodingException> { DeclaredTypeStore.load(dir) }
    }

    @Test
    fun `shared format still validates every reference and expression cycle before return`() = directory { dir ->
        save(dir)
        val original = Files.readAllBytes(dir.resolve("graph.types"))
        for ((offset, value) in listOf(84 to -2, 84 to 0, 128 to Int.MAX_VALUE)) {
            val corrupt = original.copyOf()
            ByteBuffer.wrap(corrupt).putInt(offset, value)
            writeTypes(dir, corrupt)
            assertFailsWith<IllegalArgumentException>("offset=$offset, value=$value") { DeclaredTypeStore.load(dir) }
        }
        for (length in listOf(36, 67, original.size - 1)) {
            writeTypes(dir, original.copyOf(length))
            assertFailsWith<IllegalArgumentException> { DeclaredTypeStore.load(dir) }
        }
    }

    @Test
    fun `unreferenced malformed global text stays legacy compatible but referenced formal text is strict`() = directory { dir ->
        val strings = save(dir)
        val original = Files.readAllBytes(dir.resolve("graph.types"))
        val values = (0 until strings.size()).map(strings::get).toMutableList()
        values[strings.findId("!node-only")] = "unused\uD800"
        BinIO.storeObject(FrontCodedStringList(values.iterator(), 4, false), dir.resolve("graph.strings").toString())
        hash(dir.resolve("graph.strings")).copyInto(original, 36)
        writeTypes(dir, original)
        assertEquals(table, DeclaredTypeStore.load(dir))
        values[strings.findId("method:echo")] = "formal\uD800"
        BinIO.storeObject(FrontCodedStringList(values.iterator(), 4, false), dir.resolve("graph.strings").toString())
        hash(dir.resolve("graph.strings")).copyInto(original, 36)
        writeTypes(dir, original)
        assertFailsWith<CharacterCodingException> { DeclaredTypeStore.load(dir) }
    }

    @Test
    fun `different shared IDs with duplicate values do not conceal duplicate member keys`() = directory { dir ->
        val strings = save(dir)
        val values = (0 until strings.size()).map(strings::get).toMutableList()
        values[strings.findId("BB")] = "Aa"
        BinIO.storeObject(FrontCodedStringList(values.iterator(), 4, false), dir.resolve("graph.strings").toString())
        val bytes = Files.readAllBytes(dir.resolve("graph.types"))
        hash(dir.resolve("graph.strings")).copyInto(bytes, 36)
        writeTypes(dir, bytes)
        assertEquals("Duplicate field in graph.types", assertFailsWith<IllegalArgumentException> {
            DeclaredTypeStore.load(dir)
        }.message)
    }

    @Test
    fun `same directory and different dictionary resave remap all IDs`() = directory { dir ->
        save(dir)
        val loaded = DeclaredTypeStore.load(dir)
        val changed = linkedSetOf("!earlier", "!other")
        DeclaredTypeStore.collectStrings(loaded, changed)
        val strings = StringTable.build(changed, dir, true)
        DeclaredTypeStore.save(loaded, dir, strings)
        assertEquals(table, DeclaredTypeStore.load(dir))
        for (version in listOf(1, 2)) {
            DeclaredTypeWireFixture.write(dir, table, version)
            assertEquals(table, DeclaredTypeStore.load(dir))
            DeclaredTypeStore.save(DeclaredTypeStore.load(dir), dir, strings)
            assertEquals(0x47545903, ByteBuffer.wrap(Files.readAllBytes(dir.resolve("graph.types"))).int)
            assertEquals(table, DeclaredTypeStore.load(dir))
        }
    }

    @Test
    fun `GraphStore collects declaration only texts and both loaders preserve them on resave`() = directory { dir ->
        val graph = DefaultGraph.Builder().apply { setDeclaredTypes(table) }.build()
        GraphStore.save(graph, dir)
        assertTrue(StringTable.load(dir).findId("class:shared.Owner") >= 0)
        for (load in listOf({ GraphStore.load(dir) }, { GraphStore.loadMapped(dir) })) {
            val restored = load()
            try {
                assertEquals(table, restored.declaredTypes())
                GraphStore.save(restored, dir)
                assertEquals(table, DeclaredTypeStore.load(dir))
            } finally { (restored as? AutoCloseable)?.close() }
        }
    }

    @Test
    fun `digest stream accounts for skipped bytes and the complete unread tail`() {
        val bytes = ByteArray(20000) { (it * 17).toByte() }
        val digest = MessageDigest.getInstance("SHA-256")
        SerializedDigestInputStream(ByteArrayInputStream(bytes), digest).use { input ->
            assertEquals(0, input.read())
            assertEquals(0L, input.skip(-1))
            assertEquals(9000L, input.skip(9000))
            assertEquals(bytes[9001].toInt() and 255, input.read())
            assertEquals(10998L, input.skip(Long.MAX_VALUE))
            assertEquals(-1, input.read())
        }
        assertContentEquals(MessageDigest.getInstance("SHA-256").digest(bytes), digest.digest())
    }

    private fun save(dir: Path): StringTable {
        Files.writeString(dir.resolve("graph.metadata"), "metadata")
        val values = linkedSetOf("!node-only")
        DeclaredTypeStore.collectStrings(table, values)
        val strings = StringTable.build(values, dir, true)
        DeclaredTypeStore.save(table, dir, strings)
        assertNotNull(strings.serializedDigest())
        return strings
    }

    private fun hash(path: Path): ByteArray = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))

    private fun writeTypes(dir: Path, bytes: ByteArray) {
        Files.write(dir.resolve("graph.types"), bytes)
        Files.writeString(dir.resolve("forward.properties"),
            "${DeclaredTypeStore.BINDING_KEY}=${HexFormat.of().formatHex(hash(dir.resolve("graph.types")))}\n")
    }

    private fun directory(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("shared-declared-types")
        try { block(dir) } finally { dir.toFile().deleteRecursively() }
    }
}
