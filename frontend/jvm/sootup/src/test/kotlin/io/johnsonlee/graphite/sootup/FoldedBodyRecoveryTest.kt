package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ConstantNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.graph.AnalysisCompleteness
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.ArtifactEvidence
import io.johnsonlee.graphite.graph.ArtifactRole
import io.johnsonlee.graphite.graph.ClassEvidence
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodEvidence
import io.johnsonlee.graphite.graph.analysisStatus
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.AnalysisIdentity
import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.FoldPlan
import io.johnsonlee.graphite.input.FoldReport
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.testfixture.minimalAndroidSdk
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import sootup.core.interceptor.BodyInterceptor
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.constant.Constant
import sootup.core.jimple.common.constant.IntConstant as JimpleIntConstant
import sootup.core.jimple.common.constant.StringConstant as JimpleStringConstant
import sootup.core.jimple.common.stmt.InvokableStmt
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.interceptors.LocalSplitter

class FoldedBodyRecoveryTest : FoldedBodyObservationFixture() {
    @Test
    fun `observed APK recovery retains the analyzed call and reports the discarded fold`() = withAndroidFixture { apk, sdk ->
        val rule = ConstantFold.parse(mapOf(
            "match" to mapOf("CallSite" to mapOf(
                "caller_class" to OWNER, "caller_name" to "staticArguments", "callee_name" to "sink"
            )),
            "value" to "folded"
        ))
        val reports = mutableListOf<FoldReport>()
        val loader = loader(sdk, FoldPlan(listOf(rule), reports::add))
        var factoryCalls = 0
        var foldedBeforeFailure = false
        loader.apkInterceptorFactory = { chain ->
            val primary = factoryCalls++ == 0
            chain + BodyInterceptor { builder, _ ->
                if (primary && builder.methodSignature.name == "staticArguments") {
                    foldedBeforeFailure = builder.stmts.filterIsInstance<InvokableStmt>()
                        .none { it.invokeExpr.orElse(null)?.methodSignature?.name == "sink" }
                    builder.locals.clear()
                    error("injected failure after folding")
                }
            }
        }
        val delivered = mutableListOf<FoldedMethodObservation>()
        val result = loader.loadObserved(apk, delivered::add)
        val observation = delivered.single { it.evidence.method.identity == METHOD }
        assertTrue(foldedBeforeFailure, "the failed primary chain must have actually applied the fold")
        assertRetainedCall(result, observation)
        val recovery = assertNotNull(observation.evidence.recovery)
        assertEquals("split dex defaults without constant folding", recovery.chain)
        assertTrue(recovery.reason.endsWith("failed: injected failure after folding"))
        val folding = assertNotNull(observation.evidence.folding)
        assertTrue(folding.foldedCalls.isEmpty(), "discarded replacements must not remain successful")
        assertNull(folding.statementsBefore)
        assertNull(folding.statementsAfter)
        val reason = folding.unsupportedReasons.getValue(0).single()
        assertTrue(reason.contains("injected failure after folding"))
        val report = assertNotNull(result.foldReport)
        assertEquals(0, report.outcomes.single().matched)
        assertTrue(report.methods.isEmpty())
        assertEquals(reason, report.outcomes.single().unsupported.single().reason)
        assertSame(observation.evidence, result.observations.getValue(METHOD))
        factoryCalls = 0
        val unobserved = loader.load(apk)
        assertSameAnalysis(unobserved, result)
        assertEquals(listOf(report, report), reports, "enabling observation must not change recovery or fold accounting")
    }

    @Test
    fun `recovery without folding is explicit and does not leak into the next observed load`() = withAndroidFixture { apk, sdk ->
        val loader = loader(sdk)
        loader.apkInterceptorFactory = { chain ->
            chain + BodyInterceptor { builder, _ ->
                if (builder.methodSignature.name == "staticArguments" && chain.any { it is LocalSplitter }) {
                    builder.locals.clear()
                    error("injected splitter failure")
                }
            }
        }
        val delivered = mutableListOf<FoldedMethodObservation>()
        val recovered = loader.loadObserved(apk, delivered::add)
        val observation = delivered.single { it.evidence.method.identity == METHOD }
        assertRetainedCall(recovered, observation)
        val recovery = assertNotNull(observation.evidence.recovery)
        assertEquals("dex defaults without splitting or constant folding", recovery.chain)
        assertTrue(recovery.reason.endsWith("failed: injected splitter failure"))
        assertNull(observation.evidence.folding)
        assertNull(recovered.foldReport)
        assertSameAnalysis(loader.load(apk), recovered)

        loader.apkInterceptorFactory = { it }
        val next = mutableListOf<FoldedMethodObservation>()
        val successful = loader.loadObserved(apk, next::add)
        val successfulObservation = next.single { it.evidence.method.identity == METHOD }
        assertRetainedCall(successful, successfulObservation)
        assertNull(successfulObservation.evidence.recovery, "recovery belongs to its own load only")
        assertEquals(0, loader.bodyInterceptionFallbackCount)
        assertNotNull(recovered.observations.getValue(METHOD).recovery, "a later load must not mutate earlier receipts")
        assertSameAnalysis(loader.load(apk), successful)
    }

    @Test
    fun `final reconciliation reports an identifiable failed member and preserves an opaque failed class`() {
        val identity = "lib.Reader.read(I)Ljava/lang/String;"
        val completeness = AnalysisCompleteness(
            AnalysisIdentity(dependencyPackages = listOf("lib.")),
            mapOf("lib.jar" to ArtifactEvidence(ArtifactRole.LIBRARY)),
            mapOf(
                "lib.Reader" to ClassEvidence("lib.jar", AnalysisStatus.ANALYZED),
                "lib.Broken" to ClassEvidence("lib.jar", AnalysisStatus.RESOLUTION_FAILED)
            ),
            mapOf(identity to MethodEvidence(AnalysisStatus.RESOLUTION_FAILED, "malformed method metadata"))
        )
        val graph = DefaultGraph.Builder().setAnalysisCompleteness(completeness).build()
        val delivered = mutableListOf<FoldedMethodObservation>()
        val result = FoldedBodyDelivery(delivered::add).finish(graph, null)
        val observation = delivered.single()
        val evidence = observation.evidence
        assertEquals(JvmMethodIdentity("lib.Reader", "read", "(I)Ljava/lang/String;"), evidence.method)
        assertEquals(AnalysisStatus.RESOLUTION_FAILED, evidence.status)
        assertEquals("malformed method metadata", evidence.message)
        assertEquals("lib.jar", evidence.origin)
        assertEquals(ArtifactRole.LIBRARY, evidence.role)
        assertNull(evidence.modifiers)
        assertNull(evidence.application)
        assertNull(observation.snapshot, "missing resolution must never turn into a successful empty body")
        assertEquals(setOf(identity), result.observations.keys)
        assertEquals(AnalysisStatus.RESOLUTION_FAILED, result.completeness.classStatus("lib.Broken"))
        assertEquals(AnalysisStatus.EXTERNAL, result.completeness.classStatus("outside.Unknown"))
        assertSame(completeness, result.completeness)
    }

    private fun assertRetainedCall(result: ObservedJavaLoad, observation: FoldedMethodObservation) {
        val body = assertNotNull(observation.snapshot).body
        val invoke = body.stmts.filterIsInstance<InvokableStmt>()
            .mapNotNull { it.invokeExpr.orElse(null) }.single { it.methodSignature.name == "sink" }
        assertEquals(OWNER, invoke.methodSignature.declClassType.fullyQualifiedName)
        assertEquals("java.lang.String", invoke.methodSignature.type.toString())
        assertEquals(listOf("java.lang.String", "int"), invoke.methodSignature.parameterTypes.map { it.toString() })
        // DEX defaults may preserve argument locals. Follow their actual assignments up to
        // this call rather than require constant propagation that graph extraction does itself.
        val constants = mutableMapOf<Local, Constant>()
        var arguments: List<Value>? = null
        for (stmt in body.stmts) {
            if ((stmt as? InvokableStmt)?.invokeExpr?.orElse(null) === invoke) {
                arguments = invoke.args.map { (it as? Local)?.let(constants::get) ?: it }
                break
            }
            if (stmt is JAssignStmt && stmt.leftOp is Local) {
                val target = stmt.leftOp as Local
                val constant = (stmt.rightOp as? Constant) ?: (stmt.rightOp as? Local)?.let(constants::get)
                if (constant == null) constants.remove(target) else constants[target] = constant
            }
        }
        val values = assertNotNull(arguments)
        assertEquals(2, values.size)
        assertEquals("android", assertIs<JimpleStringConstant>(values[0]).value)
        assertEquals(7, assertIs<JimpleIntConstant>(values[1]).value)
        val call = result.graph.nodes<CallSiteNode>().single { it.caller.identity == METHOD && it.callee.name == "sink" }
        assertEquals("android", assertIs<StringConstant>(argumentConstants(result.graph, call.arguments[0]).single()).value)
        assertEquals(7, assertIs<IntConstant>(argumentConstants(result.graph, call.arguments[1]).single()).value)
        assertEquals(observation.evidence.method.identity, call.caller.identity)
        assertEquals(AnalysisStatus.ANALYZED, observation.evidence.status)
        assertEquals(AnalysisStatus.ANALYZED, result.graph.analysisStatus(call.caller))
        assertEquals(apkName(), observation.evidence.origin)
        assertEquals(ArtifactRole.APPLICATION, observation.evidence.role)
        assertEquals(true, observation.evidence.application)
        assertEquals(observation.evidence.origin, result.completeness.classes.getValue(OWNER).origin)
        assertEquals(observation.evidence.role, result.completeness.artifacts.getValue(apkName()).role)
    }

    /** Follow the graph's actual assignment producers of this call's argument, including local copies. */
    private fun argumentConstants(graph: Graph, argument: NodeId): Set<ConstantNode> {
        val constants = linkedSetOf<ConstantNode>()
        val visited = mutableSetOf<NodeId>()
        val pending = ArrayDeque<NodeId>()
        pending.add(argument)
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (!visited.add(id)) continue
            val node = graph.node(id)
            if (node is ConstantNode) {
                constants.add(node)
            } else {
                graph.incoming(id, DataFlowEdge::class.java)
                    .filter { it.kind == DataFlowKind.ASSIGN }.forEach { pending.add(it.from) }
            }
        }
        return constants
    }

    private fun assertSameAnalysis(unobserved: Graph, observed: ObservedJavaLoad) {
        assertEquals(graphFacts(unobserved), graphFacts(observed.graph), "APK observation must preserve all graph facts")
        // graphFacts joins gap node ids after renumbering; the remaining evidence contains stable identities only.
        assertEquals(assertNotNull(unobserved.analysisCompleteness()).copy(callSiteGaps = emptyMap()),
            observed.completeness.copy(callSiteGaps = emptyMap()))
    }

    private fun loader(sdk: Path, folding: FoldPlan? = null): JavaProjectLoader = JavaProjectLoader(
        LoaderConfig(includePackages = listOf(OWNER), includeLibraries = false, buildCallGraph = false,
            androidSdk = sdk, folding = folding),
        useMmapBuilder = false
    ).also { it.warningSink = {} }

    private fun withAndroidFixture(action: (Path, Path) -> Unit) {
        val sdk = minimalAndroidSdk("folded-body-recovery", apiLevel = 21)
        try {
            action(apk(), sdk)
        } finally {
            sdk.toFile().deleteRecursively()
        }
    }

    private fun apk(): Path = Path.of(requireNotNull(System.getProperty("frontend.correctness.android")))
    private fun apkName(): String = apk().fileName.toString()

    private companion object {
        const val OWNER = "fixture.frontend.android.AndroidCases"
        const val METHOD = "$OWNER.staticArguments()Ljava/lang/String;"
    }
}
