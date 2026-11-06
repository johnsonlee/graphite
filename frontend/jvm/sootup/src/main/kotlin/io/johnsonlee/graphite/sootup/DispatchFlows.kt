package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.constant.MethodHandle

/** Tracks functional-value targets and flows across locals, parameters, fields and returns. */
internal class DispatchFlows(private val context: SootUpAdapter) {
    private val trackCrossMethodFunctionalDispatch = context.trackCrossMethodFunctionalDispatch
    private val dynamicTargets = context.dynamicTargets
    private val localToParamIndex = context.localToParamIndex
    private val arrayDynamicTargets = context.arrayDynamicTargets
    private val slotTargets = context.slotTargets
    private val flowFrom = context.flowFrom
    private val flowTo = context.flowTo
    private val lostSeeds = context.lostSeeds
    private val mayHoldFunctionByType = context.mayHoldFunctionByType
    private var propagation
        get() = context.propagation
        set(value) { context.propagation = value }
    private val openLocals = context.openLocals
    private val view = context.view
    private val descriptors get() = context.descriptors
    private val dispatchResolution get() = context.dispatchResolution
    private val functionObjects get() = context.functionObjects
    private val metadata get() = context.metadata
    private val resources get() = context.resources

    /**
     * A Kotlin bound callable reference class (`fn::invoke`, `obj::method`) hands its receiver
     * to the stdlib base constructor (`FunctionReferenceImpl(arity, receiver, owner, name,
     * signature, flags)`), which stores it in `CallableReference.receiver`; the reference's
     * `invoke` reads it back through the subclass's own field reference. The stdlib may be
     * outside the view, so the store is recorded here: every argument that may hold a function
     * value flows to the slot of the subclass's `receiver` field reference, resolved as the
     * read's is ([resolvedFieldSignature]).
     */
    fun trackCallableReferenceReceiver(caller: MethodDescriptor, superInit: MethodDescriptor, args: List<Value>) {
        if (!trackCrossMethodFunctionalDispatch || !isKotlinCallableReferenceBaseClass(superInit.declaringClass.className)) return
        val receiverField = view.identifierFactory.getFieldSignature(
            CALLABLE_REFERENCE_RECEIVER_FIELD,
            view.identifierFactory.getClassType(caller.declaringClass.className),
            JAVA_LANG_OBJECT
        )
        val slot = DispatchSlot.Field(descriptors.resolvedFieldSignature(receiverField).toString())
        args.filterIsInstance<Local>().forEach { trackFlow(caller, it, slot) }
    }

    fun handleKind(handle: MethodHandle): HandleKind = when (handle.kind) {
        MethodHandle.Kind.REF_INVOKE_STATIC, MethodHandle.Kind.REF_INVOKE_CONSTRUCTOR -> HandleKind.STATIC
        else -> HandleKind.INSTANCE
    }

    fun mergeLocalTargets(method: MethodDescriptor, local: Local, targets: Collection<DispatchTarget>) {
        val key = metadata.localKey(method, local.name)
        dynamicTargets[key] = metadata.mergeTargets(dynamicTargets[key], targets)
    }

    /** `target = source` (or a cast of it): [target] holds whatever function value [source] holds. */
    fun copyFunctionValue(method: MethodDescriptor, source: Local, target: Local) {
        val sourceKey = metadata.localKey(method, source.name)
        val targetKey = metadata.localKey(method, target.name)
        dynamicTargets[sourceKey]?.let { mergeLocalTargets(method, target, it) }
        arrayDynamicTargets[sourceKey]?.let { arrayDynamicTargets[targetKey] = metadata.mergeTargets(arrayDynamicTargets[targetKey], it) }
        if (trackCrossMethodFunctionalDispatch) {
            localToParamIndex[sourceKey]?.let { localToParamIndex[targetKey] = it }
            trackSlotFlow(seededSlotOf(method, source), method, target)
        } else {
            inheritOpenness(method, from = source, to = target)
        }
    }

    /** With cross-method tracking off, record that [local] holds a value its method cannot name when [open]. */
    fun markOpenLocal(method: MethodDescriptor, local: Local, open: Boolean) {
        if (!trackCrossMethodFunctionalDispatch && open && mayHoldFunction(descriptors.toTypeDescriptor(local.type))) {
            openLocals.add(metadata.localKey(method, local.name))
        }
    }

    /**
     * With cross-method tracking off, [to] holds what [from] holds (a copy, an element stored
     * into an array, an element loaded from one): open when [from] is, whatever its type says.
     */
    fun inheritOpenness(method: MethodDescriptor, from: Local, to: Local) {
        if (!trackCrossMethodFunctionalDispatch && metadata.localKey(method, from.name) in openLocals) {
            openLocals.add(metadata.localKey(method, to.name))
        }
    }

    /** The slot the value of [local] lives in across methods: its parameter, or the local itself. */
    fun slotOf(method: MethodDescriptor, local: Local): DispatchSlot {
        val parameter = localToParamIndex[metadata.localKey(method, local.name)]
        return if (parameter != null) {
            DispatchSlot.Parameter(parameter.method, parameter.index)
        } else {
            DispatchSlot.Local(method, local.name)
        }
    }

    /**
     * The function value in [local] flows to [sink]: its targets known in this method seed
     * [sink] now, and targets that reach [local]'s slot later follow it there.
     */
    fun trackFlow(method: MethodDescriptor, local: Local, sink: DispatchSlot) {
        if (!trackCrossMethodFunctionalDispatch) return
        val key = metadata.localKey(method, local.name)
        (dynamicTargets[key] ?: arrayDynamicTargets[key])?.let { seedSlot(sink, it) }
        if (mayHoldFunction(descriptors.toTypeDescriptor(local.type))) {
            flowSlot(seededSlotOf(method, local), sink)
        }
    }

    /** Whatever function value reaches [source] also reaches [target]. */
    fun trackSlotFlow(source: DispatchSlot, method: MethodDescriptor, target: Local) {
        if (trackCrossMethodFunctionalDispatch && mayHoldFunction(descriptors.toTypeDescriptor(target.type))) {
            flowSlot(source, DispatchSlot.Local(method, target.name))
        }
    }

    /**
     * [slotOf] [local], holding the function values this method knows [local] holds when the
     * slot is the local's own: a slot a flow leaves, or a call dispatches on, must carry what
     * its local carries, or it reads as a value from nowhere (see [computeOpenSlots]) although
     * the local was assigned a lambda two statements earlier. A local that aliases a parameter
     * is not seeded: the parameter's slot is every caller's, and what this method assigned the
     * local is not what the callers pass.
     */
    fun seededSlotOf(method: MethodDescriptor, local: Local): DispatchSlot {
        val slot = slotOf(method, local)
        if (slot is DispatchSlot.Local) {
            val key = metadata.localKey(method, local.name)
            (dynamicTargets[key] ?: arrayDynamicTargets[key])?.let { seedSlot(slot, it) }
        }
        return slot
    }

    /**
     * [slot] holds [targets] as well. A saturated holder ([SATURATED_TARGETS]) has lost what
     * it held, so [slot] gets nothing from it and is recorded as a slot targets were lost to.
     */
    fun seedSlot(slot: DispatchSlot, targets: Collection<DispatchTarget>) {
        if (targets === SATURATED_TARGETS) {
            lostSeeds.set(dispatchResolution.slotId(slot))
        } else if (targets.isNotEmpty()) {
            addTargets(dispatchResolution.slotId(slot), targets as? Set<DispatchTarget> ?: LinkedHashSet(targets))
        }
    }

    /**
     * Slot [id] holds [incoming] as well; true when that added a target. A slot with nothing
     * of its own takes [incoming] itself, and so does one whose targets [incoming] already
     * includes, so a slot that fans out to many others (an interface method's parameter
     * reaching the same parameter of every implementation, a result local reached by every
     * call on the interface) shares one set with them rather than filling a copy per slot;
     * only a slot where distinct sets meet gets a set of its own. Sets are never mutated.
     * A slot past [MAX_TARGETS] saturates, see [SATURATED_TARGETS], and nothing changes it after.
     */
    fun addTargets(id: Int, incoming: Set<DispatchTarget>): Boolean {
        val current = slotTargets[id]
        val merged = metadata.mergeTargets(current, incoming)
        if (merged === current) return false
        slotTargets[id] = merged
        return true
    }

    fun flowSlot(from: DispatchSlot, to: DispatchSlot) {
        if (from == to) return
        val fromId = dispatchResolution.slotId(from)
        val toId = dispatchResolution.slotId(to)
        val propagation = propagation
        if (propagation == null) {
            flowFrom.add(fromId)
            flowTo.add(toId)
        } else {
            propagation.addFlow(fromId, toId)
        }
    }

    /** [flowSlot], and have the fixpoint revisit [from] so targets it already holds follow the new flow. */
    fun flowSlotNow(from: DispatchSlot, to: DispatchSlot) {
        flowSlot(from, to)
        propagation?.pending?.set(dispatchResolution.slotId(from))
    }

    /** The slot [local]'s function value lives in, or null when its type cannot hold one. */
    fun functionSlotOf(method: MethodDescriptor, local: Local): DispatchSlot? =
        if (trackCrossMethodFunctionalDispatch && mayHoldFunction(descriptors.toTypeDescriptor(local.type))) slotOf(method, local) else null

    /**
     * Whether a value of [type] can hold a function value, which bounds what cross-method
     * dispatch tracking records: an interface, an abstract class, `Object` (erased generics), a
     * function-object class, an array of those, or a type outside the view (the JDK, the Kotlin
     * stdlib), but not a primitive, a common concrete JDK type or a concrete class in the view.
     */
    fun mayHoldFunction(type: TypeDescriptor): Boolean = mayHoldFunctionByType.getOrPut(type.className) {
        val className = type.className.substringBefore('[')
        if (isNonFunctionType(className)) return@getOrPut false
        val sootClass = resources.resolveClassByName(className) ?: return@getOrPut true
        sootClass.isInterface || sootClass.isAbstract || functionObjects.isFunctionObjectClass(className)
    }
}
