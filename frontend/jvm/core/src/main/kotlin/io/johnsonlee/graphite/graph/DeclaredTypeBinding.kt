package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node

/**
 * Binds a graph's own nodes and methods to their rows in its [DeclaredTypeTable] without
 * spelling a member key: a persisted graph names a field's class, name and type by string
 * IDs, and a table saved beside it keys its rows by the same IDs, so the lookup compares
 * integers where [MemberTypeKey] decoded and hashed three strings per node.
 *
 * Every answer is definitive for the graph's table except [UNANSWERED] and
 * [UNANSWERED_METHOD], which hand a receiver the binding does not know (a node of another
 * graph, a descriptor no graph materialized) back to the key lookup. Type IDs are the
 * table's own, as [DeclaredTypeTable.fields] and [MethodTypes] carry them.
 */
interface DeclaredTypeBinding {
    /** The declared type ID of [node]'s field, [UNBOUND] when the table has no row for it, or [UNANSWERED]. */
    fun fieldTypeId(node: FieldNode): Int

    /**
     * The declared type ID of a Parameter [node]'s parameter or a Return [node]'s return type,
     * [UNBOUND] when the table has no row for its method or no such parameter, or [UNANSWERED].
     */
    fun memberTypeId(node: Node): Int

    /** The declared types of [method], null when the table has none, or [UNANSWERED_METHOD] (compared by identity). */
    fun methodTypes(method: MethodDescriptor): MethodTypes?

    /**
     * What a presence check needs of [method]'s declaration without decoding it: [HAS_PARAMETERS]
     * and [HAS_BOUNDS] flags, 0 for a row with neither, [UNBOUND] when the table has no row, or
     * [UNANSWERED].
     */
    fun methodShape(method: MethodDescriptor): Int

    companion object {
        const val UNBOUND = -1
        const val UNANSWERED = -2

        /** The method declares at least one parameter type. */
        const val HAS_PARAMETERS = 1

        /** At least one of the method's type parameters has a bound. */
        const val HAS_BOUNDS = 2

        /** Identity sentinel: the binding cannot answer for this receiver. */
        val UNANSWERED_METHOD: MethodTypes = MethodTypes(emptyList(), UNANSWERED)
    }
}
