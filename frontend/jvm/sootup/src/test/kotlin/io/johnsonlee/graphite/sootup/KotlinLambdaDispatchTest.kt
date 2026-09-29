package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.analysis.DataFlowAnalysis
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every shape a Kotlin function value compiles to must connect the call that invokes it to the
 * lambda body. The fixtures in `src/kotlinLambdaFixtures` are compiled twice, see
 * [KotlinIndyLambdaDispatchTest] and [KotlinClassLambdaDispatchTest], and the `indy` output is
 * desugared by D8, see [KotlinDesugaredLambdaDispatchTest]; each lambda body calls a
 * distinct `Sink` method, and each check starts from a call site fed by the invoking method's
 * own parameter, so it can only reach that `Sink` method through the resolved dispatch (never
 * through the call site that creates the lambda).
 */
abstract class KotlinLambdaDispatchTest(private val mode: String) {

    private val graph: Graph by lazy { load(mode) }

    private val callSites: List<CallSiteNode> by lazy { graph.nodes<CallSiteNode>().toList() }

    private val callSitesByCaller: Map<MethodDescriptor, List<CallSiteNode>> by lazy { callSites.groupBy { it.caller } }

    @Test
    fun `non-capturing lambda dispatches to its body`() = assertDispatch("NonCapturingLambda", "use", 0, "nonCapturing")

    @Test
    fun `capturing lambda dispatches to its body`() = assertDispatch("CapturingLambda", "use", 1, "capturing")

    @Test
    fun `lambda passed as a parameter dispatches to its body`() = assertDispatch("ParameterLambda", "call", 1, "parameter")

    @Test
    fun `lambda forwarded through another method dispatches to its body`() =
        assertDispatch("ForwardedLambda", "inner", 1, "forwarded")

    @Test
    fun `returned lambda dispatches to its body`() = assertDispatch("ReturnedLambda", "use", 0, "returned")

    @Test
    fun `lambda stored in a field dispatches to its body`() = assertDispatch("FieldLambda", "use", 0, "field")

    @Test
    fun `lambda injected through a constructor dispatches to its body`() =
        assertDispatch("InjectedLambda", "use", 0, "injected")

    @Test
    fun `lambda captured by another lambda dispatches to its body`() =
        assertDispatch("NestedLambda", "use", 0, "nestedInner")

    @Test
    fun `unbound callable reference dispatches to the referenced function`() =
        assertDispatch("FunctionReference", "call", 1, "reference")

    @Test
    fun `bound callable reference dispatches to the referenced function`() =
        assertDispatch("BoundReference", "call", 1, "boundReference")

    @Test
    fun `property reference dispatches to the property getter`() =
        assertDispatch("PropertyReference", "call", 1, "property")

    @Test
    fun `suspend lambda dispatches to its body`() = assertDispatch("SuspendLambda", "call", 1, "suspended")

    @Test
    fun `fun interface lambda dispatches to its body`() = assertDispatch("FunInterfaceLambda", "call", 1, "funInterface")

    @Test
    fun `Java SAM-converted lambda dispatches to its body`() = assertDispatch("JavaSamLambda", "call", 1, "javaSam")

    @Test
    fun `function value wrapped into a Java SAM dispatches to its body`() =
        assertDispatch("WrappedSamLambda", "call", 1, "wrappedSam")

    @Test
    fun `anonymous object dispatches to its override`() = assertDispatch("AnonymousObject", "call", 1, "anonymous")

    @Test
    fun `lambda with receiver dispatches to its body`() = assertDispatch("ReceiverLambda", "call", 1, "withReceiver")

    @Test
    fun `inline lambda body is part of the caller`() {
        assertTrue(reaches(callSitesOf("InlineLambda", "use"), "inlined"), "InlineLambda.use should call Sink.inlined")
    }

    @Test
    fun `lambda handed to code outside the graph stays reachable from its creator`() {
        assertTrue(
            reaches(callSitesOf("DeferredLambda", "use"), "deferred"),
            "[$mode] DeferredLambda.use should reach the lazy {} body. Call sites: ${describe("DeferredLambda", "use")}"
        )
    }

    @Test
    fun `captured values and call arguments reach the lambda body parameters`() {
        val sinkCall = callSites.single {
            it.callee.name == "dataFlow" && it.callee.declaringClass.className == "$PACKAGE.Sink"
        }
        val analysis = DataFlowAnalysis(graph)
        assertEquals(
            listOf("captured-constant"),
            analysis.backwardSlice(sinkCall.arguments[0]).constants().map { (it as StringConstant).value },
            "[$mode] the captured prefix should trace back to its constant"
        )
        assertEquals(
            listOf("argument-constant"),
            analysis.backwardSlice(sinkCall.arguments[1]).constants().map { (it as StringConstant).value },
            "[$mode] the lambda argument should trace back to its constant"
        )
    }

    private fun assertDispatch(className: String, methodName: String, parameterIndex: Int, marker: String) {
        val fromParameter = parameterLocals(className, methodName, parameterIndex)
        val invocations = callSitesOf(className, methodName).filter { site ->
            site.receiver in fromParameter || site.arguments.any { it in fromParameter }
        }
        assertTrue(invocations.isNotEmpty(), "[$mode] $className.$methodName should pass its parameter #$parameterIndex to a call")
        assertTrue(
            reaches(invocations, marker),
            "[$mode] $className.$methodName should dispatch to the lambda calling Sink.$marker. " +
                "Call sites: ${describe(className, methodName)}"
        )
    }

    /** Nodes holding the value of parameter [index] of the method: the parameter and the locals assigned from it. */
    private fun parameterLocals(className: String, methodName: String, index: Int): Set<NodeId> {
        val parameters = graph.nodes<ParameterNode>().filter {
            it.method.declaringClass.className == "$PACKAGE.$className" && it.method.name == methodName && it.index == index
        }.map { it.id }.toList()
        return (parameters + parameters.flatMap { param ->
            graph.outgoing(param, DataFlowEdge::class.java).filter { it.kind == DataFlowKind.ASSIGN }.map { it.to }.toList()
        }).toSet()
    }

    private fun callSitesOf(className: String, methodName: String): List<CallSiteNode> = callSites.filter {
        it.caller.declaringClass.className == "$PACKAGE.$className" && it.caller.name == methodName
    }

    /** Whether any of [from], or anything they call within a few hops, calls `Sink.[marker]`. */
    private fun reaches(from: List<CallSiteNode>, marker: String): Boolean {
        val visited = mutableSetOf<MethodDescriptor>()
        var frontier = from
        repeat(MAX_HOPS) {
            if (frontier.any { it.callee.name == marker && it.callee.declaringClass.className == "$PACKAGE.Sink" }) return true
            frontier = frontier.map { it.callee }.filter(visited::add).flatMap { callSitesByCaller[it].orEmpty() }
        }
        return false
    }

    private fun describe(className: String, methodName: String): List<String> =
        callSitesOf(className, methodName).map { "${it.callee.declaringClass.className}.${it.callee.name}" }

    private companion object {
        const val PACKAGE = "sample.kotlinlambda"
        // D8 adds a `$r8$lambda$` trampoline per lambda: a lambda calling a lambda is 7 hops deep
        const val MAX_HOPS = 10

        fun load(mode: String): Graph {
            val dir = System.getProperty("kotlin.lambda.$mode.fixtures")
                ?: error("kotlin.lambda.$mode.fixtures is not set; run through Gradle")
            return JavaProjectLoader(LoaderConfig(includePackages = listOf(PACKAGE), buildCallGraph = false)).load(Path.of(dir))
        }
    }
}

/** Kotlin 2.x default code generation: lambdas and SAM conversions through `invokedynamic`. */
class KotlinIndyLambdaDispatchTest : KotlinLambdaDispatchTest("indy")

/** Kotlin 1.x default code generation: a class per lambda, `INSTANCE` singletons, `$sam$` wrappers. */
class KotlinClassLambdaDispatchTest : KotlinLambdaDispatchTest("class")

/** The `indy` output desugared by D8 for Android: `Outer$$ExternalSyntheticLambda<n>` classes. */
class KotlinDesugaredLambdaDispatchTest : KotlinLambdaDispatchTest("desugared")
