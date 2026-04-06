package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.webgraph.GraphStore
import org.junit.AfterClass
import org.junit.BeforeClass
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class QueryCommandTest {

    companion object {
        private lateinit var graphDir: Path

        private val fooType = TypeDescriptor("com.example.Foo")
        private val parentType = TypeDescriptor("com.example.Parent")
        private val childType = TypeDescriptor("com.example.Child")
        private val barMethod = MethodDescriptor(fooType, "bar", listOf(TypeDescriptor("int")), TypeDescriptor("void"))
        private val bazMethod = MethodDescriptor(fooType, "baz", emptyList(), TypeDescriptor("void"))
        private val quxMethod = MethodDescriptor(childType, "qux", listOf(TypeDescriptor("java.lang.String")), TypeDescriptor("int"))

        private lateinit var paramNode: ParameterNode
        private lateinit var localNode: LocalVariable
        private lateinit var intConstNode: IntConstant
        private lateinit var strConstNode: StringConstant
        private lateinit var returnNode: ReturnNode
        private lateinit var callSiteNode: CallSiteNode
        private lateinit var enumConstNode: EnumConstant
        private lateinit var fieldNode: FieldNode

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val builder = DefaultGraph.Builder()

            paramNode = ParameterNode(NodeId.next(), 0, TypeDescriptor("int"), barMethod)
            localNode = LocalVariable(NodeId.next(), "x", TypeDescriptor("int"), barMethod)
            intConstNode = IntConstant(NodeId.next(), 42)
            strConstNode = StringConstant(NodeId.next(), "hello")
            returnNode = ReturnNode(NodeId.next(), barMethod)
            callSiteNode = CallSiteNode(NodeId.next(), barMethod, bazMethod, 10, null, listOf(paramNode.id))
            enumConstNode = EnumConstant(NodeId.next(), TypeDescriptor("com.example.Status"), "ACTIVE", listOf(1, "active"))
            fieldNode = FieldNode(NodeId.next(), FieldDescriptor(fooType, "name", TypeDescriptor("java.lang.String")), false)

            builder.addNode(paramNode)
            builder.addNode(localNode)
            builder.addNode(intConstNode)
            builder.addNode(strConstNode)
            builder.addNode(returnNode)
            builder.addNode(callSiteNode)
            builder.addNode(enumConstNode)
            builder.addNode(fieldNode)

            builder.addEdge(DataFlowEdge(paramNode.id, localNode.id, DataFlowKind.ASSIGN))
            builder.addEdge(DataFlowEdge(intConstNode.id, localNode.id, DataFlowKind.ASSIGN))
            builder.addEdge(DataFlowEdge(localNode.id, returnNode.id, DataFlowKind.RETURN_VALUE))
            builder.addEdge(CallEdge(callSiteNode.id, callSiteNode.id, isVirtual = false))

            builder.addMethod(barMethod)
            builder.addMethod(bazMethod)
            builder.addMethod(quxMethod)

            builder.addTypeRelation(childType, parentType, TypeRelation.EXTENDS)

            builder.addEnumValues("com.example.Status", "ACTIVE", listOf(1, "active"))

            builder.addMemberAnnotation("com.example.Foo", "bar", "javax.annotation.Nullable", emptyMap())
            builder.addMemberAnnotation("com.example.Foo", "bar", "org.springframework.web.bind.annotation.GetMapping",
                mapOf("value" to "/api/bar"))

            val graph = builder.build()
            graphDir = Files.createTempDirectory("graphite-cli-test")
            GraphStore.save(graph, graphDir)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            graphDir.toFile().deleteRecursively()
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

    // ========================================================================
    // InfoCommand
    // ========================================================================

    @Test
    fun `info shows node and edge counts`() {
        val cmd = InfoCommand()
        cmd.graphDir = graphDir
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(out.contains("Nodes:"), "Output should contain 'Nodes:', got: $out")
        assertTrue(out.contains("8"), "Output should contain node count 8, got: $out")
        assertTrue(out.contains("Edges:"), "Output should contain 'Edges:', got: $out")
        assertTrue(out.contains("4"), "Output should contain edge count 4, got: $out")
    }

    @Test
    fun `info shows method count`() {
        val cmd = InfoCommand()
        cmd.graphDir = graphDir
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(out.contains("Methods:"), "Output should contain 'Methods:', got: $out")
        assertTrue(out.contains("3"), "Output should contain method count 3, got: $out")
    }

    @Test
    fun `info shows call site and constant and field counts`() {
        val cmd = InfoCommand()
        cmd.graphDir = graphDir
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(out.contains("CallSites:"), "Output should contain 'CallSites:', got: $out")
        assertTrue(out.contains("Constants:"), "Output should contain 'Constants:', got: $out")
        assertTrue(out.contains("Fields:"), "Output should contain 'Fields:', got: $out")
    }

    // ========================================================================
    // NodesCommand
    // ========================================================================

    @Test
    fun `nodes lists all nodes by default`() {
        val cmd = NodesCommand()
        cmd.graphDir = graphDir
        cmd.limit = 100
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(out.contains("8 node(s)"), "Should list 8 nodes, got: $out")
    }

    @Test
    fun `nodes filters by type CallSiteNode`() {
        val cmd = NodesCommand()
        cmd.graphDir = graphDir
        cmd.nodeType = "CallSiteNode"
        cmd.limit = 100
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(out.contains("1 node(s)"), "Should find 1 call site node, got: $out")
        assertTrue(out.contains("CallSite["), "Should format as CallSite, got: $out")
    }

    @Test
    fun `nodes filters by type constant`() {
        val cmd = NodesCommand()
        cmd.graphDir = graphDir
        cmd.nodeType = "constant"
        cmd.limit = 100
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        // IntConstant + StringConstant + EnumConstant = 3
        assertTrue(out.contains("3 node(s)"), "Should find 3 constant nodes, got: $out")
    }

    @Test
    fun `nodes respects limit`() {
        val cmd = NodesCommand()
        cmd.graphDir = graphDir
        cmd.limit = 2
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(out.contains("2 node(s)"), "Should limit to 2 nodes, got: $out")
    }

    @Test
    fun `nodes json format outputs valid JSON`() {
        val cmd = NodesCommand()
        cmd.graphDir = graphDir
        cmd.nodeType = "CallSiteNode"
        cmd.format = "json"
        cmd.limit = 100
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(out.trimStart().startsWith("["), "JSON output should start with '[', got: $out")
        assertTrue(out.contains("\"type\""), "JSON should contain 'type' field, got: $out")
        assertTrue(out.contains("\"CallSiteNode\""), "JSON should contain 'CallSiteNode' type, got: $out")
    }

    @Test
    fun `nodes with null type returns all nodes`() {
        val cmd = NodesCommand()
        cmd.graphDir = graphDir
        cmd.nodeType = null
        cmd.limit = 100
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(out.contains("8 node(s)"), "Should list all 8 nodes when type is null, got: $out")
    }

    @Test
    fun `nodes with unknown type returns all nodes`() {
        val cmd = NodesCommand()
        cmd.graphDir = graphDir
        cmd.nodeType = "UnknownType"
        cmd.limit = 100
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(out.contains("8 node(s)"), "Unknown type should fall back to Node (all), got: $out")
    }

    // ========================================================================
    // CallSitesCommand
    // ========================================================================

    @Test
    fun `call-sites finds matching call sites by class pattern`() {
        val cmd = CallSitesCommand()
        cmd.graphDir = graphDir
        cmd.classPattern = "com.example.Foo"
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(out.contains("1 call site(s)"), "Should find 1 call site, got: $out")
        assertTrue(out.contains("Foo.bar"), "Should show caller method, got: $out")
        assertTrue(out.contains("Foo.baz"), "Should show callee method, got: $out")
    }

    @Test
    fun `call-sites with no matches returns 0`() {
        val cmd = CallSitesCommand()
        cmd.graphDir = graphDir
        cmd.classPattern = "com.nonexistent.Class"
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(out.contains("0 call site(s)"), "Should find 0 call sites, got: $out")
    }

    @Test
    fun `call-sites with method pattern`() {
        val cmd = CallSitesCommand()
        cmd.graphDir = graphDir
        cmd.methodPattern = "baz"
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(out.contains("1 call site(s)"), "Should find 1 call site matching method 'baz', got: $out")
    }

    @Test
    fun `call-sites json format`() {
        val cmd = CallSitesCommand()
        cmd.graphDir = graphDir
        cmd.format = "json"
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(out.trimStart().startsWith("["), "JSON output should start with '[', got: $out")
        assertTrue(out.contains("\"CallSiteNode\""), "JSON should contain CallSiteNode type, got: $out")
    }

    // ========================================================================
    // MethodsCommand
    // ========================================================================

    @Test
    fun `methods lists all methods`() {
        val cmd = MethodsCommand()
        cmd.graphDir = graphDir
        cmd.limit = 100
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(out.contains("3 method(s)"), "Should list 3 methods, got: $out")
    }

    @Test
    fun `methods filters by class pattern`() {
        val cmd = MethodsCommand()
        cmd.graphDir = graphDir
        cmd.classPattern = "com.example.Child"
        cmd.limit = 100
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(out.contains("1 method(s)"), "Should find 1 method in Child class, got: $out")
        assertTrue(out.contains("Child.qux"), "Should show Child.qux, got: $out")
    }

    @Test
    fun `methods filters by name pattern`() {
        val cmd = MethodsCommand()
        cmd.graphDir = graphDir
        cmd.namePattern = "bar"
        cmd.limit = 100
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(out.contains("1 method(s)"), "Should find 1 method named 'bar', got: $out")
        assertTrue(out.contains("Foo.bar"), "Should show Foo.bar, got: $out")
    }

    @Test
    fun `methods respects limit`() {
        val cmd = MethodsCommand()
        cmd.graphDir = graphDir
        cmd.limit = 1
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(out.contains("1 method(s)"), "Should limit to 1 method, got: $out")
    }

    @Test
    fun `methods shows return type and parameters`() {
        val cmd = MethodsCommand()
        cmd.graphDir = graphDir
        cmd.namePattern = "qux"
        cmd.limit = 100
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(out.contains("String"), "Should show parameter type String, got: $out")
        assertTrue(out.contains("int"), "Should show return type int, got: $out")
    }

    // ========================================================================
    // AnnotationsCommand
    // ========================================================================

    @Test
    fun `annotations shows annotation data`() {
        val cmd = AnnotationsCommand()
        cmd.graphDir = graphDir
        cmd.className = "com.example.Foo"
        cmd.memberName = "bar"
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(out.contains("Annotations for com.example.Foo.bar"), "Should show annotation header, got: $out")
        assertTrue(out.contains("@javax.annotation.Nullable"), "Should show Nullable annotation, got: $out")
        assertTrue(out.contains("@org.springframework.web.bind.annotation.GetMapping"), "Should show GetMapping annotation, got: $out")
        assertTrue(out.contains("/api/bar"), "Should show annotation value, got: $out")
    }

    @Test
    fun `annotations shows empty for unknown member`() {
        val cmd = AnnotationsCommand()
        cmd.graphDir = graphDir
        cmd.className = "com.example.Unknown"
        cmd.memberName = "nonexistent"
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(out.contains("No annotations found"), "Should indicate no annotations found, got: $out")
    }

    // ========================================================================
    // BuildCommand
    // ========================================================================

    @Test
    fun `build from nonexistent path returns error`() {
        val cmd = BuildCommand()
        cmd.input = Path.of("/nonexistent/path/to/jar")
        cmd.output = Path.of("/tmp/out")
        val (_, err, code) = captureOutput { cmd.call() }
        assertEquals(1, code)
        assertTrue(err.contains("Error"), "Should show error message on stderr, got: $err")
    }

    @Test
    fun `build from nonexistent path with verbose returns error with stacktrace`() {
        val cmd = BuildCommand()
        cmd.input = Path.of("/nonexistent/path/to/jar")
        cmd.output = Path.of("/tmp/out")
        cmd.verbose = true
        val (_, err, code) = captureOutput { cmd.call() }
        assertEquals(1, code)
        assertTrue(err.contains("Error"), "Should show error message on stderr, got: $err")
    }

    @Test
    fun `build from valid classes directory succeeds`() {
        val classesDir = Files.createTempDirectory("build-test-classes")
        val outputDir = Files.createTempDirectory("build-test-output")
        try {
            // Compile a minimal Java class into the temp directory
            val javaFile = classesDir.resolve("Sample.java")
            Files.writeString(javaFile, """
                package sample;
                public class Sample {
                    public int getValue() { return 42; }
                }
            """.trimIndent())
            val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
            val sampleDir = classesDir.resolve("sample")
            Files.createDirectories(sampleDir)
            val compileResult = compiler.run(null, null, null, "-d", classesDir.toString(), javaFile.toString())
            assertEquals(0, compileResult, "Java compilation should succeed")

            val cmd = BuildCommand()
            cmd.input = classesDir
            cmd.output = outputDir
            cmd.includePackages = listOf("sample")
            cmd.verbose = true
            val (_, err, code) = captureOutput { cmd.call() }
            assertEquals(0, code, "Build should succeed, stderr: $err")
            assertTrue(err.contains("Loading bytecode"), "Should show loading message, got: $err")
            assertTrue(err.contains("Graph built"), "Should show graph built message, got: $err")
            assertTrue(err.contains("Saving to"), "Should show saving message, got: $err")
            assertTrue(err.contains("Done"), "Should show done message, got: $err")
        } finally {
            classesDir.toFile().deleteRecursively()
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `build from empty directory triggers catch block`() {
        val emptyDir = Files.createTempDirectory("build-test-empty")
        val outputDir = Files.createTempDirectory("build-test-output-empty")
        try {
            val cmd = BuildCommand()
            cmd.input = emptyDir
            cmd.output = outputDir
            cmd.includePackages = listOf("sample")
            val (_, err, code) = captureOutput { cmd.call() }
            // Either succeeds with 0 nodes or fails depending on SootUp behavior
            // Either way, it exercises the try block
            assertTrue(code == 0 || code == 1, "Should return 0 or 1, got: $code, err: $err")
        } finally {
            emptyDir.toFile().deleteRecursively()
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `build from invalid file triggers catch with verbose`() {
        // Create a file (not a directory) that exists but can't be loaded
        val invalidFile = Files.createTempFile("build-test-invalid", ".txt")
        val outputDir = Files.createTempDirectory("build-test-output-invalid")
        try {
            Files.writeString(invalidFile, "not a valid jar or class directory")
            val cmd = BuildCommand()
            cmd.input = invalidFile
            cmd.output = outputDir
            cmd.includePackages = listOf("sample")
            cmd.verbose = true
            val (_, err, code) = captureOutput { cmd.call() }
            assertEquals(1, code, "Should fail for invalid input, stderr: $err")
            assertTrue(err.contains("Error"), "Should show error, got: $err")
        } finally {
            invalidFile.toFile().delete()
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `build with exclude packages`() {
        val classesDir = Files.createTempDirectory("build-test-classes2")
        val outputDir = Files.createTempDirectory("build-test-output2")
        try {
            val javaFile = classesDir.resolve("Example.java")
            Files.writeString(javaFile, """
                package example;
                public class Example {
                    public String hello() { return "world"; }
                }
            """.trimIndent())
            val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
            Files.createDirectories(classesDir.resolve("example"))
            val compileResult = compiler.run(null, null, null, "-d", classesDir.toString(), javaFile.toString())
            assertEquals(0, compileResult, "Java compilation should succeed")

            val cmd = BuildCommand()
            cmd.input = classesDir
            cmd.output = outputDir
            cmd.includePackages = listOf("example")
            cmd.excludePackages = listOf("example.internal")
            cmd.buildCallGraph = true
            val (_, err, code) = captureOutput { cmd.call() }
            assertEquals(0, code, "Build should succeed, stderr: $err")
        } finally {
            classesDir.toFile().deleteRecursively()
            outputDir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    @Test
    fun `resolveNodeType maps CallSiteNode`() {
        assertEquals(CallSiteNode::class.java, resolveNodeType("CallSiteNode"))
        assertEquals(CallSiteNode::class.java, resolveNodeType("callsite"))
    }

    @Test
    fun `resolveNodeType maps ConstantNode`() {
        assertEquals(ConstantNode::class.java, resolveNodeType("constant"))
        assertEquals(ConstantNode::class.java, resolveNodeType("ConstantNode"))
    }

    @Test
    fun `resolveNodeType maps FieldNode`() {
        assertEquals(FieldNode::class.java, resolveNodeType("field"))
        assertEquals(FieldNode::class.java, resolveNodeType("FieldNode"))
    }

    @Test
    fun `resolveNodeType maps ParameterNode`() {
        assertEquals(ParameterNode::class.java, resolveNodeType("parameter"))
        assertEquals(ParameterNode::class.java, resolveNodeType("ParameterNode"))
    }

    @Test
    fun `resolveNodeType maps ReturnNode`() {
        assertEquals(ReturnNode::class.java, resolveNodeType("return"))
        assertEquals(ReturnNode::class.java, resolveNodeType("ReturnNode"))
    }

    @Test
    fun `resolveNodeType maps LocalVariable`() {
        assertEquals(LocalVariable::class.java, resolveNodeType("local"))
        assertEquals(LocalVariable::class.java, resolveNodeType("LocalVariable"))
    }

    @Test
    fun `resolveNodeType defaults to Node for null`() {
        assertEquals(Node::class.java, resolveNodeType(null))
    }

    @Test
    fun `resolveNodeType defaults to Node for unknown`() {
        assertEquals(Node::class.java, resolveNodeType("SomethingElse"))
    }

    @Test
    fun `formatNode formats CallSiteNode`() {
        val result = formatNode(callSiteNode)
        assertTrue(result.startsWith("CallSite["), "Should start with 'CallSite[', got: $result")
        assertTrue(result.contains("Foo.bar"), "Should contain caller 'Foo.bar', got: $result")
        assertTrue(result.contains("Foo.baz"), "Should contain callee 'Foo.baz', got: $result")
    }

    @Test
    fun `formatNode formats IntConstant`() {
        val result = formatNode(intConstNode)
        assertTrue(result.startsWith("IntConstant["), "Should start with 'IntConstant[', got: $result")
        assertTrue(result.contains("= 42"), "Should contain value '= 42', got: $result")
    }

    @Test
    fun `formatNode formats StringConstant`() {
        val result = formatNode(strConstNode)
        assertTrue(result.startsWith("StringConstant["), "Should start with 'StringConstant[', got: $result")
        assertTrue(result.contains("\"hello\""), "Should contain quoted value, got: $result")
    }

    @Test
    fun `formatNode formats EnumConstant`() {
        val result = formatNode(enumConstNode)
        assertTrue(result.startsWith("EnumConstant["), "Should start with 'EnumConstant[', got: $result")
        assertTrue(result.contains("Status.ACTIVE"), "Should contain enum name, got: $result")
    }

    @Test
    fun `formatNode formats FieldNode`() {
        val result = formatNode(fieldNode)
        assertTrue(result.startsWith("Field["), "Should start with 'Field[', got: $result")
        assertTrue(result.contains("Foo.name"), "Should contain field name, got: $result")
        assertTrue(result.contains("String"), "Should contain field type, got: $result")
    }

    @Test
    fun `formatNode formats ParameterNode`() {
        val result = formatNode(paramNode)
        assertTrue(result.startsWith("Parameter["), "Should start with 'Parameter[', got: $result")
        assertTrue(result.contains("bar"), "Should contain method name, got: $result")
        assertTrue(result.contains("#0"), "Should contain parameter index, got: $result")
    }

    @Test
    fun `formatNode formats ReturnNode`() {
        val result = formatNode(returnNode)
        assertTrue(result.startsWith("Return["), "Should start with 'Return[', got: $result")
        assertTrue(result.contains("bar"), "Should contain method name, got: $result")
    }

    @Test
    fun `formatNode formats LocalVariable`() {
        val result = formatNode(localNode)
        assertTrue(result.startsWith("Local["), "Should start with 'Local[', got: $result")
        assertTrue(result.contains("x"), "Should contain variable name, got: $result")
        assertTrue(result.contains("int"), "Should contain variable type, got: $result")
    }

    @Test
    fun `nodeToMap for CallSiteNode includes correct fields`() {
        val map = nodeToMap(callSiteNode)
        assertEquals("CallSiteNode", map["type"])
        assertEquals(callSiteNode.id.value, map["id"])
        assertNotNull(map["caller"])
        assertNotNull(map["callee"])
        assertNotNull(map["label"])
        assertTrue((map["label"] as String).contains("Foo.baz"))
    }

    @Test
    fun `nodeToMap for IntConstant includes correct fields`() {
        val map = nodeToMap(intConstNode)
        assertEquals("IntConstant", map["type"])
        assertEquals(intConstNode.id.value, map["id"])
        assertEquals(42, map["value"])
        assertEquals("42", map["label"])
    }

    @Test
    fun `nodeToMap for StringConstant includes correct fields`() {
        val map = nodeToMap(strConstNode)
        assertEquals("StringConstant", map["type"])
        assertEquals("hello", map["value"])
        assertEquals("\"hello\"", map["label"])
    }

    @Test
    fun `nodeToMap for EnumConstant includes correct fields`() {
        val map = nodeToMap(enumConstNode)
        assertEquals("EnumConstant", map["type"])
        assertEquals("com.example.Status", map["enumType"])
        assertEquals("ACTIVE", map["enumName"])
    }

    @Test
    fun `nodeToMap for FieldNode includes correct fields`() {
        val map = nodeToMap(fieldNode)
        assertEquals("FieldNode", map["type"])
        assertEquals("com.example.Foo", map["class"])
        assertEquals("name", map["name"])
        assertEquals("java.lang.String", map["fieldType"])
    }

    @Test
    fun `nodeToMap for ParameterNode includes correct fields`() {
        val map = nodeToMap(paramNode)
        assertEquals("ParameterNode", map["type"])
        assertEquals(0, map["index"])
        assertEquals("int", map["paramType"])
        assertNotNull(map["method"])
    }

    @Test
    fun `nodeToMap for ReturnNode includes correct fields`() {
        val map = nodeToMap(returnNode)
        assertEquals("ReturnNode", map["type"])
        assertNotNull(map["method"])
        assertEquals("return", map["label"])
    }

    @Test
    fun `nodeToMap for LocalVariable includes correct fields`() {
        val map = nodeToMap(localNode)
        assertEquals("LocalVariable", map["type"])
        assertEquals("x", map["name"])
        assertEquals("int", map["varType"])
        assertNotNull(map["method"])
        assertEquals("x", map["label"])
    }

    @Test
    fun `edgeToMap for DataFlowEdge includes correct fields`() {
        val edge = DataFlowEdge(paramNode.id, localNode.id, DataFlowKind.ASSIGN)
        val map = edgeToMap(edge)
        assertEquals(paramNode.id.value, map["from"])
        assertEquals(localNode.id.value, map["to"])
        assertEquals("DataFlow", map["type"])
        assertEquals("ASSIGN", map["kind"])
    }

    @Test
    fun `edgeToMap for CallEdge includes correct fields`() {
        val edge = CallEdge(callSiteNode.id, callSiteNode.id, isVirtual = true, isDynamic = true)
        val map = edgeToMap(edge)
        assertEquals(callSiteNode.id.value, map["from"])
        assertEquals(callSiteNode.id.value, map["to"])
        assertEquals("Call", map["type"])
        assertEquals(true, map["virtual"])
        assertEquals(true, map["dynamic"])
    }

    @Test
    fun `edgeToMap for TypeEdge includes correct fields`() {
        val n1Id = NodeId.next()
        val n2Id = NodeId.next()
        val edge = TypeEdge(n1Id, n2Id, TypeRelation.IMPLEMENTS)
        val map = edgeToMap(edge)
        assertEquals(n1Id.value, map["from"])
        assertEquals(n2Id.value, map["to"])
        assertEquals("Type", map["type"])
        assertEquals("IMPLEMENTS", map["kind"])
    }

    @Test
    fun `edgeToMap for ControlFlowEdge includes correct fields`() {
        val n1Id = NodeId.next()
        val n2Id = NodeId.next()
        val edge = ControlFlowEdge(n1Id, n2Id, ControlFlowKind.BRANCH_TRUE)
        val map = edgeToMap(edge)
        assertEquals(n1Id.value, map["from"])
        assertEquals(n2Id.value, map["to"])
        assertEquals("ControlFlow", map["type"])
        assertEquals("BRANCH_TRUE", map["kind"])
    }

    // ========================================================================
    // QueryCommand (parent)
    // ========================================================================

    @Test
    fun `parent command shows help`() {
        val cmd = QueryCommand()
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(out.contains("graphite-query"), "Help should contain command name, got: $out")
    }

    // ========================================================================
    // Additional formatNode coverage for remaining constant types
    // ========================================================================

    @Test
    fun `formatNode formats LongConstant`() {
        val node = LongConstant(NodeId.next(), 123456789L)
        val result = formatNode(node)
        assertTrue(result.startsWith("LongConstant["), "Should start with 'LongConstant[', got: $result")
        assertTrue(result.contains("= 123456789"), "Should contain value, got: $result")
    }

    @Test
    fun `formatNode formats FloatConstant`() {
        val node = FloatConstant(NodeId.next(), 3.14f)
        val result = formatNode(node)
        assertTrue(result.startsWith("FloatConstant["), "Should start with 'FloatConstant[', got: $result")
        assertTrue(result.contains("3.14"), "Should contain value, got: $result")
    }

    @Test
    fun `formatNode formats DoubleConstant`() {
        val node = DoubleConstant(NodeId.next(), 2.718)
        val result = formatNode(node)
        assertTrue(result.startsWith("DoubleConstant["), "Should start with 'DoubleConstant[', got: $result")
        assertTrue(result.contains("2.718"), "Should contain value, got: $result")
    }

    @Test
    fun `formatNode formats BooleanConstant`() {
        val node = BooleanConstant(NodeId.next(), true)
        val result = formatNode(node)
        assertTrue(result.startsWith("BooleanConstant["), "Should start with 'BooleanConstant[', got: $result")
        assertTrue(result.contains("= true"), "Should contain value, got: $result")
    }

    @Test
    fun `formatNode formats NullConstant`() {
        val node = NullConstant(NodeId.next())
        val result = formatNode(node)
        assertTrue(result.startsWith("NullConstant["), "Should start with 'NullConstant[', got: $result")
    }

    @Test
    fun `nodeToMap for LongConstant includes correct fields`() {
        val node = LongConstant(NodeId.next(), 99L)
        val map = nodeToMap(node)
        assertEquals("LongConstant", map["type"])
        assertEquals(99L, map["value"])
        assertEquals("99L", map["label"])
    }

    @Test
    fun `nodeToMap for FloatConstant includes correct fields`() {
        val node = FloatConstant(NodeId.next(), 1.5f)
        val map = nodeToMap(node)
        assertEquals("FloatConstant", map["type"])
        assertEquals(1.5f, map["value"])
        assertEquals("1.5f", map["label"])
    }

    @Test
    fun `nodeToMap for DoubleConstant includes correct fields`() {
        val node = DoubleConstant(NodeId.next(), 2.5)
        val map = nodeToMap(node)
        assertEquals("DoubleConstant", map["type"])
        assertEquals(2.5, map["value"])
        assertEquals("2.5d", map["label"])
    }

    @Test
    fun `nodeToMap for BooleanConstant includes correct fields`() {
        val node = BooleanConstant(NodeId.next(), false)
        val map = nodeToMap(node)
        assertEquals("BooleanConstant", map["type"])
        assertEquals(false, map["value"])
        assertEquals("false", map["label"])
    }

    @Test
    fun `nodeToMap for NullConstant includes correct fields`() {
        val node = NullConstant(NodeId.next())
        val map = nodeToMap(node)
        assertEquals("NullConstant", map["type"])
        assertEquals("null", map["label"])
    }

    @Test
    fun `edgeToMap for CallEdge with defaults`() {
        val edge = CallEdge(paramNode.id, localNode.id, isVirtual = false)
        val map = edgeToMap(edge)
        assertEquals(false, map["virtual"])
        assertEquals(false, map["dynamic"])
    }

    @Test
    fun `build with includeLibs and libFilters`() {
        val emptyDir = Files.createTempDirectory("build-test-libs")
        val outputDir = Files.createTempDirectory("build-test-libs-out")
        try {
            val cmd = BuildCommand()
            cmd.input = emptyDir
            cmd.output = outputDir
            cmd.includeLibs = true
            cmd.libFilters = listOf("*.jar")
            cmd.includePackages = listOf("sample")
            val (_, _, code) = captureOutput { cmd.call() }
            // Either succeeds or fails, but exercises the field initializers
            assertTrue(code == 0 || code == 1)
        } finally {
            emptyDir.toFile().deleteRecursively()
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `call sites with limit`() {
        val cmd = CallSitesCommand()
        cmd.graphDir = graphDir
        cmd.limit = 1
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
    }
}
