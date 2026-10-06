package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.nodes
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

/**
 * `CallSite.ordinal`: the invokes of one callee in one method are numbered in statement order
 * from `0` over the whole body, derived calls from `-1` downwards, so `(caller_signature, callee_signature,
 * ordinal)` names every call site of a graph, and the number the fold pass computes for a
 * call is the one the graph built without rules gave it.
 */
class CallSiteOrdinalTest {

    private val packageName = "sample.ordinal"
    private val temporaryRoots = mutableListOf<Path>()

    @AfterTest
    fun deleteCompiledFixtures() {
        temporaryRoots.forEach { it.toFile().deleteRecursively() }
    }

    private val sources = mapOf(
        "Flags.java" to """
            package sample.ordinal;
            public class Flags {
                public static boolean enabled(String key) { return key.length() > 3; }
                public static boolean enabled(int key) { return key > 3; }
            }
        """,
        "Sub.java" to "package sample.ordinal; public class Sub extends Flags { }",
        "Shapes.java" to """
            package sample.ordinal;
            import java.util.function.Supplier;
            public class Shapes {
                static void work(int which) { }
                static int value() { return 1; }
                public void repeated() {
                    if (Flags.enabled("a")) { work(1); }
                    work(value());
                    if (Sub.enabled("b")) { work(2); }
                    if (Flags.enabled(3)) { work(3); }
                    if (Flags.enabled("c")) { work(4); }
                    work(value());
                }
                public void functional() {
                    Supplier<Boolean> first = () -> Flags.enabled("x");
                    Supplier<Boolean> second = () -> Flags.enabled("y");
                    if (first.get() && second.get()) { work(5); }
                }
            }
        """
    )

    private fun compile(): Path {
        val root = Files.createTempDirectory("call-site-ordinal").also(temporaryRoots::add)
        val sourceDir = root.resolve("src/sample/ordinal").also { Files.createDirectories(it) }
        val classesDir = root.resolve("classes").also { Files.createDirectories(it) }
        val files = sources.map { (name, text) -> sourceDir.resolve(name).also { Files.writeString(it, text.trimIndent()) }.toString() }
        val result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classesDir.toString(), *files.toTypedArray())
        assertEquals(0, result, "fixture compiles")
        return classesDir
    }

    private fun load(input: Path, folds: List<FoldRule> = emptyList(), report: (FoldReport) -> Unit = {}): Graph =
        JavaProjectLoader(
            LoaderConfig(includePackages = listOf(packageName), folding = folds.takeIf { it.isNotEmpty() }?.let { FoldPlan(it, report) })
        ).load(input)

    private fun Graph.sites(): List<CallSiteNode> =
        nodes<CallSiteNode>().filter { it.caller.declaringClass.className.startsWith(packageName) }.toList()

    private fun key(site: CallSiteNode) = Triple(site.caller.signature, site.callee.signature, site.ordinal)

    private fun callSiteKey(site: CallSiteNode) =
        CallSiteKey(site.caller.signature, site.caller.descriptor, site.callee.signature, site.callee.descriptor, site.ordinal!!)

    @Test
    fun `every call site of a graph has an ordinal and the triple names it`() {
        val sites = load(compile()).sites()
        assertTrue(sites.isNotEmpty())
        sites.forEach { assertNotNull(it.ordinal, "${it.caller.signature} -> ${it.callee.signature}") }
        assertEquals(sites.map(::key).distinct().size, sites.size, "one triple per call site")
        for ((_, group) in sites.groupBy { it.caller.signature to it.callee.signature }) {
            val spelled = group.mapNotNull { it.ordinal }.filter { it >= 0 }
            val derived = group.mapNotNull { it.ordinal }.filter { it < 0 }.sortedDescending()
            // The bytecode's own calls count from 0 over every invoke of the body; a boxing call the
            // graph shows as a dataflow edge takes its number too, so the visible ones may skip it.
            assertEquals(spelled.distinct().size, spelled.size, "one ordinal per call: $group")
            assertEquals(List(derived.size) { -(it + 1) }, derived, "derived calls count from -1: $group")
        }
    }

    @Test
    fun `ordinals follow statement order per callee and ignore the other calls in between`() {
        val sites = load(compile()).sites().filter { it.caller.name == "repeated" }
        val enabledByString = sites.filter { it.callee.signature == "$packageName.Flags.enabled(java.lang.String)" }
        // The bytecode spells `Sub.enabled("b")`, the graph names the declaring class and counts it with the others.
        assertEquals(listOf(0, 1, 2), enabledByString.map { it.ordinal }, enabledByString.map { it.callee.signature }.toString())
        assertEquals(listOf(0), sites.filter { it.callee.signature == "$packageName.Flags.enabled(int)" }.map { it.ordinal })
        assertEquals(listOf(0, 1, 2, 3, 4, 5), sites.filter { it.callee.name == "work" }.map { it.ordinal })
        assertEquals(listOf(0, 1), sites.filter { it.callee.name == "value" }.map { it.ordinal })
    }

    @Test
    fun `calls the frontend derives from a function value count below zero`() {
        val sites = load(compile()).sites().filter { it.caller.name == "functional" }
        val lambdaBodies = sites.filter { it.callee.name.startsWith("lambda$") }
        assertTrue(lambdaBodies.isNotEmpty(), sites.map { it.callee.signature }.toString())
        lambdaBodies.forEach { assertTrue(it.ordinal!! < 0, "${it.callee.signature} is derived: ${it.ordinal}") }
        val gets = sites.filter { it.callee.name == "get" }
        assertEquals(listOf(0, 1), gets.map { it.ordinal }, "the two `get` calls the bytecode spells")
    }

    @Test
    fun `a rule naming the triple folds exactly that call`() {
        val classes = compile()
        var report: FoldReport? = null
        val rule = ConstantFold.parse(
            mapOf(
                "match" to mapOf(
                    "CallSite" to mapOf(
                        "caller_signature" to "$packageName.Shapes.repeated()",
                        "callee_signature" to "$packageName.Flags.enabled(java.lang.String)",
                        "ordinal" to 1
                    )
                ),
                "value" to false
            )
        )
        val folded = load(classes, listOf(rule)) { report = it }
        assertEquals(1, report!!.outcomes.single().matched)
        val work = folded.sites().filter { it.caller.name == "repeated" && it.callee.name == "work" }
        // `work(2)` sat behind the folded gate; the other five calls stay and keep the ordinals
        // the bytecode gave them, so the folded graph leaves a gap where the call was.
        assertEquals(5, work.size)
        assertEquals(listOf(0, 1, 3, 4, 5), work.map { it.ordinal })
        val remaining = folded.sites().filter { it.caller.name == "repeated" && it.callee.signature.endsWith("enabled(java.lang.String)") }
        assertEquals(listOf(0, 2), remaining.map { it.ordinal })
        // A key read off the folded graph names the same call in the bytecode: applied to the same
        // classes, the two surviving keys fold exactly those two calls, not a renumbered neighbour.
        val survivors = remaining.map { callSiteKey(it) }.toSet()
        val readBack = FoldSites("MATCH (cs:CallSite) RETURN cs", survivors, ConstantPattern.parse(false, "value"))
        var again: FoldReport? = null
        val refolded = load(classes, listOf(readBack)) { again = it }
        assertEquals(2, again!!.outcomes.single().matched, again.toString())
        assertEquals(emptyList(), again!!.outcomes.single().hints)
        // What is left is `#1`, the call the first build folded and this one did not select.
        val left = refolded.sites().filter { it.caller.name == "repeated" && it.callee.signature.endsWith("enabled(java.lang.String)") }
        assertEquals(listOf(1), left.map { it.ordinal })
    }

    @Test
    fun `a select rule folds the call sites whose keys it carries and reports the ones it cannot find`() {
        val classes = compile()
        val unfolded = load(classes).sites().filter { it.callee.name == "enabled" && it.ordinal!! >= 0 }
        val keys = unfolded.map { callSiteKey(it) }.toSet()
        val enabled = "$packageName.Flags.enabled(java.lang.String)"
        val missing = CallSiteKey("$packageName.Shapes.gone()", "()V", enabled, "(Ljava/lang/String;)Z", 0)
        val rule = FoldSites(
            select = "MATCH (cs:CallSite {callee_name: 'enabled'}) RETURN cs",
            selected = keys + missing,
            value = ConstantPattern.parse(false, "value")
        )
        var report: FoldReport? = null
        val folded = load(classes, listOf(rule)) { report = it }
        val outcome = report!!.outcomes.single()
        assertEquals(keys.size, outcome.matched, outcome.sites.toString())
        assertEquals(listOf("selected call site $missing is not in this build"), outcome.hints)
        assertEquals(emptyList(), folded.sites().filter { it.callee.name == "enabled" && it.ordinal!! >= 0 }.map(::key))
        assertEquals("select {MATCH (cs:CallSite {callee_name: 'enabled'}) RETURN cs} 7 selected call site(s) = false", rule.description)

        // An unresolved rule (the query has not run) names no call and folds nothing.
        var unresolvedReport: FoldReport? = null
        load(classes, listOf(rule.copy(selected = null))) { unresolvedReport = it }
        assertEquals(0, unresolvedReport!!.outcomes.single().matched)
        assertEquals(emptyList(), unresolvedReport!!.outcomes.single().hints)
    }

    @Test
    fun `the triples read off an unfolded graph fold every call they name`() {
        val classes = compile()
        val unfolded = load(classes).sites().filter { it.callee.name == "enabled" && it.ordinal!! >= 0 }
        assertEquals(6, unfolded.size, unfolded.map(::key).toString())
        val rules = unfolded.map { site ->
            ConstantFold.parse(
                mapOf(
                    "match" to mapOf(
                        "CallSite" to mapOf(
                            "caller_signature" to site.caller.signature,
                            "callee_signature" to site.callee.signature,
                            "ordinal" to site.ordinal
                        )
                    ),
                    "value" to false
                )
            )
        }
        var report: FoldReport? = null
        val folded = load(classes, rules) { report = it }
        assertEquals(List(rules.size) { 1 }, report!!.outcomes.map { it.matched }, report!!.outcomes.map { it.fold.description }.toString())
        assertEquals(emptyList(), folded.sites().filter { it.callee.name == "enabled" && it.ordinal!! >= 0 }.map(::key))
    }
}
