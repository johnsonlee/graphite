package io.johnsonlee.graphite.sootup

import sootup.core.jimple.common.Local
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.constant.Constant
import sootup.core.jimple.common.constant.DoubleConstant
import sootup.core.jimple.common.constant.FloatConstant
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.constant.LongConstant
import sootup.core.jimple.common.constant.NullConstant
import sootup.core.jimple.common.constant.StringConstant
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr
import sootup.core.jimple.common.expr.AbstractInvokeExpr
import sootup.core.jimple.common.expr.JCastExpr
import sootup.core.jimple.common.expr.JSpecialInvokeExpr
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.jimple.common.ref.JStaticFieldRef
import sootup.core.jimple.common.stmt.JAssignStmt

/** Equality semantics of known final JDK values; never executes application equals methods. */
internal class ConstantEquality(
    private val definitions: Map<Local, JAssignStmt>,
    private val enums: Map<Local, JStaticFieldRef>
) {
    private data class Known(val type: String, val value: Any)

    fun evaluate(invoke: AbstractInvokeExpr): Boolean? {
        val signature = invoke.methodSignature
        if (signature.type.toString() != "boolean") return null
        val parameters = signature.parameterTypes.map { it.toString() }
        return when {
            invoke is AbstractInstanceInvokeExpr && invoke !is JSpecialInvokeExpr &&
                signature.name == "equals" && parameters == listOf(OBJECT) -> {
                compare(invoke.base, invoke.args.single(), nullableReceiver = false)
            }
            invoke is JStaticInvokeExpr && parameters == listOf(OBJECT, OBJECT) &&
                (signature.declClassType.fullyQualifiedName to signature.name) in HELPERS -> {
                compare(invoke.args[0], invoke.args[1], nullableReceiver = true)
            }
            else -> null
        }
    }

    private fun compare(left: Value, right: Value, nullableReceiver: Boolean): Boolean? =
        known(left)?.let { a ->
            known(right)?.let { b -> if (!nullableReceiver && a == NULL) null else a == b }
        }

    private fun known(value: Value, visited: Set<Local> = emptySet()): Known? = when (value) {
        is NullConstant -> NULL
        is StringConstant -> Known("java.lang.String", value.value)
        is Local -> if (value in visited) null else {
            enums[value]?.let { Known(it.fieldSignature.declClassType.fullyQualifiedName, it.fieldSignature) }
                ?: definitions[value]?.rightOp?.let { known(it, visited + value) }
        }
        // The cast remains in the graph, including its exceptional path. On normal completion
        // it preserves the object's value; eliminating a later equals cannot suppress its throw.
        is JCastExpr -> known(value.op, visited)
        is JStaticFieldRef -> booleanBoxOf(value)?.let { boxed(it.first, it.second) }
        is JStaticInvokeExpr -> valueOfBoxOf(value)?.let { boxed(it.first, it.second) }
        else -> null
    }

    private fun boxed(type: String, constant: Constant): Known? {
        val value: Any? = when (constant) {
            is IntConstant -> when (type) {
                "java.lang.Boolean" -> constant.value != 0
                "java.lang.Byte" -> constant.value.toByte()
                "java.lang.Short" -> constant.value.toShort()
                "java.lang.Character" -> constant.value.toChar()
                "java.lang.Integer" -> constant.value
                else -> null
            }
            is LongConstant -> constant.value
            // Boxed equals canonicalizes NaNs and distinguishes positive and negative zero.
            is FloatConstant -> java.lang.Float.floatToIntBits(constant.value)
            is DoubleConstant -> java.lang.Double.doubleToLongBits(constant.value)
            else -> null
        }
        return value?.let { Known(type, it) }
    }

    private companion object {
        const val OBJECT = "java.lang.Object"
        val NULL = Known("null", Unit)
        val HELPERS = setOf("java.util.Objects" to "equals", "kotlin.jvm.internal.Intrinsics" to "areEqual")
    }
}
