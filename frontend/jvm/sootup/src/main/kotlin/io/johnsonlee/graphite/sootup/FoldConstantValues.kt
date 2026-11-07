package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.ConstantPattern
import io.johnsonlee.graphite.input.FoldRule
import sootup.core.jimple.common.Immediate
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.constant.DoubleConstant
import sootup.core.jimple.common.constant.FloatConstant
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.constant.LongConstant
import sootup.core.jimple.common.constant.NullConstant
import sootup.core.jimple.common.constant.StringConstant
import sootup.core.jimple.common.constant.Constant
import sootup.core.jimple.common.expr.AbstractInvokeExpr
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.jimple.common.ref.JStaticFieldRef
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.core.jimple.common.stmt.JNopStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.model.FieldModifier
import sootup.core.signatures.FieldSignature
import sootup.core.types.ClassType
import sootup.core.types.PrimitiveType
import sootup.core.types.Type
import sootup.core.types.VoidType
import sootup.core.views.View

private const val STRING_CLASS = "java.lang.String"

private const val OBJECT_CLASS = "java.lang.Object"

private const val VALUE_OF = "valueOf"

private const val BOOLEAN_TYPE = "boolean"

private const val INT_TYPE = "int"

private const val LONG_TYPE = "long"

private const val FLOAT_TYPE = "float"

private const val DOUBLE_TYPE = "double"

private fun booleanConstant(value: Boolean): Value = IntConstant.getInstance(if (value) 1 else 0)

/** The type a scalar names on its own: `false` is a `boolean`, `3` an `int`, `"x"` a `java.lang.String`. */
private fun naturalType(pattern: ConstantPattern): String? = when (pattern.label) {
    ConstantPattern.BOOLEAN -> BOOLEAN_TYPE
    ConstantPattern.INT -> INT_TYPE
    ConstantPattern.LONG -> LONG_TYPE
    ConstantPattern.FLOAT -> FLOAT_TYPE
    ConstantPattern.DOUBLE -> DOUBLE_TYPE
    ConstantPattern.STRING -> STRING_CLASS
    else -> when (pattern.scalar) {
        is Boolean -> BOOLEAN_TYPE
        is Int -> INT_TYPE
        is Long -> LONG_TYPE
        is Double -> DOUBLE_TYPE
        is String -> STRING_CLASS
        else -> null
    }
}

/** [constant], a value of [primitive], boxed as the compiler boxes it. */
private fun boxed(primitive: String, constant: Immediate, view: View): Value {
    val factory = view.identifierFactory
    val box = factory.getClassType(BOXES.getValue(primitive).first)
    return if (primitive == BOOLEAN_TYPE) {
        JStaticFieldRef(FieldSignature(box, if ((constant as IntConstant).value != 0) "TRUE" else "FALSE", box))
    } else {
        JStaticInvokeExpr(factory.getMethodSignature(box, VALUE_OF, box, listOf(factory.getType(primitive))), listOf(constant))
    }
}

/**
 * [value] as the `int` constant Jimple uses for every int-like [type], or `null` when the
 * declared type cannot hold it: a `byte` method does not return 128, a `short` 32768 or a
 * `char` -1, whatever the carrier, and a folded call must keep the method's own contract.
 */
private fun intLikeConstant(type: Type, value: Long): Value? =
    intLikeRange(type)?.takeIf { value in it }?.let { IntConstant.getInstance(value.toInt()) }

/**
 * [value] at `float` precision, or `null` when a `float` cannot carry it: a finite double
 * past `Float.MAX_VALUE` (`1e308`) narrows to an infinity, a value that was not written.
 */
private fun floatConstant(value: Double): Value? =
    value.toFloat().takeIf { it.isFinite() || !value.isFinite() }?.let(FloatConstant::getInstance)

private fun nullFor(type: Type): Value? =
    if (type is ClassType || type.toString().endsWith("[]")) NullConstant.getInstance() else null

private fun isIntLike(type: Type): Boolean = intLikeRange(type) != null

/**
 * The values a type Jimple carries as an `int` can hold, `null` for any other type. SootUp
 * derives the narrow types (and `boolean`) from `IntType`, so they are matched first.
 */
private fun intLikeRange(type: Type): LongRange? = when (type) {
    is PrimitiveType.BooleanType -> null
    is PrimitiveType.ByteType -> Byte.MIN_VALUE.toLong()..Byte.MAX_VALUE.toLong()
    is PrimitiveType.ShortType -> Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong()
    is PrimitiveType.CharType -> Char.MIN_VALUE.code.toLong()..Char.MAX_VALUE.code.toLong()
    is PrimitiveType.IntType -> Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
    else -> null
}

private fun isString(type: Type): Boolean = type is ClassType && type.fullyQualifiedName == STRING_CLASS

private fun integral(value: Any): Long? = when (value) {
    is Int -> value.toLong()
    is Long -> value
    else -> null
}

private fun numeric(value: Any): Double? = when (value) {
    is Int -> value.toDouble()
    is Long -> value.toDouble()
    is Double -> value
    else -> null
}

private fun isObject(type: Type): Boolean = type is ClassType && type.fullyQualifiedName == OBJECT_CLASS

/** Typed fold values and enum eligibility; independent of body interception and per-rule accounting. */
internal class FoldConstantValues {
    private val enumConstants = HashMap<Pair<ClassType, String>, Boolean>()

    fun describe(constant: Constant, parameterType: Type): Pair<String, Map<String, Any?>>? = when (constant) {
        is IntConstant ->
            if (parameterType is PrimitiveType.BooleanType) ConstantPattern.BOOLEAN to mapOf(ConstantPattern.VALUE to (constant.value != 0))
            else ConstantPattern.INT to mapOf(ConstantPattern.VALUE to constant.value)
        is LongConstant -> ConstantPattern.LONG to mapOf(ConstantPattern.VALUE to constant.value)
        is FloatConstant -> ConstantPattern.FLOAT to mapOf(ConstantPattern.VALUE to constant.value.toDouble())
        is DoubleConstant -> ConstantPattern.DOUBLE to mapOf(ConstantPattern.VALUE to constant.value)
        is StringConstant -> ConstantPattern.STRING to mapOf(ConstantPattern.VALUE to constant.value)
        is NullConstant -> ConstantPattern.NULL to emptyMap()
        else -> null
    }

    /** A static field read: an enum constant when the field is one (`ACC_ENUM`), a plain field otherwise. */
    fun describe(field: JStaticFieldRef, view: View): Pair<String, Map<String, Any?>> {
        val signature = field.fieldSignature
        val owner = signature.declClassType
        return if (isEnumConstant(view, field)) {
            ConstantPattern.ENUM to mapOf("enum_type" to owner.fullyQualifiedName, "name" to signature.name)
        } else {
            ConstantPattern.FIELD to mapOf("class" to owner.fullyQualifiedName, "name" to signature.name)
        }
    }

    /**
     * The statement that stands in for [stmt], or `null` when the return type cannot carry the
     * value. A discarded call is checked the same way before it becomes a `nop`: a `void` call,
     * or an overload returning a type the value does not fit, is not the call the rule means,
     * and deleting it would delete its effects.
     */
    fun replacementFor(stmt: Stmt, invoke: AbstractInvokeExpr, fold: FoldRule, view: View, resultType: String?): Stmt? {
        val returnType = invoke.methodSignature.type
        val value = if (isObject(returnType)) erasedConstantFor(fold.value, view, resultType) else constantFor(returnType, fold.value, view)
        if (value == null) return null
        return if (stmt is JInvokeStmt) JNopStmt(stmt.positionInfo) else (stmt as JAssignStmt).withRValue(value)
    }

    /**
     * The Jimple value [pattern] names for a call returning [type], `null` when the type cannot
     * carry it: a constant, or for an `EnumConstant` the read of the enum's static field, which
     * is what the constant is in bytecode. The enum must be the return type itself.
     */
    @Suppress("ReturnCount")
    private fun constantFor(type: Type, pattern: ConstantPattern, view: View): Value? {
        if (pattern.label == ConstantPattern.NULL) return nullFor(type)
        if (pattern.label == ConstantPattern.ENUM) return enumConstantFor(type, pattern, view)
        val primitive = BOXES.entries.firstOrNull { it.value.first == type.toString() }?.key
        if (primitive != null) {
            return constantFor(view.identifierFactory.getType(primitive), pattern, view)
                ?.let { boxed(primitive, it as Immediate, view) }
        }
        val value = pattern.scalar
        if (type is VoidType || value == null) return null
        return when (pattern.label) {
            ConstantPattern.CONSTANT -> inferredConstantFor(type, value, view)
            ConstantPattern.BOOLEAN -> (value as? Boolean)?.takeIf { type is PrimitiveType.BooleanType }?.let(::booleanConstant)
            ConstantPattern.INT -> integral(value)?.let { intLikeConstant(type, it) }
            ConstantPattern.LONG -> integral(value)?.takeIf { type is PrimitiveType.LongType }?.let(LongConstant::getInstance)
            ConstantPattern.FLOAT -> numeric(value)?.takeIf { type is PrimitiveType.FloatType }?.let(::floatConstant)
            ConstantPattern.DOUBLE -> numeric(value)?.takeIf { type is PrimitiveType.DoubleType }?.let(DoubleConstant::getInstance)
            else -> (value as? String)?.takeIf { isString(type) }?.let { stringConstant(it, view) }
        }
    }

    /**
     * The value a call whose return type is erased to `java.lang.Object` becomes, `null` when
     * none fits: what the call really returns there is [resultType], when the CLI read it off the
     * function value's body, else the type the constant names (`false` a `boolean`, `3` an `int`,
     * an `EnumConstant` its enum). A primitive is boxed the way the compiler boxes it
     * (`Boolean.TRUE`/`FALSE`, `Integer.valueOf(3)`), so the cast and unboxing after the call
     * fold; a string is its constant; an enum constant is the read of its field.
     */
    private fun erasedConstantFor(pattern: ConstantPattern, view: View, resultType: String?): Value? {
        val factory = view.identifierFactory
        val effective = resultType ?: naturalType(pattern)
        val primitive = BOXES.entries.firstOrNull { (name, box) -> effective == name || effective == box.first }?.key
        val enumType = pattern.properties[ConstantPattern.ENUM_TYPE] as? String
        return when {
            pattern.label == ConstantPattern.NULL -> NullConstant.getInstance()
            pattern.label == ConstantPattern.ENUM -> enumType?.takeIf { resultType == null || resultType == it }
                ?.let { enumConstantFor(factory.getClassType(it), pattern, view) }
            primitive != null -> constantFor(factory.getType(primitive), pattern, view)?.let { boxed(primitive, it as Immediate, view) }
            effective == STRING_CLASS -> constantFor(factory.getClassType(STRING_CLASS), pattern, view)
            else -> null
        }
    }

    /** The shorthand: the scalar is carried by whatever the return type is, when it can be. */
    private fun inferredConstantFor(type: Type, value: Any, view: View): Value? = when {
        type is PrimitiveType.BooleanType -> (value as? Boolean)?.let(::booleanConstant)
        isIntLike(type) -> (value as? Int)?.let { intLikeConstant(type, it.toLong()) }
        type is PrimitiveType.LongType -> integral(value)?.let(LongConstant::getInstance)
        type is PrimitiveType.DoubleType -> numeric(value)?.let(DoubleConstant::getInstance)
        type is PrimitiveType.FloatType -> numeric(value)?.let(::floatConstant)
        isString(type) -> (value as? String)?.let { stringConstant(it, view) }
        else -> null
    }

    /** The read of the named constant, when the return type is its enum and the enum declares a constant of that name. */
    private fun enumConstantFor(type: Type, pattern: ConstantPattern, view: View): Value? {
        val enumType = pattern.properties[ConstantPattern.ENUM_TYPE] as? String
        val name = pattern.properties[ConstantPattern.NAME] as? String
        val fits = type is ClassType && type.fullyQualifiedName == enumType && name != null && isEnumConstant(view, type, name)
        return if (fits) JStaticFieldRef(FieldSignature(type as ClassType, name!!, type)) else null
    }

    /** Whether [field] reads an enum constant, as `describe` labels it and `FoldBranches` compares it. */
    fun isEnumConstant(view: View, field: JStaticFieldRef): Boolean =
        isEnumConstant(view, field.fieldSignature.declClassType, field.fieldSignature.name)

    /**
     * Whether [owner] declares an enum constant [name]: the field carries `ACC_ENUM`, by the class
     * in the view, else by the analysis JVM's own copy of it, loaded without initialisation. An
     * enum's other static fields (`static E alias = A`) are fields, whatever their type.
     */
    fun isEnumConstant(view: View, owner: ClassType, name: String): Boolean = enumConstants.getOrPut(owner to name) {
        runCatching {
            view.getClass(owner).map { declared -> declared.getField(name).map { FieldModifier.ENUM in it.modifiers }.orElse(false) }
                .orElse(null)
        }.getOrNull()
            ?: runCatching {
                val loader = ConstantFolding::class.java.classLoader
                Class.forName(owner.fullyQualifiedName, false, loader).getDeclaredField(name).isEnumConstant
            }.getOrDefault(false)
    }

    private fun stringConstant(value: String, view: View): Value =
        StringConstant(value, view.identifierFactory.getClassType(STRING_CLASS))
}
