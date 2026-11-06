package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.ConstantPattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import sootup.core.jimple.basic.StmtPositionInfo
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.constant.DoubleConstant
import sootup.core.jimple.common.constant.FloatConstant
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.constant.LongConstant
import sootup.core.jimple.common.constant.StringConstant
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.jimple.common.ref.JStaticFieldRef
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.java.core.views.JavaView

/** Untyped rule scalars retain their own value types when the lookup returns erased Object. */
class FoldScalarInferenceTest {
    private val view = JavaView(emptyList())
    private val factory = view.identifierFactory
    private val objectType = factory.getClassType("java.lang.Object")
    private val result = Local("result", objectType)
    private val invoke = JStaticInvokeExpr(
        factory.getMethodSignature(factory.getClassType("sample.Flags"), "lookup", objectType, emptyList()),
        emptyList()
    )
    private val position = StmtPositionInfo.getNoStmtPositionInfo()
    private val assignment = JAssignStmt(result, invoke, position)
    private val values = FoldConstantValues()

    private fun pattern(label: String, scalar: Any) = ConstantPattern(label, mapOf(ConstantPattern.VALUE to scalar))

    private fun scalarPattern(scalar: Any) = pattern(ConstantPattern.CONSTANT, scalar)

    private fun rule(pattern: ConstantPattern) = ConstantFold(mapOf("callee_name" to "lookup"), value = pattern)

    private fun replacement(pattern: ConstantPattern): Value {
        val replacement = assertIs<JAssignStmt>(values.replacementFor(assignment, invoke, rule(pattern), view, null))
        assertSame(result, replacement.leftOp, "inference preserves the assignment destination")
        return replacement.rightOp
    }

    private fun assertBoxed(pattern: ConstantPattern, primitive: String, wrapper: String, constant: Value) {
        val boxed = assertIs<JStaticInvokeExpr>(replacement(pattern))
        assertEquals(wrapper, boxed.methodSignature.declClassType.fullyQualifiedName)
        assertEquals("valueOf", boxed.methodSignature.name)
        assertEquals(wrapper, boxed.methodSignature.type.toString())
        assertEquals(listOf(primitive), boxed.methodSignature.parameterTypes.map { it.toString() })
        assertEquals(listOf(constant), boxed.args)
    }

    @Test
    fun `generic boolean values become the matching Boolean singleton`() {
        for ((scalar, field) in listOf(true to "TRUE", false to "FALSE")) {
            val boxed = assertIs<JStaticFieldRef>(replacement(scalarPattern(scalar)))
            assertEquals("java.lang.Boolean", boxed.fieldSignature.declClassType.fullyQualifiedName)
            assertEquals("java.lang.Boolean", boxed.fieldSignature.type.toString())
            assertEquals(field, boxed.fieldSignature.name)
        }
    }

    @Test
    fun `generic numbers keep integer width and double signed zero when boxed`() {
        assertBoxed(scalarPattern(42), "int", "java.lang.Integer", IntConstant.getInstance(42))
        assertBoxed(scalarPattern(42L), "long", "java.lang.Long", LongConstant.getInstance(42L))
        assertBoxed(scalarPattern(-0.0), "double", "java.lang.Double", DoubleConstant.getInstance(-0.0))
        val boxed = assertIs<JStaticInvokeExpr>(replacement(scalarPattern(-0.0)))
        assertEquals((-0.0).toRawBits(), assertIs<DoubleConstant>(boxed.args.single()).value.toRawBits())
    }

    @Test
    fun `generic text stays a string constant with the exact content`() {
        val text = "value with \"quotes\" and α"
        val constant = assertIs<StringConstant>(replacement(scalarPattern(text)))
        assertEquals(text, constant.value)
        assertEquals("java.lang.String", constant.type.toString())
    }

    @Test
    fun `explicit numeric labels determine erased boxing instead of the carrier scalar class`() {
        assertBoxed(pattern(ConstantPattern.INT, 42L), "int", "java.lang.Integer", IntConstant.getInstance(42))
        assertBoxed(pattern(ConstantPattern.LONG, 42), "long", "java.lang.Long", LongConstant.getInstance(42L))
        assertBoxed(pattern(ConstantPattern.FLOAT, 0.1), "float", "java.lang.Float", FloatConstant.getInstance(0.1f))
        assertBoxed(pattern(ConstantPattern.DOUBLE, 42), "double", "java.lang.Double", DoubleConstant.getInstance(42.0))
    }

    @Test
    fun `explicit boolean and string labels retain their own erased reference values`() {
        val boolean = assertIs<JStaticFieldRef>(replacement(pattern(ConstantPattern.BOOLEAN, false)))
        assertEquals("java.lang.Boolean", boolean.fieldSignature.declClassType.fullyQualifiedName)
        assertEquals("FALSE", boolean.fieldSignature.name)
        val string = assertIs<StringConstant>(replacement(pattern(ConstantPattern.STRING, "labelled")))
        assertEquals("labelled", string.value)
        assertEquals("java.lang.String", string.type.toString())
    }

    @Test
    fun `a generic pattern without a scalar cannot erase an assigned or discarded call`() {
        val unspecified = rule(ConstantPattern(ConstantPattern.CONSTANT))
        assertNull(values.replacementFor(assignment, invoke, unspecified, view, null))
        assertNull(values.replacementFor(JInvokeStmt(invoke, position), invoke, unspecified, view, null))
        assertSame(invoke, assignment.rightOp)
    }
}
