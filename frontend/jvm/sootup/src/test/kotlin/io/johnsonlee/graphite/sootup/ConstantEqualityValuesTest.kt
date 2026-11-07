package io.johnsonlee.graphite.sootup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import sootup.core.jimple.basic.StmtPositionInfo
import sootup.core.jimple.common.Immediate
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.constant.StringConstant
import sootup.core.jimple.common.expr.JCastExpr
import sootup.core.jimple.common.expr.JNewExpr
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.java.core.JavaIdentifierFactory

/** Known final JDK values compare by their actual wrapper type; unknown allocations stay unknown. */
class ConstantEqualityValuesTest {
    private val factory = JavaIdentifierFactory.getInstance()
    private val objectType = factory.getClassType("java.lang.Object")
    private val stringType = factory.getClassType("java.lang.String")
    private val position = StmtPositionInfo.getNoStmtPositionInfo()

    private fun equal(left: Immediate, right: Immediate, definitions: Map<Local, JAssignStmt>): Boolean? {
        val objects = factory.getClassType("java.util.Objects")
        val signature = factory.getMethodSignature(objects, "equals", factory.getType("boolean"), listOf(objectType, objectType))
        return ConstantEquality(definitions, emptyMap()).evaluate(JStaticInvokeExpr(signature, listOf(left, right)))
    }

    private fun boxed(primitive: String, wrapper: String, value: Int): Value {
        val type = factory.getClassType(wrapper)
        val signature = factory.getMethodSignature(type, "valueOf", type, listOf(factory.getType(primitive)))
        return JStaticInvokeExpr(signature, listOf(IntConstant.getInstance(value)))
    }

    @Test
    fun `a retained cast preserves the known string value for later equality`() {
        val local = Local("castResult", stringType)
        val cast = JCastExpr(StringConstant("value", stringType), stringType)
        val assignment = JAssignStmt(local, cast, position)
        val definitions = mapOf(local to assignment)
        assertEquals(true, equal(local, StringConstant("value", stringType), definitions))
        assertEquals(false, equal(local, StringConstant("other", stringType), definitions))
        assertSame(cast, assignment.rightOp, "evaluating equality must retain the possibly throwing cast")
    }

    @Test
    fun `Short equality preserves negative values and rejects an equal Integer magnitude`() {
        val left = Local("shortValue", factory.getClassType("java.lang.Short"))
        val same = Local("sameShort", left.type)
        val different = Local("differentShort", left.type)
        val integer = Local("integerValue", factory.getClassType("java.lang.Integer"))
        val definitions = mapOf(
            left to JAssignStmt(left, boxed("short", "java.lang.Short", -1), position),
            same to JAssignStmt(same, boxed("short", "java.lang.Short", -1), position),
            different to JAssignStmt(different, boxed("short", "java.lang.Short", 1), position),
            integer to JAssignStmt(integer, boxed("int", "java.lang.Integer", -1), position)
        )
        assertEquals(true, equal(left, same, definitions))
        assertEquals(false, equal(left, different, definitions))
        assertEquals(false, equal(left, integer, definitions))
    }

    @Test
    fun `an application allocation never becomes a proven equals result`() {
        val type = factory.getClassType("sample.Custom")
        val local = Local("custom", type)
        val allocation = JAssignStmt(local, JNewExpr(type), position)
        assertNull(equal(local, StringConstant("value", stringType), mapOf(local to allocation)))
    }
}
