package io.johnsonlee.graphite.analysis

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class BranchFieldReferenceTest : BranchReachabilityAnalysisTestSupport() {
    @Test
    fun `findUnreferencedFields returns field with no external references`() {
        val classA = TypeDescriptor("com.example.A")
        val classB = TypeDescriptor("com.example.B")
        val field =
            FieldNode(
                id = NodeId.next(), descriptor = FieldDescriptor(classA, "name", TypeDescriptor("java.lang.String")), isStatic = false)

        val builder = DefaultGraph.Builder()
        builder.addNode(field)
        val graph = builder.build()

        val analysis = BranchReachabilityAnalysis(graph)
        val unreferenced = analysis.findUnreferencedFields()

        assertTrue(unreferenced.any { it.name == "name" })
    }

    @Test
    fun `findUnreferencedFields excludes field with external FIELD_LOAD`() {
        val classA = TypeDescriptor("com.example.A")
        val classB = TypeDescriptor("com.example.B")
        val field =
            FieldNode(
                id = NodeId.next(), descriptor = FieldDescriptor(classA, "name", TypeDescriptor("java.lang.String")), isStatic = false)
        val methodB = MethodDescriptor(classB, "readName", emptyList(), TypeDescriptor("java.lang.String"))
        val localB = LocalVariable(NodeId.next(), "local", TypeDescriptor("java.lang.String"), methodB)

        val builder = DefaultGraph.Builder()
        builder.addNode(field)
        builder.addNode(localB)
        builder.addEdge(DataFlowEdge(field.id, localB.id, DataFlowKind.FIELD_LOAD))
        val graph = builder.build()

        val analysis = BranchReachabilityAnalysis(graph)
        val unreferenced = analysis.findUnreferencedFields()

        assertTrue(unreferenced.none { it.name == "name" })
    }

    @Test
    fun `findUnreferencedFields excludes field with external FIELD_STORE`() {
        val classA = TypeDescriptor("com.example.A")
        val classB = TypeDescriptor("com.example.B")
        val field =
            FieldNode(
                id = NodeId.next(), descriptor = FieldDescriptor(classA, "name", TypeDescriptor("java.lang.String")), isStatic = false)
        val methodB = MethodDescriptor(classB, "writeName", emptyList(), TypeDescriptor("void"))
        val localB = LocalVariable(NodeId.next(), "value", TypeDescriptor("java.lang.String"), methodB)

        val builder = DefaultGraph.Builder()
        builder.addNode(field)
        builder.addNode(localB)
        builder.addEdge(DataFlowEdge(localB.id, field.id, DataFlowKind.FIELD_STORE))
        val graph = builder.build()

        val analysis = BranchReachabilityAnalysis(graph)
        val unreferenced = analysis.findUnreferencedFields()

        assertTrue(unreferenced.none { it.name == "name" })
    }

    @Test
    fun `findUnreferencedFields excludes field with external getter call`() {
        val classA = TypeDescriptor("com.example.A")
        val classB = TypeDescriptor("com.example.B")
        val field =
            FieldNode(
                id = NodeId.next(), descriptor = FieldDescriptor(classA, "name", TypeDescriptor("java.lang.String")), isStatic = false)
        val methodB = MethodDescriptor(classB, "doWork", emptyList(), TypeDescriptor("void"))
        val getterMethod = MethodDescriptor(classA, "getName", emptyList(), TypeDescriptor("java.lang.String"))
        val callSite = CallSiteNode(NodeId.next(), methodB, getterMethod, 10, null, emptyList())

        val builder = DefaultGraph.Builder()
        builder.addNode(field)
        builder.addNode(callSite)
        val graph = builder.build()

        val analysis = BranchReachabilityAnalysis(graph)
        val unreferenced = analysis.findUnreferencedFields()

        assertTrue(unreferenced.none { it.name == "name" })
    }

    @Test
    fun `findUnreferencedFields excludes field with external setter call`() {
        val classA = TypeDescriptor("com.example.A")
        val classB = TypeDescriptor("com.example.B")
        val field =
            FieldNode(
                id = NodeId.next(), descriptor = FieldDescriptor(classA, "name", TypeDescriptor("java.lang.String")), isStatic = false)
        val methodB = MethodDescriptor(classB, "doWork", emptyList(), TypeDescriptor("void"))
        val setterMethod = MethodDescriptor(classA, "setName", listOf(TypeDescriptor("java.lang.String")), TypeDescriptor("void"))
        val callSite = CallSiteNode(NodeId.next(), methodB, setterMethod, 10, null, emptyList())

        val builder = DefaultGraph.Builder()
        builder.addNode(field)
        builder.addNode(callSite)
        val graph = builder.build()

        val analysis = BranchReachabilityAnalysis(graph)
        val unreferenced = analysis.findUnreferencedFields()

        assertTrue(unreferenced.none { it.name == "name" })
    }

    @Test
    fun `findUnreferencedFields excludes field with external isXxx call`() {
        val classA = TypeDescriptor("com.example.A")
        val classB = TypeDescriptor("com.example.B")
        val field =
            FieldNode(id = NodeId.next(), descriptor = FieldDescriptor(classA, "active", TypeDescriptor("boolean")), isStatic = false)
        val methodB = MethodDescriptor(classB, "check", emptyList(), TypeDescriptor("void"))
        val isMethod = MethodDescriptor(classA, "isActive", emptyList(), TypeDescriptor("boolean"))
        val callSite = CallSiteNode(NodeId.next(), methodB, isMethod, 10, null, emptyList())

        val builder = DefaultGraph.Builder()
        builder.addNode(field)
        builder.addNode(callSite)
        val graph = builder.build()

        val analysis = BranchReachabilityAnalysis(graph)
        val unreferenced = analysis.findUnreferencedFields()

        assertTrue(unreferenced.none { it.name == "active" })
    }

    @Test
    fun `findUnreferencedFields excludes synthetic fields`() {
        val classA = TypeDescriptor("com.example.A")
        val syntheticField =
            FieldNode(
                id = NodeId.next(), descriptor = FieldDescriptor(classA, "\$VALUES", TypeDescriptor("java.lang.Object")), isStatic = true)
        val thisField =
            FieldNode(
                id = NodeId.next(), descriptor = FieldDescriptor(classA, "this\$0", TypeDescriptor("com.example.Outer")), isStatic = false)

        val builder = DefaultGraph.Builder()
        builder.addNode(syntheticField)
        builder.addNode(thisField)
        val graph = builder.build()

        val analysis = BranchReachabilityAnalysis(graph)
        val unreferenced = analysis.findUnreferencedFields()

        assertTrue(unreferenced.isEmpty())
    }

    @Test
    fun `findUnreferencedFields keeps field only referenced internally`() {
        val classA = TypeDescriptor("com.example.A")
        val field = FieldNode(id = NodeId.next(), descriptor = FieldDescriptor(classA, "count", TypeDescriptor("int")), isStatic = false)
        // Internal reference (same class)
        val methodA = MethodDescriptor(classA, "increment", emptyList(), TypeDescriptor("void"))
        val localA = LocalVariable(NodeId.next(), "c", TypeDescriptor("int"), methodA)

        val builder = DefaultGraph.Builder()
        builder.addNode(field)
        builder.addNode(localA)
        builder.addEdge(DataFlowEdge(field.id, localA.id, DataFlowKind.FIELD_LOAD))
        val graph = builder.build()

        val analysis = BranchReachabilityAnalysis(graph)
        val unreferenced = analysis.findUnreferencedFields()

        // Internal-only reference means unreferenced from outside
        assertTrue(unreferenced.any { it.name == "count" })
    }

    @Test
    fun `extractFieldNameFromAccessor handles get prefix`() {
        assertEquals("name", extractFieldNameFromAccessor("getName"))
        assertEquals("value", extractFieldNameFromAccessor("getValue"))
    }

    @Test
    fun `extractFieldNameFromAccessor handles set prefix`() {
        assertEquals("name", extractFieldNameFromAccessor("setName"))
    }

    @Test
    fun `extractFieldNameFromAccessor handles is prefix`() {
        assertEquals("active", extractFieldNameFromAccessor("isActive"))
    }

    @Test
    fun `extractFieldNameFromAccessor returns null for non-accessor`() {
        assertEquals(null, extractFieldNameFromAccessor("doWork"))
        assertEquals(null, extractFieldNameFromAccessor("get")) // too short
        assertEquals(null, extractFieldNameFromAccessor("set")) // too short
        assertEquals(null, extractFieldNameFromAccessor("is")) // too short
    }

    @Test
    fun `DeadCodeResult with unreferencedFields`() {
        val field = FieldDescriptor(TypeDescriptor("com.example.A"), "name", TypeDescriptor("java.lang.String"))
        val result =
            DeadCodeResult(
                deadBranches = emptyList(),
                deadMethods = emptySet(),
                deadCallSites = emptySet(),
                unreferencedMethods = emptySet(),
                unreferencedFields = setOf(field))
        assertEquals(1, result.unreferencedFields.size)
        assertEquals("name", result.unreferencedFields.first().name)
    }
}
