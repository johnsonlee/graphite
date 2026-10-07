package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.ConstantFold
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.constant.IntConstant
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

class KotlinEnumFoldTest {
    private val prefix = "sample.kotlinlambda."
    private val owner = prefix + "KotlinEnumFoldShapesKt"
    private val classes = Path.of(requireNotNull(System.getProperty("kotlin.lambda.indy.fixtures")))

    private fun rule(lookup: String, enum: String, value: String): ConstantFold = ConstantFold.parse(mapOf(
        "match" to mapOf("CallSite" to mapOf("callee_class" to owner, "callee_name" to lookup)),
        "value" to mapOf("EnumConstant" to mapOf("enum_type" to prefix + enum, "name" to value))
    ))

    private fun view(vararg rules: ConstantFold): JavaView = JavaView(PathBasedAnalysisInputLocation.create(
        classes, SourceType.Application, ConstantFolding(rules.toList()).bodyInterceptors(emptyList())
    ))

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

    private fun assertScopeGuardsWork(body: Body) {
        assertEquals(listOf("enumInScope", "enumTail", "enumWork"), body.calls())
        val scope = body.stmts.filterIsInstance<JAssignStmt>().single { it.callee() == "enumInScope" }
        val branch = body.stmts.filterIsInstance<JIfStmt>().single()
        assertEquals<Value>(scope.leftOp, branch.condition.op1, body.toString())
        assertEquals(IntConstant.getInstance(0), branch.condition.op2, body.toString())
        assertTrue(branch.condition is JEqExpr || branch.condition is JNeExpr, body.toString())
        val target = body.controlFlowGraph.getBranchTargetsOf(branch).single()
        val fallthrough = body.controlFlowGraph.successors(branch).single { it !== target }
        val work = body.stmts.single { it.callee() == "enumWork" }
        val whenFalse = if (branch.condition is JEqExpr) target else fallthrough
        val whenTrue = if (branch.condition is JEqExpr) fallthrough else target
        assertFalse(body.reaches(whenFalse, work), "out of scope bypasses work: $body")
        assertTrue(body.reaches(whenTrue, work), "in scope reaches work: $body")
    }

    @Test
    fun `Kotlin generated entries factory is safe while custom initialization remains unsafe`() {
        val view = view()
        val safety = EnumInitializationSafety()
        val ordinary = view.identifierFactory.getClassType(prefix + "KotlinFoldOption")
        val initializer = view.getClass(ordinary).get().methods.single { it.name == "<clinit>" }.body
        assertTrue(initializer.stmts.any { it.callee() == "enumEntries" }, "fixture exercises modern Kotlin enum initialization")
        assertTrue(safety.isNonReentrant(view, ordinary))
        assertFalse(safety.isNonReentrant(view, view.identifierFactory.getClassType(prefix + "KotlinUnsafeFoldOption")))
    }

    @Test
    fun `Kotlin equality inequality and equals fold the option while preserving runtime scope`() {
        for (option in listOf("A", "B")) {
            val view = view(rule("enumOption", "KotlinFoldOption", option))
            val bodies = view.getClass(view.identifierFactory.getClassType(owner)).get().methods
                .filter { it.name in setOf("enumEqual", "enumNotEqual", "enumEqualsA", "enumEqualsB") }
                .associate { it.name to it.body }
            val kept = if (option == "A") listOf("enumEqual", "enumEqualsA") else listOf("enumNotEqual", "enumEqualsB")
            for ((name, body) in bodies) {
                if (name in kept) {
                    assertScopeGuardsWork(body)
                } else {
                    assertEquals(listOf("enumInScope", "enumTail"), body.calls(), "$name with $option still evaluates scope")
                }
            }
            assertEquals(setOf("enumEqual", "enumNotEqual", "enumEqualsA", "enumEqualsB"), bodies.keys)
        }
    }

    @Test
    fun `Kotlin enum with an initializer callback retains runtime equals after its lookup folds`() {
        val view = view(rule("unsafeEnumOption", "KotlinUnsafeFoldOption", "A"))
        val body = view.getClass(view.identifierFactory.getClassType(owner)).get().methods.single { it.name == "unsafeEnumEquals" }.body
        assertEquals(listOf("enumTail", "enumWork", "equals"), body.calls())
        assertEquals(1, body.stmts.filterIsInstance<JIfStmt>().size, body.toString())
    }
}
