package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.NodeId
import sootup.core.jimple.common.Value
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.ValueNode
import org.objectweb.asm.Handle
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.constant.MethodHandle
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr
import sootup.core.jimple.common.expr.AbstractInvokeExpr
import sootup.core.jimple.common.expr.JDynamicInvokeExpr
import sootup.core.jimple.common.expr.JSpecialInvokeExpr
import sootup.core.jimple.common.ref.JFieldRef
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.signatures.MethodSignature

/** Emits ordinary and dynamic call sites, including dataflow and functional-dispatch bindings. */
internal class CallSiteEmitter(private val context: SootUpAdapter) {
    private val trackCrossMethodFunctionalDispatch = context.trackCrossMethodFunctionalDispatch
    private val interprocedural = context.interprocedural
    private val dynamicTargets = context.dynamicTargets
    private val slotAdapters = context.slotAdapters
    private var propagation
        get() = context.propagation
        set(value) { context.propagation = value }
    private val localeSpecsByLocal = context.localeSpecsByLocal
    private val resourceHandlePathsByLocal = context.resourceHandlePathsByLocal
    private val resourceBundlePaths = context.resourceBundlePaths
    private val bundleControlSpecsByLocal = context.bundleControlSpecsByLocal
    private var bodyResolvedCallees
        get() = context.bodyResolvedCallees
        set(value) { context.bodyResolvedCallees = value }
    private val untrackedDispatches = context.untrackedDispatches
    private val openLocals = context.openLocals
    private var bodyCallOrdinals
        get() = context.bodyCallOrdinals
        set(value) { context.bodyCallOrdinals = value }
    private val graphBuilder = context.graphBuilder
    private val bodies get() = context.bodies
    private val bundleControl get() = context.bundleControl
    private val callKinds get() = context.callKinds
    private val descriptors get() = context.descriptors
    private val dispatchFlows get() = context.dispatchFlows
    private val dispatchResolution get() = context.dispatchResolution
    private val localeValues get() = context.localeValues
    private val locales get() = context.locales
    private val metadata get() = context.metadata
    private val reachability get() = context.reachability
    private val resourceReads get() = context.resourceReads
    private val values get() = context.values

    fun processInvokeExpr(
        invokeExpr: AbstractInvokeExpr,
        caller: MethodDescriptor,
        resultNode: ValueNode?,
        stmt: Stmt? = null
    ) {
        val calleeSignature = bodyResolvedCallees[invokeExpr.methodSignature]
            ?: descriptors.resolveMethodDefiningClass(invokeExpr.methodSignature)
        val callee = descriptors.toMethodDescriptor(calleeSignature)
        val argNodeIds = callKinds.argumentNodeIds(invokeExpr.args, caller)
        if (!emitSpecialCall(invokeExpr, caller, resultNode, stmt, calleeSignature, argNodeIds)) {
            emitNormalCall(invokeExpr, caller, resultNode, stmt, calleeSignature, callee, argNodeIds)
        }
    }

    private fun emitSpecialCall(
        invokeExpr: AbstractInvokeExpr,
        caller: MethodDescriptor,
        resultNode: ValueNode?,
        stmt: Stmt?,
        calleeSignature: MethodSignature,
        argNodeIds: List<NodeId>
    ): Boolean {
        val args = invokeExpr.args
        return when {
            callKinds.isBoxingMethod(calleeSignature) && resultNode != null && argNodeIds.isNotEmpty() -> {
            graphBuilder.addEdge(
                DataFlowEdge(
                    from = argNodeIds[0],
                    to = resultNode.id,
                    kind = DataFlowKind.ASSIGN
                )
            )
            if (stmt != null && resultNode is LocalVariable && callKinds.isPrimitiveBoxingCall(calleeSignature, args)) {
                bodies.recordLocalWrite(stmt, resultNode.id, argNodeIds[0].value)
            }
            true
        }

        // Handle unboxing methods (Integer.intValue, Long.longValue, etc.)
        // The receiver object's value flows to the result
            callKinds.isUnboxingMethod(calleeSignature) && resultNode != null && invokeExpr is AbstractInstanceInvokeExpr -> {
            val baseNode = values.getOrCreateValueNode(invokeExpr.base, caller)
            if (baseNode != null) {
                graphBuilder.addEdge(
                    DataFlowEdge(
                        from = baseNode.id,
                        to = resultNode.id,
                        kind = DataFlowKind.ASSIGN
                    )
                )
            }
            true
        }

        // Handle invokedynamic (lambdas and method references)
        // Extract the actual target method from bootstrap arguments
            invokeExpr is JDynamicInvokeExpr -> {
            processDynamicInvoke(invokeExpr, caller, resultNode, stmt)
            true
        }

            else -> false
        }
    }

    private fun emitNormalCall(
        invokeExpr: AbstractInvokeExpr,
        caller: MethodDescriptor,
        resultNode: ValueNode?,
        stmt: Stmt?,
        calleeSignature: MethodSignature,
        callee: MethodDescriptor,
        argNodeIds: List<NodeId>
    ) {
        val args = invokeExpr.args
        if (calleeSignature.declClassType.fullyQualifiedName == LOCALE_CLASS && calleeSignature.name == INIT_METHOD) {
            val receiverLocal = (invokeExpr as? AbstractInstanceInvokeExpr)?.base as? Local
            val localeSpec = localeValues.extractConstructedLocaleSpec(invokeExpr)
            if (receiverLocal != null && localeSpec != null) {
                localeSpecsByLocal[metadata.localKey(caller, receiverLocal.name)] = localeSpec
            }
        }
        locales.updateLocaleBuilderState(caller, calleeSignature, invokeExpr, resultNode)

        // Extract receiver for instance method calls
        val receiverLocal = (invokeExpr as? AbstractInstanceInvokeExpr)?.base
        val receiverNode = if (receiverLocal != null) {
            values.getOrCreateValueNode(receiverLocal, caller)
        } else {
            null
        }

        // Create call site node
        val callSite = CallSiteNode(
            id = metadata.nextNodeId("call"),
            caller = caller,
            callee = callee,
            lineNumber = null, // SootUp may provide position info
            receiver = receiverNode?.id,
            arguments = argNodeIds,
            ordinal = stmt?.let(bodyCallOrdinals::get)
        )
        graphBuilder.addNode(callSite)
        if (stmt != null) {
            bodies.recordStmtNode(stmt, callSite.id)
        }
        val virtual = invokeExpr is AbstractInstanceInvokeExpr && invokeExpr !is JSpecialInvokeExpr
        interprocedural?.call(callee, virtual, argNodeIds, resultNode?.id)
        reachability.noteReachedCallee(callee, virtual, caller)
        val resourceRelevantCall = callKinds.isResourceRelevantCall(calleeSignature)
        linkCallResources(callSite, calleeSignature, invokeExpr, caller, resultNode, resourceRelevantCall)
        trackReceiverDispatch(invokeExpr, caller, resultNode, callSite, receiverLocal, receiverNode)
        emitCallDataflow(callSite, caller, resultNode, receiverNode, argNodeIds, args)
        recordCallResult(invokeExpr, caller, resultNode, calleeSignature, callee, resourceRelevantCall)
    }

    private fun linkCallResources(
        callSite: CallSiteNode,
        calleeSignature: MethodSignature,
        invokeExpr: AbstractInvokeExpr,
        caller: MethodDescriptor,
        resultNode: ValueNode?,
        resourceRelevantCall: Boolean
    ) {
        if (resourceRelevantCall) {
            resourceReads.linkResourceReads(callSite, calleeSignature, invokeExpr)
            resourceReads.linkResourceFileReads(callSite, calleeSignature, invokeExpr, caller)
            resourceReads.linkResourceBundleReads(callSite, calleeSignature, invokeExpr, caller)
            resourceReads.linkStructuredResourceLoads(callSite, calleeSignature, invokeExpr, caller)
            resourceReads.trackResourceAssociations(callSite, calleeSignature, invokeExpr, caller, resultNode)
        }

    }

    private fun trackReceiverDispatch(
        invokeExpr: AbstractInvokeExpr,
        caller: MethodDescriptor,
        resultNode: ValueNode?,
        callSite: CallSiteNode,
        receiverLocal: sootup.core.jimple.common.Value?,
        receiverNode: ValueNode?
    ) {
        val args = invokeExpr.args
        val callee = callSite.callee
        // Resolve functional interface dispatch: a call on a local holding a function value
        // (e.g. Function.apply, Function1.invoke) also calls the function value's implementation.
        // Targets known in this method resolve now; the call also waits on the receiver's slot for
        // targets that arrive from other methods (parameters, returns, fields).
        // Constructor and private calls (`invokespecial`) never dispatch.
        if (receiverLocal is Local && receiverNode is LocalVariable && invokeExpr !is JSpecialInvokeExpr) {
            val pending = PendingDispatch(
                callSite = callSite,
                result = resultNode?.id,
                argumentSlots = args.map { arg -> (arg as? Local)?.let { dispatchFlows.functionSlotOf(caller, it) } },
                resultSlot = (resultNode as? LocalVariable)?.let { DispatchSlot.Local(caller, it.name) },
                resolved = mutableSetOf()
            )
            dynamicTargets[metadata.localKey(caller, receiverLocal.name)].orEmpty()
                .filter(pending.resolved::add)
                .forEach { dispatchResolution.emitResolvedDispatch(pending, it) }
            if (dispatchFlows.mayHoldFunction(receiverNode.type)) {
                if (trackCrossMethodFunctionalDispatch) {
                    dispatchResolution.callsOn(dispatchResolution.slotId(dispatchFlows.seededSlotOf(caller, receiverLocal))).add(pending)
                } else {
                    val receiverKey = metadata.localKey(caller, receiverLocal.name)
                    untrackedDispatches += UntrackedDispatch(
                        pending,
                        lost = dynamicTargets[receiverKey] === SATURATED_TARGETS,
                        open = receiverKey in openLocals
                    )
                }
            }
        }
        if (invokeExpr is JSpecialInvokeExpr && callee.name == INIT_METHOD && caller.name == INIT_METHOD) {
            dispatchFlows.trackCallableReferenceReceiver(caller, callee, args)
        }

    }

    private fun emitCallDataflow(
        callSite: CallSiteNode,
        caller: MethodDescriptor,
        resultNode: ValueNode?,
        receiverNode: ValueNode?,
        argNodeIds: List<NodeId>,
        args: List<Value>
    ) {
        val callee = callSite.callee
        // Add dataflow edge from receiver to call site (for backward tracing)
        if (receiverNode != null) {
            graphBuilder.addEdge(
                DataFlowEdge(
                    from = receiverNode.id,
                    to = callSite.id,
                    kind = DataFlowKind.ASSIGN
                )
            )
        }

        // Add dataflow from arguments to parameters
        argNodeIds.forEachIndexed { index, argNodeId ->
            graphBuilder.addEdge(
                DataFlowEdge(
                    from = argNodeId,
                    to = callSite.id, // For now, flow to call site; will refine with call graph
                    kind = DataFlowKind.PARAMETER_PASS
                )
            )

            // Track function values passed as arguments for cross-method dispatch
            (args.getOrNull(index) as? Local)?.let { arg ->
                dispatchFlows.trackFlow(caller, arg, DispatchSlot.Parameter(callee, index))
            }
        }

        // If there's a result, add dataflow from call to result
        if (resultNode != null) {
            graphBuilder.addEdge(
                DataFlowEdge(
                    from = callSite.id,
                    to = resultNode.id,
                    kind = DataFlowKind.RETURN_VALUE
                )
            )
        }

    }

    private fun recordCallResult(
        invokeExpr: AbstractInvokeExpr,
        caller: MethodDescriptor,
        resultNode: ValueNode?,
        calleeSignature: MethodSignature,
        callee: MethodDescriptor,
        resourceRelevantCall: Boolean
    ) {
        // Track call result locals for return value propagation
        if (resultNode is LocalVariable) {
            val resultKey = metadata.localKey(caller, resultNode.name)
            if (resourceRelevantCall) {
                resourceReads.extractResourceLookupPath(caller, calleeSignature, invokeExpr)?.let {
                    resourceHandlePathsByLocal[resultKey] = linkedSetOf(it)
                }
                resourceReads.extractResourceBundlePaths(caller, calleeSignature, invokeExpr)?.let {
                    resourceBundlePaths[resultKey] = LinkedHashSet(it)
                }
            }
            localeValues.extractLocaleFactorySpec(calleeSignature, invokeExpr)?.let { localeSpecsByLocal[resultKey] = it }
            bundleControl.extractBundleControlSpec(caller, calleeSignature, invokeExpr)?.let { bundleControlSpecsByLocal[resultKey] = it }
            if (trackCrossMethodFunctionalDispatch && dispatchFlows.mayHoldFunction(resultNode.type)) {
                dispatchFlows.flowSlot(DispatchSlot.Return(callee), DispatchSlot.Local(caller, resultNode.name))
            } else if (!trackCrossMethodFunctionalDispatch && dispatchFlows.mayHoldFunction(resultNode.type)) {
                openLocals.add(resultKey)
            }
        }    }

    /**
     * Process an invokedynamic instruction (lambda or method reference).
     *
     * The bootstrap arguments contain MethodHandle objects that reference
     * the actual target method. For example:
     * - `handler::listUsers` -> MethodHandle pointing to `Handler.listUsers`
     * - `x -> x.getName()` -> MethodHandle pointing to a synthetic lambda method
     *
     * We create CallEdges to the actual target methods, enabling backward
     * tracing through lambdas and method references.
     */
    fun processDynamicInvoke(
        invokeExpr: JDynamicInvokeExpr,
        caller: MethodDescriptor,
        resultNode: ValueNode?,
        stmt: Stmt?
    ) {
        // Extract actual target method(s) from bootstrap arguments
        val handles = invokeExpr.bootstrapArgs
            .filterIsInstance<MethodHandle>()
            .filter { it.isMethodRef }
            .mapNotNull { handle ->
                val sig = handle.referenceSignature
                if (sig is MethodSignature) descriptors.toMethodDescriptor(sig) to dispatchFlows.handleKind(handle) else null
            }

        // Create argument nodes: the values the lambda or method reference captures
        val args = invokeExpr.args
        val argNodeIds = callKinds.argumentNodeIds(args, caller)
        val samName = invokeExpr.methodSignature.name
        val targets = mutableListOf<DispatchTarget>()
        val leftOp = (stmt as? JAssignStmt)?.leftOp
        val resultLocal = (leftOp as? Local)?.name ?: (resultNode as? LocalVariable)?.name
        val resultSlot = when {
            leftOp is JFieldRef -> DispatchSlot.Field(descriptors.fieldSlotKey(leftOp))
            resultLocal != null -> DispatchSlot.Local(caller, resultLocal)
            else -> null
        }

        // For each target method, create a call site
        for ((target, kind) in handles) {
            emitDynamicHandle(target, kind, args, argNodeIds, caller, resultNode, stmt)
            val handle = DispatchTarget.Handle(target, kind, samName, argNodeIds)
            targets += handle
            adaptBoundReceiver(caller, args, handle, resultSlot, targets)
        }
        storeDynamicTargets(caller, resultLocal, leftOp, targets)
        if (handles.isEmpty()) emitSyntheticDynamicCall(invokeExpr, caller, resultNode, stmt, argNodeIds)
    }

    private fun emitDynamicHandle(
        target: MethodDescriptor,
        kind: HandleKind,
        args: List<Value>,
        argNodeIds: List<NodeId>,
        caller: MethodDescriptor,
        resultNode: ValueNode?,
        stmt: Stmt?
    ) {
        // An instance handle takes its first captured value as the receiver, so the remaining
        // captures line up with the implementation's parameters
        val callSite = CallSiteNode(
            id = metadata.nextNodeId("call"),
            caller = caller,
            callee = target,
            lineNumber = null,
            receiver = if (kind == HandleKind.INSTANCE) argNodeIds.firstOrNull() else null,
            arguments = if (kind == HandleKind.INSTANCE) argNodeIds.drop(1) else argNodeIds,
            ordinal = descriptors.nextDerivedCallOrdinal(caller, target)
        )
        graphBuilder.addNode(callSite)
        if (stmt != null) {
            bodies.recordStmtNode(stmt, callSite.id)
        }
        reachability.noteReachedCallee(target, virtual = false, caller)

        // Add call edge (marked as dynamic)
        graphBuilder.addEdge(
            CallEdge(
                from = callSite.id,
                to = callSite.id, // Self-referencing for now; will be resolved with method entry nodes
                isVirtual = false,
                isDynamic = true
            )
        )

        // Dataflow from arguments to call site, and from the call to its result
        argNodeIds.forEach { graphBuilder.addEdge(DataFlowEdge(from = it, to = callSite.id, kind = DataFlowKind.PARAMETER_PASS)) }
        if (resultNode != null) {
            graphBuilder.addEdge(DataFlowEdge(from = callSite.id, to = resultNode.id, kind = DataFlowKind.RETURN_VALUE))
        }

        // Captured function values flow into the implementation's parameters, e.g. a Kotlin
        // lambda capturing another lambda compiles to `outer$lambda$1(Function1 inner, ...)`
        args.forEachIndexed { index, arg ->
            val paramIndex = if (kind == HandleKind.INSTANCE) index - 1 else index
            if (arg is Local && paramIndex >= 0) {
                dispatchFlows.trackFlow(caller, arg, DispatchSlot.Parameter(target, paramIndex))
            }
        }

    }

    private fun adaptBoundReceiver(
        caller: MethodDescriptor,
        args: List<Value>,
        handle: DispatchTarget.Handle,
        resultSlot: DispatchSlot?,
        targets: MutableList<DispatchTarget>
    ) {
        val target = handle.method
        val kind = handle.kind
        val samName = handle.samName
        val argNodeIds = handle.captures
        // A method reference to a function value's own method (`fn::apply`) dispatches on to
        // whatever that function value dispatches to: what is known in this method now, and
        // what reaches the receiver from other methods (a parameter, a field) in the fixpoint
        val boundReceiver = args.firstOrNull() as? Local
        if (kind == HandleKind.INSTANCE && boundReceiver != null) {
            dynamicTargets[metadata.localKey(caller, boundReceiver.name)]?.forEach { inner ->
                targets += DispatchTarget.Adapted(samName, target, argNodeIds, inner)
            }
            if (
                trackCrossMethodFunctionalDispatch && resultSlot != null &&
                dispatchFlows.mayHoldFunction(descriptors.toTypeDescriptor(boundReceiver.type))
            ) {
                val receiverId = dispatchResolution.slotId(dispatchFlows.slotOf(caller, boundReceiver))
                (slotAdapters.get(receiverId) ?: mutableListOf<SlotAdapter>().also { slotAdapters.put(receiverId, it) })
                    .add(SlotAdapter(resultSlot, samName, target, argNodeIds))
            }
        }
    }

    private fun storeDynamicTargets(
        caller: MethodDescriptor,
        resultLocal: String?,
        leftOp: Value?,
        targets: List<DispatchTarget>
    ) {
        // Track dynamic targets for functional interface dispatch resolution
        // Merge with existing targets (supports conditional assignment where both branches
        // assign different lambdas/method references to the same local)
        if (resultLocal != null && targets.isNotEmpty()) {
            val key = metadata.localKey(caller, resultLocal)
            dynamicTargets[key] = metadata.mergeTargets(dynamicTargets[key], targets)
        }

        // Track dynamic targets flowing directly to a field store:
        // e.g., this.mapper = invokedynamic(...) where resultNode is a FieldNode
        if (trackCrossMethodFunctionalDispatch && leftOp is JFieldRef && targets.isNotEmpty()) {
            dispatchFlows.seedSlot(DispatchSlot.Field(descriptors.fieldSlotKey(leftOp)), targets)
        }

    }

    private fun emitSyntheticDynamicCall(
        invokeExpr: JDynamicInvokeExpr,
        caller: MethodDescriptor,
        resultNode: ValueNode?,
        stmt: Stmt?,
        argNodeIds: List<NodeId>
    ) {
        val callee = descriptors.toMethodDescriptor(invokeExpr.methodSignature)
        val callSite = CallSiteNode(
            id = metadata.nextNodeId("call"),
            caller = caller,
            callee = callee,
            lineNumber = null,
            receiver = null,
            arguments = argNodeIds,
            ordinal = stmt?.let(bodyCallOrdinals::get)
        )
        graphBuilder.addNode(callSite)
        if (stmt != null) {
            bodies.recordStmtNode(stmt, callSite.id)
        }

        argNodeIds.forEach { argNodeId ->
            graphBuilder.addEdge(
                DataFlowEdge(
                    from = argNodeId,
                    to = callSite.id,
                    kind = DataFlowKind.PARAMETER_PASS
                )
            )
        }

        if (resultNode != null) {
            graphBuilder.addEdge(
                DataFlowEdge(
                    from = callSite.id,
                    to = resultNode.id,
                    kind = DataFlowKind.RETURN_VALUE
                )
            )
        }
    }
}
