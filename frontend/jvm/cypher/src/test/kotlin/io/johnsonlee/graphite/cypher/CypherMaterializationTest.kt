package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class CypherMaterializationTest : CypherExecutorTestFixture() {

    @Test
    fun `union distinct`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN n.value AS v UNION MATCH (m:IntConstant) RETURN m.value AS v")
        // UNION distinct: {42, 7} from first + {42, 7} from second = distinct {42, 7}
        assertEquals(2, result.rows.size)
        val values = result.rows.map { it["v"] }.toSet()
        assertTrue(values.contains(42))
        assertTrue(values.contains(7))
    }

    @Test
    fun `union all`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN n.value AS v UNION ALL MATCH (m:IntConstant) RETURN m.value AS v")
        // UNION ALL: 2 from first + 2 from second = 4
        assertEquals(4, result.rows.size)
    }

    @Test
    fun `return full StringConstant node as map`() {
        val result = executor.execute("MATCH (n:StringConstant) RETURN n")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST") val nodeMap = result.rows[0]["n"] as Map<String, Any?>
        assertEquals("hello", nodeMap["value"])
        assertEquals("StringConstant", nodeMap["type"])
    }

    @Test
    fun `return full LongConstant node as map`() {
        val result = executor.execute("MATCH (n:LongConstant) RETURN n")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST") val nodeMap = result.rows[0]["n"] as Map<String, Any?>
        assertEquals(999999999999L, nodeMap["value"])
        assertEquals("LongConstant", nodeMap["type"])
    }

    @Test
    fun `return full BooleanConstant node as map`() {
        val result = executor.execute("MATCH (n:BooleanConstant) RETURN n")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST") val nodeMap = result.rows[0]["n"] as Map<String, Any?>
        assertEquals(true, nodeMap["value"])
        assertEquals("BooleanConstant", nodeMap["type"])
    }

    @Test
    fun `return full EnumConstant node as map`() {
        val result = executor.execute("MATCH (n:EnumConstant) RETURN n")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST") val nodeMap = result.rows[0]["n"] as Map<String, Any?>
        assertEquals("ACTIVE", nodeMap["name"])
        assertEquals("com.example.Status", nodeMap["enum_type"])
    }

    @Test
    fun `return full FieldNode as map`() {
        val result = executor.execute("MATCH (n:FieldNode) RETURN n")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST") val nodeMap = result.rows[0]["n"] as Map<String, Any?>
        assertEquals("name", nodeMap["name"])
        assertEquals("java.lang.String", nodeMap["type"])
        assertEquals("com.example.Service", nodeMap["class"])
        assertEquals(false, nodeMap["static"])
    }

    @Test
    fun `return full ParameterNode as map`() {
        val result = executor.execute("MATCH (n:ParameterNode) RETURN n")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST") val nodeMap = result.rows[0]["n"] as Map<String, Any?>
        assertEquals(0, nodeMap["index"])
        assertEquals("int", nodeMap["type"])
    }

    @Test
    fun `return full ReturnNode as map`() {
        val result = executor.execute("MATCH (n:`ReturnNode`) RETURN n")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST") val nodeMap = result.rows[0]["n"] as Map<String, Any?>
        assertTrue((nodeMap["method"] as String).contains("process"))
    }

    @Test
    fun `return full LocalVariable as map`() {
        val result = executor.execute("MATCH (n:LocalVariable) WHERE n.name = 'x' RETURN n")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST") val nodeMap = result.rows[0]["n"] as Map<String, Any?>
        assertEquals("x", nodeMap["name"])
        assertEquals("int", nodeMap["type"])
    }

    @Test
    fun `return full CallSiteNode as map`() {
        val result = executor.execute("MATCH (n:CallSiteNode) WHERE n.callee_name = 'save' RETURN n")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST") val nodeMap = result.rows[0]["n"] as Map<String, Any?>
        assertEquals("com.example.Repository", nodeMap["callee_class"])
        assertEquals("save", nodeMap["callee_name"])
        assertEquals("com.example.Service", nodeMap["caller_class"])
        assertEquals("process", nodeMap["caller_name"])
        assertEquals(10, nodeMap["line"])
        assertTrue(nodeMap.containsKey("ordinal"), nodeMap.keys.toString())
    }

    @Test
    fun `materialize list value`() {
        // Return a list containing a node - should materialize nodes inside lists
        val result = executor.execute("MATCH (n:IntConstant) RETURN collect(n) AS nodes")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST") val nodes = result.rows[0]["nodes"] as List<*>
        assertEquals(2, nodes.size)
        // Each element should be a materialized map
        assertTrue(nodes[0] is Map<*, *>)
    }

    @Test
    fun `materialize map value`() {
        // Use a WITH that creates a map containing a node value indirectly
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value = 42 RETURN {v: n.value} AS m")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST") val map = result.rows[0]["m"] as Map<String, Any?>
        assertEquals(42, map["v"])
    }

    @Test
    fun `budgeted execution materializes named paths nested in maps`() {
        val budgeted = CypherExecutor(graph, CypherExecutionBudget(maxWorkUnits = 100))
        val result =
            budgeted.execute("MATCH p=(a:IntConstant {value: 42})-[:DATAFLOW]->(b:ParameterNode) " + "RETURN p, {path: p} AS nested")

        val path = result.rows.single()["p"] as List<*>
        assertEquals(3, path.size)
        assertTrue(path[0] is Map<*, *>)
        assertTrue(path[1] is DataFlowEdge)
        val nested = result.rows.single()["nested"] as Map<*, *>
        assertEquals(path, nested["path"])
    }

    @Test
    fun `named path retains anonymous endpoint nodes`() {
        val result = executor.execute("MATCH p=(:IntConstant {value: 42})-[:DATAFLOW]->(:ParameterNode) RETURN p")

        val path = result.rows.single()["p"] as List<*>
        assertEquals(3, path.size)
        assertEquals(42, (path[0] as Map<*, *>)["value"])
        assertTrue(path[1] is DataFlowEdge)
        assertEquals(0, (path[2] as Map<*, *>)["index"])
    }

    @Test
    fun `return NullConstant as map`() {
        val result = executor.execute("MATCH (n:NullConstant) RETURN n.id")
        assertEquals(1, result.rows.size)
    }

    @Test
    fun `return full LongConstant node as map with value`() {
        val result = executor.execute("MATCH (n:LongConstant) RETURN n")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST") val nodeMap = result.rows[0]["n"] as Map<String, Any?>
        assertEquals(999999999999L, nodeMap["value"])
        assertEquals("LongConstant", nodeMap["type"])
    }

    @Test
    fun `return full FloatConstant node as map`() {
        val result = executor.execute("MATCH (n:FloatConstant) RETURN n")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST") val nodeMap = result.rows[0]["n"] as Map<String, Any?>
        assertEquals(3.14f, nodeMap["value"])
        assertEquals("FloatConstant", nodeMap["type"])
    }

    @Test
    fun `return full DoubleConstant node as map`() {
        val result = executor.execute("MATCH (n:DoubleConstant) RETURN n")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST") val nodeMap = result.rows[0]["n"] as Map<String, Any?>
        assertEquals(2.718, nodeMap["value"])
        assertEquals("DoubleConstant", nodeMap["type"])
    }

    @Test
    fun `return full NullConstant node as map`() {
        val result = executor.execute("MATCH (n:NullConstant) RETURN n")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST") val nodeMap = result.rows[0]["n"] as Map<String, Any?>
        assertEquals(null, nodeMap["value"])
        assertEquals("NullConstant", nodeMap["type"])
    }

    @Test
    fun `LongConstant property access`() {
        val result = executor.execute("MATCH (n:LongConstant) RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(999999999999L, result.rows[0]["n.value"])
    }

    @Test
    fun `FloatConstant property access`() {
        val result = executor.execute("MATCH (n:FloatConstant) RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(3.14f, result.rows[0]["n.value"])
    }

    @Test
    fun `DoubleConstant property access`() {
        val result = executor.execute("MATCH (n:DoubleConstant) RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(2.718, result.rows[0]["n.value"])
    }

    @Test
    fun `NullConstant property access`() {
        val result = executor.execute("MATCH (n:NullConstant) RETURN n.value")
        assertEquals(1, result.rows.size)
        assertNull(result.rows[0]["n.value"])
    }

    @Test
    fun `query AnnotationNode by label`() {
        val result = executor.execute("MATCH (a:Annotation) RETURN a.name, a.class, a.member")
        assertEquals(2, result.rows.size)
        val names = result.rows.map { it["a.name"] }.toSet()
        assertTrue(names.contains("org.springframework.web.bind.annotation.GetMapping"))
        assertTrue(names.contains("org.springframework.web.bind.annotation.PostMapping"))
    }

    @Test
    fun `query AnnotationNode with CONTAINS filter`() {
        val result = executor.execute("MATCH (a:Annotation) WHERE a.name CONTAINS 'GetMapping' RETURN a.class, a.member")
        assertEquals(1, result.rows.size)
        assertEquals("com.example.UserController", result.rows[0]["a.class"])
        assertEquals("getUser", result.rows[0]["a.member"])
    }

    @Test
    fun `query AnnotationNode by AnnotationNode label`() {
        val result = executor.execute("MATCH (a:AnnotationNode) RETURN a.name")
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `query AnnotationNode with custom annotation value property`() {
        val result = executor.execute("MATCH (a:Annotation) WHERE a.value = '/api/users/{id}' RETURN a.name, a.member")
        assertEquals(1, result.rows.size)
        assertEquals("getUser", result.rows[0]["a.member"])
    }

    @Test
    fun `return full AnnotationNode materializes to map`() {
        val result = executor.execute("MATCH (a:Annotation) WHERE a.name CONTAINS 'GetMapping' RETURN a")
        assertEquals(1, result.rows.size)
        val nodeMap = result.rows[0]["a"]
        assertTrue(nodeMap is Map<*, *>)
        @Suppress("UNCHECKED_CAST") val map = nodeMap as Map<String, Any?>
        assertEquals("AnnotationNode", map["type"])
        assertEquals("org.springframework.web.bind.annotation.GetMapping", map["name"])
        assertEquals("com.example.UserController", map["class"])
        assertEquals("getUser", map["member"])
        assertEquals("/api/users/{id}", map["value"])
    }

    @Test
    fun `query ResourceValueNode by label and property`() {
        val result = executor.execute("MATCH (r:ResourceValue) WHERE r.key = 'server.port' RETURN r.path, r.value")
        assertEquals(1, result.rows.size)
        assertEquals("application.yml", result.rows[0]["r.path"])
        assertEquals(8080, result.rows[0]["r.value"])
    }

    @Test
    fun `query ResourceFileNode by label and property`() {
        val resourceFile = NodeId.next()
        val builder = DefaultGraph.Builder()
        builder.addNode(ResourceFileNode(resourceFile, "application.yml", "BOOT-INF/classes", "yaml"))
        val localExecutor = CypherExecutor(builder.build())

        val result = localExecutor.execute("MATCH (r:ResourceFile) WHERE r.path = 'application.yml' RETURN r.source, r.format")
        assertEquals(1, result.rows.size)
        assertEquals("BOOT-INF/classes", result.rows[0]["r.source"])
        assertEquals("yaml", result.rows[0]["r.format"])
    }

    @Test
    fun `return full ResourceFileNode materializes to map`() {
        val resourceFile = NodeId.next()
        val builder = DefaultGraph.Builder()
        builder.addNode(ResourceFileNode(resourceFile, "application.yml", "BOOT-INF/classes", "yaml", "prod"))
        val localExecutor = CypherExecutor(builder.build())

        val result = localExecutor.execute("MATCH (r:ResourceFile) RETURN r")
        assertEquals(1, result.rows.size)
        val nodeMap = result.rows[0]["r"]
        assertTrue(nodeMap is Map<*, *>)
        @Suppress("UNCHECKED_CAST") val map = nodeMap as Map<String, Any?>
        assertEquals("ResourceFileNode", map["type"])
        assertEquals("application.yml", map["path"])
        assertEquals("BOOT-INF/classes", map["source"])
        assertEquals("yaml", map["format"])
        assertEquals("prod", map["profile"])
    }

    @Test
    fun `return full ReturnNode materializes actual type`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.Service"), "load", emptyList(), TypeDescriptor("java.lang.Object"))
        val builder = DefaultGraph.Builder()
        builder.addNode(ReturnNode(NodeId.next(), method, TypeDescriptor("com.example.User")))
        val localExecutor = CypherExecutor(builder.build())

        val result = localExecutor.execute("MATCH (r:`ReturnNode`) RETURN r")
        assertEquals(1, result.rows.size)
        val nodeMap = result.rows[0]["r"]
        assertTrue(nodeMap is Map<*, *>)
        @Suppress("UNCHECKED_CAST") val map = nodeMap as Map<String, Any?>
        assertEquals("ReturnNode", map["type"])
        assertEquals(method.signature, map["method"])
        assertEquals("com.example.User", map["actual_type"])
    }

    @Test
    fun `nodeToMap materializes resource value local variable field and parameter branches`() {
        val type = TypeDescriptor("com.example.Controller")
        val stringType = TypeDescriptor("java.lang.String")
        val method = MethodDescriptor(type, "handle", listOf(stringType), stringType)
        val executor = CypherExecutor(DefaultGraph.Builder().build())
        val nodeToMap = CypherExecutor::class.java.getDeclaredMethod("nodeToMap", Node::class.java).apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val resourceValueMap =
            nodeToMap.invoke(executor, ResourceValueNode(NodeId.next(), "application.yml", "server.port", 8080, "yaml", "dev"))
                as Map<String, Any?>
        assertEquals("ResourceValueNode", resourceValueMap["type"])
        assertEquals("application.yml", resourceValueMap["path"])
        assertEquals("server.port", resourceValueMap["key"])
        assertEquals(8080, resourceValueMap["value"])
        assertEquals("yaml", resourceValueMap["format"])
        assertEquals("dev", resourceValueMap["profile"])

        @Suppress("UNCHECKED_CAST")
        val localMap = nodeToMap.invoke(executor, LocalVariable(NodeId.next(), "name", stringType, method)) as Map<String, Any?>
        assertEquals("name", localMap["name"])
        assertEquals("java.lang.String", localMap["type"])

        @Suppress("UNCHECKED_CAST")
        val fieldMap =
            nodeToMap.invoke(executor, FieldNode(NodeId.next(), FieldDescriptor(type, "title", stringType), true)) as Map<String, Any?>
        assertEquals("title", fieldMap["name"])
        assertEquals("com.example.Controller", fieldMap["class"])
        assertEquals(true, fieldMap["static"])

        @Suppress("UNCHECKED_CAST")
        val parameterMap = nodeToMap.invoke(executor, ParameterNode(NodeId.next(), 0, stringType, method)) as Map<String, Any?>
        assertEquals(0, parameterMap["index"])
        assertEquals(method.signature, parameterMap["method"])
    }
}
