package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.jvmTypeDescriptor
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.graph.MethodTypes

private const val GENERIC_TYPE_PROPERTY = "generic_type"
private const val TYPE_INFO_PROPERTY = "type_info"

/** Declaration projections keep erased graph identities and declaration metadata separate. */
object DeclaredTypeProperties {
    val nodePropertyNames: List<String> = listOf(GENERIC_TYPE_PROPERTY, TYPE_INFO_PROPERTY)
    val methodPropertyNames: List<String> = listOf(
        "generic_return_type", "generic_parameter_types", "return_type_info", "parameter_type_info", "type_parameters"
    )

    fun isNodeProperty(node: Node, property: String): Boolean =
        property in nodePropertyNames && supportsNode(node)

    fun hasNodeProperties(node: Node, graph: Graph?): Boolean =
        supportsNode(node) && graph?.declaredTypes()?.let { nodeTypeId(node, it) != null } == true

    fun hasMethodProperties(method: MethodDescriptor, graph: Graph?): Boolean =
        graph?.declaredTypes()?.methods?.containsKey(methodKey(method)) == true

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
            "generic_parameter_types" -> declaration.parameterTypes.map(table::render)
            "return_type_info" -> table.info(declaration.returnType)
            "parameter_type_info" -> declaration.parameterTypes.map(table::info)
            "type_parameters" -> declaration.typeParameters.map { parameter ->
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
