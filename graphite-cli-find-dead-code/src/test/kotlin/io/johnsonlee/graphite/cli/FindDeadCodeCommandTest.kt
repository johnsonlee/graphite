package io.johnsonlee.graphite.cli

import com.google.gson.JsonParser
import io.johnsonlee.graphite.analysis.DeadBranch
import io.johnsonlee.graphite.analysis.DeadCodeResult
import io.johnsonlee.graphite.core.*
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FindDeadCodeCommandTest {

    private val cmd = FindDeadCodeCommand()

    // ========================================================================
    // parseYamlValue
    // ========================================================================

    @Test
    fun `parseYamlValue returns true for true string`() {
        assertEquals(true, cmd.parseYamlValue("true"))
    }

    @Test
    fun `parseYamlValue returns false for false string`() {
        assertEquals(false, cmd.parseYamlValue("false"))
    }

    @Test
    fun `parseYamlValue returns string null for null`() {
        assertEquals("null", cmd.parseYamlValue("null"))
    }

    @Test
    fun `parseYamlValue returns Int for integer string`() {
        assertEquals(42, cmd.parseYamlValue("42"))
    }

    @Test
    fun `parseYamlValue returns negative Int`() {
        assertEquals(-1, cmd.parseYamlValue("-1"))
    }

    @Test
    fun `parseYamlValue returns Long for large number`() {
        assertEquals(3000000000L, cmd.parseYamlValue("3000000000"))
    }

    @Test
    fun `parseYamlValue returns Double for decimal`() {
        assertEquals(3.14, cmd.parseYamlValue("3.14"))
    }

    @Test
    fun `parseYamlValue returns string for non-numeric`() {
        assertEquals("hello", cmd.parseYamlValue("hello"))
    }

    @Test
    fun `parseYamlValue returns string for empty`() {
        assertEquals("", cmd.parseYamlValue(""))
    }

    // ========================================================================
    // isSyntheticMethod
    // ========================================================================

    @Test
    fun `isSyntheticMethod returns true for dollar-containing names`() {
        val method = makeMethod("access\$100")
        assertTrue(cmd.isSyntheticMethod(method))
    }

    @Test
    fun `isSyntheticMethod returns true for lambda methods`() {
        val method = makeMethod("lambda\$main\$0")
        assertTrue(cmd.isSyntheticMethod(method))
    }

    @Test
    fun `isSyntheticMethod returns true for values`() {
        val method = makeMethod("values")
        assertTrue(cmd.isSyntheticMethod(method))
    }

    @Test
    fun `isSyntheticMethod returns true for valueOf`() {
        val method = makeMethod("valueOf")
        assertTrue(cmd.isSyntheticMethod(method))
    }

    @Test
    fun `isSyntheticMethod returns false for regular method`() {
        val method = makeMethod("doWork")
        assertFalse(cmd.isSyntheticMethod(method))
    }

    @Test
    fun `isSyntheticMethod returns false for init`() {
        val method = makeMethod("<init>")
        assertFalse(cmd.isSyntheticMethod(method))
    }

    // ========================================================================
    // constantDisplayValue
    // ========================================================================

    @Test
    fun `constantDisplayValue handles IntConstant`() {
        assertEquals("42", cmd.constantDisplayValue(IntConstant(NodeId.next(), 42)))
    }

    @Test
    fun `constantDisplayValue handles StringConstant`() {
        assertEquals("hello", cmd.constantDisplayValue(StringConstant(NodeId.next(), "hello")))
    }

    @Test
    fun `constantDisplayValue handles BooleanConstant true`() {
        assertEquals("true", cmd.constantDisplayValue(BooleanConstant(NodeId.next(), true)))
    }

    @Test
    fun `constantDisplayValue handles BooleanConstant false`() {
        assertEquals("false", cmd.constantDisplayValue(BooleanConstant(NodeId.next(), false)))
    }

    @Test
    fun `constantDisplayValue handles LongConstant`() {
        assertEquals("100", cmd.constantDisplayValue(LongConstant(NodeId.next(), 100L)))
    }

    @Test
    fun `constantDisplayValue handles FloatConstant`() {
        assertEquals("1.5", cmd.constantDisplayValue(FloatConstant(NodeId.next(), 1.5f)))
    }

    @Test
    fun `constantDisplayValue handles DoubleConstant`() {
        assertEquals("2.5", cmd.constantDisplayValue(DoubleConstant(NodeId.next(), 2.5)))
    }

    @Test
    fun `constantDisplayValue handles NullConstant`() {
        assertEquals("null", cmd.constantDisplayValue(NullConstant(NodeId.next())))
    }

    @Test
    fun `constantDisplayValue handles EnumConstant with value`() {
        val constant = EnumConstant(
            id = NodeId.next(),
            enumType = TypeDescriptor("com.example.Status"),
            enumName = "ACTIVE",
            constructorArgs = listOf(1)
        )
        assertEquals("1", cmd.constantDisplayValue(constant))
    }

    @Test
    fun `constantDisplayValue handles EnumConstant without value`() {
        val constant = EnumConstant(
            id = NodeId.next(),
            enumType = TypeDescriptor("com.example.Status"),
            enumName = "ACTIVE"
        )
        assertEquals("Status.ACTIVE", cmd.constantDisplayValue(constant))
    }

    // ========================================================================
    // dumpYaml
    // ========================================================================

    @Test
    fun `dumpYaml produces block-style YAML`() {
        val data = mapOf("key" to "value", "number" to 42)
        val yaml = cmd.dumpYaml(data)
        assertTrue(yaml.contains("key: value"))
        assertTrue(yaml.contains("number: 42"))
    }

    @Test
    fun `dumpYaml handles nested maps`() {
        val data = mapOf("outer" to mapOf("inner" to "value"))
        val yaml = cmd.dumpYaml(data)
        assertTrue(yaml.contains("outer:"))
        assertTrue(yaml.contains("inner: value"))
    }

    @Test
    fun `dumpYaml handles lists`() {
        val data = mapOf("items" to listOf("a", "b", "c"))
        val yaml = cmd.dumpYaml(data)
        assertTrue(yaml.contains("items:"))
        assertTrue(yaml.contains("- a"))
    }

    // ========================================================================
    // loadAssumptions
    // ========================================================================

    @Test
    fun `loadAssumptions parses call assumption with value`() {
        val yaml = """
            assumptions:
              - call:
                  class: com.example.Client
                  method: getOption
                  args:
                    '0': 1001
                value: true
        """.trimIndent()

        val file = createTempYamlFile(yaml)
        val assumptions = cmd.loadAssumptions(file)

        assertEquals(1, assumptions.size)
        assertEquals("com.example.Client", assumptions[0].methodPattern.declaringClass)
        assertEquals("getOption", assumptions[0].methodPattern.name)
        assertEquals(0, assumptions[0].argumentIndex)
        assertEquals(1001, assumptions[0].argumentValue)
        assertEquals(true, assumptions[0].assumedValue)
    }

    @Test
    fun `loadAssumptions skips entries with question mark placeholder`() {
        val yaml = """
            assumptions:
              - call:
                  class: com.example.Client
                  method: getOption
                  args:
                    '0': 1001
                value: "???"
        """.trimIndent()

        val file = createTempYamlFile(yaml)
        val assumptions = cmd.loadAssumptions(file)

        assertTrue(assumptions.isEmpty())
    }

    @Test
    fun `loadAssumptions skips entries without value`() {
        val yaml = """
            assumptions:
              - call:
                  class: com.example.Client
                  method: getOption
        """.trimIndent()

        val file = createTempYamlFile(yaml)
        val assumptions = cmd.loadAssumptions(file)

        assertTrue(assumptions.isEmpty())
    }

    @Test
    fun `loadAssumptions parses field assumption`() {
        val yaml = """
            assumptions:
              - field:
                  class: com.example.Config
                  name: debugMode
                value: false
        """.trimIndent()

        val file = createTempYamlFile(yaml)
        val assumptions = cmd.loadAssumptions(file)

        assertEquals(1, assumptions.size)
        assertEquals("com.example.Config", assumptions[0].methodPattern.declaringClass)
        assertEquals("debugMode", assumptions[0].methodPattern.name)
        assertEquals(false, assumptions[0].assumedValue)
    }

    @Test
    fun `loadAssumptions returns empty for empty file`() {
        val file = createTempYamlFile("")
        val assumptions = cmd.loadAssumptions(file)
        assertTrue(assumptions.isEmpty())
    }

    @Test
    fun `loadAssumptions returns empty when assumptions key is missing`() {
        val yaml = """
            other_key: value
        """.trimIndent()

        val file = createTempYamlFile(yaml)
        val assumptions = cmd.loadAssumptions(file)
        assertTrue(assumptions.isEmpty())
    }

    @Test
    fun `loadAssumptions handles multiple assumptions`() {
        val yaml = """
            assumptions:
              - call:
                  class: com.example.Client
                  method: getOption
                  args:
                    '0': 1001
                value: true
              - call:
                  class: com.example.Client
                  method: getOption
                  args:
                    '0': 1002
                value: false
        """.trimIndent()

        val file = createTempYamlFile(yaml)
        val assumptions = cmd.loadAssumptions(file)

        assertEquals(2, assumptions.size)
    }

    @Test
    fun `loadAssumptions skips entry missing class in call`() {
        val yaml = """
            assumptions:
              - call:
                  method: getOption
                value: true
        """.trimIndent()

        val file = createTempYamlFile(yaml)
        val assumptions = cmd.loadAssumptions(file)
        assertTrue(assumptions.isEmpty())
    }

    @Test
    fun `loadAssumptions skips entry missing method in call`() {
        val yaml = """
            assumptions:
              - call:
                  class: com.example.Client
                value: true
        """.trimIndent()

        val file = createTempYamlFile(yaml)
        val assumptions = cmd.loadAssumptions(file)
        assertTrue(assumptions.isEmpty())
    }

    @Test
    fun `loadAssumptions skips entry with neither call nor field`() {
        val yaml = """
            assumptions:
              - other_type:
                  key: value
                value: true
        """.trimIndent()

        val file = createTempYamlFile(yaml)
        val assumptions = cmd.loadAssumptions(file)
        assertTrue(assumptions.isEmpty())
    }

    @Test
    fun `loadAssumptions call without args`() {
        val yaml = """
            assumptions:
              - call:
                  class: com.example.Client
                  method: isEnabled
                value: true
        """.trimIndent()

        val file = createTempYamlFile(yaml)
        val assumptions = cmd.loadAssumptions(file)

        assertEquals(1, assumptions.size)
        assertEquals(null, assumptions[0].argumentIndex)
        assertEquals(null, assumptions[0].argumentValue)
        assertEquals(true, assumptions[0].assumedValue)
    }

    // ========================================================================
    // formatDeadCodeResult
    // ========================================================================

    @Test
    fun `formatDeadCodeResult shows unreferenced methods`() {
        val type = TypeDescriptor("com.example.Foo")
        val method = MethodDescriptor(type, "unused", listOf(TypeDescriptor("java.lang.String")), TypeDescriptor("void"))

        val result = DeadCodeResult(
            deadBranches = emptyList(),
            deadMethods = emptySet(),
            deadCallSites = emptySet(),
            unreferencedMethods = setOf(method)
        )

        val output = cmd.formatDeadCodeResult(result)
        assertTrue(output.contains("Unreferenced methods (1):"))
        assertTrue(output.contains("com.example.Foo.unused(String)"))
        assertTrue(output.contains("Total dead methods: 1"))
    }

    @Test
    fun `formatDeadCodeResult shows dead branches`() {
        val type = TypeDescriptor("com.example.Bar")
        val method = MethodDescriptor(type, "check", emptyList(), TypeDescriptor("void"))
        val callee = MethodDescriptor(type, "dead", emptyList(), TypeDescriptor("void"))
        val callSite = CallSiteNode(
            id = NodeId.next(),
            caller = method,
            callee = callee,
            lineNumber = 10,
            receiver = null,
            arguments = emptyList()
        )

        val result = DeadCodeResult(
            deadBranches = listOf(
                DeadBranch(
                    conditionNodeId = NodeId.next(),
                    deadKind = ControlFlowKind.BRANCH_TRUE,
                    method = method,
                    deadNodeIds = IntOpenHashSet(),
                    deadCallSites = listOf(callSite)
                )
            ),
            deadMethods = emptySet(),
            deadCallSites = emptySet(),
            unreferencedMethods = emptySet()
        )

        val output = cmd.formatDeadCodeResult(result)
        assertTrue(output.contains("Dead branches (1):"))
        assertTrue(output.contains("BRANCH_TRUE"))
        assertTrue(output.contains("Bar.dead()"))
    }

    @Test
    fun `formatDeadCodeResult shows transitively dead methods`() {
        val type = TypeDescriptor("com.example.Baz")
        val method = MethodDescriptor(type, "transitiveDead", emptyList(), TypeDescriptor("void"))

        val result = DeadCodeResult(
            deadBranches = emptyList(),
            deadMethods = setOf(method),
            deadCallSites = emptySet(),
            unreferencedMethods = emptySet()
        )

        val output = cmd.formatDeadCodeResult(result)
        assertTrue(output.contains("Transitively dead methods (1):"))
        assertTrue(output.contains("com.example.Baz.transitiveDead()"))
    }

    @Test
    fun `formatDeadCodeResult shows summary with zero counts`() {
        val result = DeadCodeResult(
            deadBranches = emptyList(),
            deadMethods = emptySet(),
            deadCallSites = emptySet(),
            unreferencedMethods = emptySet()
        )

        val output = cmd.formatDeadCodeResult(result)
        assertTrue(output.contains("Summary:"))
        assertTrue(output.contains("Total dead methods: 0"))
        // The detail sections should not appear (only the summary section has these labels)
        assertFalse(output.contains("Unreferenced methods ("))
        assertFalse(output.contains("Transitively dead methods ("))
    }

    @Test
    fun `formatDeadCodeResult truncates dead call sites over 10`() {
        val type = TypeDescriptor("com.example.Foo")
        val method = MethodDescriptor(type, "check", emptyList(), TypeDescriptor("void"))

        val callSites = (1..15).map { i ->
            val callee = MethodDescriptor(type, "dead$i", emptyList(), TypeDescriptor("void"))
            CallSiteNode(
                id = NodeId.next(),
                caller = method,
                callee = callee,
                lineNumber = i,
                receiver = null,
                arguments = emptyList()
            )
        }

        val result = DeadCodeResult(
            deadBranches = listOf(
                DeadBranch(
                    conditionNodeId = NodeId.next(),
                    deadKind = ControlFlowKind.BRANCH_TRUE,
                    method = method,
                    deadNodeIds = IntOpenHashSet(),
                    deadCallSites = callSites
                )
            ),
            deadMethods = emptySet(),
            deadCallSites = emptySet(),
            unreferencedMethods = emptySet()
        )

        val output = cmd.formatDeadCodeResult(result)
        assertTrue(output.contains("... and 5 more"))
    }

    // ========================================================================
    // formatDeadCodeResultJson
    // ========================================================================

    @Test
    fun `formatDeadCodeResultJson returns valid JSON with unreferenced methods`() {
        val type = TypeDescriptor("com.example.Foo")
        val method = MethodDescriptor(type, "unused", listOf(TypeDescriptor("java.lang.String")), TypeDescriptor("void"))

        val result = DeadCodeResult(
            deadBranches = emptyList(),
            deadMethods = emptySet(),
            deadCallSites = emptySet(),
            unreferencedMethods = setOf(method)
        )

        val json = cmd.formatDeadCodeResultJson(result)
        val root = JsonParser.parseString(json).asJsonObject

        val unreferenced = root.getAsJsonArray("unreferencedMethods")
        assertEquals(1, unreferenced.size())
        val entry = unreferenced[0].asJsonObject
        assertEquals("com.example.Foo", entry.get("class").asString)
        assertEquals("unused", entry.get("method").asString)
        assertEquals("com.example.Foo.unused(String)", entry.get("signature").asString)

        val summary = root.getAsJsonObject("summary")
        assertEquals(1, summary.get("unreferencedMethods").asInt)
        assertEquals(1, summary.get("totalDeadMethods").asInt)
    }

    @Test
    fun `formatDeadCodeResultJson returns valid JSON with dead branches`() {
        val type = TypeDescriptor("com.example.Bar")
        val method = MethodDescriptor(type, "check", emptyList(), TypeDescriptor("void"))
        val callee = MethodDescriptor(type, "dead", emptyList(), TypeDescriptor("void"))
        val callSite = CallSiteNode(
            id = NodeId.next(),
            caller = method,
            callee = callee,
            lineNumber = 10,
            receiver = null,
            arguments = emptyList()
        )

        val result = DeadCodeResult(
            deadBranches = listOf(
                DeadBranch(
                    conditionNodeId = NodeId.next(),
                    deadKind = ControlFlowKind.BRANCH_TRUE,
                    method = method,
                    deadNodeIds = IntOpenHashSet(),
                    deadCallSites = listOf(callSite)
                )
            ),
            deadMethods = emptySet(),
            deadCallSites = emptySet(),
            unreferencedMethods = emptySet()
        )

        val json = cmd.formatDeadCodeResultJson(result)
        val root = JsonParser.parseString(json).asJsonObject

        val branches = root.getAsJsonArray("deadBranches")
        assertEquals(1, branches.size())
        val branch = branches[0].asJsonObject
        assertEquals("BRANCH_TRUE", branch.get("deadKind").asString)
        assertEquals("com.example.Bar", branch.get("class").asString)

        val callSites = branch.getAsJsonArray("deadCallSites")
        assertEquals(1, callSites.size())
        assertEquals("com.example.Bar.dead", callSites[0].asJsonObject.get("callee").asString)
    }

    @Test
    fun `formatDeadCodeResultJson returns valid JSON with dead methods`() {
        val type = TypeDescriptor("com.example.Baz")
        val method = MethodDescriptor(type, "transitiveDead", emptyList(), TypeDescriptor("void"))

        val result = DeadCodeResult(
            deadBranches = emptyList(),
            deadMethods = setOf(method),
            deadCallSites = emptySet(),
            unreferencedMethods = emptySet()
        )

        val json = cmd.formatDeadCodeResultJson(result)
        val root = JsonParser.parseString(json).asJsonObject

        val deadMethods = root.getAsJsonArray("deadMethods")
        assertEquals(1, deadMethods.size())
        assertEquals("transitiveDead", deadMethods[0].asJsonObject.get("method").asString)

        val summary = root.getAsJsonObject("summary")
        assertEquals(1, summary.get("transitivelyDeadMethods").asInt)
        assertEquals(1, summary.get("totalDeadMethods").asInt)
    }

    @Test
    fun `formatDeadCodeResultJson returns valid JSON with empty result`() {
        val result = DeadCodeResult(
            deadBranches = emptyList(),
            deadMethods = emptySet(),
            deadCallSites = emptySet(),
            unreferencedMethods = emptySet()
        )

        val json = cmd.formatDeadCodeResultJson(result)
        val root = JsonParser.parseString(json).asJsonObject

        assertEquals(0, root.getAsJsonArray("unreferencedMethods").size())
        assertEquals(0, root.getAsJsonArray("deadBranches").size())
        assertEquals(0, root.getAsJsonArray("deadMethods").size())

        val summary = root.getAsJsonObject("summary")
        assertEquals(0, summary.get("totalDeadMethods").asInt)
        assertEquals(0, summary.get("deadCallSites").asInt)
    }

    // ========================================================================
    // call() error handling
    // ========================================================================

    @Test
    fun `call returns 1 when input path does not exist`() {
        val cmd = FindDeadCodeCommand()
        cmd.input = Path.of("/nonexistent/path/to/nothing.jar")

        val exitCode = cmd.call()
        assertEquals(1, exitCode)
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun makeMethod(name: String): MethodDescriptor {
        val type = TypeDescriptor("com.example.Foo")
        return MethodDescriptor(type, name, emptyList(), TypeDescriptor("void"))
    }

    private fun createTempYamlFile(content: String): File {
        val file = File.createTempFile("test-assumptions", ".yaml")
        file.deleteOnExit()
        file.writeText(content)
        return file
    }
}
