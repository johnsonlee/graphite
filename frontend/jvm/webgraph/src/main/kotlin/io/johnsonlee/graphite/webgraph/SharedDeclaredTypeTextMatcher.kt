package io.johnsonlee.graphite.webgraph

import it.unimi.dsi.lang.MutableString

/**
 * Query-local facts for already validated shared IDs. Slot collisions only cause redecoding.
 * Fixed arrays never grow with the dictionary/type table; one buffer retains no decoded Strings.
 */
internal class SharedDeclaredTypeTextMatcher(
    fragments: List<String>,
    private val readText: (Int, MutableString) -> Unit
) {
    private val fragments = fragments.toList()
    private val ids = IntArray(CAPACITY) { -1 }
    private val facts = ByteArray(CAPACITY)
    private val buffer = MutableString()

    fun match(id: Int): Int {
        val slot = id and (CAPACITY - 1)
        if (ids[slot] == id) return facts[slot].toInt()
        readText(id, buffer)
        var mask = if (buffer.length == 0) 0 else DeclaredTypeAtomMatcher.NON_EMPTY
        for ((index, fragment) in fragments.withIndex()) {
            if (reusableContains(buffer, null, fragment)) mask = mask or (1 shl index)
        }
        // Publish the ID only after a complete decode and match, including on slot eviction.
        facts[slot] = mask.toByte()
        ids[slot] = id
        return mask
    }

    private companion object {
        const val CAPACITY = 1024
    }
}
