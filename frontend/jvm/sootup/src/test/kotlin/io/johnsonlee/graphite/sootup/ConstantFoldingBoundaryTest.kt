package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.graph.incoming
import io.johnsonlee.graphite.input.CallSiteKey
import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.ConstantPattern
import io.johnsonlee.graphite.input.FoldPlan
import io.johnsonlee.graphite.input.FoldReport
import io.johnsonlee.graphite.input.FoldRule
import io.johnsonlee.graphite.input.FoldSites
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConstantFoldingBoundaryTest {
    private val root = Files.createTempDirectory("fold-boundaries")

    @AfterTest
    fun cleanup() { root.toFile().deleteRecursively() }

    private fun fixture(): Path {
        val source = root.resolve("Shapes.java")
        Files.writeString(source, """
            package sample.boundary;
            class Box {
                int key;
                Box(int key) { this.key = key; }
                static Box make(int key) { return new Box(key); }
                boolean on() { return key > 0; }
                boolean self() { return on(); }
            }
            public class Shapes {
                static Box field;
                static boolean gate() { return true; }
                static boolean key(int value) { return value > 0; }
                static boolean boxedKey(Object value) { return value != null; }
                public boolean allocated() { return new Box(7).on(); }
                public boolean dynamic(int key) { return Box.make(key).on(); }
                public boolean readField() { return field.on(); }
                public boolean fromArray(Box[] boxes) { return boxes[0].on(); }
                public boolean arithmetic(int value) { return key(value + 1); }
                public boolean boxed() { return boxedKey(Integer.valueOf(7)); }
                public int comparisons(double d, long n) {
                    if (gate()) return 1;
                    if (d > 3.0) return 2;
                    if (n < 4L) return 3;
                    return 4;
                }
            }
        """.trimIndent())
        val classes = root.resolve("classes").also(Files::createDirectories)
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString()))
        return classes
    }

    private fun load(classes: Path, rules: List<FoldRule> = emptyList(), report: (FoldReport) -> Unit = {}): Graph =
        JavaProjectLoader(LoaderConfig(includePackages = listOf("sample.boundary"), buildCallGraph = false,
            folding = rules.takeIf { it.isNotEmpty() }?.let { FoldPlan(it, report) })).load(classes)

    private fun site(graph: Graph, caller: String, callee: String): CallSiteNode =
        graph.nodes<CallSiteNode>().single { it.caller.name == caller && it.callee.name == callee }

    private fun selected(site: CallSiteNode, args: Map<Int, Any?> = emptyMap(), receiver: Map<Int, Any?> = emptyMap()) = FoldSites(
        "MATCH (cs:CallSite) RETURN cs",
        setOf(CallSiteKey(site.caller.signature, site.caller.descriptor, site.callee.signature, site.callee.descriptor, site.ordinal!!)),
        ConstantPattern.parse(false, "value"),
        arguments = args.mapValues { ConstantPattern.parse(it.value, "argument") },
        receiverArguments = receiver.mapValues { ConstantPattern.parse(it.value, "receiver") }
    )

    @Test
    fun `receiver matching preserves dynamic field array and this calls but folds a matching constructor`() {
        val classes = fixture()
        val plain = load(classes)
        val callers = listOf("allocated", "dynamic", "readField", "fromArray", "self")
        val rules = callers.map { selected(site(plain, it, "on"), receiver = mapOf(0 to 7)) }
        var report: FoldReport? = null
        val folded = load(classes, rules) { report = it }
        val outcomes = assertNotNull(report).outcomes
        assertEquals(listOf(1, 0, 0, 0, 0), outcomes.map { it.matched })
        assertEquals(0, folded.nodes<CallSiteNode>().count { it.caller.name == "allocated" && it.callee.name == "on" })
        callers.drop(1).forEach { assertEquals("on", site(folded, it, "on").callee.name) }
        val reasons = outcomes.drop(1).map { it.unsupported.single().reason }
        assertTrue(reasons[0].contains("whose argument 0 is not a constant here"), reasons[0])
        assertTrue(reasons[1].contains("a read of field"), reasons[1])
        assertTrue(reasons[2].contains("computed at run time"), reasons[2])
        assertTrue(reasons[3].contains("is this, not a call's result"), reasons[3])
    }

    @Test
    fun `selected argument constraints reject missing and computed arguments but recognize boxed constants`() {
        val classes = fixture()
        val plain = load(classes)
        val rules = listOf(
            selected(site(plain, "allocated", "on"), args = mapOf(0 to 7)),
            selected(site(plain, "arithmetic", "key"), args = mapOf(0 to 7)),
            selected(site(plain, "boxed", "boxedKey"), args = mapOf(0 to 7))
        )
        var report: FoldReport? = null
        val folded = load(classes, rules) { report = it }
        val outcomes = assertNotNull(report).outcomes
        assertEquals(listOf(0, 0, 1), outcomes.map { it.matched })
        assertTrue(outcomes[0].unsupported.single().reason.contains("the call has 0 argument(s)"))
        assertTrue(outcomes[1].unsupported.single().reason.contains("computed at run time"))
        assertEquals(0, folded.nodes<CallSiteNode>().count { it.caller.name == "boxed" && it.callee.name == "boxedKey" })
    }

    @Test
    fun `folding one gate preserves comparisons whose numeric operands are runtime parameters`() {
        val classes = fixture()
        val rule = ConstantFold(mapOf("callee_name" to "gate"), value = ConstantPattern.parse(false, "value"))
        var report: FoldReport? = null
        val graph = load(classes, listOf(rule)) { report = it }
        assertEquals(1, assertNotNull(report).outcomes.single().matched)
        assertEquals(0, graph.nodes<CallSiteNode>().count { it.callee.name == "gate" })
        val returned = graph.nodes<ReturnNode>().single { it.method.name == "comparisons" }
        val values = graph.incoming(returned.id).mapNotNull { graph.node(it.from) as? IntConstant }.map { it.value }.toSet()
        assertEquals(setOf(2, 3, 4), values, "all runtime branches remain, while the folded gate return is removed")
    }
}
