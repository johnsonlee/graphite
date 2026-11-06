package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.CallSiteGap
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.DependencyBodyScope
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DependencyLateDispatchGapTest : DependencyScopeFixture() {
    @Test
    fun `callback-only dependency retains partial dispatch evidence`() {
        val graph = lateGraph("""
            Supplier<String> supplier = System.nanoTime() > 0 ? () -> "late" : external;
            return supplier.get();
        """)
        val calls = graph.nodes<CallSiteNode>().toList()
        val callback = calls.single { it.caller.declaringClass.className == late && it.caller.name == "read" && it.callee.name == "get" }
        assertTrue(calls.any { it.caller.declaringClass.className == late && it.callee.name.startsWith("lambda\$") },
            "the late body resolves its known lambda alternative")
        assertEquals(CallSiteGap.DISPATCH_PARTIAL, assertNotNull(graph.analysisCompleteness()).callSiteGaps[callback.id.value])
    }

    @Test
    fun `late known field alternative marks an earlier resolved call partial`() {
        val graph = lateGraph("""
            sample.scope.app.Registry.selected = () -> "late";
            return "installed";
        """)
        assertEquals(CallSiteGap.DISPATCH_PARTIAL, rootGap(graph))
    }

    @Test
    fun `late saturated field marks an earlier resolved call partial`() {
        val assignments = (1..65).joinToString("\n") { "sample.scope.app.Registry.selected = () -> \"late$it\";" }
        val graph = lateGraph("$assignments\nreturn \"installed\";")
        assertEquals(CallSiteGap.DISPATCH_PARTIAL, rootGap(graph))
    }

    @Test
    fun `late known local stays complete and an unknown local stays unresolved`() {
        val known = lateGraph("""
            Supplier<String> supplier = () -> "known";
            return supplier.get();
        """)
        val knownCall = known.nodes<CallSiteNode>().single { it.caller.declaringClass.className == late && it.callee.name == "get" }
        assertNull(assertNotNull(known.analysisCompleteness()).callSiteGaps[knownCall.id.value])
        assertNull(rootGap(known))

        val unknown = lateGraph("return external.get();")
        val unknownCall = unknown.nodes<CallSiteNode>().single { it.caller.declaringClass.className == late && it.callee.name == "get" }
        assertEquals(CallSiteGap.DISPATCH_UNRESOLVED, assertNotNull(unknown.analysisCompleteness()).callSiteGaps[unknownCall.id.value])
    }

    private val late = "sample.scope.late.Late"

    private fun rootGap(graph: Graph): CallSiteGap? {
        val calls = graph.nodes<CallSiteNode>().filter { it.caller.name == "run" }.toList()
        assertTrue(calls.any { it.callee.name.startsWith("lambda\$") }, "the root resolved the original selected lambda")
        val selected = calls.filter { it.callee.name == "get" }.maxBy { requireNotNull(it.ordinal) }
        return assertNotNull(graph.analysisCompleteness()).callSiteGaps[selected.id.value]
    }

    private fun lateGraph(body: String): Graph {
        val classes = compile(mapOf(
            "Late.java" to """
                package sample.scope.late;
                import java.util.function.Supplier;
                public class Late {
                    public static Supplier<String> external;
                    public static String read() { $body }
                }
            """.trimIndent(),
            "CallbackRoot.java" to """
                package sample.scope.app;
                public class CallbackRoot {
                    public String run() { Registry.callback.get(); return Registry.selected.get(); }
                }
            """.trimIndent(),
            "Registry.java" to """
                package sample.scope.app;
                import java.util.function.Supplier;
                public class Registry {
                    public static Supplier<String> callback;
                    public static Supplier<String> selected = () -> "original";
                    public static void install() { callback = sample.scope.late.Late::read; }
                }
            """.trimIndent()
        ), "late-dispatch-gaps")
        val input = Files.createTempDirectory("scope-late-dispatch-gaps").also(temporaryRoots::add)
        jar(input.resolve("app.jar"), classes, "sample/scope/app/")
        jar(input.resolve("late.jar"), classes, "sample/scope/late/")
        val config = LoaderConfig(includePackages = listOf(appPackage), includeLibraries = true,
            buildCallGraph = false, dependencyBodies = DependencyBodyScope.Reachable(roots = listOf("$appPackage.CallbackRoot")))
        val graph = load(input, config)
        assertTrue(
            graph.nodes<CallSiteNode>().any { it.caller.name == "run" && it.callee.declaringClass.className == late && it.origin != null },
            "the root reaches Late.read through resolved field dispatch"
        )
        assertEquals(AnalysisStatus.ANALYZED, assertNotNull(graph.analysisCompleteness()).classStatus(late))
        return graph
    }
}
