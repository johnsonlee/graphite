package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.analysis.DispatchSlot

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import java.util.BitSet
import sootup.core.graph.ControlFlowGraph
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.stmt.JIfStmt
import sootup.core.jimple.common.stmt.JReturnStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.jimple.common.expr.JEqExpr
import sootup.core.jimple.common.expr.JNeExpr
import sootup.core.jimple.common.expr.JLtExpr
import sootup.core.jimple.common.expr.JGeExpr
import sootup.core.jimple.common.expr.JGtExpr
import sootup.core.jimple.common.expr.JLeExpr
import io.johnsonlee.graphite.sootup.SootUpAdapter.PendingBranchScope

/** Builds branch scopes and local-definition tables from SootUp control flow. */
internal class ControlFlowEmitter(private val context: SootUpAdapter) {
    private val localNodes = context.localNodes
    private val methodReturnNodes = context.methodReturnNodes
    private val allocationNodes = context.allocationNodes
    private val stmtNodeIds
        get() = context.stmtNodeIds
    private val stmtLocalWrites
        get() = context.stmtLocalWrites
    private val stmtIdentityWrites
        get() = context.stmtIdentityWrites
    private val activeMethod
        get() = context.activeMethod
    private val graphBuilder = context.graphBuilder
    private val dispatchFlows get() = context.dispatchFlows
    private val metadata get() = context.metadata
    private val values get() = context.values

    fun processReturn(stmt: JReturnStmt, method: MethodDescriptor) {
        val returnValue = stmt.op
        val returnNode = methodReturnNodes[method] ?: return

        val valueNode = values.getOrCreateValueNode(returnValue, method)
        if (valueNode != null) {
            graphBuilder.addEdge(
                DataFlowEdge(
                    from = valueNode.id,
                    to = returnNode.id,
                    kind = DataFlowKind.RETURN_VALUE
                )
            )

            // Track function values flowing through return values
            if (returnValue is Local) {
                dispatchFlows.trackFlow(method, returnValue, DispatchSlot.Return(method))
            }
        }
    }

    /**
     * Process control flow for a method (pass 2).
     *
     * For each JIfStmt, identifies:
     * 1. The condition operand's NodeId
     * 2. The JVM comparison operator and comparand
     * 3. Which nodes belong to the true branch vs false branch
     *
     * Creates ControlFlowEdge and BranchScope entries.
     */
    fun processControlFlow(
        branchStatements: List<JIfStmt>,
        stmtGraph: ControlFlowGraph<*>,
        statements: List<Stmt>,
        method: MethodDescriptor
    ) {
        val controlFlowIndex = ControlFlowIndex(stmtGraph, statements)
        val reachableCache = HashMap<Int, BitSet>()
        val pending = branchStatements.mapNotNull { stmt ->
            prepareBranchScope(stmt, method, controlFlowIndex, reachableCache)
        }
        if (pending.isEmpty()) return
        val writes = resolveLocalWrites(controlFlowIndex)
        val trackedLocals = trackedLocals(pending, writes)
        for (scope in pending) {
            graphBuilder.addBranchScope(
                conditionNodeId = scope.conditionNodeId,
                method = method,
                comparison = scope.comparison,
                trueBranchNodeIds = scope.trueIds,
                falseBranchNodeIds = scope.falseIds,
                trueDefinitions = definitionsFor(scope.trueOnlyStatements, writes, trackedLocals),
                falseDefinitions = definitionsFor(scope.falseOnlyStatements, writes, trackedLocals)
            )
        }
        recordLocalDefinitionTables(writes, trackedLocals)
    }

    /**
     * The statements whose writes belong to one side, derived from raw reachability: those the side's
     * successor reaches and the other successor does not. [branchStatements] forces a side's own
     * successor into its set even when the other side reaches it (a merge point, a loop exit, or the
     * shared target of a branch with an empty `then`); that keeps the node sets and control-flow edges
     * as they are, but a write there executes whichever way the branch goes, so it is a definition of
     * neither side. Subtracting it with a killed side would drop a write the other side still makes.
     */
    private fun prepareBranchScope(
        stmt: JIfStmt,
        method: MethodDescriptor,
        controlFlowIndex: ControlFlowIndex,
        reachableCache: MutableMap<Int, BitSet>
    ): PendingBranchScope? {
        val condition = stmt.condition

        val op1 = condition.op1
        val op2 = condition.op2

        // Resolve operands to NodeIds
        val op1Node = values.getOrCreateValueNode(op1, method)
        val op2Node = values.getOrCreateValueNode(op2, method)
        val comparisonOp = comparisonOp(condition)
        val successors = controlFlowIndex.successors(controlFlowIndex.idOf(stmt))
        val operands = if (op1Node != null && op2Node != null) op1Node to op2Node else null
        if (operands == null || successors.size != 2 || comparisonOp == null) return null
        val conditionNodeId = operands.first.id
        val comparison = BranchComparison(operator = comparisonOp, comparandNodeId = operands.second.id)

        val falseSuccessor = successors[0]  // fall-through
        val trueSuccessor = successors[1]   // goto target

        // Walk each branch collecting all reachable node ids until merge point.
        val trueStatements = branchStatements(trueSuccessor, falseSuccessor, controlFlowIndex, reachableCache)
        val falseStatements = branchStatements(falseSuccessor, trueSuccessor, controlFlowIndex, reachableCache)
        val trueIds = nodeIdsFor(trueStatements, controlFlowIndex)
        val falseIds = nodeIdsFor(falseStatements, controlFlowIndex)

        addControlFlowEdges(conditionNodeId, comparison, trueIds, falseIds)

        // Record branch data (BranchScope is materialised lazily by DefaultGraph)
        return if (trueIds.isNotEmpty() || falseIds.isNotEmpty()) {
            PendingBranchScope(
                conditionNodeId,
                comparison,
                trueIds,
                falseIds,
                definitionStatements(trueStatements, trueSuccessor, reachableCache.getValue(falseSuccessor)),
                definitionStatements(falseStatements, falseSuccessor, reachableCache.getValue(trueSuccessor))
            )
        } else null
    }

    fun definitionStatements(sideStatements: BitSet, successorId: Int, otherReachable: BitSet): BitSet =
        if (otherReachable.get(successorId)) (sideStatements.clone() as BitSet).also { it.clear(successorId) } else sideStatements

    /**
     * The method's local writes by statement ordinal: `[localNodeId, constantNodeId | NO_CONSTANT]`.
     * Identity writes are resolved to the local's node here, without creating one.
     */
    fun resolveLocalWrites(controlFlowIndex: ControlFlowIndex): Int2ObjectOpenHashMap<IntArray> {
        val writes = Int2ObjectOpenHashMap<IntArray>(stmtLocalWrites.size + stmtIdentityWrites.size)
        for ((stmt, write) in stmtLocalWrites) {
            writes.put(controlFlowIndex.idOf(stmt), write)
        }
        val method = activeMethod ?: return writes
        for ((stmt, localName) in stmtIdentityWrites) {
            val key = metadata.localKey(method, localName)
            val node = allocationNodes[key] ?: localNodes[key] ?: continue
            writes.put(controlFlowIndex.idOf(stmt), intArrayOf(node.id.value, BranchScope.NO_CONSTANT))
        }
        return writes
    }

    /** Locals with a constant write on some branch side: the only ones a consumer can fold. */
    fun trackedLocals(pending: List<PendingBranchScope>, writes: Int2ObjectOpenHashMap<IntArray>): IntOpenHashSet {
        val tracked = IntOpenHashSet()
        for (scope in pending) {
            collectConstantlyWrittenLocals(scope.trueOnlyStatements, writes, tracked)
            collectConstantlyWrittenLocals(scope.falseOnlyStatements, writes, tracked)
        }
        return tracked
    }

    fun collectConstantlyWrittenLocals(statements: BitSet, writes: Int2ObjectOpenHashMap<IntArray>, into: IntOpenHashSet) {
        var statementId = statements.nextSetBit(0)
        while (statementId >= 0) {
            val write = writes.get(statementId)
            if (write != null && write[1] != BranchScope.NO_CONSTANT) into.add(write[0])
            statementId = statements.nextSetBit(statementId + 1)
        }
    }

    /**
     * The writes to tracked locals made by [statements], packed as
     * `[stmtOrdinal, localNodeId, constantNodeId | NO_CONSTANT]*` in ascending statement order.
     * The ordinal is the [ControlFlowIndex] id, which equals the statement's index in the
     * first-pass body traversal.
     */
    fun definitionsFor(
        statements: BitSet,
        writes: Int2ObjectOpenHashMap<IntArray>,
        trackedLocals: IntOpenHashSet
    ): IntArray {
        if (trackedLocals.isEmpty()) return BranchScope.EMPTY_DEFINITIONS
        var packed: IntArrayBuilder? = null
        var statementId = statements.nextSetBit(0)
        while (statementId >= 0) {
            val write = writes.get(statementId)
            if (write != null && write[0] in trackedLocals) {
                val builder = packed ?: IntArrayBuilder(BranchScope.DEFINITION_STRIDE).also { packed = it }
                builder.add(statementId)
                builder.add(write[0])
                builder.add(write[1])
            }
            statementId = statements.nextSetBit(statementId + 1)
        }
        return packed?.toIntArray() ?: BranchScope.EMPTY_DEFINITIONS
    }

    /**
     * Record every write of each tracked local, so consumers can subtract killed branch-side
     * writes from a complete, persistence-safe list instead of from the local's ASSIGN edges
     * (persisted graphs collapse repeated arcs), and see any surviving non-constant write.
     */
    fun recordLocalDefinitionTables(writes: Int2ObjectOpenHashMap<IntArray>, trackedLocals: IntOpenHashSet) {
        if (trackedLocals.isEmpty()) return
        val ordered = ArrayList<IntArray>()
        for (entry in writes.int2ObjectEntrySet()) {
            val write = entry.value
            if (write[0] in trackedLocals) ordered += intArrayOf(entry.intKey, write[0], write[1])
        }
        ordered.sortWith(compareBy({ it[1] }, { it[0] }))
        var start = 0
        while (start < ordered.size) {
            val localId = ordered[start][1]
            var end = start
            while (end < ordered.size && ordered[end][1] == localId) end++
            val packed = IntArray((end - start) * BranchScope.DEFINITION_STRIDE)
            for (position in start until end) {
                val base = (position - start) * BranchScope.DEFINITION_STRIDE
                val triple = ordered[position]
                packed[base] = triple[0]
                packed[base + 1] = triple[1]
                packed[base + 2] = triple[2]
            }
            graphBuilder.addLocalDefinitions(NodeId(localId), packed)
            start = end
        }
    }

    fun comparisonOp(condition: Any): ComparisonOp? = when (condition) {
        is JEqExpr -> ComparisonOp.EQ
        is JNeExpr -> ComparisonOp.NE
        is JLtExpr -> ComparisonOp.LT
        is JGeExpr -> ComparisonOp.GE
        is JGtExpr -> ComparisonOp.GT
        is JLeExpr -> ComparisonOp.LE
        else -> null
    }

    /**
     * Walk a branch from [start] collecting statements that are exclusively in this branch.
     *
     * Stops when reaching:
     * - A statement also reachable from [otherBranchStart] (merge point)
     * - A return/throw statement
     * - A statement already visited
     *
     * Uses forward dominance: only includes statements that are reachable from [start]
     * but not directly reachable from [otherBranchStart] without going through the merge point.
     */
    fun branchStatements(
        startId: Int,
        otherStartId: Int,
        controlFlowIndex: ControlFlowIndex,
        reachableCache: MutableMap<Int, BitSet>
    ): BitSet {
        val startReachable = reachableCache.getOrPut(startId) {
            collectReachable(startId, controlFlowIndex)
        }
        val otherReachable = reachableCache.getOrPut(otherStartId) {
            collectReachable(otherStartId, controlFlowIndex)
        }

        val branchStatements = startReachable.clone() as BitSet
        branchStatements.andNot(otherReachable)
        branchStatements.set(startId)
        return branchStatements
    }

    /** Create ControlFlowEdges from the condition to the first node in each branch. */
    fun addControlFlowEdges(
        conditionNodeId: NodeId,
        comparison: BranchComparison,
        trueIds: IntArray,
        falseIds: IntArray
    ) {
        if (trueIds.isNotEmpty()) {
            graphBuilder.addEdge(
                ControlFlowEdge(
                    from = conditionNodeId,
                    to = NodeId(trueIds[0]),
                    kind = ControlFlowKind.BRANCH_TRUE,
                    comparison = comparison
                )
            )
        }
        if (falseIds.isNotEmpty()) {
            graphBuilder.addEdge(
                ControlFlowEdge(
                    from = conditionNodeId,
                    to = NodeId(falseIds[0]),
                    kind = ControlFlowKind.BRANCH_FALSE,
                    comparison = comparison
                )
            )
        }
    }

    fun nodeIdsFor(statements: BitSet, controlFlowIndex: ControlFlowIndex): IntArray {
        val nodeIds = IntArrayBuilder()
        var statementId = statements.nextSetBit(0)
        while (statementId >= 0) {
            stmtNodeIds[controlFlowIndex.statement(statementId)]?.forEach(nodeIds::add)
            statementId = statements.nextSetBit(statementId + 1)
        }
        return nodeIds.toIntArray()
    }

    /**
     * Collect all statements reachable from [start] via forward traversal.
     */
    fun collectReachable(startId: Int, controlFlowIndex: ControlFlowIndex): BitSet {
        val reachable = BitSet()
        val queue = IntQueue()
        queue.add(startId)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (reachable.get(current)) continue
            reachable.set(current)
            for (successorId in controlFlowIndex.successors(current)) {
                if (!reachable.get(successorId)) {
                    queue.add(successorId)
                }
            }
        }

        return reachable
    }
}
