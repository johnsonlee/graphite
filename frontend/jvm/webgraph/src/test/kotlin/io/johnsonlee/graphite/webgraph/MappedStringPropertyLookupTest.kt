package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.cypher.query
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.nodesByStringProperty
import io.johnsonlee.graphite.graph.nodesByStringPropertyDisjunction
import io.johnsonlee.graphite.graph.nodesByTransformedStringProperty
import java.io.Closeable
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verifies mapped string property lookup. */
class MappedStringPropertyLookupTest : GraphStoreTestFixture() {

    @Test
    fun `node string collection accepts an iterable batch`() {
        val strings = linkedSetOf<String>()

        NodeRecordCodec.collectNodeStrings(listOf(StringConstant(NodeId(0), "feature-alpha")), strings)

        assertEquals(setOf("feature-alpha"), strings)
    }

    @Test
    @Suppress("LongMethod")
    fun `mapped string property lookup uses raw node fields and falls back when unsupported`() {
        val owner = TypeDescriptor("example.Owner")
        val caller =
            MethodDescriptor(
                owner, "callerFeature", listOf(TypeDescriptor("java.lang.String"), TypeDescriptor("int")), TypeDescriptor("void"))
        val callee = MethodDescriptor(TypeDescriptor("example.Target"), "billingFeature", emptyList(), TypeDescriptor("void"))
        val graph =
            DefaultGraph.Builder()
                .addNode(StringConstant(NodeId(0), "feature-alpha"))
                .addNode(StringConstant(NodeId(1), "feature-beta"))
                .addNode(CallSiteNode(NodeId(2), caller, callee, 10, null, emptyList()))
                .addNode(EnumConstant(NodeId(3), TypeDescriptor("example.State"), "READY"))
                .addNode(LocalVariable(NodeId(4), "request", TypeDescriptor("java.lang.String"), caller))
                .addNode(FieldNode(NodeId(5), FieldDescriptor(owner, "token", TypeDescriptor("java.lang.String")), false))
                .addNode(ParameterNode(NodeId(6), 0, TypeDescriptor("java.lang.String"), caller))
                .addNode(ResourceFileNode(NodeId(7), "config/app.yml", "resources", "yaml"))
                .addNode(IntConstant(NodeId(8), 42))
                .build()
        val dir = Files.createTempDirectory("webgraph-string-property-index")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir)
            try {
                val mapped = loaded as MappedWebGraphBackedGraph
                listOf(
                        "caller_class" to "example.Owner",
                        "caller_name" to "callerFeature",
                        "callee_class" to "example.Target",
                        "callee_name" to "billingFeature")
                    .forEach { (property, expected) ->
                        assertEquals(
                            listOf(2),
                            loaded
                                .nodesByStringProperty(CallSiteNode::class.java, property, StringMatchMode.EQUALS, expected, limit = 1)
                                .orEmpty()
                                .map { it.id.value }
                                .toList())
                    }
                assertEquals(
                    listOf(7),
                    loaded
                        .nodesByStringPropertyDisjunction(
                            ResourceFileNode::class.java,
                            listOf(
                                StringPropertyPredicate("path", null, StringMatchMode.CONTAINS, "config"),
                                StringPropertyPredicate("source", null, StringMatchMode.CONTAINS, "resources")),
                            limit = 1)
                        .orEmpty()
                        .map { it.id.value }
                        .toList())
                assertEarlyLimitedLookupsDoNotBuildIndex(mapped)
                assertBroadDiscoveryQuery(loaded)
                assertNull(loaded.nodesByStringProperty(StringConstant::class.java, "value", StringMatchMode.CONTAINS, "alpha"))
                val contains =
                    loaded
                        .nodesByStringProperty(StringConstant::class.java, "value", StringMatchMode.CONTAINS, "alpha")
                        ?.map { it.value }
                        ?.toList()
                assertEquals(1, mapped.stringPropertyIndexCount())
                assertTrue(
                    loaded
                        .nodesByStringProperty(StringConstant::class.java, "value", StringMatchMode.EQUALS, "definitely-missing")
                        .orEmpty()
                        .none())
                assertEquals(
                    listOf("feature-alpha"),
                    loaded
                        .nodesByTransformedStringProperty(
                            StringConstant::class.java,
                            "value",
                            StringValueTransform.LOWERCASE,
                            StringMatchMode.EQUALS,
                            "feature-alpha",
                            limit = Int.MAX_VALUE)
                        .orEmpty()
                        .map { it.value }
                        .toList())
                loaded.nodesByStringProperty(CallSiteNode::class.java, "caller_name", StringMatchMode.STARTS_WITH, "caller")
                val startsWith =
                    loaded
                        .nodesByStringProperty(CallSiteNode::class.java, "caller_name", StringMatchMode.STARTS_WITH, "caller")
                        ?.map { it.caller.name }
                        ?.toList()
                loaded.nodesByStringProperty(CallSiteNode::class.java, "callee_name", StringMatchMode.ENDS_WITH, "Feature")
                val endsWith =
                    loaded
                        .nodesByStringProperty(CallSiteNode::class.java, "callee_name", StringMatchMode.ENDS_WITH, "Feature")
                        ?.map { it.callee.name }
                        ?.toList()
                val cypherValues =
                    loaded.query("MATCH (n:StringConstant) WHERE n.value CONTAINS 'beta' RETURN n.value AS value").rows.map { it["value"] }

                assertEquals(listOf("feature-alpha"), contains)
                assertEquals(listOf("callerFeature"), startsWith)
                assertEquals(listOf("billingFeature"), endsWith)
                assertEquals(listOf("feature-beta"), cypherValues)
                assertWrappedLowercaseQuery(loaded)
                assertRawStringPropertyLookups(loaded)
                assertWorkAwareTransformedLookup(mapped)
                var disjunctionWork = 0
                val disjunction =
                    loaded
                        .nodesByStringPropertyDisjunction(
                            CallSiteNode::class.java,
                            listOf(
                                StringPropertyPredicate("caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "owner"),
                                StringPropertyPredicate(
                                    "callee_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "target")),
                            Int.MAX_VALUE,
                            GraphWorkConsumer { disjunctionWork++ })
                        ?.map { it.id.value }
                        ?.toList()
                assertEquals(listOf(2), disjunction)
                assertTrue(disjunctionWork > 0)
                assertNull(
                    loaded.nodesByStringPropertyDisjunction(
                        CallSiteNode::class.java, listOf(StringPropertyPredicate("unknown", null, StringMatchMode.CONTAINS, "feature"))))
                assertNull(loaded.nodesByStringProperty(StringConstant::class.java, "unknown", StringMatchMode.CONTAINS, "feature"))
                assertNull(loaded.nodesByStringProperty(IntConstant::class.java, "value", StringMatchMode.CONTAINS, "42"))
                assertTrue(loaded.memberAnnotationIndex().orEmpty().isEmpty())
                assertNull(loaded.classOrigin("example.Owner"))
                assertTrue(loaded.classOrigins().isEmpty())
                assertTrue(loaded.artifactDependencies().isEmpty())
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
