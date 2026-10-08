package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ReturnNode
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes

/**
 * What a `select` rule needs beyond a key: a call reached through an erased function value, a
 * key read through a helper, two methods one signature apart, and one statement count per method.
 */
class FoldSelectionTest {

    private val packageName = "sample.selection"
    private val temporaryRoots = mutableListOf<Path>()

    @AfterTest
    fun deleteCompiledFixtures() {
        temporaryRoots.forEach { it.toFile().deleteRecursively() }
    }

    private val sources = mapOf(
        "Flags.java" to """
            package sample.selection;
            public class Flags {
                public static boolean enabled(String key) { return key.length() > 3; }
                public static boolean other(String key) { return key.length() > 4; }
                public static int variant(String key) { return key.length(); }
                public static String name(String key) { return key; }
            }
        """,
        "Mode.java" to "package sample.selection; public enum Mode { A, B }",
        "Box.java" to """
            package sample.selection;
            public class Box {
                private final int key;
                Box(int key) { this.key = key; }
                static Box of(int key) { return new Box(key); }
                boolean isOn() { return key > 3; }
            }
        """,
        "Helper.java" to "package sample.selection; public class Helper { static Box box() { return Box.of(1234); } }",
        "Gate.java" to """
            package sample.selection;
            public class Gate {
                static boolean gate(String key) { return Flags.enabled(key); }
                static int id(int x) { return x; }
                static boolean on(int key) { return key > 3; }
            }
        """,
        "Shapes.java" to """
            package sample.selection;
            import java.util.function.Function;
            import java.util.function.Supplier;
            public class Shapes {
                static void work(int which) { }
                public void erased() {
                    Supplier<Boolean> gate = () -> Flags.enabled("x");
                    if (gate.get()) { work(1); }
                }
                public void chosen() {
                    Function<Integer, Mode> fn = k -> Mode.B;
                    if (fn.apply(3) == Mode.B) { work(2); }
                }
                public void counted() {
                    Supplier<Integer> n = () -> 3;
                    if (n.get() > 2) { work(3); }
                }
                public void named() {
                    Supplier<String> s = () -> "on";
                    work(s.get().length());
                }
                public void helped() {
                    if (Helper.box().isOn()) { work(4); }
                }
                public void twoRules() {
                    if (Flags.enabled("a")) { work(5); }
                    if (Flags.other("b")) { work(6); }
                }
                public void outer() {
                    if (Gate.gate("new_checkout")) { work(7); }
                    if (Gate.gate("dark_mode")) { work(8); }
                }
                public void leaked() {
                    int a = Gate.id(1234);
                    int b = Gate.id(5678);
                    if (Gate.on(b)) { work(9); }
                    work(a);
                }
                public void bare() {
                    if (Flags.enabled("z")) { work(10); }
                }
                public void run(int[] a) { if (Flags.enabled("k")) { work(11); } }
                public void run(int[][] a) { if (Flags.enabled("k")) { work(12); } }
                public void consumed() { int v = Flags.variant("k"); work(v); work(0); }
                static Box boxed(int key) { return Box.of(key); }
                static boolean onBox(Box b) { return b.isOn(); }
                public void twoKeys() {
                    Box a = boxed(1234);
                    Box b = boxed(5678);
                    if (a.isOn()) { work(13); }
                    if (b.isOn()) { work(14); }
                }
                public void cleaned() { int v = Flags.variant("c"); if (Flags.enabled("c")) { work(v); } }
                public void lengthy() { String s = Flags.name("k"); work(s.length()); }
            }
        """
    )

    private fun compile(): Path {
        val root = Files.createTempDirectory("fold-selection").also(temporaryRoots::add)
        val sourceDir = root.resolve("src/sample/selection").also { Files.createDirectories(it) }
        val classesDir = root.resolve("classes").also { Files.createDirectories(it) }
        val files = sources.map { (name, text) -> sourceDir.resolve(name).also { Files.writeString(it, text.trimIndent()) }.toString() }
        val result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classesDir.toString(), *files.toTypedArray())
        assertEquals(0, result, "fixture compiles")
        Files.write(classesDir.resolve("sample/selection/Overloads.class"), overloads())
        return classesDir
    }

    /**
     * `Overloads.same()` twice, returning `Object` and `String`, each testing `Flags.enabled("k")`
     * once: legal bytecode (a bridge beside its covariant override has this shape) that javac
     * does not write from one source file.
     */
    private fun overloads(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "sample/selection/Overloads", null, "java/lang/Object", null)
        listOf("Ljava/lang/Object;" to 7, "Ljava/lang/String;" to 8).forEach { (returnType, which) ->
            val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "same", "()$returnType", null, null)
            method.visitCode()
            method.visitLdcInsn("k")
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "sample/selection/Flags", "enabled", "(Ljava/lang/String;)Z", false)
            val skip = Label()
            method.visitJumpInsn(Opcodes.IFEQ, skip)
            method.visitIntInsn(Opcodes.BIPUSH, which)
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "sample/selection/Shapes", "work", "(I)V", false)
            method.visitLabel(skip)
            method.visitInsn(Opcodes.ACONST_NULL)
            method.visitInsn(Opcodes.ARETURN)
            method.visitMaxs(0, 0)
            method.visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun load(
        input: Path,
        folds: List<FoldRule> = emptyList(),
        interprocedural: Boolean = false,
        report: (FoldReport) -> Unit = {}
    ): Graph = JavaProjectLoader(
        LoaderConfig(
            includePackages = listOf(packageName),
            interproceduralDataflow = interprocedural,
            folding = folds.takeIf { it.isNotEmpty() }?.let { FoldPlan(it, report) }
        )
    ).load(input)

    private fun Graph.sites(caller: String): List<CallSiteNode> =
        nodes<CallSiteNode>().filter { it.caller.declaringClass.className.startsWith(packageName) && it.caller.name == caller }.toList()

    /** The `work(n)` calls of [caller] that survive, by the constant passed. */
    private fun Graph.work(caller: String): List<Int> = sites(caller).filter { it.callee.name == "work" }
        .flatMap { site -> site.arguments.mapNotNull { (node(it) as? IntConstant)?.value } }.sorted()

    private fun key(site: CallSiteNode) =
        CallSiteKey(site.caller.signature, site.caller.descriptor, site.callee.signature, site.callee.descriptor, site.ordinal!!)

    private fun select(site: CallSiteNode, value: Any?, resultType: String? = null): FoldSites {
        val key = key(site)
        val resultTypes = listOfNotNull(resultType?.let { key to it }).toMap()
        return FoldSites("MATCH (cs:CallSite) RETURN cs", setOf(key), ConstantPattern.parse(value, "value"), resultTypes)
    }

    private fun select(sites: List<CallSiteNode>, value: Any?, args: Map<Int, Any?>): FoldSites = FoldSites(
        "MATCH (cs:CallSite) RETURN cs",
        sites.map(::key).toSet(),
        ConstantPattern.parse(value, "value"),
        arguments = args.mapValues { (index, pattern) -> ConstantPattern.parse(pattern, "args.$index") }
    )

    private fun FoldReport.unsupported(): List<String> = outcomes.flatMap { it.unsupported }.map { it.reason }

    private fun fold(classes: Path, rule: FoldRule): Pair<Graph, FoldReport> {
        var report: FoldReport? = null
        val graph = load(classes, listOf(rule)) { report = it }
        return graph to assertNotNull(report)
    }

    private fun erasedCall(graph: Graph, caller: String, callee: String): CallSiteNode =
        graph.sites(caller).single { it.callee.name == callee && it.ordinal!! >= 0 }

    @Test
    fun `a call resolved to a lambda body remembers the call it was resolved from`() {
        val graph = load(compile())
        val get = erasedCall(graph, "erased", "get")
        val derived = graph.sites("erased").filter { it.ordinal!! < 0 && it.callee.name.startsWith("lambda$") }
        assertTrue(derived.isNotEmpty())
        // The lambda's creation is derived too, but from no call: only the dispatch has an origin.
        assertEquals(listOf(get.id), derived.mapNotNull { it.origin }.distinct())
        assertEquals("java.lang.Boolean", derived.single { it.origin != null }.callee.returnType.className)
        assertNull(get.origin)
    }

    @Test
    fun `a boolean folds through an erased call, its cast and its unboxing`() {
        val classes = compile()
        val get = erasedCall(load(classes), "erased", "get")
        assertEquals("()Ljava/lang/Object;", get.callee.descriptor)

        val (off, report) = fold(classes, select(get, false, "java.lang.Boolean"))
        assertEquals(1, report.outcomes.single().matched, report.toString())
        assertEquals(emptyList(), off.work("erased"), "the gated call is gone")
        assertTrue(off.sites("erased").none { it.callee.name == "get" || it.callee.name == "booleanValue" })

        val (on, _) = fold(classes, select(get, true, "boolean"))
        assertEquals(listOf(1), on.work("erased"))

        // A `match` rule without a result type boxes the constant as the type it names.
        val match = ConstantFold(mapOf("callee_name" to "get", "caller_name" to "erased"), value = ConstantPattern.parse(false, "value"))
        val (matched, matchReport) = fold(classes, match)
        assertEquals(1, matchReport.outcomes.single().matched)
        assertEquals(emptyList(), matched.work("erased"))
    }

    @Test
    fun `an enum constant folds through an erased call and its cast`() {
        val classes = compile()
        val apply = erasedCall(load(classes), "chosen", "apply")
        fun enum(name: String) = mapOf("EnumConstant" to mapOf("enum_type" to "$packageName.Mode", "name" to name))

        val (other, report) = fold(classes, select(apply, enum("A"), "$packageName.Mode"))
        assertEquals(1, report.outcomes.single().matched, report.toString())
        assertEquals(emptyList(), other.work("chosen"), "A is not B")

        val (same, _) = fold(classes, select(apply, enum("B")))
        assertEquals(listOf(2), same.work("chosen"))

        val (_, refused) = fold(classes, select(apply, enum("A"), "java.lang.Boolean"))
        val outcome = refused.outcomes.single()
        assertEquals(0, outcome.matched)
        assertEquals(
            "return type java.lang.Object (result type java.lang.Boolean) cannot carry " +
                "EnumConstant {enum_type: \"$packageName.Mode\", name: \"A\"}",
            outcome.unsupported.single().reason
        )
    }

    @Test
    fun `a number is boxed as the result type and a string stands as itself`() {
        val classes = compile()
        val graph = load(classes)
        val counted = erasedCall(graph, "counted", "get")

        val (small, report) = fold(classes, select(counted, 1, "java.lang.Integer"))
        assertEquals(1, report.outcomes.single().matched)
        assertEquals(emptyList(), small.work("counted"), "1 > 2 is false")
        val (large, _) = fold(classes, select(counted, 5, "int"))
        assertEquals(listOf(3), large.work("counted"))
        val (_, long) = fold(classes, select(counted, 5L, "long"))
        assertEquals(1, long.outcomes.single().matched, "boxed as the type the CLI read off the lambda")

        val named = erasedCall(graph, "named", "get")
        val (_, string) = fold(classes, select(named, "abc", "java.lang.String"))
        assertEquals(1, string.outcomes.single().matched)
        val (_, natural) = fold(classes, select(named, "abc"))
        assertEquals(1, natural.outcomes.single().matched)
        val (_, unknown) = fold(classes, select(named, "abc", "java.util.List"))
        assertEquals(0, unknown.outcomes.single().matched)
        val (_, nothing) = fold(classes, select(named, null))
        assertEquals(1, nothing.outcomes.single().matched, "null fits any reference")
    }

    @Test
    fun `interprocedural dataflow carries a key through a helper's return to the gate`() {
        val classes = compile()
        fun reaches(graph: Graph): Boolean {
            val key = graph.nodes<IntConstant>().single { it.value == 1234 }.id
            val gate = graph.sites("helped").single { it.callee.name == "isOn" }.id
            return graph.reachable(key, hops = 8).contains(gate)
        }
        val plain = load(classes)
        assertFalse(reaches(plain), "without the flag a value stops at the helper's ReturnNode")
        val linked = load(classes, interprocedural = true)
        assertTrue(reaches(linked))

        val box = linked.nodes<ReturnNode>().single { it.method.name == "box" }
        val boxCall = linked.sites("helped").single { it.callee.name == "box" }
        val result = linked.outgoing(boxCall.id).filterIsInstance<DataFlowEdge>().single { it.kind == DataFlowKind.RETURN_VALUE }.to
        assertTrue(linked.outgoing(box.id).any { it is DataFlowEdge && it.to == result && it.kind == DataFlowKind.RETURN_VALUE })
        val of = linked.nodes<ParameterNode>().single { it.method.name == "of" }
        assertTrue(linked.incoming(of.id).any { it is DataFlowEdge && it.kind == DataFlowKind.PARAMETER_PASS })
        assertTrue(plain.incoming(plain.nodes<ParameterNode>().single { it.method.name == "of" }.id).none { it is DataFlowEdge })
        // The edges only: every node and every ordinal is the one the plain build has.
        assertEquals(plain.nodes<CallSiteNode>().map(::key).toSet(), linked.nodes<CallSiteNode>().map(::key).toSet())
    }

    private fun Graph.reachable(from: NodeId, hops: Int): Set<NodeId> {
        var frontier = setOf(from)
        val seen = mutableSetOf(from)
        repeat(hops) {
            frontier = frontier.flatMap { id -> outgoing(id).filterIsInstance<DataFlowEdge>().map { it.to } }.filter(seen::add).toSet()
        }
        return seen
    }

    @Test
    fun `a select rule with args folds the selected call whose argument is that constant and names the other`() {
        val classes = compile()
        val plain = load(classes)
        val gates = plain.sites("outer").filter { it.callee.name == "gate" }
        assertEquals(2, gates.size)
        val (graph, report) = fold(classes, select(gates, false, mapOf(0 to "new_checkout")))
        assertEquals(listOf(8), graph.work("outer"), "the new_checkout gate folds, the dark_mode gate stands")
        val reason = report.unsupported().single()
        assertTrue(
            reason.startsWith("argument 0 of sample.selection.Gate.gate(java.lang.String) is \"dark_mode\" here, not \"new_checkout\""),
            reason
        )
    }

    @Test
    fun `a selected call whose key is a parameter is not the key's to fold and says whose it is`() {
        val classes = compile()
        val plain = load(classes)
        val inner = plain.sites("gate").single { it.callee.name == "enabled" }
        val (graph, report) = fold(classes, select(listOf(inner), false, mapOf(0 to "new_checkout")))
        assertEquals(listOf(7, 8), graph.work("outer"), "a shared helper is nobody's to fold")
        assertEquals(1, graph.sites("gate").count { it.callee.name == "enabled" })
        val reason = report.unsupported().single()
        assertTrue(
            reason.startsWith(
                "argument 0 of sample.selection.Flags.enabled(java.lang.String) is not a constant here: " +
                    "it is parameter 0 of sample.selection.Gate.gate(java.lang.String); select the call that passes the constant"
            ),
            reason
        )
        // Without args the selection is an assumption about the call site, whoever calls gate():
        // the inner call folds, and the report says the fold reaches every caller of gate().
        val (bareGraph, bareReport) = fold(classes, select(listOf(inner), false, emptyMap()))
        assertEquals(0, bareGraph.sites("gate").count { it.callee.name == "enabled" }, "folded inside the helper")
        assertEquals(listOf(7, 8), bareGraph.work("outer"), "gate()'s callers still call it; its constant return is not propagated")
        val outcome = bareReport.outcomes.single()
        assertEquals(1, outcome.matched)
        assertEquals(
            listOf(
                "sample.selection.Flags.enabled(java.lang.String) folds for every caller of " +
                    "sample.selection.Gate.gate(java.lang.String): argument 0 is parameter 0 of " +
                    "sample.selection.Gate.gate(java.lang.String) (1 call(s))"
            ),
            outcome.hints
        )
    }

    @Test
    fun `a key cannot fold a gate fed by another call's result`() {
        val classes = compile()
        val plain = load(classes)
        // 1234 reaches id's return and so, through the shared ReturnNode, the result b = id(5678)
        // that feeds on(b): a query over interprocedural edges may select that call, the fold does not take it.
        val on = plain.sites("leaked").single { it.callee.name == "on" }
        val (graph, report) = fold(classes, select(listOf(on), false, mapOf(0 to 1234)))
        assertEquals(listOf(9), graph.work("leaked"), "on(b) stands, so work(9) stands; work(a) passes no constant")
        val reason = report.unsupported().single()
        assertTrue(
            reason.startsWith(
                "argument 0 of sample.selection.Gate.on(int) is not a constant here: " +
                    "it is the result of a call to sample.selection.Gate.id(int)"
            ),
            reason
        )
    }

    @Test
    fun `without args a selected call folds where every argument is a constant`() {
        val classes = compile()
        val plain = load(classes)
        val bare = plain.sites("bare").single { it.callee.name == "enabled" }
        val (graph, report) = fold(classes, select(listOf(bare), false, emptyMap()))
        assertEquals(emptyList(), graph.work("bare"))
        assertEquals(emptyList(), report.unsupported())
        assertEquals("select {MATCH (cs:CallSite) RETURN cs} 1 selected call site(s) = false", report.outcomes.single().fold.description)
        val keyed = select(listOf(bare), false, mapOf(0 to "z"))
        assertEquals("select {MATCH (cs:CallSite) RETURN cs} [0: \"z\"] 1 selected call site(s) = false", keyed.description)
    }

    @Test
    fun `array dimension overloads have distinct keys and only the selected caller folds`() {
        val classes = compile()
        val plain = load(classes)
        val runs = plain.nodes<CallSiteNode>().filter { it.caller.name == "run" && it.callee.name == "enabled" }.toList()
        assertEquals(2, runs.size)
        assertEquals(2, runs.map(::key).toSet().size)
        val selected = runs.single { it.caller.parameterTypes.single().className == "int[]" }
        val (graph, report) = fold(classes, select(listOf(selected), false, mapOf(0 to "k")))
        val constants: List<Int> = graph.nodes<CallSiteNode>().filter { it.caller.name == "run" }
            .flatMap { site -> site.arguments.mapNotNull { (graph.node(it) as? IntConstant)?.value } }.sorted().toList()
        assertEquals(listOf(12), constants, "only the selected int[] overload loses its guarded work call")
        val remaining = graph.nodes<CallSiteNode>().single { it.caller.name == "run" && it.callee.name == "enabled" }
        assertEquals("int[][]", remaining.caller.parameterTypes.single().className)
        assertEquals(emptyList(), report.unsupported())
    }

    @Test
    fun `an invoke that survives with a folded argument keeps the key it had`() {
        val classes = compile()
        val plain = load(classes)
        val before = plain.sites("consumed").filter { it.callee.name == "work" }.map(::key).sortedBy { it.ordinal }
        assertEquals(listOf(0, 1), before.map { it.ordinal })
        val rule = ConstantFold.parse(
            mapOf("match" to mapOf("CallSite" to mapOf("callee_name" to "variant")), "args" to mapOf(0 to "k"), "value" to 0)
        )
        val (graph, report) = fold(classes, rule)
        assertEquals(1, report.outcomes.single().matched)
        val after = graph.sites("consumed").filter { it.callee.name == "work" }
        assertEquals(
            before, after.map(::key).sortedBy { it.ordinal },
            "work(v) became work(0), a new statement with the ordinal of the old"
        )
        assertEquals(listOf(0, 0), after.flatMap { it.arguments }.mapNotNull { (graph.node(it) as? IntConstant)?.value })
        assertTrue(after.none { it.ordinal == null }, "a surviving call is never without its ordinal")
    }

    private fun selectAll(sites: List<CallSiteNode>, value: Any?, receiver: Map<Int, Any?> = emptyMap()): FoldSites = FoldSites(
        "MATCH (cs:CallSite) RETURN cs",
        sites.map(::key).toSet(),
        ConstantPattern.parse(value, "value"),
        receiverArguments = receiver.mapValues { (index, pattern) -> ConstantPattern.parse(pattern, "receiver_args.$index") }
    )

    @Test
    fun `a key in the receiver is held to the call that produced the receiver`() {
        val classes = compile()
        val plain = load(classes)
        val gates = plain.sites("twoKeys").filter { it.callee.name == "isOn" }
        assertEquals(2, gates.size)
        val (graph, report) = fold(classes, selectAll(gates, false, mapOf(0 to 1234)))
        assertEquals(listOf(14), graph.work("twoKeys"), "the 1234 gate folds, the 5678 gate stands")
        val reason = report.unsupported().single()
        assertTrue(
            reason.startsWith(
                "the receiver of sample.selection.Box.isOn() comes from sample.selection.Shapes.boxed(int), " +
                    "whose argument 0 is 5678 here, not 1234"
            ),
            reason
        )
        // Through a helper every box shares, the receiver is a parameter: with receiver_args the
        // selection is refused, and a bare selection folds there with a warning naming every caller.
        val inner = plain.sites("onBox").single { it.callee.name == "isOn" }
        val (refused, refusedReport) = fold(classes, selectAll(listOf(inner), false, mapOf(0 to 1234)))
        assertEquals(1, refused.sites("onBox").count { it.callee.name == "isOn" })
        assertTrue(
            refusedReport.unsupported().single().startsWith(
                "the receiver of sample.selection.Box.isOn() is parameter 0 of sample.selection.Shapes.onBox(sample.selection.Box)"
            ),
            refusedReport.unsupported().single()
        )
        val (shared, sharedReport) = fold(classes, selectAll(listOf(inner), false))
        assertEquals(0, shared.sites("onBox").count { it.callee.name == "isOn" })
        assertEquals(
            listOf(
                "sample.selection.Box.isOn() folds for every caller of sample.selection.Shapes.onBox(sample.selection.Box): " +
                    "the receiver is parameter 0 of sample.selection.Shapes.onBox(sample.selection.Box) (1 call(s))"
            ),
            sharedReport.outcomes.single().hints
        )
        // A static call has no receiver for receiver_args to match.
        val enabled = plain.sites("bare").single { it.callee.name == "enabled" }
        val (_, staticReport) = fold(classes, selectAll(listOf(enabled), false, mapOf(0 to "z")))
        assertEquals(
            "sample.selection.Flags.enabled(java.lang.String) is a static call: it has no receiver for 'receiver_args' to match",
            staticReport.unsupported().single()
        )
        // Without receiver_args, a bare selection is unconditional: both calls fold.
        val (both, _) = fold(classes, selectAll(gates, false))
        assertEquals(emptyList(), both.work("twoKeys"))
    }

    @Test
    fun `a call the clean-up rebuilt keeps its ordinal`() {
        val classes = compile()
        val plain = load(classes)
        val before = plain.sites("cleaned").single { it.callee.name == "variant" }
        val enabled = plain.sites("cleaned").single { it.callee.name == "enabled" }
        val (graph, report) = fold(classes, selectAll(listOf(enabled), false))
        assertEquals(1, report.outcomes.single().matched)
        assertEquals(emptyList(), graph.work("cleaned"), "the gated call is gone")
        val after = graph.sites("cleaned").single { it.callee.name == "variant" }
        assertEquals(key(before), key(after), "variant(\"k\") survives as a bare call, with the key it had")
    }

    @Test
    fun `a string constant used as a receiver stays in its local and the fold terminates`() {
        val classes = compile()
        val plain = load(classes)
        val name = plain.sites("lengthy").single { it.callee.name == "name" }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        val future = executor.submit<Pair<Graph, FoldReport>> { fold(classes, selectAll(listOf(name), "x")) }
        val (graph, report) = try {
            future.get(60, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
        assertEquals(1, report.outcomes.single().matched)
        val callees = graph.sites("lengthy").map { it.callee.name }.sorted()
        assertEquals(listOf("length", "work"), callees, "s.length() keeps its receiver local, work(...) stays")
    }

    @Test
    fun `statements are counted once per method, whatever the number of rules that folded there`() {
        val classes = compile()
        fun rule(name: String) =
            ConstantFold(mapOf("callee_name" to name, "caller_name" to "twoRules"), value = ConstantPattern.parse(false, "value"))
        var report: FoldReport? = null
        val graph = load(classes, listOf(rule("enabled"), rule("other"))) { report = it }
        assertEquals(emptyList(), graph.work("twoRules"))
        val folded = assertNotNull(report)
        assertEquals(listOf(1, 1), folded.outcomes.map { it.matched })
        val method = folded.methods.single()
        assertEquals("$packageName.Shapes.twoRules()", method.method)
        assertTrue(method.statementsRemoved > 0)
        assertEquals(method.statementsRemoved, folded.statementsRemoved)
    }

    @Test
    fun `two methods one return type apart are two callers`() {
        val classes = compile()
        val sites = load(classes).sites("same").filter { it.callee.name == "enabled" }
        assertEquals(2, sites.size)
        assertEquals(1, sites.map { it.caller.signature to it.ordinal }.distinct().size, "the signature and ordinal alone collide")
        assertNotEquals(key(sites[0]), key(sites[1]))
        val stringly = sites.single { it.caller.descriptor == "()Ljava/lang/String;" }

        val (graph, report) = fold(classes, select(stringly, false))
        assertEquals(1, report.outcomes.single().matched, report.toString())
        assertEquals(listOf(7), graph.work("same"), "only the String method's gate folds")
        val remaining = graph.sites("same").filter { it.callee.name == "enabled" }.map { it.caller.descriptor }
        assertEquals(listOf("()Ljava/lang/Object;"), remaining)
        assertEquals(1, report.methods.size)
    }
}
