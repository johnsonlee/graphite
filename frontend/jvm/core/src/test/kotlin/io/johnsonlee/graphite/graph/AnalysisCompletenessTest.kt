package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.input.AnalysisIdentity
import io.johnsonlee.graphite.input.EmptyResourceAccessor
import java.io.Closeable
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalysisCompletenessTest {

    private fun method(className: String, name: String, vararg parameters: String, returns: String = "void") = MethodDescriptor(
        TypeDescriptor(className), name, parameters.map(::TypeDescriptor), TypeDescriptor(returns)
    )

    private val analysed = method("app.Service", "value", returns = "java.lang.String")
    private val truncated = method("app.Service", "huge")
    private val abstract = method("lib.Reader", "read", "java.lang.String", returns = "java.lang.String")
    private val oom = method("lib.Impl", "explode")
    private val unresolved = method("lib.Impl", "broken")
    private val excluded = method("other.Util", "helper")
    private val external = method("java.util.ResourceBundle", "getBundle", "java.lang.String", returns = "java.util.ResourceBundle")

    private val completeness = AnalysisCompleteness(
        scope = AnalysisIdentity(includePackages = listOf("app"), includeLibraries = true, dependencyPackages = listOf("lib.")),
        artifacts = mapOf(
            "app.jar" to ArtifactEvidence(ArtifactRole.APPLICATION),
            "lib.jar" to ArtifactEvidence(ArtifactRole.LIBRARY, multiRelease = true),
            "other.jar" to ArtifactEvidence(ArtifactRole.LIBRARY)
        ),
        classes = mapOf(
            "app.Service" to ClassEvidence("app.jar", AnalysisStatus.ANALYZED),
            "lib.Reader" to ClassEvidence("lib.jar", AnalysisStatus.ANALYZED),
            "lib.Impl" to ClassEvidence("lib.jar", AnalysisStatus.ANALYZED),
            "lib.Broken" to ClassEvidence("lib.jar", AnalysisStatus.RESOLUTION_FAILED),
            "other.Util" to ClassEvidence("other.jar", AnalysisStatus.EXCLUDED_BY_SCOPE)
        ),
        methods = mapOf(
            truncated.identity to MethodEvidence(AnalysisStatus.ANALYZED_WITHOUT_CONTROL_FLOW),
            abstract.identity to MethodEvidence(AnalysisStatus.ABSTRACT),
            oom.identity to MethodEvidence(AnalysisStatus.INCOMPLETE, "Java heap space"),
            unresolved.identity to MethodEvidence(AnalysisStatus.RESOLUTION_FAILED, "bad frame")
        ),
        duplicateClasses = mapOf("lib.Reader" to listOf("lib.jar", "other.jar"))
    )

    @Test
    fun `a method's status is its own record, else analysed when the graph holds it, else its class's`() {
        assertEquals(AnalysisStatus.ANALYZED, completeness.statusOf(analysed, inGraph = true))
        assertEquals(AnalysisStatus.ANALYZED_WITHOUT_CONTROL_FLOW, completeness.statusOf(truncated, inGraph = true))
        assertEquals(AnalysisStatus.ABSTRACT, completeness.statusOf(abstract, inGraph = true))
        assertEquals(AnalysisStatus.INCOMPLETE, completeness.statusOf(oom, inGraph = true), "its nodes exist, its body does not")
        assertEquals(AnalysisStatus.RESOLUTION_FAILED, completeness.statusOf(unresolved, inGraph = false))
        assertEquals(AnalysisStatus.EXCLUDED_BY_SCOPE, completeness.statusOf(excluded, inGraph = false))
        assertEquals(AnalysisStatus.EXTERNAL, completeness.statusOf(external, inGraph = false))
        assertEquals(
            AnalysisStatus.UNKNOWN_MEMBER,
            completeness.statusOf(method("app.Service", "missing"), inGraph = false),
            "the class was analysed and has no such method"
        )
        assertEquals(
            AnalysisStatus.RESOLUTION_FAILED,
            completeness.statusOf(method("lib.Broken", "any"), inGraph = false),
            "a class whose methods could not be resolved"
        )
        assertEquals(AnalysisStatus.EXTERNAL, completeness.classStatus("java.lang.String"))
        assertEquals(AnalysisStatus.EXCLUDED_BY_SCOPE, completeness.classStatus("other.Util"))
        assertEquals("Java heap space", completeness.methods.getValue(oom.identity).message)
        assertEquals(listOf("lib.jar", "other.jar"), completeness.duplicateClasses.getValue("lib.Reader"))
        assertTrue(completeness.artifacts.getValue("lib.jar").multiRelease)
    }

    @Test
    fun `only the analysed statuses promise a complete body`() {
        assertEquals(
            setOf(AnalysisStatus.ANALYZED, AnalysisStatus.ANALYZED_WITHOUT_CONTROL_FLOW),
            AnalysisStatus.entries.filter { it.bodyComplete }.toSet()
        )
    }

    @Test
    fun `a class or method record carries only the statuses that can apply to it`() {
        assertEquals(
            "a class is ANALYZED, EXCLUDED_BY_SCOPE, NOT_EXPANDED or RESOLUTION_FAILED, not ABSTRACT",
            assertFailsWith<IllegalArgumentException> { ClassEvidence("a.jar", AnalysisStatus.ABSTRACT) }.message
        )
        assertEquals(AnalysisStatus.NOT_EXPANDED, ClassEvidence("a.jar", AnalysisStatus.NOT_EXPANDED).status)
        assertEquals(
            "a method's own status is never NOT_EXPANDED",
            assertFailsWith<IllegalArgumentException> { MethodEvidence(AnalysisStatus.NOT_EXPANDED) }.message
        )
        assertFalse(AnalysisStatus.NOT_EXPANDED.bodyComplete)
        val gaps = completeness.copy(
            classes = completeness.classes + ("lib.Far" to ClassEvidence("lib.jar", AnalysisStatus.NOT_EXPANDED)),
            callSiteGaps = mapOf(7 to CallSiteGap.DISPATCH_UNRESOLVED, 9 to CallSiteGap.DISPATCH_SATURATED)
        )
        assertEquals(AnalysisStatus.NOT_EXPANDED, gaps.statusOf(method("lib.Far", "any"), inGraph = false), "reached, not read")
        assertEquals(CallSiteGap.DISPATCH_SATURATED, gaps.callSiteGaps.getValue(9))
        assertTrue(completeness.callSiteGaps.isEmpty())
        assertEquals(
            "a method's own status is never EXTERNAL",
            assertFailsWith<IllegalArgumentException> { MethodEvidence(AnalysisStatus.EXTERNAL) }.message
        )
        assertEquals(
            "a method's own status is never EXCLUDED_BY_SCOPE",
            assertFailsWith<IllegalArgumentException> { MethodEvidence(AnalysisStatus.EXCLUDED_BY_SCOPE) }.message
        )
        assertEquals(
            "a method's own status is never UNKNOWN_MEMBER",
            assertFailsWith<IllegalArgumentException> { MethodEvidence(AnalysisStatus.UNKNOWN_MEMBER) }.message
        )
        assertNull(MethodEvidence(AnalysisStatus.NATIVE).message)
        assertNull(ClassEvidence(null, AnalysisStatus.ANALYZED).origin)
    }

    @Test
    fun `a built graph answers a method's status from its index and the evidence it was given`() {
        for (mapped in listOf(false, true)) {
            val directory = Files.createTempDirectory("completeness-builder")
            try {
                val builder: FullGraphBuilder = if (mapped) MmapGraphBuilder(directory) else DefaultGraph.Builder()
                builder.addNode(IntConstant(NodeId(0), 42))
                builder.addMethod(analysed)
                builder.addMethod(oom)
                val plain = builder.build()
                try {
                    assertNull(plain.analysisCompleteness(), "a build that recorded nothing")
                    assertNull(plain.analysisStatus(analysed))
                    assertTrue(plain.hasMethod(analysed))
                    assertFalse(plain.hasMethod(external))
                } finally {
                    (plain as? Closeable)?.close()
                }
                val recording: FullGraphBuilder = if (mapped) MmapGraphBuilder(directory) else DefaultGraph.Builder()
                recording.addNode(IntConstant(NodeId(0), 42))
                recording.addMethod(analysed)
                recording.addMethod(oom)
                recording.setAnalysisCompleteness(completeness)
                val graph = recording.build()
                try {
                    assertEquals(completeness, graph.analysisCompleteness())
                    assertEquals(AnalysisStatus.ANALYZED, graph.analysisStatus(analysed))
                    assertEquals(AnalysisStatus.INCOMPLETE, graph.analysisStatus(oom))
                    assertEquals(AnalysisStatus.EXCLUDED_BY_SCOPE, graph.analysisStatus(excluded))
                    assertEquals(AnalysisStatus.EXTERNAL, graph.analysisStatus(external))
                    assertEquals(AnalysisStatus.UNKNOWN_MEMBER, graph.analysisStatus(method("app.Service", "missing")))
                } finally {
                    (graph as? Closeable)?.close()
                }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `a graph without a method index answers hasMethod by an exact scan`() {
        val graph = object : Graph {
            override fun node(id: NodeId) = null
            override fun <T : Node> nodes(type: Class<T>) = emptySequence<T>()
            override fun outgoing(id: NodeId) = emptySequence<Edge>()
            override fun incoming(id: NodeId) = emptySequence<Edge>()
            override fun <T : Edge> outgoing(id: NodeId, type: Class<T>) = emptySequence<T>()
            override fun <T : Edge> incoming(id: NodeId, type: Class<T>) = emptySequence<T>()
            override fun callSites(methodPattern: MethodPattern) = emptySequence<CallSiteNode>()
            override fun supertypes(type: TypeDescriptor) = emptySequence<TypeDescriptor>()
            override fun subtypes(type: TypeDescriptor) = emptySequence<TypeDescriptor>()
            override fun methods(pattern: MethodPattern) = sequenceOf(analysed, abstract).filter { pattern.matches(it) }
            override fun enumValues(enumClass: String, enumName: String) = null
            override fun memberAnnotations(className: String, memberName: String) = emptyMap<String, Map<String, Any?>>()
            override val resources = EmptyResourceAccessor
            override fun branchScopes() = emptySequence<BranchScope>()
            override fun branchScopesFor(conditionNodeId: NodeId) = emptySequence<BranchScope>()
        }
        assertTrue(graph.hasMethod(analysed))
        assertTrue(graph.hasMethod(abstract))
        assertFalse(graph.hasMethod(analysed.copy(returnType = TypeDescriptor("int"))), "the return type is part of the identity")
        assertFalse(graph.hasMethod(external))
        assertNull(graph.analysisCompleteness())
        assertNull(graph.analysisStatus(analysed))
    }
}
