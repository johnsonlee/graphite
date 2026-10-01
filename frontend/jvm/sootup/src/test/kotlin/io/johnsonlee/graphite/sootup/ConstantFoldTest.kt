package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.incoming
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.FoldPlan
import io.johnsonlee.graphite.input.FoldReport
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.relativeTo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Folding gate calls to constants while the graph is built: every shape a gated block takes
 * must leave nothing of the side the constant rules out, whatever the branch sides look like.
 */
class ConstantFoldTest {

    private val packageName = "sample.fold"
    private val flags = "$packageName.Flags"
    private val shapes = "$packageName.Shapes"
    private val temporaryRoots = mutableListOf<Path>()

    @AfterTest
    fun deleteCompiledFixtures() {
        temporaryRoots.forEach { it.toFile().deleteRecursively() }
    }

    private val source = """
        package sample.fold;

        public class Shapes {
            private int count;
            static void work() { }
            static void tail() { }
            static void other() { }
            public int earlyReturnConstant() { if (Flags.enabled("k")) { return 1; } return 2; }
            public void guardReturn() { if (!Flags.enabled("k")) { return; } work(); work(); }
            public void fieldStore() { if (Flags.enabled("k")) { count = 0; return; } work(); }
            public void callBody() { if (Flags.enabled("k")) { work(); } tail(); }
            public void throwBody() { if (Flags.enabled("k")) { throw new IllegalStateException("x"); } tail(); }
            public void andShape(boolean a) { boolean x = a && Flags.enabled("k"); if (x) { work(); } tail(); }
            public void discarded() { Flags.enabled("k"); tail(); }
            public void objectGate() { if (Flags.enabledObject("k") == Boolean.TRUE) { work(); } tail(); }
            public void untouched() { other(); tail(); }
            public int limit() { return Flags.limit() + 1; }
            public String label() { return Flags.label(); }
            public void below() { if (Flags.limit() < 5) { work(); } tail(); }
            public void atMost() { if (Flags.limit() <= 2) { work(); } tail(); }
            public void above() { if (Flags.big() > 100L) { work(); } tail(); }
            public void atLeast() { if (Flags.big() >= 100L) { work(); } tail(); }
            public void nullRef() { if (Flags.ref() != null) { work(); } tail(); }
            public void nullArray() { if (Flags.arr() == null) { work(); } tail(); }
            public int small() { return Flags.small() + Flags.tiny() + Flags.letter(); }
            public double wide() { return Flags.ratio() + Flags.precise(); }
            public void keyedA() { if (Flags.enabled("a")) { work(); } tail(); }
            public void keyedB() { if (Flags.enabled("b")) { work(); } tail(); }
            public void keyedDynamic(String key) { if (Flags.enabled(key)) { work(); } tail(); }
            public void enumOn() { if (Flags.mode(Flags.Mode.ON)) { work(); } tail(); }
            public void enumOff() { if (Flags.mode(Flags.Mode.OFF)) { work(); } tail(); }
            public void marked() { if (Flags.marked(Flags.MARKER)) { work(); } tail(); }
            public void boolArg() { if (Flags.flag(true)) { work(); } tail(); }
            public void longArg() { if (Flags.wideKey(7L)) { work(); } tail(); }
            public void floatArg() { if (Flags.narrow(1.5f)) { work(); } tail(); }
            public void doubleArg() { if (Flags.exact(0.5)) { work(); } tail(); }
            public void nullArg() { if (Flags.marked(null)) { work(); } tail(); }
            public void classArg() { if (Flags.marked(Shapes.class)) { work(); } tail(); }
        }
    """.trimIndent()

    private val flagsSource = """
        package sample.fold;

        public class Flags {
            public static boolean enabled(String key) { return key.length() > 3; }
            public static Boolean enabledObject(String key) { return key.length() > 3; }
            public static int limit() { return 10; }
            public static String label() { return "live"; }
            public static long big() { return 10L; }
            public static short small() { return 1; }
            public static byte tiny() { return 1; }
            public static char letter() { return 'a'; }
            public static float ratio() { return 1f; }
            public static double precise() { return 1d; }
            public static Object ref() { return null; }
            public static int[] arr() { return null; }
            public enum Mode { ON, OFF }
            public static final Object MARKER = new Object();
            public static boolean mode(Mode mode) { return mode == Mode.ON; }
            public static boolean marked(Object marker) { return marker == MARKER; }
            public static boolean flag(boolean on) { return on; }
            public static boolean wideKey(long key) { return key > 0; }
            public static boolean narrow(float key) { return key > 0; }
            public static boolean exact(double key) { return key > 0; }
        }
    """.trimIndent()

    private fun compile(): Path {
        val root = Files.createTempDirectory("constant-fold").also(temporaryRoots::add)
        val sourceDir = root.resolve("src/sample/fold").also { Files.createDirectories(it) }
        val classesDir = root.resolve("classes").also { Files.createDirectories(it) }
        val files = listOf("Shapes.java" to source, "Flags.java" to flagsSource).map { (name, text) ->
            sourceDir.resolve(name).also { Files.writeString(it, text) }.toString()
        }
        val result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classesDir.toString(), *files.toTypedArray())
        assertEquals(0, result, "fixture compiles")
        return classesDir
    }

    private fun load(input: Path, vararg folds: ConstantFold, report: (FoldReport) -> Unit = {}): Graph =
        JavaProjectLoader(
            LoaderConfig(
                includePackages = listOf(packageName),
                buildCallGraph = false,
                folding = folds.toList().takeIf { it.isNotEmpty() }?.let { FoldPlan(it, report) }
            )
        ).load(input)

    private fun Graph.callees(method: String): List<String> =
        nodes<CallSiteNode>().filter { it.caller.name == method }.map { it.callee.name }.sorted().toList()

    private fun Graph.returnedConstants(method: String): Set<Int> {
        val returnNode = nodes<ReturnNode>().single { it.method.name == method }
        return incoming(returnNode.id).mapNotNull { node(it.from) as? IntConstant }.map { it.value }.toSet()
    }

    /** A rule in its document form: the call-site properties, the argument constants and the value. */
    private fun rule(vararg site: Pair<String, Any?>, args: Map<Int, Any?> = emptyMap(), value: Any?): ConstantFold =
        ConstantFold.parse(mapOf("match" to mapOf("CallSite" to site.toMap()), "args" to args, "value" to value))

    private fun gate(name: String, value: Any?, args: Map<Int, Any?> = emptyMap()): ConstantFold =
        rule("callee_class" to flags, "callee_name" to name, args = args, value = value)

    private fun typed(label: String, vararg properties: Pair<String, Any?>): Map<String, Any?> = mapOf(label to properties.toMap())

    private val gateOff = gate("enabled", false)
    private val gateOn = gate("enabled", true)

    @Test
    fun `a gate folded to false leaves nothing of the side it rules out, whatever its shape`() {
        var report: FoldReport? = null
        val graph = load(compile(), gateOff) { report = it }

        assertEquals(emptyList(), graph.callees("earlyReturnConstant"))
        assertEquals(setOf(2), graph.returnedConstants("earlyReturnConstant"), "the early return is gone")
        assertEquals(emptyList(), graph.callees("guardReturn"), "a false guard returns before the work")
        assertEquals(listOf("work"), graph.callees("fieldStore"))
        assertEquals(listOf("tail"), graph.callees("callBody"))
        assertEquals(listOf("tail"), graph.callees("throwBody"), "the throw and its constructor are gone")
        assertEquals(listOf("tail"), graph.callees("andShape"), "a && false never runs the work")
        assertEquals(listOf("tail"), graph.callees("discarded"), "a discarded call disappears")
        assertEquals(listOf("other", "tail"), graph.callees("untouched"))

        val outcome = assertNotNull(report).outcomes.single()
        assertEquals(gateOff, outcome.fold)
        assertEquals(10, outcome.matched, "one call per gated method: ${outcome.sites}")
        assertEquals(listOf("tail"), graph.callees("keyedDynamic"), "without an argument pattern every key folds")
        val guard = outcome.sites.single { it.method == "$shapes.guardReturn()" }
        assertEquals(1, guard.calls)
        assertTrue(guard.statementsAfter < guard.statementsBefore, "dead code was removed: $guard")
        assertTrue(outcome.statementsRemoved > 0)
        assertEquals(emptyList(), outcome.unsupported)
        assertEquals(emptyList(), report!!.unmatched)
    }

    @Test
    fun `a gate folded to true keeps the gated side and drops the other`() {
        val graph = load(compile(), gateOn)

        assertEquals(setOf(1), graph.returnedConstants("earlyReturnConstant"))
        assertEquals(listOf("work", "work"), graph.callees("guardReturn"))
        assertEquals(emptyList(), graph.callees("fieldStore"), "the method returns inside the gated block")
        assertEquals(listOf("tail", "work"), graph.callees("callBody"))
        assertEquals(listOf("<init>"), graph.callees("throwBody"), "only the exception's constructor survives")
        assertEquals(listOf("tail", "work"), graph.callees("andShape"), "a && true depends on a")
    }

    @Test
    fun `methods no rule touches go through the default chain unchanged`() {
        val classes = compile()
        val plain = load(classes)
        val folded = load(classes, gateOff)
        for (method in listOf("untouched", "objectGate", "limit", "label")) {
            assertEquals(plain.callees(method), folded.callees(method), method)
        }
        assertEquals(listOf("enabled", "tail"), plain.callees("discarded"), "without a rule the gate call stays")
    }

    @Test
    fun `a return type that cannot carry the value is reported, not folded`() {
        var report: FoldReport? = null
        val graph = load(compile(), gate("enabledObject", false)) { report = it }
        assertEquals(listOf("enabledObject", "tail", "work"), graph.callees("objectGate"))
        val outcome = report!!.outcomes.single()
        assertEquals(0, outcome.matched)
        assertEquals("$shapes.objectGate()", outcome.unsupported.single().method)
        assertTrue(outcome.unsupported.single().reason.contains("java.lang.Boolean"), outcome.unsupported.single().reason)
        assertEquals(emptyList(), report!!.unmatched, "a rule that matched but could not fold is not unmatched")
    }

    @Test
    fun `numeric and string returns fold, a null folds a reference`() {
        var report: FoldReport? = null
        val graph = load(
            compile(),
            gate("limit", 3),
            gate("label", "fixed"),
            gate("enabledObject", null)
        ) { report = it }
        assertEquals(emptyList(), graph.callees("limit"))
        assertEquals(emptyList(), graph.callees("label"))
        assertEquals(listOf("tail", "work"), graph.callees("objectGate"), "the call is gone; Boolean.TRUE is a field, not a constant")
        assertEquals(listOf(3, 1, 1), report!!.outcomes.map { it.matched }, "limit is called from three methods")
    }

    @Test
    fun `every carrier type folds and every comparison on constants resolves`() {
        var report: FoldReport? = null
        val graph = load(
            compile(),
            gate("limit", 3),
            gate("big", 100),
            gate("ref", null),
            gate("arr", null),
            gate("small", 1),
            gate("tiny", 2),
            gate("letter", 3),
            gate("ratio", 4L),
            gate("precise", 0.5)
        ) { report = it }
        assertEquals(listOf("tail", "work"), graph.callees("below"), "3 < 5")
        assertEquals(listOf("tail"), graph.callees("atMost"), "3 <= 2 fails")
        assertEquals(listOf("tail"), graph.callees("above"), "100 > 100 fails")
        assertEquals(listOf("tail", "work"), graph.callees("atLeast"), "100 >= 100")
        assertEquals(listOf("tail"), graph.callees("nullRef"), "null != null fails")
        assertEquals(listOf("tail", "work"), graph.callees("nullArray"), "null == null")
        assertEquals(emptyList(), graph.callees("small"), "short, byte and char carry an int")
        assertEquals(emptyList(), graph.callees("wide"), "float and double carry a long or a double")
        assertEquals(emptyList(), report!!.unmatched)
        assertTrue(report!!.outcomes.all { it.unsupported.isEmpty() }, report!!.outcomes.flatMap { it.unsupported }.toString())
    }

    @Test
    fun `a value the return type cannot carry is unsupported whatever the type`() {
        var report: FoldReport? = null
        load(
            compile(),
            gate("limit", "text"),
            gate("big", "text"),
            gate("ratio", "text"),
            gate("label", 1),
            gate("ref", "text"),
            gate("enabled", 1)
        ) { report = it }
        assertTrue(report!!.outcomes.all { it.unsupported.isNotEmpty() }, report.toString())
        assertTrue(report!!.outcomes.all { it.matched == 0 }, report.toString())
        // The discarded call too: a value its return type cannot carry means the rule names
        // another call, and deleting this one would delete its effects.
        assertTrue(report!!.outcomes.last().unsupported.all { it.reason == "return type boolean cannot carry 1" }, report.toString())
        assertEquals(emptyList(), report!!.unmatched)
    }

    @Test
    fun `a byte, a short and a char carry only their own range, whatever the carrier`() {
        // Jimple carries all three as an int; the declared type still bounds what the method returns.
        val outside = listOf(
            "tiny" to 128, "tiny" to typed("IntConstant", "value" to -129),
            "small" to 32768, "small" to typed("IntConstant", "value" to -32769),
            "letter" to -1, "letter" to typed("IntConstant", "value" to 65536)
        )
        for ((method, value) in outside) {
            var report: FoldReport? = null
            load(compile(), gate(method, value)) { report = it }
            val outcome = report!!.outcomes.single()
            assertEquals(0, outcome.matched, "$method = $value: $outcome")
            assertTrue(outcome.unsupported.isNotEmpty(), "$method = $value is reported")
        }
        var report: FoldReport? = null
        load(compile(), gate("tiny", -128), gate("small", typed("IntConstant", "value" to 32767)), gate("letter", 65535)) { report = it }
        assertTrue(report!!.outcomes.all { it.matched > 0 && it.unsupported.isEmpty() }, report.toString())
    }

    @Test
    fun `a float cannot carry a finite double past its range`() {
        // 1e308 is finite as a double and an infinity as a float: narrowed, the call would
        // become a value nobody wrote. Labelled or inferred, the site is reported instead.
        for (value in listOf<Any>(typed("FloatConstant", "value" to 1e308), -1e308)) {
            var report: FoldReport? = null
            load(compile(), gate("ratio", value)) { report = it }
            val outcome = report!!.outcomes.single()
            assertEquals(0, outcome.matched, outcome.toString())
            assertEquals(
                listOf("return type float cannot carry ${outcome.fold.value.description}"),
                outcome.unsupported.map { it.reason }.distinct()
            )
        }
    }

    @Test
    fun `a signature narrows a rule to one overload and an unmatched rule is reported`() {
        var report: FoldReport? = null
        val graph = load(
            compile(),
            rule("callee_signature" to "$flags.enabled(int)", value = false),
            rule("callee_signature" to "$flags.enabled(java.lang.String)", value = false)
        ) { report = it }
        assertEquals(listOf("tail"), graph.callees("callBody"))
        assertEquals(listOf(rule("callee_signature" to "$flags.enabled(int)", value = false)), report!!.unmatched)
        assertEquals(listOf(0, 10), report!!.outcomes.map { it.matched })
    }

    @Test
    fun `an argument constant selects the calls a rule folds`() {
        var report: FoldReport? = null
        val graph = load(
            compile(),
            gate("enabled", false, args = mapOf(0 to "a")),
            gate("enabled", true, args = mapOf(0 to typed("StringConstant", "value" to "b"))),
            gate("enabled", false, args = mapOf(1 to "x"))
        ) { report = it }
        assertEquals(listOf("tail"), graph.callees("keyedA"), "enabled(\"a\") is false")
        assertEquals(listOf("tail", "work"), graph.callees("keyedB"), "enabled(\"b\") is true")
        assertEquals(listOf("enabled", "tail", "work"), graph.callees("keyedDynamic"), "a key from a parameter is not a constant")
        assertEquals(listOf("enabled", "tail", "work"), graph.callees("callBody"), "enabled(\"k\") matches no rule and stays")
        val (offA, onB, missing) = report!!.outcomes
        assertEquals(1, offA.matched)
        assertEquals(1, onB.matched)
        assertEquals(0, missing.matched, "an index past the arguments never matches")
        val unsupported = offA.unsupported.single()
        assertEquals("$shapes.keyedDynamic(java.lang.String)", unsupported.method)
        assertEquals("argument 0 of $flags.enabled(java.lang.String) is not a constant", unsupported.reason)
        assertEquals(emptyList(), onB.unsupported, "the unresolved argument is reported once, under the first rule")
        assertEquals(listOf(missing.fold), report!!.unmatched)
    }

    @Test
    fun `every constant kind an argument can be is matched by its label`() {
        val mode = "$flags\$Mode"
        var report: FoldReport? = null
        val graph = load(
            compile(),
            gate("mode", false, args = mapOf(0 to typed("EnumConstant", "enum_type" to mode, "name" to "ON"))),
            gate("marked", false, args = mapOf(0 to typed("Field", "class" to flags, "name" to "MARKER"))),
            gate("marked", false, args = mapOf(0 to null)),
            gate("flag", false, args = mapOf(0 to true)),
            gate("wideKey", false, args = mapOf(0 to typed("LongConstant", "value" to 7))),
            gate("narrow", false, args = mapOf(0 to typed("FloatConstant", "value" to 1.5))),
            gate("exact", false, args = mapOf(0 to 0.5)),
            gate("flag", false, args = mapOf(0 to typed("BooleanConstant", "value" to false))),
            gate("wideKey", false, args = mapOf(0 to typed("IntConstant", "value" to 7)))
        ) { report = it }
        assertEquals(listOf("tail"), graph.callees("enumOn"), "Mode.ON is the enum constant")
        assertEquals(listOf("mode", "tail", "work"), graph.callees("enumOff"), "Mode.OFF is another")
        assertEquals(listOf("tail"), graph.callees("marked"), "a static field read is a Field")
        assertEquals(listOf("tail"), graph.callees("nullArg"), "null is a NullConstant")
        assertEquals(listOf("marked", "tail", "work"), graph.callees("classArg"), "a class literal is not a constant the rule can name")
        assertEquals(listOf("tail"), graph.callees("boolArg"), "true is a BooleanConstant")
        assertEquals(listOf("tail"), graph.callees("longArg"), "7L is a LongConstant")
        assertEquals(listOf("tail"), graph.callees("floatArg"))
        assertEquals(listOf("tail"), graph.callees("doubleArg"))
        val matched = report!!.outcomes.map { it.matched }
        assertEquals(listOf(1, 1, 1, 1, 1, 1, 1, 0, 0), matched, "BooleanConstant false and IntConstant 7 match nothing")
        assertEquals("$shapes.classArg()", report!!.outcomes[1].unsupported.single().method)
    }

    @Test
    fun `caller properties scope a rule and a glob matches a run of characters`() {
        var report: FoldReport? = null
        val graph = load(
            compile(),
            rule("callee_class" to flags, "callee_name" to "enab*", "caller_name" to "guard*", value = false),
            rule("callee_class" to "sample.*", "callee_name" to "enabled", "caller_signature" to "$shapes.callBody()", value = false)
        ) { report = it }
        assertEquals(emptyList(), graph.callees("guardReturn"))
        assertEquals(listOf("tail"), graph.callees("callBody"))
        assertEquals(listOf("enabled", "tail", "work"), graph.callees("keyedA"), "a caller the rules do not name keeps its call")
        assertEquals(listOf(1, 1), report!!.outcomes.map { it.matched })
    }

    @Test
    fun `a labelled value pins the constant the call becomes`() {
        var report: FoldReport? = null
        val graph = load(
            compile(),
            gate("enabled", typed("BooleanConstant", "value" to false)),
            gate("big", typed("LongConstant", "value" to 100)),
            gate("limit", typed("IntConstant", "value" to 3)),
            gate("label", typed("StringConstant", "value" to "x")),
            gate("ref", typed("NullConstant")),
            gate("precise", typed("DoubleConstant", "value" to 0.5)),
            gate("ratio", typed("FloatConstant", "value" to 4)),
            gate("enabledObject", typed("IntConstant", "value" to 0)),
            gate("tiny", typed("IntConstant", "value" to 3_000_000_000L)),
            gate("small", typed("IntConstant")),
            gate("letter", typed("Constant"))
        ) { report = it }
        assertEquals(listOf("tail"), graph.callees("callBody"))
        assertEquals(listOf("tail"), graph.callees("above"), "100L > 100L fails")
        assertEquals(listOf("tail", "work"), graph.callees("below"), "3 < 5")
        assertEquals(emptyList(), graph.callees("label"))
        assertEquals(listOf("tail"), graph.callees("nullRef"))
        assertEquals(emptyList(), graph.callees("wide"))
        val outcomes = report!!.outcomes
        assertTrue(outcomes.take(7).all { it.unsupported.isEmpty() && it.matched > 0 }, outcomes.toString())
        val reasons = outcomes.drop(7).map { it.unsupported.single().reason }
        assertTrue(reasons[0].contains("java.lang.Boolean cannot carry IntConstant {value: 0}"), reasons[0])
        assertTrue(reasons[1].contains("byte cannot carry IntConstant {value: 3000000000}"), reasons[1])
        assertTrue(reasons[2].contains("short cannot carry IntConstant {}"), reasons[2])
        assertTrue(reasons[3].contains("char cannot carry Constant {}"), reasons[3])
    }

    @Test
    fun `a rule that folds nothing reports the nearest calls`() {
        var report: FoldReport? = null
        load(
            compile(),
            gate("enabled", false, args = mapOf(0 to "zzz")),
            rule("callee_class" to "sample.fold.Nope", "callee_name" to "enabled", value = false),
            rule("callee_class" to flags, "callee_name" to "enabled", "caller_class" to "x.*", value = false),
            gate("missing", false),
            rule("callee_class" to "sample.fold.Nope*", value = 1)
        ) { report = it }
        val outcomes = report!!.outcomes
        val byArgument = outcomes[0]
        val byClass = outcomes[1]
        val byCaller = outcomes[2]
        val none = outcomes[3]
        val byClassOnly = outcomes[4]
        assertTrue(report!!.outcomes.all { it.matched == 0 }, report.toString())
        assertEquals(
            setOf(
                "$flags.enabled(java.lang.String) is called with argument 0 = StringConstant {value: \"k\"} (7 call(s))",
                "$flags.enabled(java.lang.String) is called with argument 0 = StringConstant {value: \"a\"} (1 call(s))",
                "$flags.enabled(java.lang.String) is called with argument 0 = StringConstant {value: \"b\"} (1 call(s))",
                "$flags.enabled(java.lang.String) is called with argument 0 = not a constant (1 call(s))"
            ),
            byArgument.hints.toSet(),
            "hints follow the order methods are processed in, which is the frontend's"
        )
        assertEquals(1, byArgument.unsupported.size, "the parameter key is still reported")
        val fromCallBody = "$flags.enabled(java.lang.String) is called from $shapes.callBody(), where callee_class is $flags (1 call(s))"
        assertTrue(fromCallBody in byClass.hints, byClass.hints.toString())
        assertTrue(byClass.hints.size <= 8, "hints are bounded: ${byClass.hints}")
        val fromGuard = "$flags.enabled(java.lang.String) is called from $shapes.guardReturn(), where caller_class is $shapes (1 call(s))"
        assertTrue(fromGuard in byCaller.hints, byCaller.hints.toString())
        assertEquals(emptyList(), none.hints, "nothing is called 'missing'")
        assertEquals(emptyList(), byClassOnly.hints, "a rule naming only a class nothing has is not a near miss")
        assertEquals(emptyList(), gateOffHints())
    }

    private fun gateOffHints(): List<String> {
        var report: FoldReport? = null
        load(compile(), gateOff) { report = it }
        return report!!.outcomes.single().hints.filter { "argument" in it }
    }

    @Test
    fun `a jar input folds like a directory and no report is produced without rules`() {
        val classes = compile()
        val jar = classes.parent.resolve("shapes.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            Files.walk(classes).filter { Files.isRegularFile(it) }.forEach { file ->
                out.putNextEntry(JarEntry(file.relativeTo(classes).toString().replace('\\', '/')))
                out.write(Files.readAllBytes(file))
                out.closeEntry()
            }
        }
        var report: FoldReport? = null
        assertEquals(emptyList(), load(jar, gateOff) { report = it }.callees("guardReturn"))
        assertNotNull(report)

        var withoutRules: FoldReport? = null
        load(jar) { withoutRules = it }
        assertNull(withoutRules, "no rules, no report")
    }
}
