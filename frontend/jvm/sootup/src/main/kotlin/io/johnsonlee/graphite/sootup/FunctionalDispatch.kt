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
 *
 * Resolution is context-insensitive: every function value passed to a method's parameter, or
 * returned from it, lands in the same [DispatchSlot], so a call inside a shared helper
 * resolves to the union of what all its callers pass.
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

/**
 * The call site a [DispatchTarget] resolves a call to. [argumentSlots] is aligned with
 * [arguments]: the slot each argument's function value lives in, or null for a value that
 * cannot hold one (or a capture, whose flow was recorded at creation).
 */
internal data class ResolvedDispatch(
    val method: MethodDescriptor,
    val receiver: NodeId?,
    val receiverSlot: DispatchSlot?,
    val arguments: List<NodeId>,
    val argumentSlots: List<DispatchSlot?>
)

/**
 * A call on a function value whose targets may only be known after every method is
 * processed. [resolved] collects the targets already emitted for it, [argumentSlots] and
 * [resultSlot] are where its arguments and result live, so a resolved implementation's
 * parameter and return slots can be connected to them.
 */
internal class PendingDispatch(
    val callSite: CallSiteNode,
    val result: NodeId?,
    val argumentSlots: List<DispatchSlot?>,
    val resultSlot: DispatchSlot?,
    val resolved: MutableSet<DispatchTarget>
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

/** Base classes of Kotlin callable references (`::f`, `obj::f`, `C::prop`), which store the bound receiver. */
internal fun isKotlinCallableReferenceBaseClass(className: String): Boolean =
    className.startsWith(KOTLIN_JVM_INTERNAL) && className.contains("Reference")

/** Interfaces that make any implementing class a Kotlin function value. */
internal fun isKotlinFunctionInterface(className: String): Boolean =
    className.startsWith(KOTLIN_FUNCTIONS) || className == KOTLIN_FUNCTION_BASE

/** Kotlin property references implement `invoke` in the stdlib base class by delegating to `get`. */
internal fun isKotlinPropertyReferenceClass(className: String): Boolean =
    className.startsWith(KOTLIN_JVM_INTERNAL) && className.contains("PropertyReference")

/**
 * A function value re-bound through a method reference to its own method (`fn::apply`): every
 * target that reaches the slot this adapter is registered on also reaches [sink], wrapped in a
 * [DispatchTarget.Adapted] that dispatches through [invokedAs] on the captured receiver
 * ([captures] leads with it).
 */
internal data class SlotAdapter(
    val sink: DispatchSlot,
    val samName: String,
    val invokedAs: MethodDescriptor,
    val captures: List<NodeId>
) {
    /**
     * [target] re-bound through this site, or null when the chain already passes through this
     * site (`fn = fn::apply` in a loop): the repeated layer resolves to the same call as the
     * first, so dropping it loses nothing and keeps the fixpoint finite. Distinct sites nest
     * freely, up to [MAX_ADAPTED_DEPTH] as a safety bound.
     */
    fun adapt(target: DispatchTarget): DispatchTarget? {
        val chain = target.adaptedChain()
        val repeated = chain.any { it.samName == samName && it.invokedAs == invokedAs && it.captures == captures }
        return if (repeated || chain.size >= MAX_ADAPTED_DEPTH) null else DispatchTarget.Adapted(samName, invokedAs, captures, target)
    }
}

/** The `fn::apply` re-bindings this target is wrapped in, outermost first. */
internal fun DispatchTarget.adaptedChain(): List<DispatchTarget.Adapted> =
    generateSequence(this as? DispatchTarget.Adapted) { it.inner as? DispatchTarget.Adapted }.toList()

private const val MAX_ADAPTED_DEPTH = 32

/**
 * Whether [className] is the binary name of an anonymous class (JLS 13.1: `Outer$<digits>`),
 * which is also the shape of Kotlin lambda classes (`Outer$fn$1`), Kotlin SAM wrappers
 * (`Outer$sam$<interface>$0`) and retrolambda / Bazel desugar lambdas (`Outer$$Lambda$1`).
 * D8/R8 lambda classes (`Outer$$ExternalSyntheticLambda<n>`) are recognized by their synthetic
 * flag instead, since R8 may rename them.
 */
internal fun isAnonymousClassName(className: String): Boolean {
    val suffix = className.substringAfterLast('.').substringAfterLast('$', missingDelimiterValue = "")
    return suffix.isNotEmpty() && suffix.all(Char::isDigit)
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
