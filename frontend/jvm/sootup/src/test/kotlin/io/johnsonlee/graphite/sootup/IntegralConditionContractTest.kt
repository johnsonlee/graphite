package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.ConstantPattern
import java.nio.file.Files
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import sootup.core.jimple.common.Immediate
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.constant.LongConstant
import sootup.core.jimple.common.expr.AbstractConditionExpr
import sootup.core.jimple.common.expr.JAddExpr
import sootup.core.jimple.common.expr.JEqExpr
import sootup.core.jimple.common.expr.JGeExpr
import sootup.core.jimple.common.expr.JGtExpr
import sootup.core.jimple.common.expr.JLeExpr
import sootup.core.jimple.common.expr.JLtExpr
import sootup.core.jimple.common.expr.JNeExpr
import sootup.core.jimple.common.stmt.JIfStmt
import sootup.core.jimple.common.stmt.JReturnStmt
import sootup.core.model.Body
import sootup.core.model.SourceType
import sootup.java.core.views.JavaView

class IntegralConditionContractTest {
    @Test
    fun `all integral operators preserve exact mixed width truth and the chosen CFG successor`() {
        val root = Files.createTempDirectory("integral-condition-contract")
        try {
            val source = root.resolve("IntegralBranch.java")
            Files.writeString(source, """
                public class IntegralBranch {
                    static int value() { return 7; }
                    static int branch(int key) { if (value() > key) return 1; else return 2; }
                }
            """.trimIndent())
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", root.toString(), source.toString()))
            val view = JavaView(ParsedClassLocation(root, SourceType.Application, emptyList()))
            val method = view.getClass(view.identifierFactory.getClassType("IntegralBranch")).get().methods.single { it.name == "branch" }
            for ((left, right, sign) in operands()) {
                val expected = when (sign) {
                    -1 -> listOf(false, true, true, true, false, false)
                    0 -> listOf(true, false, false, true, false, true)
                    else -> listOf(false, true, false, false, true, true)
                }
                conditions(left, right).forEachIndexed { index, (condition, operator) ->
                    assertEquals(operator, comparisonOp(condition))
                    val builder = Body.builder(method.body, method.modifiers)
                    val graph = builder.controlFlowGraph
                    val original = graph.nodes.filterIsInstance<JIfStmt>().single()
                    val branch = original.withCondition(condition)
                    graph.replaceNode(original, branch)
                    val kept = graph.successors(branch)[if (expected[index]) 1 else 0] as JReturnStmt
                    val folding = ConstantFolding(listOf(ConstantFold(mapOf("callee_name" to "value"),
                        value = ConstantPattern.parse(42, "value"))))
                    folding.bodyInterceptors(emptyList()).forEach { it.interceptBody(builder, view) }
                    assertEquals(1, folding.report().outcomes.single().matched)
                    assertEquals(emptyList(), graph.nodes.filterIsInstance<JIfStmt>(), "$condition must be resolved")
                    assertEquals(listOf(kept.op), graph.nodes.filterIsInstance<JReturnStmt>().map { it.op }, "$condition")
                    assertEquals(listOf(kept.op), builder.build().stmts.filterIsInstance<JReturnStmt>().map { it.op })
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `non condition expressions remain unknown rather than becoming a comparison`() {
        val one = IntConstant.getInstance(1)
        assertNull(comparisonOp(one))
        assertNull(comparisonOp(JAddExpr(one, IntConstant.getInstance(2))))
    }

    private fun operands(): List<Triple<Immediate, Immediate, Int>> = listOf(
        Triple(IntConstant.getInstance(-1), LongConstant.getInstance(0), -1),
        Triple(LongConstant.getInstance(0), IntConstant.getInstance(0), 0),
        Triple(LongConstant.getInstance(9_007_199_254_740_993), LongConstant.getInstance(9_007_199_254_740_992), 1),
        Triple(LongConstant.getInstance(Long.MAX_VALUE), IntConstant.getInstance(Int.MAX_VALUE), 1),
        Triple(LongConstant.getInstance(Long.MIN_VALUE), IntConstant.getInstance(Int.MIN_VALUE), -1),
        Triple(IntConstant.getInstance(Int.MAX_VALUE), LongConstant.getInstance(Long.MAX_VALUE), -1),
        Triple(IntConstant.getInstance(Int.MIN_VALUE), LongConstant.getInstance(Long.MIN_VALUE), 1)
    )

    private fun conditions(left: Immediate, right: Immediate): List<Pair<AbstractConditionExpr, ComparisonOp>> = listOf(
        JEqExpr(left, right) to ComparisonOp.EQ, JNeExpr(left, right) to ComparisonOp.NE,
        JLtExpr(left, right) to ComparisonOp.LT, JLeExpr(left, right) to ComparisonOp.LE,
        JGtExpr(left, right) to ComparisonOp.GT, JGeExpr(left, right) to ComparisonOp.GE
    )
}
