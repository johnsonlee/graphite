package io.johnsonlee.graphite.sootup

import java.util.Collections
import java.util.IdentityHashMap
import sootup.core.graph.BasicBlock
import sootup.core.graph.ControlFlowGraph
import sootup.core.graph.ImmutableBlockControlFlowGraph
import sootup.core.graph.MutableBlockControlFlowGraph
import sootup.core.jimple.basic.FullStmtPositionInfo
import sootup.core.jimple.basic.NoPositionInformation
import sootup.core.jimple.basic.SimpleStmtPositionInfo
import sootup.core.jimple.basic.StmtPositionInfo
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.constant.Constant
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr
import sootup.core.jimple.common.expr.AbstractInvokeExpr
import sootup.core.jimple.common.expr.JDynamicInvokeExpr
import sootup.core.jimple.common.expr.JNewMultiArrayExpr
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JGotoStmt
import sootup.core.jimple.common.stmt.JIdentityStmt
import sootup.core.jimple.common.stmt.JIfStmt
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.core.jimple.common.stmt.JNopStmt
import sootup.core.jimple.common.stmt.JReturnStmt
import sootup.core.jimple.common.stmt.JReturnVoidStmt
import sootup.core.jimple.common.stmt.JThrowStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.jimple.javabytecode.stmt.JBreakpointStmt
import sootup.core.jimple.javabytecode.stmt.JEnterMonitorStmt
import sootup.core.jimple.javabytecode.stmt.JExitMonitorStmt
import sootup.core.jimple.javabytecode.stmt.JRetStmt
import sootup.core.jimple.javabytecode.stmt.JSwitchStmt
import sootup.core.model.Body
import sootup.core.model.FullPosition
import sootup.core.model.Position
import sootup.java.core.AnnotationUsage
import sootup.java.core.jimple.basic.JavaLocal

/**
 * An owned, read-only copy of the non-SSA Jimple consumed by this frontend.
 *
 * [body] preserves typed SootUp instructions, locals, positions, branch ordering and exceptional
 * control flow. It can be retained after the observer returns or the load finishes. Its graph
 * and blocks are immutable; collections that SootUp computes on demand may be mutable copies.
 * No view, body source, builder or original mutable graph is retained.
 *
 * SootUp's ordinary Body builder only wraps a mutable graph and several Jimple constructors
 * retain mutable input collections. Capture therefore freezes the graph and copies those
 * collections explicitly. SSA phi expressions and unknown representations fail capture rather
 * than provide a snapshot with incomplete semantics or mutable aliases.
 */
class FoldedBodySnapshot private constructor(val body: Body) {
    internal companion object {
        fun capture(body: Body): FoldedBodySnapshot {
            val graph = SnapshotGraph(body.controlFlowGraph)
            val locals = IdentityHashMap<Local, Local>()
            fun ownedLocal(local: Local): Local = locals.getOrPut(local) { copyLocal(local) }
            body.locals.forEach(::ownedLocal)
            for (stmt in body.controlFlowGraph.nodes) {
                stmt.usesAndDefs.forEach(::checkValue)
                graph.replaceNode(stmt, copyStatement(stmt, ::ownedLocal))
            }
            // Body wraps this set without copying it. Only this builder owns the copy.
            val builder = Body.builder(graph)
                .setMethodSignature(body.methodSignature)
                .setLocals(body.locals.mapTo(LinkedHashSet(), ::ownedLocal))
                .setPosition(copyPosition(body.position))
            return FoldedBodySnapshot(builder.build())
        }
    }
}

/** Body's normal unmodifiable graph is a forwarding facade that still exposes mutable blocks. */
private class SnapshotGraph(graph: ControlFlowGraph<*>) : MutableBlockControlFlowGraph(graph) {
    // Body.getStmts iterates blocks in a different order from getBlocksSorted (reverse
    // postorder). Preserve the exact analyzed instruction order, including disconnected blocks.
    // Typed statement copies preserve branching and therefore do not split or merge blocks.
    private val copiedBlockOrder: List<BasicBlock<*>> = run {
        val originalOrder = mutableListOf<BasicBlock<*>>()
        val seen = Collections.newSetFromMap(IdentityHashMap<BasicBlock<*>, Boolean>())
        graph.forEach { stmt ->
            val block = graph.getBlockOf(stmt)
            if (seen.add(block)) originalOrder.add(block)
        }
        graph.blocks.forEach { if (seen.add(it)) originalOrder.add(it) }
        originalOrder.map { getBlockOf(it.head) }
    }

    override fun getBlocksSorted(): List<BasicBlock<*>> = copiedBlockOrder

    override fun unmodifiableControlFlowGraph(): ControlFlowGraph<*> = ImmutableBlockControlFlowGraph(this)
}

@Suppress("CyclomaticComplexMethod") // Exhaustive supported Jimple statements, each with its own typed copy API.
private fun copyStatement(original: Stmt, local: (Local) -> Local): Stmt {
    check(original.javaClass.name in SNAPSHOT_STATEMENT_CLASSES) {
        "Unsupported folded-body statement: ${original.javaClass.name}"
    }
    val position = copyPositionInfo(original.positionInfo)
    if (original is JSwitchStmt) {
        val key = (original.key as? Local)?.let(local) ?: original.key
        // SootUp's withKey/withPositionInfo methods turn table switches into lookup switches.
        return if (original.isTableSwitch) {
            JSwitchStmt(key, original.values.first().value, original.values.last().value, position)
        } else JSwitchStmt(key, ArrayList(original.values), position)
    }
    var stmt = original
    for (used in original.uses.filterIsInstance<Local>()) stmt = stmt.withNewUse(used, local(used))
    return when (stmt) {
        is JAssignStmt -> JAssignStmt((stmt.leftOp as? Local)?.let(local) ?: stmt.leftOp, copyValue(stmt.rightOp), position)
        is JInvokeStmt -> JInvokeStmt(copyInvoke(stmt.invokeExpr.orElseThrow()), position)
        is JIdentityStmt -> stmt.withLocal(local(stmt.leftOp)).withPositionInfo(position)
        is JIfStmt -> stmt.withPositionInfo(position)
        is JGotoStmt -> stmt.withPositionInfo(position)
        is JReturnStmt -> stmt.withPositionInfo(position)
        is JReturnVoidStmt -> stmt.withPositionInfo(position)
        is JThrowStmt -> stmt.withPositionInfo(position)
        is JNopStmt -> stmt.withPositionInfo(position)
        is JBreakpointStmt -> stmt.withPositionInfo(position)
        is JEnterMonitorStmt -> stmt.withPositionInfo(position)
        is JExitMonitorStmt -> stmt.withPositionInfo(position)
        is JRetStmt -> stmt.withStmtAddress(copyValue(stmt.stmtAddress)).withPositionInfo(position)
        else -> error("Unsupported folded-body statement: ${stmt.javaClass.name}")
    }
}

private fun copyValue(value: Value): Value = when (value) {
    is AbstractInvokeExpr -> copyInvoke(value)
    is JNewMultiArrayExpr -> value.withSizes(Collections.unmodifiableList(ArrayList(value.sizes)))
    else -> value // The remaining supported values have immutable fields and immediate operands.
}

private fun copyInvoke(invoke: AbstractInvokeExpr): AbstractInvokeExpr = when (invoke) {
    is JDynamicInvokeExpr -> JDynamicInvokeExpr(
        invoke.bootstrapMethodSignature, ArrayList(invoke.bootstrapArgs),
        invoke.methodSignature, invoke.handleTag, ArrayList(invoke.args)
    )
    is JStaticInvokeExpr -> invoke.withArgs(ArrayList(invoke.args))
    is AbstractInstanceInvokeExpr -> invoke.withArgs(ArrayList(invoke.args))
    else -> error("Unsupported folded-body invocation: ${invoke.javaClass.name}")
}

/** Exact built-in classes only: a new backend value needs an ownership audit before admission. */
private fun checkValue(value: Value) {
    val type = value.javaClass
    check(type.name in SNAPSHOT_VALUE_CLASSES) { "Unsupported folded-body value: ${type.name}" }
}

private val SNAPSHOT_STATEMENT_CLASSES: Set<String> = buildSet {
    listOf(
        "JAssignStmt", "JInvokeStmt", "JIdentityStmt", "JIfStmt", "JGotoStmt", "JReturnStmt",
        "JReturnVoidStmt", "JThrowStmt", "JNopStmt"
    ).forEach { add("sootup.core.jimple.common.stmt.$it") }
    listOf("JSwitchStmt", "JBreakpointStmt", "JEnterMonitorStmt", "JExitMonitorStmt", "JRetStmt")
        .forEach { add("sootup.core.jimple.javabytecode.stmt.$it") }
}

private val SNAPSHOT_VALUE_CLASSES: Set<String> = buildSet {
    add("sootup.core.jimple.common.Local")
    add("sootup.java.core.jimple.basic.JavaLocal")
    listOf(
        "DoubleConstant", "LongConstant", "FloatConstant", "StringConstant", "ClassConstant",
        "IntConstant", "MethodHandle", "NullConstant", "BooleanConstant", "EnumConstant", "MethodType"
    ).forEach { add("sootup.core.jimple.common.constant.$it") }
    listOf(
        "JInstanceFieldRef", "JThisRef", "JCaughtExceptionRef", "JArrayRef", "JParameterRef", "JStaticFieldRef"
    ).forEach { add("sootup.core.jimple.common.ref.$it") }
    listOf(
        "JStaticInvokeExpr", "JLeExpr", "JXorExpr", "JRemExpr", "JNeExpr", "JLtExpr", "JUshrExpr",
        "JNewExpr", "JEqExpr", "JMulExpr", "JCmplExpr", "JSpecialInvokeExpr", "JNewArrayExpr",
        "JShrExpr", "JSubExpr", "JLengthExpr", "JOrExpr", "JAddExpr", "JVirtualInvokeExpr", "JCastExpr",
        "JGtExpr", "JInstanceOfExpr", "JDynamicInvokeExpr", "JInterfaceInvokeExpr", "JShlExpr", "JGeExpr",
        "JDivExpr", "JNewMultiArrayExpr", "JNegExpr", "JAndExpr", "JCmpgExpr", "JCmpExpr"
    ).forEach { add("sootup.core.jimple.common.expr.$it") }
}

private fun copyPosition(position: Position): Position =
    if (position === NoPositionInformation.getInstance()) position
    else FullPosition(position.firstLine, position.firstCol, position.lastLine, position.lastCol)

private fun copyPositionInfo(info: StmtPositionInfo): StmtPositionInfo = when {
    info === StmtPositionInfo.getNoStmtPositionInfo() -> info
    info.javaClass === SimpleStmtPositionInfo::class.java -> SimpleStmtPositionInfo(copyPosition(info.stmtPosition))
    info.javaClass === FullStmtPositionInfo::class.java -> {
        // SootUp 3.0.1 has no operand-position count or copy accessor. Read its pinned backend
        // field so sparse/trailing operand positions are preserved, then own all values.
        val field = FullStmtPositionInfo::class.java.getDeclaredField("operandPositions")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val positions = field.get(info) as Array<Position?>?
        FullStmtPositionInfo(
            copyPosition(info.stmtPosition), positions?.map { it?.let(::copyPosition) }?.toTypedArray()
        )
    }
    else -> error("Unsupported folded-body position information: ${info.javaClass.name}")
}

private fun copyLocal(local: Local): Local = when (local.javaClass) {
    Local::class.java -> Local(local.name, local.type)
    JavaLocal::class.java -> JavaLocal(local.name, local.type, Collections.unmodifiableList(
        (local as JavaLocal).annotations.map(::copyAnnotation)
    ))
    else -> error("Unsupported folded-body local: ${local.javaClass.name}")
}

private fun copyAnnotation(annotation: AnnotationUsage): AnnotationUsage = AnnotationUsage(
    annotation.annotation, annotation.values.mapValues { (_, value) -> copyAnnotationValue(value) }
)

private fun copyAnnotationValue(value: Any): Any = when (value) {
    is AnnotationUsage -> copyAnnotation(value)
    is List<*> -> Collections.unmodifiableList(value.map { copyAnnotationValue(checkNotNull(it)) })
    is Constant -> value.also(::checkValue)
    is String, is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double, is Char -> value
    else -> error("Unsupported folded-body annotation value: ${value.javaClass.name}")
}
