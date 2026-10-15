package io.johnsonlee.graphite.graph

/** Semantic fields used by the shared validator; no persistence layout crosses this interface. */
enum class DeclaredTypeTextField { KIND, NAME, SCOPE, VARIANCE }

/** Optional read-only access without materializing declared type rows. All checks remain in core. */
interface DeclaredTypeValidationAccess {
    val size: Int
    fun text(id: Int, field: DeclaredTypeTextField): String
    fun textIsEmpty(id: Int, field: DeclaredTypeTextField): Boolean
    fun textEquals(id: Int, field: DeclaredTypeTextField, expected: String): Boolean
    fun textUtf8Length(id: Int, field: DeclaredTypeTextField): Int
    fun owner(id: Int): Int?
    fun component(id: Int): Int?
    fun argumentCount(id: Int): Int
    fun argument(id: Int, index: Int): Int
}

internal class OrdinaryDeclaredTypeValidationAccess(private val types: List<DeclaredType>) : DeclaredTypeValidationAccess {
    override val size: Int get() = types.size
    override fun text(id: Int, field: DeclaredTypeTextField): String = types[id].let {
        when (field) {
            DeclaredTypeTextField.KIND -> it.kind
            DeclaredTypeTextField.NAME -> it.name
            DeclaredTypeTextField.SCOPE -> it.scope
            DeclaredTypeTextField.VARIANCE -> it.variance
        }
    }
    override fun textIsEmpty(id: Int, field: DeclaredTypeTextField): Boolean = text(id, field).isEmpty()
    override fun textEquals(id: Int, field: DeclaredTypeTextField, expected: String): Boolean = text(id, field) == expected
    override fun textUtf8Length(id: Int, field: DeclaredTypeTextField): Int = text(id, field).toByteArray(Charsets.UTF_8).size
    override fun owner(id: Int): Int? = types[id].owner
    override fun component(id: Int): Int? = types[id].component
    override fun argumentCount(id: Int): Int = types[id].arguments.size
    override fun argument(id: Int, index: Int): Int = types[id].arguments[index]
}
