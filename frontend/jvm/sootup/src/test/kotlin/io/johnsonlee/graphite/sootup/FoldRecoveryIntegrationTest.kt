package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MmapGraph
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.CallSiteKey
import io.johnsonlee.graphite.input.ConstantPattern
import io.johnsonlee.graphite.input.FoldPlan
import io.johnsonlee.graphite.input.FoldReport
import io.johnsonlee.graphite.input.FoldSites
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FoldRecoveryIntegrationTest {

    @Test
    fun `streamed folding preserves surviving ordinals and reports once after each completed load`() {
        val root = Files.createTempDirectory("fold-recovery")
        try {
            val classes = compile(root)
            val selected = selectFirst(classes)
            val events = mutableListOf<String>()
            val reports = mutableListOf<FoldReport>()
            val rule = FoldSites("MATCH (cs:CallSite) RETURN cs", setOf(selected), ConstantPattern.parse(false, "value"))
            val loader = JavaProjectLoader(LoaderConfig(
                includePackages = listOf("sample.foldrecovery"),
                folding = FoldPlan(listOf(rule)) { report ->
                    events.add("report")
                    reports.add(report)
                },
                verbose = { if (it == "Finished graphBuilder.build()") events.add("finished") }
            ))
            repeat(2) {
                assertIs<MmapGraph>(loader.load(classes)).use { folded ->
                    assertFoldedCalls(folded)
                    val report = reports.last()
                    assertEquals(1, report.outcomes.single().matched, "call-graph body resolution must not double-count")
                    assertEquals(selected.caller, report.outcomes.single().sites.single().method)
                    assertEquals(1, report.methods.size)
                }
            }
            assertEquals(listOf("finished", "report", "finished", "report"), events)
            assertEquals(2, reports.size)
            assertEquals(reports.first(), reports.last(), "loader reuse must not retain the previous load's fold accounting")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
    private fun compile(root: Path): Path {
        val source = root.resolve("Calls.java")
        Files.writeString(source, """
            package sample.foldrecovery;
            public class Calls {
                public static boolean enabled(String key) { return key.length() > 2; }
                public static void work(int value) { }
                public static void run() {
                    if (enabled("a")) { work(1); }
                    if (enabled("b")) { work(2); }
                }
                public static void main(String[] args) { run(); }
            }
        """.trimIndent())
        val classes = Files.createDirectory(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString()))
        return classes
    }

    private fun selectFirst(classes: Path): CallSiteKey {
        val graph = JavaProjectLoader(LoaderConfig(includePackages = listOf("sample.foldrecovery"))).load(classes)
        return assertIs<MmapGraph>(graph).use { plain ->
            val calls = plain.nodes<CallSiteNode>().filter { it.caller.name == "run" && it.callee.name == "enabled" }.toList()
            assertEquals(listOf<Int?>(0, 1), calls.map { it.ordinal }.sortedBy { it })
            val first = calls.single { it.ordinal == 0 }
            CallSiteKey(first.caller.signature, first.caller.descriptor, first.callee.signature, first.callee.descriptor, 0)
        }
    }

    private fun assertFoldedCalls(graph: Graph) {
        val calls = graph.nodes<CallSiteNode>().filter { it.caller.name == "run" }.toList()
        assertEquals(listOf<Int?>(1), calls.filter { it.callee.name == "enabled" }.map { it.ordinal })
        val work = calls.single { it.callee.name == "work" }
        assertEquals(1, work.ordinal, "the second work call keeps its pre-fold ordinal")
        assertEquals(2, (graph.node(work.arguments.single()) as IntConstant).value)
    }

}
