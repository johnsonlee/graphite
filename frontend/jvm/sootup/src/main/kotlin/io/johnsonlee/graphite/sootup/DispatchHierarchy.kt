package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.analysis.DispatchSlot
import io.johnsonlee.graphite.analysis.DispatchTarget
import io.johnsonlee.graphite.analysis.HandleKind
import io.johnsonlee.graphite.analysis.ResolvedDispatch
import io.johnsonlee.graphite.analysis.isKotlinPropertyReferenceClass

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import org.objectweb.asm.Handle
import sootup.core.model.SootClass
import java.lang.reflect.Modifier
import sootup.core.model.MethodModifier
import sootup.core.model.SootMethod
import sootup.core.signatures.MethodSignature

/** Resolves virtual overrides and the scoped class hierarchy used by dispatch. */
internal class DispatchHierarchy(private val context: SootUpAdapter) {
    private val slots = context.slots
    private val directSubtypes = context.directSubtypes
    private val transitiveSubtypesByClass = context.transitiveSubtypesByClass
    private val supertypesByClass = context.supertypesByClass
    private val declaredMethodIndexByClass = context.declaredMethodIndexByClass
    private val sortedMethodsByClass = context.sortedMethodsByClass
    private val view = context.view
    private val bytecodeMethods get() = context.bytecodeMethods
    private val descriptors get() = context.descriptors
    private val functionObjects get() = context.functionObjects
    private val methodSources get() = context.methodSources
    private val resources get() = context.resources

    fun resolveDispatch(
        target: DispatchTarget,
        invoked: MethodDescriptor,
        receiver: NodeId?,
        arguments: List<NodeId>,
        argumentSlots: List<DispatchSlot?>
    ): ResolvedDispatch? = when (target) {
        is DispatchTarget.Handle -> if (target.samName != invoked.name || !functionObjects.isImplementedByFunctionValue(invoked)) {
            null
        } else {
            // The function value's own receiver is not the implementation's: a static method or
            // constructor has none, and an instance method takes the first capture or argument.
            // Captured values already flowed to the implementation's parameters at creation.
            val all = target.captures + arguments
            val slots = List<DispatchSlot?>(target.captures.size) { null } + argumentSlots
            when (target.kind) {
                HandleKind.STATIC -> ResolvedDispatch(target.method, null, null, all, slots)
                HandleKind.INSTANCE ->
                    ResolvedDispatch(target.method, all.firstOrNull(), slots.firstOrNull(), all.drop(1), slots.drop(1))
            }
        }
        // The function object is the receiver, and it dispatched here: no further dispatch on it
        is DispatchTarget.FunctionObject -> findFunctionObjectMethod(target.className, invoked)
            ?.let { ResolvedDispatch(it, receiver, null, arguments, argumentSlots) }
        is DispatchTarget.Adapted -> if (target.samName != invoked.name || !functionObjects.isImplementedByFunctionValue(invoked)) {
            null
        } else {
            val all = target.captures + arguments
            val slots = List<DispatchSlot?>(target.captures.size) { null } + argumentSlots
            resolveDispatch(target.inner, target.invokedAs, all.firstOrNull(), all.drop(1), slots.drop(1))
        }
    }

    /**
     * The method of function-object class [className] (or an in-view superclass) that a call
     * to [invoked] runs: same name and erased parameter types, which the bridge method the
     * compiler emits for a generic interface always has. Kotlin property references implement
     * `invoke` in the stdlib by calling `get`, so `get` stands in for `invoke` there.
     */
    fun findFunctionObjectMethod(className: String, invoked: MethodDescriptor): MethodDescriptor? {
        val start = resources.resolveClassByName(className)
        val names = if (
            start != null && invoked.name == KOTLIN_INVOKE && superclassNames(start).any(::isKotlinPropertyReferenceClass)
        ) {
            setOf(KOTLIN_INVOKE, KOTLIN_PROPERTY_GET)
        } else {
            setOf(invoked.name)
        }
        val parameterTypes = invoked.parameterTypes.map { it.className }
        return generateSequence(start) { current ->
            current.superclass.orElse(null)?.let { resources.resolveClassByName(it.fullyQualifiedName) }
        }.firstNotNullOfOrNull { current ->
            val candidates = methodsInSignatureOrder(current).filter {
                !it.isStatic && !it.isAbstract && it.name in names && it.parameterTypes.size == parameterTypes.size
            }
            // An exact override runs for any call; the erased bridge stands in only for an
            // abstract method with another erasure, never for a default method the class
            // does not override
            candidates.firstOrNull { method ->
                method.parameterTypes.map { descriptors.toTypeDescriptor(it).className } == parameterTypes
            } ?: candidates.firstOrNull { MethodModifier.isBridge(it.modifiers) && functionObjects.isImplementedByFunctionValue(invoked) }
        }?.let(descriptors::toMethodDescriptor)
    }

    /**
     * [sootClass]'s methods ordered by signature. SootUp's method set has no stable iteration
     * order across JVM runs, and the order decides which node IDs the call sites get.
     */
    fun methodsInSignatureOrder(sootClass: SootClass): List<SootMethod> =
        sortedMethodsByClass.getOrPut(sootClass.type.fullyQualifiedName) {
            (methodSources.streamMethodsOrNull(sootClass)?.toList() ?: bytecodeMethods.resolveMethodsOrEmpty(sootClass))
                .sortedBy { it.signature.toString() }
        }

    /**
     * Flows a call graph edge implies but no statement records: a function value passed to a
     * method's parameter reaches that parameter in every override (`invoker.invoke(fn)` calls
     * `Impl.invoke`), and one returned by an override is what a call on the overridden method
     * returns. Derived lazily, for slots that hold a target.
     */
    fun overrideFlows(slot: DispatchSlot): List<DispatchSlot> = when (slot) {
        is DispatchSlot.Parameter -> overridesOf(slot.method).map { DispatchSlot.Parameter(it, slot.index) }
        is DispatchSlot.Return -> overriddenBy(slot.method).map { DispatchSlot.Return(it) }
        else -> emptyList()
    }

    /**
     * The concrete implementations of [method] in every subtype of its declaring class: the
     * one a subtype declares, or the one it inherits from a superclass that is not itself a
     * subtype of the declaring type (`class Child extends Base implements Invoker {}` runs
     * `Base.invoke` for `Invoker.invoke`).
     */
    fun overridesOf(method: MethodDescriptor): List<MethodDescriptor> {
        if (!isOverridable(method)) return emptyList()
        val own = method.declaringClass.className
        return transitiveSubtypes(own).mapNotNull { subtype ->
            effectiveMethod(subtype,
                method)?.takeIf { it.declaringClassType.fullyQualifiedName != own }?.let(descriptors::toMethodDescriptor)
        }.distinct()
    }

    /**
     * The methods [method] implements or overrides: those declared by a supertype of its
     * declaring class, and those declared by a supertype of any subclass that inherits it
     * (`class FactoryChild extends FactoryBase implements Factory {}` makes `FactoryBase.make`
     * the implementation of `Factory.make`). A supertype outside the view (a JDK or Kotlin
     * stdlib interface) has no [SootMethod] to inspect, so its method is assumed to share
     * [method]'s sub-signature.
     */
    fun overriddenBy(method: MethodDescriptor): List<MethodDescriptor> {
        val own = method.declaringClass.className
        val declared = declaredMethod(own, method)?.takeIf { isOverridable(method) } ?: return emptyList()
        val implementors = listOf(own) + transitiveSubtypes(own).filter { effectiveMethod(it, method) === declared }
        return implementors.flatMap(::supertypes).distinct().filter { it != own }.mapNotNull { supertype ->
            when (resources.resolveClassByName(supertype)) {
                null -> descriptors.toMethodDescriptor(MethodSignature(view.identifierFactory.getClassType(supertype),
                    declared.subSignature))
                else -> declaredMethod(supertype, method)?.takeIf { !it.isStatic }?.let(descriptors::toMethodDescriptor)
            }
        }.distinct()
    }

    /** The concrete method a call to [descriptor] on an instance of [className] runs: declared by it or inherited. */
    fun effectiveMethod(className: String, descriptor: MethodDescriptor): SootMethod? =
        generateSequence(className) { resources.resolveClassByName(it)?.superclass?.orElse(null)?.fullyQualifiedName }
            .mapNotNull { declaredMethod(it, descriptor) }
            .firstOrNull()
            ?.takeIf { !it.isStatic && !it.isAbstract }

    fun isOverridable(method: MethodDescriptor): Boolean {
        if (method.name == INIT_METHOD || method.declaringClass.className == JAVA_LANG_OBJECT) return false
        val declared = declaredMethod(method.declaringClass.className, method)
        return declared == null || (!declared.isStatic && !MethodModifier.isPrivate(declared.modifiers))
    }

    /**
     * The method [className] declares with [descriptor]'s erased sub-signature, if any. The
     * return type is part of it, so an overridden generic method finds the override's bridge,
     * whose body flows on to the typed implementation.
     */
    fun declaredMethod(className: String, descriptor: MethodDescriptor): SootMethod? =
        declaredMethodIndexByClass.getOrPut(className) {
            resources.resolveClassByName(className)?.let(::methodsInSignatureOrder).orEmpty().associateBy { method ->
                methodKey(
                    method.name,
                    method.parameterTypes.map { descriptors.toTypeDescriptor(it).className },
                    descriptors.toTypeDescriptor(method.returnType).className
                )
            }
        }[methodKey(descriptor.name, descriptor.parameterTypes.map { it.className }, descriptor.returnType.className)]

    fun methodKey(name: String, parameterTypes: List<String>, returnType: String = ""): String =
        "$name(${parameterTypes.joinToString(",")})$returnType"

    /** Every class in the view that extends or implements [className], directly or not. */
    fun transitiveSubtypes(className: String): List<String> = transitiveSubtypesByClass.getOrPut(className) {
        val visited = linkedSetOf<String>()
        val queue = ArrayDeque(directSubtypes[className].orEmpty())
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (visited.add(next)) queue.addAll(directSubtypes[next].orEmpty())
        }
        visited.toList()
    }

    /** Every superclass and interface of [className], directly or not, as far as the view resolves them. */
    fun supertypes(className: String): List<String> = supertypesByClass.getOrPut(className) {
        val visited = linkedSetOf<String>()
        val queue = ArrayDeque(directSupertypes(className))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (visited.add(next)) queue.addAll(directSupertypes(next))
        }
        visited.toList()
    }

    fun directSupertypes(className: String): List<String> {
        val sootClass = resources.resolveClassByName(className) ?: return emptyList()
        return listOfNotNull(sootClass.superclass.orElse(null)?.fullyQualifiedName) +
            sootClass.interfaces.map { it.fullyQualifiedName }
    }

    /** Names of [sootClass]'s superclasses, as far as the view resolves them (plus the first one it does not). */
    fun superclassNames(sootClass: SootClass): Sequence<String> =
        generateSequence(sootClass.superclass.orElse(null)?.fullyQualifiedName) { name ->
            resources.resolveClassByName(name)?.superclass?.orElse(null)?.fullyQualifiedName
        }

    fun isOverridable(modifiers: Int): Boolean =
        !Modifier.isStatic(modifiers) && !Modifier.isPrivate(modifiers) && !Modifier.isFinal(modifiers)
}
