package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.analysis.DataFlowAnalysis
import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.EmptyResourceAccessor
import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.ResourceEntry
import io.johnsonlee.graphite.webgraph.GraphStore
import org.junit.AfterClass
import org.junit.BeforeClass
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AbKeysCommandTest {

    companion object {
        private lateinit var graphDir: Path
        private lateinit var emptyGraphDir: Path

        // For direct, in-memory invocations (where resources matter)
        private lateinit var inMemoryGraph: Graph
        private var configCallSiteId: NodeId = NodeId(0)

        private val controllerType = TypeDescriptor("com.example.Controller")
        private val abClientType = TypeDescriptor("com.example.AbClient")
        private val configType = TypeDescriptor("com.example.Config")
        private val abKeysEnumType = TypeDescriptor("com.example.AbKeys")
        private val stringType = TypeDescriptor("java.lang.String")
        private val booleanType = TypeDescriptor("boolean")

        private val handleLiteral = MethodDescriptor(controllerType, "handleLiteral", emptyList(), TypeDescriptor("void"))
        private val handleEnum = MethodDescriptor(controllerType, "handleEnum", emptyList(), TypeDescriptor("void"))
        private val handleConfig = MethodDescriptor(controllerType, "handleConfig", emptyList(), TypeDescriptor("void"))
        private val getOption = MethodDescriptor(abClientType, "getOption", listOf(stringType, booleanType), booleanType)
        private val getString = MethodDescriptor(configType, "getString", listOf(stringType), stringType)

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val builder = DefaultGraph.Builder()

            // ---- handleLiteral: AbClient.getOption("inline_key", false) ----
            val literalKey = StringConstant(NodeId.next(), "inline_key")
            val literalDefault = BooleanConstant(NodeId.next(), false)
            val literalCallSite = CallSiteNode(
                id = NodeId.next(),
                caller = handleLiteral,
                callee = getOption,
                lineNumber = 10,
                receiver = null,
                arguments = listOf(literalKey.id, literalDefault.id)
            )

            // ---- handleEnum: getOption(AbKeys.MY_FLAG.name(), false) ----
            // Slice from arg[0] should yield an EnumConstant directly.
            val enumConst = EnumConstant(
                id = NodeId.next(),
                enumType = abKeysEnumType,
                enumName = "MY_FLAG",
                constructorArgs = emptyList()
            )
            val enumDefault = BooleanConstant(NodeId.next(), false)
            val enumCallSite = CallSiteNode(
                id = NodeId.next(),
                caller = handleEnum,
                callee = getOption,
                lineNumber = 20,
                receiver = null,
                arguments = listOf(enumConst.id, enumDefault.id)
            )

            // ---- handleConfig: getOption(config.getString("ab.feature"), false) ----
            val configKeyConst = StringConstant(NodeId.next(), "ab.feature")
            val getStringCs = CallSiteNode(
                id = NodeId.next(),
                caller = handleConfig,
                callee = getString,
                lineNumber = 30,
                receiver = null,
                arguments = listOf(configKeyConst.id)
            )
            val configResultLocal = LocalVariable(NodeId.next(), "key", stringType, handleConfig)
            val configDefault = BooleanConstant(NodeId.next(), false)
            val configCallSite = CallSiteNode(
                id = NodeId.next(),
                caller = handleConfig,
                callee = getOption,
                lineNumber = 31,
                receiver = null,
                arguments = listOf(configResultLocal.id, configDefault.id)
            )
            configCallSiteId = configCallSite.id

            // Add all nodes
            listOf(
                literalKey, literalDefault, literalCallSite,
                enumConst, enumDefault, enumCallSite,
                configKeyConst, getStringCs, configResultLocal, configDefault, configCallSite
            ).forEach { builder.addNode(it) }

            // Add edges so backwardSlice traverses correctly
            // configResultLocal <- getString call <- "ab.feature" StringConstant
            builder.addEdge(DataFlowEdge(getStringCs.id, configResultLocal.id, DataFlowKind.RETURN_VALUE))
            builder.addEdge(DataFlowEdge(configKeyConst.id, getStringCs.id, DataFlowKind.PARAMETER_PASS))

            builder.addMethod(handleLiteral)
            builder.addMethod(handleEnum)
            builder.addMethod(handleConfig)
            builder.addMethod(getOption)
            builder.addMethod(getString)

            val graph = builder.build()
            inMemoryGraph = graph

            graphDir = Files.createTempDirectory("ab-keys-test")
            GraphStore.save(graph, graphDir)

            // Empty graph (no AB call sites) for the early-return path
            val emptyBuilder = DefaultGraph.Builder()
            val unrelated = MethodDescriptor(controllerType, "noop", emptyList(), TypeDescriptor("void"))
            emptyBuilder.addMethod(unrelated)
            val emptyGraph = emptyBuilder.build()
            emptyGraphDir = Files.createTempDirectory("ab-keys-test-empty")
            GraphStore.save(emptyGraph, emptyGraphDir)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            graphDir.toFile().deleteRecursively()
            emptyGraphDir.toFile().deleteRecursively()
        }
    }

    private fun captureOutput(block: () -> Int): Triple<String, String, Int> {
        val outBaos = ByteArrayOutputStream()
        val errBaos = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        System.setOut(PrintStream(outBaos))
        System.setErr(PrintStream(errBaos))
        val code = try {
            block()
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
        return Triple(outBaos.toString(), errBaos.toString(), code)
    }

    // ------------------------------------------------------------------
    // End-to-end via call()
    // ------------------------------------------------------------------

    @Test
    fun `ab-keys finds literal, enum, and reports text format`() {
        val cmd = AbKeysCommand()
        cmd.graphDir = graphDir
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(out.contains("inline_key"), "Should report literal key, got: $out")
        assertTrue(out.contains("AbKeys.MY_FLAG"), "Should report enum key, got: $out")
        assertTrue(out.contains("unique AB key"), "Should show summary, got: $out")
        assertTrue(out.contains("Controller.handleLiteral"), "Should show caller, got: $out")
    }

    @Test
    fun `ab-keys json format outputs JSON array`() {
        val cmd = AbKeysCommand()
        cmd.graphDir = graphDir
        cmd.format = "json"
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(out.trimStart().startsWith("["), "JSON output should start with '[', got: $out")
        assertTrue(out.contains("\"key\""), "JSON should contain 'key' field, got: $out")
        assertTrue(out.contains("inline_key"), "JSON should contain literal key, got: $out")
    }

    @Test
    fun `ab-keys returns 0 and stderr message when no call sites match`() {
        val cmd = AbKeysCommand()
        cmd.graphDir = emptyGraphDir
        val (_, err, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(err.contains("No AB call sites found"), "Should warn about empty result, got: $err")
    }

    @Test
    fun `ab-keys with custom methods filter`() {
        val cmd = AbKeysCommand()
        cmd.graphDir = graphDir
        cmd.methods = listOf("nonExistentMethod")
        val (_, err, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(err.contains("No AB call sites found"))
    }

    // ------------------------------------------------------------------
    // Direct private-method tests for branches that need a non-empty
    // ResourceAccessor or special inputs (resources are not persisted by
    // GraphStore.save, so the config branch can't be exercised via call()).
    // ------------------------------------------------------------------

    private fun newCmd(): AbKeysCommand = AbKeysCommand()

    private fun invokePrivate(cmd: AbKeysCommand, name: String, vararg args: Any?): Any? {
        val method = AbKeysCommand::class.java.declaredMethods.first { it.name == name }
        method.isAccessible = true
        return method.invoke(cmd, *args)
    }

    @Test
    fun `flattenYaml flattens nested map and ignores nulls`() {
        val cmd = newCmd()
        val out = HashMap<String, String>()
        val nested = mapOf(
            "ab" to mapOf(
                "feature" to "feature_x",
                "missing" to null
            ),
            "top" to 42
        )
        invokePrivate(cmd, "flattenYaml", nested, "", out)
        assertEquals("feature_x", out["ab.feature"])
        assertEquals("42", out["top"])
        assertTrue(!out.containsKey("ab.missing"), "Null values should be skipped")
    }

    @Test
    fun `flattenYaml with non-empty prefix builds dotted keys`() {
        val cmd = newCmd()
        val out = HashMap<String, String>()
        invokePrivate(cmd, "flattenYaml", mapOf("k" to "v"), "root", out)
        assertEquals("v", out["root.k"])
    }

    @Test
    fun `parseConfigFiles reads yaml and properties from a custom ResourceAccessor`() {
        val cmd = newCmd()
        val accessor = object : ResourceAccessor {
            private val files = mapOf(
                "application.yml" to "ab:\n  feature: feature_x\n",
                "config.yaml" to "other: value\n",
                "extra.properties" to "prop.key=prop_value\n",
                "broken.yml" to "::: not yaml :::",
                "broken.properties" to "\u0000\u0000\u0000\u0000"
            )
            override fun list(pattern: String): Sequence<ResourceEntry> = when {
                pattern.endsWith(".yml") -> sequenceOf(
                    ResourceEntry("application.yml", "test"),
                    ResourceEntry("broken.yml", "test")
                )
                pattern.endsWith(".yaml") -> sequenceOf(ResourceEntry("config.yaml", "test"))
                pattern.endsWith(".properties") -> sequenceOf(
                    ResourceEntry("extra.properties", "test"),
                    ResourceEntry("missing.properties", "test")
                )
                else -> emptySequence()
            }
            override fun open(path: String): InputStream {
                val content = files[path] ?: throw java.io.IOException(path)
                return ByteArrayInputStream(content.toByteArray())
            }
        }
        val graph = stubGraphWithResources(accessor)

        @Suppress("UNCHECKED_CAST")
        val result = invokePrivate(cmd, "parseConfigFiles", graph) as Map<String, String>
        assertEquals("feature_x", result["ab.feature"])
        assertEquals("value", result["other"])
        assertEquals("prop_value", result["prop.key"])
    }

    @Test
    fun `findConfigCalls resolves matching config keys and skips empty arguments`() {
        val cmd = newCmd()
        val analysis = DataFlowAnalysis(inMemoryGraph)
        val configArgId = (inMemoryGraph.node(configCallSiteId) as CallSiteNode).arguments[0]
        val slice = analysis.backwardSlice(configArgId)
        val configEntries = mapOf("ab.feature" to "feature_x")

        @Suppress("UNCHECKED_CAST")
        val result = invokePrivate(
            cmd, "findConfigCalls", slice, inMemoryGraph, configEntries
        ) as List<Pair<String, String>>
        assertTrue(result.any { it.first == "ab.feature" && it.second == "feature_x" })
    }

    @Test
    fun `findConfigCalls returns empty when no config entry matches`() {
        val cmd = newCmd()
        val analysis = DataFlowAnalysis(inMemoryGraph)
        val configArgId = (inMemoryGraph.node(configCallSiteId) as CallSiteNode).arguments[0]
        val slice = analysis.backwardSlice(configArgId)

        @Suppress("UNCHECKED_CAST")
        val result = invokePrivate(
            cmd, "findConfigCalls", slice, inMemoryGraph, emptyMap<String, String>()
        ) as List<Pair<String, String>>
        assertTrue(result.isEmpty())
    }

    @Test
    fun `outputText prints No AB keys for empty list`() {
        val cmd = newCmd()
        val (out, _, _) = captureOutput {
            invokePrivate(cmd, "outputText", emptyList<AbKeysCommand.AbKeyResult>())
            0
        }
        assertTrue(out.contains("No AB keys found"), "Got: $out")
    }

    @Test
    fun `outputText groups results and truncates after five`() {
        val cmd = newCmd()
        val results = (1..7).map { i ->
            AbKeysCommand.AbKeyResult(
                key = "shared_key",
                source = "literal",
                framework = "com.example.AbClient",
                method = "getOption",
                callerClass = "com.example.Caller$i",
                callerMethod = "m$i"
            )
        }
        val (out, _, _) = captureOutput {
            invokePrivate(cmd, "outputText", results)
            0
        }
        assertTrue(out.contains("shared_key"))
        assertTrue(out.contains("7 usages"))
        assertTrue(out.contains("and 2 more"), "Should truncate, got: $out")
    }

    @Test
    fun `outputText shows singular usage for single result`() {
        val cmd = newCmd()
        val results = listOf(
            AbKeysCommand.AbKeyResult("only", "literal", "f", "m", "c", "cm")
        )
        val (out, _, _) = captureOutput {
            invokePrivate(cmd, "outputText", results)
            0
        }
        assertTrue(out.contains("(1 usage)"), "Should be singular, got: $out")
    }

    @Test
    fun `outputJson serializes results`() {
        val cmd = newCmd()
        val results = listOf(
            AbKeysCommand.AbKeyResult("k", "literal", "f", "m", "c", "cm")
        )
        val (out, _, _) = captureOutput {
            invokePrivate(cmd, "outputJson", results)
            0
        }
        assertTrue(out.contains("\"key\""))
        assertTrue(out.contains("\"k\""))
    }

    @Test
    fun `AbKeyResult data class basics`() {
        val a = AbKeysCommand.AbKeyResult("k", "literal", "f", "m", "c", "cm")
        val b = a.copy()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a.toString().contains("k"))
    }

    @Test
    fun `runWithGraph emits config-sourced keys when resources resolve`() {
        val accessor = object : ResourceAccessor {
            override fun list(pattern: String): Sequence<ResourceEntry> = when {
                pattern.endsWith(".yml") -> sequenceOf(ResourceEntry("application.yml", "test"))
                else -> emptySequence()
            }
            override fun open(path: String): InputStream =
                ByteArrayInputStream("ab:\n  feature: feature_x\n".toByteArray())
        }
        val graph = stubGraphWithResources(accessor)
        val cmd = AbKeysCommand()
        val (out, _, code) = captureOutput { cmd.runWithGraph(graph) }
        assertEquals(0, code)
        assertTrue(out.contains("feature_x"), "Should emit config value, got: $out")
        assertTrue(out.contains("config:ab.feature"), "Should emit config source label, got: $out")
    }

    @Test
    fun `runWithGraph configPrefix filter excludes non-matching keys`() {
        val accessor = object : ResourceAccessor {
            override fun list(pattern: String): Sequence<ResourceEntry> = when {
                pattern.endsWith(".yml") -> sequenceOf(ResourceEntry("application.yml", "test"))
                else -> emptySequence()
            }
            override fun open(path: String): InputStream =
                ByteArrayInputStream("ab:\n  feature: feature_x\n".toByteArray())
        }
        val graph = stubGraphWithResources(accessor)
        val cmd = AbKeysCommand()
        cmd.configPrefix = "nomatch."
        val (out, _, code) = captureOutput { cmd.runWithGraph(graph) }
        assertEquals(0, code)
        assertTrue(!out.contains("config:ab.feature"), "Filtered prefix must drop config source, got: $out")
    }

    @Test
    fun `ab-keys with configPrefix filters via call()`() {
        // configPrefix is non-empty; literal/enum results don't go through the
        // prefix filter, but this exercises the option setter and call path.
        val cmd = AbKeysCommand()
        cmd.graphDir = graphDir
        cmd.configPrefix = "ab."
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(out.contains("inline_key"))
    }

    // Build a minimal Graph that delegates everything to inMemoryGraph but
    // overrides `resources` with the supplied accessor.
    private fun stubGraphWithResources(accessor: ResourceAccessor): Graph {
        val delegate = inMemoryGraph
        return object : Graph by delegate {
            override val resources: ResourceAccessor = accessor
        }
    }
}
