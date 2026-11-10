package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@Suppress("MagicNumber")
class MappedIndexContractTest : GraphStoreTestFixture() {
    @Test
    fun `serializer compatibility facade preserves node tags and rejects a mismatched branch digest`() {
        val node = io.johnsonlee.graphite.core.StringConstant(NodeId(0), "value")
        assertEquals(NodeSerializer.TAG_STRING_CONSTANT, NodeSerializer.tagOf(node))
        val rejected = NodeSerializer.decodeBranchDefinitionPayload(
            byteArrayOf(1, 2, 3), 0, ByteArray(NodeSerializer.DIGEST_BYTES), 1,
            NodeSerializer.NodeTagLookup { NodeSerializer.tagOf(node) })
        kotlin.test.assertNull(rejected.definitions)
        assertEquals("is corrupt (payload digest mismatch)", rejected.rejection)
        val preamble = NodeSerializer.decodeBranchDefinitionPreamble(byteArrayOf(), 0, null)
        kotlin.test.assertNull(preamble.payloadDigest)
        assertTrue(preamble.rejection != null)
    }

    @Test
    fun `zero member rows reject every accessor instead of inventing a default binding`() {
        val rows = DeclaredTypeStore.EmptyMemberRows
        assertEquals(0, rows.rowCount)
        for (row in listOf(-1, 0, 1)) {
            val accessors = listOf<() -> Any>(
                { rows.ownerAt(row) }, { rows.nameAt(row) }, { rows.erasedAt(row) },
                { rows.valueAt(row) }, { rows.intValueAt(row) },
                { rows.methodTypeIdAt(row, 0) }, { rows.methodShapeAt(row) })
            for (accessor in accessors) {
                assertEquals("row=$row, size=0", assertFailsWith<IndexOutOfBoundsException> { accessor() }.message)
            }
        }
    }

    @Test
    fun `branch tag lookup rejects missing and out of range node identities`() {
        val offsets = IntNodeOffsetIndex(2).apply { set(0, 0) }
        val data = java.nio.ByteBuffer.allocate(8).putInt(0).put(NodeSerializer.TAG_STRING_CONSTANT.toByte())
        val tags = mappedBranchDefinitionNodeTags(offsets, data)
        assertEquals(NodeSerializer.TAG_STRING_CONSTANT, tags.tagOf(0))
        for (id in listOf(-1, 1, 2, Int.MAX_VALUE)) assertEquals(NodeSerializer.NO_NODE, tags.tagOf(id))
    }

    @Test
    fun `shared type dictionary validates newly queried strings after loading finishes`() {
        val dir = java.nio.file.Files.createTempDirectory("shared-types-contract")
        try {
            val strings = StringTable.build(listOf("loaded", "query-only", "bad\uD800"), dir)
            val texts = SharedDeclaredTypeTexts(strings)
            texts.validateId(strings.indexOf("loaded"))
            texts.finishLoading()
            assertEquals("query-only", texts.text(strings.indexOf("query-only")))
            assertEquals("query-only".hashCode(), texts.hash(strings.indexOf("query-only")))
            assertFailsWith<java.nio.charset.MalformedInputException> { texts.validateId(strings.indexOf("bad\uD800")) }
            assertFailsWith<IllegalArgumentException> { texts.validateId(strings.size()) }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun `method metadata rejects changed counts and preserves long parameter vectors`() {
        val method = MethodDescriptor(TypeDescriptor("sample.Owner"), "run", List(40) { TypeDescriptor("int") }, TypeDescriptor("void"))
        val dir = Files.createTempDirectory("method-index-contract")
        try {
            GraphStore.save(DefaultGraph.Builder().addMethod(method).build(), dir)
            val strings = StringTable.load(dir)
            val bytes = ByteBuffer.wrap(Files.readAllBytes(dir.resolve("graph.metadata")))
            val index = MappedMethodIndex.build(bytes, strings, 1)
            assertEquals(listOf(method), index.methods(MethodPattern()).toList())
            assertEquals(emptyList(), index.methods(MethodPattern(parameterTypes = List(40) { "java.*" })).toList())
            val exact = MethodPattern(java.util.regex.Pattern.quote("sample.Owner"), java.util.regex.Pattern.quote("run"),
                List(40) { java.util.regex.Pattern.quote("int") }, java.util.regex.Pattern.quote("void"), useRegex = true)
            assertEquals(listOf(method), MappedMethodIndex.sliceExact(bytes, strings, 1, exact, 10, null))
            for (attempt in listOf<() -> Any?>(
                { MappedMethodIndex.build(bytes, strings, 2) },
                { MappedMethodIndex.sliceExact(bytes, strings, 2, exact, 10, null) }
            )) {
                val failure = assertFailsWith<IllegalArgumentException> { attempt() }
                assertEquals("Metadata method count changed while loading: expected 2, found 1", failure.message)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `untrusted candidate string IDs are checked before their posting lists are merged`() {
        val dir = Files.createTempDirectory("posting-candidate-contract")
        try {
            val strings = StringTable.build(listOf("alpha", "beta", "absent"), dir)
            val alpha = strings.indexOf("alpha")
            val beta = strings.indexOf("beta")
            val byString = listOf(alpha to intArrayOf(1, 3), beta to intArrayOf(2)).sortedBy { it.first }
            val ends = byString.runningFold(0) { count, entry -> count + entry.second.size }.drop(1).toIntArray()
            val property = MappedCallSiteStringIndex.PropertyCsr(ends, byString.map { it.first }.toIntArray(),
                byString.flatMap { it.second.toList() }.toIntArray())
            val properties = arrayOf(property)
            val target = CallSitePostingRanges(properties)
            val predicate = StringPropertyPredicate("caller_name", null, StringMatchMode.STARTS_WITH, "al")
            val runtime = CallSitePredicateRuntime(predicate, mutableMapOf(), strings, LongArray(strings.size()))
            property.collectMatchingRanges(0, runtime, intArrayOf(alpha, beta, strings.indexOf("absent")), false, target, null)
            val merged = CallSitePostingRanges(properties)
            merged.addAll(target)
            assertEquals(listOf(1, 3), merged.mergedNodeIds(Int::toLong, null).toList())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `bounded projection cache evicts least recently read entry and keeps fresh values`() {
        val cache = RawProjectionMatches()
        fun key(index: Int) = RawProjectionMatchKey(listOf(
            StringPropertyPredicate("caller_name", null, StringMatchMode.EQUALS, "method$index")), 1)
        repeat(MAX_RAW_PROJECTION_MATCH_ENTRIES) { cache.put(key(it), intArrayOf(it)) }
        assertEquals(listOf(0), cache[key(0)]?.toList())
        cache.put(key(MAX_RAW_PROJECTION_MATCH_ENTRIES), intArrayOf(99))
        assertNull(cache[key(1)], "the cold entry is evicted")
        assertEquals(listOf(0), cache[key(0)]?.toList(), "a recent read protects its entry")
        assertEquals(listOf(99), cache[key(MAX_RAW_PROJECTION_MATCH_ENTRIES)]?.toList())
        assertEquals(MAX_RAW_PROJECTION_MATCH_ENTRIES, cache.size())
    }

    @Test
    fun `legacy two-bit edge labels preserve all four families and endpoints`() {
        val families = listOf(io.johnsonlee.graphite.core.DataFlowEdge::class, io.johnsonlee.graphite.core.CallEdge::class,
            io.johnsonlee.graphite.core.TypeEdge::class, io.johnsonlee.graphite.core.ControlFlowEdge::class)
        for (label in 0..3) {
            val decoded = NodeSerializer.decodeEdge(label, io.johnsonlee.graphite.core.NodeId(1),
                io.johnsonlee.graphite.core.NodeId(2), version = 2)
            assertEquals(families[label], decoded::class)
            assertEquals(io.johnsonlee.graphite.core.NodeId(1), decoded.from)
            assertEquals(io.johnsonlee.graphite.core.NodeId(2), decoded.to)
        }
    }
}
