package io.johnsonlee.graphite.core

import it.unimi.dsi.fastutil.ints.IntOpenHashSet

/**
 * Edges represent relationships between nodes.
 * The edge type determines how values flow or relate.
 */
sealed interface Edge {
    val from: NodeId
    val to: NodeId
}

/**
 * Data flows from one node to another.
 * Examples:
 * - Assignment: x = y (flow from y to x)
 * - Parameter passing: foo(x) (flow from x to parameter)
 * - Return: return x (flow from x to return node)
 */
data class DataFlowEdge(
    override val from: NodeId,
    override val to: NodeId,
    val kind: DataFlowKind
) : Edge

enum class DataFlowKind {
    ASSIGN,           // Direct assignment
    PARAMETER_PASS,   // Argument passed to parameter
    RETURN_VALUE,     // Value returned from method
    FIELD_STORE,      // Value stored to field
    FIELD_LOAD,       // Value loaded from field
    ARRAY_STORE,      // Value stored to array
    ARRAY_LOAD,       // Value loaded from array
    CAST,             // Type cast
    PHI               // SSA phi node merge
}

/**
 * Method call relationship.
 * From caller method to callee method.
 */
data class CallEdge(
    override val from: NodeId, // CallSiteNode
    override val to: NodeId,   // Target method entry
    val isVirtual: Boolean,
    val isDynamic: Boolean = false // invokedynamic
) : Edge

/**
 * Type hierarchy relationship.
 */
data class TypeEdge(
    override val from: NodeId, // Subtype
    override val to: NodeId,   // Supertype
    val kind: TypeRelation
) : Edge

enum class TypeRelation {
    EXTENDS,
    IMPLEMENTS
}

/**
 * Control flow within a method.
 *
 * For BRANCH_TRUE/BRANCH_FALSE edges:
 * - `from` is the condition operand's NodeId (e.g., the local holding a boolean)
 * - `to` is a NodeId in the target branch
 * - `comparison` describes the JVM-level comparison (e.g., `== 0`, `!= null`)
 *
 * BRANCH_TRUE = the path taken when the JVM condition evaluates to true.
 * BRANCH_FALSE = the path taken when the JVM condition evaluates to false.
 */
data class ControlFlowEdge(
    override val from: NodeId,
    override val to: NodeId,
    val kind: ControlFlowKind,
    val comparison: BranchComparison? = null
) : Edge

enum class ControlFlowKind {
    SEQUENTIAL,
    BRANCH_TRUE,
    BRANCH_FALSE,
    SWITCH_CASE,
    SWITCH_DEFAULT,
    EXCEPTION,
    RETURN
}

/**
 * Describes the JVM-level comparison at a branch point.
 *
 * In Jimple: `if $z0 == 0 goto target`
 * → operator=EQ, comparandNodeId=NodeId(of constant 0)
 *
 * The BranchReachabilityAnalysis uses this to determine which branch is dead
 * given a constant assumption about the condition operand.
 */
data class BranchComparison(
    val operator: ComparisonOp,
    val comparandNodeId: NodeId
)

enum class ComparisonOp {
    EQ, NE, LT, GE, GT, LE
}

/**
 * Records which nodes belong to each branch of a condition.
 *
 * This provides efficient O(1) lookup for BranchReachabilityAnalysis
 * to determine which CallSiteNodes become dead when a branch is killed.
 */
data class BranchScope(
    val conditionNodeId: NodeId,
    val method: MethodDescriptor,
    val comparison: BranchComparison,
    val trueBranchNodeIds: IntOpenHashSet,
    val falseBranchNodeIds: IntOpenHashSet
)
