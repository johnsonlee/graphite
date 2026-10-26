package io.johnsonlee.graphite.cli

import com.google.gson.JsonParser
import io.johnsonlee.graphite.cypher.CypherCancellationSignal
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.graph.MethodTypes
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.MemberTypeKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("StringLiteralDuplication")
class DeclaredTypeExploreTest {
    @Test
    fun `node JSON and subgraphs expose declaration metadata from graph`() {
        val genericType = "generic_type"
        val typeInfo = "type_info"
        val field = FieldNode(
            NodeId.next(), FieldDescriptor(TypeDescriptor("Example"), "items", TypeDescriptor("java.util.List")), false
        )
        val graph = DefaultGraph.Builder().apply {
            addNode(field)
            setDeclaredTypes(DeclaredTypeTable(
                listOf(
                    DeclaredType("class", "java.lang.String"),
                    DeclaredType("class", "java.util.List", arguments = listOf(0))
                ),
                mapOf(MemberTypeKey("Example", "items", "Ljava/util/List;") to 1), emptyMap(), emptyMap()
            ))
        }.build()
        val mapped = nodeToMap(field, graph)
        assertEquals("java.util.List", mapped["fieldType"])
        assertEquals("java.util.List<java.lang.String>", mapped[genericType])
        val info = mapped[typeInfo] as Map<*, *>
        assertEquals("java.lang.String", ((info["arguments"] as List<*>).single() as Map<*, *>)["name"])
        val subgraph = ExploreRoutes().buildSubgraph(graph, field.id, 0)
        assertEquals(listOf(mapped), subgraph["nodes"])
        assertNull(nodeToMap(field)[genericType])
        assertNull(nodeToMap(field)[typeInfo])
        assertFalse(genericType in nodeToMap(field))
        assertFalse(typeInfo in nodeToMap(field))
        val external = field.copy(descriptor = field.descriptor.copy(name = "external"))
        assertFalse(genericType in nodeToMap(external, graph))
        assertFalse(typeInfo in nodeToMap(external, graph))
    }

    @Test
    fun `declared type maps and JSON use the same sparse optional members recursively`() {
        val table = DeclaredTypeTable(
            listOf(
                DeclaredType("class", "java.lang.String"),
                DeclaredType("class", "java.util.List", arguments = listOf(0))
            ), emptyMap(), emptyMap(), emptyMap()
        )
        val info = table.info(1)
        assertFalse("scope" in info)
        assertNull(info["scope"])
        val serialized = GsonCypherResponseSerializer().serialize(mapOf("info" to info), CypherCancellationSignal())
        val json = JsonParser.parseString(serialized).asJsonObject.getAsJsonObject("info")
        assertEquals(setOf("kind", "name", "arguments"), json.keySet())
        assertEquals("java.util.List", json["name"].asString)
        val argument = json.getAsJsonArray("arguments").single().asJsonObject
        assertEquals(setOf("kind", "name", "arguments"), argument.keySet())
        assertEquals("java.lang.String", argument["name"].asString)
        assertEquals(0, argument.getAsJsonArray("arguments").size())
        assertFalse("scope" in info)
    }

    @Test
    fun `endpoint declarations supplement erased parameters and return type`() {
        val owner = TypeDescriptor("Example")
        val list = TypeDescriptor("java.util.List")
        val method = MethodDescriptor(owner, "load", listOf(list), list)
        val graph = DefaultGraph.Builder().apply {
            addMethod(method)
            addMemberAnnotation(owner.className, method.name, "org.springframework.web.bind.annotation.GetMapping",
                mapOf("value" to "/items"))
            setDeclaredTypes(DeclaredTypeTable(
                listOf(
                    DeclaredType("class", "java.lang.String"),
                    DeclaredType("class", "java.util.List", arguments = listOf(0))
                ), emptyMap(), mapOf(MemberTypeKey(owner.className, method.name, method.descriptor) to MethodTypes(listOf(1), 1)),
                emptyMap()
            ))
        }.build()
        val endpoint = EndpointExtractor().extract(graph).single()
        assertEquals("GET", endpoint["httpMethod"])
        assertEquals("/items", endpoint["path"])
        assertEquals("java.util.List", endpoint["returns"])
        assertEquals(listOf("java.util.List"), endpoint["parameters"])
        assertEquals("java.util.List<java.lang.String>", endpoint["generic_return_type"])
        assertEquals(listOf("java.util.List<java.lang.String>"), endpoint["generic_parameter_types"])
        assertEquals(listOf(endpoint["return_type_info"]), endpoint["parameter_type_info"])
        assertEquals(emptyList<Any>(), endpoint["type_parameters"])
    }

}
