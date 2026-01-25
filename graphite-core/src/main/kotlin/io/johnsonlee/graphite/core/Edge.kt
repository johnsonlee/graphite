package io.johnsonlee.graphite.core

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
 */
data class ControlFlowEdge(
    override val from: NodeId,
    override val to: NodeId,
    val kind: ControlFlowKind
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
