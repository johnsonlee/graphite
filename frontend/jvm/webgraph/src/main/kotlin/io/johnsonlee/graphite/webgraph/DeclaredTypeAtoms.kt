package io.johnsonlee.graphite.webgraph

/** Absolute reads over type rows whose UTF-8, references and record boundaries were validated on load. */
internal interface DeclaredTypeAtoms {
    fun typeOffset(index: Int): Int
    fun atomInt(offset: Int): Int
    fun atomByte(offset: Int): Byte
}
