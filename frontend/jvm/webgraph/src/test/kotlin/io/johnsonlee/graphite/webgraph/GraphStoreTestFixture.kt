package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeRelation
import io.johnsonlee.graphite.cypher.query
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.PreferredRawGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.SerialGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.nodesByStringProperty
import io.johnsonlee.graphite.graph.nodesByStringPropertyDisjunction
import io.johnsonlee.graphite.graph.nodesByTransformedStringProperty
import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.ResourceEntry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shared graph fixtures and assertions for persistence and mapped-query tests. */
abstract class GraphStoreTestFixture {

    internal var previousMappedCallSitePreparationProperty: String? = null

    @BeforeTest
    fun preserveLazyMappedLoadForExistingColdPathTests() {
        previousMappedCallSitePreparationProperty = System.getProperty(GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY)
        System.setProperty(GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY, "false")
    }

    @AfterTest
    fun restoreMappedCallSitePreparationProperty() {
        previousMappedCallSitePreparationProperty?.let { previous ->
            System.setProperty(GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY, previous)
        } ?: System.clearProperty(GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY)
    }

    internal fun assertSerialProjectionRestoresPersistedSidecar(graph: MappedWebGraphBackedGraph) {
        assertTrue(graph.isCallSiteStringIndexInitialized())
        assertTrue(graph.isCallSiteTrigramIndexInitialized())
        assertTrue(graph.isCallSiteStringIndexLoadedFromPersistence())
        graph.releaseStringPropertyDisjunctionCache()
        assertFalse(graph.isCallSiteStringIndexInitialized())
        graph.resetCallSiteScanMetrics()
        val projection =
            graph.distinctStringPropertyDisjunction(
                CallSiteNode::class.java,
                listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "definitely-missing-serial-projection")),
                projectedProperties = listOf("caller_class"),
                limit = 2,
                workConsumer = SerialGraphWorkBatchConsumer {})
        assertTrue(projection.orEmpty().isEmpty())
        assertEquals(1L, graph.callSiteStringProjectionLookupCount())
        assertTrue(graph.isCallSiteStringIndexInitialized())
        assertTrue(graph.isCallSiteStringIndexLoadedFromPersistence())
        graph.releaseStringPropertyDisjunctionCache()
        assertFalse(graph.isCallSiteStringIndexInitialized())
        graph.resetCallSiteScanMetrics()
        val rawIds =
            graph
                .nodesByStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    listOf(StringPropertyPredicate("caller_name", null, StringMatchMode.CONTAINS, "call")),
                    limit = 2,
                    workConsumer = PreferredRawGraphWorkBatchConsumer {})
                .orEmpty()
                .map { node -> node.id.value }
                .toList()
        assertEquals(listOf(0, 1), rawIds)
        assertEquals(1L, graph.callSiteStringLookupEntryCount())
        assertFalse(graph.isCallSiteStringIndexInitialized())
        graph.resetCallSiteScanMetrics()
        val rawProjection =
            graph.projectStringPropertyDisjunction(
                CallSiteNode::class.java,
                listOf(StringPropertyPredicate("caller_name", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "call")),
                projectedProperties = listOf("callee_name"),
                limit = 2,
                workConsumer = PreferredRawGraphWorkBatchConsumer {})
        assertEquals(listOf(listOf("invoke"), listOf("invoke")), rawProjection?.map { row -> row.values })
        assertEquals(1L, graph.callSiteStringLookupEntryCount())
        assertEquals(0L, graph.callSiteStringProjectionLookupCount())
        assertFalse(graph.isCallSiteStringIndexInitialized())
    }

    internal fun assertEarlyLimitedLookupsDoNotBuildIndex(graph: MappedWebGraphBackedGraph) {
        listOf(StringMatchMode.CONTAINS to "alpha", StringMatchMode.STARTS_WITH to "feature").forEach { (mode, expected) ->
            val early =
                graph.nodesByStringProperty(StringConstant::class.java, "value", mode, expected, limit = 1)?.map { it.value }?.toList()
            assertEquals(listOf("feature-alpha"), early)
        }
        assertEquals(0, graph.stringPropertyIndexCount())
    }

    internal fun assertBroadDiscoveryQuery(graph: Graph) {
        val rows =
            graph
                .query(
                    """
            MATCH (n)
            WHERE (exists(n.class) AND n.class CONTAINS 'Owner')
               OR (exists(n.name) AND n.name CONTAINS 'Owner')
               OR (exists(n.caller_class) AND n.caller_class CONTAINS 'Owner')
               OR (exists(n.caller_name) AND n.caller_name CONTAINS 'Owner')
               OR (exists(n.callee_class) AND n.callee_class CONTAINS 'Owner')
               OR (exists(n.callee_name) AND n.callee_name CONTAINS 'Owner')
            RETURN DISTINCT n.class AS class, n.name AS name,
                n.caller_class AS caller, n.caller_name AS callerMethod,
                n.callee_class AS callee, n.callee_name AS calleeMethod
            LIMIT 120
            """
                        .trimIndent())
                .rows

        assertEquals(2, rows.size)
        assertEquals("token", rows.single { it["class"] == "example.Owner" }["name"])
        val callSite = rows.single { it["caller"] == "example.Owner" }
        assertEquals("callerFeature", callSite["callerMethod"])
        assertEquals("example.Target", callSite["callee"])
        assertEquals("billingFeature", callSite["calleeMethod"])
    }

    internal fun MappedWebGraphBackedGraph.stringConstantsMissing(expected: String): Boolean =
        nodesByStringProperty(StringConstant::class.java, "value", StringMatchMode.CONTAINS, expected, limit = 1).orEmpty().none()

    internal fun MappedWebGraphBackedGraph.unboundedStringConstants(expected: String): Sequence<StringConstant>? =
        nodesByStringProperty(StringConstant::class.java, "value", StringMatchMode.CONTAINS, expected)

    internal fun assertRawStringPropertyLookups(graph: Graph) {
        graph.nodesByTransformedStringProperty(
            FieldNode::class.java, "class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "owner")
        assertEquals(
            listOf("example.Owner"),
            assertNotNull(
                    graph.nodesByTransformedStringProperty(
                        FieldNode::class.java, "class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "owner"))
                .map { it.descriptor.declaringClass.className }
                .toList())
        assertEquals(
            listOf("example.State"),
            indexedValues(graph, EnumConstant::class.java, "enum_type", StringMatchMode.ENDS_WITH, "State") { it.enumType.className })
        assertEquals(
            listOf("READY"), indexedValues(graph, EnumConstant::class.java, "name", StringMatchMode.STARTS_WITH, "REA") { it.enumName })
        assertEquals(
            listOf("request"), indexedValues(graph, LocalVariable::class.java, "name", StringMatchMode.CONTAINS, "que") { it.name })
        assertEquals(
            listOf("java.lang.String"),
            indexedValues(graph, LocalVariable::class.java, "type", StringMatchMode.ENDS_WITH, "String") { it.type.className })
        assertEquals(
            listOf("example.Owner"),
            indexedValues(graph, FieldNode::class.java, "class", StringMatchMode.CONTAINS, "Owner") {
                it.descriptor.declaringClass.className
            })
        assertEquals(
            listOf("token"), indexedValues(graph, FieldNode::class.java, "name", StringMatchMode.CONTAINS, "oke") { it.descriptor.name })
        assertEquals(
            listOf("java.lang.String"),
            indexedValues(graph, FieldNode::class.java, "type", StringMatchMode.ENDS_WITH, "String") { it.descriptor.type.className })
        assertEquals(
            listOf("java.lang.String"),
            indexedValues(graph, ParameterNode::class.java, "type", StringMatchMode.CONTAINS, "lang") { it.type.className })
        assertResourceStringPropertyLookups(graph)
    }

    internal fun assertWorkAwareTransformedLookup(graph: MappedWebGraphBackedGraph) {
        var inspected = 0
        val values =
            graph
                .nodesByTransformedStringProperty(
                    FieldNode::class.java,
                    "class",
                    StringValueTransform.LOWERCASE,
                    StringMatchMode.CONTAINS,
                    "owner",
                    limit = 1,
                    workConsumer = GraphWorkConsumer { inspected++ })
                .orEmpty()
                .map { it.descriptor.declaringClass.className }
                .toList()

        assertEquals(listOf("example.Owner"), values)
        assertTrue(inspected > 0)
    }

    internal fun assertWrappedLowercaseQuery(graph: Graph) {
        val values =
            graph
                .query(
                    "MATCH (n) WHERE " +
                        "toLower(coalesce(n.caller_name, '')) CONTAINS 'feature' OR " +
                        "toLower(coalesce(n.callee_name, '')) CONTAINS 'feature' " +
                        "RETURN DISTINCT n.caller_name AS caller, n.callee_name AS callee LIMIT 250")
                .rows
                .map { it["caller"] to it["callee"] }

        assertEquals(listOf("callerFeature" to "billingFeature"), values)
    }

    internal fun assertResourceStringPropertyLookups(graph: Graph) {
        assertEquals(
            listOf("config/app.yml"),
            indexedValues(graph, ResourceFileNode::class.java, "path", StringMatchMode.STARTS_WITH, "config") { it.path })
        assertEquals(
            listOf("resources"),
            indexedValues(graph, ResourceFileNode::class.java, "source", StringMatchMode.CONTAINS, "source") { it.source })
        assertEquals(
            listOf("yaml"), indexedValues(graph, ResourceFileNode::class.java, "format", StringMatchMode.ENDS_WITH, "yaml") { it.format })
        assertEquals(
            listOf("example.Owner"),
            indexedValues(graph, CallSiteNode::class.java, "caller_class", StringMatchMode.CONTAINS, "Owner") {
                it.caller.declaringClass.className
            })
        assertEquals(
            listOf("example.Target"),
            indexedValues(graph, CallSiteNode::class.java, "callee_class", StringMatchMode.CONTAINS, "Target") {
                it.callee.declaringClass.className
            })
        assertEquals(
            listOf("feature-alpha", "feature-beta"),
            indexedValues(graph, StringConstant::class.java, "value", StringMatchMode.CONTAINS, "a") { it.value })
    }

    internal fun <T : Node, R> indexedValues(
        graph: Graph,
        type: Class<T>,
        property: String,
        mode: StringMatchMode,
        expected: String,
        transform: (T) -> R
    ): List<R> {
        graph.nodesByStringProperty(type, property, mode, expected)
        return assertNotNull(graph.nodesByStringProperty(type, property, mode, expected)).map(transform).toList()
    }

    internal fun edgeKey(from: NodeId, to: NodeId): Long = from.value.toLong() shl INT_BITS or (to.value.toLong() and UNSIGNED_INT_MASK)

    internal var branchCondNodeId: NodeId = NodeId(0)

    internal var branchTrueNodeId: NodeId = NodeId(0)

    internal var branchFalseNodeId: NodeId = NodeId(0)

    internal class MetadataFallbackGraph(private val delegate: Graph) : Graph by delegate {
        override fun memberAnnotationIndex(): Map<String, Map<String, Map<String, Any?>>>? = null

        override fun typeHierarchyTypes(): Set<String> = emptySet()
    }

    internal fun buildBranchScopeGraph(): Graph {
        val builder = DefaultGraph.Builder()
        val fooType = TypeDescriptor("com.example.Foo")
        val method = MethodDescriptor(fooType, "check", emptyList(), TypeDescriptor("boolean"))
        builder.addMethod(method)

        val condNode = IntConstant(NodeId.next(), 0)
        val trueNode = IntConstant(NodeId.next(), 1)
        val falseNode = IntConstant(NodeId.next(), 2)
        builder.addNode(condNode)
        builder.addNode(trueNode)
        builder.addNode(falseNode)

        branchCondNodeId = condNode.id
        branchTrueNodeId = trueNode.id
        branchFalseNodeId = falseNode.id

        val comp = BranchComparison(ComparisonOp.NE, condNode.id)
        builder.addBranchScope(condNode.id, method, comp, intArrayOf(trueNode.id.value), intArrayOf(falseNode.id.value))

        return builder.build()
    }

    internal fun assertBranchScopeOperations(loaded: Graph) {
        val scopes = loaded.branchScopes().toList()
        assertEquals(1, scopes.size)
        assertEquals(branchCondNodeId, scopes[0].conditionNodeId)
        assertEquals(ComparisonOp.NE, scopes[0].comparison.operator)
        assertTrue(scopes[0].trueBranchNodeIds.contains(branchTrueNodeId.value))
        assertTrue(scopes[0].falseBranchNodeIds.contains(branchFalseNodeId.value))

        val scopesFor = loaded.branchScopesFor(branchCondNodeId).toList()
        assertEquals(1, scopesFor.size)

        assertEquals(0, loaded.branchScopesFor(NodeId(999999)).count())
    }

    internal fun assertGraphOperations(original: Graph, loaded: Graph) {
        // Nodes
        val originalNodes = original.nodes(Node::class.java).toList()
        val loadedNodes = loaded.nodes(Node::class.java).toList()
        assertEquals(originalNodes.size, loadedNodes.size, "Node count should match")

        for (node in originalNodes) {
            assertNotNull(loaded.node(node.id), "Node ${node.id} should exist in loaded graph")
        }

        // Non-existent node
        assertNull(loaded.node(NodeId(999999)))

        // Typed node queries
        assertEquals(1, loaded.nodes(IntConstant::class.java).count())
        assertEquals(1, loaded.nodes(CallSiteNode::class.java).count())
        assertEquals(1, loaded.nodes(FieldNode::class.java).count())
        assertEquals(1, loaded.nodes(ParameterNode::class.java).count())
        assertEquals(1, loaded.nodes(LocalVariable::class.java).count())
        assertEquals(1, loaded.nodes(ReturnNode::class.java).count())
        assertEquals(1, loaded.nodes(EnumConstant::class.java).count())

        // Outgoing edges
        for (node in originalNodes) {
            val origEdges = original.outgoing(node.id).toList()
            val loadEdges = loaded.outgoing(node.id).toList()
            assertEquals(origEdges.size, loadEdges.size, "Outgoing edge count for node ${node.id}")
        }

        // Incoming edges
        for (node in originalNodes) {
            val origEdges = original.incoming(node.id).toList()
            val loadEdges = loaded.incoming(node.id).toList()
            assertEquals(origEdges.size, loadEdges.size, "Incoming edge count for node ${node.id}")
        }

        // callSites
        val matched = loaded.callSites(MethodPattern(declaringClass = "com.example.Foo", name = "baz")).toList()
        assertEquals(1, matched.size)
        val noMatch = loaded.callSites(MethodPattern(name = "nonexistent")).toList()
        assertTrue(noMatch.isEmpty())

        // supertypes and subtypes
        val child = TypeDescriptor("com.example.Child")
        val parent = TypeDescriptor("com.example.Parent")
        assertTrue(loaded.supertypes(child).any { it.className == "com.example.Parent" })
        assertTrue(loaded.subtypes(parent).any { it.className == "com.example.Child" })
        assertEquals(0, loaded.supertypes(TypeDescriptor("com.nonexistent.Type")).count())
        assertEquals(0, loaded.subtypes(TypeDescriptor("com.nonexistent.Type")).count())

        // methods
        val allMethods = loaded.methods(MethodPattern()).toList()
        assertEquals(2, allMethods.size)
        val barMethods = loaded.methods(MethodPattern(name = "bar")).toList()
        assertEquals(1, barMethods.size)

        // enumValues
        assertEquals(listOf(1, "active"), loaded.enumValues("com.example.Status", "ACTIVE"))
        assertNull(loaded.enumValues("com.example.Missing", "MISSING"))

        // memberAnnotations
        val annotations = loaded.memberAnnotations("com.example.Foo", "bar")
        assertTrue(annotations.containsKey("javax.annotation.Nullable"))
        assertTrue(loaded.memberAnnotations("com.example.Unknown", "unknown").isEmpty())

        // typeHierarchyTypes
        val types = loaded.typeHierarchyTypes()
        assertTrue(types.contains("com.example.Parent"))
        assertTrue(types.contains("com.example.Child"))

        // resources
        val resourceEntries = loaded.resources.list("**").toList()
        assertEquals(1, resourceEntries.size)
        assertEquals("application.properties", resourceEntries.single().path)
        assertTrue(loaded.resources.open("application.properties").bufferedReader().readText().contains("feature.mode=shadow"))
    }

    internal fun buildTestGraph(): Graph {
        val builder =
            DefaultGraph.Builder()
                .setResources(
                    object : ResourceAccessor {
                        private val resources = mapOf("application.properties" to "feature.mode=shadow\nfeature.enabled=true\n")

                        override fun list(pattern: String): Sequence<ResourceEntry> =
                            resources.keys.asSequence().map { ResourceEntry(it, "test-fixture") }

                        override fun open(path: String) =
                            resources[path]?.let { ByteArrayInputStream(it.toByteArray()) }
                                ?: throw java.io.IOException("Resource not found: $path")
                    })

        val fooType = TypeDescriptor("com.example.Foo")
        val parentType = TypeDescriptor("com.example.Parent")
        val childType = TypeDescriptor("com.example.Child")
        val method = MethodDescriptor(fooType, "bar", listOf(TypeDescriptor("int")), TypeDescriptor("void"))

        // Nodes
        val param = ParameterNode(NodeId.next(), 0, TypeDescriptor("int"), method)
        val local = LocalVariable(NodeId.next(), "x", TypeDescriptor("int"), method)
        val constant = IntConstant(NodeId.next(), 42)
        val returnNode = ReturnNode(NodeId.next(), method)
        val callee = MethodDescriptor(fooType, "baz", emptyList(), TypeDescriptor("void"))
        val callSite = CallSiteNode(NodeId.next(), method, callee, 10, null, listOf(param.id))
        val enumConst = EnumConstant(NodeId.next(), TypeDescriptor("com.example.Status"), "ACTIVE", listOf(1, "active"))
        val field = FieldNode(NodeId.next(), FieldDescriptor(fooType, "name", TypeDescriptor("java.lang.String")), false)

        builder.addNode(param)
        builder.addNode(local)
        builder.addNode(constant)
        builder.addNode(returnNode)
        builder.addNode(callSite)
        builder.addNode(enumConst)
        builder.addNode(field)

        val annotNode = AnnotationNode(NodeId.next(), "javax.annotation.Nullable", "com.example.Foo", "bar", mapOf("value" to "true"))
        builder.addNode(annotNode)

        // Edges
        builder.addEdge(DataFlowEdge(param.id, local.id, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(constant.id, local.id, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(local.id, returnNode.id, DataFlowKind.RETURN_VALUE))
        builder.addEdge(CallEdge(callSite.id, callSite.id, isVirtual = false))

        // Method
        builder.addMethod(method)
        builder.addMethod(callee)

        // Type hierarchy
        builder.addTypeRelation(childType, parentType, TypeRelation.EXTENDS)

        // Enum values
        builder.addEnumValues("com.example.Status", "ACTIVE", listOf(1, "active"))

        // Annotations
        builder.addMemberAnnotation("com.example.Foo", "bar", "javax.annotation.Nullable", emptyMap())

        return builder.build()
    }

    internal data class LegacyV1Fixture(val dir: Path, val method: MethodDescriptor, val annotationNode: AnnotationNode)

    internal fun createLegacyV1GraphDir(): LegacyV1Fixture {
        val dir = Files.createTempDirectory("legacy-v1-graph")
        val method = MethodDescriptor(TypeDescriptor("com.example.Foo"), "bar", emptyList(), TypeDescriptor("void"))
        val returnNode = ReturnNode(NodeId(1), method)
        val annotationNode = AnnotationNode(NodeId(2), "javax.annotation.Nullable", "com.example.Foo", "bar", mapOf("value" to "hello"))

        val builder = DefaultGraph.Builder()
        builder.addMethod(method)
        builder.addNode(returnNode)
        builder.addNode(annotationNode)
        builder.addMemberAnnotation("com.example.Foo", "bar", "javax.annotation.Nullable", annotationNode.values)
        GraphStore.save(builder.build(), dir)

        val strings =
            StringTable.build(
                setOf(
                    method.declaringClass.className,
                    method.name,
                    method.returnType.className,
                    annotationNode.name,
                    annotationNode.className,
                    annotationNode.memberName,
                    "com.example.Foo#bar",
                    "javax.annotation.Nullable",
                    "value",
                    "hello"),
                dir)
        writeLegacyV1NodeData(dir, method, returnNode, annotationNode, strings)
        writeLegacyV1Metadata(dir, method, strings)
        writeLegacyV1NodeIndex(dir, method, returnNode, annotationNode, strings)
        writeLegacyV1Comparisons(dir)

        return LegacyV1Fixture(dir, method, annotationNode)
    }

    internal fun writeLegacyV1NodeData(
        dir: Path,
        method: MethodDescriptor,
        returnNode: ReturnNode,
        annotationNode: AnnotationNode,
        strings: StringTable
    ) {
        DataOutputStream(dir.resolve("graph.nodedata").toFile().outputStream()).use { dos ->
            dos.writeInt(NodeSerializer.MAGIC_NODEDATA or 0x01)
            dos.writeInt(2)
            dos.write(legacyV1ReturnNodeBytes(returnNode, method, strings))
            dos.write(legacyV1AnnotationNodeBytes(annotationNode, strings))
        }
    }

    internal fun legacyV1ReturnNodeBytes(returnNode: ReturnNode, method: MethodDescriptor, strings: StringTable): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { dos ->
            dos.writeInt(returnNode.id.value)
            dos.writeByte(11)
            writeLegacyV1MethodDescriptor(dos, method, strings)
            dos.writeBoolean(false)
        }
        return baos.toByteArray()
    }

    internal fun legacyV1AnnotationNodeBytes(annotationNode: AnnotationNode, strings: StringTable): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { dos ->
            dos.writeInt(annotationNode.id.value)
            dos.writeByte(13)
            dos.writeInt(strings.indexOf(annotationNode.name))
            dos.writeInt(strings.indexOf(annotationNode.className))
            dos.writeInt(strings.indexOf(annotationNode.memberName))
            dos.writeInt(annotationNode.values.size)
            for ((k, v) in annotationNode.values) {
                dos.writeInt(strings.indexOf(k))
                dos.writeInt(strings.indexOf(v?.toString() ?: ""))
            }
        }
        return baos.toByteArray()
    }

    internal fun writeLegacyV1Metadata(dir: Path, method: MethodDescriptor, strings: StringTable) {
        DataOutputStream(dir.resolve("graph.metadata").toFile().outputStream()).use { dos ->
            dos.writeInt(NodeSerializer.MAGIC_METADATA or 0x01)
            dos.writeInt(1)
            writeLegacyV1MethodDescriptor(dos, method, strings)
            dos.writeInt(0)
            dos.writeInt(0)
            dos.writeInt(0)
            dos.writeInt(1)
            dos.writeInt(strings.indexOf("com.example.Foo#bar"))
            dos.writeInt(1)
            dos.writeInt(strings.indexOf("javax.annotation.Nullable"))
            dos.writeInt(1)
            dos.writeInt(strings.indexOf("value"))
            dos.writeInt(strings.indexOf("hello"))
            dos.writeInt(0)
        }
    }

    internal fun writeLegacyV1NodeIndex(
        dir: Path,
        method: MethodDescriptor,
        returnNode: ReturnNode,
        annotationNode: AnnotationNode,
        strings: StringTable
    ) {
        val returnNodeSize = legacyV1ReturnNodeBytes(returnNode, method, strings).size.toLong()
        DataOutputStream(dir.resolve("graph.nodeindex").toFile().outputStream()).use { dos ->
            dos.writeInt(NodeSerializer.MAGIC_NODEINDEX or 0x01)
            dos.writeInt(2)
            dos.writeInt(returnNode.id.value)
            dos.writeByte(11)
            dos.writeLong(8L)
            dos.writeInt(annotationNode.id.value)
            dos.writeByte(13)
            dos.writeLong(8L + returnNodeSize)
        }
    }

    internal fun writeLegacyV1Comparisons(dir: Path) {
        DataOutputStream(dir.resolve("graph.comparisons").toFile().outputStream()).use { dos ->
            dos.writeInt(NodeSerializer.MAGIC_COMPARISONS or 0x01)
            dos.writeInt(0)
        }
    }

    internal fun writeLegacyV1MethodDescriptor(dos: DataOutputStream, method: MethodDescriptor, strings: StringTable) {
        dos.writeInt(strings.indexOf(method.declaringClass.className))
        dos.writeInt(strings.indexOf(method.name))
        dos.writeInt(method.parameterTypes.size)
        method.parameterTypes.forEach { dos.writeInt(strings.indexOf(it.className)) }
        dos.writeInt(strings.indexOf(method.returnType.className))
    }
}

internal fun rewriteCallSiteStringIndexChecksum(bytes: ByteArray) {
    val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    val checksum = CRC32()

    fun updateInt(value: Int) {
        repeat(Int.SIZE_BYTES) { byteIndex -> checksum.update(value ushr (byteIndex * Byte.SIZE_BITS) and 0xff) }
    }

    fun updateLong(value: Long) {
        repeat(Long.SIZE_BYTES) { byteIndex -> checksum.update(((value ushr (byteIndex * Byte.SIZE_BITS)) and 0xffL).toInt()) }
    }

    repeat(4) { updateInt(input.int) }
    val identity = ByteArray(CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES)
    input.get(identity)
    checksum.update(identity)
    val uniqueCounts = IntArray(CALL_SITE_STRING_PROPERTY_COUNT) { input.int.also(::updateInt) }
    val postingCount = input.int.also(::updateInt)
    updateLong(input.long)
    val callSiteCount = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(3 * Int.SIZE_BYTES)
    uniqueCounts.forEach { uniqueCount -> repeat(uniqueCount * 2 + callSiteCount) { updateInt(input.int) } }
    val stringCount = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(2 * Int.SIZE_BYTES)
    repeat(stringCount + postingCount) { updateLong(input.long) }
    check(input.position() == bytes.size - Long.SIZE_BYTES)
    input.putLong(checksum.value)
}
