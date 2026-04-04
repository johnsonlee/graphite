package io.johnsonlee.graphite.cli

import com.google.gson.JsonParser
import io.johnsonlee.graphite.analysis.DataFlowPath
import io.johnsonlee.graphite.analysis.PropagationNodeType
import io.johnsonlee.graphite.analysis.PropagationPath
import io.johnsonlee.graphite.analysis.PropagationSourceType
import io.johnsonlee.graphite.analysis.PropagationStep
import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.query.ArgumentConstantResult
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FindArgumentsCommandTest {

    private val cmd = FindArgumentsCommand()

    // ========================================================================
    // constantToString
    // ========================================================================

    @Test
    fun `constantToString formats IntConstant`() {
        val c = IntConstant(NodeId.next(), 42)
        assertEquals("42", cmd.constantToString(c))
    }

    @Test
    fun `constantToString formats StringConstant with quotes`() {
        val c = StringConstant(NodeId.next(), "hello")
        assertEquals("\"hello\"", cmd.constantToString(c))
    }

    @Test
    fun `constantToString formats BooleanConstant true`() {
        val c = BooleanConstant(NodeId.next(), true)
        assertEquals("true", cmd.constantToString(c))
    }

    @Test
    fun `constantToString formats BooleanConstant false`() {
        val c = BooleanConstant(NodeId.next(), false)
        assertEquals("false", cmd.constantToString(c))
    }

    @Test
    fun `constantToString formats LongConstant with L suffix`() {
        val c = LongConstant(NodeId.next(), 100L)
        assertEquals("100L", cmd.constantToString(c))
    }

    @Test
    fun `constantToString formats FloatConstant with f suffix`() {
        val c = FloatConstant(NodeId.next(), 1.5f)
        assertEquals("1.5f", cmd.constantToString(c))
    }

    @Test
    fun `constantToString formats DoubleConstant`() {
        val c = DoubleConstant(NodeId.next(), 2.5)
        assertEquals("2.5", cmd.constantToString(c))
    }

    @Test
    fun `constantToString formats NullConstant`() {
        val c = NullConstant(NodeId.next())
        assertEquals("null", cmd.constantToString(c))
    }

    @Test
    fun `constantToString formats EnumConstant with value`() {
        val c = EnumConstant(
            NodeId.next(), TypeDescriptor("com.example.Status"), "ACTIVE", listOf(1)
        )
        assertEquals("Status.ACTIVE (value: 1)", cmd.constantToString(c))
    }

    @Test
    fun `constantToString formats EnumConstant without value`() {
        val c = EnumConstant(
            NodeId.next(), TypeDescriptor("com.example.Status"), "PENDING"
        )
        assertEquals("Status.PENDING", cmd.constantToString(c))
    }

    // ========================================================================
    // constantTypeName
    // ========================================================================

    @Test
    fun `constantTypeName for int`() {
        assertEquals("int", cmd.constantTypeName(IntConstant(NodeId.next(), 0)))
    }

    @Test
    fun `constantTypeName for long`() {
        assertEquals("long", cmd.constantTypeName(LongConstant(NodeId.next(), 0L)))
    }

    @Test
    fun `constantTypeName for float`() {
        assertEquals("float", cmd.constantTypeName(FloatConstant(NodeId.next(), 0f)))
    }

    @Test
    fun `constantTypeName for double`() {
        assertEquals("double", cmd.constantTypeName(DoubleConstant(NodeId.next(), 0.0)))
    }

    @Test
    fun `constantTypeName for String`() {
        assertEquals("String", cmd.constantTypeName(StringConstant(NodeId.next(), "")))
    }

    @Test
    fun `constantTypeName for boolean`() {
        assertEquals("boolean", cmd.constantTypeName(BooleanConstant(NodeId.next(), true)))
    }

    @Test
    fun `constantTypeName for null`() {
        assertEquals("null", cmd.constantTypeName(NullConstant(NodeId.next())))
    }

    @Test
    fun `constantTypeName for enum`() {
        val c = EnumConstant(NodeId.next(), TypeDescriptor("com.example.Status"), "ACTIVE")
        assertEquals("enum (com.example.Status)", cmd.constantTypeName(c))
    }

    // ========================================================================
    // constantKey
    // ========================================================================

    @Test
    fun `constantKey for enum`() {
        val c = EnumConstant(NodeId.next(), TypeDescriptor("com.example.Status"), "ACTIVE")
        assertEquals("enum:com.example.Status.ACTIVE", cmd.constantKey(c))
    }

    @Test
    fun `constantKey for value`() {
        val c = IntConstant(NodeId.next(), 42)
        assertEquals("value:42", cmd.constantKey(c))
    }

    @Test
    fun `constantKey for null`() {
        val c = NullConstant(NodeId.next())
        assertEquals("value:null", cmd.constantKey(c))
    }

    // ========================================================================
    // call() error handling
    // ========================================================================

    @Test
    fun `call returns 1 when input path does not exist`() {
        val cmd = FindArgumentsCommand()
        cmd.input = Path.of("/nonexistent/path/to/nothing.jar")
        cmd.targetClass = "com.example.Foo"
        cmd.targetMethod = "bar"

        val exitCode = cmd.call()
        assertEquals(1, exitCode)
    }

    // ========================================================================
    // formatText
    // ========================================================================

    @Test
    fun `formatText shows constant values and occurrences`() {
        val results = listOf(makeResult(1001, 0))
        cmd.argIndices = listOf(0)
        cmd.targetClass = "com.example.Client"
        cmd.targetMethod = "getOption"

        val output = cmd.formatText(results)
        assertTrue(output.contains("1001"))
        assertTrue(output.contains("Type: int"))
        assertTrue(output.contains("Occurrences: 1"))
        assertTrue(output.contains("Unique values: 1"))
    }

    @Test
    fun `formatText shows multiple argument indices`() {
        val results = listOf(
            makeResult(1001, 0),
            makeResult(2002, 1)
        )
        cmd.argIndices = listOf(0, 1)
        cmd.targetClass = "com.example.Client"
        cmd.targetMethod = "setConfig"

        val output = cmd.formatText(results)
        assertTrue(output.contains("[arg 0]"))
        assertTrue(output.contains("[arg 1]"))
        assertTrue(output.contains("Arguments analyzed:"))
    }

    @Test
    fun `formatText truncates over 5 occurrences`() {
        val results = (1..7).map { makeResult(1001, 0) }
        cmd.argIndices = listOf(0)
        cmd.targetClass = "com.example.Client"
        cmd.targetMethod = "getOption"

        val output = cmd.formatText(results)
        assertTrue(output.contains("... and 2 more"))
    }

    @Test
    fun `formatText shows propagation depth statistics`() {
        val step = PropagationStep(NodeId(1), PropagationNodeType.CONSTANT, "const", null, null, 0)
        val path = PropagationPath(listOf(step), PropagationSourceType.CONSTANT, 3)
        val results = listOf(makeResult(42, 0, propagationPath = path))
        cmd.argIndices = listOf(0)
        cmd.targetClass = "com.example.Client"
        cmd.targetMethod = "getOption"

        val output = cmd.formatText(results)
        assertTrue(output.contains("Max propagation depth: 3"))
    }

    @Test
    fun `formatText shows propagation path when showPath enabled`() {
        val step = PropagationStep(NodeId(1), PropagationNodeType.CONSTANT, "const 42", null, null, 0)
        val path = PropagationPath(listOf(step), PropagationSourceType.CONSTANT, 1)
        val results = listOf(makeResult(42, 0, propagationPath = path))
        cmd.argIndices = listOf(0)
        cmd.targetClass = "com.example.Client"
        cmd.targetMethod = "getOption"
        cmd.showPath = true

        val output = cmd.formatText(results)
        assertTrue(output.contains("Path:"))
    }

    @Test
    fun `formatText shows complex path count`() {
        val step = PropagationStep(NodeId(1), PropagationNodeType.CALL_SITE, "call", null, null, 0)
        val path = PropagationPath(listOf(step), PropagationSourceType.RETURN_VALUE, 1)
        val results = listOf(makeResult(42, 0, propagationPath = path))
        cmd.argIndices = listOf(0)
        cmd.targetClass = "com.example.Client"
        cmd.targetMethod = "getOption"

        val output = cmd.formatText(results)
        assertTrue(output.contains("Complex paths"))
    }

    // ========================================================================
    // formatJson
    // ========================================================================

    @Test
    fun `formatJson produces valid JSON`() {
        val results = listOf(makeResult(1001, 0))
        cmd.argIndices = listOf(0)
        cmd.targetClass = "com.example.Client"
        cmd.targetMethod = "getOption"

        val json = cmd.formatJson(results)
        val root = JsonParser.parseString(json).asJsonObject
        assertEquals("com.example.Client", root.get("targetClass").asString)
        assertEquals("getOption", root.get("targetMethod").asString)
        assertTrue(root.has("uniqueValues"))
        assertTrue(root.has("statistics"))
    }

    @Test
    fun `formatJson with empty results`() {
        cmd.argIndices = listOf(0)
        cmd.targetClass = "com.example.Client"
        cmd.targetMethod = "getOption"

        val json = cmd.formatJson(emptyList())
        val root = JsonParser.parseString(json).asJsonObject
        assertEquals(0, root.get("totalOccurrences").asInt)
    }

    @Test
    fun `formatJson with enum constant`() {
        val constant = EnumConstant(
            NodeId.next(), TypeDescriptor("com.example.Status"), "ACTIVE", listOf(1)
        )
        val results = listOf(makeResult(constant = constant, argIndex = 0))
        cmd.argIndices = listOf(0)
        cmd.targetClass = "com.example.Client"
        cmd.targetMethod = "getOption"

        val json = cmd.formatJson(results)
        val root = JsonParser.parseString(json).asJsonObject
        val uniqueValues = root.getAsJsonArray("uniqueValues")
        val entry = uniqueValues[0].asJsonObject
        assertEquals("com.example.Status", entry.get("enumType").asString)
        assertEquals("ACTIVE", entry.get("enumName").asString)
    }

    @Test
    fun `formatJson includes show-path data when enabled`() {
        val step = PropagationStep(NodeId(1), PropagationNodeType.CONSTANT, "const 42", null, null, 0)
        val path = PropagationPath(listOf(step), PropagationSourceType.CONSTANT, 1)
        val results = listOf(makeResult(42, 0, propagationPath = path))
        cmd.argIndices = listOf(0)
        cmd.targetClass = "com.example.Client"
        cmd.targetMethod = "getOption"
        cmd.showPath = true

        val json = cmd.formatJson(results)
        val root = JsonParser.parseString(json).asJsonObject
        val occ = root.getAsJsonArray("uniqueValues")[0].asJsonObject.getAsJsonArray("occurrences")[0].asJsonObject
        assertTrue(occ.has("propagationPath"))
    }

    @Test
    fun `formatJson statistics section`() {
        val step = PropagationStep(NodeId(1), PropagationNodeType.CALL_SITE, "call", null, null, 0)
        val path = PropagationPath(listOf(step), PropagationSourceType.RETURN_VALUE, 2)
        val results = listOf(makeResult(42, 0, propagationPath = path))
        cmd.argIndices = listOf(0)
        cmd.targetClass = "com.example.Client"
        cmd.targetMethod = "getOption"

        val json = cmd.formatJson(results)
        val root = JsonParser.parseString(json).asJsonObject
        val stats = root.getAsJsonObject("statistics")
        assertEquals(2, stats.get("maxPropagationDepth").asInt)
    }

    // ========================================================================
    // formatText - additional branch coverage
    // ========================================================================

    @Test
    fun `formatText with empty results`() {
        cmd.argIndices = listOf(0)
        cmd.targetClass = "com.example.Client"
        cmd.targetMethod = "getOption"

        val output = cmd.formatText(emptyList())
        assertTrue(output.contains("Found 0 argument constant(s)"))
        assertTrue(output.contains("Unique values: 0"))
    }

    @Test
    fun `formatText shows depth range when min differs from max`() {
        val step1 = PropagationStep(NodeId(1), PropagationNodeType.CONSTANT, "const", null, null, 0)
        val path1 = PropagationPath(listOf(step1), PropagationSourceType.CONSTANT, 1)
        val step2 = PropagationStep(NodeId(2), PropagationNodeType.CALL_SITE, "call", null, null, 0)
        val step3 = PropagationStep(NodeId(3), PropagationNodeType.CONSTANT, "const", null, null, 1)
        val path2 = PropagationPath(listOf(step2, step3), PropagationSourceType.CONSTANT, 5)

        val results = listOf(
            makeResult(42, 0, propagationPath = path1),
            makeResult(42, 0, propagationPath = path2)
        )
        cmd.argIndices = listOf(0)
        cmd.targetClass = "com.example.Client"
        cmd.targetMethod = "getOption"

        val output = cmd.formatText(results)
        assertTrue(output.contains("Propagation depth: 1-5"))
    }

    @Test
    fun `formatText shows verbose path details when showPath and verbose enabled`() {
        val step = PropagationStep(
            NodeId(1), PropagationNodeType.CONSTANT, "const 42",
            "Caller.main():10", DataFlowKind.ASSIGN, 0
        )
        val path = PropagationPath(listOf(step), PropagationSourceType.CONSTANT, 1)
        val results = listOf(makeResult(42, 0, propagationPath = path))
        cmd.argIndices = listOf(0)
        cmd.targetClass = "com.example.Client"
        cmd.targetMethod = "getOption"
        cmd.showPath = true
        cmd.verbose = true

        val output = cmd.formatText(results)
        assertTrue(output.contains("Path:"))
        assertTrue(output.contains("Source type: CONSTANT"))
        assertTrue(output.contains("1."))
    }

    @Test
    fun `formatText with no propagation depth hides depth line`() {
        // Result with depth 0 should not show propagation depth
        val results = listOf(makeResult(42, 0))
        cmd.argIndices = listOf(0)
        cmd.targetClass = "com.example.Client"
        cmd.targetMethod = "getOption"

        val output = cmd.formatText(results)
        assertFalse(output.contains("Propagation depth:"))
    }

    @Test
    fun `formatText with exactly 5 occurrences does not truncate`() {
        val results = (1..5).map { makeResult(1001, 0) }
        cmd.argIndices = listOf(0)
        cmd.targetClass = "com.example.Client"
        cmd.targetMethod = "getOption"

        val output = cmd.formatText(results)
        assertFalse(output.contains("... and"))
    }

    @Test
    fun `formatText with showPath but no propagation path`() {
        val results = listOf(makeResult(42, 0, propagationPath = null))
        cmd.argIndices = listOf(0)
        cmd.targetClass = "com.example.Client"
        cmd.targetMethod = "getOption"
        cmd.showPath = true

        val output = cmd.formatText(results)
        assertFalse(output.contains("Path:"))
    }

    @Test
    fun `formatText with zero complex paths hides complex line`() {
        val results = listOf(makeResult(42, 0))
        cmd.argIndices = listOf(0)
        cmd.targetClass = "com.example.Client"
        cmd.targetMethod = "getOption"

        val output = cmd.formatText(results)
        assertFalse(output.contains("Complex paths"))
    }

    // ========================================================================
    // formatJson - additional branch coverage
    // ========================================================================

    @Test
    fun `formatJson without showPath does not include propagationPath`() {
        val step = PropagationStep(NodeId(1), PropagationNodeType.CONSTANT, "const 42", null, null, 0)
        val path = PropagationPath(listOf(step), PropagationSourceType.CONSTANT, 1)
        val results = listOf(makeResult(42, 0, propagationPath = path))
        cmd.argIndices = listOf(0)
        cmd.targetClass = "com.example.Client"
        cmd.targetMethod = "getOption"
        cmd.showPath = false

        val json = cmd.formatJson(results)
        val root = JsonParser.parseString(json).asJsonObject
        val occ = root.getAsJsonArray("uniqueValues")[0].asJsonObject.getAsJsonArray("occurrences")[0].asJsonObject
        assertFalse(occ.has("propagationPath"))
    }

    @Test
    fun `formatJson with showPath but null propagationPath`() {
        val results = listOf(makeResult(42, 0, propagationPath = null))
        cmd.argIndices = listOf(0)
        cmd.targetClass = "com.example.Client"
        cmd.targetMethod = "getOption"
        cmd.showPath = true

        val json = cmd.formatJson(results)
        val root = JsonParser.parseString(json).asJsonObject
        val occ = root.getAsJsonArray("uniqueValues")[0].asJsonObject.getAsJsonArray("occurrences")[0].asJsonObject
        assertFalse(occ.has("propagationPath"))
    }

    @Test
    fun `formatJson includes involvesReturnValue and involvesFieldAccess`() {
        val step = PropagationStep(NodeId(1), PropagationNodeType.FIELD, "field", null, null, 0)
        val path = PropagationPath(listOf(step), PropagationSourceType.FIELD, 1)
        val results = listOf(makeResult(42, 0, propagationPath = path))
        cmd.argIndices = listOf(0)
        cmd.targetClass = "com.example.Client"
        cmd.targetMethod = "getOption"

        val json = cmd.formatJson(results)
        val root = JsonParser.parseString(json).asJsonObject
        val occ = root.getAsJsonArray("uniqueValues")[0].asJsonObject.getAsJsonArray("occurrences")[0].asJsonObject
        assertTrue(occ.has("involvesFieldAccess"))
    }

    @Test
    fun `formatJson with non-enum constant has value field`() {
        val results = listOf(makeResult(42, 0))
        cmd.argIndices = listOf(0)
        cmd.targetClass = "com.example.Client"
        cmd.targetMethod = "getOption"

        val json = cmd.formatJson(results)
        val root = JsonParser.parseString(json).asJsonObject
        val entry = root.getAsJsonArray("uniqueValues")[0].asJsonObject
        assertTrue(entry.has("value"))
        assertFalse(entry.has("enumType"))
    }

    @Test
    fun `formatJson depthRange for multiple occurrences`() {
        val step1 = PropagationStep(NodeId(1), PropagationNodeType.CONSTANT, "const", null, null, 0)
        val path1 = PropagationPath(listOf(step1), PropagationSourceType.CONSTANT, 2)
        val path2 = PropagationPath(listOf(step1), PropagationSourceType.CONSTANT, 5)
        val results = listOf(
            makeResult(42, 0, propagationPath = path1),
            makeResult(42, 0, propagationPath = path2)
        )
        cmd.argIndices = listOf(0)
        cmd.targetClass = "com.example.Client"
        cmd.targetMethod = "getOption"

        val json = cmd.formatJson(results)
        val root = JsonParser.parseString(json).asJsonObject
        val entry = root.getAsJsonArray("uniqueValues")[0].asJsonObject
        val depthRange = entry.getAsJsonObject("depthRange")
        assertEquals(2, depthRange.get("min").asInt)
        assertEquals(5, depthRange.get("max").asInt)
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun makeResult(
        value: Int = 0,
        argIndex: Int = 0,
        propagationPath: PropagationPath? = null
    ): ArgumentConstantResult {
        return makeResult(IntConstant(NodeId.next(), value), argIndex, propagationPath)
    }

    private fun makeResult(
        constant: ConstantNode,
        argIndex: Int = 0,
        propagationPath: PropagationPath? = null
    ): ArgumentConstantResult {
        val callerType = TypeDescriptor("com.example.Caller")
        val calleeType = TypeDescriptor("com.example.Client")
        val caller = MethodDescriptor(callerType, "main", emptyList(), TypeDescriptor("void"))
        val callee = MethodDescriptor(calleeType, "getOption", listOf(TypeDescriptor("int")), TypeDescriptor("boolean"))
        val callSite = CallSiteNode(NodeId.next(), caller, callee, 10, null, listOf(constant.id))

        return ArgumentConstantResult(
            callSite = callSite,
            argumentIndex = argIndex,
            constant = constant,
            path = DataFlowPath(listOf(constant.id, callSite.id)),
            propagationPath = propagationPath
        )
    }
}
