package io.johnsonlee.graphite.analysis

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.FieldStructure
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeHierarchyConfig
import io.johnsonlee.graphite.core.TypeStructure
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class TypeHierarchyBoundaryTest : TypeHierarchyAnalysisTestSupport() {
    @Test
    fun `multiple constructor arguments assigned to one Object field retain both types`() {
        val caller = method("Example", "make")
        val first = LocalVariable(NodeId(1), "user", userType, caller)
        val second = LocalVariable(NodeId(2), "address", addressType, caller)
        val field = FieldNode(NodeId(3), FieldDescriptor(responseType, "data", objectType), false)
        val ctor = MethodDescriptor(responseType, "<init>", listOf(objectType, objectType), voidType)
        val call = CallSiteNode(NodeId(4), caller, ctor, null, null, listOf(first.id, second.id))
        val graph = DefaultGraph.Builder().addNode(first).addNode(second).addNode(field).addNode(call).build()
        val fields = TypeHierarchyAnalysis(graph).findConstructorAssignments(responseType, caller, 0)
        assertEquals(setOf(userType, addressType), fields.getValue("data").actualTypes.map { it.type }.toSet())
        assertEquals(objectType, fields.getValue("data").declaredType)
    }

    @Test
    fun `depth budget stops return and generic expansion without inventing types`() {
        val target = method("Example", "run", userType)
        val graph = DefaultGraph.Builder().addNode(ReturnNode(NodeId(1), target)).build()
        val analysis = TypeHierarchyAnalysis(graph, TypeHierarchyConfig(maxDepth = -1))
        assertTrue(analysis.analyzeMethod(target).returnStructures.isEmpty())
        assertTrue(analysis.analyzeGenericTypeArguments(userType, target, 0).isEmpty())
        val setter = CallSiteNode(NodeId(2), target, method("Example", "setMissing"), null, null, emptyList())
        assertEquals(objectType, analysis.inferDeclaredTypeFromSetter(setter))
        assertNull(analysis.findArgumentType(NodeId(99)))
    }

    @Test
    fun `reentrant method analysis falls back to its declared return structure`() {
        val target = method("Example", "run", userType)
        lateinit var analysis: TypeHierarchyAnalysis
        var reentrant: Set<TypeStructure>? = null
        val delegate = DefaultGraph.Builder().build()
        val graph = object : io.johnsonlee.graphite.graph.Graph by delegate {
            override fun <T : io.johnsonlee.graphite.core.Node> nodes(type: Class<T>): Sequence<T> {
                if (type == ReturnNode::class.java) reentrant = analysis.analyzeMethod(target).returnStructures
                return emptySequence()
            }
        }
        analysis = TypeHierarchyAnalysis(graph)
        assertTrue(analysis.analyzeMethod(target).returnStructures.isEmpty())
        assertEquals(setOf(TypeStructure.from(userType)), reentrant)
        assertTrue(analysis.analyzingMethods.isEmpty(), "outer analysis must release its recursion guard")
    }

    @Test
    fun `compatibility remains conservative outside java classes and Object`() {
        val analysis = TypeHierarchyAnalysis(DefaultGraph.Builder().build())
        assertTrue(analysis.isCompatibleType("Example", "Example"))
        assertTrue(analysis.isCompatibleType("Example", "java.lang.Object"))
        assertTrue(analysis.isCompatibleType("java.util.ArrayList", "java.util.List"))
        assertFalse(analysis.isCompatibleType("Example", "Other"))
        assertFalse(analysis.isCompatibleType("java.util.List", "Other"))
    }

    @Test
    fun `global field types remain present at the depth boundary without expansion`() {
        val analysis = TypeHierarchyAnalysis(buildResponseGraph().build(),
            TypeHierarchyConfig(maxDepth = 0, excludePackages = emptyList()))
        val fields = mutableMapOf<String, FieldStructure>()
        analysis.addGlobalFieldAssignments(responseType, fields, 0)
        assertEquals(setOf(TypeStructure.simple(userType.className)), fields.getValue("data").actualTypes)
        assertEquals(objectType, fields.getValue("data").declaredType)
    }

    @Test
    fun `getter discovery includes excluded collection types but not arbitrary excluded classes`() {
        val target = method(userType.className, "getItems", TypeDescriptor("java.util.List"))
        val excluded = method(userType.className, "getIgnored", TypeDescriptor("org.external.Secret"))
        val graph = DefaultGraph.Builder().addMethod(target).addMethod(excluded).build()
        val analysis = TypeHierarchyAnalysis(graph, TypeHierarchyConfig(includePackages = listOf("com.example")))
        val fields = mutableMapOf<String, FieldStructure>()
        analysis.addGetterBasedFields(userType, fields, 0)
        assertEquals(setOf("items"), fields.keys)
        assertEquals(target.returnType, fields.getValue("items").declaredType)
        assertTrue(fields.getValue("items").actualTypes.isEmpty())
    }
}
