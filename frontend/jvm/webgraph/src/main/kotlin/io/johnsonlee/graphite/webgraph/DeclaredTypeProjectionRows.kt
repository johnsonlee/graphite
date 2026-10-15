package io.johnsonlee.graphite.webgraph

import java.nio.ByteBuffer

/** Absolute iteration over already validated GTY05 declaration values, without decoding names or scopes. */
internal object DeclaredTypeProjectionRows {
    private const val FORMAL_HEADER_BYTES = 12

    fun visit(input: ByteBuffer, kind: String, consume: (Int) -> Unit) {
        when (kind) {
            "field" -> consume(input.int)
            "method" -> { references(input, consume); consume(input.int); formals(input, consume) }
            "class" -> {
                formals(input, consume)
                val parent = input.int
                if (parent >= 0) consume(parent)
                references(input, consume)
            }
        }
    }

    private fun references(input: ByteBuffer, consume: (Int) -> Unit) { repeat(input.int) { consume(input.int) } }
    private fun formals(input: ByteBuffer, consume: (Int) -> Unit) {
        repeat(input.int) {
            input.position(input.position() + FORMAL_HEADER_BYTES)
            references(input, consume)
        }
    }
}
