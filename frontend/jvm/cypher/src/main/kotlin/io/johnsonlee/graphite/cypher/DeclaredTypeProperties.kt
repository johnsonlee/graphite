package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.jvmTypeDescriptor
import io.johnsonlee.graphite.graph.DeclaredTypeBinding
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.ImmutableDeclaredTypeStorage
import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.graph.MethodTypes

private const val GENERIC_TYPE_PROPERTY = "generic_type"
private const val TYPE_INFO_PROPERTY = "type_info"
private const val GENERIC_PARAMETER_TYPES_PROPERTY = "generic_parameter_types"
private const val PARAMETER_TYPE_INFO_PROPERTY = "parameter_type_info"
private const val TYPE_PARAMETERS_PROPERTY = "type_parameters"
private const val GENERIC_RETURN_TYPE_PROPERTY = "generic_return_type"
private const val RETURN_TYPE_INFO_PROPERTY = "return_type_info"
private const val RETURN_RENDERED = 0
private const val PARAMETERS_RENDERED = 1
private const val RETURN_STRUCTURED = 2
private const val PARAMETERS_STRUCTURED = 3
private const val TYPE_PARAMETERS = 4

/** Declaration projections keep erased graph identities and declaration metadata separate. */
object DeclaredTypeProperties {
    val nodePropertyNames: List<String> = listOf(GENERIC_TYPE_PROPERTY, TYPE_INFO_PROPERTY)
    val methodPropertyNames: List<String> = listOf(
        GENERIC_RETURN_TYPE_PROPERTY, GENERIC_PARAMETER_TYPES_PROPERTY, RETURN_TYPE_INFO_PROPERTY,
        PARAMETER_TYPE_INFO_PROPERTY, TYPE_PARAMETERS_PROPERTY
    )

    // Asked for every property read of every node: the instance check first, then two
    // comparisons, not a list search.
    fun isNodeProperty(node: Node, property: String): Boolean =
        supportsNode(node) && (property == GENERIC_TYPE_PROPERTY || property == TYPE_INFO_PROPERTY)

    fun hasNodeProperties(node: Node, graph: Graph?): Boolean = nodeBinding(node, graph) != null

    fun hasMethodProperties(method: MethodDescriptor, graph: Graph?): Boolean = methodBinding(method, graph) != null

    /** Null means this property is outside the declaration specialization. */
    internal fun nodePropertyPresent(node: Node, property: String, graph: Graph?): Boolean? {
        if (!isNodeProperty(node, property)) return null
        // A persisted graph answers presence from the bits its saver wrote, without resolving the row.
        val table = graph?.declaredTypes()?.takeIf { it.types.isNotEmpty() }
        val persisted = if (table != null && table.isImmutable()) {
            graph.declaredTypeBinding()?.nodePresence(node) ?: DeclaredTypeBinding.UNANSWERED
        } else {
            DeclaredTypeBinding.UNANSWERED
        }
        return when (persisted) {
            DeclaredTypeBinding.PRESENT -> {
                table?.validate()
                true
            }
            DeclaredTypeBinding.ABSENT -> false
            else -> decodedNodePresence(nodeBinding(node, graph), property)
        }
    }

    internal fun methodPropertyPresent(method: MethodDescriptor, property: String, graph: Graph?): Boolean? {
        val kind = methodPropertyKind(property)
        val table = if (kind < 0) null else graph?.declaredTypes()?.takeIf { it.types.isNotEmpty() }
        return when {
            kind < 0 -> null
            table == null -> false
            else -> graph?.let { boundMethodPresence(method, kind, table, it) }
                ?: decodedMethodPresence(method, property, graph)
        }
    }

    private fun decodedMethodPresence(method: MethodDescriptor, property: String, graph: Graph?): Boolean =
        methodBinding(method, graph)?.let { (table, declaration) ->
            if (table.isImmutable()) {
                // Empty projections do not call render/info. Preserve their original validation boundary.
                val projects = projectsType(property, declaration.parameterTypes.isNotEmpty()) {
                    declaration.typeParameters.any { it.bounds.isNotEmpty() }
                }
                if (projects) table.validate()
                true
            } else {
                projectMethodProperty(table, declaration, property) != null
            }
        } ?: false

    /**
     * A persisted graph answers a presence check from two flags of the row, read without decoding
     * the declaration. The descriptor remembers the answer for this table, so a row's five
     * properties read a field after the first; a hand-built descriptor or a mutable table hands
     * the question to the decoded path (null).
     */
    private fun boundMethodPresence(method: MethodDescriptor, kind: Int, table: DeclaredTypeTable, graph: Graph): Boolean? {
        if (!table.isImmutable()) return null
        val shape = if (method.declaredShapeOwner === table) {
            method.declaredShape
        } else {
            (graph.declaredTypeBinding()?.methodShape(method) ?: DeclaredTypeBinding.UNANSWERED).also {
                method.declaredShape = it
                method.declaredShapeOwner = table
            }
        }
        return when (shape) {
            DeclaredTypeBinding.UNANSWERED -> null
            DeclaredTypeBinding.UNBOUND -> false
            else -> {
                val projects = when (kind) {
                    PARAMETERS_RENDERED, PARAMETERS_STRUCTURED -> shape and DeclaredTypeBinding.HAS_PARAMETERS != 0
                    TYPE_PARAMETERS -> shape and DeclaredTypeBinding.HAS_BOUNDS != 0
                    else -> true
                }
                if (projects) table.validate()
                true
            }
        }
    }

    private inline fun projectsType(
        property: String, hasParameters: Boolean, hasBounds: () -> Boolean
    ): Boolean = when (property) {
        GENERIC_PARAMETER_TYPES_PROPERTY, PARAMETER_TYPE_INFO_PROPERTY -> hasParameters
        TYPE_PARAMETERS_PROPERTY -> hasBounds()
        else -> true
    }

    fun nodeProperties(node: Node, graph: Graph?): Map<String, Any?> = nodeBinding(node, graph)?.let { (table, id) ->
        mapOf(GENERIC_TYPE_PROPERTY to table.render(id), TYPE_INFO_PROPERTY to table.info(id))
    }.orEmpty()

    fun nodeProperty(node: Node, property: String, graph: Graph?): Any? = nodeBinding(node, graph)?.let { (table, id) ->
        when (property) {
            GENERIC_TYPE_PROPERTY -> table.render(id)
            TYPE_INFO_PROPERTY -> table.info(id)
            else -> null
        }
    }

    fun methodProperties(method: MethodDescriptor, graph: Graph?): Map<String, Any?> =
        methodBinding(method, graph)?.let { (table, declaration) ->
            methodPropertyNames.associateWith { projectMethodProperty(table, declaration, it) }
        }.orEmpty()

    fun methodProperty(method: MethodDescriptor, property: String, graph: Graph?): Any? =
        methodBinding(method, graph)?.let { (table, declaration) -> projectMethodProperty(table, declaration, property) }

    private fun projectMethodProperty(table: DeclaredTypeTable, declaration: MethodTypes, property: String): Any? =
        when (property) {
            "generic_return_type" -> table.render(declaration.returnType)
            GENERIC_PARAMETER_TYPES_PROPERTY -> declaration.parameterTypes.map(table::render)
            "return_type_info" -> table.info(declaration.returnType)
            PARAMETER_TYPE_INFO_PROPERTY -> declaration.parameterTypes.map(table::info)
            TYPE_PARAMETERS_PROPERTY -> declaration.typeParameters.map { parameter ->
                mapOf(
                    "name" to parameter.name,
                    "scope" to parameter.scope,
                    "bounds" to parameter.bounds.map(table::render),
                    "bound_info" to parameter.bounds.map(table::info)
                )
            }
            else -> null
        }

    private fun supportsNode(node: Node): Boolean = node is FieldNode || node is ParameterNode || node is ReturnNode

    private fun nodeBinding(node: Node, graph: Graph?): Pair<DeclaredTypeTable, Int>? {
        val table = if (supportsNode(node)) graph?.declaredTypes() else null
        if (table == null || table.types.isEmpty()) return null
        return bind(node, table, lastNodeBinding) {
            // A persisted graph binds its own nodes by the string IDs their records carry; the key
            // lookup, which decodes and hashes three strings per node, answers only what it cannot.
            val bound = graph?.declaredTypeBinding()?.let { binding ->
                if (node is FieldNode) binding.fieldTypeId(node) else binding.memberTypeId(node)
            } ?: DeclaredTypeBinding.UNANSWERED
            when (bound) {
                DeclaredTypeBinding.UNANSWERED -> nodeTypeId(node, table)?.let { id -> table to id }
                DeclaredTypeBinding.UNBOUND -> null
                else -> table to bound
            }
        }
    }

    private fun nodeTypeId(node: Node, table: DeclaredTypeTable): Int? = when (node) {
        is FieldNode -> table.fields[MemberTypeKey(
            node.descriptor.declaringClass.className, node.descriptor.name, jvmTypeDescriptor(node.descriptor.type.className)
        )]
        is ParameterNode -> table.methods[methodKey(node.method)]?.parameterTypes?.getOrNull(node.index)
        else -> table.methods[methodKey((node as ReturnNode).method)]?.returnType // supportsNode admits no other kind
    }

    private fun methodBinding(method: MethodDescriptor, graph: Graph?): Pair<DeclaredTypeTable, MethodTypes>? {
        val table = graph?.declaredTypes()?.takeIf { it.types.isNotEmpty() } ?: return null
        return bind(method, table, lastMethodBinding) {
            val binding = graph.declaredTypeBinding()
            val bound = binding?.methodTypes(method)
            if (binding != null && bound !== DeclaredTypeBinding.UNANSWERED_METHOD) bound?.let { table to it }
            else table.methods[methodKey(method)]?.let { table to it }
        }
    }

    private inline fun <R : Any, B : Any> bind(
        receiver: R, table: DeclaredTypeTable, memo: ThreadLocal<LastBinding<R, B>>, lookup: () -> B?
    ): B? {
        val last = if (table.isImmutable()) memo.get() else null
        if (last != null && last.receiver === receiver && last.table === table) return last.binding
        return lookup().also { last?.remember(receiver, table, it) }
    }

    /**
     * The receiver bound last on this thread. A row projects several declaration properties of
     * one node or method, and each looked the member up again: a key rebuilt from its type
     * names, hashed, and matched against decoded text. Only an immutable table is remembered;
     * a mutable one may have changed between two properties of the same row.
     */
    private class LastBinding<R : Any, B : Any> {
        var receiver: R? = null
        var table: DeclaredTypeTable? = null
        var binding: B? = null

        fun remember(receiver: R, table: DeclaredTypeTable, binding: B?) {
            this.receiver = receiver
            this.table = table
            this.binding = binding
        }
    }

    private val lastNodeBinding = ThreadLocal.withInitial { LastBinding<Node, Pair<DeclaredTypeTable, Int>>() }
    private val lastMethodBinding =
        ThreadLocal.withInitial { LastBinding<MethodDescriptor, Pair<DeclaredTypeTable, MethodTypes>>() }

    private fun methodKey(method: MethodDescriptor) = MemberTypeKey(
        method.declaringClass.className, method.name, method.descriptor
    )
}

private fun DeclaredTypeTable.isImmutable(): Boolean =
    (types as? ImmutableDeclaredTypeStorage)?.isImmutableTable(this) == true

/** Presence of a node property once its row is bound: the row exists, and what rendering it would validate is validated. */
private fun decodedNodePresence(binding: Pair<DeclaredTypeTable, Int>?, property: String): Boolean {
    val (table, id) = binding ?: return false
    if (table.isImmutable()) {
        // render/info validate before traversing the selected type, including unrelated rows.
        table.validate()
    } else {
        // Ordinary public collections may have changed since the cached validation succeeded.
        when (property) {
            GENERIC_TYPE_PROPERTY -> table.render(id)
            else -> table.info(id)
        }
    }
    return true
}

/** Which of the five declaration properties [property] is, by one string switch; -1 for none. */
private fun methodPropertyKind(property: String): Int = when (property) {
    GENERIC_RETURN_TYPE_PROPERTY -> RETURN_RENDERED
    GENERIC_PARAMETER_TYPES_PROPERTY -> PARAMETERS_RENDERED
    RETURN_TYPE_INFO_PROPERTY -> RETURN_STRUCTURED
    PARAMETER_TYPE_INFO_PROPERTY -> PARAMETERS_STRUCTURED
    TYPE_PARAMETERS_PROPERTY -> TYPE_PARAMETERS
    else -> -1
}
