package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.EnumValueIndexSource
import io.johnsonlee.graphite.graph.Graph
import java.io.Closeable
import java.io.DataInputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verifies graph metadata round trip. */
class GraphMetadataRoundTripTest : GraphStoreTestFixture() {

    @Test
    fun `round-trip preserves member annotations`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val annotations = loaded.memberAnnotations("com.example.Foo", "bar")
            assertEquals(graph.memberAnnotations("com.example.Foo", "bar"), annotations)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves class origins`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.Foo"), "bar", emptyList(), TypeDescriptor("void"))
        val graph =
            DefaultGraph.Builder()
                .addMethod(method)
                .addNode(ReturnNode(NodeId(1), method))
                .addClassOrigin("org.apache.lucene.index.IndexWriter", "BOOT-INF/lib/lucene-core-9.11.1.jar")
                .addClassOrigin("org.apache.logging.log4j.Logger", "BOOT-INF/lib/log4j-api-2.23.1.jar")
                .build()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)
            assertEquals("BOOT-INF/lib/lucene-core-9.11.1.jar", loaded.classOrigin("org.apache.lucene.index.IndexWriter"))
            assertEquals("BOOT-INF/lib/log4j-api-2.23.1.jar", loaded.classOrigin("org.apache.logging.log4j.Logger"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves artifact dependencies`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.Foo"), "bar", emptyList(), TypeDescriptor("void"))
        val graph =
            DefaultGraph.Builder()
                .addMethod(method)
                .addNode(ReturnNode(NodeId(1), method))
                .addArtifactDependency("elasticsearch-8.17.0", "lucene-core-9.12.0", 42)
                .addArtifactDependency("lucene-highlighter-9.12.0", "lucene-core-9.12.0", 9)
                .build()
        val dir = Files.createTempDirectory("webgraph-artifact-deps-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)
            assertEquals(
                mapOf(
                    "elasticsearch-8.17.0" to mapOf("lucene-core-9.12.0" to 42),
                    "lucene-highlighter-9.12.0" to mapOf("lucene-core-9.12.0" to 9)),
                loaded.artifactDependencies())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves AnnotationNode`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val annotations = loaded.nodes(AnnotationNode::class.java).toList()
            assertEquals(1, annotations.size)
            assertEquals("javax.annotation.Nullable", annotations[0].name)
            assertEquals("com.example.Foo", annotations[0].className)
            assertEquals("bar", annotations[0].memberName)
            assertEquals("true", annotations[0].values["value"])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves typed annotation values`() {
        val builder = DefaultGraph.Builder()
        val ownerType = TypeDescriptor("com.example.Foo")
        val method = MethodDescriptor(ownerType, "bar", emptyList(), TypeDescriptor("void"))
        val annotationNode =
            AnnotationNode(
                NodeId.next(),
                "com.example.Http",
                "com.example.Foo",
                "bar",
                mapOf("paths" to listOf("/a", "/b"), "required" to true, "code" to 200, "note" to null))
        builder.addMethod(method)
        builder.addNode(ReturnNode(NodeId.next(), method))
        builder.addNode(annotationNode)
        builder.addMemberAnnotation("com.example.Foo", "bar", "com.example.Http", annotationNode.values)

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-typed-annot-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val loadedNode = loaded.node(annotationNode.id) as AnnotationNode
            assertEquals(annotationNode.values, loadedNode.values)
            assertEquals(annotationNode.values, loaded.memberAnnotations("com.example.Foo", "bar")["com.example.Http"])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves ResourceValueNode`() {
        val builder = DefaultGraph.Builder()
        val method = MethodDescriptor(TypeDescriptor("com.example.Foo"), "bar", emptyList(), TypeDescriptor("void"))
        val resourceFileNode = ResourceFileNode(NodeId.next(), "application.yml", "BOOT-INF/classes", "yaml", "prod")
        val resourceNode = ResourceValueNode(NodeId.next(), "application.yml", "server.port", 8080, "yaml", "prod")
        val callSite = CallSiteNode(NodeId.next(), method, method, 12, null, emptyList())
        builder.addMethod(method)
        builder.addNode(ReturnNode(NodeId.next(), method))
        builder.addNode(resourceFileNode)
        builder.addNode(resourceNode)
        builder.addNode(callSite)
        builder.addEdge(ResourceEdge(resourceNode.id, callSite.id, ResourceRelation.LOOKUP))

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-resource-value-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val loadedFile = loaded.node(resourceFileNode.id) as ResourceFileNode
            val loadedNode = loaded.node(resourceNode.id) as ResourceValueNode
            assertEquals(resourceFileNode, loadedFile)
            assertEquals(resourceNode, loadedNode)
            assertTrue(
                loaded.incoming(callSite.id, ResourceEdge::class.java).any {
                    it.from == resourceNode.id && it.kind == ResourceRelation.LOOKUP
                })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves type hierarchy`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val child = TypeDescriptor("com.example.Child")
            val parent = TypeDescriptor("com.example.Parent")
            assertTrue(loaded.supertypes(child).any { it.className == "com.example.Parent" })
            assertTrue(loaded.subtypes(parent).any { it.className == "com.example.Child" })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves enum values`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val values = loaded.enumValues("com.example.Status", "ACTIVE")
            assertEquals(listOf(1, "active"), values)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `enum declarations take precedence over node lookups and missing keys use the fallback`() {
        val type = TypeDescriptor("example.Status")
        val source =
            DefaultGraph.Builder()
                .addNode(EnumConstant(NodeId(1), type, "EXPLICIT"))
                .addNode(EnumConstant(NodeId(2), type, "REFERENCED"))
                .addEnumValues(type.className, "EXPLICIT", listOf("fallback"))
                .addEnumValues(type.className, "REFERENCED", listOf("discovered"))
                .build()
        val graph =
            object : Graph by source, EnumValueIndexSource {
                override fun enumValueIndex(): Map<String, List<Any?>> = mapOf("example.Status#EXPLICIT" to emptyList())
            }
        val dir = Files.createTempDirectory("webgraph-enum-fallback")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)
            assertEquals(emptyList<Any?>(), loaded.enumValues(type.className, "EXPLICIT"))
            assertEquals(listOf("discovered"), loaded.enumValues(type.className, "REFERENCED"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `declaration-only enum cases survive eager and mapped resaves`() {
        val type = TypeDescriptor("example.Status")
        val graph =
            DefaultGraph.Builder()
                .addEnumValues(type.className, "PLAIN", emptyList())
                .addEnumValues(type.className, "PAYLOAD", listOf(7, null, "label"))
                .addNode(EnumConstant(NodeId(1), type, "UNDECLARED"))
                .build()

        fun assertDeclarations(loaded: Graph) {
            assertEquals(emptyList<Any?>(), loaded.enumValues(type.className, "PLAIN"))
            assertEquals(listOf(7, null, "label"), loaded.enumValues(type.className, "PAYLOAD"))
            assertNull(loaded.enumValues(type.className, "UNDECLARED"))
            assertNull(loaded.enumValues(type.className, "MISSING"))
            assertEquals(listOf("UNDECLARED"), loaded.nodes(EnumConstant::class.java).map { it.enumName }.toList())
        }

        val dir = Files.createTempDirectory("webgraph-enum-declarations")
        try {
            val first = dir.resolve("original")
            GraphStore.save(graph, first)
            for (mode in listOf(GraphStore.LoadMode.EAGER, GraphStore.LoadMode.MAPPED)) {
                val loaded = GraphStore.load(first, mode)
                val resaved = dir.resolve(mode.name)
                try {
                    assertDeclarations(loaded)
                    GraphStore.save(loaded, resaved)
                } finally {
                    (loaded as? Closeable)?.close()
                }
                assertDeclarations(GraphStore.load(resaved, GraphStore.LoadMode.EAGER))
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves call sites`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val originalCallSites = graph.nodes(CallSiteNode::class.java).toList()
            val loadedCallSites = loaded.nodes(CallSiteNode::class.java).toList()
            assertEquals(originalCallSites.size, loadedCallSites.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves call-site ordinals, derived and unknown ones included`() {
        val returnType = TypeDescriptor("void")
        val caller = MethodDescriptor(TypeDescriptor("example.Caller"), "run", emptyList(), returnType)
        val callee = MethodDescriptor(TypeDescriptor("example.Flags"), "enabled", emptyList(), returnType)
        val graph =
            DefaultGraph.Builder()
                .apply {
                    addNode(CallSiteNode(NodeId(0), caller, callee, null, null, emptyList(), ordinal = 0))
                    addNode(CallSiteNode(NodeId(1), caller, callee, null, null, emptyList(), ordinal = 1))
                    addNode(CallSiteNode(NodeId(2), caller, callee, null, null, emptyList(), ordinal = -1, origin = NodeId(1)))
                    addNode(CallSiteNode(NodeId(3), caller, callee, null, null, emptyList(), ordinal = null))
                }
                .build()
        val dir = Files.createTempDirectory("webgraph-ordinal")
        try {
            GraphStore.save(graph, dir)
            val expected: List<Int?> = listOf(0, 1, -1, null)
            fun Graph.ordinals(): List<Int?> = nodes(CallSiteNode::class.java).sortedBy { it.id.value }.map { it.ordinal }.toList()
            assertEquals(expected, GraphStore.load(dir).ordinals())
            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { mapped -> assertEquals(expected, mapped.ordinals()) }
            // The call a derived one was resolved from travels with its ordinal.
            fun Graph.origins(): List<Int?> = nodes(CallSiteNode::class.java).sortedBy { it.id.value }.map { it.origin?.value }.toList()
            val origins = listOf(null, null, 1, null)
            assertEquals(origins, GraphStore.load(dir).origins())
            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { mapped -> assertEquals(origins, mapped.origins()) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `ordinals live in a sidecar that older readers never open`() {
        val returnType = TypeDescriptor("void")
        val caller = MethodDescriptor(TypeDescriptor("example.Caller"), "run", emptyList(), returnType)
        val callee = MethodDescriptor(TypeDescriptor("example.Flags"), "enabled", emptyList(), returnType)
        val graph =
            DefaultGraph.Builder()
                .apply {
                    addNode(CallSiteNode(NodeId(0), caller, callee, null, null, emptyList(), ordinal = 0))
                    addNode(CallSiteNode(NodeId(1), caller, callee, null, null, emptyList(), ordinal = 1))
                }
                .build()
        val dir = Files.createTempDirectory("webgraph-ordinal-sidecar")
        try {
            GraphStore.save(graph, dir)
            val sidecar = dir.resolve(GraphStore.CALL_SITE_ORDINALS_FILE)
            assertTrue(Files.exists(sidecar))
            assertEquals(
                NodeSerializer.FORMAT_VERSION,
                DataInputStream(Files.newInputStream(dir.resolve("graph.nodedata"))).use {
                    NodeSerializer.readHeader(it, NodeSerializer.MAGIC_NODEDATA)
                },
                "the record format does not change")
            val metadataFile = dir.resolve("graph.metadata")
            val binding = readCallSiteOrdinalBinding(metadataFile)
            assertNotNull(binding, "graph.metadata ends with the sidecar's binding")
            val loaded = CallSiteOrdinals.load(sidecar, binding)
            assertEquals(2, loaded.size)
            assertEquals(1, loaded[1])
            assertEquals(null, loaded[7])
            assertEquals(0, CallSiteOrdinals.load(sidecar, null).size, "no binding: no ordinals")
            assertEquals(0, CallSiteOrdinals.load(sidecar, ByteArray(32)).size, "another graph's binding: no ordinals")
            // A writer that predates the sidecar re-saves graph.metadata without the binding and leaves
            // the sidecar behind: the ordinals it holds belong to another graph and must not attach.
            val metadataBytes = Files.readAllBytes(metadataFile)
            Files.write(metadataFile, metadataBytes.copyOf(metadataBytes.size - NodeSerializer.CALL_SITE_ORDINALS_BINDING_BYTES))
            assertEquals(null, readCallSiteOrdinalBinding(metadataFile))
            fun Graph.ordinals(): List<Int?> = nodes(CallSiteNode::class.java).sortedBy { it.id.value }.map { it.ordinal }.toList()
            assertEquals(listOf(null, null), GraphStore.load(dir).ordinals(), "a stale sidecar is not attached")
            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { mapped -> assertEquals(listOf(null, null), mapped.ordinals()) }
            Files.write(metadataFile, metadataBytes)
            // Without the sidecar the same graph reads with no ordinals, as a reader that predates it does.
            Files.delete(sidecar)
            assertEquals(listOf(null, null), GraphStore.load(dir).ordinals())
            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { mapped -> assertEquals(listOf(null, null), mapped.ordinals()) }
            // A sidecar that is not one is ignored, and a save without ordinals removes a stale one.
            Files.write(sidecar, byteArrayOf(1, 2, 3))
            assertEquals(0, CallSiteOrdinals.load(sidecar, binding).size)
            Files.write(sidecar, ByteArray(0))
            assertEquals(0, CallSiteOrdinals.load(sidecar, binding).size)
            val plain = DefaultGraph.Builder().apply { addNode(CallSiteNode(NodeId(0), caller, callee, null, null, emptyList())) }.build()
            GraphStore.save(plain, dir)
            assertFalse(Files.exists(sidecar))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
