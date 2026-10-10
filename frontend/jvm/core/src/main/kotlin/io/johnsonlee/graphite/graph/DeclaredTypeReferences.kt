package io.johnsonlee.graphite.graph

/**
 * Optional reference-validation path for immutable declaration maps whose values have a compact
 * representation. Implementations must check every value reference against the supplied type count
 * on every call, including parameter bounds and optional superclass references. This performs the
 * same checks as iterating decoded values; it is not a certificate that validation happened earlier.
 */
interface DeclaredTypeReferences {
    fun validateTypeReferences(typeCount: Int)
}
