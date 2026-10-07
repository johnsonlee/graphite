package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.ConstantFold
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import sootup.core.interceptor.BodyInterceptor
import sootup.core.graph.MutableBasicBlock
import sootup.core.graph.MutableBlockControlFlowGraph
import sootup.core.jimple.Jimple
import sootup.core.jimple.basic.StmtPositionInfo
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.stmt.JReturnStmt
import sootup.core.model.Body
import sootup.core.model.SourceType
import sootup.java.core.views.JavaView

class RecoveringBodyInterceptorTest {
    private val roots = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        roots.forEach { it.toFile().deleteRecursively() }
    }

    private fun fixture(): Pair<Body.BodyBuilder, JavaView> {
        val root = Files.createTempDirectory("recovering-body").also(roots::add)
        val source = root.resolve("Shapes.java")
        Files.writeString(source, """
            public class Shapes {
                public static int value() { return 7; }
                public static int folded() { return value(); }
                public static int branches(int key) {
                    try {
                        switch (key) {
                            case 0: case 1: return Integer.parseInt("11");
                            case 2: return Integer.parseInt("22");
                            default: return Integer.parseInt("33");
                        }
                    } catch (NumberFormatException failure) { return -1; }
                }
            }
        """.trimIndent())
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", root.toString(), source.toString()))
        val view = JavaView(ParsedClassLocation(root, SourceType.Application, emptyList()))
        val method = view.getClass(view.identifierFactory.getClassType("Shapes")).get().methods.single { it.name == "branches" }
        return Body.builder(method.body, method.modifiers) to view
    }

    private fun method(view: JavaView, name: String): Body.BodyBuilder {
        val method = view.getClass(view.identifierFactory.getClassType("Shapes")).get().methods.single { it.name == name }
        return Body.builder(method.body, method.modifiers)
    }

    @Test
    fun `a failed pass retains original locals statements and traps and the next method succeeds`() {
        val (builder, view) = fixture()
        val original = builder.build().toString()
        val locals = builder.locals.toSet()
        val starting = builder.controlFlowGraph.startingStmt
        val failures = mutableListOf<String>()
        val mutateThenFail = BodyInterceptor { candidate, _ ->
            if (candidate.methodSignature.name == "branches") {
                candidate.controlFlowGraph.blocks.toList().forEach(candidate.controlFlowGraph::removeBlock)
                candidate.locals.clear()
                error("deliberate failure after mutation")
            }
            val returned = candidate.stmts.filterIsInstance<JReturnStmt>().single()
            candidate.controlFlowGraph.replaceNode(returned, returned.withReturnValue(IntConstant.getInstance(42)))
        }
        val guard = RecoveringBodyInterceptor(listOf(mutateThenFail), failures::add)
        guard.interceptBody(builder, view)
        assertEquals(original, builder.build().toString())
        assertEquals(locals, builder.locals)
        assertSame(starting, builder.controlFlowGraph.startingStmt)
        assertEquals(1, failures.size)
        assertTrue(failures.single().contains("Shapes: int branches(int)"))
        assertTrue(failures.single().contains("deliberate failure after mutation"))

        val next = method(view, "value")
        guard.interceptBody(next, view)
        assertEquals(IntConstant.getInstance(42), next.build().stmts.filterIsInstance<JReturnStmt>().single().op)
        assertEquals(1, failures.size)
    }

    @Test
    fun `successful interception preserves switch successor order duplicate targets and exceptional edges`() {
        val (builder, view) = fixture()
        val graph = builder.controlFlowGraph
        val original = builder.build().toString()
        val successors = graph.nodes.associateWith { graph.successors(it).toList() }
        val exceptional = graph.nodes.associateWith { graph.exceptionalSuccessors(it).toMap() }
        assertTrue(exceptional.values.any { it.isNotEmpty() }, "fixture must contain trap edges")
        assertTrue(successors.values.any { it.size > it.toSet().size }, "fixture must have duplicate switch targets")
        RecoveringBodyInterceptor(listOf(BodyInterceptor { _, _ -> }), { error(it) }).interceptBody(builder, view)
        assertEquals(original, builder.build().toString())
        assertEquals(successors, graph.nodes.associateWith { graph.successors(it).toList() })
        assertEquals(exceptional, graph.nodes.associateWith { graph.exceptionalSuccessors(it).toMap() })
    }

    @Test
    fun `successful interception preserves successors when blocks precede their predecessors`() {
        val (_, view) = fixture()
        val position = StmtPositionInfo.getNoStmtPositionInfo()
        val first = Jimple.newNopStmt(position)
        val branch = Jimple.newGotoStmt(position)
        val returned = Jimple.newReturnStmt(IntConstant.getInstance(7), position)
        val graph = MutableBlockControlFlowGraph()
        for (stmt in listOf(branch, returned, first)) graph.addBlock(listOf(stmt))
        (graph.getBlockOf(branch) as MutableBasicBlock).linkSuccessor(0, graph.getBlockOf(returned) as MutableBasicBlock)
        (graph.getBlockOf(first) as MutableBasicBlock).linkSuccessor(0, graph.getBlockOf(branch) as MutableBasicBlock)
        graph.setStartingStmt(first)
        val builder = Body.builder(graph).setMethodSignature(method(view, "value").methodSignature)
        RecoveringBodyInterceptor(listOf(BodyInterceptor { _, _ -> }), { error(it) }).interceptBody(builder, view)
        assertEquals(listOf(branch), graph.successors(first))
        assertEquals(listOf(returned), graph.successors(branch))
        assertEquals(listOf(first, branch, returned), builder.build().stmts)
    }

    @Test
    fun `invalid graph returned by an interceptor also falls back`() {
        val (builder, view) = fixture()
        val original = builder.build().toString()
        val failures = mutableListOf<String>()
        RecoveringBodyInterceptor(listOf(BodyInterceptor { candidate, _ ->
            val graph = candidate.controlFlowGraph
            val branching = graph.nodes.first { graph.successors(it).size > 1 }
            graph.successors(branching).toList().forEach { graph.removeEdge(branching, it) }
        }), failures::add).interceptBody(builder, view)
        assertEquals(original, builder.build().toString())
        assertEquals(1, failures.size)
    }

    @Test
    fun `OOM propagates without counting or retaining a partially intercepted body`() {
        val (builder, view) = fixture()
        val original = builder.build().toString()
        val failure = OutOfMemoryError("test allocation failure")
        val guard = RecoveringBodyInterceptor(listOf(BodyInterceptor { candidate, _ ->
            candidate.locals.clear()
            throw failure
        }), { error("OOM must not be reported as an interceptor fallback") })
        assertSame(failure, assertFailsWith<OutOfMemoryError> { guard.interceptBody(builder, view) })
        assertEquals(original, builder.build().toString())
    }

    @Test
    fun `failed folding discards report entries and call ordinals with the transformed body`() {
        val (_, view) = fixture()
        val builder = method(view, "folded")
        val original = builder.build().toString()
        val fold = ConstantFold.parse(mapOf(
            "match" to mapOf("CallSite" to mapOf("callee_class" to "Shapes", "callee_name" to "value")),
            "value" to 42
        ))
        val folding = ConstantFolding(listOf(fold))
        val afterAccounting = BodyInterceptor { candidate, _ ->
            assertEquals(1, folding.report().methods.size)
            assertNotNull(folding.ordinalsBeforeFolding(candidate.methodSignature))
            error("failure after fold accounting")
        }
        val failures = mutableListOf<String>()
        RecoveringBodyInterceptor(
            folding.bodyInterceptors(emptyList()) + afterAccounting, failures::add, folding::discard
        ).interceptBody(builder, view)
        assertEquals(original, builder.build().toString())
        assertEquals(1, failures.size)
        assertTrue(folding.report().methods.isEmpty())
        assertTrue(folding.report().outcomes.single().sites.isEmpty())
        assertNull(folding.ordinalsBeforeFolding(builder.methodSignature))
    }
}
