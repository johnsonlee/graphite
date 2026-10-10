package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.graph.MethodTypes
import it.unimi.dsi.fastutil.io.BinIO
import it.unimi.dsi.util.FrontCodedStringList
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedDeclaredTypeTextStatsTest {
    @Test
    fun `shared text hashes and UTF8 lengths match standard strings before and after publication`() = directory { dir ->
        val values = listOf("", "\u0000", "Aa", "BB", "\u007f", "\u0080", "\u07ff", "\u0800",
            "\ud7ff", "\ue000", "\uffff", "\ud800\udc00", "\udbff\udfff", "类型🚀", "prefix".repeat(200))
        val strings = StringTable.build(values, dir)
        val texts = SharedDeclaredTypeTexts(strings)
        repeat(2) {
            for (value in values.reversed()) {
                val id = strings.findId(value)
                assertEquals(value.hashCode(), texts.hash(id), value)
                assertEquals(value.toByteArray(Charsets.UTF_8).size, texts.utf8Length(id), value)
                assertEquals(value, texts.text(id))
            }
            texts.finishLoading()
        }
        assertFailsWith<IllegalArgumentException> { texts.hash(-1) }
        assertFailsWith<IllegalArgumentException> { texts.utf8Length(strings.size()) }
    }

    @Test
    fun `malformed surrogate references never acquire a successful validation entry`() = directory { dir ->
        val values = listOf("\ud800", "\udc00", "x\ud800y", "\ud800\ud800", "\udc00\udfff", "ok\ud800\udc00")
        val strings = StringTable.build(values, dir)
        val texts = SharedDeclaredTypeTexts(strings)
        repeat(2) {
            for (value in values.dropLast(1)) {
                val id = strings.findId(value)
                assertFailsWith<CharacterCodingException> { texts.hash(id) }
                assertFailsWith<CharacterCodingException> { texts.utf8Length(id) }
            }
            val valid = strings.findId(values.last())
            assertEquals(values.last().hashCode(), texts.hash(valid))
            assertEquals(6, texts.utf8Length(valid))
        }
        texts.finishLoading()
        assertFailsWith<CharacterCodingException> { texts.text(strings.findId("\ud800")) }
    }

    @Test
    fun `different IDs preserve duplicate text equality and distinct colliding hash values`() = directory { dir ->
        BinIO.storeObject(FrontCodedStringList(listOf("Aa", "Aa", "BB", "").iterator(), 4, false),
            dir.resolve("graph.strings").toString())
        val texts = SharedDeclaredTypeTexts(StringTable.load(dir))
        repeat(2) {
            assertEquals("Aa", texts.text(0))
            assertEquals("Aa", texts.text(1))
            assertEquals("BB", texts.text(2))
            assertEquals(texts.hash(0), texts.hash(1))
            assertEquals(texts.hash(0), texts.hash(2))
            assertEquals(2, texts.utf8Length(0))
            assertEquals(0, texts.utf8Length(3))
            assertEquals(0, texts.hash(3))
            texts.finishLoading()
        }
    }

    @Test
    fun `shared UTF8 DAG budget counts repeated edges and supplementary text before publication`() = directory { dir ->
        val name = "🚀".repeat(75_000)
        val strings = StringTable.build(listOf("", "class", "C", name), dir, true)
        Files.writeString(dir.resolve("graph.metadata"), "metadata")
        writeExpansion(dir, strings, name, 3)
        val loaded = DeclaredTypeStore.load(dir)
        assertEquals(name, loaded.types[0].name)
        assertEquals(listOf(0, 0, 0), loaded.types[1].arguments)
        loaded.validate()
        writeExpansion(dir, strings, name, 4)
        assertEquals("Excessive graph.types projection expansion", assertFailsWith<IllegalArgumentException> {
            DeclaredTypeStore.load(dir)
        }.message)
    }

    @Test
    fun `published mapped tables keep graph scoped values under concurrent validation queries and resave`() = directory { dir ->
        val originals = listOf(table("类型🚀"), table("別の型🌍"))
        val paths = originals.indices.map { dir.resolve("graph-$it").also { path -> Files.createDirectory(path) } }
        originals.forEachIndexed { index, table ->
            GraphStore.save(DefaultGraph.Builder().apply { setDeclaredTypes(table) }.build(), paths[index])
        }
        val loaded = paths.map { DeclaredTypeStore.load(it) }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 8).map { task -> Callable {
                val index = task % loaded.size
                assertPublishedTable(loaded[index], originals[index])
            } }
            executor.invokeAll(tasks, 30, TimeUnit.SECONDS).forEach { it.get() }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
        }
        loaded.forEachIndexed { index, table ->
            val values = linkedSetOf("!new-global-prefix")
            DeclaredTypeStore.collectStrings(table, values)
            val strings = StringTable.build(values, paths[index], true)
            DeclaredTypeStore.save(table, paths[index], strings)
            assertEquals(originals[index], DeclaredTypeStore.load(paths[index]))
        }
    }

    private fun assertPublishedTable(actual: DeclaredTypeTable, expected: DeclaredTypeTable) {
        repeat(8) {
            actual.validate()
            assertEquals(expected.render(1), actual.render(1))
            assertEquals(expected.info(1), actual.info(1))
            assertEquals(expected.methods, actual.methods)
            assertEquals(expected.fields, actual.fields)
            assertTrue(DeclaredTypeTextCandidates(actual.types, listOf(expected.types[0].name), null).mayMatch())
            assertFalse(DeclaredTypeTextCandidates(actual.types, listOf("absent"), null).mayMatch())
            assertTrue(declaredTextMayMatch(actual, listOf(expected.types[0].name)))
            assertFalse(declaredTextMayMatch(actual, listOf("absent")))
        }
    }

    private fun table(name: String): DeclaredTypeTable {
        val key = MemberTypeKey("Owner", "echo", "(Ljava/util/List;)Ljava/util/List;")
        return DeclaredTypeTable(
            listOf(DeclaredType("class", name), DeclaredType("class", "java.util.List", arguments = listOf(0))),
            linkedMapOf(key.copy(name = "Aa") to 0, key.copy(name = "BB") to 1),
            mapOf(key to MethodTypes(listOf(1), 1)),
            emptyMap()
        )
    }

    /** Independent GTY03 encoder; malformed expansion must reach the production load validator. */
    private fun writeExpansion(dir: Path, strings: StringTable, name: String, repetitions: Int) {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            out.writeInt(0x47545903)
            out.write(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dir.resolve("graph.metadata"))))
            out.write(requireNotNull(strings.serializedDigest()))
            out.writeInt(2)
            for ((value, arguments) in listOf(name to emptyList(), "C" to List(repetitions) { 0 })) {
                out.writeInt(strings.findId("class")); out.writeInt(strings.findId(value)); out.writeInt(strings.findId(""))
                out.writeInt(-1); out.writeInt(-1); out.writeInt(strings.findId(""))
                out.writeInt(arguments.size); arguments.forEach(out::writeInt)
            }
            repeat(3) { out.writeInt(0) }
        }
        Files.write(dir.resolve("graph.types"), output.toByteArray())
        DeclaredTypeWireFixture.rebind(dir)
    }

    private fun directory(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("shared-declared-stats")
        try { block(dir) } finally { dir.toFile().deleteRecursively() }
    }
}
