package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.checkThreadInterrupted
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.ImmutableDeclaredTypeStorage
import java.util.concurrent.CancellationException

/** Immutable ASCII identifier n-gram union; MAYBE still requires the complete node predicate. */
internal class DeclaredTypeTextSummary private constructor(private val bits: LongArray) {
    fun mayContain(fragment: String): Boolean {
        checkCancelled(0)
        if (fragment.isEmpty() || !fragment.withIndex().all { (index, value) ->
                checkCancelled(index)
                symbol(value) >= 0
            }) return true
        var previous = 0
        var pair = 0
        return fragment.withIndex().all { (index, value) ->
            checkCancelled(index)
            val current = symbol(value)
            val bit = when (index) {
                0 -> current
                1 -> PAIR_BASE + previous * ALPHABET_SIZE + current
                else -> TRIPLE_BASE + pair * ALPHABET_SIZE + current
            }
            pair = previous * ALPHABET_SIZE + current
            previous = current
            bits[bit ushr WORD_SHIFT] and (1L shl (bit and WORD_MASK)) != 0L
        }
    }

    /** Union type-name atoms with generated enum/scope text without including member index keys. */
    fun union(other: DeclaredTypeTextSummary): DeclaredTypeTextSummary =
        DeclaredTypeTextSummary(LongArray(bits.size) { index ->
            checkCancelled(index)
            bits[index] or other.bits[index]
        })

    /** Only the loader owns this builder. Snapshotting prevents later writes reaching readers. */
    class Builder {
        private val bits = LongArray(WORDS)
        private var previous = -1
        private var pair = -1
        private var inspected = 0

        init {
            for (key in listOf("kind", "name", "scope", "owner", "component", "variance", "arguments")) {
                beginText()
                key.forEach(::add)
            }
        }

        fun beginText() { previous = -1; pair = -1 }

        fun add(character: Char) {
            checkCancelled(inspected++)
            val current = symbol(character)
            if (current < 0) {
                beginText()
            } else {
                set(current)
                if (previous >= 0) set(PAIR_BASE + previous * ALPHABET_SIZE + current)
                if (pair >= 0) set(TRIPLE_BASE + pair * ALPHABET_SIZE + current)
                pair = if (previous < 0) -1 else previous * ALPHABET_SIZE + current
                previous = current
            }
        }

        fun build(): DeclaredTypeTextSummary = DeclaredTypeTextSummary(bits.copyOf())

        private fun set(bit: Int) { bits[bit ushr WORD_SHIFT] = bits[bit ushr WORD_SHIFT] or (1L shl (bit and WORD_MASK)) }
    }

    private companion object {
        const val ALPHABET_SIZE = 63
        const val PAIR_BASE = 64
        const val TRIPLE_BASE = 4096
        const val WORDS = 3971 // 31,768 bytes: 1 character word + 63 pair words + 3907 triple words.
        const val WORD_SHIFT = 6
        const val WORD_MASK = 63
        const val CANCELLATION_MASK = 255
        const val LOWERCASE_BASE = 26
        const val UNDERSCORE = 52
        const val DIGIT_BASE = 53

        fun symbol(value: Char): Int = when (value) {
            in 'A'..'Z' -> value - 'A'
            in 'a'..'z' -> LOWERCASE_BASE + (value - 'a')
            '_' -> UNDERSCORE
            in '0'..'9' -> DIGIT_BASE + (value - '0')
            else -> -1
        }

        fun checkCancelled(index: Int) {
            if ((index and CANCELLATION_MASK) == 0) {
                checkThreadInterrupted { CancellationException("Declared type summary search interrupted") }
            }
        }
    }
}

/** Mutable/custom/copied backings cannot borrow a previously validated negative summary. */
internal fun declaredTextMayMatch(table: DeclaredTypeTable, fragments: List<String>): Boolean {
    checkThreadInterrupted { CancellationException("Declared type summary search interrupted") }
    val immutable = table.types as? ImmutableDeclaredTypeStorage
    val summary = (table.types as? DeclaredTypeAtoms)?.textSummary
    return immutable?.isImmutableTable(table) != true || summary == null || fragments.all(summary::mayContain)
}
