package io.johnsonlee.graphite.analysis

import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.ConstantNode
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import it.unimi.dsi.fastutil.ints.IntOpenHashSet

private const val FORWARD_TRACE_DEPTH_MULTIPLIER = 10
private const val GETTER_PREFIX_LENGTH = 3
private const val BOOLEAN_GETTER_PREFIX_LENGTH = 2

/**
 * An assumption declares that a specific expression always evaluates to a known constant.
 *
 * Example: `getOption(1001) -> true` means whenever `getOption` is called with argument value 1001, its return value is `true`.
 */
data class Assumption(
    val methodPattern: MethodPattern,
    val argumentIndex: Int? = null,
    val argumentValue: Any? = null,
    val assumedValue: Any?
)

/** A dead branch identified by the analysis. */
data class DeadBranch(
    val conditionNodeId: NodeId,
    val deadKind: ControlFlowKind,
    val method: MethodDescriptor,
    val deadNodeIds: IntOpenHashSet,
    val deadCallSites: List<CallSiteNode>
)

/** Result of dead code analysis. */
data class DeadCodeResult(
    val deadBranches: List<DeadBranch>,
    val deadMethods: Set<MethodDescriptor>,
    val deadCallSites: Set<CallSiteNode>,
    val unreferencedMethods: Set<MethodDescriptor>,
    val unreferencedFields: Set<FieldDescriptor> = emptySet()
)

/**
 * Analyzes branch reachability given a set of constant assumptions.
 *
 * Algorithm:
 * 1. For each assumption, find matching call sites
 * 2. Forward-trace the return value to find branch conditions
 * 3. Evaluate conditions with assumed values to identify dead branches
 * 4. Collect dead call sites from dead branches
 * 5. Find transitively dead methods (only called from dead branches)
 */
class BranchReachabilityAnalysis(private val graph: Graph, private val config: AnalysisConfig = AnalysisConfig()) {

    /** Analyze dead code given a set of assumptions. */
    fun analyze(assumptions: List<Assumption>): DeadCodeResult {
        val allDeadBranches = mutableListOf<DeadBranch>()
        val allDeadCallSites = mutableSetOf<CallSiteNode>()

        for (assumption in assumptions) {
            val deadBranches = analyzeAssumption(assumption)
            allDeadBranches.addAll(deadBranches)
            for (branch in deadBranches) {
                allDeadCallSites.addAll(branch.deadCallSites)
            }
        }

        // Find transitively dead methods
        val deadMethods = findTransitivelyDeadMethods(allDeadCallSites)

        return DeadCodeResult(
            deadBranches = allDeadBranches,
            deadMethods = deadMethods,
            deadCallSites = allDeadCallSites,
            unreferencedMethods = emptySet() // filled separately by unreferenced detection
            )
    }

    fun findUnreferencedFields(): Set<FieldDescriptor> = FieldReferenceAnalysis(graph).findUnreferenced()

    /** Find methods that are never referenced in the graph. */
    fun findUnreferencedMethods(): Set<MethodDescriptor> {
        val allMethods = graph.methods(MethodPattern()).toSet()
        val calledMethods = graph.nodes(CallSiteNode::class.java).map { it.callee.signature }.toSet()

        return allMethods
            .filter { method ->
                // A method is unreferenced if no call site targets it
                // Skip constructors and static initializers
                method.name != "<init>" && method.name != "<clinit>" && method.signature !in calledMethods
            }
            .toSet()
    }

    private fun analyzeAssumption(assumption: Assumption): List<DeadBranch> =
        graph
            .callSites(assumption.methodPattern)
            .filter { matchesArgumentConstraint(it, assumption) }
            .flatMap { callSite ->
                forwardTraceFromCallSite(callSite).asSequence().flatMap { nodeId ->
                    graph.branchScopesFor(nodeId).mapNotNull { evaluateBranch(it, assumption.assumedValue) }
                }
            }
            .toList()

    private fun matchesArgumentConstraint(callSite: CallSiteNode, assumption: Assumption): Boolean =
        when {
            assumption.argumentIndex == null || assumption.argumentValue == null -> true
            assumption.argumentIndex >= callSite.arguments.size -> false
            else -> matchesArgumentValue(callSite.arguments[assumption.argumentIndex], assumption.argumentValue)
        }

    private fun matchesArgumentValue(nodeId: NodeId, value: Any): Boolean {
        val node = graph.node(nodeId)
        return if (node is ConstantNode) {
            constantEquals(node.value, value)
        } else {
            DataFlowAnalysis(graph, config).backwardSlice(nodeId).allConstants().any { constantEquals(it.value, value) }
        }
    }

    /** Forward-trace from a call site to find all nodes its return value flows to. */
    private fun forwardTraceFromCallSite(callSite: CallSiteNode): Set<NodeId> {
        val visited = mutableSetOf<NodeId>()
        val queue = ArrayDeque<NodeId>()
        queue.add(callSite.id)

        while (queue.isNotEmpty() && visited.size <= config.maxDepth * FORWARD_TRACE_DEPTH_MULTIPLIER) {
            val current = queue.removeFirst()
            if (current in visited) continue
            visited.add(current)

            // Follow outgoing DataFlowEdges
            graph.outgoing(current, DataFlowEdge::class.java).forEach { edge ->
                if (edge.to !in visited) {
                    queue.add(edge.to)
                }
            }
        }

        return visited
    }

    private fun evaluateBranch(scope: BranchScope, assumedValue: Any?): DeadBranch? =
        (graph.node(scope.comparison.comparandNodeId) as? ConstantNode)?.let { comparand ->
            evaluateComparison(assumedValue, scope.comparison.operator, comparand.value)?.let { conditionIsTrue ->
                buildDeadBranch(scope, conditionIsTrue)
            }
        }

    private fun buildDeadBranch(scope: BranchScope, conditionIsTrue: Boolean): DeadBranch? {
        val deadKind = if (conditionIsTrue) ControlFlowKind.BRANCH_FALSE else ControlFlowKind.BRANCH_TRUE
        val deadNodeIds = if (conditionIsTrue) scope.falseBranchNodeIds else scope.trueBranchNodeIds
        if (deadNodeIds.isEmpty) return null
        val deadCallSites = buildList {
            val iter = deadNodeIds.intIterator()
            while (iter.hasNext()) {
                val node = graph.node(NodeId(iter.nextInt()))
                if (node is CallSiteNode) add(node)
            }
        }
        return DeadBranch(scope.conditionNodeId, deadKind, scope.method, deadNodeIds, deadCallSites)
    }

    /**
     * Evaluate a JVM comparison: left `op` right.
     *
     * Handles boolean (true=1, false=0), integer, and null comparisons.
     */
    private fun evaluateComparison(left: Any?, op: ComparisonOp, right: Any?): Boolean? {
        val leftNum = toComparableNumber(left)
        val rightNum = toComparableNumber(right)

        if (leftNum != null && rightNum != null) {
            return when (op) {
                ComparisonOp.EQ -> leftNum.compareTo(rightNum) == 0
                ComparisonOp.NE -> leftNum.compareTo(rightNum) != 0
                ComparisonOp.LT -> leftNum < rightNum
                ComparisonOp.GE -> leftNum >= rightNum
                ComparisonOp.GT -> leftNum > rightNum
                ComparisonOp.LE -> leftNum <= rightNum
            }
        }

        // Handle null comparisons
        return when (op) {
            ComparisonOp.EQ -> left == right
            ComparisonOp.NE -> left != right
            else -> null
        } // Can't evaluate non-numeric non-null with ordering operators
    }

    /** Convert a value to a Long for numeric comparison. Boolean: true=1, false=0 (matches JVM bytecode convention). */
    private fun toComparableNumber(value: Any?): Long? {
        return when (value) {
            is Boolean -> if (value) 1L else 0L
            is Int -> value.toLong()
            is Long -> value
            is Short -> value.toLong()
            is Byte -> value.toLong()
            is Float -> value.toLong()
            is Double -> value.toLong()
            null -> null
            else -> null
        }
    }

    private fun constantEquals(a: Any?, b: Any?): Boolean {
        val aNum = toComparableNumber(a)
        val bNum = toComparableNumber(b)
        return when {
            a == b -> true
            aNum != null && bNum != null -> aNum == bNum
            else -> a?.toString() == b?.toString()
        }
    }

    /**
     * Find methods that are only called from dead call sites.
     *
     * Iterates until convergence: if a method becomes dead, all its call sites become dead, which may make other methods dead.
     */
    private fun findTransitivelyDeadMethods(deadCallSites: Set<CallSiteNode>): Set<MethodDescriptor> {
        val deadMethods = mutableSetOf<MethodDescriptor>()
        val allDeadCallSiteIds = deadCallSites.map { it.id }.toMutableSet()
        var changed = true

        while (changed) {
            changed = false

            // Find methods where ALL call sites to them are dead
            val allCallSites = graph.nodes(CallSiteNode::class.java).toList()
            val callSitesByCallee = allCallSites.groupBy { it.callee.signature }

            for ((signature, callSitesForMethod) in callSitesByCallee) {
                val method = callSitesForMethod.first().callee
                val eligible = method !in deadMethods && method.name !in setOf("<init>", "<clinit>")
                val allDead = eligible && callSitesForMethod.all { it.id in allDeadCallSiteIds }
                if (allDead) {
                    deadMethods.add(method)
                    changed = true

                    // All call sites WITHIN this dead method also become dead
                    markDeadMethodCallSites(allCallSites, signature, allDeadCallSiteIds)
                }
            }
        }

        return deadMethods
    }

    private fun markDeadMethodCallSites(callSites: List<CallSiteNode>, signature: String, deadIds: MutableSet<NodeId>) {
        callSites.filter { it.caller.signature == signature }.forEach { deadIds.add(it.id) }
    }
}

/**
 * Extract the field name from a JavaBean accessor method name. Supports getXxx, setXxx, and isXxx patterns.
 *
 * @return the field name (first char lowered) or null if not an accessor
 */
internal fun extractFieldNameFromAccessor(methodName: String): String? {
    val prefix =
        when {
            methodName.startsWith("get") && methodName.length > GETTER_PREFIX_LENGTH -> "get"
            methodName.startsWith("set") && methodName.length > GETTER_PREFIX_LENGTH -> "set"
            methodName.startsWith("is") && methodName.length > BOOLEAN_GETTER_PREFIX_LENGTH -> "is"
            else -> return null
        }
    val rest = methodName.removePrefix(prefix)
    return rest.replaceFirstChar { it.lowercase() }
}

/** Check if a FieldNode is synthetic and should be excluded from unreferenced detection. */
private fun isSyntheticField(field: FieldNode): Boolean {
    val name = field.descriptor.name
    return name.startsWith("\$") || name.startsWith("this\$") || name == "\$VALUES" || name == "serialVersionUID"
}

/** Finds externally used fields without coupling accessor names to edge traversal state. */
private class FieldReferenceAnalysis(private val graph: Graph) {
    fun findUnreferenced(): Set<FieldDescriptor> {
        val accessorReferences =
            graph
                .nodes(CallSiteNode::class.java)
                .mapNotNull { callSite ->
                    val calleeClass = callSite.callee.declaringClass.className
                    if (callSite.caller.declaringClass.className == calleeClass) null
                    else {
                        extractFieldNameFromAccessor(callSite.callee.name)?.let { "$calleeClass#$it" }
                    }
                }
                .toSet()
        return graph
            .nodes(FieldNode::class.java)
            .filterNot(::isSyntheticField)
            .filterNot { "${it.descriptor.declaringClass.className}#${it.descriptor.name}" in accessorReferences }
            .filterNot(::hasExternalDataFlow)
            .map { it.descriptor }
            .toSet()
    }

    private fun hasExternalDataFlow(field: FieldNode): Boolean {
        val declaringClass = field.descriptor.declaringClass.className
        val loaded =
            graph.outgoing(field.id, DataFlowEdge::class.java).any { edge ->
                (edge.kind == DataFlowKind.FIELD_LOAD || edge.kind == DataFlowKind.ASSIGN) && externalLocal(edge.to, declaringClass)
            }
        return loaded ||
            graph.incoming(field.id, DataFlowEdge::class.java).any { edge ->
                (edge.kind == DataFlowKind.FIELD_STORE || edge.kind == DataFlowKind.ASSIGN) && externalLocal(edge.from, declaringClass)
            }
    }

    private fun externalLocal(nodeId: NodeId, declaringClass: String): Boolean {
        val node = graph.node(nodeId)
        return node is LocalVariable && node.method.declaringClass.className != declaringClass
    }
}
