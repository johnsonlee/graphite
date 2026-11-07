package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.AnalysisCompleteness
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.ArtifactEvidence
import io.johnsonlee.graphite.graph.ArtifactRole
import io.johnsonlee.graphite.graph.ClassEvidence
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.FullGraphBuilder
import io.johnsonlee.graphite.graph.MethodEvidence
import io.johnsonlee.graphite.graph.analysisStatus
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.AnalysisIdentity
import io.johnsonlee.graphite.input.DependencyBodyScope
import io.johnsonlee.graphite.input.FoldPlan
import io.johnsonlee.graphite.input.LoaderConfig
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import sootup.core.model.MethodModifier

class FoldedBodyObservationLifecycleTest : FoldedBodyObservationFixture() {
    @Test
    fun `an observed body does not hide incomplete graph extraction`() {
        val input = smallInput(mapOf(
            "App.java" to """
                package sample.scope.app;
                public class App {
                    public String run() {
                        String first = sample.scope.dep.Helper.read();
                        return sample.scope.dep.Helper.after(first);
                    }
                }
            """.trimIndent(),
            "Helper.java" to """
                package sample.scope.dep;
                public class Helper {
                    public static String read() { return "first"; }
                    public static String after(String value) { return value; }
                }
            """.trimIndent()
        ))
        val method = method("$appPackage.App", "run", returns = "java.lang.String")
        val log = mutableListOf<String>()
        val failure = OutOfMemoryError("injected graph extraction failure")
        val backing = DefaultGraph.Builder()
        val builder = object : FullGraphBuilder by backing {
            override fun addNode(node: Node): FullGraphBuilder {
                if (node is CallSiteNode && node.caller == method && node.callee.name == "after") throw failure
                return backing.addNode(node)
            }
        }
        val loader = JavaProjectLoader(config().copy(verbose = log::add), graphBuilderFactory = { builder })
        val events = mutableListOf<FoldedMethodObservation>()
        val result = loader.loadObserved(input, events::add)
        val captured = events.single { it.evidence.method.identity == method.identity }
        assertEquals(listOf("read", "after"), calls(assertNotNull(captured.snapshot).body).map { it.methodSignature.name })
        assertEquals(listOf("read"), result.graph.nodes<CallSiteNode>().filter { it.caller == method }.map { it.callee.name }.toList())
        assertEquals(AnalysisStatus.INCOMPLETE, captured.evidence.status)
        assertEquals(AnalysisStatus.INCOMPLETE, result.graph.analysisStatus(method))
        assertEquals(failure.message, result.completeness.methods.getValue(method.identity).message)
        assertTrue(log.any { it.contains(method.signature) && it.contains(failure.message.orEmpty()) })
    }

    @Test
    fun `observer exceptions and OOM escape and the loader can be reused after failure`() {
        val input = smallInput()
        val loader = JavaProjectLoader(config(), false)
        for (failure in listOf(IllegalArgumentException("capture failed"), OutOfMemoryError("observer allocation failed"))) {
            assertSame(failure, assertFails { loader.loadObserved(input) { throw failure } })
            val result = loader.loadObserved(input) { }
            assertEquals(AnalysisStatus.ANALYZED,
                result.observations.getValue("$depPackage.Helper.read()Ljava/lang/String;").status)
            assertEquals(setOf("read", "<init>"), result.observations.values
                .filter { it.method.owner == "$depPackage.Helper" }.map { it.method.name }.toSet())
        }
    }

    @Test
    fun `sequential base and head loads cannot overwrite retained snapshots or receipts`() {
        val baseInput = smallInput()
        val headInput = smallInput(mapOf(
            "App.java" to "package sample.scope.app; public class App { public String run() { return sample.scope.dep.Helper.read(); } }",
            "Helper.java" to "package sample.scope.dep; public class Helper { public static String read() { return \"head\"; } }"
        ))
        val loader = JavaProjectLoader(config(), false)
        val baseEvents = linkedMapOf<String, FoldedMethodObservation>()
        val base = loader.loadObserved(baseInput) { baseEvents[it.evidence.method.identity] = it }
        val baseBody = assertNotNull(baseEvents["$depPackage.Helper.read()Ljava/lang/String;"]?.snapshot).body
        val before = baseBody.toString()
        val headEvents = linkedMapOf<String, FoldedMethodObservation>()
        val head = loader.loadObserved(headInput) { headEvents[it.evidence.method.identity] = it }
        val headBody = assertNotNull(headEvents["$depPackage.Helper.read()Ljava/lang/String;"]?.snapshot).body
        assertTrue(before.contains("\"dependency\""))
        assertTrue(headBody.toString().contains("\"head\""))
        assertFalse(headBody.toString().contains("\"dependency\""))
        assertEquals(before, baseBody.toString(), "the previous invocation's owned snapshot is still intact")
        assertEquals(baseEvents.keys, base.observations.keys)
        assertEquals(headEvents.keys, head.observations.keys)
        assertEquals(AnalysisStatus.ANALYZED, base.observations.getValue("$depPackage.Helper.read()Ljava/lang/String;").status)
    }

    @Test
    fun `distinct concurrent loaders keep invocation local sinks and snapshot data`() {
        val baseInput = smallInput()
        val headInput = smallInput(mapOf(
            "App.java" to "package sample.scope.app; public class App { public String run() { return sample.scope.dep.Helper.read(); } }",
            "Helper.java" to "package sample.scope.dep; public class Helper { public static String read() { return \"concurrent-head\"; } }"
        ))
        val ready = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = listOf(baseInput, headInput).map { input -> Callable {
                val events = linkedMapOf<String, FoldedMethodObservation>()
                var first = true
                val result = JavaProjectLoader(config(), false).loadObserved(input) { event ->
                    if (first) {
                        first = false
                        ready.countDown()
                        assertTrue(ready.await(30, TimeUnit.SECONDS), "both loads reach observation concurrently")
                    }
                    assertNull(events.put(event.evidence.method.identity, event))
                }
                assertEquals(events.keys, result.observations.keys)
                assertNotNull(events["$depPackage.Helper.read()Ljava/lang/String;"]?.snapshot).body.toString()
            } }
            val futures = tasks.map { pool.submit(it) }
            val bodies = futures.map { it.get(60, TimeUnit.SECONDS) }
            assertTrue(bodies[0].contains("\"dependency\""))
            assertFalse(bodies[0].contains("concurrent-head"))
            assertTrue(bodies[1].contains("\"concurrent-head\""))
            assertFalse(bodies[1].contains("\"dependency\""))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `same loader rejects callback reentry and overlapping loads before changing its state`() {
        val input = smallInput()
        val loader = JavaProjectLoader(config(), false)
        var reentered = false
        val outer = loader.loadObserved(input) {
            if (!reentered) {
                reentered = true
                val failure = assertFailsWith<IllegalStateException> { loader.load(input) }
                assertTrue(assertNotNull(failure.message).contains("load in progress"))
            }
        }
        assertTrue(reentered)
        assertEquals(AnalysisStatus.ANALYZED, outer.completeness.classStatus("$depPackage.Helper"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            var first = true
            val active = pool.submit(Callable {
                loader.loadObserved(input) {
                    if (first) {
                        first = false
                        entered.countDown()
                        assertTrue(release.await(30, TimeUnit.SECONDS))
                    }
                }
            })
            assertTrue(entered.await(30, TimeUnit.SECONDS))
            assertFailsWith<IllegalStateException> { loader.loadObserved(input) { } }
            release.countDown()
            assertEquals(AnalysisStatus.ANALYZED,
                active.get(60, TimeUnit.SECONDS).completeness.classStatus("$depPackage.Helper"))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `retained receipt collections are immutable including per method folding metadata`() {
        val input = bootJar()
        val (result, events) = observe(input, LoaderConfig(includePackages = listOf(appPackage), includeLibraries = true,
            dependencyBodies = DependencyBodyScope.Declared(artifacts = listOf("dep-*.jar")), folding = FoldPlan(listOf(gateOff))))
        val event = events.getValue(method(example, "value", returns = "java.lang.String").identity)
        assertFailsWith<UnsupportedOperationException> {
            (result.observations as MutableMap).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (assertNotNull(event.evidence.modifiers) as MutableSet<MethodModifier>).clear()
        }
        val folded = assertNotNull(event.evidence.folding)
        assertEquals(mapOf(0 to 1), folded.foldedCalls)
        assertFailsWith<UnsupportedOperationException> { (folded.foldedCalls as MutableMap)[0] = 99 }
        assertFailsWith<UnsupportedOperationException> { (folded.unsupportedReasons as MutableMap)[0] = listOf("invented") }
        assertEquals(mapOf(0 to 1), folded.foldedCalls)
        assertEquals(events.keys, result.observations.keys)
    }

    @Test
    fun `known resolution failures are explicit and class enumeration failures never invent successful captures`() {
        val failed = "$depPackage.Missing.work()V"
        val graph = DefaultGraph.Builder().apply {
            setAnalysisCompleteness(AnalysisCompleteness(AnalysisIdentity(),
                mapOf("dep.jar" to ArtifactEvidence(ArtifactRole.LIBRARY)),
                mapOf("$depPackage.Missing" to ClassEvidence("dep.jar", AnalysisStatus.RESOLUTION_FAILED),
                    "$depPackage.Unenumerable" to ClassEvidence("dep.jar", AnalysisStatus.RESOLUTION_FAILED)),
                mapOf(failed to MethodEvidence(AnalysisStatus.RESOLUTION_FAILED, "malformed method declaration"))))
        }.build()
        val events = mutableListOf<FoldedMethodObservation>()
        val result = FoldedBodyDelivery(FoldedBodyObserver { events += it }).finish(graph, null)
        val failure = events.single()
        assertEquals(failed, failure.evidence.method.identity)
        assertEquals(AnalysisStatus.RESOLUTION_FAILED, failure.evidence.status)
        assertEquals("malformed method declaration", failure.evidence.message)
        assertEquals(ArtifactRole.LIBRARY, failure.evidence.role)
        assertEquals("dep.jar", failure.evidence.origin)
        assertNull(failure.snapshot)
        assertNull(failure.evidence.modifiers)
        assertNull(failure.evidence.application)
        assertEquals(AnalysisStatus.RESOLUTION_FAILED, result.completeness.classStatus("$depPackage.Unenumerable"))
        assertFalse(result.observations.values.any { it.method.owner == "$depPackage.Unenumerable" })
    }

    private fun config() = LoaderConfig(includePackages = listOf(appPackage), includeLibraries = true,
        dependencyBodies = DependencyBodyScope.Declared(artifacts = listOf("dep.jar")))
}
