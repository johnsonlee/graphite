package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId

/**
 * A concrete implementation that a call on a function value (a Java functional interface, a
 * Kotlin `FunctionN`, a `fun interface`, ...) may dispatch to.
 *
 * Function values reach the graph in two shapes:
 * - [Handle]: an `invokedynamic` through `LambdaMetafactory` (Java lambdas and method references,
 *   Kotlin 2.x lambdas and SAM conversions), whose bootstrap arguments name the implementation
 *   method and whose dynamic arguments are the captured values;
 * - [FunctionObject]: an instance of a class that *is* the function (Kotlin `Lambda`,
 *   `SuspendLambda`, callable/property reference classes, `$sam$` wrappers, anonymous classes and
 *   `object :` expressions), where the implementation is whatever that class declares for the
 *   invoked method.
 */
internal sealed interface DispatchTarget {

    /**
     * An `invokedynamic`-bound [method].
     *
     * @property samName the functional interface method the handle implements; only calls to a
     *   method of that name dispatch here (so `fn.andThen(...)` does not reach the lambda body)
     * @property captures the values bound at creation, which precede the call's own arguments
     */
    data class Handle(
        val method: MethodDescriptor,
        val kind: HandleKind,
        val samName: String,
        val captures: List<NodeId>
    ) : DispatchTarget

    /** An instance of [className], dispatched by the invoked method's name and parameter types. */
    data class FunctionObject(val className: String) : DispatchTarget

    /**
     * A method reference to a function value's own method (`fn::apply`): calling the new function
     * value calls [invokedAs] on the captured receiver, which dispatches on to [inner].
     */
    data class Adapted(
        val samName: String,
        val invokedAs: MethodDescriptor,
        val captures: List<NodeId>,
        val inner: DispatchTarget
    ) : DispatchTarget
}

/** How a method handle's implementation takes its receiver and arguments. */
internal enum class HandleKind {
    /** Static method or constructor: captures and call arguments are all parameters. */
    STATIC,

    /** Instance method: the first captured value, or else the first call argument, is the receiver. */
    INSTANCE
}

/** The call site a [DispatchTarget] resolves a call to. */
internal data class ResolvedDispatch(
    val method: MethodDescriptor,
    val receiver: NodeId?,
    val arguments: List<NodeId>
)

/** A call on a function value whose targets are only known after every method is processed. */
internal data class PendingDispatch(
    val callSite: CallSiteNode,
    val result: NodeId?,
    val resolved: Set<DispatchTarget>
)

/**
 * A place a function value can be held across method boundaries; dispatch targets propagate
 * between slots until they reach a fixpoint.
 */
internal sealed interface DispatchSlot {
    data class Parameter(val method: MethodDescriptor, val index: Int) : DispatchSlot
    data class Return(val method: MethodDescriptor) : DispatchSlot
    data class Field(val key: String) : DispatchSlot
    data class Local(val method: MethodDescriptor, val name: String) : DispatchSlot
}

/** Superclasses whose subclasses the Kotlin compiler emits for lambdas and callable references. */
internal fun isKotlinFunctionBaseClass(className: String): Boolean =
    className == KOTLIN_SUSPEND_LAMBDA ||
        className == KOTLIN_RESTRICTED_SUSPEND_LAMBDA ||
        (className.startsWith(KOTLIN_JVM_INTERNAL) &&
            (className.endsWith("Lambda") || className.contains("Reference")))

/** Interfaces that make any implementing class a Kotlin function value. */
internal fun isKotlinFunctionInterface(className: String): Boolean =
    className.startsWith(KOTLIN_FUNCTIONS) || className == KOTLIN_FUNCTION_BASE

/** Kotlin property references implement `invoke` in the stdlib base class by delegating to `get`. */
internal fun isKotlinPropertyReferenceClass(className: String): Boolean =
    className.startsWith(KOTLIN_JVM_INTERNAL) && className.contains("PropertyReference")

/**
 * Class names compilers and desugaring tools give to function objects:
 * - anonymous classes (`new Runnable() {...}`, Kotlin `object : ...`, Kotlin lambda classes), and
 *   retrolambda / Bazel desugar lambdas (`Outer$$Lambda$1`): `Outer$<digits>`;
 * - Kotlin SAM wrappers: `Outer$sam$<interface>$<digits>`;
 * - D8/R8 desugared lambdas: `Outer$$ExternalSyntheticLambda<n>`, and `-$$Lambda$Outer$<hash>`
 *   from older D8 versions.
 */
internal fun isGeneratedFunctionClassName(className: String): Boolean {
    val simpleName = className.substringAfterLast('.')
    val suffix = simpleName.substringAfterLast('$', missingDelimiterValue = "")
    return (suffix.isNotEmpty() && suffix.all(Char::isDigit)) ||
        GENERATED_FUNCTION_CLASS_MARKERS.any(simpleName::contains)
}

/** Types whose values are never function values: primitives and common final or concrete JDK types. */
internal fun isNonFunctionType(className: String): Boolean = className in NON_FUNCTION_TYPES

private val NON_FUNCTION_TYPES = setOf(
    "boolean", "byte", "char", "short", "int", "long", "float", "double", "void",
    "java.lang.String", "java.lang.Boolean", "java.lang.Byte", "java.lang.Character", "java.lang.Short",
    "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double", "java.lang.Class",
    "java.lang.StringBuilder", "java.lang.StringBuffer", "java.math.BigDecimal", "java.math.BigInteger",
    "java.util.ArrayList", "java.util.LinkedList", "java.util.HashMap", "java.util.LinkedHashMap",
    "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeMap", "java.util.TreeSet"
)

private const val KOTLIN_JVM_INTERNAL = "kotlin.jvm.internal."
private const val KOTLIN_FUNCTIONS = "kotlin.jvm.functions.Function"
private const val KOTLIN_FUNCTION_BASE = "kotlin.jvm.internal.FunctionBase"
private const val KOTLIN_SUSPEND_LAMBDA = "kotlin.coroutines.jvm.internal.SuspendLambda"
private const val KOTLIN_RESTRICTED_SUSPEND_LAMBDA = "kotlin.coroutines.jvm.internal.RestrictedSuspendLambda"
private val GENERATED_FUNCTION_CLASS_MARKERS = listOf("\$sam\$", "\$\$ExternalSyntheticLambda", "-\$\$Lambda\$")
