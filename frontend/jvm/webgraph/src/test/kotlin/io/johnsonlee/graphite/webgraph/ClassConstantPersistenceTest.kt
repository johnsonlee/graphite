package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.ClassConstant
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.ConstantNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalDefinition
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import javax.tools.ToolProvider
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.incoming
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/** Class literals (`ClassConstant`, record tag 16) through save, eager and mapped load, and the definition sidecars. */
class ClassConstantPersistenceTest {
    private val method = MethodDescriptor(TypeDescriptor("sample.Registry"), "pick", emptyList(), TypeDescriptor("java.lang.Class"))

    private class Fixture(
        val graph: Graph,
        val condId: NodeId,
        val localId: NodeId,
        /** `Payload.class`, `Payload[].class`, `int[][].class`. */
        val literals: List<ClassConstant>,
        val text: StringConstant,
        val trueDefinitions: List<LocalDefinition>,
        val falseDefinitions: List<LocalDefinition>,
        val allDefinitions: List<LocalDefinition>
    ) {
        val payload get() = literals[0]
        val ints get() = literals[2]
    }

    /**
     * `type = Payload.class; if (on == 0) type = Payload[].class else type = int[][].class`, beside a string spelling Payload's
     * descriptor.
     */
    private fun fixture(): Fixture {
        val builder = DefaultGraph.Builder()
        builder.addMethod(method)
        val cond = LocalVariable(NodeId(1), "on", TypeDescriptor("boolean"), method)
        val local = LocalVariable(NodeId(2), "type", TypeDescriptor("java.lang.Class"), method)
        val payload = ClassConstant(NodeId(3), "Lsample/Payload;")
        val payloads = ClassConstant(NodeId(4), "[Lsample/Payload;")
        val ints = ClassConstant(NodeId(5), "[[I")
        val text = StringConstant(NodeId(6), "Lsample/Payload;")
        val zero = IntConstant(NodeId(7), 0)
        listOf(cond, local, payload, payloads, ints, text, zero).forEach(builder::addNode)
        val straightLine = LocalDefinition(1, local.id, payload.id)
        val trueDefinitions = listOf(LocalDefinition(3, local.id, payloads.id))
        val falseDefinitions = listOf(LocalDefinition(5, local.id, ints.id))
        val all = listOf(straightLine) + trueDefinitions + falseDefinitions
        all.forEach { builder.addEdge(DataFlowEdge(it.constantNodeId!!, local.id, DataFlowKind.ASSIGN)) }
        builder.addBranchScope(
            cond.id,
            method,
            BranchComparison(ComparisonOp.EQ, zero.id),
            intArrayOf(local.id.value),
            intArrayOf(local.id.value),
            trueDefinitions = BranchScope.packDefinitions(trueDefinitions),
            falseDefinitions = BranchScope.packDefinitions(falseDefinitions))
        builder.addLocalDefinitions(local.id, BranchScope.packDefinitions(all))
        val literals = listOf(payload, payloads, ints)
        return Fixture(builder.build(), cond.id, local.id, literals, text, trueDefinitions, falseDefinitions, all)
    }

    private fun roundTrip(mode: GraphStore.LoadMode, block: (Fixture, Graph) -> Unit) {
        val fixture = fixture()
        val dir: Path = Files.createTempDirectory("webgraph-class-constant")
        try {
            GraphStore.save(fixture.graph, dir)
            block(fixture, GraphStore.load(dir, mode))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun assertRoundTrip(fixture: Fixture, loaded: Graph) {
        fixture.literals.forEach { expected ->
            val node = assertIs<ClassConstant>(loaded.node(expected.id))
            assertEquals(expected, node)
        }
        val text = assertIs<StringConstant>(loaded.node(fixture.text.id))
        assertEquals(fixture.payload.value, text.value, "the string and the class literal share a string table entry")
        assertNotEquals(text.id, loaded.node(fixture.payload.id)?.id, "and stay different constants")
        assertEquals(
            listOf("Lsample/Payload;", "[Lsample/Payload;", "[[I"),
            loaded.nodes(ClassConstant::class.java).map { it.value }.sorted().toList())
        assertEquals(5, loaded.nodes(ConstantNode::class.java).count(), "three class literals, the string and the int")
        assertEquals(
            fixture.literals.map { it.id }.toSet(),
            loaded.incoming<DataFlowEdge>(fixture.localId).map { it.from }.toSet(),
            "every class literal still flows into the local")
        val scope = loaded.branchScopesFor(fixture.condId).single()
        assertEquals(fixture.trueDefinitions, scope.trueDefinitions)
        assertEquals(fixture.falseDefinitions, scope.falseDefinitions)
        assertEquals(fixture.allDefinitions, loaded.localDefinitionsFor(fixture.localId))
        assertEquals(fixture.graph.localDefinitions(), loaded.localDefinitions())
    }

    private fun values(graph: Graph, query: String): List<Any?> =
        CypherExecutor(graph).execute(query).rows.map { it.values.single() }

    /** Labels, equality, prefix, membership and dataflow, through the mapped record paths as well as the eager graph. */
    private fun assertQueries(fixture: Fixture, graph: Graph) {
        assertEquals(
            listOf("Lsample/Payload;", "[Lsample/Payload;", "[[I"),
            values(graph, "MATCH (n:ClassConstant) RETURN n.value AS v ORDER BY v"))
        assertEquals(
            listOf(listOf("ClassConstant", "Constant")),
            values(graph, "MATCH (n:ClassConstant {value: '[[I'}) RETURN labels(n) AS l"))
        assertEquals(
            listOf("ClassConstant"),
            values(graph, "MATCH (n:Constant) WHERE n.value = 'Lsample/Payload;' AND 'ClassConstant' IN labels(n) RETURN n.type AS t"))
        assertEquals(
            listOf("StringConstant"),
            values(graph, "MATCH (n:StringConstant) WHERE n.value = 'Lsample/Payload;' RETURN n.type AS t"),
            "the string that spells the same descriptor is not a class literal")
        assertEquals(
            listOf("Lsample/Payload;", "[Lsample/Payload;"),
            values(graph,
                "MATCH (n:ClassConstant) WHERE n.value STARTS WITH 'Lsample' OR n.value STARTS WITH '[Lsample' " +
                    "RETURN n.value AS v ORDER BY v"))
        assertEquals(
            listOf("[[I"),
            values(graph, "MATCH (n:ClassConstant) WHERE n.value IN ['[[I', 'Lmissing;'] RETURN n.value AS v"))
        assertEquals(
            listOf("Lsample/Payload;", "[Lsample/Payload;"),
            values(graph, "MATCH (n:ClassConstant) WHERE n.value CONTAINS 'Payload' RETURN n.value AS v ORDER BY v"))
        assertEquals(
            listOf(3L),
            values(graph, "MATCH (c:ClassConstant)-[:DATAFLOW]->(l:LocalVariable {name: 'type'}) RETURN count(c) AS n"))
        assertEquals(
            listOf(fixture.ints.id.value),
            values(graph, "MATCH (n:ClassConstant) WHERE n.value ENDS WITH 'I' RETURN n.id AS id"))
    }

    @Test
    fun `eager graph answers class literal queries`() = roundTrip(GraphStore.LoadMode.EAGER, ::assertQueries)

    @Test
    fun `mapped graph answers class literal queries`() = roundTrip(GraphStore.LoadMode.MAPPED, ::assertQueries)

    @Test
    fun `eager load restores class literals and their definitions`() = roundTrip(GraphStore.LoadMode.EAGER, ::assertRoundTrip)

    @Test
    fun `mapped load restores class literals and their definitions`() = roundTrip(GraphStore.LoadMode.MAPPED, ::assertRoundTrip)

    /**
     * A class literal built from bytecode by both node builders and saved: the JVM half of the native interop check. CI points
     * `GRAPHITE_CLASS_CONSTANT_FIXTURE` at a fresh directory and reads the two graphs with the Rust reader (`class_constant` test).
     */
    @Test
    fun `bytecode class literals survive both builders and a save`() {
        val root = Files.createTempDirectory("webgraph-class-literal-build")
        try {
            val classes = compileSetup(root)
            val output = System.getenv("GRAPHITE_CLASS_CONSTANT_FIXTURE")?.let(Path::of) ?: root.resolve("export")
            listOf(false, true).forEach { mmap -> buildAndCheck(classes, output.resolve(if (mmap) "mmap" else "default"), mmap) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun compileSetup(root: Path): Path {
        val source = root.resolve("Setup.java")
        Files.writeString(source, """
            package fixture;
            public class Setup {
                public static class Payload { }
                public static void register(Class<?> type) { }
                public static void init() {
                    Class<?> type = Payload.class;
                    register(type);
                    register(Payload[].class);
                    register(int[][].class);
                    String text = "Lfixture/Setup${'$'}Payload;";
                    text.length();
                }
            }
        """.trimIndent())
        val classes = Files.createDirectories(root.resolve("classes"))
        val compiler = assertNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), source.toString()))
        return classes
    }

    private fun buildAndCheck(classes: Path, directory: Path, mmap: Boolean) {
        val config = LoaderConfig(includePackages = listOf("fixture"), buildCallGraph = false)
        val graph = JavaProjectLoader(config, useMmapBuilder = mmap).load(classes)
        val expected = listOf("Lfixture/Setup\$Payload;", "[Lfixture/Setup\$Payload;", "[[I")
        try {
            assertEquals(expected, graph.nodes(ClassConstant::class.java).map { it.value }.sorted().toList())
            Files.createDirectories(directory)
            GraphStore.save(graph, directory)
        } finally {
            (graph as? AutoCloseable)?.close()
        }
        listOf(GraphStore.LoadMode.EAGER, GraphStore.LoadMode.MAPPED).forEach { mode ->
            val loaded = GraphStore.load(directory, mode)
            assertEquals(expected, values(loaded, "MATCH (n:ClassConstant) RETURN n.value AS v ORDER BY v"), "$mmap $mode")
            assertEquals(
                listOf("Lfixture/Setup\$Payload;"),
                values(loaded,
                    "MATCH (c:ClassConstant)-[:DATAFLOW*1..3]->(cs:CallSiteNode {callee_name: 'register'}) " +
                        "WHERE c.value STARTS WITH 'L' RETURN DISTINCT c.value AS v"),
                "$mmap $mode: the literal reaches the call through the local")
            assertEquals(
                listOf("StringConstant"),
                values(loaded,
                    "MATCH (n:Constant) WHERE n.value = 'Lfixture/Setup\$Payload;' AND n.type <> 'ClassConstant' " +
                        "RETURN n.type AS t"),
                "$mmap $mode: the equal string stays a string")
        }
    }

    @Test
    fun `the record carries tag 16 and the descriptor as a string`() {
        assertEquals(16, NodeSerializer.TAG_CLASS_CONSTANT)
        assertEquals(NodeSerializer.TAG_CLASS_CONSTANT, graphNodeTagOf(ClassConstant(NodeId(1), "[I")))
        assertEquals(ClassConstant::class.java, nodeClassForTag(NodeSerializer.TAG_CLASS_CONSTANT))
        val strings = mutableSetOf<String>()
        collectNodeStrings(ClassConstant(NodeId(1), "Lsample/Payload;"), strings)
        assertEquals(setOf("Lsample/Payload;"), strings)
    }
}
