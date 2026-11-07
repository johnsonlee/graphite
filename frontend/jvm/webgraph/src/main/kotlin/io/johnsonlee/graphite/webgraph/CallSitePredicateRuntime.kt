package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate

internal class CallSitePredicateRuntime(
    private val predicate: StringPropertyPredicate,
    sharedStates: MutableMap<CallSitePredicateKey, ByteArray?>,
    private val stringTable: StringTable,
    private val trigramSignatures: LongArray
) {
    val exactStringId: Int? = if (
        predicate.transform == null && predicate.mode == StringMatchMode.EQUALS
    ) {
        stringTable.findId(predicate.expected)
    } else {
        null
    }

    private val requiredTrigramSignature: Long? = predicate.expected
        .takeIf { predicate.requiresTrigramSignature() }
        ?.let(::callSiteTrigramSignature)

    private val states: ByteArray? = if (exactStringId == null && requiredTrigramSignature == null) {
        val stateKey = CallSitePredicateKey(predicate.transform, predicate.mode, predicate.expected)
        sharedStates.getOrPut(stateKey) { ByteArray(stringTable.size()) }
    } else {
        null
    }

    fun matches(stringId: Int): Boolean {
        requiredTrigramSignature?.let { required ->
            if (trigramSignatures[stringId] and required != required) return false
        }
        val state = states
        return if (state == null) stringMatches(
            stringTable.get(stringId), predicate.transform, predicate.mode, predicate.expected
        ) else when (state[stringId]) {
            RAW_STRING_MATCH -> true
            RAW_STRING_MISS -> false
            else -> stringMatches(
                stringTable.get(stringId),
                predicate.transform,
                predicate.mode,
                predicate.expected
            ).also { matched ->
                state[stringId] = if (matched) RAW_STRING_MATCH else RAW_STRING_MISS
            }
        }
    }
}
