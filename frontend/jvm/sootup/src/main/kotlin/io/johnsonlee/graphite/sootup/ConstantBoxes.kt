package io.johnsonlee.graphite.sootup

import sootup.core.jimple.common.constant.Constant
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.jimple.common.ref.JStaticFieldRef

private const val BOOLEAN_BOX = "java.lang.Boolean"
private const val VALUE_OF = "valueOf"

/** Each primitive type's box and the method that unboxes it, as `javac` and `kotlinc` emit them. */
internal val BOXES: Map<String, Pair<String, String>> = mapOf(
    "boolean" to ("java.lang.Boolean" to "booleanValue"),
    "byte" to ("java.lang.Byte" to "byteValue"),
    "short" to ("java.lang.Short" to "shortValue"),
    "char" to ("java.lang.Character" to "charValue"),
    "int" to ("java.lang.Integer" to "intValue"),
    "long" to ("java.lang.Long" to "longValue"),
    "float" to ("java.lang.Float" to "floatValue"),
    "double" to ("java.lang.Double" to "doubleValue")
)

/** The box and the constant behind `Boolean.TRUE` / `Boolean.FALSE`, or `null`. */
internal fun booleanBoxOf(field: JStaticFieldRef): Pair<String, Constant>? {
    val signature = field.fieldSignature
    return when {
        signature.declClassType.fullyQualifiedName != BOOLEAN_BOX || signature.type.toString() != BOOLEAN_BOX -> null
        signature.name == "TRUE" -> BOOLEAN_BOX to IntConstant.getInstance(1)
        signature.name == "FALSE" -> BOOLEAN_BOX to IntConstant.getInstance(0)
        else -> null
    }
}

/** Only the standard primitive boxing overload, not valueOf(String) or custom factories. */
internal fun isPrimitiveBoxing(invoke: JStaticInvokeExpr): Boolean {
    val signature = invoke.methodSignature
    val box = signature.declClassType.fullyQualifiedName
    val primitive = signature.parameterTypes.singleOrNull()?.toString()
    return signature.name == VALUE_OF && BOXES[primitive]?.first == box && signature.type.toString() == box
}

/** The box and the constant behind `Integer.valueOf(3)` and its kin, or `null`. */
internal fun valueOfBoxOf(invoke: JStaticInvokeExpr): Pair<String, Constant>? {
    val signature = invoke.methodSignature
    val box = signature.declClassType.fullyQualifiedName
    val constant = invoke.args.singleOrNull() as? Constant
    return if (isPrimitiveBoxing(invoke) && constant != null) box to constant else null
}
