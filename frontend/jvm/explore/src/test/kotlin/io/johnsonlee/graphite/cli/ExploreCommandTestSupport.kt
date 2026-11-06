package io.johnsonlee.graphite.cli

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import io.javalin.Javalin
import io.javalin.json.JavalinGson
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeRelation
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.ResourceEntry
import io.johnsonlee.graphite.webgraph.GraphStore
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.AfterClass
import org.junit.BeforeClass

@Suppress("DEPRECATION")
internal abstract class ExploreCommandTestSupport {
    companion object {
        internal lateinit var graphDir: Path
        internal lateinit var app: Javalin
        internal var port: Int = 0
        internal val gson = Gson()

        internal val fooType = TypeDescriptor("com.example.Foo")
        internal val bazType = TypeDescriptor("com.example.Baz")
        internal val parentType = TypeDescriptor("com.example.Parent")
        internal val childType = TypeDescriptor("com.example.Child")
        internal val barMethod = MethodDescriptor(fooType, "bar", listOf(TypeDescriptor("int")), TypeDescriptor("void"))
        internal val bazMethod = MethodDescriptor(bazType, "baz", emptyList(), TypeDescriptor("void"))
        internal val quxMethod = MethodDescriptor(childType, "qux", listOf(TypeDescriptor("java.lang.String")), TypeDescriptor("int"))

        internal lateinit var paramNode: ParameterNode
        internal lateinit var localNode: LocalVariable
        internal lateinit var intConstNode: IntConstant
        internal lateinit var strConstNode: StringConstant
        internal lateinit var returnNode: ReturnNode
        internal lateinit var callSiteNode: CallSiteNode
        internal lateinit var enumConstNode: EnumConstant
        internal lateinit var fieldNode: FieldNode
        internal lateinit var resourceFileNode: ResourceFileNode
        internal lateinit var propertyFileNode: ResourceFileNode
        internal val resources =
            mapOf(
                "application.yml" to "server:\n  port: 8080\nfeature:\n  enabled: true\n",
                "config/application.properties" to "feature.mode=shadow\n")

        internal class TestResourceAccessor(private val resources: Map<String, String>) : ResourceAccessor {
            override fun list(pattern: String): Sequence<ResourceEntry> {
                val matcher = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$pattern")
                return resources.keys.asSequence().filter { matcher.matches(Path.of(it)) }.map { ResourceEntry(it, "test-fixture") }
            }

            override fun open(path: String) =
                resources[path]?.let { ByteArrayInputStream(it.toByteArray()) } ?: throw java.io.IOException("Resource not found: $path")
        }

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val builder = DefaultGraph.Builder().setResources(TestResourceAccessor(resources))

            paramNode = ParameterNode(NodeId.next(), 0, TypeDescriptor("int"), barMethod)
            localNode = LocalVariable(NodeId.next(), "x", TypeDescriptor("int"), barMethod)
            intConstNode = IntConstant(NodeId.next(), 42)
            strConstNode = StringConstant(NodeId.next(), "hello")
            returnNode = ReturnNode(NodeId.next(), barMethod)
            callSiteNode = CallSiteNode(NodeId.next(), barMethod, bazMethod, 10, null, listOf(paramNode.id))
            enumConstNode = EnumConstant(NodeId.next(), TypeDescriptor("com.example.Status"), "ACTIVE", listOf(1, "active"))
            fieldNode = FieldNode(NodeId.next(), FieldDescriptor(fooType, "name", TypeDescriptor("java.lang.String")), false)
            resourceFileNode = ResourceFileNode(NodeId.next(), "application.yml", "test-fixture", "yaml")
            propertyFileNode = ResourceFileNode(NodeId.next(), "config/application.properties", "test-fixture", "properties")

            builder.addNode(paramNode)
            builder.addNode(localNode)
            builder.addNode(intConstNode)
            builder.addNode(strConstNode)
            builder.addNode(returnNode)
            builder.addNode(callSiteNode)
            builder.addNode(enumConstNode)
            builder.addNode(fieldNode)
            builder.addNode(resourceFileNode)
            builder.addNode(propertyFileNode)

            builder.addEdge(DataFlowEdge(paramNode.id, localNode.id, DataFlowKind.ASSIGN))
            builder.addEdge(DataFlowEdge(intConstNode.id, localNode.id, DataFlowKind.ASSIGN))
            builder.addEdge(DataFlowEdge(localNode.id, returnNode.id, DataFlowKind.RETURN_VALUE))
            builder.addEdge(ResourceEdge(propertyFileNode.id, callSiteNode.id, ResourceRelation.LOOKUP))
            builder.addEdge(CallEdge(callSiteNode.id, callSiteNode.id, isVirtual = false))

            builder.addMethod(barMethod)
            builder.addMethod(bazMethod)
            builder.addMethod(quxMethod)

            builder.addTypeRelation(childType, parentType, TypeRelation.EXTENDS)

            builder.addEnumValues("com.example.Status", "ACTIVE", listOf(1, "active"))

            builder.addMemberAnnotation("com.example.Foo", "bar", "javax.annotation.Nullable", emptyMap())
            builder.addMemberAnnotation(
                "com.example.Foo", "<class>", "org.springframework.web.bind.annotation.RequestMapping", mapOf("value" to "/v1"))
            builder.addMemberAnnotation(
                "com.example.Foo", "bar", "org.springframework.web.bind.annotation.GetMapping", mapOf("value" to "/api/bar"))

            val graph = builder.build()
            graphDir = Files.createTempDirectory("explore-test")
            GraphStore.save(graph, graphDir)
            val loadedGraph = GraphStore.load(graphDir)
            app = Javalin.create { config -> config.jsonMapper(JavalinGson(GsonBuilder().setPrettyPrinting().create())) }.start(0)
            port = app.port()

            val explore = ExploreCommand()
            explore.registerApiRoutes(app, loadedGraph)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            app.stop()
            graphDir.toFile().deleteRecursively()
        }
    }

    protected fun get(path: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> = get(port, path, headers)

    protected fun get(targetPort: Int, path: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val url = URI("http://localhost:$targetPort$path").toURL()
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
        val code = conn.responseCode
        val body =
            if (code in 200..299) {
                InputStreamReader(conn.inputStream).use { it.readText() }
            } else {
                conn.errorStream?.let { InputStreamReader(it).use { r -> r.readText() } } ?: ""
            }
        conn.disconnect()
        return code to body
    }

    protected fun withExploreApp(graph: Graph, routes: ExploreRoutes = ExploreRoutes(), block: (Int) -> Unit) {
        val localApp = Javalin.create { config -> config.jsonMapper(JavalinGson(GsonBuilder().setPrettyPrinting().create())) }.start(0)
        try {
            routes.register(localApp, graph)
            block(localApp.port())
        } finally {
            localApp.stop()
        }
    }

    protected fun withRegistryApp(registry: GraphRegistry, routes: ExploreRoutes = ExploreRoutes(), block: (Int) -> Unit) {
        val topology = TopologyService(registry, emptyList()).also { it.rebuild() }
        withRegistryApp(registry, topology, routes, block)
    }

    protected fun withRegistryApp(
        registry: GraphRegistry,
        topology: TopologyService,
        routes: ExploreRoutes = ExploreRoutes(),
        block: (Int) -> Unit
    ) {
        val localApp = Javalin.create { config -> config.jsonMapper(JavalinGson(GsonBuilder().setPrettyPrinting().create())) }.start(0)
        try {
            routes.register(localApp, registry, topology)
            block(localApp.port())
        } finally {
            localApp.stop()
            topology.close()
            registry.close()
        }
    }

    protected fun post(path: String, jsonBody: String): Pair<Int, String> = request(port, "POST", path, jsonBody)

    protected fun post(targetPort: Int, path: String, jsonBody: String): Pair<Int, String> = request(targetPort, "POST", path, jsonBody)

    protected fun put(targetPort: Int, path: String, jsonBody: String): Pair<Int, String> = request(targetPort, "PUT", path, jsonBody)

    protected fun delete(targetPort: Int, path: String): Pair<Int, String> = request(targetPort, "DELETE", path, null)

    protected fun request(targetPort: Int, method: String, path: String, jsonBody: String?): Pair<Int, String> {
        val url = URI("http://localhost:$targetPort$path").toURL()
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        if (jsonBody != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(jsonBody.toByteArray()) }
        }
        val code = conn.responseCode
        val body =
            if (code in 200..299) {
                conn.inputStream?.let { InputStreamReader(it).use { r -> r.readText() } } ?: ""
            } else {
                conn.errorStream?.let { InputStreamReader(it).use { r -> r.readText() } } ?: ""
            }
        conn.disconnect()
        return code to body
    }

    protected fun saveConstantGraph(root: Path, directory: String, vararg values: Int): Path {
        val output = root.resolve(directory)
        val builder = DefaultGraph.Builder()
        values.forEach { value -> builder.addNode(IntConstant(NodeId.next(), value)) }
        GraphStore.save(builder.build(), output)
        return output
    }

    protected fun saveCollidingGraph(root: Path, directory: String, value: Int, resource: String): Path {
        val output = root.resolve(directory)
        val constant = IntConstant(NodeId(7), value)
        val local = LocalVariable(NodeId(8), "value", TypeDescriptor("int"), barMethod)
        val graph =
            DefaultGraph.Builder()
                .setResources(TestResourceAccessor(mapOf("shared/config.txt" to resource)))
                .addNode(constant)
                .addNode(local)
                .addEdge(DataFlowEdge(constant.id, local.id, DataFlowKind.ASSIGN))
                .build()
        GraphStore.save(graph, output)
        return output
    }

    protected inline fun <reified T> parseJson(json: String): T {
        return gson.fromJson(json, object : TypeToken<T>() {}.type)
    }

    protected fun captureOutput(block: () -> Int): Triple<String, String, Int> {
        val outBaos = java.io.ByteArrayOutputStream()
        val errBaos = java.io.ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        System.setOut(java.io.PrintStream(outBaos))
        System.setErr(java.io.PrintStream(errBaos))
        val code =
            try {
                block()
            } finally {
                System.setOut(oldOut)
                System.setErr(oldErr)
            }
        return Triple(outBaos.toString(), errBaos.toString(), code)
    }

    protected fun singleCypherValue(json: String): Any? {
        val result: Map<String, Any?> = parseJson(json)
        @Suppress("UNCHECKED_CAST") val rows = result["rows"] as List<Map<String, Any?>>
        return rows.single().values.single()
    }

    @Suppress("UNCHECKED_CAST")
    protected fun <T> invokeExplorePrivate(target: Any, name: String, parameterTypes: Array<Class<*>>, vararg args: Any?): T {
        val method =
            runCatching { target::class.java.getDeclaredMethod(name, *parameterTypes) }
                .getOrElse {
                    listOf(
                            "io.johnsonlee.graphite.cli.c4.SystemBoundaryDetectorKt",
                            "io.johnsonlee.graphite.cli.c4.ExternalSystemClassifierKt",
                            "io.johnsonlee.graphite.cli.c4.SubjectDetectorKt",
                            "io.johnsonlee.graphite.cli.c4.ContainerClustererKt",
                            "io.johnsonlee.graphite.cli.c4.ComponentSelectorKt")
                        .firstNotNullOfOrNull { className ->
                            runCatching { Class.forName(className).getDeclaredMethod(name, *parameterTypes) }.getOrNull()
                        } ?: throw it
                }
        method.isAccessible = true
        val receiver = if (method.declaringClass == target::class.java) target else null
        return method.invoke(receiver, *args) as T
    }

    @Suppress("UNCHECKED_CAST")
    protected fun <T> invokeDiagramPrivate(name: String, parameterTypes: Array<Class<*>>, vararg args: Any?): T {
        val method = Class.forName("io.johnsonlee.graphite.cli.c4.C4RenderingPlanKt").getDeclaredMethod(name, *parameterTypes)
        method.isAccessible = true
        return method.invoke(null, *args) as T
    }

    protected fun assertRefreshValue(graph: Graph, failures: ConcurrentLinkedQueue<String>) {
        val values = graph.nodes(IntConstant::class.java).map { it.value }.toList()
        if (values != listOf(1) && values != listOf(2)) {
            failures += "acquired partial graph values: $values"
        }
    }

    protected fun contextTruncationWorkspace(): Map<String, Any?> {
        val relationships =
            (1..205).map { index ->
                mapOf(
                    "id" to "rel-$index",
                    "destinationId" to "dependency:external-$index",
                    "description" to "uses",
                    "properties" to mapOf("graphite.relationshipKind" to "uses", "graphite.weight" to "1"))
            }
        val externalSystems =
            (1..205).map { index ->
                mapOf(
                    "id" to "dependency:external-$index",
                    "name" to "External $index",
                    "description" to "External system $index",
                    "properties" to mapOf("graphite.architectureType" to "external-library"))
            }
        val contextWorkspace =
            mapOf(
                "properties" to mapOf("graphite.level" to "context"),
                "model" to
                    mapOf(
                        "people" to emptyList<Map<String, Any?>>(),
                        "softwareSystems" to
                            listOf(
                                mapOf(
                                    "id" to "system:application",
                                    "name" to "Application",
                                    "description" to "Application",
                                    "properties" to mapOf("graphite.architectureType" to "software-system"),
                                    "relationships" to relationships)) + externalSystems))
        return contextWorkspace
    }
}
