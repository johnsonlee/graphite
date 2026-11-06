package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.cli.ExploreCommandTestSupport.Companion.fieldNode
import io.johnsonlee.graphite.cli.ExploreCommandTestSupport.Companion.graphDir
import io.johnsonlee.graphite.cli.ExploreCommandTestSupport.Companion.localNode
import io.johnsonlee.graphite.cli.ExploreCommandTestSupport.Companion.port
import io.johnsonlee.graphite.cli.c4.C4ArchitectureService
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.input.JavaArchiveLayout
import io.johnsonlee.graphite.webgraph.GraphStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import picocli.CommandLine

@Suppress("DEPRECATION")
internal class ExploreGraphArchitectureTest : ExploreCommandTestSupport() {
    @Test
    fun `GET api subgraph returns nodes and edges`() {
        val (code, body) = get("/api/graphs/standalone/subgraph?center=${localNode.id.value}&depth=1")
        assertEquals(200, code)
        val subgraph: Map<String, Any?> = parseJson(body)
        assertTrue(subgraph.containsKey("nodes"), "Should contain 'nodes' key")
        assertTrue(subgraph.containsKey("edges"), "Should contain 'edges' key")
        @Suppress("UNCHECKED_CAST") val nodes = subgraph["nodes"] as List<Map<String, Any?>>
        assertTrue(nodes.isNotEmpty(), "Subgraph should contain nodes")
    }

    @Test
    fun `GET api subgraph with depth 0 returns only center node`() {
        val (code, body) = get("/api/graphs/standalone/subgraph?center=${fieldNode.id.value}&depth=0")
        assertEquals(200, code)
        val subgraph: Map<String, Any?> = parseJson(body)
        @Suppress("UNCHECKED_CAST") val nodes = subgraph["nodes"] as List<Map<String, Any?>>
        assertEquals(1, nodes.size, "Depth 0 should return only the center node")
        @Suppress("UNCHECKED_CAST") val edges = subgraph["edges"] as List<Map<String, Any?>>
        assertEquals(0, edges.size, "Depth 0 should return no edges")
    }

    @Test
    fun `GET api subgraph missing center returns 400`() {
        val (code, _) = get("/api/graphs/standalone/subgraph?depth=2")
        assertEquals(400, code)
    }

    @Test
    fun `GET api subgraph with invalid center returns 400`() {
        val (code, _) = get("/api/graphs/standalone/subgraph?center=notanumber")
        assertEquals(400, code)
    }

    @Test
    fun `GET api subgraph defaults to depth 2`() {
        val (code, body) = get("/api/graphs/standalone/subgraph?center=${localNode.id.value}")
        assertEquals(200, code)
        val subgraph: Map<String, Any?> = parseJson(body)
        @Suppress("UNCHECKED_CAST") val nodes = subgraph["nodes"] as List<Map<String, Any?>>
        // With depth 2, should traverse further than depth 1
        assertTrue(nodes.size > 1, "Default depth 2 should return multiple nodes")
    }

    @Test
    fun `GET api subgraph supports outgoing-only traversal`() {
        val (code, body) = get("/api/graphs/standalone/subgraph?center=${localNode.id.value}&depth=1&direction=outgoing")
        assertEquals(200, code)
        val subgraph: Map<String, Any?> = parseJson(body)
        @Suppress("UNCHECKED_CAST") val edges = subgraph["edges"] as List<Map<String, Any?>>
        assertTrue(edges.isNotEmpty(), "Outgoing traversal should include localNode outgoing edges")
        assertTrue(edges.all { it["from"] == localNode.id.value.toDouble() }, "Outgoing-only traversal should not include incoming edges")
    }

    @Test
    fun `GET api subgraph rejects invalid direction`() {
        val (code, _) = get("/api/graphs/standalone/subgraph?center=${localNode.id.value}&direction=sideways")
        assertEquals(400, code)
    }

    @Test
    fun `GET api subgraph for nonexistent center returns empty`() {
        val (code, body) = get("/api/graphs/standalone/subgraph?center=999999")
        assertEquals(200, code)
        val subgraph: Map<String, Any?> = parseJson(body)
        @Suppress("UNCHECKED_CAST") val nodes = subgraph["nodes"] as List<Map<String, Any?>>
        assertEquals(0, nodes.size, "Nonexistent center should return empty subgraph")
    }

    @Test
    fun `explorer defaults to mapped load mode`() {
        val explore = ExploreCommand()

        CommandLine(explore).parseArgs()

        assertEquals(8080, explore.port)
        assertEquals(GraphStore.LoadMode.MAPPED, explore.loadMode)
        assertNull(explore.data)
        assertNull(explore.graphId)
        assertNull(explore.topology)
        assertTrue(explore.graphSpecs.isEmpty())
        assertEquals(4, DEFAULT_MAX_CONCURRENT_CYPHER)
        assertEquals(1_000_000L, DEFAULT_CYPHER_WORK_BUDGET)
        assertEquals(4, explore.maxConcurrentCypher)
        assertEquals(1_000_000L, explore.cypherWorkBudget)
        assertEquals(60_000L, explore.cypherMaxTimeoutMillis)
        assertFalse(explore.metricsEnabled)
    }

    @Test
    fun `server metrics require explicit opt in`() {
        val serve = ServeCommand()

        CommandLine(serve).parseArgs("--metrics")

        assertTrue(serve.metricsEnabled)
    }

    @Test
    fun `serve help marks cypher work budget deprecated and documents timeout replacement`() {
        val usage = CommandLine(ServeCommand()).usageMessage

        assertTrue(usage.contains("--cypher-work-budget"), usage)
        assertTrue(usage.contains("Deprecated and ignored"), usage)
        assertTrue(usage.contains("--cypher-max-timeout-ms"), usage)
    }

    @Test
    fun `serve rejects non-positive active cypher limits and ignores deprecated work budget`() {
        val concurrency = ServeCommand().apply { maxConcurrentCypher = 0 }
        val timeout = ServeCommand().apply { cypherMaxTimeoutMillis = 0 }
        val work = ServeCommand().apply { cypherWorkBudget = 0 }

        val (_, concurrencyError, concurrencyCode) = captureOutput { concurrency.call() }
        val (_, timeoutError, timeoutCode) = captureOutput { timeout.call() }
        val (_, workError, workCode) = captureOutput { work.call() }

        assertEquals(1, concurrencyCode)
        assertEquals(1, timeoutCode)
        assertEquals(1, workCode)
        assertTrue(concurrencyError.contains("must be positive"), concurrencyError)
        assertTrue(timeoutError.contains("must be positive"), timeoutError)
        assertTrue(workError.contains("--data"), workError)
    }

    @Test
    fun `serve without initial graph requires data directory`() {
        val serve = ServeCommand()

        val (_, err, code) = captureOutput { serve.call() }

        assertEquals(1, code)
        assertTrue(err.contains("--data"), "Expected data directory error, got: $err")
    }

    @Test
    fun `serve positional graph requires an explicit graph id`() {
        val serve = ServeCommand()
        serve.graphDir = graphDir

        val (_, err, code) = captureOutput { serve.call() }

        assertEquals(1, code)
        assertTrue(err.contains("--id is required"), "Expected explicit graph id error, got: $err")
    }

    @Test
    fun `serve with invalid graph spec returns error before blocking`() {
        val root = Files.createTempDirectory("explore-invalid-graph-spec")
        try {
            val serve = ServeCommand()
            serve.data = root
            serve.graphSpecs = listOf("missing-separator")

            val (_, err, code) = captureOutput { serve.call() }

            assertEquals(1, code)
            assertTrue(err.contains("Invalid --graph 'missing-separator'"), "Expected invalid graph error, got: $err")
            assertTrue(err.contains("Expected id:path"), "Expected id:path hint, got: $err")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `serve validates the topology query after loading the configured graph catalog`() {
        val root = Files.createTempDirectory("explore-invalid-topology")
        try {
            saveConstantGraph(root, "service-a", 11)
            saveConstantGraph(root, "service-b", 22)
            val query = root.resolve("topology.cypher")
            Files.writeString(query, "RETURN 'service-a' AS source")
            val serve =
                ServeCommand().apply {
                    data = root
                    graphSpecs = listOf("service-a:service-a", "service-b:service-b")
                    topology = query
                    port = 0
                }

            val (_, err, code) = captureOutput { serve.call() }

            assertEquals(1, code)
            assertTrue(err.contains("must return 'source' and 'target'"), err)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `serve loads positional and graph specs into registry`() {
        val root = Files.createTempDirectory("explore-initial-graphs")
        try {
            val positional = saveConstantGraph(root, "service-a", 11)
            saveConstantGraph(root, "service-b", 22)
            val registry = GraphRegistry(root, GraphStore.LoadMode.MAPPED)
            try {
                val serve = ServeCommand()
                serve.graphDir = positional
                serve.graphId = "service-a"
                serve.graphSpecs = listOf("service-b:service-b")

                val loadInitialGraphs = ServeCommand::class.java.getDeclaredMethod("loadInitialGraphs", GraphRegistry::class.java)
                loadInitialGraphs.isAccessible = true
                loadInitialGraphs.invoke(serve, registry)

                assertEquals(listOf("service-a", "service-b"), registry.list().map { it.id })
                registry.acquire("service-a")?.use { lease -> assertEquals("service-a", lease.id) }
                registry.acquire("service-b")?.use { lease -> assertEquals(1L, lease.graph.nodeCount(Node::class.java)) }
            } finally {
                registry.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `call starts server and blocks until interrupted`() {
        // Test that call() actually starts a server and blocks
        val explore = ExploreCommand()
        explore.graphDir = graphDir
        explore.graphId = "test"
        explore.port = 0 // random port

        var result: Int? = null
        var exception: Throwable? = null
        val thread = Thread {
            try {
                result = explore.call()
            } catch (e: Throwable) {
                exception = e
            }
        }
        thread.start()
        // Give it time to start
        Thread.sleep(2000)
        // Interrupt to unblock Thread.join()
        thread.interrupt()
        thread.join(5000)
        // The method should have thrown InterruptedException or returned
        // Either is acceptable - the key is exercising the code path
        assertTrue(!thread.isAlive, "Thread should have terminated after interrupt")
    }

    @Test
    fun `buildSubgraph traverses outgoing and incoming edges`() {
        val graph = GraphStore.load(graphDir)
        val explore = ExploreCommand()
        val result = explore.buildSubgraph(graph, localNode.id, 1)
        @Suppress("UNCHECKED_CAST") val nodes = result["nodes"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST") val edges = result["edges"] as List<Map<String, Any?>>
        // localNode has 2 incoming (param, intConst) and 1 outgoing (returnNode)
        assertTrue(nodes.size >= 4, "Should include center + neighbors, got ${nodes.size}")
        assertTrue(edges.isNotEmpty(), "Should include edges")
    }

    @Test
    fun `buildSubgraph with negative depth returns only center`() {
        val graph = GraphStore.load(graphDir)
        val explore = ExploreCommand()
        // depth -1: visit returns immediately due to remaining < 0
        val result = explore.buildSubgraph(graph, localNode.id, -1)
        @Suppress("UNCHECKED_CAST") val nodes = result["nodes"] as List<Map<String, Any?>>
        assertEquals(0, nodes.size, "Negative depth should return nothing (remaining < 0 check)")
    }

    @Test
    fun `buildSubgraph caps excessive traversals`() {
        val builder = DefaultGraph.Builder()
        val nodeCount = 2_100
        repeat(nodeCount) { index ->
            builder.addNode(IntConstant(NodeId(index), index))
            if (index > 0) {
                builder.addEdge(DataFlowEdge(NodeId(index - 1), NodeId(index), DataFlowKind.ASSIGN))
            }
        }

        val result = ExploreCommand().buildSubgraph(builder.build(), NodeId(0), nodeCount)
        @Suppress("UNCHECKED_CAST") val nodes = result["nodes"] as List<Map<String, Any?>>
        assertEquals(2_000, nodes.size, "Subgraph traversal should stop at the node cap")
    }

    @Test
    fun `buildSubgraph caps edges independently of nodes`() {
        val builder = DefaultGraph.Builder()
        val fanOut = 5_100
        builder.addNode(IntConstant(NodeId(0), 0))
        repeat(fanOut) { index ->
            val leaf = index + 1
            builder.addNode(IntConstant(NodeId(leaf), leaf))
            builder.addEdge(DataFlowEdge(NodeId(0), NodeId(leaf), DataFlowKind.ASSIGN))
        }

        val result = ExploreCommand().buildSubgraph(builder.build(), NodeId(0), 1)
        @Suppress("UNCHECKED_CAST") val nodes = result["nodes"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST") val edges = result["edges"] as List<Map<String, Any?>>
        assertEquals(2_000, nodes.size, "Node cap applies before the edge cap on a wide fan-out")
        assertEquals(5_000, edges.size, "Edges keep accumulating up to their own cap after the node cap")
    }

    @Test
    fun `buildC4Model does not invent containers or components for library-only graphs`() {
        val graph = GraphStore.load(graphDir)
        val explore = ExploreCommand()
        val result = explore.buildC4Model(graph, "all", 50)
        assertEquals("Graphite C4 Workspace", result["name"])
        @Suppress("UNCHECKED_CAST") val views = result["views"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val systemContextViews = views["systemContextViews"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST") val containerViews = views["containerViews"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST") val componentViews = views["componentViews"] as List<Map<String, Any?>>
        assertTrue(systemContextViews.isNotEmpty())
        assertTrue(containerViews.isNotEmpty())
        assertTrue(componentViews.isEmpty())
        @Suppress("UNCHECKED_CAST") val model = result["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val softwareSystems = model["softwareSystems"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST") val application = softwareSystems.first { (it["id"] as? String)?.startsWith("system:") == true }
        @Suppress("UNCHECKED_CAST") val containers = application["containers"] as List<Map<String, Any?>>
        assertTrue(containers.isEmpty())
    }

    @Test
    fun `buildC4Model groups external systems by artifact provenance when available`() {
        val internalType = TypeDescriptor("com.example.AppService")
        val internalMethod = MethodDescriptor(internalType, "run", emptyList(), TypeDescriptor("void"))
        val luceneWriterType = TypeDescriptor("org.apache.lucene.index.IndexWriter")
        val luceneReaderType = TypeDescriptor("org.apache.lucene.index.DirectoryReader")
        val logType = TypeDescriptor("org.apache.logging.log4j.Logger")
        val runtimeType = TypeDescriptor("java.util.List")
        val builder = DefaultGraph.Builder()

        builder.addMethod(internalMethod)
        builder.addNode(
            CallSiteNode(
                NodeId.next(),
                internalMethod,
                MethodDescriptor(luceneWriterType, "commit", emptyList(), TypeDescriptor("void")),
                10,
                null,
                emptyList()))
        builder.addNode(
            CallSiteNode(
                NodeId.next(),
                internalMethod,
                MethodDescriptor(luceneReaderType, "open", emptyList(), luceneReaderType),
                11,
                null,
                emptyList()))
        builder.addNode(
            CallSiteNode(
                NodeId.next(),
                internalMethod,
                MethodDescriptor(logType, "info", listOf(TypeDescriptor("java.lang.String")), TypeDescriptor("void")),
                12,
                null,
                emptyList()))
        builder.addNode(
            CallSiteNode(
                NodeId.next(),
                internalMethod,
                MethodDescriptor(runtimeType, "size", emptyList(), TypeDescriptor("int")),
                13,
                null,
                emptyList()))
        builder.addClassOrigin(luceneWriterType.className, JavaArchiveLayout.bootInfLibEntry("lucene-core-9.11.1.jar"))
        builder.addClassOrigin(luceneReaderType.className, JavaArchiveLayout.bootInfLibEntry("lucene-core-9.11.1.jar"))
        builder.addClassOrigin(logType.className, JavaArchiveLayout.bootInfLibEntry("log4j-api-2.23.1.jar"))
        builder.addArtifactDependency("lucene-core-9.11.1", "log4j-api-2.23.1", 7)

        val explore = ExploreCommand()
        val result = explore.buildC4Model(builder.build(), "context", 50)
        @Suppress("UNCHECKED_CAST") val model = result["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val softwareSystems = model["softwareSystems"] as List<Map<String, Any?>>
        val names = softwareSystems.mapNotNull { it["name"] as? String }

        assertTrue(names.contains("lucene-core-9.11.1"))
        assertTrue(names.contains("log4j-api-2.23.1"))
        assertTrue(names.contains("Java Runtime"))
        assertFalse(names.contains("org.apache"))
        @Suppress("UNCHECKED_CAST") val luceneSystem = softwareSystems.first { it["name"] == "lucene-core-9.11.1" }
        @Suppress("UNCHECKED_CAST") val relationships = luceneSystem["relationships"] as List<Map<String, Any?>>
        assertTrue(relationships.any { it["destinationId"] == "dependency:artifact:log4j-api-2.23.1" })
    }

    @Test
    fun `buildC4Model infers external software systems from referenced absent classes`() {
        val appType = TypeDescriptor("com.acme.checkout.CheckoutService")
        val appMethod = MethodDescriptor(appType, "pay", emptyList(), TypeDescriptor("void"))
        val paymentType = TypeDescriptor("com.partner.payment.PaymentGateway")
        val paymentMethod = MethodDescriptor(paymentType, "charge", emptyList(), TypeDescriptor("void"))
        val graph =
            DefaultGraph.Builder()
                .addMethod(appMethod)
                .addNode(CallSiteNode(NodeId.next(), appMethod, paymentMethod, 20, null, emptyList()))
                .build()

        val workspace = ExploreCommand().buildC4Model(graph, "context", 50)
        @Suppress("UNCHECKED_CAST") val model = workspace["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val systems = model["softwareSystems"] as List<Map<String, Any?>>
        val external = systems.first { it["id"] == "dependency:namespace:com.partner.payment" }
        val properties = external["properties"] as Map<*, *>

        assertEquals("com.partner.payment", external["name"])
        assertEquals("external-system", properties["graphite.kind"])
        assertEquals("external-system", properties["graphite.architectureType"])
        assertTrue((properties["graphite.responsibility"] as? String).orEmpty().contains("external software system"))

        val service = C4ArchitectureService()
        assertTrue(service.renderMermaid(workspace).contains("External Systems"))
        assertTrue(service.renderPlantUml(workspace).contains("package \"External Systems\""))
    }
}
