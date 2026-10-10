package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.cypher.CypherBudgetExceededException
import io.johnsonlee.graphite.cypher.CypherExecutionBudget
import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.graph.MethodTypes
import io.johnsonlee.graphite.graph.NodePropertyTextCandidates
import io.johnsonlee.graphite.graph.propertyTextFragments
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Persisted semantic and work-accounting fixtures; these are not performance measurements. */
class MappedDeclaredTypeCandidatesTest {
    @Test
    fun `union preserves generic and erased matches order limits missing bindings and negative any`() = withGraph { graph ->
        val lookup = graph as NodePropertyTextCandidates
        assertTrue(lookup.includesDeclaredTypeProperties)
        val oracle = CypherExecutor(object : Graph by graph {})
        val candidate = CypherExecutor(graph)
        for (needle in listOf(
            "List<java.lang.", "name=java.lang", "DeclarationOnly", "scope=method:Owner",
            "? extends DeclarationOnly", "? super ScopedType", "Outer<ScopedType>.Inner",
            "owner={kind=class", "component={kind=class", "variance=unbounded",
            "erasedOnly", "MissingDeclarationNeedle", "generic_type", "[][]", "中文", null
        )) {
            for (label in listOf("", ":Field", ":Parameter", ":Return")) {
                for (negation in listOf("", "NOT ")) {
                    val query = "MATCH (n$label) WHERE ${negation}any(k IN keys(n) WHERE toString(n[k]) CONTAINS \$term) " +
                        "RETURN n.id AS id LIMIT 3"
                    assertEquals(oracle.execute(query, mapOf("term" to needle)).rows,
                        candidate.execute(query, mapOf("term" to needle)).rows, "$label $negation $needle")
                }
            }
        }
        val all = graph.nodes(Node::class.java).map { it.id }.toList()
        val candidates = assertNotNull(lookup.propertyTextCandidates(
            Node::class.java, propertyTextFragments("DeclarationOnly"), null
        )).map { it.id }.toList()
        assertEquals(candidates.size, candidates.toSet().size)
        assertEquals(all.filter { it in candidates }, candidates)
        assertTrue(candidates.containsAll(listOf(NodeId(1), NodeId(2), NodeId(3))))
    }

    @Test
    fun `generic misses exclude declaration nodes while preserving erased candidates`() = withGraph { graph ->
        val lookup = graph as NodePropertyTextCandidates
        assertEquals(emptyList(), assertNotNull(lookup.propertyTextCandidates(
            Node::class.java, listOf("MissingDeclarationNeedle"), null
        )).toList())
        assertEquals(listOf(NodeId(4)), assertNotNull(lookup.propertyTextCandidates(
            StringConstant::class.java, listOf("erasedOnly"), null
        )).map { it.id }.toList())
        assertEquals(emptyList(), assertNotNull(lookup.propertyTextCandidates(
            FieldNode::class.java, listOf("DeclarationOnly", "MissingDeclarationNeedle"), null
        )).toList())
    }

    @Test
    fun `summary lookup preserves lazy node accounting and unrelated node kinds`() = withGraph { graph ->
        val lookup = graph as NodePropertyTextCandidates
        var work = 0
        val candidates = assertNotNull(lookup.propertyTextCandidates(
            Node::class.java, listOf("MissingDeclarationNeedle"), GraphWorkConsumer { work++ }
        ))
        assertEquals(0, work)
        assertEquals(emptyList(), candidates.toList())
        assertEquals(graph.nodes(Node::class.java).count(), work)
        work = 0
        assertNotNull(lookup.propertyTextCandidates(
            StringConstant::class.java, listOf("MissingDeclarationNeedle"), GraphWorkConsumer { work++ }
        )).toList()
        assertEquals(1, work)
        assertFailsWith<CypherBudgetExceededException> {
            CypherExecutor(graph, CypherExecutionBudget(1)).execute(
                "MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'MissingDeclarationNeedle') RETURN n.id LIMIT 1"
            )
        }
    }

    private fun withGraph(block: (Graph) -> Unit) {
        val directory = Files.createTempDirectory("mapped-declared-type-candidates")
        try {
            GraphStore.save(fixture(), directory)
            val graph = GraphStore.loadMapped(directory)
            try {
                block(graph)
            } finally {
                (graph as? AutoCloseable)?.close()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun fixture(): Graph {
        val owner = TypeDescriptor("Owner")
        val erased = TypeDescriptor("java.util.List")
        val method = MethodDescriptor(owner, "method", listOf(erased), erased)
        val table = DeclaredTypeTable(
            listOf(
                DeclaredType("class", "java.lang.DeclarationOnly"),
                DeclaredType("class", "java.util.List", arguments = listOf(0)),
                DeclaredType("variable", "ScopedType", "method:Owner#method(Ljava/util/List;)Ljava/util/List;"),
                DeclaredType("wildcard", component = 0, variance = "extends"),
                DeclaredType("wildcard", component = 2, variance = "super"),
                DeclaredType("wildcard", variance = "unbounded"),
                DeclaredType("class", "Outer", arguments = listOf(2)),
                DeclaredType("class", "Outer\$Inner", owner = 6, arguments = listOf(1, 3, 4, 5)),
                DeclaredType("array", component = 7)
            ),
            mapOf(MemberTypeKey("Owner", "value", "Ljava/util/List;") to 1),
            mapOf(MemberTypeKey("Owner", "method", method.descriptor) to MethodTypes(listOf(8), 8)), emptyMap()
        )
        return DefaultGraph.Builder().apply {
            addNode(FieldNode(NodeId(1), FieldDescriptor(owner, "value", erased), false))
            addNode(ParameterNode(NodeId(2), 0, erased, method))
            addNode(ReturnNode(NodeId(3), method, null))
            addNode(StringConstant(NodeId(4), "erasedOnly"))
            addNode(FieldNode(NodeId(5), FieldDescriptor(owner, "unbound", erased), false))
            addNode(ParameterNode(NodeId(6), 7, erased, method))
            setDeclaredTypes(table)
        }.build()
    }
}
