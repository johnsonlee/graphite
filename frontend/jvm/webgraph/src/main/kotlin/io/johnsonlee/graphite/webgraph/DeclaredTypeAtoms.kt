package io.johnsonlee.graphite.webgraph

/** Absolute reads over type rows whose UTF-8, references and record boundaries were validated on load. */
internal interface DeclaredTypeAtoms {
    fun typeOffset(index: Int): Int
    fun atomInt(offset: Int): Int
    fun atomByte(offset: Int): Byte
    fun atomTextOffset(position: Int): Int
    fun nextTextField(position: Int): Int
    fun atomText(position: Int): String {
        val offset = atomTextOffset(position)
        return ByteArray(atomInt(offset)) { atomByte(offset + Int.SIZE_BYTES + it) }.toString(Charsets.UTF_8)
    }
    fun atomTextLength(position: Int): Int = atomInt(atomTextOffset(position))
    fun atomTextEquals(position: Int, expected: String): Boolean {
        if (expected.any { it.code > DECLARED_ATOM_ASCII_MAX }) return atomText(position) == expected
        val offset = atomTextOffset(position)
        return atomInt(offset) == expected.length &&
            expected.indices.all { atomByte(offset + Int.SIZE_BYTES + it).toInt() == expected[it].code }
    }
    fun atomContains(position: Int, fragment: String): Boolean {
        val offset = atomTextOffset(position)
        val length = atomInt(offset)
        for (start in 0..length - fragment.length) {
            if (fragment.indices.all { atomByte(offset + Int.SIZE_BYTES + start + it).toInt() == fragment[it].code }) return true
        }
        return false
    }
}

private const val DECLARED_ATOM_ASCII_MAX = 127
