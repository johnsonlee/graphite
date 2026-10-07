package io.johnsonlee.graphite.sootup

import java.util.concurrent.ConcurrentHashMap
import sootup.core.jimple.common.expr.AbstractInvokeExpr
import sootup.core.jimple.common.expr.JNewExpr
import sootup.core.jimple.common.expr.JSpecialInvokeExpr
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.jimple.common.ref.JStaticFieldRef
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.model.MethodModifier
import sootup.core.model.SootClass
import sootup.core.model.SootMethod
import sootup.core.types.ClassType
import sootup.core.views.View

private const val ENUM_BASE_CLASS = "java.lang.Enum"
private const val ENUM_CONSTRUCTOR = "<init>"
private const val ENUM_INITIALIZER = "<clinit>"
private const val ENUM_VALUES_FACTORY = "\$values"

/**
 * A static enum field can still be null when initialization calls back into its reader.
 * Only trust enums whose initialization cannot call user code outside their generated
 * construction sequence. This deliberately rejects custom initialization rather than
 * approximating a call graph and missing virtual calls or another class's initializer.
 */
internal class EnumInitializationSafety {
    private val safe = ConcurrentHashMap<ClassType, Boolean>()

    fun isNonReentrant(view: View, type: ClassType): Boolean {
        // Resolving an initializer body may itself run the fold interceptor. Publish false
        // before resolving it, so recursive readers remain conservative instead of looping.
        safe.putIfAbsent(type, false)?.let { return it }
        val result = runCatching { prove(view.getClass(type).orElse(null)) }.getOrDefault(false)
        safe[type] = result
        return result
    }

    private fun prove(declared: SootClass?): Boolean {
        if (declared == null) return false
        val ordinaryEnum = declared.isEnum && declared.isFinal && declared.interfaces.isEmpty()
        val candidates = if (ordinaryEnum && declared.superclass.orElse(null)?.fullyQualifiedName == ENUM_BASE_CLASS) {
            declared.methods
        } else emptyList()
        val initialization = candidates.filter {
            it.name == ENUM_INITIALIZER || it.name == ENUM_CONSTRUCTOR || isValuesFactory(it)
        }
        return initialization.any { it.name == ENUM_INITIALIZER } &&
            initialization.any { it.name == ENUM_CONSTRUCTOR } &&
            initialization.all { method -> method.hasBody() && method.body.stmts.all { safeStatement(it, declared) } }
    }

    private fun isValuesFactory(method: SootMethod): Boolean =
        method.name == ENUM_VALUES_FACTORY && method.isPrivate && method.isStatic && MethodModifier.SYNTHETIC in method.modifiers

    private fun safeStatement(stmt: Stmt, declared: SootClass): Boolean {
        val assignment = stmt as? JAssignStmt
        val fields = stmt.uses.filterIsInstance<JStaticFieldRef>() + listOfNotNull(assignment?.leftOp as? JStaticFieldRef)
        val ownFields = fields.all { it.fieldSignature.declClassType == declared.type }
        val allocation = assignment?.rightOp as? JNewExpr
        val ownAllocation = allocation == null || allocation.type == declared.type
        val invoke = when (stmt) {
            is JAssignStmt -> stmt.invokeExpr.orElse(null)
            is JInvokeStmt -> stmt.invokeExpr.orElse(null)
            else -> null
        }
        return ownFields && ownAllocation && (invoke == null || safeInvocation(invoke, declared))
    }

    private fun safeInvocation(invoke: AbstractInvokeExpr, declared: SootClass): Boolean {
        val signature = invoke.methodSignature
        val target = declared.methods.firstOrNull { it.signature == signature }
        return when {
            signature.declClassType.fullyQualifiedName == ENUM_BASE_CLASS ->
                invoke is JSpecialInvokeExpr && signature.name == ENUM_CONSTRUCTOR
            isKotlinEnumEntriesFactory(invoke) -> true
            signature.declClassType != declared.type -> false
            invoke is JSpecialInvokeExpr -> target?.name == ENUM_CONSTRUCTOR
            invoke is JStaticInvokeExpr -> target?.let(::isValuesFactory) == true
            else -> false
        }
    }

    /**
     * Kotlin 1.9+ enum initializers wrap their generated values array for the entries property.
     * The stdlib array overload only null-checks and stores that array in EnumEntriesList;
     * its constructor chain cannot call application code. The Function0 overload invokes a
     * provider, so matching the exact descriptor is essential. Verified against 2.0.21:
     * https://github.com/JetBrains/kotlin/blob/v2.0.21/libraries/stdlib/src/kotlin/enums/EnumEntries.kt
     */
    private fun isKotlinEnumEntriesFactory(invoke: AbstractInvokeExpr): Boolean {
        val signature = invoke.methodSignature
        return invoke is JStaticInvokeExpr &&
            signature.declClassType.fullyQualifiedName == "kotlin.enums.EnumEntriesKt" &&
            signature.name == "enumEntries" &&
            signature.parameterTypes.singleOrNull()?.toString() == "java.lang.Enum[]" &&
            signature.type.toString() == "kotlin.enums.EnumEntries"
    }
}
