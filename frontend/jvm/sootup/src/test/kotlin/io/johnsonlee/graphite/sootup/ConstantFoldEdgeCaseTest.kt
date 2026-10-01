package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.CallGraphAlgorithm
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shapes where folding must follow the JVM exactly: branches inside and around exception
 * handlers, the comparison semantics of `-0.0`, `NaN` and wide `long`s, a callee reached through
 * a subclass, a static field that is not an enum constant, and the accounting when a body is
 * resolved more than once.
 */
class ConstantFoldEdgeCaseTest {

    private val packageName = "sample.edge"
    private val flags = "$packageName.Flags"
    private val temporaryRoots = mutableListOf<Path>()

    @AfterTest
    fun deleteCompiledFixtures() {
        temporaryRoots.forEach { it.toFile().deleteRecursively() }
    }

    private val sources = mapOf(
        "Flags.java" to """
            package sample.edge;
            public class Flags {
                public static boolean enabled(String key) { return key.length() > 3; }
                public static double precise() { return 1d; }
                public static long big() { return 10L; }
                public static boolean wideKey(long key) { return key > 0; }
                public static final Flags SELF = new Flags();
                public static boolean marked(Object marker) { return marker == SELF; }
                public static Option option(int key) { return key > 0 ? Option.TREATMENT : Option.CONTROL; }
                public static void touch() { }
                public static Boolean boxed() { return Boolean.TRUE; }
                public static void use(Object o) { }
            }
        """,
        "Lamp.java" to "package sample.edge; public enum Lamp { ON, OFF; static Lamp alias = ON; }",
        "Option.java" to "package sample.edge; public enum Option { CONTROL, TREATMENT }",
        "Sub.java" to "package sample.edge; public class Sub extends Flags { }",
        "Mode.java" to """
            package sample.edge;
            public enum Mode {
                ON, OFF;
                static final boolean GATED = Flags.enabled("k");
            }
        """,
        "Shapes.java" to """
            package sample.edge;
            public class Shapes {
                static void work() { }
                static void tail() { }
                static void other() { }
                public static void main(String[] args) { new Shapes().tryGuard(); }
                public void tryGuard() { try { if (Flags.enabled("k")) { work(); } other(); } catch (RuntimeException e) { tail(); } }
                public void handlerGate() { try { other(); } catch (RuntimeException e) { if (Flags.enabled("k")) { work(); } } tail(); }
                public void finallyGate() { try { other(); } finally { if (Flags.enabled("k")) { work(); } } tail(); }
                public void wholeTry() { if (Flags.enabled("k")) { try { work(); } catch (RuntimeException e) { tail(); } } other(); }
                public void emptyThen() { if (Flags.enabled("k")) { } tail(); }
                public void negZero() { if (Flags.precise() == 0.0) { work(); } tail(); }
                public void nanAbove() { if (Flags.precise() > 1.0) { work(); } tail(); }
                public void nanBelow() { if (Flags.precise() < 1.0) { work(); } tail(); }
                public void longExact() { if (Flags.big() == 9007199254740993L) { work(); } tail(); }
                public void longKey() { if (Flags.wideKey(9007199254740993L)) { work(); } tail(); }
                public void inherited() { if (Sub.enabled("k")) { work(); } tail(); }
                public void singleton() { if (Flags.marked(Flags.SELF)) { work(); } tail(); }
                public void enumEq() { if (Flags.option(1) == Option.TREATMENT) { work(); } tail(); }
                public void enumNe() { if (Flags.option(1) != Option.TREATMENT) { work(); } tail(); }
                public void enumNull() { if (Flags.option(1) == null) { work(); } tail(); }
                public void enumLocal() { Option o = Flags.option(1); if (o == Option.CONTROL) { work(); } else { other(); } tail(); }
                public void enumSwitch() { switch (Flags.option(1)) { case CONTROL: work(); break; default: other(); } tail(); }
                public void tryOnly() { try { Flags.enabled("k"); } catch (RuntimeException e) { tail(); } other(); }
                public void discarded() { Flags.touch(); Flags.boxed(); tail(); }
                public void boxedGate() { Integer a = Integer.valueOf(1); Integer.valueOf(2); Flags.use(a); tail(); }
                public void aliasEq() { if (Lamp.alias == Lamp.ON) { work(); } if (Flags.enabled("k")) { other(); } tail(); }
            }
        """
    )

    private fun compile(): Path {
        val root = Files.createTempDirectory("constant-fold-edge").also(temporaryRoots::add)
        val sourceDir = root.resolve("src/sample/edge").also { Files.createDirectories(it) }
        val classesDir = root.resolve("classes").also { Files.createDirectories(it) }
        val files = sources.map { (name, text) -> sourceDir.resolve(name).also { Files.writeString(it, text.trimIndent()) }.toString() }
        val result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classesDir.toString(), *files.toTypedArray())
        assertEquals(0, result, "fixture compiles")
        return classesDir
    }

    private fun load(
        input: Path,
        vararg folds: FoldRule,
        callGraph: CallGraphAlgorithm? = null,
        report: (FoldReport) -> Unit = {}
    ): Graph = JavaProjectLoader(
        LoaderConfig(
            includePackages = listOf(packageName),
            buildCallGraph = callGraph != null,
            callGraphAlgorithm = callGraph ?: CallGraphAlgorithm.CHA,
            folding = folds.toList().takeIf { it.isNotEmpty() }?.let { FoldPlan(it, report) }
        )
    ).load(input)

    private fun Graph.callees(method: String): List<String> =
        nodes<CallSiteNode>().filter { it.caller.name == method }.map { it.callee.name }.sorted().toList()

    private fun rule(vararg site: Pair<String, Any?>, args: Map<Int, Any?> = emptyMap(), value: Any?): ConstantFold =
        ConstantFold.parse(mapOf("match" to mapOf("CallSite" to site.toMap()), "args" to args, "value" to value))

    private fun gate(name: String, value: Any?, args: Map<Int, Any?> = emptyMap()): ConstantFold =
        rule("callee_class" to flags, "callee_name" to name, args = args, value = value)

    @Test
    fun `a gate inside, around or under an exception handler folds without touching the handler`() {
        val classes = compile()
        val off = load(classes, gate("enabled", false))
        assertEquals(listOf("other", "tail"), off.callees("tryGuard"), "the handler stays, the gated work goes")
        assertEquals(listOf("other", "tail"), off.callees("handlerGate"), "a gate inside the handler folds")
        assertEquals(listOf("other", "tail"), off.callees("finallyGate"), "both copies of the finally block fold")
        assertEquals(listOf("other"), off.callees("wholeTry"), "a try/catch on the dead side goes with it")
        assertEquals(listOf("tail"), off.callees("emptyThen"))

        val on = load(classes, gate("enabled", true))
        assertEquals(listOf("other", "tail", "work"), on.callees("tryGuard"))
        assertEquals(listOf("other", "tail", "work"), on.callees("handlerGate"))
        assertTrue("work" in on.callees("finallyGate") && "other" in on.callees("finallyGate"), on.callees("finallyGate").toString())
        assertEquals(listOf("other", "tail", "work"), on.callees("wholeTry"))
        assertEquals(listOf("tail"), on.callees("emptyThen"))
    }

    @Test
    fun `floating comparisons follow the JVM for negative zero and NaN`() {
        val classes = compile()
        assertEquals(listOf("tail", "work"), load(classes, gate("precise", -0.0)).callees("negZero"), "-0.0 == 0.0")
        assertEquals(listOf("tail", "work"), load(classes, gate("precise", 0.0)).callees("negZero"))
        val nan = load(classes, gate("precise", Double.NaN))
        assertEquals(listOf("tail"), nan.callees("nanAbove"), "NaN > 1.0 is false")
        assertEquals(listOf("tail"), nan.callees("nanBelow"), "NaN < 1.0 is false")
        assertEquals(listOf("tail"), nan.callees("negZero"), "NaN == 0.0 is false")
    }

    @Test
    fun `long comparisons and long argument patterns keep every bit`() {
        val classes = compile()
        assertEquals(listOf("tail"), load(classes, gate("big", 9007199254740992L)).callees("longExact"), "2^53 != 2^53 + 1")
        assertEquals(listOf("tail", "work"), load(classes, gate("big", 9007199254740993L)).callees("longExact"))
        val near = load(classes, gate("wideKey", false, args = mapOf(0 to 9007199254740992L)))
        assertEquals(listOf("tail", "wideKey", "work"), near.callees("longKey"), "2^53 does not match 2^53 + 1")
        val exact = load(classes, gate("wideKey", false, args = mapOf(0 to 9007199254740993L)))
        assertEquals(listOf("tail"), exact.callees("longKey"))
    }

    @Test
    fun `a callee reached through a subclass matches the class that declares it, as the graph names it`() {
        val classes = compile()
        val plain = load(classes)
        val site = plain.nodes<CallSiteNode>().single { it.caller.name == "inherited" && it.callee.name == "enabled" }
        assertEquals(flags, site.callee.declaringClass.className, "the graph names the declaring class")
        assertEquals(listOf("tail"), load(classes, gate("enabled", false)).callees("inherited"))
    }

    @Test
    fun `a static field of its own class is a field, not an enum constant`() {
        val classes = compile()
        val field = mapOf("FieldNode" to mapOf("class" to flags, "name" to "SELF"))
        val byField = load(classes, gate("marked", false, args = mapOf(0 to field)))
        assertEquals(listOf("tail"), byField.callees("singleton"))
        val enum = mapOf("EnumConstant" to mapOf("enum_type" to flags, "name" to "SELF"))
        val byEnum = load(classes, gate("marked", false, args = mapOf(0 to enum)))
        assertEquals(listOf("marked", "tail", "work"), byEnum.callees("singleton"), "SELF is not an enum constant")
    }

    @Test
    fun `a call folded to an enum constant resolves the comparisons against enum constants`() {
        val classes = compile()
        val control = mapOf("EnumConstant" to mapOf("enum_type" to "$packageName.Option", "name" to "CONTROL"))
        var report: FoldReport? = null
        val folded = load(classes, gate("option", control)) { report = it }
        val outcome = report!!.outcomes.single()
        assertEquals(5, outcome.matched, outcome.sites.toString())
        assertEquals(listOf("tail"), folded.callees("enumEq"), "CONTROL == TREATMENT is false")
        assertEquals(listOf("tail", "work"), folded.callees("enumNe"))
        assertEquals(listOf("tail"), folded.callees("enumNull"), "an enum constant is not null")
        assertEquals(listOf("tail", "work"), folded.callees("enumLocal"), "the constant reaches the test through the local")
        // A switch goes through the enum's ordinal and a synthetic lookup array, which is not folded.
        assertEquals(listOf("ordinal", "other", "tail", "work"), folded.callees("enumSwitch"))

        var treatment: FoldReport? = null
        val treatmentConstant = mapOf("EnumConstant" to mapOf("enum_type" to "$packageName.Option", "name" to "TREATMENT"))
        val treated = load(classes, gate("option", treatmentConstant)) { treatment = it }
        assertEquals(5, treatment!!.outcomes.single().matched)
        assertEquals(listOf("tail", "work"), treated.callees("enumEq"))
        assertEquals(listOf("tail"), treated.callees("enumNe"))
        assertEquals(listOf("other", "tail"), treated.callees("enumLocal"))

        // An enum constant on a method that does not return that enum is reported, not applied.
        var mismatch: FoldReport? = null
        load(classes, gate("enabled", control)) { mismatch = it }
        val unsupported = mismatch!!.outcomes.single().unsupported
        assertEquals(0, mismatch!!.outcomes.single().matched)
        assertTrue(unsupported.isNotEmpty(), mismatch.toString())
        assertTrue(unsupported.all { it.reason.startsWith("return type boolean cannot carry") }, unsupported.toString())
    }

    @Test
    fun `a folded call throws nothing, so a handler only it could reach goes`() {
        val classes = compile()
        assertEquals(listOf("other"), load(classes, gate("enabled", false)).callees("tryOnly"))
        assertEquals(listOf("other"), load(classes, gate("enabled", true)).callees("tryOnly"))
    }

    @Test
    fun `a discarded call folds only when its return type carries the value`() {
        val classes = compile()
        var report: FoldReport? = null
        val graph = load(classes, gate("touch", false), gate("boxed", false)) { report = it }
        assertEquals(listOf("boxed", "tail", "touch"), graph.callees("discarded"), "neither call is deleted")
        val (touch, boxed) = report!!.outcomes
        assertEquals(0, touch.matched)
        assertEquals(listOf("return type void cannot carry false"), touch.unsupported.map { it.reason }.distinct())
        assertEquals(0, boxed.matched)
        assertEquals(listOf("return type java.lang.Boolean cannot carry false"), boxed.unsupported.map { it.reason }.distinct())
    }

    @Test
    fun `an ordinal counts every invoke of the body, so a key read off the graph folds the call it names`() {
        val classes = compile()
        val unfolded = load(classes)
        // `Integer a = Integer.valueOf(1)` is a dataflow edge in the graph, not a call site; the
        // discarded `Integer.valueOf(2)` is the body's second invoke of that callee all the same,
        // so the key the graph hands out must name the second invoke, not the first.
        val visible = unfolded.nodes<CallSiteNode>().filter { it.caller.name == "boxedGate" && it.callee.name == "valueOf" }.toList()
        assertEquals(listOf(1), visible.map { it.ordinal })
        val site = visible.single()
        val rule = FoldSites(
            select = "MATCH (cs:CallSite {caller_name: 'boxedGate', callee_name: 'valueOf'}) RETURN cs",
            selected = setOf(CallSiteKey(site.caller.signature, site.callee.signature, site.ordinal!!)),
            value = ConstantPattern.parse(null, "value")
        )
        var report: FoldReport? = null
        val folded = load(classes, rule) { report = it }
        assertEquals(1, report!!.outcomes.single().matched)
        assertEquals(listOf("tail", "use"), folded.callees("boxedGate"), "the selected call is the one folded")
        assertTrue(folded.nodes<CallSiteNode>().none { it.caller.name == "boxedGate" && it.callee.name == "valueOf" })
    }

    @Test
    fun `an enum-typed static field that is not a constant is compared at run time`() {
        val classes = compile()
        assertEquals(listOf("tail", "work"), load(classes, gate("enabled", false)).callees("aliasEq"), "Lamp.alias is a field")
        val bogus = mapOf("EnumConstant" to mapOf("enum_type" to "$packageName.Option", "name" to "BOGUS"))
        var report: FoldReport? = null
        val graph = load(classes, gate("option", bogus)) { report = it }
        assertEquals(0, report!!.outcomes.single().matched)
        assertTrue(report!!.outcomes.single().unsupported.isNotEmpty())
        assertEquals(listOf("option", "tail", "work"), graph.callees("enumEq"), "no constant of that name: nothing folds")
    }

    @Test
    fun `a rule that matches nothing leaves every node of the graph as a build without rules has it`() {
        val classes = compile()
        // Every node without its id (ids follow creation order, which the comparison does not pin).
        fun Graph.shape(): List<String> = nodes<Node>().map { node ->
            when (node) {
                is LocalVariable -> "local ${node.method.signature} ${node.name} ${node.type.className}"
                is CallSiteNode -> "call ${node.caller.signature} ${node.callee.signature} ${node.ordinal}"
                else -> node.toString().replace(Regex("id=node#\\d+,? ?"), "")
            }
        }.sorted().toList()
        val plain = load(classes).shape()
        val unmatched = load(classes, gate("absent", false)).shape()
        assertEquals(emptyList(), unmatched - plain.toSet(), "nodes only the build with the unmatched rule has")
        assertEquals(emptyList(), plain - unmatched.toSet(), "nodes only the build without rules has")
        assertEquals(plain, unmatched)
    }

    @Test
    fun `a body resolved more than once is counted once`() {
        val classes = compile()
        for (algorithm in listOf(CallGraphAlgorithm.CHA, CallGraphAlgorithm.RTA)) {
            var report: FoldReport? = null
            load(classes, gate("enabled", false), callGraph = algorithm) { report = it }
            val outcome = report!!.outcomes.single()
            val methods = outcome.sites.map { it.method }
            assertEquals(methods.distinct(), methods, "$algorithm: one site per method")
            assertTrue("$packageName.Mode.<clinit>()" in methods, "$algorithm: the enum initialiser folded once: $methods")
            assertEquals(10, outcome.matched, "$algorithm: one call per gated method: ${outcome.sites}")
            val hint = outcome.hints.filter { "argument" in it }
            assertEquals(emptyList(), hint)
        }
    }

    @Test
    fun `a build without a call graph counts the same`() {
        var report: FoldReport? = null
        load(compile(), gate("enabled", false)) { report = it }
        assertEquals(10, report!!.outcomes.single().matched)
        assertFalse(report!!.outcomes.single().sites.isEmpty())
    }
}
