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

/** Declaration projections keep erased graph identities and declaration metadata separate. */
object DeclaredTypeProperties {
    val nodePropertyNames: List<String> = listOf(GENERIC_TYPE_PROPERTY, TYPE_INFO_PROPERTY)
    val methodPropertyNames: List<String> = listOf(
        "generic_return_type", GENERIC_PARAMETER_TYPES_PROPERTY, "return_type_info",
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
        return nodeBinding(node, graph)?.let { (table, id) ->
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
            true
        } ?: false
    }

    internal fun methodPropertyPresent(method: MethodDescriptor, property: String, graph: Graph?): Boolean? {
        val table = graph?.declaredTypes()?.takeIf { it.types.isNotEmpty() }
        return when {
            property !in methodPropertyNames -> null
            table == null -> false
            else -> boundMethodPresence(method, property, table, graph)
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
     * the declaration; the memo keeps that to one read for the row's five properties. Null when the
     * binding does not know the method or the table may change, so the decoded path decides.
     */
    private fun boundMethodPresence(
        method: MethodDescriptor, property: String, table: DeclaredTypeTable, graph: Graph
    ): Boolean? {
        val binding = graph.declaredTypeBinding() ?: return null
        val shape = bind(method, table, lastMethodShape) {
            binding.methodShape(method).takeIf { it != DeclaredTypeBinding.UNANSWERED }
        }
        return when {
            shape == null || !table.isImmutable() -> null
            shape == DeclaredTypeBinding.UNBOUND -> false
            else -> {
                val projects = projectsType(property, shape and DeclaredTypeBinding.HAS_PARAMETERS != 0) {
                    shape and DeclaredTypeBinding.HAS_BOUNDS != 0
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
        is ReturnNode -> table.methods[methodKey(node.method)]?.returnType
        else -> null
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
    private val lastMethodShape = ThreadLocal.withInitial { LastBinding<MethodDescriptor, Int>() }
    private val lastMethodBinding =
        ThreadLocal.withInitial { LastBinding<MethodDescriptor, Pair<DeclaredTypeTable, MethodTypes>>() }

    private fun methodKey(method: MethodDescriptor) = MemberTypeKey(
        method.declaringClass.className, method.name, method.descriptor
    )
}

private fun DeclaredTypeTable.isImmutable(): Boolean =
    (types as? ImmutableDeclaredTypeStorage)?.isImmutableTable(this) == true
