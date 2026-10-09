package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.DeclaredTypeTextField
import io.johnsonlee.graphite.graph.DeclaredTypeValidationAccess

/** Semantic absolute reads over rows whose wire boundaries and UTF-8 are already fully checked. */
internal class MappedDeclaredTypeValidationAccess(
    private val atoms: DeclaredTypeAtoms,
    override val size: Int
) : DeclaredTypeValidationAccess {
    override fun text(id: Int, field: DeclaredTypeTextField): String = atoms.atomText(fieldOffset(id, field))
    override fun textIsEmpty(id: Int, field: DeclaredTypeTextField): Boolean = textUtf8Length(id, field) == 0
    override fun textEquals(id: Int, field: DeclaredTypeTextField, expected: String): Boolean =
        atoms.atomTextEquals(fieldOffset(id, field), expected)
    override fun textUtf8Length(id: Int, field: DeclaredTypeTextField): Int = atoms.atomTextLength(fieldOffset(id, field))
    override fun owner(id: Int): Int? = atoms.atomInt(referencesOffset(id)).takeUnless { it == -1 }
    override fun component(id: Int): Int? = atoms.atomInt(referencesOffset(id) + Int.SIZE_BYTES).takeUnless { it == -1 }
    override fun argumentCount(id: Int): Int = atoms.atomInt(argumentsOffset(id))
    override fun argument(id: Int, index: Int): Int {
        val offset = argumentsOffset(id)
        if (index < 0 || index >= atoms.atomInt(offset)) throw IndexOutOfBoundsException("Argument index $index")
        return atoms.atomInt(offset + Int.SIZE_BYTES * (index + 1))
    }

    private fun fieldOffset(id: Int, field: DeclaredTypeTextField): Int {
        var offset = atoms.typeOffset(id)
        repeat(field.ordinal) { offset = atoms.nextTextField(offset) }
        if (field == DeclaredTypeTextField.VARIANCE) offset += Int.SIZE_BYTES * 2
        return offset
    }

    private fun referencesOffset(id: Int): Int = atoms.nextTextField(fieldOffset(id, DeclaredTypeTextField.SCOPE))
    private fun argumentsOffset(id: Int): Int = atoms.nextTextField(fieldOffset(id, DeclaredTypeTextField.VARIANCE))

}
