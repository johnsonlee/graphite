package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.EnumValueReference
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.LoaderConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import sootup.core.jimple.basic.StmtPositionInfo
import sootup.core.jimple.common.Immediate
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.constant.StringConstant
import sootup.core.jimple.common.expr.JSpecialInvokeExpr
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.jimple.common.expr.JVirtualInvokeExpr
import sootup.core.jimple.common.ref.JStaticFieldRef
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.java.core.JavaIdentifierFactory
import sootup.java.core.jimple.basic.JavaLocal
import sootup.java.core.views.JavaView

class EnumConstructorIndexTest {
    private val factory = JavaIdentifierFactory.getInstance()
    private val className = "sample.indexed.Values"
    private val enumType = factory.getClassType(className)
    private val position = StmtPositionInfo.getNoStmtPositionInfo()

    @Test
    fun `first eligible invocation wins after short constructors and ignores other statement kinds`() {
        val receiver = local("receiver")
        val ignored = local("ignored")
        val short = local("short")
        val foreign = local("foreign")
        val arguments = args(IntConstant.getInstance(99))
        val result = JavaLocal("result", factory.getType("int"), emptyList())
        val statements = listOf(
            JInvokeStmt(JStaticInvokeExpr(signature("<init>", arguments), arguments), position),
            JInvokeStmt(JVirtualInvokeExpr(receiver, signature("touch", arguments), arguments), position),
            JAssignStmt(result, JVirtualInvokeExpr(ignored, signature("<init>", arguments, "int"), arguments), position),
            constructor(receiver),
            constructor(receiver, IntConstant.getInstance(11)),
            constructor(JavaLocal("receiver", factory.getType("java.lang.Object"), emptyList()), IntConstant.getInstance(22)),
            constructor(short),
            // The old search matches base.name and <init>, without restricting invocation kind or owner.
            JInvokeStmt(JVirtualInvokeExpr(foreign, signature("<init>", arguments, owner = "sample.Other"), arguments), position),
            field("FIRST", receiver), field("IGNORED", ignored), field("SHORT", short), field("FOREIGN", foreign)
        )
        val graph = extract(statements)
        assertEquals(listOf(11), graph.enumValues(className, "FIRST"))
        assertNull(graph.enumValues(className, "IGNORED"))
        assertNull(graph.enumValues(className, "SHORT"))
        assertEquals(listOf(99), graph.enumValues(className, "FOREIGN"))
    }

    @Test
    fun `arguments are resolved at each field assignment with existing flattened aliases`() {
        val original = local("original")
        val other = local("other")
        val alias = local("alias")
        val copiedAlias = local("copiedAlias")
        val value = JavaLocal("value", factory.getType("int"), emptyList())
        val unknown = JavaLocal("unknown", factory.getType("int"), emptyList())
        val statements = listOf(
            JAssignStmt(value, IntConstant.getInstance(1), position),
            JAssignStmt(alias, original, position), JAssignStmt(copiedAlias, alias, position),
            field("FIRST", copiedAlias),
            JAssignStmt(value, IntConstant.getInstance(2), position),
            field("SECOND", copiedAlias),
            JAssignStmt(alias, other, position),
            field("REBOUND", alias), field("COPIED", copiedAlias),
            // Full-CFG lookup can find a later invocation; it must not use the final value of the local.
            constructor(original, value, unknown, string("tag")),
            constructor(original, IntConstant.getInstance(99)),
            constructor(other, IntConstant.getInstance(66)),
            JAssignStmt(value, IntConstant.getInstance(3), position)
        )
        val graph = extract(statements)
        assertEquals(listOf(1, null, "tag"), graph.enumValues(className, "FIRST"))
        assertEquals(listOf(2, null, "tag"), graph.enumValues(className, "SECOND"))
        assertEquals(listOf(66), graph.enumValues(className, "REBOUND"))
        assertEquals(listOf(2, null, "tag"), graph.enumValues(className, "COPIED"))
    }

    @Test
    fun `unknown arguments remain null and field names are not filtered`() {
        val receiver = local("receiver")
        val unknown = JavaLocal("unknown", factory.getType("int"), emptyList())
        val graph = extract(listOf(
            constructor(receiver, unknown),
            constructor(receiver, IntConstant.getInstance(7)),
            field("\$VALUES", receiver)
        ))
        assertEquals(listOf(null), graph.enumValues(className, "\$VALUES"))
    }

    @Test
    fun `boxed and enum reference arguments use each assignment snapshot in two statement passes`() {
        val receiver = local("receiver")
        val boxed = JavaLocal("boxed", factory.getType("java.lang.Integer"), emptyList())
        val priorityType = factory.getClassType("sample.Priority")
        val priority = JavaLocal("priority", priorityType, emptyList())
        val boxingSignature = factory.getMethodSignature("java.lang.Integer", "valueOf", "java.lang.Integer", listOf("int"))
        fun box(value: Int) = JAssignStmt(boxed, JStaticInvokeExpr(boxingSignature, listOf(IntConstant.getInstance(value))), position)
        fun assignPriority(name: String) = JAssignStmt(
            priority, JStaticFieldRef(factory.getFieldSignature(name, priorityType, priorityType)), position
        )
        val statements = CountingStatements(listOf(
            box(10), assignPriority("HIGH"), constructor(receiver, boxed, priority), field("FIRST", receiver),
            box(20), assignPriority("LOW"), field("SECOND", receiver)
        ))
        val graph = extract(statements)
        assertEquals(listOf(10, EnumValueReference("sample.Priority", "HIGH")), graph.enumValues(className, "FIRST"))
        assertEquals(listOf(20, EnumValueReference("sample.Priority", "LOW")), graph.enumValues(className, "SECOND"))
        assertEquals(2, statements.iterations, "one constructor indexing pass and one value/alias pass, not a rescan per field")
    }

    private fun local(name: String): JavaLocal = JavaLocal(name, enumType, emptyList())

    private fun string(value: String) = StringConstant(value, factory.getType("java.lang.String"))

    private fun args(vararg userArgs: Immediate): List<Immediate> = listOf(string("VALUE"), IntConstant.getInstance(0)) + userArgs

    private fun signature(name: String, args: List<Immediate>, returnType: String = "void", owner: String = className) =
        factory.getMethodSignature(owner, name, returnType, args.map { it.type.toString() })

    private fun constructor(receiver: Local, vararg userArgs: Immediate): JInvokeStmt {
        val args = args(*userArgs)
        return JInvokeStmt(JSpecialInvokeExpr(receiver, signature("<init>", args), args), position)
    }

    private fun field(name: String, value: Local) =
        JAssignStmt(JStaticFieldRef(factory.getFieldSignature(name, enumType, enumType)), value, position)

    private fun extract(statements: Iterable<Stmt>): Graph {
        val builder = DefaultGraph.Builder()
        val adapter = SootUpAdapter(
            JavaView(emptyList()), LoaderConfig(verbose = {}), extensions = emptyList(),
            inputLocationSources = emptyMap(), graphBuilder = builder
        )
        SootUpAdapter::class.java.getDeclaredMethod("extractEnumValues", String::class.java, Iterable::class.java).apply {
            isAccessible = true
        }.invoke(adapter, className, statements)
        return builder.build()
    }

    private class CountingStatements(private val statements: List<Stmt>) : Iterable<Stmt> {
        var iterations = 0
            private set

        override fun iterator(): Iterator<Stmt> {
            iterations++
            return statements.iterator()
        }
    }
}
