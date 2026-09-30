package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.LocalDefinition
import io.johnsonlee.graphite.core.ConstantNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.incoming
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Persistence of branch-side constant definitions through the optional `graph.branchdefs` sidecar.
 */
class BranchDefinitionPersistenceTest {

    private val method = MethodDescriptor(TypeDescriptor("com.example.Foo"), "check", emptyList(), TypeDescriptor("int"))

    private class Fixture(
        val graph: Graph,
        val condId: NodeId,
        val otherCondId: NodeId,
        val localId: NodeId,
        val trueDefinitions: List<LocalDefinition>,
        val falseDefinitions: List<LocalDefinition>,
        val allDefinitions: List<LocalDefinition>
    )

    /**
     * `x = 1` in straight-line code, then a branch on `on` whose true side assigns `x = 1` twice and whose
     * false side assigns `x = 0`; a second scope on `p` carries no definitions. Node ids are fixed so two
     * fixtures share them. With [swapSides] the `x = 1` writes sit on the false side and `x = 0` on the true
     * side, which leaves every persisted graph file byte-identical.
     */
    private fun buildGraph(constantEdges: Boolean = true, swapSides: Boolean = false): Fixture {
        val builder = DefaultGraph.Builder()
        builder.addMethod(method)
        val cond = LocalVariable(NodeId(1), "on", TypeDescriptor("boolean"), method)
        val otherCond = LocalVariable(NodeId(2), "p", TypeDescriptor("boolean"), method)
        val local = LocalVariable(NodeId(3), "x", TypeDescriptor("int"), method)
        val one = IntConstant(NodeId(4), 1)
        val zero = IntConstant(NodeId(5), 0)
        val other = LocalVariable(NodeId(6), "y", TypeDescriptor("int"), method)
        listOf(cond, otherCond, local, one, zero, other).forEach { builder.addNode(it) }
        val comparison = BranchComparison(ComparisonOp.EQ, zero.id)

        val straightLine = LocalDefinition(1, local.id, one.id)
        val ones = listOf(LocalDefinition(3, local.id, one.id), LocalDefinition(5, local.id, one.id))
        val zeros = listOf(LocalDefinition(8, local.id, zero.id))
        val copy = LocalDefinition(9, local.id, null)
        val trueDefinitions = if (swapSides) zeros else ones
        val falseDefinitions = (if (swapSides) ones else zeros) + copy
        val all = (listOf(straightLine) + ones + zeros + copy).sortedBy { it.stmtOrdinal }
        if (constantEdges) {
            all.forEach { definition ->
                builder.addEdge(DataFlowEdge(definition.constantNodeId ?: other.id, definition.localNodeId, DataFlowKind.ASSIGN))
            }
        } else {
            builder.addEdge(DataFlowEdge(other.id, local.id, DataFlowKind.ASSIGN))
        }
        builder.addBranchScope(
            cond.id, method, comparison, intArrayOf(local.id.value), intArrayOf(local.id.value, other.id.value),
            trueDefinitions = BranchScope.packDefinitions(trueDefinitions),
            falseDefinitions = BranchScope.packDefinitions(falseDefinitions)
        )
        builder.addBranchScope(otherCond.id, method, comparison, intArrayOf(zero.id.value), intArrayOf())
        builder.addLocalDefinitions(local.id, BranchScope.packDefinitions(all))
        return Fixture(builder.build(), cond.id, otherCond.id, local.id, trueDefinitions, falseDefinitions, all)
    }

    private fun withSavedGraph(block: (Fixture, Path) -> Unit) {
        val fixture = buildGraph()
        val dir = Files.createTempDirectory("webgraph-branchdefs-test")
        try {
            GraphStore.save(fixture.graph, dir)
            block(fixture, dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun <T> capturingStderr(block: () -> T): Pair<T, String> {
        val previous = System.err
        val buffer = ByteArrayOutputStream()
        System.setErr(PrintStream(buffer, true))
        return try {
            block() to buffer.toString()
        } finally {
            System.setErr(previous)
        }
    }

    /** Load and touch the branch scopes, which is when the sidecar is read and any warning printed. */
    private fun loadScopes(dir: Path, mode: GraphStore.LoadMode = GraphStore.LoadMode.EAGER): Pair<Graph, String> {
        val (graph, stderr) = capturingStderr { GraphStore.load(dir, mode).also { it.branchScopes().count() } }
        return graph to stderr
    }

    private fun assertLoadedWithDefinitions(fixture: Fixture, loaded: Graph) {
        val scopes = loaded.branchScopes().toList()
        assertEquals(fixture.graph.branchScopes().toList(), scopes, "scopes survive a save/load round-trip field by field")
        val withDefinitions = loaded.branchScopesFor(fixture.condId).single()
        assertEquals(fixture.trueDefinitions, withDefinitions.trueDefinitions)
        assertEquals(fixture.falseDefinitions, withDefinitions.falseDefinitions)
        val without = loaded.branchScopesFor(fixture.otherCondId).single()
        assertEquals(emptyList(), without.trueDefinitions)
        assertEquals(emptyList(), without.falseDefinitions)
        assertEquals(fixture.graph.localDefinitions(), loaded.localDefinitions())
        assertEquals(fixture.allDefinitions, loaded.localDefinitionsFor(fixture.localId))
        assertEquals(emptyList(), loaded.localDefinitionsFor(fixture.condId))
    }

    private fun assertLoadedWithoutDefinitions(fixture: Fixture, loaded: Graph) {
        val original = fixture.graph.branchScopes().toList()
        val scopes = loaded.branchScopes().toList()
        assertEquals(original.size, scopes.size)
        assertTrue(scopes.all { it.trueDefinitions.isEmpty() && it.falseDefinitions.isEmpty() }, "definitions are empty: $scopes")
        assertEquals(
            original.map { it.copy(trueDefinitions = emptyList(), falseDefinitions = emptyList()) },
            scopes,
            "every other field is unchanged"
        )
        assertNotEquals(original, scopes, "a graph read without its definitions is not equal to the built one")
        assertEquals(emptyMap(), loaded.localDefinitions())
        assertEquals(emptyList(), loaded.localDefinitionsFor(fixture.localId))
    }

    private fun sidecar(dir: Path): Path = dir.resolve(GraphStore.BRANCH_DEFINITIONS_FILE)

    private fun metadata(dir: Path): Path = dir.resolve("graph.metadata")

    private val preamble = NodeSerializer.BRANCH_DEFINITIONS_PREAMBLE_BYTES

    private fun rewriteHeader(dir: Path, header: Int) {
        val bytes = Files.readAllBytes(sidecar(dir))
        ByteBuffer.wrap(bytes).putInt(0, header)
        Files.write(sidecar(dir), bytes)
    }

    /**
     * Replace the payload and re-sign it in both the sidecar preamble and the metadata trailer, so only
     * the payload's meaning changes.
     */
    private val noNodes = NodeSerializer.NodeTagLookup { NodeSerializer.NO_NODE }

    private fun rewritePayload(dir: Path, edit: (ByteBuffer) -> Unit) =
        replacePayload(dir) { payload -> payload.also { edit(ByteBuffer.wrap(it)) } }

    /** Replace the payload and re-sign the sidecar preamble and the metadata trailer for it. */
    private fun replacePayload(dir: Path, transform: (ByteArray) -> ByteArray) {
        val bytes = Files.readAllBytes(sidecar(dir))
        val payload = transform(bytes.copyOfRange(preamble, bytes.size))
        val digest = MessageDigest.getInstance(NodeSerializer.DIGEST_ALGORITHM).digest(payload)
        val signed = ByteBuffer.wrap(bytes.copyOfRange(0, preamble))
        signed.position(Int.SIZE_BYTES)
        signed.putInt(payload.size)
        signed.put(digest)
        Files.write(sidecar(dir), signed.array() + payload)
        val metadataBytes = Files.readAllBytes(metadata(dir))
        System.arraycopy(digest, 0, metadataBytes, metadataBytes.size - NodeSerializer.DIGEST_BYTES, digest.size)
        Files.write(metadata(dir), metadataBytes)
    }

    /** Drop the metadata trailer, as a writer that predates the sidecar leaves `graph.metadata`. */
    private fun stripMetadataTrailer(dir: Path) {
        val bytes = Files.readAllBytes(metadata(dir))
        Files.write(metadata(dir), bytes.copyOf(bytes.size - NodeSerializer.METADATA_TRAILER_BYTES))
    }

    @Test
    fun `save writes the sidecar and eager load restores definitions field by field`() = withSavedGraph { fixture, dir ->
        assertTrue(Files.isRegularFile(sidecar(dir)))
        val (loaded, stderr) = loadScopes(dir)
        assertEquals("", stderr, "no warning on a matching sidecar")
        assertLoadedWithDefinitions(fixture, loaded)
    }

    @Test
    fun `mapped load restores definitions field by field`() = withSavedGraph { fixture, dir ->
        val (loaded, stderr) = loadScopes(dir, GraphStore.LoadMode.MAPPED)
        try {
            assertEquals("", stderr)
            assertLoadedWithDefinitions(fixture, loaded)
        } finally {
            (loaded as Closeable).close()
        }
    }

    @Test
    fun `persisted graphs collapse repeated edges but keep every definition`() = withSavedGraph { fixture, dir ->
        val sourceEdges = fixture.graph.incoming<DataFlowEdge>(fixture.localId)
            .filter { it.kind == DataFlowKind.ASSIGN && fixture.graph.node(it.from) is ConstantNode }.toList()
        assertEquals(4, sourceEdges.size, "the source graph keeps one ASSIGN edge per constant definition")

        val (loaded, _) = loadScopes(dir)
        val loadedEdges = loaded.incoming<DataFlowEdge>(fixture.localId)
            .filter { it.kind == DataFlowKind.ASSIGN && loaded.node(it.from) is ConstantNode }.toList()
        assertEquals(2, loadedEdges.size, "one arc per (constant, local) pair survives persistence")

        // The consumer subtracts the killed side from the local's full definition list, never from its edges.
        val scope = loaded.branchScopesFor(fixture.condId).single()
        val killedTrue = scope.trueDefinitions.map { it.stmtOrdinal }.toSet()
        val aliveAfterTrue = loaded.localDefinitionsFor(fixture.localId).filter { it.stmtOrdinal !in killedTrue }
        assertEquals(listOf(1, 8, 9), aliveAfterTrue.map { it.stmtOrdinal }, "x = 1, x = 0 and x = y stay alive")
        assertTrue(aliveAfterTrue.any { !it.isConstant }, "the surviving x = y blocks folding")

        val killedFalse = scope.falseDefinitions.map { it.stmtOrdinal }.toSet()
        val aliveAfterFalse = loaded.localDefinitionsFor(fixture.localId).filter { it.stmtOrdinal !in killedFalse }
        assertEquals(listOf(1, 3, 5), aliveAfterFalse.map { it.stmtOrdinal }, "only the x = 1 writes stay alive")
        assertTrue(aliveAfterFalse.all { it.constantNodeId == NodeId(4) }, "every survivor is x = 1, so x folds to 1")
    }

    @Test
    fun `a graph without definitions still writes a sidecar with zero counts`() {
        val builder = DefaultGraph.Builder()
        builder.addMethod(method)
        val cond = IntConstant(NodeId.next(), 0)
        builder.addNode(cond)
        builder.addBranchScope(cond.id, method, BranchComparison(ComparisonOp.NE, cond.id), intArrayOf(cond.id.value), intArrayOf())
        val dir = Files.createTempDirectory("webgraph-branchdefs-empty-test")
        try {
            GraphStore.save(builder.build(), dir)
            // preamble + scopeCount + (trueCount + falseCount) + localCount
            assertEquals(preamble + 4L * Int.SIZE_BYTES, Files.size(sidecar(dir)))
            assertNotNull(trailerDigest(dir), "graph.metadata carries the trailer even without definitions")
            val (loaded, stderr) = loadScopes(dir)
            assertEquals("", stderr)
            val scope = loaded.branchScopes().single()
            assertEquals(emptyList(), scope.trueDefinitions)
            assertEquals(emptyList(), scope.falseDefinitions)
            assertEquals(emptyMap(), loaded.localDefinitions())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun trailerDigest(dir: Path): ByteArray? {
        val bytes = Files.readAllBytes(metadata(dir))
        val trailerBytes = NodeSerializer.METADATA_TRAILER_BYTES
        val input = java.io.DataInputStream(bytes.inputStream(bytes.size - trailerBytes, trailerBytes))
        return NodeSerializer.readMetadataTrailer(input)
    }

    @Test
    fun `a graph without branch scopes never reads the sidecar`() {
        val builder = DefaultGraph.Builder()
        builder.addMethod(method)
        builder.addNode(IntConstant(NodeId.next(), 0))
        val dir = Files.createTempDirectory("webgraph-branchdefs-noscopes-test")
        try {
            GraphStore.save(builder.build(), dir)
            Files.delete(sidecar(dir))
            val (loaded, stderr) = loadScopes(dir)
            assertEquals("", stderr)
            assertEquals(0, loaded.branchScopes().count())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `saving twice replaces the sidecar`() = withSavedGraph { fixture, dir ->
        val before = Files.readAllBytes(sidecar(dir))
        Files.write(sidecar(dir), before + byteArrayOf(1, 2, 3))
        GraphStore.save(fixture.graph, dir)
        assertTrue(before.contentEquals(Files.readAllBytes(sidecar(dir))))
    }

    @Test
    fun `a missing sidecar loads empty definitions with a warning`() = withSavedGraph { fixture, dir ->
        Files.delete(sidecar(dir))
        val (loaded, stderr) = loadScopes(dir)
        assertTrue(stderr.contains("Warning") && stderr.contains("is missing"), stderr)
        assertLoadedWithoutDefinitions(fixture, loaded)
    }

    @Test
    fun `a missing sidecar loads empty definitions on the mapped path too`() = withSavedGraph { fixture, dir ->
        Files.delete(sidecar(dir))
        val (loaded, stderr) = loadScopes(dir, GraphStore.LoadMode.MAPPED)
        try {
            assertTrue(stderr.contains("is missing"), stderr)
            assertLoadedWithoutDefinitions(fixture, loaded)
        } finally {
            (loaded as Closeable).close()
        }
    }

    @Test
    fun `a wrong scope count loads empty definitions with a warning`() = withSavedGraph { fixture, dir ->
        rewritePayload(dir) { it.putInt(0, 7) }
        val (loaded, stderr) = loadScopes(dir)
        assertTrue(stderr.contains("does not match (7 branch scopes"), stderr)
        assertLoadedWithoutDefinitions(fixture, loaded)
    }

    @Test
    fun `a wrong magic loads empty definitions`() = withSavedGraph { fixture, dir ->
        rewriteHeader(dir, NodeSerializer.MAGIC_METADATA or NodeSerializer.BRANCH_DEFINITIONS_VERSION)
        val (loaded, stderr) = loadScopes(dir)
        assertTrue(stderr.contains("does not match (unknown header"), stderr)
        assertLoadedWithoutDefinitions(fixture, loaded)
    }

    @Test
    fun `an unknown version loads empty definitions`() = withSavedGraph { fixture, dir ->
        rewriteHeader(dir, NodeSerializer.MAGIC_BRANCHDEFS or (NodeSerializer.BRANCH_DEFINITIONS_VERSION + 1))
        val (loaded, stderr) = loadScopes(dir)
        assertTrue(stderr.contains("does not match (unknown header"), stderr)
        assertLoadedWithoutDefinitions(fixture, loaded)
    }

    @Test
    fun `a sidecar left behind by an older writer that re-saved the directory loads empty definitions`() {
        val fixture = buildGraph()
        val dir = Files.createTempDirectory("webgraph-branchdefs-stale-test")
        try {
            GraphStore.save(fixture.graph, dir)
            val stale = Files.readAllBytes(sidecar(dir))

            // The same nodes, ids, methods and branch scopes, but `x = y` instead of the constant ASSIGN
            // edges, re-saved by a writer that knows nothing about the sidecar or the metadata trailer.
            val rewritten = buildGraph(constantEdges = false)
            GraphStore.save(rewritten.graph, dir)
            stripMetadataTrailer(dir)
            Files.write(sidecar(dir), stale)

            val (loaded, stderr) = loadScopes(dir)
            assertTrue(stderr.contains("graph.metadata carries no branch-definition digest"), stderr)
            assertLoadedWithoutDefinitions(rewritten, loaded)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a stale sidecar whose sides are swapped is rejected although every graph file is byte-identical`() {
        val fixture = buildGraph()
        val swapped = buildGraph(swapSides = true)
        val dir = Files.createTempDirectory("webgraph-branchdefs-swap-test")
        val swappedDir = Files.createTempDirectory("webgraph-branchdefs-swap-other")
        try {
            GraphStore.save(fixture.graph, dir)
            GraphStore.save(swapped.graph, swappedDir)
            assertOnlyAttributionDiffers(dir, swappedDir)

            // An older writer re-saves the swapped graph over the first one: no trailer, old sidecar left behind.
            val stale = Files.readAllBytes(sidecar(dir))
            GraphStore.save(swapped.graph, dir)
            stripMetadataTrailer(dir)
            Files.write(sidecar(dir), stale)
            val (loadedAfterOldWriter, oldWriterStderr) = loadScopes(dir)
            assertTrue(oldWriterStderr.contains("carries no branch-definition digest"), oldWriterStderr)
            assertLoadedWithoutDefinitions(swapped, loadedAfterOldWriter)

            // A current writer re-saves the swapped graph but the old sidecar is put back: digests disagree.
            GraphStore.save(swapped.graph, dir)
            Files.write(sidecar(dir), stale)
            val (loadedAfterNewWriter, newWriterStderr) = loadScopes(dir)
            assertTrue(newWriterStderr.contains("does not match the definitions graph.metadata was written with"), newWriterStderr)
            assertLoadedWithoutDefinitions(swapped, loadedAfterNewWriter)
        } finally {
            dir.toFile().deleteRecursively()
            swappedDir.toFile().deleteRecursively()
        }
    }

    /** Every persisted file is byte-identical except the two that carry the side attribution. */
    private fun assertOnlyAttributionDiffers(dir: Path, other: Path) {
        val names = Files.list(dir).use { files -> files.filter { Files.isRegularFile(it) }.map { it.fileName.toString() }.toList() }
        for (name in names) {
            val identical = Files.readAllBytes(dir.resolve(name)).contentEquals(Files.readAllBytes(other.resolve(name)))
            if (name == GraphStore.BRANCH_DEFINITIONS_FILE || name == "graph.metadata") {
                assertFalse(identical, "$name must differ: it carries the side attribution")
            } else {
                assertTrue(identical, "$name is byte-identical between the two graphs")
            }
        }
    }

    @Test
    fun `a flipped payload bit loads empty definitions`() = withSavedGraph { fixture, dir ->
        val bytes = Files.readAllBytes(sidecar(dir))
        val constantIdOffset = preamble + 4 * Int.SIZE_BYTES  // first triple's constantNodeId
        bytes[constantIdOffset + 3] = (bytes[constantIdOffset + 3].toInt() xor 1).toByte()
        Files.write(sidecar(dir), bytes)
        val (loaded, stderr) = loadScopes(dir)
        assertTrue(stderr.contains("is corrupt (payload digest mismatch)"), stderr)
        assertLoadedWithoutDefinitions(fixture, loaded)
    }

    @Test
    fun `trailing bytes load empty definitions`() = withSavedGraph { fixture, dir ->
        Files.write(sidecar(dir), Files.readAllBytes(sidecar(dir)) + byteArrayOf(0, 0, 0, 0))
        val (loaded, stderr) = loadScopes(dir)
        assertTrue(stderr.contains("is corrupt (declared payload"), stderr)
        assertLoadedWithoutDefinitions(fixture, loaded)
    }

    @Test
    fun `a signed payload that ends inside a field loads empty definitions`() = withSavedGraph { fixture, dir ->
        val bytes = Files.readAllBytes(sidecar(dir))
        // Keep only the scope count: a correctly signed four-byte payload.
        val payload = bytes.copyOfRange(preamble, preamble + Int.SIZE_BYTES)
        val digest = MessageDigest.getInstance(NodeSerializer.DIGEST_ALGORITHM).digest(payload)
        val signed = ByteBuffer.wrap(bytes.copyOfRange(0, preamble))
        signed.position(Int.SIZE_BYTES)
        signed.putInt(payload.size)
        signed.put(digest)
        Files.write(sidecar(dir), signed.array() + payload)
        val metadataBytes = Files.readAllBytes(metadata(dir))
        System.arraycopy(digest, 0, metadataBytes, metadataBytes.size - NodeSerializer.DIGEST_BYTES, digest.size)
        Files.write(metadata(dir), metadataBytes)

        val (loaded, stderr) = loadScopes(dir)
        assertTrue(stderr.contains("is corrupt (payload ends inside a field)"), stderr)
        assertLoadedWithoutDefinitions(fixture, loaded)
    }

    @Test
    fun `a declared payload above the budget is rejected before anything is allocated`() {
        val preambleBytes = ByteBuffer.allocate(preamble)
        preambleBytes.putInt(NodeSerializer.MAGIC_BRANCHDEFS or NodeSerializer.BRANCH_DEFINITIONS_VERSION)
        preambleBytes.putInt(1 shl 20)
        preambleBytes.put(ByteArray(NodeSerializer.DIGEST_BYTES))
        val rejected = NodeSerializer.decodeBranchDefinitionPreamble(
            preambleBytes.array(), (1 shl 20).toLong(), ByteArray(NodeSerializer.DIGEST_BYTES), maxPayloadBytes = 1 shl 16
        )
        assertNull(rejected.payloadDigest)
        assertTrue(rejected.rejection!!.contains("exceeds the 65536-byte budget"), rejected.rejection)

        val accepted = NodeSerializer.decodeBranchDefinitionPreamble(
            preambleBytes.array(), (1 shl 20).toLong(), ByteArray(NodeSerializer.DIGEST_BYTES)
        )
        assertNotNull(accepted.payloadDigest)
    }

    @Test
    fun `a truncated sidecar loads empty definitions`() = withSavedGraph { fixture, dir ->
        val bytes = Files.readAllBytes(sidecar(dir))
        Files.write(sidecar(dir), bytes.copyOf(bytes.size - 2))
        val (loaded, stderr) = loadScopes(dir)
        assertTrue(stderr.contains("is corrupt (declared payload"), stderr)
        assertLoadedWithoutDefinitions(fixture, loaded)

        Files.write(sidecar(dir), bytes.copyOf(preamble - 1))
        val (short, shortStderr) = loadScopes(dir)
        assertTrue(shortStderr.contains("is corrupt (too short)"), shortStderr)
        assertLoadedWithoutDefinitions(fixture, short)
    }

    @Test
    fun `a definition count larger than the payload loads empty definitions without allocating`() = withSavedGraph { fixture, dir ->
        rewritePayload(dir) { it.putInt(Int.SIZE_BYTES, Int.MAX_VALUE / 3 - 1) }
        val (loaded, stderr) = loadScopes(dir)
        assertTrue(stderr.contains("is corrupt (invalid definition count"), stderr)
        assertLoadedWithoutDefinitions(fixture, loaded)
    }

    @Test
    fun `a negative definition count loads empty definitions`() = withSavedGraph { fixture, dir ->
        rewritePayload(dir) { it.putInt(Int.SIZE_BYTES, -1) }
        val (loaded, stderr) = loadScopes(dir)
        assertTrue(stderr.contains("is corrupt (invalid definition count -1)"), stderr)
        assertLoadedWithoutDefinitions(fixture, loaded)
    }

    @Test
    fun `a bad local table loads empty definitions`() = withSavedGraph { fixture, dir ->
        val scopesEnd = Int.SIZE_BYTES + // scopeCount
            (Int.SIZE_BYTES + fixture.trueDefinitions.size * 3 * Int.SIZE_BYTES) +
            (Int.SIZE_BYTES + fixture.falseDefinitions.size * 3 * Int.SIZE_BYTES) +
            2 * Int.SIZE_BYTES // second scope: two zero counts
        rewritePayload(dir) { it.putInt(scopesEnd, 1_000_000) }
        val (tooMany, stderr) = loadScopes(dir)
        assertTrue(stderr.contains("is corrupt (invalid local count 1000000)"), stderr)
        assertLoadedWithoutDefinitions(fixture, tooMany)

        rewritePayload(dir) {
            it.putInt(scopesEnd, 2)
            it.putInt(scopesEnd + Int.SIZE_BYTES, fixture.localId.value)
            it.putInt(scopesEnd + 2 * Int.SIZE_BYTES, 0)
            it.putInt(scopesEnd + 3 * Int.SIZE_BYTES, fixture.localId.value)
            it.putInt(scopesEnd + 4 * Int.SIZE_BYTES, 0)
        }
        // The table now holds two zero-length entries for the same local in place of the four definitions.
        val (_, duplicateStderr) = loadScopes(dir)
        assertTrue(
            duplicateStderr.contains("is corrupt (invalid local count") || duplicateStderr.contains("duplicate local"),
            duplicateStderr
        )
    }

    @Test
    fun `a local table larger than the graph or with a negative local loads empty definitions`() = withSavedGraph { fixture, dir ->
        val scopesEnd = Int.SIZE_BYTES + // scopeCount
            (Int.SIZE_BYTES + fixture.trueDefinitions.size * 3 * Int.SIZE_BYTES) +
            (Int.SIZE_BYTES + fixture.falseDefinitions.size * 3 * Int.SIZE_BYTES) +
            2 * Int.SIZE_BYTES // second scope: two zero counts
        // The fixture graph has six nodes; declare seven zero-length tables, which the payload could hold.
        rewritePayload(dir) { it.putInt(scopesEnd, 7) }
        val (tooMany, stderr) = loadScopes(dir)
        assertTrue(stderr.contains("is corrupt (invalid local count 7)"), stderr)
        assertLoadedWithoutDefinitions(fixture, tooMany)

        rewritePayload(dir) {
            it.putInt(scopesEnd, 1)
            it.putInt(scopesEnd + Int.SIZE_BYTES, -5)
        }
        val (negative, negativeStderr) = loadScopes(dir)
        assertTrue(negativeStderr.contains("is corrupt (invalid local -5)"), negativeStderr)
        assertLoadedWithoutDefinitions(fixture, negative)

        rewritePayload(dir) { it.putInt(2 * Int.SIZE_BYTES, -3) }  // first triple's stmtOrdinal
        val (badTriple, tripleStderr) = loadScopes(dir)
        assertTrue(tripleStderr.contains("is corrupt (invalid definition [-3,"), tripleStderr)
        assertLoadedWithoutDefinitions(fixture, badTriple)
    }

    @Test
    fun `ids that are not the expected node kind load empty definitions`() = withSavedGraph { fixture, dir ->
        // The fixture's node ids are 1..6 (locals 1, 2, 3, 6; constants 4, 5): 0 and 7 are not nodes.
        val scopesEnd = Int.SIZE_BYTES +
            (Int.SIZE_BYTES + fixture.trueDefinitions.size * 3 * Int.SIZE_BYTES) +
            (Int.SIZE_BYTES + fixture.falseDefinitions.size * 3 * Int.SIZE_BYTES) +
            2 * Int.SIZE_BYTES
        val firstTriple = 2 * Int.SIZE_BYTES  // after scopeCount and the first scope's true count
        val firstTableTriple = scopesEnd + 3 * Int.SIZE_BYTES  // after localCount, localId and the table's count
        val stride = 3 * Int.SIZE_BYTES

        fun rejects(reason: String, edit: (ByteBuffer) -> Unit) {
            rewritePayload(dir, edit)
            val (loaded, stderr) = loadScopes(dir)
            assertTrue(stderr.contains(reason), "expected <$reason> in: $stderr")
            assertLoadedWithoutDefinitions(fixture, loaded)
        }

        // Each rewrite edits the file the previous one left, so every step also restores the field before it.
        val constantOfFirstTriple = firstTriple + 2 * Int.SIZE_BYTES
        rejects("is corrupt (invalid definition [3, 3, 0] in a branch side)") { it.putInt(constantOfFirstTriple, 0) }  // no node 0
        rejects("is corrupt (invalid definition [3, 3, 7] in a branch side)") { it.putInt(constantOfFirstTriple, 7) }  // no node 7
        rejects("is corrupt (invalid definition [3, 3, 6] in a branch side)") { it.putInt(constantOfFirstTriple, 6) }  // a local
        rejects("is corrupt (invalid definition [3, 3, -2] in a branch side)") { it.putInt(firstTriple + 2 * Int.SIZE_BYTES, -2) }
        rejects("is corrupt (side definition [3, 7, 4] has no table)") {
            it.putInt(firstTriple + 2 * Int.SIZE_BYTES, 4)
            it.putInt(firstTriple + Int.SIZE_BYTES, 7)  // side local that is not a node
        }
        rejects("is corrupt (invalid local 7)") {
            it.putInt(firstTriple + Int.SIZE_BYTES, 3)
            it.putInt(scopesEnd + Int.SIZE_BYTES, 7)  // table key that is not a node
        }
        rejects("is corrupt (invalid local 4)") {
            it.putInt(scopesEnd + Int.SIZE_BYTES, 4)  // table key that is a constant node
            repeat(fixture.allDefinitions.size) { index -> it.putInt(firstTableTriple + index * stride + Int.SIZE_BYTES, 4) }
        }
        rejects("is corrupt (invalid definition [1, 6, 4] in the table of local 3)") {
            it.putInt(scopesEnd + Int.SIZE_BYTES, 3)
            repeat(fixture.allDefinitions.size) { index -> it.putInt(firstTableTriple + index * stride + Int.SIZE_BYTES, 3) }
            it.putInt(firstTableTriple + Int.SIZE_BYTES, 6)  // one entry names another local
        }

        rewritePayload(dir) { it.putInt(firstTableTriple + Int.SIZE_BYTES, 3) }
        val (restored, stderr) = loadScopes(dir)
        assertEquals("", stderr)
        assertLoadedWithDefinitions(fixture, restored)
    }

    @Test
    fun `a table that disagrees with the side definitions loads empty definitions`() = withSavedGraph { fixture, dir ->
        val scopesEnd = Int.SIZE_BYTES +
            (Int.SIZE_BYTES + fixture.trueDefinitions.size * 3 * Int.SIZE_BYTES) +
            (Int.SIZE_BYTES + fixture.falseDefinitions.size * 3 * Int.SIZE_BYTES) +
            2 * Int.SIZE_BYTES
        val firstTriple = 2 * Int.SIZE_BYTES
        val firstTableTriple = scopesEnd + 3 * Int.SIZE_BYTES
        val stride = 3 * Int.SIZE_BYTES

        fun rejects(reason: String, mode: GraphStore.LoadMode = GraphStore.LoadMode.EAGER, edit: (ByteBuffer) -> Unit) {
            rewritePayload(dir, edit)
            val (loaded, stderr) = loadScopes(dir, mode)
            try {
                assertTrue(stderr.contains(reason), "expected <$reason> in: $stderr")
                assertLoadedWithoutDefinitions(fixture, loaded)
            } finally {
                (loaded as? Closeable)?.close()
            }
        }

        // The only internally consistent table moves from x (3) to y (6): x's side definitions lose their table.
        rejects("is corrupt (side definition [3, 3, 4] has no table)") {
            it.putInt(scopesEnd + Int.SIZE_BYTES, 6)
            repeat(fixture.allDefinitions.size) { index -> it.putInt(firstTableTriple + index * stride + Int.SIZE_BYTES, 6) }
        }
        rejects("is corrupt (side definition [3, 3, 4] has no table)", GraphStore.LoadMode.MAPPED) {}
        rejects("is corrupt (side definition [3, 3, 5] is not in the table of local 3)") {
            it.putInt(scopesEnd + Int.SIZE_BYTES, 3)
            repeat(fixture.allDefinitions.size) { index -> it.putInt(firstTableTriple + index * stride + Int.SIZE_BYTES, 3) }
            it.putInt(firstTriple + 2 * Int.SIZE_BYTES, 5)  // the side says x = 0 where the table says x = 1
        }
        rejects("is corrupt (side definition [4, 3, 4] is not in the table of local 3)") {
            it.putInt(firstTriple + 2 * Int.SIZE_BYTES, 4)
            it.putInt(firstTriple, 4)  // an ordinal the table has no entry for
        }
        rejects("is corrupt (unordered definitions in the table of local 3)") {
            it.putInt(firstTriple, 3)
            it.putInt(firstTableTriple, 3)  // entries 0 and 1 (ordinals 1 and 3) swap ordinals
            it.putInt(firstTableTriple + stride, 1)
        }
        rewritePayload(dir) {
            it.putInt(firstTableTriple, 1)
            it.putInt(firstTableTriple + stride, 3)
        }

        // The true side's first definition [3, 3, 4] copied over the false side's first entry:
        // one statement on both sides of the scope.
        val firstFalseTriple = firstTriple + fixture.trueDefinitions.size * stride + Int.SIZE_BYTES
        rejects("is corrupt (definition [3, 3, 4] is on both sides of a scope)") {
            it.putInt(firstFalseTriple, 3)
            it.putInt(firstFalseTriple + Int.SIZE_BYTES, 3)
            it.putInt(firstFalseTriple + 2 * Int.SIZE_BYTES, 4)
        }
        rejects("is corrupt (definition [3, 3, 4] is on both sides of a scope)", GraphStore.LoadMode.MAPPED) {}
        rejects("is corrupt (unordered definitions in a branch side)") {
            it.putInt(firstFalseTriple, 8)
            it.putInt(firstFalseTriple + 2 * Int.SIZE_BYTES, 5)
            it.putInt(firstTriple, 5)  // true side: ordinals 5, 5
        }
        rewritePayload(dir) { it.putInt(firstTriple, 3) }

        // An extra, internally consistent table for y, which no side defines.
        replacePayload(dir) { payload ->
            ByteBuffer.wrap(payload).putInt(scopesEnd, 2)
            payload + ByteBuffer.allocate(2 * Int.SIZE_BYTES).putInt(6).putInt(0).array()
        }
        val (extra, extraStderr) = loadScopes(dir)
        assertTrue(extraStderr.contains("is corrupt (1 table(s) of locals without branch-side definitions)"), extraStderr)
        assertLoadedWithoutDefinitions(fixture, extra)
    }

    @Test
    fun `loadBranchDefinitions short-circuits without scopes and reports an unreadable file`() {
        val dir = Files.createTempDirectory("webgraph-branchdefs-unreadable")
        try {
            val none = GraphMetadata(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyList())
            val (empty, stderr) = capturingStderr { GraphStore.loadBranchDefinitions(dir, none, 0, noNodes) }
            assertSame(PersistedBranchDefinitions.EMPTY, empty)
            assertEquals("", stderr)

            Files.createDirectories(sidecar(dir))  // a directory where the file should be
            val scope = BranchScopeData(0, method, BranchComparison(ComparisonOp.EQ, NodeId(1)), intArrayOf(), intArrayOf())
            val one = none.copy(branchScopes = listOf(scope), branchDefinitionDigest = ByteArray(NodeSerializer.DIGEST_BYTES))
            val (rejected, dirStderr) = capturingStderr { GraphStore.loadBranchDefinitions(dir, one, 1, noNodes) }
            assertSame(PersistedBranchDefinitions.EMPTY, rejected)
            assertTrue(dirStderr.contains("is missing"), dirStderr)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `decodeBranchDefinitionPreamble rejects a bad header before touching the payload`() {
        val bytes = ByteArray(preamble)
        ByteBuffer.wrap(bytes).putInt(NodeSerializer.MAGIC_COMPARISONS or NodeSerializer.BRANCH_DEFINITIONS_VERSION)
        val decoded = NodeSerializer.decodeBranchDefinitionPreamble(bytes, 0, ByteArray(NodeSerializer.DIGEST_BYTES))
        assertNull(decoded.payloadDigest)
        assertNotNull(decoded.rejection)
    }

    @Test
    fun `writeMetadataTrailer rejects a digest of the wrong length`() {
        assertFailsWith<IllegalArgumentException> {
            NodeSerializer.writeMetadataTrailer(DataOutputStream(ByteArrayOutputStream()), ByteArray(16))
        }
    }

    @Test
    fun `readMetadataTrailer ignores a trailer with another magic`() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { it.writeInt(NodeSerializer.MAGIC_COMPARISONS); it.write(ByteArray(NodeSerializer.DIGEST_BYTES)) }
        assertNull(NodeSerializer.readMetadataTrailer(java.io.DataInputStream(out.toByteArray().inputStream())))
    }

    @Test
    fun `BranchScopeData compares and hashes its definitions`() {
        val comparison = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val base = BranchScopeData(0, method, comparison, intArrayOf(1), intArrayOf(2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8))
        val same = BranchScopeData(0, method, comparison, intArrayOf(1), intArrayOf(2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8))
        assertEquals(base, same)
        assertEquals(base.hashCode(), same.hashCode())
        assertNotEquals(base, base.copy(trueDefinitions = intArrayOf(3, 4, 9)))
        assertNotEquals(base, base.copy(falseDefinitions = BranchScope.EMPTY_DEFINITIONS))

        val scope = base.toBranchScope()
        assertEquals(listOf(LocalDefinition(3, NodeId(4), NodeId(5))), scope.trueDefinitions)
        assertEquals(listOf(LocalDefinition(6, NodeId(7), NodeId(8))), scope.falseDefinitions)
        assertTrue(scope.trueBranchNodeIds.contains(1))
        assertTrue(scope.falseBranchNodeIds.contains(2))

        val fromSidecar = base.toBranchScope(intArrayOf(9, 4, 5) to BranchScope.EMPTY_DEFINITIONS)
        assertEquals(listOf(LocalDefinition(9, NodeId(4), NodeId(5))), fromSidecar.trueDefinitions)
        assertEquals(emptyList(), fromSidecar.falseDefinitions)
    }
}
