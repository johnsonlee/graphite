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
    // formatDeadCodeResult with unreferenced fields
    // ========================================================================

    @Test
    fun `formatDeadCodeResult shows unreferenced fields`() {
        val type = TypeDescriptor("com.example.Foo")
        val field = FieldDescriptor(type, "unusedField", TypeDescriptor("java.lang.String"))

        val result = DeadCodeResult(
            deadBranches = emptyList(),
            deadMethods = emptySet(),
            deadCallSites = emptySet(),
            unreferencedMethods = emptySet(),
            unreferencedFields = setOf(field)
        )

        val output = cmd.formatDeadCodeResult(result)
        assertTrue(output.contains("Unreferenced fields (1):"))
        assertTrue(output.contains("com.example.Foo.unusedField: String"))
        assertTrue(output.contains("Unreferenced fields: 1"))
    }

    @Test
    fun `formatDeadCodeResult summary includes field count`() {
        val result = DeadCodeResult(
            deadBranches = emptyList(),
            deadMethods = emptySet(),
            deadCallSites = emptySet(),
            unreferencedMethods = emptySet(),
            unreferencedFields = emptySet()
        )

        val output = cmd.formatDeadCodeResult(result)
        assertTrue(output.contains("Unreferenced fields: 0"))
    }

    @Test
    fun `formatDeadCodeResultJson includes unreferencedFields`() {
        val type = TypeDescriptor("com.example.Foo")
        val field = FieldDescriptor(type, "unused", TypeDescriptor("java.lang.String"))

        val result = DeadCodeResult(
            deadBranches = emptyList(),
            deadMethods = emptySet(),
            deadCallSites = emptySet(),
            unreferencedMethods = emptySet(),
            unreferencedFields = setOf(field)
        )

        val json = cmd.formatDeadCodeResultJson(result)
        val root = JsonParser.parseString(json).asJsonObject

        val fields = root.getAsJsonArray("unreferencedFields")
        assertEquals(1, fields.size())
        val entry = fields[0].asJsonObject
        assertEquals("com.example.Foo", entry.get("class").asString)
        assertEquals("unused", entry.get("field").asString)
        assertEquals("java.lang.String", entry.get("type").asString)

        val summary = root.getAsJsonObject("summary")
        assertEquals(1, summary.get("unreferencedFields").asInt)
    }

    @Test
    fun `formatDeadCodeResultJson empty unreferencedFields`() {
        val result = DeadCodeResult(
            deadBranches = emptyList(),
            deadMethods = emptySet(),
            deadCallSites = emptySet(),
            unreferencedMethods = emptySet(),
            unreferencedFields = emptySet()
        )

        val json = cmd.formatDeadCodeResultJson(result)
        val root = JsonParser.parseString(json).asJsonObject

        assertEquals(0, root.getAsJsonArray("unreferencedFields").size())
        assertEquals(0, root.getAsJsonObject("summary").get("unreferencedFields").asInt)
    }

    // ========================================================================
    // collectExcludedFieldClasses / filterUnreferencedFields
    // ========================================================================

    @Test
    fun `collectExcludedFieldClasses returns empty for no endpoints`() {
        val graph = stubGraph()
        val excluded = cmd.collectExcludedFieldClasses(graph)
        assertTrue(excluded.isEmpty())
    }

    @Test
    fun `filterUnreferencedFields excludes fields in excluded classes`() {
        val type = TypeDescriptor("com.example.ResponseDto")
        val field = FieldDescriptor(type, "name", TypeDescriptor("java.lang.String"))
        val excluded = setOf("com.example.ResponseDto")

        val graph = stubGraph()
        val filtered = cmd.filterUnreferencedFields(setOf(field), excluded, graph)
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `filterUnreferencedFields keeps fields not in excluded classes`() {
        val type = TypeDescriptor("com.example.Internal")
        val field = FieldDescriptor(type, "name", TypeDescriptor("java.lang.String"))
        val excluded = setOf("com.example.ResponseDto")

        val graph = stubGraph()
        val filtered = cmd.filterUnreferencedFields(setOf(field), excluded, graph)
        assertEquals(1, filtered.size)
    }

    @Test
    fun `filterUnreferencedFields excludes field with JsonProperty`() {
        val type = TypeDescriptor("com.example.Dto")
        val field = FieldDescriptor(type, "name", TypeDescriptor("java.lang.String"))

        val graph = stubGraph(
            jacksonFieldInfoMap = mapOf(
                "com.example.Dto#name" to io.johnsonlee.graphite.graph.JacksonFieldInfo(jsonName = "user_name")
            )
        )
        val filtered = cmd.filterUnreferencedFields(setOf(field), emptySet(), graph)
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `filterUnreferencedFields keeps field with JsonIgnore`() {
        val type = TypeDescriptor("com.example.Dto")
        val field = FieldDescriptor(type, "secret", TypeDescriptor("java.lang.String"))

        val graph = stubGraph(
            jacksonFieldInfoMap = mapOf(
                "com.example.Dto#secret" to io.johnsonlee.graphite.graph.JacksonFieldInfo(jsonName = "secret", isIgnored = true)
            )
        )
        val filtered = cmd.filterUnreferencedFields(setOf(field), emptySet(), graph)
        assertEquals(1, filtered.size)
    }

    @Test
    fun `collectExcludedFieldClasses with endpoints includes return and param types`() {
        val endpointMethod = MethodDescriptor(
            TypeDescriptor("com.example.Controller"),
            "getUser",
            listOf(TypeDescriptor("com.example.RequestDto")),
            TypeDescriptor("com.example.UserDto", listOf(TypeDescriptor("com.example.Address")))
        )
        val endpoint = EndpointInfo(
            method = endpointMethod,
            httpMethod = HttpMethod.GET,
            path = "/user"
        )
        val graph = stubGraph(endpointList = listOf(endpoint))
        val excluded = cmd.collectExcludedFieldClasses(graph)

        assertTrue(excluded.contains("com.example.UserDto"))
        assertTrue(excluded.contains("com.example.Address"))
        assertTrue(excluded.contains("com.example.RequestDto"))
        assertFalse(excluded.contains("com.example.Controller"))
    }

    @Test
    fun `collectTypeClasses skips java and kotlin primitives`() {
        val result = mutableSetOf<String>()
        cmd.collectTypeClasses(TypeDescriptor("java.lang.String"), result)
        cmd.collectTypeClasses(TypeDescriptor("kotlin.Int"), result)
        cmd.collectTypeClasses(TypeDescriptor("void"), result)
        cmd.collectTypeClasses(TypeDescriptor("boolean"), result)
        cmd.collectTypeClasses(TypeDescriptor("int"), result)
        cmd.collectTypeClasses(TypeDescriptor("long"), result)
        cmd.collectTypeClasses(TypeDescriptor("double"), result)
        cmd.collectTypeClasses(TypeDescriptor("float"), result)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `collectTypeClasses adds custom types with type arguments`() {
        val result = mutableSetOf<String>()
        val type = TypeDescriptor("com.example.Wrapper", listOf(TypeDescriptor("com.example.Inner")))
        cmd.collectTypeClasses(type, result)
        assertTrue(result.contains("com.example.Wrapper"))
        assertTrue(result.contains("com.example.Inner"))
    }

    @Test
    fun `collectTypeStructureClasses collects nested types`() {
        val structure = TypeStructure(
            type = TypeDescriptor("com.example.Outer"),
            typeArguments = mapOf(
                "T" to TypeStructure(type = TypeDescriptor("com.example.Inner"))
            ),
            fields = mapOf(
                "data" to FieldStructure(
                    name = "data",
                    declaredType = TypeDescriptor("com.example.Data"),
                    actualTypes = setOf(TypeStructure(type = TypeDescriptor("com.example.ActualData")))
                )
            )
        )
        val result = mutableSetOf<String>()
        cmd.collectTypeStructureClasses(structure, result)

        assertTrue(result.contains("com.example.Outer"))
        assertTrue(result.contains("com.example.Inner"))
        assertTrue(result.contains("com.example.ActualData"))
    }

    @Test
    fun `collectTypeStructureClasses skips java and kotlin primitives`() {
        val structure = TypeStructure(type = TypeDescriptor("java.lang.String"))
        val result = mutableSetOf<String>()
        cmd.collectTypeStructureClasses(structure, result)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `filterUnreferencedFields excludes field with getter Jackson info`() {
        val type = TypeDescriptor("com.example.Dto")
        val field = FieldDescriptor(type, "name", TypeDescriptor("java.lang.String"))

        val graph = stubGraph(
            jacksonGetterInfoMap = mapOf(
                "com.example.Dto#getName" to io.johnsonlee.graphite.graph.JacksonFieldInfo(jsonName = "user_name")
            )
        )
        val filtered = cmd.filterUnreferencedFields(setOf(field), emptySet(), graph)
        assertTrue(filtered.isEmpty())
    }

    // ========================================================================
    // --iterate validation
    // ========================================================================

    @Test
    fun `iterate without delete returns 1`() {
        val cmd = FindDeadCodeCommand()
        cmd.input = Path.of(System.getProperty("java.io.tmpdir"))
        cmd.iterate = true
        cmd.buildCommand = "echo done"
        cmd.delete = false

        assertEquals(1, cmd.call())
    }

    @Test
    fun `iterate without build-command returns 1`() {
        val cmd = FindDeadCodeCommand()
        cmd.input = Path.of(System.getProperty("java.io.tmpdir"))
        cmd.iterate = true
        cmd.delete = true
        cmd.buildCommand = null

        assertEquals(1, cmd.call())
    }

    @Test
    fun `iterate with dry-run returns 1`() {
        val cmd = FindDeadCodeCommand()
        cmd.input = Path.of(System.getProperty("java.io.tmpdir"))
        cmd.iterate = true
        cmd.delete = true
        cmd.buildCommand = "echo done"
        cmd.dryRun = true

        assertEquals(1, cmd.call())
    }

    @Test
    fun `iterate with export-assumptions returns 1`() {
        val cmd = FindDeadCodeCommand()
        cmd.input = Path.of(System.getProperty("java.io.tmpdir"))
        cmd.iterate = true
        cmd.delete = true
        cmd.buildCommand = "echo done"
        cmd.exportAssumptions = File.createTempFile("test", ".yaml").also { it.deleteOnExit() }

        assertEquals(1, cmd.call())
    }

    // ========================================================================
    // runBuildCommand
    // ========================================================================

    @Test
    fun `runBuildCommand returns 0 for successful command`() {
        assertEquals(0, cmd.runBuildCommand("echo done"))
    }

    @Test
    fun `runBuildCommand returns non-zero for failed command`() {
        val exitCode = cmd.runBuildCommand("exit 1")
        assertEquals(1, exitCode)
    }

    // ========================================================================
    // formatResult
    // ========================================================================

    @Test
    fun `formatResult dispatches to text formatter`() {
        val result = DeadCodeResult(
            deadBranches = emptyList(),
            deadMethods = emptySet(),
            deadCallSites = emptySet(),
            unreferencedMethods = emptySet(),
            unreferencedFields = emptySet()
        )
        cmd.outputFormat = "text"
        val output = cmd.formatResult(result)
        assertTrue(output.contains("Summary:"))
    }

    @Test
    fun `formatResult dispatches to json formatter`() {
        val result = DeadCodeResult(
            deadBranches = emptyList(),
            deadMethods = emptySet(),
            deadCallSites = emptySet(),
            unreferencedMethods = emptySet(),
            unreferencedFields = emptySet()
        )
        cmd.outputFormat = "json"
        val output = cmd.formatResult(result)
        assertTrue(output.contains("\"summary\""))
    }

    // ========================================================================
    // RoundStatistics
    // ========================================================================

    @Test
    fun `RoundStatistics data class holds correct values`() {
        val stats = FindDeadCodeCommand.RoundStatistics(
            round = 1,
            deletedMethods = 3,
            deletedFields = 2,
            deletedBranches = 1,
            deletedFiles = 0
        )
        assertEquals(1, stats.round)
        assertEquals(3, stats.deletedMethods)
        assertEquals(2, stats.deletedFields)
        assertEquals(1, stats.deletedBranches)
        assertEquals(0, stats.deletedFiles)
    }

    @Test
    fun `RoundStatistics copy updates round`() {
        val stats = FindDeadCodeCommand.RoundStatistics(
            round = 0,
            deletedMethods = 5,
            deletedFields = 0,
            deletedBranches = 0,
            deletedFiles = 1
        )
        val updated = stats.copy(round = 3)
        assertEquals(3, updated.round)
        assertEquals(5, updated.deletedMethods)
    }

    // ========================================================================
    // printIterationSummary
    // ========================================================================

    @Test
    fun `printIterationSummary with empty rounds prints no rounds message`() {
        val errBaos = java.io.ByteArrayOutputStream()
        val oldErr = System.err
        System.setErr(java.io.PrintStream(errBaos))
        try {
            cmd.printIterationSummary(emptyList(), converged = false, buildFailedOnRound = null)
        } finally {
            System.setErr(oldErr)
        }
        val output = errBaos.toString()
        assertTrue(output.contains("No rounds executed."))
    }

    @Test
    fun `printIterationSummary with converged rounds prints summary table`() {
        val rounds = listOf(
            FindDeadCodeCommand.RoundStatistics(round = 1, deletedMethods = 3, deletedFields = 1, deletedBranches = 0, deletedFiles = 1),
            FindDeadCodeCommand.RoundStatistics(round = 2, deletedMethods = 1, deletedFields = 0, deletedBranches = 0, deletedFiles = 0)
        )
        val errBaos = java.io.ByteArrayOutputStream()
        val oldErr = System.err
        System.setErr(java.io.PrintStream(errBaos))
        try {
            cmd.printIterationSummary(rounds, converged = true, buildFailedOnRound = null)
        } finally {
            System.setErr(oldErr)
        }
        val output = errBaos.toString()
        assertTrue(output.contains("Iteration Summary"))
        assertTrue(output.contains("Round"))
        assertTrue(output.contains("Total"))
        assertTrue(output.contains("Converged after 2 round(s)."))
    }

    @Test
    fun `printIterationSummary with build failure prints failure message`() {
        val rounds = listOf(
            FindDeadCodeCommand.RoundStatistics(round = 1, deletedMethods = 2, deletedFields = 0, deletedBranches = 0, deletedFiles = 0)
        )
        val errBaos = java.io.ByteArrayOutputStream()
        val oldErr = System.err
        System.setErr(java.io.PrintStream(errBaos))
        try {
            cmd.printIterationSummary(rounds, converged = false, buildFailedOnRound = 1)
        } finally {
            System.setErr(oldErr)
        }
        val output = errBaos.toString()
        assertTrue(output.contains("Build failed after round 1."))
    }

    @Test
    fun `printIterationSummary with max rounds prints max rounds message`() {
        val rounds = listOf(
            FindDeadCodeCommand.RoundStatistics(round = 1, deletedMethods = 1, deletedFields = 0, deletedBranches = 0, deletedFiles = 0)
        )
        val errBaos = java.io.ByteArrayOutputStream()
        val oldErr = System.err
        System.setErr(java.io.PrintStream(errBaos))
        try {
            cmd.printIterationSummary(rounds, converged = false, buildFailedOnRound = null)
        } finally {
            System.setErr(oldErr)
        }
        val output = errBaos.toString()
        assertTrue(output.contains("Reached maximum rounds (1)."))
    }

    // ========================================================================
    // executeDeletionsWithStats
    // ========================================================================

    @Test
    fun `executeDeletionsWithStats returns null when source dirs empty`() {
        val result = DeadCodeResult(
            deadBranches = emptyList(),
            deadMethods = emptySet(),
            deadCallSites = emptySet(),
            unreferencedMethods = emptySet(),
            unreferencedFields = emptySet()
        )
        val graph = stubGraph()
        cmd.sourceDirs = emptyList()
        val stats = cmd.executeDeletionsWithStats(result, graph)
        assertEquals(null, stats)
    }

    @Test
    fun `executeDeletionsWithStats returns null when source dir does not exist`() {
        val result = DeadCodeResult(
            deadBranches = emptyList(),
            deadMethods = emptySet(),
            deadCallSites = emptySet(),
            unreferencedMethods = emptySet(),
            unreferencedFields = emptySet()
        )
        val graph = stubGraph()
        cmd.sourceDirs = listOf(Path.of("/nonexistent/source/dir"))
        val stats = cmd.executeDeletionsWithStats(result, graph)
        assertEquals(null, stats)
    }

    @Test
    fun `executeDeletionsWithStats returns zero stats for empty result`() {
        val result = DeadCodeResult(
            deadBranches = emptyList(),
            deadMethods = emptySet(),
            deadCallSites = emptySet(),
            unreferencedMethods = emptySet(),
            unreferencedFields = emptySet()
        )
        val graph = stubGraph()
        val tempDir = java.nio.file.Files.createTempDirectory("test-source")
        cmd.sourceDirs = listOf(tempDir)
        val stats = cmd.executeDeletionsWithStats(result, graph)
        assertEquals(0, stats?.deletedMethods)
        assertEquals(0, stats?.deletedFields)
        assertEquals(0, stats?.deletedBranches)
        assertEquals(0, stats?.deletedFiles)
        tempDir.toFile().deleteRecursively()
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

    private fun stubGraph(
        jacksonFieldInfoMap: Map<String, io.johnsonlee.graphite.graph.JacksonFieldInfo> = emptyMap(),
        jacksonGetterInfoMap: Map<String, io.johnsonlee.graphite.graph.JacksonFieldInfo> = emptyMap(),
        endpointList: List<EndpointInfo> = emptyList()
    ): io.johnsonlee.graphite.graph.Graph = object : io.johnsonlee.graphite.graph.Graph {
        override fun node(id: NodeId): Node? = null
        override fun <T : Node> nodes(type: Class<T>): Sequence<T> = emptySequence()
        override fun outgoing(id: NodeId): Sequence<Edge> = emptySequence()
        override fun incoming(id: NodeId): Sequence<Edge> = emptySequence()
        override fun <T : Edge> outgoing(id: NodeId, type: Class<T>): Sequence<T> = emptySequence()
        override fun <T : Edge> incoming(id: NodeId, type: Class<T>): Sequence<T> = emptySequence()
        override fun callSites(methodPattern: io.johnsonlee.graphite.graph.MethodPattern): Sequence<CallSiteNode> = emptySequence()
        override fun supertypes(type: TypeDescriptor): Sequence<TypeDescriptor> = emptySequence()
        override fun subtypes(type: TypeDescriptor): Sequence<TypeDescriptor> = emptySequence()
        override fun methods(pattern: io.johnsonlee.graphite.graph.MethodPattern): Sequence<MethodDescriptor> = emptySequence()
        override fun enumValues(enumClass: String, enumName: String): List<Any?>? = null
        override fun endpoints(pattern: String?, httpMethod: HttpMethod?): Sequence<EndpointInfo> = endpointList.asSequence()
        override fun branchScopes(): Sequence<BranchScope> = emptySequence()
        override fun branchScopesFor(conditionNodeId: NodeId): Sequence<BranchScope> = emptySequence()
        override fun jacksonFieldInfo(className: String, fieldName: String): io.johnsonlee.graphite.graph.JacksonFieldInfo? =
            jacksonFieldInfoMap["$className#$fieldName"]
        override fun jacksonGetterInfo(className: String, methodName: String): io.johnsonlee.graphite.graph.JacksonFieldInfo? =
            jacksonGetterInfoMap["$className#$methodName"]
    }
}
