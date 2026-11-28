package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.CallSiteGap
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.nodes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The JVM reader on a graph another writer produced. `backend/storage/fixtures/importer-graph` is the directory the Rust
 * importer writes for `importer-graph.json` (a Rust test keeps it so), with the files that importer writes and no others:
 * it holds every required file of `graph-files.txt` and no `graph.comparisons`, which the JVM loaders used to open
 * unconditionally, so `graphite.jar query` failed on every JavaScript graph.
 */
class ImporterGraphInteropTest {
    private val root = Files.createTempDirectory("importer-graph-interop")

    @AfterTest
    fun cleanup() { root.toFile().deleteRecursively() }

    /** A private copy: the reader writes derived files beside a graph it reads. */
    private fun importerGraph(): Path {
        val source = Path.of(javaClass.getResource("/importer-graph/graph.manifest")!!.toURI()).parent
        val copy = Files.createDirectory(root.resolve("graph-${System.nanoTime()}"))
        source.listDirectoryEntries().forEach { Files.copy(it, copy.resolve(it.name)) }
        return copy
    }

    private fun <T> Graph.use(block: (Graph) -> T): T = try { block(this) } finally { (this as? AutoCloseable)?.close() }

    @Test
    fun `the importer's graph holds every required file and no comparisons`() {
        val names = importerGraph().listDirectoryEntries().map { it.name }.toSet()
        assertTrue(names.containsAll(GraphManifest.REQUIRED_FILES), "$names")
        assertFalse(GraphStore.COMPARISONS_FILE in names)
    }

    @Test
    fun `the importer's graph loads eagerly and answers queries`() =
        GraphStore.load(importerGraph(), GraphStore.LoadMode.EAGER).use(::assertImporterGraph)

    @Test
    fun `the importer's graph loads mapped and answers queries`() = GraphStore.loadMapped(importerGraph()).use(::assertImporterGraph)

    private fun assertImporterGraph(graph: Graph) {
        assertEquals(listOf("hello 🌍"), graph.nodes<StringConstant>().map { it.value }.toList())
        val calls = graph.nodes<CallSiteNode>().sortedBy { it.lineNumber }.toList()
        assertEquals(listOf("helper" to 17, "callback" to 18), calls.map { it.callee.name to it.lineNumber })
        assertEquals("src/测试.js", calls[0].caller.declaringClass.className)
        // The importer keeps the interchange's node ids.
        assertEquals(listOf(3, 10), calls.map { it.id.value })
        assertEquals(listOf(NodeId(0)), calls[0].arguments)
        assertNull(calls[0].receiver)
        assertEquals(NodeId(4), calls[1].receiver)
        // No comparison is on record for any edge, and asking is not an error.
        assertTrue(graph.outgoing(calls[0].id).toList().isNotEmpty())
        assertTrue(graph.outgoing(calls[0].id).filterIsInstance<ControlFlowEdge>().none { it.comparison != null })
        assertEquals(
            listOf(
                mapOf("callee" to "helper", "line" to 17, "status" to "ANALYZED", "gap" to null),
                mapOf("callee" to "callback", "line" to 18, "status" to "EXTERNAL", "gap" to "DISPATCH_UNRESOLVED"),
            ),
            CypherExecutor(graph).execute(
                "MATCH (c:CallSite) RETURN c.callee_name AS callee, c.line AS line, analysisStatus(c) AS status, " +
                    "dispatchGap(c) AS gap ORDER BY line"
            ).rows
        )
        assertEquals(
            listOf(mapOf("t" to "CALL", "n" to 2L), mapOf("t" to "DATAFLOW", "n" to 3L)),
            CypherExecutor(graph).execute("MATCH (a)-[r]->(b) RETURN type(r) AS t, count(*) AS n ORDER BY t").rows
        )
        val completeness = assertNotNull(graph.analysisCompleteness())
        assertEquals(AnalysisStatus.ANALYZED, completeness.classStatus("src/测试.js"))
        assertEquals(AnalysisStatus.RESOLUTION_FAILED, completeness.classStatus("src/broken.js"))
        assertEquals(mapOf(10 to CallSiteGap.DISPATCH_UNRESOLVED), completeness.callSiteGaps)
    }

    /** A JVM-written graph with one branch comparison, saved into a fresh directory. */
    private fun savedWithComparison(): Path {
        val builder = DefaultGraph.Builder()
        val condition = IntConstant(NodeId(1), 7)
        val target = IntConstant(NodeId(2), 8)
        val comparand = IntConstant(NodeId(3), 0)
        listOf(condition, target, comparand).forEach(builder::addNode)
        val comparison = BranchComparison(ComparisonOp.EQ, comparand.id)
        builder.addEdge(ControlFlowEdge(condition.id, target.id, ControlFlowKind.BRANCH_TRUE, comparison))
        return root.resolve("saved-${System.nanoTime()}").also { GraphStore.save(builder.build(), it) }
    }

    private fun comparisons(graph: Graph): List<BranchComparison?> =
        graph.outgoing(NodeId(1)).filterIsInstance<ControlFlowEdge>().map { it.comparison }.toList()

    private fun loaders(): List<(Path) -> Graph> = listOf({ GraphStore.load(it, GraphStore.LoadMode.EAGER) }, GraphStore::loadMapped)

    @Test
    fun `a sealed graph that lists its comparisons and lost them is refused, not read as having none`() {
        for (load in loaders()) {
            val dir = savedWithComparison()
            load(dir).use { assertEquals(listOf<BranchComparison?>(BranchComparison(ComparisonOp.EQ, NodeId(3))), comparisons(it)) }
            Files.delete(dir.resolve(GraphStore.COMPARISONS_FILE))
            val refusal = assertFailsWith<GraphIntegrityException> { load(dir) }
            assertTrue(GraphStore.COMPARISONS_FILE in refusal.message.orEmpty(), refusal.message)
        }
    }

    @Test
    fun `a graph whose writer wrote no comparisons has none, sealed or not`() {
        for (load in loaders()) {
            for (sealed in listOf(true, false)) {
                val dir = savedWithComparison()
                Files.delete(dir.resolve(GraphStore.COMPARISONS_FILE))
                unsealGraph(dir)
                // Sealed again, the manifest is that of a writer that produced no such file.
                if (sealed) GraphManifest.write(dir)
                load(dir).use { assertEquals(listOf<BranchComparison?>(null), comparisons(it)) }
                assertNull(GraphManifest.read(dir)?.entries?.get(GraphStore.COMPARISONS_FILE))
            }
        }
    }
}
