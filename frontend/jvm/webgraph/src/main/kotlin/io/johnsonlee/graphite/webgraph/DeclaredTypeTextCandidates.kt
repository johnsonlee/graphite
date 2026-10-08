package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.checkThreadInterrupted
import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import java.util.concurrent.CancellationException

/**
 * Conservative text search over unique type expressions, without expanding render/info projections.
 * Fragments must come from propertyTextFragments: their ASCII identifier characters cannot cross
 * the punctuation joining rendered types or structured maps. Map keys are generated text too.
 * A hit may be an unbound declaration; the caller must still evaluate the complete node predicate.
 */
internal class DeclaredTypeTextCandidates(
    private val types: List<DeclaredType>,
    private val fragments: List<String>,
    private val work: GraphWorkConsumer?
) {
    private val matches = ByteArray(types.size)
    private val required = (1 shl fragments.size) - 1
    private var inspected = 0

    fun mayMatch(): Boolean {
        for (id in types.indices) {
            checkCancelled()
            if (matchType(id) == required) return true
        }
        return false
    }

    private fun matchType(id: Int): Int {
        val cached = matches[id].toInt()
        if (cached and COMPLETE != 0) return cached and required
        consume()
        val type = types[id]
        var mask = localMatches(type)
        type.owner?.let { mask = mask or matchReference(it) }
        type.component?.let { mask = mask or matchReference(it) }
        for (argument in type.arguments) mask = mask or matchReference(argument)
        matches[id] = (mask or COMPLETE).toByte()
        return mask
    }

    private fun matchReference(id: Int): Int {
        consume()
        return matchType(id)
    }

    private fun localMatches(type: DeclaredType): Int {
        var mask = matchText("kind", 0)
        mask = matchText(type.kind, mask)
        mask = matchText("arguments", mask)
        mask = matchProperty("name", type.name, mask)
        mask = matchProperty("scope", type.scope, mask)
        mask = matchProperty("variance", type.variance, mask)
        if (type.owner != null) mask = matchText("owner", mask)
        if (type.component != null) mask = matchText("component", mask)
        return mask
    }

    private fun matchProperty(key: String, value: String, mask: Int): Int =
        if (value.isEmpty()) mask else matchText(value, matchText(key, mask))

    private fun matchText(text: String, initial: Int): Int {
        var mask = initial
        for ((index, fragment) in fragments.withIndex()) {
            val bit = 1 shl index
            if (mask and bit == 0 && text.contains(fragment)) mask = mask or bit
        }
        return mask
    }

    private fun consume() {
        checkCancelled()
        work?.consume()
    }

    private fun checkCancelled() {
        if ((inspected++ and CANCELLATION_MASK) == 0) {
            checkThreadInterrupted { CancellationException("Declared type text scan interrupted") }
        }
    }

    private companion object {
        const val COMPLETE = 4
        const val CANCELLATION_MASK = 255
    }
}
