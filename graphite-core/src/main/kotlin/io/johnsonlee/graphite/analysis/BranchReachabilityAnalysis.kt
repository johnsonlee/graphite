package io.johnsonlee.graphite.analysis

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import it.unimi.dsi.fastutil.ints.IntOpenHashSet

/**
 * An assumption declares that a specific expression always evaluates to a known constant.
 *
 * Example: `getOption(1001) -> true` means whenever `getOption` is called
 * with argument value 1001, its return value is `true`.
 */
data class Assumption(
    val methodPattern: MethodPattern,
    val argumentIndex: Int? = null,
    val argumentValue: Any? = null,
    val assumedValue: Any?
)

/**
 * A dead branch identified by the analysis.
 */
data class DeadBranch(
    val conditionNodeId: NodeId,
    val deadKind: ControlFlowKind,
    val method: MethodDescriptor,
    val deadNodeIds: IntOpenHashSet,
    val deadCallSites: List<CallSiteNode>
)

/**
 * Result of dead code analysis.
 */
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
class BranchReachabilityAnalysis(
    private val graph: Graph,
    private val config: AnalysisConfig = AnalysisConfig()
) {

    /**
     * Analyze dead code given a set of assumptions.
     */
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

    /**
     * Find fields that are never referenced from outside their declaring class.
     *
     * A field is unreferenced if:
     * 1. No FIELD_LOAD or FIELD_STORE edges connect it to methods outside its declaring class
     * 2. No external CallSiteNode targets a getter/setter matching JavaBean naming (getXxx/setXxx/isXxx)
     *
     * Excludes synthetic fields ($-prefixed, this$, $VALUES).
     */
    fun findUnreferencedFields(): Set<FieldDescriptor> {
        val allFields = graph.nodes(FieldNode::class.java).toList()
        val referencedFields = mutableSetOf<String>() // key: "class#field"

        // Check FIELD_LOAD and FIELD_STORE edges for external references
        for (field in allFields) {
            val fieldKey = "${field.descriptor.declaringClass.className}#${field.descriptor.name}"
            val declaringClass = field.descriptor.declaringClass.className

            // Check outgoing edges (FIELD_LOAD: field -> local in some method)
            for (edge in graph.outgoing(field.id, DataFlowEdge::class.java)) {
                if (edge.kind == DataFlowKind.FIELD_LOAD || edge.kind == DataFlowKind.ASSIGN) {
                    val targetNode = graph.node(edge.to)
                    if (targetNode is LocalVariable && targetNode.method.declaringClass.className != declaringClass) {
                        referencedFields.add(fieldKey)
                        break
                    }
                }
            }
            if (fieldKey in referencedFields) continue

            // Check incoming edges (FIELD_STORE: local in some method -> field)
            for (edge in graph.incoming(field.id, DataFlowEdge::class.java)) {
                if (edge.kind == DataFlowKind.FIELD_STORE || edge.kind == DataFlowKind.ASSIGN) {
                    val sourceNode = graph.node(edge.from)
                    if (sourceNode is LocalVariable && sourceNode.method.declaringClass.className != declaringClass) {
                        referencedFields.add(fieldKey)
                        break
                    }
                }
            }
        }

        // Check external getter/setter/isXxx calls (Lombok support)
        for (callSite in graph.nodes(CallSiteNode::class.java)) {
            val calleeName = callSite.callee.name
            val calleeClass = callSite.callee.declaringClass.className
            val callerClass = callSite.caller.declaringClass.className
            if (callerClass == calleeClass) continue // skip internal calls

            val fieldName = extractFieldNameFromAccessor(calleeName) ?: continue
            referencedFields.add("$calleeClass#$fieldName")
        }

        return allFields
            .filter { !isSyntheticField(it) }
            .filter { "${it.descriptor.declaringClass.className}#${it.descriptor.name}" !in referencedFields }
            .map { it.descriptor }
            .toSet()
    }

    /**
     * Find methods that are never referenced in the graph.
     */
    fun findUnreferencedMethods(): Set<MethodDescriptor> {
        val allMethods = graph.methods(MethodPattern()).toSet()
        val calledMethods = graph.nodes(CallSiteNode::class.java)
            .map { it.callee.signature }
            .toSet()

        return allMethods.filter { method ->
            // A method is unreferenced if no call site targets it
            // Skip constructors and static initializers
            method.name != "<init>" && method.name != "<clinit>" &&
                method.signature !in calledMethods
        }.toSet()
    }

    private fun analyzeAssumption(assumption: Assumption): List<DeadBranch> {
        val deadBranches = mutableListOf<DeadBranch>()

        // Step 1: Find matching call sites
        val matchingCallSites = graph.callSites(assumption.methodPattern)
            .filter { callSite ->
                matchesArgumentConstraint(callSite, assumption)
            }
            .toList()

        // Step 2: For each matching call site, forward-trace the return value
        for (callSite in matchingCallSites) {
            val reachableNodes = forwardTraceFromCallSite(callSite)

            // Step 3: Check if any reachable node is a branch condition
            for (nodeId in reachableNodes) {
                val scopes = graph.branchScopesFor(nodeId)
                for (scope in scopes) {
                    val deadBranch = evaluateBranch(scope, assumption.assumedValue)
                    if (deadBranch != null) {
                        deadBranches.add(deadBranch)
                    }
                }
            }
        }

        return deadBranches
    }

    /**
     * Check if a call site's arguments match the assumption's constraints.
     */
    private fun matchesArgumentConstraint(callSite: CallSiteNode, assumption: Assumption): Boolean {
        if (assumption.argumentIndex == null || assumption.argumentValue == null) {
            return true // No argument constraint
        }

        val argIndex = assumption.argumentIndex
        if (argIndex >= callSite.arguments.size) return false

        val argNodeId = callSite.arguments[argIndex]
        val argNode = graph.node(argNodeId)

        // Check if the argument is a constant matching the expected value
        if (argNode is ConstantNode) {
            return constantEquals(argNode.value, assumption.argumentValue)
        }

        // Try backward slice to find constants
        val analysis = DataFlowAnalysis(graph, config)
        val result = analysis.backwardSlice(argNodeId)
        return result.allConstants().any { constantEquals(it.value, assumption.argumentValue) }
    }

    /**
     * Forward-trace from a call site to find all nodes its return value flows to.
     */
    private fun forwardTraceFromCallSite(callSite: CallSiteNode): Set<NodeId> {
        val visited = mutableSetOf<NodeId>()
        val queue = ArrayDeque<NodeId>()
        queue.add(callSite.id)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current in visited) continue
            if (visited.size > config.maxDepth * 10) break // safety limit
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

    /**
     * Evaluate whether a branch is dead given the assumed value of the condition.
     *
     * The BranchScope tells us:
     * - conditionNodeId: the operand being tested (e.g., $z0 holding getOption result)
     * - comparison: operator (EQ/NE/LT/...) + comparand (e.g., constant 0)
     *
     * We evaluate: assumedValue `operator` comparandValue → true or false
     * - If true → JVM condition is true → BRANCH_TRUE path is alive, BRANCH_FALSE is dead
     * - If false → JVM condition is false → BRANCH_FALSE path is alive, BRANCH_TRUE is dead
     */
    private fun evaluateBranch(scope: BranchScope, assumedValue: Any?): DeadBranch? {
        val comparandNode = graph.node(scope.comparison.comparandNodeId)
        val comparandValue = when (comparandNode) {
            is ConstantNode -> comparandNode.value
            else -> return null // Can't evaluate if comparand isn't a constant
        }

        val conditionIsTrue = evaluateComparison(
            assumedValue,
            scope.comparison.operator,
            comparandValue
        ) ?: return null

        // Determine which branch is dead
        val deadKind: ControlFlowKind
        val deadNodeIds: IntOpenHashSet

        if (conditionIsTrue) {
            // JVM condition is true → true branch alive, false branch dead
            deadKind = ControlFlowKind.BRANCH_FALSE
            deadNodeIds = scope.falseBranchNodeIds
        } else {
            // JVM condition is false → false branch alive, true branch dead
            deadKind = ControlFlowKind.BRANCH_TRUE
            deadNodeIds = scope.trueBranchNodeIds
        }

        if (deadNodeIds.isEmpty) return null

        // Collect dead CallSiteNodes
        val deadCallSites = buildList {
            val iter = deadNodeIds.intIterator()
            while (iter.hasNext()) {
                val node = graph.node(NodeId(iter.nextInt()))
                if (node is CallSiteNode) add(node)
            }
        }

        return DeadBranch(
            conditionNodeId = scope.conditionNodeId,
            deadKind = deadKind,
            method = scope.method,
            deadNodeIds = deadNodeIds,
            deadCallSites = deadCallSites
        )
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
        if (op == ComparisonOp.EQ) return left == right
        if (op == ComparisonOp.NE) return left != right

        return null // Can't evaluate non-numeric non-null with ordering operators
    }

    /**
     * Convert a value to a Long for numeric comparison.
     * Boolean: true=1, false=0 (matches JVM bytecode convention).
     */
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

    /**
     * Check if two constant values are equal, handling type coercion.
     */
    private fun constantEquals(a: Any?, b: Any?): Boolean {
        if (a == b) return true
        val aNum = toComparableNumber(a)
        val bNum = toComparableNumber(b)
        if (aNum != null && bNum != null) return aNum == bNum
        return a?.toString() == b?.toString()
    }

    /**
     * Find methods that are only called from dead call sites.
     *
     * Iterates until convergence: if a method becomes dead, all its call sites
     * become dead, which may make other methods dead.
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
                if (method in deadMethods) continue
                if (method.name == "<init>" || method.name == "<clinit>") continue

                val allDead = callSitesForMethod.all { it.id in allDeadCallSiteIds }
                if (allDead) {
                    deadMethods.add(method)
                    changed = true

                    // All call sites WITHIN this dead method also become dead
                    allCallSites
                        .filter { it.caller.signature == signature }
                        .forEach { allDeadCallSiteIds.add(it.id) }
                }
            }
        }

        return deadMethods
    }
}

/**
 * Extract the field name from a JavaBean accessor method name.
 * Supports getXxx, setXxx, and isXxx patterns.
 *
 * @return the field name (first char lowered) or null if not an accessor
 */
internal fun extractFieldNameFromAccessor(methodName: String): String? {
    val prefix = when {
        methodName.startsWith("get") && methodName.length > 3 -> "get"
        methodName.startsWith("set") && methodName.length > 3 -> "set"
        methodName.startsWith("is") && methodName.length > 2 -> "is"
        else -> return null
    }
    val rest = methodName.removePrefix(prefix)
    return rest.replaceFirstChar { it.lowercase() }
}

/**
 * Check if a FieldNode is synthetic and should be excluded from unreferenced detection.
 */
private fun isSyntheticField(field: FieldNode): Boolean {
    val name = field.descriptor.name
    return name.startsWith("\$") ||
        name.startsWith("this\$") ||
        name == "\$VALUES" ||
        name == "serialVersionUID"
}
