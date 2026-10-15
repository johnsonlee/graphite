package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.DeclaredTypeTable

/**
 * One save's format selection and declaration indexes, shared by dictionary collection and writing.
 * Closing releases the indexes; the plan must never be retained on a graph or reused for another save.
 */
internal class DeclaredTypeSavePlan private constructor(
    val table: DeclaredTypeTable,
    structural: StructuralDeclaredTypeScopes?,
    erased: ErasedDeclaredTypePlan?
) : AutoCloseable {
    var structural: StructuralDeclaredTypeScopes? = structural
        private set
    var erased: ErasedDeclaredTypePlan? = erased
        private set
    private var closed = false

    fun requireOpen() { check(!closed) { "Declared type save plan was already consumed" } }

    override fun close() {
        structural = null
        erased = null
        closed = true
    }

    companion object {
        const val CURRENT_VERSION = 5
        const val STRUCTURAL_VERSION = 4

        fun prepare(table: DeclaredTypeTable, maximumVersion: Int): DeclaredTypeSavePlan {
            val structural = if (maximumVersion >= STRUCTURAL_VERSION) StructuralDeclaredTypeScopes.forTable(table) else null
            val erased = if (maximumVersion >= CURRENT_VERSION && structural != null) ErasedDeclaredTypePlan.forTable(table) else null
            return DeclaredTypeSavePlan(table, structural, erased)
        }
    }
}
