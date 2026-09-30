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
 * Resource graph relationship.
 *
 * These edges model resource structure and resource access separately from
 * ordinary dataflow so Cypher can query them directly without overloading
 * [DataFlowEdge].
 */
data class ResourceEdge(
    override val from: NodeId,
    override val to: NodeId,
    val kind: ResourceRelation
) : Edge

enum class ResourceRelation {
    OPENS,              // ResourceFileNode -> CallSiteNode
    LOADS,              // ResourceFileNode -> CallSiteNode for parsers/loaders/bundles
    BUNDLE_CANDIDATE,   // ResourceFileNode -> CallSiteNode for ResourceBundle candidate resolution
    LOOKUP,             // ResourceFileNode -> CallSiteNode/FieldNode
    ENUMERATES          // ResourceFileNode -> CallSiteNode for getKeys()/enumeration-style APIs
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
 * A statement that writes a local: `local = <constant>` when [constantNodeId] is
 * set, any other write (a copy, a call result, an arithmetic result, a parameter
 * binding, ...) when it is `null`.
 *
 * In the source graph every constant definition corresponds to exactly one
 * `ASSIGN` [DataFlowEdge] from [constantNodeId] to [localNodeId]. Persisted
 * graphs collapse repeated arcs between the same nodes, so consumers must not
 * recover a local's definition multiset from its edges:
 * [io.johnsonlee.graphite.graph.Graph.localDefinitionsFor] lists every write of a
 * local that has at least one constant definition on a branch side. Subtracting
 * the definitions on killed sides from that list (by [stmtOrdinal]) leaves the
 * live writes; the local folds only when every live write is the same constant.
 */
data class LocalDefinition(
    /** Position of the statement in the method body, matching the first-pass statement order. */
    val stmtOrdinal: Int,
    /** The [LocalVariable] node being written. */
    val localNodeId: NodeId,
    /** The [ConstantNode] on the right-hand side, or `null` for a write whose value is not a constant. */
    val constantNodeId: NodeId?
) {
    val isConstant: Boolean get() = constantNodeId != null
}

/**
 * Records which nodes belong to each branch of a condition.
 *
 * This provides efficient O(1) lookup for BranchReachabilityAnalysis
 * to determine which CallSiteNodes become dead when a branch is killed.
 *
 * [trueDefinitions] and [falseDefinitions] list the writes made on each side to
 * locals that have a constant definition on some branch side, ordered by
 * [LocalDefinition.stmtOrdinal]. A side's writes are those reached only through
 * that side: a write both sides reach (after the merge point, at a loop exit)
 * executes whichever way the branch goes and belongs to neither, so subtracting
 * a killed side never removes a write the other side still makes. A definition
 * inside a nested branch appears in both the inner scope and the matching side
 * of every enclosing scope.
 */
data class BranchScope(
    val conditionNodeId: NodeId,
    val method: MethodDescriptor,
    val comparison: BranchComparison,
    val trueBranchNodeIds: IntOpenHashSet,
    val falseBranchNodeIds: IntOpenHashSet,
    val trueDefinitions: List<LocalDefinition> = emptyList(),
    val falseDefinitions: List<LocalDefinition> = emptyList()
) {
    companion object {
        /** Number of ints per definition in the packed form used by graph builders. */
        const val DEFINITION_STRIDE = 3

        /** The packed constant id of a write whose value is not a constant. */
        const val NO_CONSTANT = -1

        /** Shared empty packed-definition array. */
        val EMPTY_DEFINITIONS = IntArray(0)

        /** Expand a packed `[stmtOrdinal, localNodeId, constantNodeId]*` array. */
        fun unpackDefinitions(packed: IntArray): List<LocalDefinition> {
            if (packed.isEmpty()) return emptyList()
            require(packed.size % DEFINITION_STRIDE == 0) {
                "Packed definitions length ${packed.size} is not a multiple of $DEFINITION_STRIDE"
            }
            return List(packed.size / DEFINITION_STRIDE) { index ->
                val base = index * DEFINITION_STRIDE
                LocalDefinition(
                    stmtOrdinal = packed[base],
                    localNodeId = NodeId(packed[base + 1]),
                    constantNodeId = packed[base + 2].takeIf { it != NO_CONSTANT }?.let(::NodeId)
                )
            }
        }

        /** Expand a per-local table of packed definitions, keyed by local node id. */
        fun unpackDefinitionTable(packed: Map<Int, IntArray>): Map<NodeId, List<LocalDefinition>> {
            if (packed.isEmpty()) return emptyMap()
            val table = HashMap<NodeId, List<LocalDefinition>>(packed.size)
            for ((localId, definitions) in packed) {
                table[NodeId(localId)] = unpackDefinitions(definitions)
            }
            return table
        }

        /** Pack definitions into the `[stmtOrdinal, localNodeId, constantNodeId]*` form. */
        fun packDefinitions(definitions: List<LocalDefinition>): IntArray {
            if (definitions.isEmpty()) return EMPTY_DEFINITIONS
            val packed = IntArray(definitions.size * DEFINITION_STRIDE)
            definitions.forEachIndexed { index, definition ->
                val base = index * DEFINITION_STRIDE
                packed[base] = definition.stmtOrdinal
                packed[base + 1] = definition.localNodeId.value
                packed[base + 2] = definition.constantNodeId?.value ?: NO_CONSTANT
            }
            return packed
        }
    }
}
