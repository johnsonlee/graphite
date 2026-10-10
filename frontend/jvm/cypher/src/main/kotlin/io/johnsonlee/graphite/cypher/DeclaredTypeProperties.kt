package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.jvmTypeDescriptor
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

    fun isNodeProperty(node: Node, property: String): Boolean =
        property in nodePropertyNames && supportsNode(node)

    fun hasNodeProperties(node: Node, graph: Graph?): Boolean =
        supportsNode(node) && graph?.declaredTypes()?.let { nodeTypeId(node, it) != null } == true

    fun hasMethodProperties(method: MethodDescriptor, graph: Graph?): Boolean =
        graph?.declaredTypes()?.methods?.containsKey(methodKey(method)) == true

    /** Null means this property is outside the declaration specialization. */
    internal fun nodePropertyPresent(node: Node, property: String, graph: Graph?): Boolean? {
        if (!isNodeProperty(node, property)) return null
        return nodeBinding(node, graph)?.let { (table, id) ->
            if ((table.types as? ImmutableDeclaredTypeStorage)?.isImmutableTable(table) == true) {
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
        if (property !in methodPropertyNames) return null
        return methodBinding(method, graph)?.let { (table, declaration) ->
            if ((table.types as? ImmutableDeclaredTypeStorage)?.isImmutableTable(table) == true) {
                // Empty projections do not call render/info. Preserve their original validation boundary.
                val projectsType = when (property) {
                    GENERIC_PARAMETER_TYPES_PROPERTY, PARAMETER_TYPE_INFO_PROPERTY -> declaration.parameterTypes.isNotEmpty()
                    TYPE_PARAMETERS_PROPERTY -> declaration.typeParameters.any { it.bounds.isNotEmpty() }
                    else -> true
                }
                if (projectsType) table.validate()
                true
            } else {
                projectMethodProperty(table, declaration, property) != null
            }
        } ?: false
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
        if (!supportsNode(node)) return null
        return graph?.declaredTypes()?.let { table -> nodeTypeId(node, table)?.let { table to it } }
    }

    private fun nodeTypeId(node: Node, table: DeclaredTypeTable): Int? = when (node) {
        is FieldNode -> table.fields[MemberTypeKey(
            node.descriptor.declaringClass.className, node.descriptor.name, jvmTypeDescriptor(node.descriptor.type.className)
        )]
        is ParameterNode -> table.methods[methodKey(node.method)]?.parameterTypes?.getOrNull(node.index)
        is ReturnNode -> table.methods[methodKey(node.method)]?.returnType
        else -> null
    }

    private fun methodBinding(method: MethodDescriptor, graph: Graph?): Pair<DeclaredTypeTable, MethodTypes>? =
        graph?.declaredTypes()?.let { table -> table.methods[methodKey(method)]?.let { table to it } }

    private fun methodKey(method: MethodDescriptor) = MemberTypeKey(
        method.declaringClass.className, method.name, method.descriptor
    )
}
