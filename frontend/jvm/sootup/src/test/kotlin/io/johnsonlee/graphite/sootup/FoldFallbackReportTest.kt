package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.CallSiteKey
import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.ConstantPattern
import io.johnsonlee.graphite.input.FoldSites
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import javax.tools.ToolProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import sootup.core.jimple.common.Immediate
import sootup.core.jimple.common.constant.NullConstant
import sootup.core.jimple.common.expr.JLtExpr
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JIfStmt
import sootup.core.jimple.common.stmt.JReturnStmt
import sootup.core.model.Body
import sootup.core.model.SootClass
import sootup.core.model.SourceType
import sootup.core.types.ClassType
import sootup.core.views.View
import sootup.java.core.views.JavaView

class FoldFallbackReportTest {
    private val roots = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        roots.forEach { it.toFile().deleteRecursively() }
    }

    private fun fixture(name: String = "run"): Pair<Body.BodyBuilder, JavaView> {
        val root = Files.createTempDirectory("fold-fallback-report").also(roots::add)
        val source = root.resolve("Fallback.java")
        Files.writeString(source, """
            public class Fallback {
                static int value() { return 7; }
                static int other() { return 9; }
                static int run() { return value() + other(); }
                static int from(int key) { return key; }
                static int shared(int key) { return from(key); }
                static int branch(int key) { if (value() > key) return 1; else return 2; }
            }
        """.trimIndent())
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", root.toString(), source.toString()))
        val view = JavaView(ParsedClassLocation(root, SourceType.Application, emptyList()))
        val method = view.getClass(view.identifierFactory.getClassType("Fallback")).get().methods.single { it.name == name }
        return Body.builder(method.body, method.modifiers) to view
    }

    private fun selected(ordinal: Int = 0): FoldSites = FoldSites(
        "MATCH (cs:CallSite) RETURN cs",
        setOf(CallSiteKey("Fallback.run()", "()I", "Fallback.value()", "()I", ordinal)),
        ConstantPattern.parse(42, "value")
    )

    @Test
    fun `failure before folding reports present selection as unsupported and absent selection as unmatched`() {
        val (retained, view) = fixture()
        val original = retained.build().toString()
        val present = selected()
        val absent = selected(1)
        val folding = ConstantFolding(listOf(present, absent))
        folding.discard(retained, retained, view, "LocalSplitter failed: deliberate failure")

        val report = folding.report()
        val outcome = report.outcomes.first()
        assertEquals(0, outcome.matched)
        assertEquals(1, outcome.unsupported.size)
        assertEquals("Fallback.run()", outcome.unsupported.single().method)
        assertTrue(outcome.unsupported.single().reason.contains("LocalSplitter failed: deliberate failure"))
        assertFalse(outcome.hints.any { it.contains("not in this build") })
        assertEquals(listOf(absent), report.unmatched, "strict unmatched handling distinguishes an unsupported request from an absent call")
        assertTrue(report.outcomes.last().unsupported.isEmpty())
        assertTrue(report.outcomes.last().hints.single().contains("not in this build"))
        assertEquals(original, retained.build().toString(), "reporting preserves both original calls")
        assertTrue(report.methods.isEmpty())
        assertNull(folding.ordinalsBeforeFolding(retained.methodSignature))
    }

    @Test
    fun `failure after accounting removes applied folds and a later successful resolution clears refusal`() {
        val (retained, view) = fixture()
        val original = retained.build().toString()
        val candidate = Body.builder(retained.build(), retained.modifiers)
        val folding = ConstantFolding(listOf(selected()))
        val chain = folding.bodyInterceptors(emptyList())
        chain.forEach { it.interceptBody(candidate, view) }
        assertEquals(1, folding.report().outcomes.single().matched)
        assertEquals(1, folding.report().methods.size)
        assertNotNull(folding.ordinalsBeforeFolding(candidate.methodSignature))

        folding.discard(candidate, retained, view, "final validation failed")
        val report = folding.report()
        assertEquals(0, report.outcomes.single().matched)
        assertTrue(report.methods.isEmpty())
        assertEquals(0, report.statementsRemoved)
        assertEquals(1, report.outcomes.single().unsupported.size)
        assertTrue(report.outcomes.single().unsupported.single().reason.contains("final validation failed"))
        assertTrue(report.outcomes.single().hints.isEmpty())
        assertTrue(report.unmatched.isEmpty())
        assertNull(folding.ordinalsBeforeFolding(candidate.methodSignature))
        assertEquals(original, retained.build().toString())

        chain.forEach { it.interceptBody(retained, view) }
        assertEquals(1, folding.report().outcomes.single().matched)
        assertTrue(folding.report().outcomes.single().unsupported.isEmpty())
        assertTrue(folding.report().outcomes.single().hints.isEmpty())
    }

    @Test
    fun `fallback reports only pattern matched calls as unsupported`() {
        val (retained, view) = fixture()
        val rule = ConstantFold.parse(mapOf(
            "match" to mapOf("CallSite" to mapOf("callee_class" to "Fallback", "callee_name" to "value")),
            "value" to 42
        ))
        val folding = ConstantFolding(listOf(rule))
        folding.discard(retained, retained, view, "default pass failed")
        val outcome = folding.report().outcomes.single()
        assertEquals(0, outcome.matched)
        assertEquals(1, outcome.unsupported.size, "other() must not be reported as a requested fold")
        assertTrue(outcome.unsupported.single().reason.contains("default pass failed"))
    }

    @Test
    fun `a retained shared selection does not claim to fold for every caller`() {
        val (retained, view) = fixture("shared")
        val rule = FoldSites(
            "MATCH (cs:CallSite) RETURN cs",
            setOf(CallSiteKey("Fallback.shared(int)", "(I)I", "Fallback.from(int)", "(I)I", 0)),
            ConstantPattern.parse(42, "value")
        )
        val folding = ConstantFolding(listOf(rule))
        folding.discard(retained, retained, view, "cleanup failed")
        val outcome = folding.report().outcomes.single()
        assertEquals(1, outcome.unsupported.size)
        assertEquals(0, outcome.matched)
        assertTrue(outcome.hints.isEmpty(), "no success-oriented shared-selection hint survives a refusal")
    }

    @Test
    fun `reporting an eligibility failure does not repeat the exception that caused recovery`() {
        val (retained, view) = fixture()
        val original = retained.build().toString()
        val brokenView = object : View by view {
            override fun getClass(type: ClassType): Optional<SootClass> = error("class resolution failed")
        }
        val matching = ConstantFold(mapOf("callee_name" to "value"), value = ConstantPattern.parse(42, "value"))
        val unrelated = ConstantFold(mapOf("callee_name" to "absent"), value = ConstantPattern.parse(42, "value"))
        val folding = ConstantFolding(listOf(selected(), matching, unrelated))
        folding.discard(retained, retained, brokenView, "Fold failed")
        val outcomes = folding.report().outcomes
        val outcome = outcomes.first()
        assertEquals(1, outcomes[1].unsupported.size, "matching pattern rule also retains the failed call")
        assertTrue(outcomes[2].unsupported.isEmpty(), "unrelated pattern does not acquire another rule's failure")
        assertEquals(1, outcome.unsupported.size)
        assertTrue(outcome.unsupported.single().reason.contains("fold eligibility could not be checked"))
        assertTrue(outcome.unsupported.single().reason.contains("class resolution failed"))
        assertEquals(0, outcome.matched)
        assertTrue(outcome.hints.isEmpty())
        assertEquals(original, retained.build().toString())
    }

    @Test
    fun `module-qualified methods colliding in a persisted selection key are refused`() {
        val (builder, view) = fixture()
        val original = builder.build().toString()
        val owner = view.getClass(builder.methodSignature.declClassType).get()
        val method = owner.methods.single { it.name == "run" }
        val moduleType = sootup.java.core.JavaModuleIdentifierFactory.getInstance().getClassType("Fallback", "", "alternate.module")
        val signature = sootup.core.signatures.MethodSignature(moduleType, method.subSignature)
        val alias = sootup.java.core.JavaSootMethod(
            method.bodySource, signature, method.modifiers, method.exceptionSignatures, method.position)
        val colliding = owner.withMethods(owner.methods + alias)
        val ambiguousView = object : View by view {
            override fun getClass(type: ClassType): Optional<SootClass> = Optional.of(colliding)
        }
        val folding = ConstantFolding(listOf(selected()))
        val chain = folding.bodyInterceptors(emptyList())
        assertEquals(listOf("Gated(FoldBranches)", "Gated(DeadAssignmentEliminator)", "Gated(NopEliminator)"),
            chain.map { it.toString() }.filter { it.startsWith("Gated(") }, "recovery diagnostics identify the exact cleanup stage")
        chain.forEach { it.interceptBody(builder, ambiguousView) }
        val outcome = folding.report().outcomes.single()
        assertEquals(0, outcome.matched)
        assertTrue(outcome.unsupported.single().reason.contains("names 2 methods with the same rendered identity"), outcome.toString())
        assertEquals(original, builder.build().toString(), "ambiguous persisted identity must not select either source method")
    }

    @Test
    fun `unknown immediate arguments retain the call and report unresolved matching`() {
        val (builder, view) = fixture("shared")
        val call = builder.controlFlowGraph.nodes.filterIsInstance<JAssignStmt>()
            .single { it.rightOp is JStaticInvokeExpr }
        val invoke = call.rightOp as JStaticInvokeExpr
        val opaque = object : Immediate by invoke.args.single() {}
        builder.controlFlowGraph.replaceNode(call, call.withRValue(invoke.withArgs(listOf(opaque))))
        val original = builder.build().toString()
        val rule = ConstantFold(mapOf("callee_name" to "from"),
            arguments = mapOf(0 to ConstantPattern.parse(7, "args.0")), value = ConstantPattern.parse(42, "value"))
        val folding = ConstantFolding(listOf(rule))
        folding.bodyInterceptors(emptyList()).forEach { it.interceptBody(builder, view) }
        assertEquals(original, builder.build().toString(), "unrecognized provider values cannot establish a constant argument")
        val outcome = folding.report().outcomes.single()
        assertEquals(0, outcome.matched)
        assertTrue(outcome.unsupported.single().reason.contains("argument 0 of Fallback.from(int) is not a constant"))
    }

    @Test
    fun `reference ordering is retained instead of inventing constant truth`() {
        val (builder, view) = fixture("branch")
        val graph = builder.controlFlowGraph
        val branch = graph.nodes.filterIsInstance<JIfStmt>().single()
        val nullOrder = branch.withCondition(JLtExpr(NullConstant.getInstance(), NullConstant.getInstance()))
        graph.replaceNode(branch, nullOrder)
        val successors = graph.successors(nullOrder).toList()
        val folding = ConstantFolding(listOf(ConstantFold(mapOf("callee_name" to "value"),
            value = ConstantPattern.parse(42, "value"))))
        folding.bodyInterceptors(emptyList()).forEach { it.interceptBody(builder, view) }
        assertEquals(1, folding.report().outcomes.single().matched, "the independent selected call still folds")
        assertEquals(listOf(nullOrder), graph.nodes.filterIsInstance<JIfStmt>(), "meaningless reference ordering remains conditional")
        assertEquals(successors, graph.successors(nullOrder), "neither branch may be discarded")
        assertEquals(setOf("1", "2"), graph.nodes.filterIsInstance<JReturnStmt>().map { it.op.toString() }.toSet())
    }
}
