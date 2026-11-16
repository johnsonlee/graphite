package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.analysis.DataFlowAnalysis
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ClassConstant
import io.johnsonlee.graphite.core.ConstantNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.LoaderConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import sootup.core.jimple.common.constant.ClassConstant as SootClassConstant
import sootup.core.jimple.common.constant.MethodType as SootMethodType
import sootup.java.core.JavaIdentifierFactory
import sootup.java.core.views.JavaView

class ClassLiteralNodeTest : DependencyScopeFixture() {
    private val registrySource = """
        package sample.reg;
        public class Registry {
            public static void register(Object type, Object parser) {}
            public static Object create(Class<?> type) throws Exception { return type.getDeclaredConstructor().newInstance(); }
        }
    """.trimIndent()

    private val setupSource = """
        package sample.reg;
        public class Setup {
            public static class Payload {}
            public static void init() throws Exception {
                Registry.register(Payload.class, null);
                Registry.register(Payload[].class, null);
                Registry.register(int[][].class, null);
                Registry.register("Lsample/reg/Setup${'$'}Payload;", null);
                Registry.register(0, null);
                Class<?> viaLocal = Payload.class;
                Registry.create(viaLocal);
            }
        }
    """.trimIndent()

    private fun graph(): Graph {
        val classes = compile(mapOf("Registry.java" to registrySource, "Setup.java" to setupSource), "class-literal")
        return load(classes, LoaderConfig(includePackages = listOf("sample.reg"), buildCallGraph = false))
    }

    private fun Graph.calls(name: String): List<CallSiteNode> = nodes<CallSiteNode>()
        .filter { it.caller.name == "init" && it.callee.name == name }
        .sortedBy { it.id.value }
        .toList()

    @Test
    fun `class literals become ClassConstant nodes holding the JVM descriptor`() {
        val graph = graph()
        val registered = graph.calls("register").map { graph.node(it.arguments[0]) }
        val payload = assertIs<ClassConstant>(registered[0])
        assertEquals("Lsample/reg/Setup\$Payload;", payload.value)
        assertEquals("[Lsample/reg/Setup\$Payload;", assertIs<ClassConstant>(registered[1]).value)
        assertEquals("[[I", assertIs<ClassConstant>(registered[2]).value)
        val text = assertIs<StringConstant>(registered[3])
        assertEquals(payload.value, text.value, "the string spells the same descriptor")
        assertNotEquals(payload.id, text.id, "a class literal never interns onto an equal string")
        assertEquals(
            listOf("Lsample/reg/Setup\$Payload;", "[Lsample/reg/Setup\$Payload;", "[[I"),
            graph.nodes<ClassConstant>().map { it.value }.sorted().toList(),
            "one node per distinct class literal: Payload.class is interned across both uses"
        )
        assertTrue(graph.nodes<IntConstant>().none { it.value == 0 && it.id == payload.id })
    }

    @Test
    fun `a class literal flows through a local into a call argument`() {
        val graph = graph()
        val create = graph.calls("create").single()
        val sources = DataFlowAnalysis(graph).backwardSlice(create.arguments.single()).constants()
        val literal = assertIs<ClassConstant>(sources.single())
        assertEquals("Lsample/reg/Setup\$Payload;", literal.value)
        assertEquals(graph.node(graph.calls("register")[0].arguments[0]), literal as ConstantNode)
    }

    @Test
    fun `descriptor normalises an internal name and keeps a descriptor`() {
        val type = JavaIdentifierFactory.getInstance().getClassType("java.lang.Class")
        assertEquals("Lsample/Payload;", classLiteralDescriptor(SootClassConstant("sample/Payload", type)))
        assertEquals("Lsample/Payload;", classLiteralDescriptor(SootClassConstant("Lsample/Payload;", type)))
        assertEquals("[I", classLiteralDescriptor(SootClassConstant("[I", type)))
    }

    @Test
    fun `a constant still unmodelled keeps the logged fallback and never merges with a class literal`() {
        val messages = mutableListOf<String>()
        val adapter = SootUpAdapter(
            JavaView(emptyList()), LoaderConfig(verbose = messages::add), extensions = emptyList(), inputLocationSources = emptyMap())
        val factory = JavaIdentifierFactory.getInstance()
        val handleType = SootMethodType(
            factory.parseMethodSignature("<sample.Payload: void run()>").subSignature, factory.getClassType("java.lang.invoke.MethodType"))
        val fallback = assertIs<IntConstant>(adapter.values.getOrCreateConstant(handleType))
        assertEquals(0, fallback.value)
        assertTrue(messages.any { it.startsWith("Unsupported constant type: MethodType") }, "$messages")
        val literal = adapter.values.getOrCreateConstant(SootClassConstant("Lsample/Payload;", factory.getClassType("java.lang.Class")))
        assertEquals("Lsample/Payload;", assertIs<ClassConstant>(literal).value)
        assertNotEquals(fallback.id, literal.id)
        assertEquals(null, adapter.values.extractConstantValue(handleType))
    }
}
