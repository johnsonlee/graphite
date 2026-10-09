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
    private val atoms = (types as? DeclaredTypeAtoms)?.takeIf {
        fragments.all { fragment -> fragment.all { it.code <= ASCII_MAX } }
    }
    private val atomMatcher = atoms?.queryMatcher(fragments)
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
        val mask = atoms?.let { matchMappedType(id, it) } ?: matchDecodedType(types[id])
        matches[id] = (mask or COMPLETE).toByte()
        return mask
    }

    private fun matchDecodedType(type: DeclaredType): Int {
        var mask = localMatches(type)
        type.owner?.let { mask = mask or matchReference(it) }
        type.component?.let { mask = mask or matchReference(it) }
        for (argument in type.arguments) mask = mask or matchReference(argument)
        return mask
    }

    private fun matchMappedType(id: Int, source: DeclaredTypeAtoms): Int {
        var position = source.typeOffset(id)
        var mask = matchAtom(source, position, "kind", matchText("arguments", 0))
        position = source.nextTextField(position)
        mask = matchAtom(source, position, "name", mask)
        position = source.nextTextField(position)
        mask = matchAtom(source, position, "scope", mask)
        position = source.nextTextField(position)
        val owner = source.atomInt(position)
        val component = source.atomInt(position + Int.SIZE_BYTES)
        position += 2 * Int.SIZE_BYTES
        mask = matchAtom(source, position, "variance", mask)
        position = source.nextTextField(position)
        if (owner >= 0) mask = matchText("owner", mask) or matchReference(owner)
        if (component >= 0) mask = matchText("component", mask) or matchReference(component)
        val argumentCount = source.atomInt(position)
        position += Int.SIZE_BYTES
        repeat(argumentCount) {
            mask = mask or matchReference(source.atomInt(position))
            position += Int.SIZE_BYTES
        }
        return mask
    }

    private fun matchAtom(source: DeclaredTypeAtoms, position: Int, key: String, initial: Int): Int {
        atomMatcher?.let { matcher ->
            val facts = matcher.match(position)
            val mask = initial or (facts and required)
            return if (facts and DeclaredTypeAtomMatcher.NON_EMPTY == 0) mask else matchText(key, mask)
        }
        val length = source.atomTextLength(position)
        var mask = if (length == 0) initial else matchText(key, initial)
        for ((index, fragment) in fragments.withIndex()) {
            val bit = 1 shl index
            if (mask and bit == 0 && source.atomContains(position, fragment)) {
                mask = mask or bit
            }
        }
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
        const val ASCII_MAX = 127
        const val COMPLETE = 4
        const val CANCELLATION_MASK = 255
    }
}
