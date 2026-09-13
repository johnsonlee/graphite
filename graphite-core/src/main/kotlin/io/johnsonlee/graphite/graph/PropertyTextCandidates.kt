package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.Node

/**
 * Selects a necessary string fragment for a dynamic-property CONTAINS candidate search.
 * A returned fragment is NOT a replacement for evaluating the complete query predicate.
 *
 * Safe storage consumers may use this for scalar constants, CallSiteNode, LocalVariable,
 * FieldNode, ParameterNode, ReturnNode, and ResourceFileNode. Their non-numeric visible
 * strings are either stored verbatim or method signatures joined with '.', '(', ',', ')'.
 * None of those separators can occur inside the selected ASCII identifier fragment, so
 * a matching signature must contain the fragment in at least one stored component.
 * Primitive stringification can only contribute digits, signs, decimal points, exponent
 * letters, or the fixed tokens rejected below. It therefore cannot independently match
 * an accepted fragment.
 *
 * EnumConstant, ResourceValueNode, AnnotationNode, method metadata values, and any future
 * node kind MUST retain normal evaluation: nested values and generated names need not be
 * present verbatim in the string table. A query layer must also bypass filtering when its
 * external graphId contains the fragment (elementId/qualifiedId append ':' and digits).
 * Unknown schemas and unsupported property access semantics require the same fallback.
 */
private object PropertyTextFragmentSelector {
    private val identifier = Regex("[A-Za-z_][A-Za-z_0-9]*")
    private val generatedTokens = listOf("true", "false", "null", "NaN", "Infinity")

    private const val MAX_FRAGMENTS = 2

    /** Retains at most two distinct fragments; equal lengths keep their encounter order. */
    fun select(needle: String): List<String> {
        val selected = mutableListOf<String>()
        for (match in identifier.findAll(needle)) {
            val fragment = match.value
            if (fragment in selected || !eligible(fragment)) continue
            val position = selected.indexOfFirst { it.length < fragment.length }.takeIf { it >= 0 } ?: selected.size
            if (position < MAX_FRAGMENTS) {
                selected.add(position, fragment)
                if (selected.size > MAX_FRAGMENTS) selected.removeAt(MAX_FRAGMENTS)
            }
        }
        return selected
    }

    private fun eligible(fragment: String): Boolean =
        !fragment.all { it == 'e' || it == 'E' || it in '0'..'9' } &&
            generatedTokens.none { it.contains(fragment) }
}

/** Returns a necessary fragment only; see [NodePropertyTextCandidates] for its safe use. */
fun propertyTextFragment(needle: String): String? = propertyTextFragments(needle).firstOrNull()

/** Up to two distinct necessary fragments, longest first with stable encounter-order ties. */
fun propertyTextFragments(needle: String): List<String> = PropertyTextFragmentSelector.select(needle)

/**
 * Optional storage prefilter for ANY(k IN keys(node) WHERE toString(node[k]) CONTAINS needle).
 *
 * The caller must obtain [fragment] from [propertyTextFragment], then evaluate the original
 * predicate on every returned candidate. Implementations may include false positives but
 * MUST NOT omit a node whose visible property text contains the fragment. Preserve canonical
 * node encounter order and charge inspected storage work to [workConsumer] when supplied.
 *
 * EnumConstant, ResourceValueNode, AnnotationNode, unknown node kinds, and unsupported
 * accessor semantics require fallback evaluation; include them conservatively or return
 * null. A null result means the caller must use its normal node scan, not an empty result.
 * External graphId/elementId/qualifiedId properties are the caller's responsibility: when
 * the graphId contains the fragment, bypass this prefilter and retain normal evaluation.
 */
interface NodePropertyTextCandidates {
    fun <T : Node> propertyTextCandidates(
        type: Class<T>,
        fragment: String,
        workConsumer: GraphWorkConsumer?
    ): Sequence<T>?

    /**
     * A conjunction of necessary fragments, possibly present in separate signature components.
     * Legacy backends may safely apply only the first fragment, yielding a wider superset.
     */
    fun <T : Node> propertyTextCandidates(
        type: Class<T>,
        fragments: List<String>,
        workConsumer: GraphWorkConsumer?
    ): Sequence<T>? = fragments.firstOrNull()?.let { propertyTextCandidates(type, it, workConsumer) }
}
