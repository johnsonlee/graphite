package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.ConstantFold
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.expr.JEqExpr
import sootup.core.jimple.common.expr.JNeExpr
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JIfStmt
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.model.Body
import sootup.core.model.SourceType
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation
import sootup.java.core.views.JavaView

/** Enum equality must simplify only the experiment test, leaving its independent scope guard. */
class EnumEqualsFoldTest {
    private val temporaryRoots = mutableListOf<Path>()

    @AfterTest
    fun deleteCompiledFixtures() {
        temporaryRoots.forEach { it.toFile().deleteRecursively() }
    }

    private fun compile(): Path {
        val root = Files.createTempDirectory("enum-equals-fold").also(temporaryRoots::add)
        val source = root.resolve("Shapes.java")
        Files.writeString(source, """
            package sample.enumequals;
            public class Shapes {
                public enum Option { A, B }
                public enum Overloaded {
                    A, B;
                    public boolean equals(Overloaded other) { work(); return true; }
                }
                public static class Custom {
                    public boolean equals(Object other) { work(); return true; }
                }
                static Option option() { return Option.B; }
                static boolean inScope() { return System.nanoTime() > 0; }
                static void work() { }
                static void other() { }
                static void tail() { }
                public static void groupA() { if (inScope() && Option.A.equals(option())) work(); tail(); }
                public static void groupB() { if (inScope() && Option.B.equals(option())) work(); tail(); }
                public static boolean isGroupA() { return inScope() && Option.A.equals(option()); }
                public static boolean isGroupB() { return inScope() && Option.B.equals(option()); }
                public static void receiver() { if (option().equals(Option.A)) work(); else other(); tail(); }
                public static void objectReceiver() {
                    Object value = option(); if (value.equals(Option.A)) work(); else other(); tail();
                }
                public static void discarded() { option().equals(Option.A); tail(); }
                public static void equalityBeforeThrowingCall() {
                    try { option().equals(Option.A); other(); }
                    catch (RuntimeException expected) { work(); }
                }
                public static void overloadedEquals() {
                    option(); if (Overloaded.A.equals(Overloaded.B)) work(); else other();
                }
                public static void nullArgument() { if (option().equals(null)) work(); else other(); }
                public static void nullReceiver() {
                    Option value = null;
                    try { if (value.equals(option())) work(); }
                    catch (NullPointerException expected) { other(); }
                }
                public static void unknownReceiver(Object value) { if (value.equals(option())) work(); else other(); }
                public static void unknownArgument(Object value) { if (option().equals(value)) work(); else other(); }
                public static void customEquals(Custom value) { if (value.equals(option())) work(); else other(); }
            }
        """.trimIndent())
        val classes = root.resolve("classes").also { Files.createDirectories(it) }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString()))
        return classes
    }

    private fun bodies(classes: Path, option: String?): Map<String, Body> {
        val rule = ConstantFold.parse(mapOf(
            "match" to mapOf("CallSite" to mapOf("callee_class" to "sample.enumequals.Shapes", "callee_name" to "option")),
            "value" to option?.let {
                mapOf("EnumConstant" to mapOf("enum_type" to "sample.enumequals.Shapes\$Option", "name" to it))
            }
        ))
        val chain = ConstantFolding(listOf(rule)).bodyInterceptors(emptyList())
        val view = JavaView(PathBasedAnalysisInputLocation.create(classes, SourceType.Application, chain))
        return view.getClass(view.identifierFactory.getClassType("sample.enumequals.Shapes")).get().methods
            .filter { it.name != "<init>" }.associate { it.name to it.body }
    }

    private fun Stmt.callee(): String? = when (this) {
        is JAssignStmt -> invokeExpr.orElse(null)?.methodSignature?.name
        is JInvokeStmt -> invokeExpr.orElse(null)?.methodSignature?.name
        else -> null
    }

    private fun Body.calls(): List<String> = stmts.mapNotNull { it.callee() }.sorted()

    private fun Body.reaches(start: Stmt, target: Stmt): Boolean {
        val pending = ArrayDeque<Stmt>()
        val visited = HashSet<Stmt>()
        pending.add(start)
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (next === target) return true
            if (visited.add(next)) pending.addAll(controlFlowGraph.successors(next))
        }
        return false
    }

    private fun assertScopeStillGuardsWork(body: Body) {
        assertEquals(listOf("inScope", "tail", "work"), body.calls())
        val scope = body.stmts.filterIsInstance<JAssignStmt>().single { it.callee() == "inScope" }
        val branch = body.stmts.filterIsInstance<JIfStmt>().single()
        assertEquals<Value>(scope.leftOp, branch.condition.op1, body.toString())
        assertEquals(IntConstant.getInstance(0), branch.condition.op2, body.toString())
        val target = body.controlFlowGraph.getBranchTargetsOf(branch).single()
        val fallthrough = body.controlFlowGraph.successors(branch).single { it !== target }
        val work = body.stmts.single { it.callee() == "work" }
        assertTrue(branch.condition is JEqExpr || branch.condition is JNeExpr, body.toString())
        val whenFalse = if (branch.condition is JEqExpr) target else fallthrough
        val whenTrue = if (branch.condition is JEqExpr) fallthrough else target
        assertFalse(body.reaches(whenFalse, work), "out of scope must bypass work: $body")
        assertTrue(body.reaches(whenTrue, work), "in scope must reach work: $body")
    }

    @Test
    fun `control and treatment equality retain the independent scope condition`() {
        val classes = compile()
        for ((option, kept, removed) in listOf(Triple("A", "groupA", "groupB"), Triple("B", "groupB", "groupA"))) {
            val bodies = bodies(classes, option)
            assertScopeStillGuardsWork(bodies.getValue(kept))
            assertEquals(listOf("inScope", "tail"), bodies.getValue(removed).calls(), "scope evaluation still happens before a false RHS")
            val predicate = bodies.getValue(if (option == "A") "isGroupA" else "isGroupB")
            assertEquals(listOf("inScope"), predicate.calls())
            assertEquals(1, predicate.stmts.filterIsInstance<JIfStmt>().size, "the matching group is still conditional: $predicate")
        }
    }

    @Test
    fun `known enum receivers fold equality in either direction including Object receivers`() {
        val classes = compile()
        for ((option, kept) in listOf("A" to "work", "B" to "other")) {
            val bodies = bodies(classes, option)
            for (method in listOf("receiver", "objectReceiver")) {
                assertEquals(listOf(kept, "tail").sorted(), bodies.getValue(method).calls(), "$method with $option")
                assertTrue(bodies.getValue(method).stmts.none { it is JIfStmt }, "$method has no remaining condition")
            }
            assertEquals(listOf("other"), bodies.getValue("nullArgument").calls())
            assertEquals(listOf("tail"), bodies.getValue("discarded").calls(), "discarding enum equality keeps no call")
            val withHandler = bodies.getValue("equalityBeforeThrowingCall")
            assertEquals(listOf("other", "work"), withHandler.calls(), "only the constant equality is removed")
            val throwingCall = withHandler.stmts.single { it.callee() == "other" }
            val handlerWork = withHandler.stmts.single { it.callee() == "work" }
            val handlers = withHandler.controlFlowGraph.exceptionalSuccessors(throwingCall).values
            assertTrue(handlers.any { withHandler.reaches(it, handlerWork) }, "the remaining call still reaches its handler")
        }
    }

    @Test
    fun `a folded null argument simplifies equality only with a nonnull receiver`() {
        val bodies = bodies(compile(), null)
        for (method in listOf("groupA", "groupB")) {
            assertEquals(listOf("inScope", "tail"), bodies.getValue(method).calls(), method)
        }
        assertEquals(listOf("equals", "other", "tail", "work"), bodies.getValue("receiver").calls())
        assertEquals(listOf("equals", "tail"), bodies.getValue("discarded").calls(), "discarded null receiver still throws")
    }

    @Test
    fun `null unknown and custom receivers do not inherit enum equality semantics`() {
        val classes = compile()
        val plainView = JavaView(PathBasedAnalysisInputLocation.create(classes, SourceType.Application))
        val plainBody = plainView.getClass(plainView.identifierFactory.getClassType("sample.enumequals.Shapes")).get()
            .methods.single { it.name == "nullReceiver" }.body
        val plainEquals = plainBody.stmts.single { it.callee() == "equals" }
        assertTrue(
            plainBody.controlFlowGraph.exceptionalSuccessors(plainEquals).isNotEmpty(),
            "the original null equality has an exception handler"
        )
        val bodies = bodies(classes, "A")
        for (method in listOf("nullReceiver", "unknownReceiver", "unknownArgument", "customEquals", "overloadedEquals")) {
            val body = bodies.getValue(method)
            assertEquals(listOf("equals", "other", "work"), body.calls(), method)
            assertTrue(body.stmts.any { it is JIfStmt }, "$method retains its runtime test")
        }
        val nullBody = bodies.getValue("nullReceiver")
        val equality = nullBody.stmts.single { it.callee() == "equals" }
        assertTrue(
            nullBody.controlFlowGraph.exceptionalSuccessors(equality).isNotEmpty(),
            "null receiver must still reach its exception handler"
        )
    }
}
