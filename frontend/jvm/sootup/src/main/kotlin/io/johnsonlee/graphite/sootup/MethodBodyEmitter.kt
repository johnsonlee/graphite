package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.analysis.DispatchSlot

import io.johnsonlee.graphite.input.JvmMethodIdentity

import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.ConstantNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.ValueNode
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.ArtifactRole
import java.util.IdentityHashMap
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.constant.StringConstant as SootStringConstant
import sootup.core.jimple.common.expr.AbstractInvokeExpr
import sootup.core.jimple.common.expr.JCastExpr
import sootup.core.jimple.common.expr.JNewExpr
import sootup.core.jimple.common.ref.JFieldRef
import sootup.core.jimple.common.ref.JStaticFieldRef
import sootup.core.jimple.common.ref.JArrayRef
import sootup.core.jimple.common.ref.JParameterRef
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JIdentityStmt
import sootup.core.jimple.common.stmt.JIfStmt
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.core.jimple.common.stmt.JReturnStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.model.Body
import sootup.core.model.SootMethod
import io.johnsonlee.graphite.sootup.SootUpAdapter.LocaleBuilderSpec
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.MAX_CONTROL_FLOW_STATEMENTS

/** Translates Jimple bodies into graph nodes, assignments and method-local state. */
internal class MethodBodyEmitter(private val context: SootUpAdapter) {
    private val trackCrossMethodFunctionalDispatch = context.trackCrossMethodFunctionalDispatch
    private val interprocedural = context.interprocedural
    private val parameterNodes = context.parameterNodes
    private val methodReturnNodes = context.methodReturnNodes
    private val allocationNodes = context.allocationNodes
    private val dynamicTargets = context.dynamicTargets
    private val localToParamIndex = context.localToParamIndex
    private val arrayDynamicTargets = context.arrayDynamicTargets
    private val localeSpecsByLocal = context.localeSpecsByLocal
    private val localeBuilderSpecsByLocal = context.localeBuilderSpecsByLocal
    private val stringValuesByLocal = context.stringValuesByLocal
    private val bundleControlFormatsByLocal = context.bundleControlFormatsByLocal
    private val resourceHandlePathsByLocal = context.resourceHandlePathsByLocal
    private val propertiesPathsByLocal = context.propertiesPathsByLocal
    private val resourceBundlePaths = context.resourceBundlePaths
    private val bundleControlSpecsByLocal = context.bundleControlSpecsByLocal
    private val renderedDefiningClass = context.renderedDefiningClass
    private var bodyResolvedCallees
        get() = context.bodyResolvedCallees
        set(value) { context.bodyResolvedCallees = value }
    private var activeControlFlowTruncated
        get() = context.activeControlFlowTruncated
        set(value) { context.activeControlFlowTruncated = value }
    private val syntheticIdentities = context.syntheticIdentities
    private var activeSyntheticClass
        get() = context.activeSyntheticClass
        set(value) { context.activeSyntheticClass = value }
    private var stmtNodeIds
        get() = context.stmtNodeIds
        set(value) { context.stmtNodeIds = value }
    private var stmtLocalWrites
        get() = context.stmtLocalWrites
        set(value) { context.stmtLocalWrites = value }
    private var stmtIdentityWrites
        get() = context.stmtIdentityWrites
        set(value) { context.stmtIdentityWrites = value }
    private var activeMethod
        get() = context.activeMethod
        set(value) { context.activeMethod = value }
    private var activeMethodLocals
        get() = context.activeMethodLocals
        set(value) { context.activeMethodLocals = value }
    private var bodyCallOrdinals
        get() = context.bodyCallOrdinals
        set(value) { context.bodyCallOrdinals = value }
    private var activeLocalKeysByName
        get() = context.activeLocalKeysByName
        set(value) { context.activeLocalKeysByName = value }
    private var activeMethodParameters
        get() = context.activeMethodParameters
        set(value) { context.activeMethodParameters = value }
    private var activeParameterBindingsByIndex
        get() = context.activeParameterBindingsByIndex
        set(value) { context.activeParameterBindingsByIndex = value }
    private val graphBuilder = context.graphBuilder
    private val preFoldOrdinals = context.preFoldOrdinals
    private val bundleControl get() = context.bundleControl
    private val callKinds get() = context.callKinds
    private val calls get() = context.calls
    private val completeness get() = context.completeness
    private val controlFlow get() = context.controlFlow
    private val declarations get() = context.declarations
    private val descriptors get() = context.descriptors
    private val dispatchFlows get() = context.dispatchFlows
    private val functionObjects get() = context.functionObjects
    private val localeValues get() = context.localeValues
    private val metadata get() = context.metadata
    private val methodSources get() = context.methodSources
    private val values get() = context.values

    fun processMethod(method: SootMethod) {
        val methodDescriptor = descriptors.toMethodDescriptor(method)
        graphBuilder.addMethod(methodDescriptor)

        // Create return node for this method
        val returnNode = ReturnNode(
            id = metadata.nextNodeId("return"),
            method = methodDescriptor
        )
        methodReturnNodes[methodDescriptor] = returnNode
        graphBuilder.addNode(returnNode)
        interprocedural?.method(methodDescriptor, returnNode.id)

        activeMethod = methodDescriptor
        activeMethodLocals = mutableListOf()
        bodyCallOrdinals = emptyMap()
        activeLocalKeysByName = mutableMapOf()
        activeMethodParameters = mutableListOf()
        activeParameterBindingsByIndex = mutableMapOf()
        activeControlFlowTruncated = false
        var status = AnalysisStatus.ANALYZED
        var failure: String? = null
        var analyzedBody: Body? = null

        try {
            // Process method body if available
            if (method.hasBody()) {
                analyzedBody = method.body
                processMethodBody(method, methodDescriptor, analyzedBody)
                if (activeControlFlowTruncated) status = AnalysisStatus.ANALYZED_WITHOUT_CONTROL_FLOW
            } else {
                status = when {
                    method.isAbstract -> AnalysisStatus.ABSTRACT
                    method.isNative -> AnalysisStatus.NATIVE
                    else -> AnalysisStatus.NO_BODY
                }
            }
            // Rendered here, while the body is materialised, rather than in a pass of its own:
            // streamed methods are rebuilt on every enumeration.
            val syntheticMethod = SyntheticIdentity.isSyntheticMethod(method)
            if (activeSyntheticClass || syntheticMethod) {
                syntheticIdentities.addMethod(method, methodDescriptor.signature, syntheticMethod)
            }
        } catch (oom: OutOfMemoryError) {
            // Android/large corpus can contain a few pathological methods whose CFG
            // materialization explodes heap. Skip the offending method so graph build
            // can continue instead of failing the entire load; the method's nodes exist, so
            // the evidence says its body is incomplete.
            declarations.log { "Skipping method due to OOM while building ${methodDescriptor.signature}: ${oom.message}" }
            status = AnalysisStatus.INCOMPLETE
            failure = oom.message ?: oom.javaClass.name

        } finally {
            completeness.recordMethodEvidence(methodDescriptor, status, failure)
            metadata.clearMethodState(methodDescriptor)
            methodSources.releaseConversionState(method)
        }
        context.bodyDelivery?.let { delivery ->
            val className = methodDescriptor.declaringClass.className
            val owner = checkNotNull(context.classesByNameCache?.get(className))
            val origin = context.construction.evidenceOrigin(owner)
            val role = if (origin == null) null else context.artifactRoles[origin]
                ?: if (origin == context.singleArtifactSource) ArtifactRole.APPLICATION else ArtifactRole.LIBRARY
            delivery.deliver(createMethodObservationEvidence(
                JvmMethodIdentity.of(methodDescriptor), method.modifiers, origin, role,
                className in context.applicationClasses, status, failure,
                context.methodRecovery(method.signature), context.methodFoldObservation(method.signature)
            ), analyzedBody, className in context.duplicateClasses)
        }
    }

    fun processMethodBody(method: SootMethod, methodDescriptor: MethodDescriptor, body: Body) {
        val stmtGraph = body.controlFlowGraph
        val statements = stmtGraph.stmts
        // A body a rule folded in keeps the numbering it had before the fold, so a surviving call's
        // ordinal is the one the unfolded graph gave it; every other body is numbered as it stands.
        bodyResolvedCallees = IdentityHashMap(statements.size)
        bodyCallOrdinals = preFoldOrdinals(method.signature) ?: callOrdinals(statements) { signature ->
            val resolved = descriptors.resolveMethodDefiningClass(signature)
            bodyResolvedCallees[signature] = resolved
            renderedDefiningClass.getOrPut(resolved) { renderSignature(resolved) }
        }

        // Reset per-method stmt tracking
        stmtNodeIds = identityMutableMap()
        stmtLocalWrites = identityMutableMap()
        stmtIdentityWrites = identityMutableMap()

        // Process parameters
        processParameters(method, methodDescriptor)

        var stmtCount = 0
        var branchStatements: MutableList<JIfStmt>? = null
        var hasBranch = false
        for (stmt in statements) {
            processStatement(stmt, methodDescriptor)
            if (stmt is JIfStmt) {
                hasBranch = true
                if (stmtCount < MAX_CONTROL_FLOW_STATEMENTS) {
                    branchStatements = (branchStatements ?: mutableListOf()).also { it.add(stmt) }
                }
            }
            stmtCount++
        }

        if (!hasBranch) return
        if (stmtCount <= MAX_CONTROL_FLOW_STATEMENTS) {
            controlFlow.processControlFlow(branchStatements ?: emptyList(), stmtGraph, statements, methodDescriptor)
        } else {
            activeControlFlowTruncated = true
            declarations.log {
                "Skipping control-flow extraction for ${methodDescriptor.signature}: " +
                    "$stmtCount statements exceed $MAX_CONTROL_FLOW_STATEMENTS"
            }
        }
    }

    /**
     * Record that a node was created from a given statement.
     */
    fun recordStmtNode(stmt: Stmt, nodeId: NodeId) {
        stmtNodeIds.getOrPut(stmt) { IntArrayBuilder(1) }.add(nodeId.value)
    }

    /**
     * Record that [stmt] writes the local [localId]; [constantId] is the constant node
     * when the value is a constant (recorded at the same point as its ASSIGN edge, so
     * the two stay one-to-one) and [BranchScope.NO_CONSTANT] otherwise.
     */
    fun recordLocalWrite(stmt: Stmt, localId: NodeId, constantId: Int) {
        stmtLocalWrites[stmt] = intArrayOf(localId.value, constantId)
    }

    fun processParameters(method: SootMethod, methodDescriptor: MethodDescriptor) {
        val nodes = method.parameterTypes.mapIndexed { index, paramType ->
            val paramNode = ParameterNode(
                id = metadata.nextNodeId("param"),
                index = index,
                type = descriptors.toTypeDescriptor(paramType),
                method = methodDescriptor
            )
            parameterNodes[metadata.parameterBinding(methodDescriptor, index)] = paramNode
            graphBuilder.addNode(paramNode)
            paramNode.id
        }
        interprocedural?.parameters(methodDescriptor, nodes)
    }

    fun processStatement(stmt: Stmt, method: MethodDescriptor) {
        when (stmt) {
            is JAssignStmt -> processAssignment(stmt, method)
            is JIdentityStmt -> processIdentity(stmt, method)
            is JInvokeStmt -> processInvoke(stmt, method)
            is JReturnStmt -> controlFlow.processReturn(stmt, method)
            // JIfStmt is handled in processControlFlow (pass 2)
        }
    }

    fun processAssignment(stmt: JAssignStmt, method: MethodDescriptor) {
        if (allocateLocal(stmt, method)) return
        val leftOp = stmt.leftOp
        val rightOp = stmt.rightOp
        val targetNode = values.getOrCreateValueNode(leftOp, method)
        val sourceNode = values.getOrCreateValueNode((rightOp as? JCastExpr)?.op ?: rightOp, method)
        recordAssignment(stmt, targetNode, sourceNode)
        emitAssignmentFlow(stmt, targetNode, sourceNode)
        trackFunctionValueAssignment(stmt, method, targetNode)
        copyTrackedValues(leftOp, rightOp, method)
        if (rightOp is AbstractInvokeExpr) {
            calls.processInvokeExpr(rightOp, method, targetNode, stmt)
        }
    }

    private fun allocateLocal(stmt: JAssignStmt, method: MethodDescriptor): Boolean {
        val leftOp = stmt.leftOp
        val rightOp = stmt.rightOp
        // Handle new expressions: x = new Foo()
        // Create a LocalVariable with the correct type from the new expression
        if (rightOp is JNewExpr && leftOp is Local) {
            val allocType = descriptors.toTypeDescriptor(rightOp.type)
            val allocNode = values.getOrCreateLocalWithType(leftOp, method, allocType)
            allocationNodes[metadata.localKey(method, leftOp.name)] = allocNode
            if (callKinds.isLocaleBuilderClassName(allocType.className)) {
                localeBuilderSpecsByLocal[metadata.localKey(method, leftOp.name)] = LocaleBuilderSpec()
            }
            recordStmtNode(stmt, allocNode.id)
            functionObjects.trackFunctionObject(method, leftOp, allocType.className, allocNode.id, stmt)
            recordLocalWrite(stmt, allocNode.id, BranchScope.NO_CONSTANT)
            return true
        }

        return false
    }

    private fun recordAssignment(stmt: JAssignStmt, targetNode: ValueNode?, sourceNode: ValueNode?) {
        val leftOp = stmt.leftOp
        if (targetNode != null) {
            recordStmtNode(stmt, targetNode.id)
            if (leftOp is Local) {
                val constantId = if (sourceNode is ConstantNode) sourceNode.id.value else BranchScope.NO_CONSTANT
                recordLocalWrite(stmt, targetNode.id, constantId)
            }
        }

    }

    private fun emitAssignmentFlow(stmt: JAssignStmt, targetNode: ValueNode?, sourceNode: ValueNode?) {
        val leftOp = stmt.leftOp
        val rightOp = stmt.rightOp
        if (targetNode != null && sourceNode != null) {
            val kind = when {
                leftOp is JFieldRef -> DataFlowKind.FIELD_STORE
                leftOp is JArrayRef -> DataFlowKind.ARRAY_STORE
                rightOp is JFieldRef -> DataFlowKind.FIELD_LOAD
                rightOp is JArrayRef -> DataFlowKind.ARRAY_LOAD
                else -> DataFlowKind.ASSIGN
            }

            graphBuilder.addEdge(
                DataFlowEdge(
                    from = sourceNode.id,
                    to = targetNode.id,
                    kind = kind
                )
            )
        }

    }

    private fun copyTrackedValues(leftOp: Value, rightOp: Value, method: MethodDescriptor) {
        if (leftOp is Local) {
            val targetKey = metadata.localKey(method, leftOp.name)
            when (rightOp) {
                is SootStringConstant -> stringValuesByLocal[targetKey] = rightOp.value
            }
            when (rightOp) {
                is Local -> {
                    val sourceKey = metadata.localKey(method, rightOp.name)
                    stringValuesByLocal[sourceKey]?.let { stringValuesByLocal[targetKey] = it }
                    localeSpecsByLocal[sourceKey]?.let { localeSpecsByLocal[targetKey] = it }
                    localeBuilderSpecsByLocal[sourceKey]?.let { localeBuilderSpecsByLocal[targetKey] = it }
                    bundleControlFormatsByLocal[sourceKey]?.let { bundleControlFormatsByLocal[targetKey] = it }
                    resourceHandlePathsByLocal[sourceKey]?.let { resourceHandlePathsByLocal[targetKey] = LinkedHashSet(it) }
                    propertiesPathsByLocal[sourceKey]?.let { propertiesPathsByLocal[targetKey] = LinkedHashSet(it) }
                    resourceBundlePaths[sourceKey]?.let { resourceBundlePaths[targetKey] = LinkedHashSet(it) }
                    bundleControlSpecsByLocal[sourceKey]?.let { bundleControlSpecsByLocal[targetKey] = it }
                }
                is JStaticFieldRef -> {
                    localeValues.extractLocaleSpec(method, rightOp)?.let { localeSpecsByLocal[targetKey] = it }
                    bundleControl.extractControlFormat(method, rightOp)?.let { bundleControlFormatsByLocal[targetKey] = it }
                }
            }
        }

    }

    /** Follow function values through an assignment: field and array stores and loads, copies and casts. */
    fun trackFunctionValueAssignment(stmt: JAssignStmt, method: MethodDescriptor, targetNode: ValueNode?) {
        val leftOp = stmt.leftOp
        val rightOp = stmt.rightOp
        // Track function values stored to fields: `this.callback = fn`
        if (leftOp is JFieldRef && rightOp is Local) {
            dispatchFlows.trackFlow(method, rightOp, DispatchSlot.Field(descriptors.fieldSlotKey(leftOp)))
        }

        // Track function values stored into array elements (varargs): `fns[0] = fn`
        if (leftOp is JArrayRef && rightOp is Local) {
            val base = leftOp.base
            dynamicTargets[metadata.localKey(method, rightOp.name)]?.let { targets ->
                val arrayKey = metadata.localKey(method, base.name)
                arrayDynamicTargets[arrayKey] = metadata.mergeTargets(arrayDynamicTargets[arrayKey], targets)
            }
            dispatchFlows.trackFlow(method, rightOp, dispatchFlows.slotOf(method, base))
            dispatchFlows.inheritOpenness(method, from = rightOp, to = base)
        }

        if (leftOp is Local && targetNode is LocalVariable) {
            trackFunctionValueLoad(stmt, method, leftOp, targetNode)
        }
    }

    /** Follow function values into a local: array and field loads, `INSTANCE` singletons, copies and casts. */
    fun trackFunctionValueLoad(stmt: JAssignStmt, method: MethodDescriptor, leftOp: Local, targetNode: LocalVariable) {
        when (val rightOp = stmt.rightOp) {
            // Array element loads: `fn = fns[0]`
            is JArrayRef -> {
                val arrayKey = metadata.localKey(method, rightOp.base.name)
                arrayDynamicTargets[arrayKey]?.let { targets -> dispatchFlows.mergeLocalTargets(method, leftOp, targets) }
                dispatchFlows.trackSlotFlow(dispatchFlows.seededSlotOf(method, rightOp.base), method, leftOp)
                dispatchFlows.inheritOpenness(method, from = rightOp.base, to = leftOp)
            }
            // Field loads: `fn = this.callback`, including Kotlin's `Lambda.INSTANCE` singletons
            is JFieldRef -> {
                dispatchFlows.trackSlotFlow(DispatchSlot.Field(descriptors.fieldSlotKey(rightOp)), method, leftOp)
                dispatchFlows.markOpenLocal(method, leftOp, open = true)
                val fieldClass = rightOp.fieldSignature.declClassType.fullyQualifiedName
                if (rightOp is JStaticFieldRef && descriptors.toTypeDescriptor(rightOp.type).className == fieldClass) {
                    functionObjects.trackFunctionObject(method, leftOp, fieldClass, targetNode.id, stmt)
                }
            }
            // Copies: `a = b`
            is Local -> dispatchFlows.copyFunctionValue(method, rightOp, leftOp)
            // Casts: `fn = (Function1) lambda`, emitted after every Kotlin lambda allocation
            is JCastExpr -> (rightOp.op as? Local)?.let { dispatchFlows.copyFunctionValue(method, it, leftOp) }
            else -> Unit
        }
    }

    fun processIdentity(stmt: JIdentityStmt, method: MethodDescriptor) {
        val leftOp = stmt.leftOp
        val rightOp = stmt.rightOp
        if (rightOp !is JParameterRef && leftOp is Local) {
            stmtIdentityWrites[stmt] = leftOp.name
        }

        if (rightOp is JParameterRef) {
            val paramIndex = rightOp.index
            val paramKey = metadata.parameterBinding(method, paramIndex)
            val paramNode = parameterNodes[paramKey]
            val localNode = values.getOrCreateValueNode(leftOp, method)
            if (localNode != null) {
                recordLocalWrite(stmt, localNode.id, BranchScope.NO_CONSTANT)
            }

            if (paramNode != null && localNode != null) {
                graphBuilder.addEdge(
                    DataFlowEdge(
                        from = paramNode.id,
                        to = localNode.id,
                        kind = DataFlowKind.ASSIGN
                    )
                )
            }

            // Track which locals correspond to parameters for cross-method dispatch
            if (trackCrossMethodFunctionalDispatch) {
                val localKey = metadata.localKey(method, leftOp.name)
                localToParamIndex[localKey] = metadata.parameterBinding(method, paramIndex)
            } else {
                dispatchFlows.markOpenLocal(method, leftOp, open = true)
            }
        }
    }

    fun processInvoke(stmt: JInvokeStmt, method: MethodDescriptor) {
        val invokeExpr = stmt.invokeExpr.orElse(null) ?: return
        calls.processInvokeExpr(invokeExpr, method, null, stmt)
    }
}
