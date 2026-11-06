package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import it.unimi.dsi.fastutil.ints.IntArrayList
import java.util.BitSet
import sootup.core.jimple.common.Local

/** Propagates functional-value targets to a fixpoint and emits derived calls and gap evidence. */
internal class DispatchResolution(private val context: SootUpAdapter) {
    private val trackCrossMethodFunctionalDispatch = context.trackCrossMethodFunctionalDispatch
    private val interprocedural = context.interprocedural
    private val dynamicTargets = context.dynamicTargets
    private val arrayDynamicTargets = context.arrayDynamicTargets
    private val slotIds = context.slotIds
    private val slots = context.slots
    private val slotTargets = context.slotTargets
    private val flowFrom = context.flowFrom
    private val flowTo = context.flowTo
    private val slotAdapters = context.slotAdapters
    private val slotCalls = context.slotCalls
    private var openSlots
        get() = context.openSlots
        set(value) { context.openSlots = value }
    private var lostSlots
        get() = context.lostSlots
        set(value) { context.lostSlots = value }
    private val lostSeeds = context.lostSeeds
    private val resolvedFields = context.resolvedFields
    private val functionObjectClasses = context.functionObjectClasses
    private val mayHoldFunctionByType = context.mayHoldFunctionByType
    private val transitiveSubtypesByClass = context.transitiveSubtypesByClass
    private val supertypesByClass = context.supertypesByClass
    private val declaredMethodIndexByClass = context.declaredMethodIndexByClass
    private val sortedMethodsByClass = context.sortedMethodsByClass
    private val supertypeContractsByClass = context.supertypeContractsByClass
    private val bridgesInvokingByClass = context.bridgesInvokingByClass
    private val implementedByFunctionValue = context.implementedByFunctionValue
    private var propagation
        get() = context.propagation
        set(value) { context.propagation = value }
    private val nestedDispatches = context.nestedDispatches
    private val openLocals = context.openLocals
    private val graphBuilder = context.graphBuilder
    private val calls get() = context.calls
    private val descriptors get() = context.descriptors
    private val dispatchFlows get() = context.dispatchFlows
    private val dispatchHierarchy get() = context.dispatchHierarchy
    private val metadata get() = context.metadata
    private val reachability get() = context.reachability

    /**
     * Drop the dispatch slots, their targets and waiting calls, and the hierarchy caches they
     * were resolved against, once every dispatch has become call sites, so that on a large APK
     * they do not stay live through the call graph and `graphBuilder.build()`.
     */
    fun releaseFunctionalDispatchState() {
        slotIds.clear()
        slotIds.trim()
        slots.clear()
        slots.trimToSize()
        slotTargets.clear()
        slotTargets.trimToSize()
        slotAdapters.clear()
        slotAdapters.trim()
        slotCalls.clear()
        slotCalls.trim()
        nestedDispatches.clear()
        dynamicTargets.clear()
        arrayDynamicTargets.clear()
        openLocals.clear()
        lostSeeds.clear()
        resolvedFields.clear()
        functionObjectClasses.clear()
        mayHoldFunctionByType.clear()
        transitiveSubtypesByClass.clear()
        supertypesByClass.clear()
        declaredMethodIndexByClass.clear()
        sortedMethodsByClass.clear()
        supertypeContractsByClass.clear()
        bridgesInvokingByClass.clear()
        implementedByFunctionValue.clear()
    }

    /**
     * Post-processing: resolve functional interface dispatch across method boundaries.
     *
     * Function values move between methods through parameters, return values, fields and
     * array elements, and a method may be processed before the one that creates the function
     * value it calls. Every such move was recorded as a flow between [DispatchSlot]s; this
     * propagates the dispatch targets along those flows to a fixpoint, then resolves every call
     * that was waiting on a slot, e.g. `callback.apply(x)` inside a method whose `callback`
     * parameter receives `Foo::transform`, or `this.fn.invoke(x)` whose field a constructor
     * assigned a Kotlin lambda.
     */
    fun resolveFunctionalDispatch() {
        val propagation = SlotPropagation(slots.size, flowFrom, flowTo)
        flowFrom.clear()
        flowTo.clear()
        this.propagation = propagation
        for (id in slotTargets.indices) {
            if (slotTargets[id] != null) propagation.pending.set(id)
        }
        // Resolving a call connects its arguments and result to the implementation's parameter
        // and return slots, which may carry further function values: alternate until nothing
        // new is resolved
        do {
            propagateSlotTargets(propagation)
        } while (emitPendingDispatches())
    }

    /**
     * Include flows and targets added by the post-dispatch dependency expansion in the
     * completeness evidence. Propagate them without emitting further calls: newly discovered
     * alternatives remain gaps, as the bounded expansion contract requires. Retain the
     * existing flow graph so late writes can invalidate an earlier call's completeness too.
     */
    fun finalizeDispatchEvidence(lateClassesRead: Boolean) {
        val propagation = propagation ?: return
        if (lateClassesRead) {
            for (id in slotTargets.indices) {
                if (slotTargets[id] != null) propagation.pending.set(id)
            }
            propagateSlotTargets(propagation)
        }
        computeOpenSlots(propagation)
        this.propagation = null
    }

    /**
     * The slots a value with no known origin in the analysed scope may reach: a slot nothing
     * flows into and nothing seeds (a parameter no known caller passes a value to, the result
     * of a method outside the scope, a field nothing in the scope assigns) holds such a value,
     * and so does every slot it flows into, through the flows recorded while methods were read,
     * the flows resolution added and the adapters. A call on such a slot that also resolved to
     * bodies is partial: the bodies are in the graph, and an alternative the scope cannot name
     * may run too. A saturated slot lost its targets rather than never having any, and so did
     * every slot it flows into and every slot it seeded ([lostSeeds]): a call on one of those
     * that resolved bodies is partial as well, and one that resolved none is saturated, not
     * unresolved. One pass over the final flow graph, after the fixpoint.
     */
    fun computeOpenSlots(propagation: SlotPropagation) {
        val count = slots.size
        val hasIncoming = BitSet(count)
        for (id in 0 until count) {
            propagation.forEachFlow(id) { next -> hasIncoming.set(next) }
            slotAdapters.get(id)?.forEach { hasIncoming.set(slotId(it.sink)) }
        }
        val open = BitSet(count)
        val lost = BitSet(count)
        lost.or(lostSeeds)
        for (id in 0 until count) {
            val targets = slotTargets[id]
            when {
                targets === SATURATED_TARGETS -> lost.set(id)
                !hasIncoming.get(id) && targets.isNullOrEmpty() -> open.set(id)
            }
        }
        openSlots = closeOverFlows(open, propagation)
        lostSlots = closeOverFlows(lost, propagation)
    }

    /** Record the flows the override boundaries of slot [id] imply, once per slot, see [overrideFlows]. */
    fun expandOverrideFlows(id: Int, propagation: SlotPropagation) {
        if (!propagation.expanded.get(id)) {
            propagation.expanded.set(id)
            dispatchHierarchy.overrideFlows(slots[id]).forEach { propagation.addFlow(id, slotId(it)) }
        }
    }

    /**
     * [seeds] and every slot one of them flows into, through the recorded flows, the override
     * boundaries (an interface parameter nothing known reaches still reaches every
     * implementation's parameter, so the flows the fixpoint never needed are derived here) and
     * the adapters.
     */
    fun closeOverFlows(seeds: BitSet, propagation: SlotPropagation): BitSet {
        val queue = IntArrayList()
        var seed = seeds.nextSetBit(0)
        while (seed >= 0) {
            queue.add(seed)
            seed = seeds.nextSetBit(seed + 1)
        }
        fun mark(next: Int) {
            if (!seeds.get(next)) {
                seeds.set(next)
                queue.add(next)
            }
        }
        while (!queue.isEmpty) {
            val id = queue.popInt()
            expandOverrideFlows(id, propagation)
            propagation.forEachFlow(id, ::mark)
            slotAdapters.get(id)?.forEach { mark(slotId(it.sink)) }
        }
        return seeds
    }

    /**
     * Sweep the slots in flow order, pushing the targets of each pending one along its flows,
     * until a sweep leaves nothing pending behind it: a flow to a slot swept earlier (a cycle,
     * a flow found during the sweep) is picked up by the next sweep.
     */
    fun propagateSlotTargets(propagation: SlotPropagation) {
        var again = true
        while (again) {
            again = sweepSlots(propagation)
        }
    }

    /** One sweep; true when a slot already passed changed and the next sweep is needed. */
    fun sweepSlots(propagation: SlotPropagation): Boolean {
        var backward = false
        var index = 0
        // Slots numbered during the sweep (override flows, adapter sinks) are swept at its end
        while (index < slots.size) {
            val id = propagation.slotAt(index)
            if (propagation.pending.get(id)) {
                propagation.pending.clear(id)
                if (propagateSlot(id, propagation)) backward = true
            }
            index++
        }
        return backward
    }

    /**
     * Push [id]'s targets along its flows and adapters; true when that changed a slot the
     * current sweep has passed (or [id] itself, `fn = fn::apply`), which the next sweep handles.
     * A saturated slot (see [SATURATED_TARGETS]) passes nothing on.
     */
    fun propagateSlot(id: Int, propagation: SlotPropagation): Boolean {
        val targets = slotTargets[id]?.takeIf { it.isNotEmpty() } ?: return false
        expandOverrideFlows(id, propagation)
        var backward = false
        fun deliver(next: Int, incoming: Set<DispatchTarget>) {
            if (dispatchFlows.addTargets(next, incoming)) {
                propagation.pending.set(next)
                if (propagation.position(next) <= propagation.position(id)) backward = true
            }
        }
        propagation.forEachFlow(id) { next -> deliver(next, targets) }
        slotAdapters.get(id)?.forEach { adapter ->
            val adapted = targets.mapNotNullTo(LinkedHashSet(), adapter::adapt)
            if (adapted.isNotEmpty()) deliver(slotId(adapter.sink), adapted)
        }
        return backward
    }

    /**
     * Resolve every waiting call against the targets its slot now holds, then register the
     * calls that resolution itself produced; true if anything changed.
     */
    fun emitPendingDispatches(): Boolean {
        var emitted = false
        for (entry in slotCalls.int2ObjectEntrySet()) {
            val targets = slotTargets[entry.intKey] ?: continue
            for (pending in entry.value.toList()) {
                val fresh = targets.filter(pending.resolved::add)
                fresh.forEach { emitResolvedDispatch(pending, it) }
                emitted = emitted || fresh.isNotEmpty()
            }
        }
        val nested = nestedDispatches.toList()
        nestedDispatches.clear()
        nested.forEach { (slot, pending) -> callsOn(slotId(slot)).add(pending) }
        return emitted || nested.isNotEmpty()
    }

    fun callsOn(id: Int): MutableList<PendingDispatch> =
        slotCalls.get(id) ?: mutableListOf<PendingDispatch>().also { slotCalls.put(id, it) }

    /** The number of [slot], assigned on first use; numbers index [slots] and [slotTargets]. */
    fun slotId(slot: DispatchSlot): Int {
        val known = slotIds.getInt(slot)
        if (known >= 0) return known
        val id = slots.size
        slotIds.put(slot, id)
        slots.add(slot)
        slotTargets.add(null)
        return id
    }

    /**
     * Create the call site through which [callSite], a call on a function value, reaches
     * [target]'s implementation, with the receiver and arguments aligned to that
     * implementation's parameters. Does nothing when [target] does not implement the call.
     */
    fun emitResolvedDispatch(pending: PendingDispatch, target: DispatchTarget) {
        val callSite = pending.callSite
        val result = pending.result
        val resolved = dispatchHierarchy.resolveDispatch(target, callSite.callee, callSite.receiver, callSite.arguments,
            pending.argumentSlots) ?: return
        val resolvedCallSite = CallSiteNode(
            id = metadata.nextNodeId("call"),
            caller = callSite.caller,
            callee = resolved.method,
            lineNumber = callSite.lineNumber,
            receiver = resolved.receiver,
            arguments = resolved.arguments,
            ordinal = descriptors.nextDerivedCallOrdinal(callSite.caller, resolved.method),
            origin = callSite.id
        )
        graphBuilder.addNode(resolvedCallSite)
        interprocedural?.call(resolved.method, false, resolved.arguments, result)
        reachability.noteReachedCallee(resolved.method, virtual = false, callSite.caller)
        // Both the call on the function value and the resolved call are dynamic dispatch
        graphBuilder.addEdge(CallEdge(from = callSite.id, to = callSite.id, isVirtual = false, isDynamic = true))
        graphBuilder.addEdge(CallEdge(from = resolvedCallSite.id, to = resolvedCallSite.id, isVirtual = false, isDynamic = true))
        // Forward dataflow: arguments (and captured values) flow to the resolved target
        resolved.arguments.forEach { argNodeId ->
            graphBuilder.addEdge(DataFlowEdge(from = argNodeId, to = resolvedCallSite.id, kind = DataFlowKind.PARAMETER_PASS))
        }
        if (result != null) {
            graphBuilder.addEdge(DataFlowEdge(from = resolvedCallSite.id, to = result, kind = DataFlowKind.RETURN_VALUE))
        }
        // The call now has a callee: function values among its arguments reach the
        // implementation's parameters, and what the implementation returns reaches the result
        if (trackCrossMethodFunctionalDispatch) {
            resolved.argumentSlots.forEachIndexed { index, slot ->
                if (slot != null) dispatchFlows.flowSlotNow(slot, DispatchSlot.Parameter(resolved.method, index))
            }
            pending.resultSlot?.let { dispatchFlows.flowSlotNow(DispatchSlot.Return(resolved.method), it) }
        }
        // An unbound reference to a function value's own method (`Function::apply`) resolves
        // to a call whose receiver is itself a function value: that call dispatches in turn
        resolved.receiverSlot?.let { receiverSlot ->
            val nested = PendingDispatch(resolvedCallSite, result, resolved.argumentSlots, pending.resultSlot, mutableSetOf())
            val known = (receiverSlot as? DispatchSlot.Local)?.takeIf { it.method == callSite.caller }
                ?.let { dynamicTargets[metadata.localKey(it.method, it.name)] }.orEmpty()
            known.filter(nested.resolved::add).forEach { emitResolvedDispatch(nested, it) }
            if (trackCrossMethodFunctionalDispatch) {
                nestedDispatches += receiverSlot to nested
            }
        }
    }
}
