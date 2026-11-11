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
    fun `persistent index rejects a negative callsite count before retaining memory`() {
        val dir = java.nio.file.Files.createTempDirectory("negative-index-count")
        try {
            val strings = StringTable.build(listOf("value"), dir)
            val identity = ByteArray(CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES)
            val context = CallSiteIndexReadContext(strings.size(), -1, identity,
                { error("invalid counts must not inspect node order") }, 0,
                { _, _ -> error("invalid counts must not read properties") }, strings)
            val bytes = java.io.ByteArrayOutputStream()
            java.io.DataOutputStream(bytes).use { output ->
                output.writeInt(CALL_SITE_STRING_INDEX_MAGIC)
                output.writeInt(CALL_SITE_STRING_INDEX_VERSION)
                output.writeInt(strings.size())
                output.writeInt(-1)
                output.write(identity)
                repeat(CALL_SITE_STRING_PROPERTY_COUNT) { output.writeInt(0) }
                output.writeInt(1)
                output.writeLong(0)
            }
            val before = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
            java.io.DataInputStream(bytes.toByteArray().inputStream()).use { input ->
                assertNull(MappedCallSiteStringIndex.readPersistent(input, context))
            }
            assertEquals(before, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun `posting validation cache keeps the first verdict and releases ownership on close`() {
        val before = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
        val cache = kotlin.test.assertNotNull(BoundedPostingRangeValidationCache.create())
        assertTrue(cache.retainedBytes > 0)
        assertNull(cache[7L])
        kotlin.test.assertFalse(cache.putIfAbsent(7L, false))
        kotlin.test.assertFalse(cache.putIfAbsent(7L, true), "a later validator cannot overwrite an existing rejection")
        assertEquals(false, cache[7L])
        assertEquals(1, cache.size())
        cache.close()
        cache.close()
        assertEquals(before, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
        assertEquals(0L, cache.retainedBytes)
        assertEquals(0, cache.size())
        assertNull(cache[7L])
        assertTrue(cache.putIfAbsent(7L, true), "closed caches do not retain a new caller's verdict")
    }

    @Test
    fun `oversized type index is rejected before creating any mapped file`() {
        val dir = java.nio.file.Files.createTempDirectory("type-index-overflow")
        val file = dir.resolve("node-types")
        try {
            val counts = IntArray(256).apply { this[NodeSerializer.TAG_INT_CONSTANT] = Int.MAX_VALUE }
            val failed = assertFailsWith<IllegalArgumentException> { TypeIndexWriter(file, counts) }
            assertTrue(failed.message.orEmpty().startsWith("Mapped node type index is too large to map as a single buffer:"))
            kotlin.test.assertFalse(java.nio.file.Files.exists(file), "size validation must precede storage allocation")
        } finally { dir.toFile().deleteRecursively() }
    }

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
    fun `serializer branch facade validates actual node tags and preserves packed definitions`() {
        val method = MethodDescriptor(TypeDescriptor("sample.Owner"), "run", emptyList(), TypeDescriptor("void"))
        val local = io.johnsonlee.graphite.core.LocalVariable(NodeId(0), "value", TypeDescriptor("int"), method)
        val constant = io.johnsonlee.graphite.core.IntConstant(NodeId(1), 7)
        val packed = intArrayOf(2, local.id.value, constant.id.value)
        val scope = BranchScopeData(local.id.value, method,
            io.johnsonlee.graphite.core.BranchComparison(io.johnsonlee.graphite.core.ComparisonOp.EQ, constant.id),
            intArrayOf(local.id.value), intArrayOf(), packed, intArrayOf())
        val encoded = encodeBranchDefinitions(listOf(scope), mapOf(local.id.value to packed))
        val payload = encoded.bytes.copyOfRange(NodeSerializer.BRANCH_DEFINITIONS_PREAMBLE_BYTES, encoded.bytes.size)
        val inspected = mutableSetOf<Int>()
        val nodes = listOf(local, constant)
        val decoded = NodeSerializer.decodeBranchDefinitionPayload(payload, 1, encoded.payloadDigest, nodes.size,
            NodeSerializer.NodeTagLookup { id -> inspected.add(id); NodeSerializer.tagOf(nodes[id]) })
        assertNull(decoded.rejection)
        val definitions = kotlin.test.assertNotNull(decoded.definitions)
        assertEquals(setOf(0, 1), inspected, "the facade must pass both local and constant tag lookups to the validator")
        kotlin.test.assertContentEquals(packed, definitions.locals.getValue(local.id.value))
        kotlin.test.assertContentEquals(packed, definitions.scopes.single().first)
        kotlin.test.assertContentEquals(intArrayOf(), definitions.scopes.single().second)
        val wrongTags = NodeSerializer.decodeBranchDefinitionPayload(payload, 1, encoded.payloadDigest, nodes.size,
            NodeSerializer.NodeTagLookup { NodeSerializer.TAG_LOCAL_VARIABLE })
        assertNull(wrongTags.definitions, "a valid digest must not bypass the referenced constant's node type")
        assertTrue(wrongTags.rejection.orEmpty().contains("invalid definition"))
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
