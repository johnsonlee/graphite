package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import it.unimi.dsi.fastutil.Arrays

/** Captures ordinal fields during node persistence without retaining decoded call-site nodes. */
internal class CallSiteOrdinalPersistenceInput(ordinalCount: Int) {
    private val ids = IntArray(ordinalCount)
    private val ordinals = IntArray(ordinalCount)
    private val origins = IntArray(ordinalCount)
    private var size = 0
    private var ordered = true

    fun add(node: CallSiteNode) {
        val ordinal = node.ordinal ?: return
        check(size < ids.size) { "Call-site ordinal count changed while saving" }
        val id = node.id.value
        if (size > 0 && ids[size - 1] > id) ordered = false
        ids[size] = id
        ordinals[size] = ordinal
        origins[size] = node.origin?.value ?: NO_ORIGIN
        size++
    }

    fun encode(): NodeSerializer.EncodedCallSiteOrdinals? {
        check(size == ids.size) { "Call-site ordinal count changed while saving" }
        if (size == 0) return null
        if (!ordered) sortById()
        return NodeSerializer.encodeCallSiteOrdinals(ids, ordinals, origins)
    }

    private fun sortById() {
        // Match sortedBy's stable order, even if a custom graph repeats a node id.
        // Ordinary ascending node streams need neither this array nor a sort.
        val encounterOrder = IntArray(size) { it }
        Arrays.quickSort(
            0,
            size,
            { left, right ->
                val comparison = ids[left].compareTo(ids[right])
                if (comparison != 0) comparison else encounterOrder[left].compareTo(encounterOrder[right])
            },
            { left, right ->
                ids.swap(left, right)
                ordinals.swap(left, right)
                origins.swap(left, right)
                encounterOrder.swap(left, right)
            }
        )
    }

    private fun IntArray.swap(left: Int, right: Int) {
        val value = this[left]
        this[left] = this[right]
        this[right] = value
    }
}
