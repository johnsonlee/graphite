package io.johnsonlee.graphite.sootup

import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import sootup.core.interceptor.BodyInterceptor
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.model.Body
import sootup.core.model.SourceType
import sootup.core.views.View
import sootup.interceptors.BytecodeBodyInterceptors
import sootup.interceptors.ConditionalBranchFolder
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation
import sootup.java.core.views.JavaView

/**
 * Why `ConstantFolding.FoldBranches` resolves constant branches itself instead of running
 * SootUp's `ConditionalBranchFolder`. On 2.0.0 the folder kept the side a constant condition
 * rules out. 3.0.1 fixed that sign, but its pruning still removes the join point behind the
 * dropped side whenever the `if` was that statement's only other predecessor: for
 * `if (gate) work(); tail();` it drops `tail()` and `return` along with `work()`, and the
 * body fails SootUp's own validation. The shapes without a join point fold correctly on 3.0.1.
 * If the last test starts failing after a SootUp upgrade, the folder was fixed and
 * `FoldBranches` can hand the `if` to it.
 */
class ConditionalBranchFolderTest {

    private val temporaryRoots = mutableListOf<Path>()

    @AfterTest
    fun deleteCompiledFixtures() {
        temporaryRoots.forEach { it.toFile().deleteRecursively() }
    }

    private val shapes = """
        package sample.cbf;
        public class Shapes {
            static void work() { }
            static void tail() { }
            public void callBody() { if (Flags.enabled("k")) { work(); } tail(); }
            public void guardReturn() { if (!Flags.enabled("k")) { return; } work(); work(); }
            public int earlyReturnConstant() { if (Flags.enabled("k")) { return 1; } return 2; }
        }
    """.trimIndent()
    private val flags = "package sample.cbf; public class Flags { public static boolean enabled(String k) { return k.length() > 3; } }"

    /** Fold every `Flags.enabled(..)` to [value] and substitute it into the reads of its local, so the `if` compares two constants. */
    private class Fold(private val value: Int) : BodyInterceptor {
        override fun interceptBody(builder: Body.BodyBuilder, view: View) {
            val gates = builder.controlFlowGraph.nodes.filterIsInstance<JAssignStmt>()
                .filter { it.invokeExpr.map { invoke -> invoke.methodSignature.name == "enabled" }.orElse(false) }
            for (assign in gates) {
                val constant = IntConstant.getInstance(value)
                val replacement = assign.withRValue(constant)
                builder.controlFlowGraph.replaceNode(assign, replacement)
                val local = assign.leftOp as Local
                for (use in builder.controlFlowGraph.nodes.toList()) {
                    if (use !== replacement && use.uses.any { it == local }) {
                        builder.controlFlowGraph.replaceNode(use, use.withNewUse(local, constant))
                    }
                }
            }
        }
    }

    private fun compile(): Path {
        val root = Files.createTempDirectory("conditional-branch-folder").also(temporaryRoots::add)
        val sourceDir = root.resolve("src/sample/cbf").also { Files.createDirectories(it) }
        val classes = root.resolve("classes").also { Files.createDirectories(it) }
        val files = listOf("Shapes.java" to shapes, "Flags.java" to flags)
            .map { (name, text) -> sourceDir.resolve(name).also { Files.writeString(it, text) }.toString() }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), *files.toTypedArray()))
        return classes
    }

    /** The statements of [method] after the default chain, the fold and [extra], as Jimple lines. */
    private fun statements(classes: Path, value: Int, method: String, vararg extra: BodyInterceptor): List<String> {
        val chain = BytecodeBodyInterceptors.Default.bodyInterceptors + Fold(value) + extra.toList()
        val view = JavaView(PathBasedAnalysisInputLocation.create(classes, SourceType.Application, chain))
        val type = view.identifierFactory.getClassType("sample.cbf.Shapes")
        return view.getClass(type).get().methods.single { it.name == method }.body.stmts.map(Stmt::toString)
    }

    @Test
    fun `the fold alone leaves an if on two constants`() {
        val classes = compile()
        val callBody = statements(classes, 0, "callBody")
        assertTrue(callBody.any { it == "if 0 == 0" }, callBody.toString())
        val guardReturn = statements(classes, 1, "guardReturn")
        assertTrue(guardReturn.any { it == "if 1 != 0" }, guardReturn.toString())
    }

    @Test
    fun `ConditionalBranchFolder keeps the side a constant condition takes where there is no join point`() {
        val classes = compile()
        val folder = ConditionalBranchFolder()
        // guardReturn: `if (!enabled) return; work(); work();`.
        val guardOff = statements(classes, 0, "guardReturn", folder)
        assertEquals(0, guardOff.count { "work()" in it }, "the gated code is gone behind a false gate: $guardOff")
        val guardOn = statements(classes, 1, "guardReturn", folder)
        assertEquals(2, guardOn.count { "work()" in it }, "the gated code stays behind a true gate: $guardOn")
        // earlyReturnConstant: `if (enabled) return 1; return 2;`.
        val earlyOff = statements(classes, 0, "earlyReturnConstant", folder)
        assertTrue("return 2" in earlyOff && "return 1" !in earlyOff, earlyOff.toString())
        val earlyOn = statements(classes, 1, "earlyReturnConstant", folder)
        assertTrue("return 1" in earlyOn && "return 2" !in earlyOn, earlyOn.toString())
    }

    @Test
    fun `ConditionalBranchFolder prunes the join point behind the dropped side and fails validation`() {
        val classes = compile()
        // callBody: `if (enabled) work(); tail();`. tail() is reached from the if and from work(); once the
        // if is gone it has one predecessor left, which the folder reads as "only reachable through the
        // dropped side" and removes, along with the return behind it.
        for (value in listOf(0, 1)) {
            val failure = assertFailsWith<IllegalStateException>("gate = $value") {
                statements(classes, value, "callBody", ConditionalBranchFolder())
            }
            assertTrue(failure.message!!.contains("Failed to apply sootup.interceptors.ConditionalBranchFolder"), failure.message)
            val root = generateSequence<Throwable>(failure) { it.cause }.last()
            assertTrue(root.message!!.contains("must have '1' outgoing flow but has '0'"), root.message)
        }
    }
}
