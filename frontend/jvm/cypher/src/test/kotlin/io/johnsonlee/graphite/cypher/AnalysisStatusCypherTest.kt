package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.AnalysisCompleteness
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.CallSiteGap
import io.johnsonlee.graphite.graph.ClassEvidence
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.MethodEvidence
import io.johnsonlee.graphite.input.AnalysisIdentity
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/** `analysisStatus(x)` and `dispatchGap(c)`: an absent edge told apart from a body the build never read. */
class AnalysisStatusCypherTest {
    private val void = TypeDescriptor("void")
    private val main = TypeDescriptor("app.Main")
    private val run = MethodDescriptor(main, "run", emptyList(), void)
    private val helper = MethodDescriptor(main, "helper", emptyList(), void)
    private val big = MethodDescriptor(main, "big", emptyList(), void)
    private val missing = MethodDescriptor(main, "missing", emptyList(), void)
    private val trim = MethodDescriptor(TypeDescriptor("lib.Util"), "trim", emptyList(), void)
    private val length = MethodDescriptor(TypeDescriptor("java.lang.String"), "length", emptyList(), TypeDescriptor("int"))
    private val calls =
        listOf(helper, big, missing, trim, length).mapIndexed { i, callee ->
            CallSiteNode(NodeId(i + 1), run, callee, i, null, emptyList())
        }

    private val evidence = AnalysisCompleteness(
        scope = AnalysisIdentity(includePackages = listOf("app.")),
        artifacts = emptyMap(),
        classes = mapOf(
            "app.Main" to ClassEvidence("app.jar", AnalysisStatus.ANALYZED),
            "lib.Util" to ClassEvidence("lib.jar", AnalysisStatus.EXCLUDED_BY_SCOPE)),
        methods = mapOf(big.identity to MethodEvidence(AnalysisStatus.ANALYZED_WITHOUT_CONTROL_FLOW)),
        callSiteGaps = mapOf(5 to CallSiteGap.DISPATCH_UNRESOLVED))

    private fun executor(withEvidence: Boolean) = CypherExecutor(DefaultGraph.Builder().apply {
        listOf(run, helper, big).forEach(::addMethod)
        calls.forEach(::addNode)
        if (withEvidence) setAnalysisCompleteness(evidence)
    }.build())

    private fun rows(executor: CypherExecutor, query: String): List<List<Any?>> = executor.execute(query).rows.map { it.values.toList() }

    @Test
    fun `a callee's status says whether its absence of calls is a fact or unrecorded`() {
        assertEquals(
            listOf(
                listOf("helper", "ANALYZED", null),
                listOf("big", "ANALYZED_WITHOUT_CONTROL_FLOW", null),
                listOf("missing", "UNKNOWN_MEMBER", null),
                listOf("trim", "EXCLUDED_BY_SCOPE", null),
                listOf("length", "EXTERNAL", "DISPATCH_UNRESOLVED")),
            rows(executor(true), "MATCH (c:CallSite) RETURN c.callee_name, analysisStatus(c), dispatchGap(c) ORDER BY c.id"))
        assertEquals(
            listOf(listOf("big", "ANALYZED_WITHOUT_CONTROL_FLOW"), listOf("helper", "ANALYZED"), listOf("run", "ANALYZED")),
            rows(executor(true), "MATCH (m:Method) RETURN m.name AS n, analysisStatus(m) ORDER BY n"))
        assertEquals(listOf(listOf(null, null)), rows(executor(true), "RETURN analysisStatus(1), dispatchGap('x')"))
    }

    @Test
    fun `a graph without evidence answers null rather than a status it cannot know`() {
        assertEquals(
            List(5) { listOf(null, null) },
            rows(executor(false), "MATCH (c:CallSite) RETURN analysisStatus(c), dispatchGap(c)"))
        assertNull(CypherFunctions.call("analysisStatus", listOf(calls.first())), "no graph in scope")
        assertNull(CypherFunctions.call("dispatchGap", listOf(calls.first())))
    }
}
