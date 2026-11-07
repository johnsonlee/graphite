package io.johnsonlee.graphite.sootup

import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
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
import sootup.core.jimple.common.constant.BooleanConstant
import sootup.core.jimple.common.constant.ClassConstant
import sootup.core.jimple.common.constant.Constant
import sootup.core.jimple.common.constant.DoubleConstant
import sootup.core.jimple.common.constant.EnumConstant
import sootup.core.jimple.common.constant.FloatConstant
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.constant.LongConstant
import sootup.core.jimple.common.constant.MethodHandle
import sootup.core.jimple.common.constant.MethodType
import sootup.core.jimple.common.constant.NullConstant
import sootup.core.jimple.common.constant.StringConstant
import sootup.core.jimple.common.expr.AbstractBinopExpr
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr
import sootup.core.jimple.common.expr.AbstractUnopExpr
import sootup.core.jimple.common.expr.AbstractInvokeExpr
import sootup.core.jimple.common.expr.JCastExpr
import sootup.core.jimple.common.expr.JDynamicInvokeExpr
import sootup.core.jimple.common.expr.JInstanceOfExpr
import sootup.core.jimple.common.expr.JNewArrayExpr
import sootup.core.jimple.common.expr.JNewExpr
import sootup.core.jimple.common.expr.JNewMultiArrayExpr
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.jimple.common.ref.JArrayRef
import sootup.core.jimple.common.ref.JCaughtExceptionRef
import sootup.core.jimple.common.ref.JFieldRef
import sootup.core.jimple.common.ref.JInstanceFieldRef
import sootup.core.jimple.common.ref.JParameterRef
import sootup.core.jimple.common.ref.JThisRef
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
import sootup.core.signatures.MethodSignature
import sootup.core.signatures.SootClassMemberSignature
import sootup.core.types.ArrayType
import sootup.core.types.ClassType
import sootup.core.types.Type
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
    /** Used only to verify opted-in duplicate-class rematerialization; never retains another body. */
    internal fun semanticFingerprint(): String = fingerprint { writeBody(body) }

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

/** A typed, length-delimited codec for comparing repeated observations, not a public IR format. */
private fun fingerprint(write: BodyFingerprintWriter.() -> Unit): String {
    val digest = MessageDigest.getInstance("SHA-256")
    DataOutputStream(DigestOutputStream(OutputStream.nullOutputStream(), digest)).use {
        BodyFingerprintWriter(it).write()
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

@Suppress("TooManyFunctions") // Each supported IR component has one typed serialization boundary.
private class BodyFingerprintWriter(private val out: DataOutputStream) {
    fun writeBody(body: Body) {
        writeString("graphite-folded-body-v1")
        writeSignature(body.methodSignature)
        writePosition(body.position)
        val locals = body.locals.sortedBy { fingerprint { writeValue(it) } }
        out.writeInt(locals.size)
        locals.forEach(::writeValue)
        val graph = body.controlFlowGraph
        val statements = orderedStatements(body)
        val indices = IdentityHashMap<Stmt, Int>()
        statements.forEachIndexed { index, stmt -> indices[stmt] = index }
        fun index(stmt: Stmt): Int = checkNotNull(indices[stmt]) { "Fingerprint edge leaves the body" }
        out.writeInt(graph.startingStmt?.let(::index) ?: -1)
        out.writeInt(statements.size)
        statements.forEach { stmt ->
            writeStatement(stmt)
            val successors = graph.successors(stmt)
            out.writeInt(successors.size)
            successors.forEach { out.writeInt(index(it)) }
            val exceptional = graph.exceptionalSuccessors(stmt).entries.sortedBy { it.key.fullyQualifiedName }
            out.writeInt(exceptional.size)
            exceptional.forEach { (exception, target) -> writeType(exception); out.writeInt(index(target)) }
        }
        val blocks = graph.blocks.sortedBy { block -> block.stmts.minOf(::index) }
        out.writeInt(blocks.size)
        blocks.forEach { block ->
            out.writeInt(block.stmtCount)
            block.stmts.forEach { out.writeInt(index(it)) }
            out.writeInt(block.successors.size)
            block.successors.forEach { out.writeInt(index(it.head)) }
            val exceptional = block.exceptionalSuccessors.entries.sortedBy { it.key.fullyQualifiedName }
            out.writeInt(exceptional.size)
            exceptional.forEach { (exception, target) -> writeType(exception); out.writeInt(index(target.head)) }
        }
    }

    /** Preserve successor slots, while disregarding incidental block-enumeration order. */
    private fun orderedStatements(body: Body): List<Stmt> {
        val graph = body.controlFlowGraph
        val ordered = mutableListOf<Stmt>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Stmt, Boolean>())
        fun admit(stmt: Stmt) { if (seen.add(stmt)) ordered.add(stmt) }
        var next = 0
        fun drain() {
            while (next < ordered.size) {
                val stmt = ordered[next++]
                graph.successors(stmt).forEach(::admit)
                graph.exceptionalSuccessors(stmt).entries.sortedBy { it.key.fullyQualifiedName }
                    .forEach { admit(it.value) }
            }
        }
        graph.startingStmt?.let(::admit)
        drain()
        // A supported immutable body may also enumerate unreachable blocks. Include all of
        // them; instruction keys make their ordering independent of object identity where possible.
        graph.nodes.filter { it !in seen }.sortedBy { fingerprint { writeStatement(it) } }
            .forEach { admit(it); drain() }
        return ordered
    }

    @Suppress("CyclomaticComplexMethod") // Exhaustive supported typed statements; unknown instructions fail closed.
    private fun writeStatement(stmt: Stmt) {
        writeString(stmt.javaClass.name)
        writePositionInfo(stmt.positionInfo)
        when (stmt) {
            is JAssignStmt -> { writeValue(stmt.leftOp); writeValue(stmt.rightOp) }
            is JInvokeStmt -> writeValue(stmt.invokeExpr.orElseThrow())
            is JIdentityStmt -> { writeValue(stmt.leftOp); writeValue(stmt.rightOp) }
            is JIfStmt -> writeValue(stmt.condition)
            is JSwitchStmt -> {
                writeValue(stmt.key)
                out.writeBoolean(stmt.isTableSwitch)
                out.writeInt(stmt.values.size)
                stmt.values.forEach { out.writeInt(it.value) }
            }
            is JReturnStmt -> writeValue(stmt.op)
            is JThrowStmt -> writeValue(stmt.op)
            is JRetStmt -> writeValue(stmt.stmtAddress)
            is JEnterMonitorStmt -> writeValue(stmt.op)
            is JExitMonitorStmt -> writeValue(stmt.op)
            is JGotoStmt, is JReturnVoidStmt, is JNopStmt, is JBreakpointStmt -> Unit
            else -> error("Unsupported folded-body fingerprint statement: ${stmt.javaClass.name}")
        }
    }

    @Suppress("CyclomaticComplexMethod") // The finite supported value vocabulary is serialized without textual IR dumps.
    private fun writeValue(value: Value) {
        checkValue(value)
        writeString(value.javaClass.name)
        writeType(value.type)
        when (value) {
            is Local -> {
                writeString(value.name)
                val annotations = (value as? JavaLocal)?.annotations?.toList().orEmpty()
                out.writeInt(annotations.size)
                annotations.forEach(::writeAnnotation)
            }
            is Constant -> writeConstant(value)
            is AbstractInvokeExpr -> writeInvocation(value)
            is AbstractBinopExpr -> { writeValue(value.op1); writeValue(value.op2) }
            is AbstractUnopExpr -> writeValue(value.op)
            is JCastExpr -> writeValue(value.op)
            is JInstanceOfExpr -> { writeValue(value.op); writeType(value.checkType) }
            is JNewArrayExpr -> { writeType(value.baseType); writeValue(value.size) }
            is JNewMultiArrayExpr -> {
                writeType(value.baseType)
                out.writeInt(value.sizes.size)
                value.sizes.forEach(::writeValue)
            }
            is JFieldRef -> {
                writeSignature(value.fieldSignature)
                if (value is JInstanceFieldRef) writeValue(value.base)
            }
            is JArrayRef -> { writeValue(value.base); writeValue(value.index) }
            is JParameterRef -> out.writeInt(value.index)
            is JThisRef, is JCaughtExceptionRef, is JNewExpr -> Unit // Their complete payload is their type.
            else -> error("Unsupported folded-body fingerprint value: ${value.javaClass.name}")
        }
    }

    private fun writeInvocation(invoke: AbstractInvokeExpr) {
        writeSignature(invoke.methodSignature)
        if (invoke is AbstractInstanceInvokeExpr) writeValue(invoke.base)
        out.writeInt(invoke.args.size)
        invoke.args.forEach(::writeValue)
        if (invoke is JDynamicInvokeExpr) {
            out.writeInt(invoke.handleTag)
            writeSignature(invoke.bootstrapMethodSignature)
            out.writeInt(invoke.bootstrapArgs.size)
            invoke.bootstrapArgs.forEach(::writeValue)
        }
    }

    private fun writeConstant(constant: Constant) {
        when (constant) {
            is IntConstant -> out.writeInt(constant.value)
            is LongConstant -> out.writeLong(constant.value)
            is FloatConstant -> out.writeInt(constant.value.toRawBits())
            is DoubleConstant -> out.writeLong(constant.value.toRawBits())
            is BooleanConstant -> out.writeBoolean(constant.value)
            is StringConstant -> writeString(constant.value)
            is ClassConstant -> writeString(constant.value)
            is EnumConstant -> writeString(constant.value)
            is MethodHandle -> { writeString(constant.kind.name); writeSignature(constant.referenceSignature) }
            is MethodType -> {
                out.writeInt(constant.parameterTypes.size)
                constant.parameterTypes.forEach(::writeType)
                writeType(constant.returnType)
            }
            is NullConstant -> Unit
            else -> error("Unsupported folded-body fingerprint constant: ${constant.javaClass.name}")
        }
    }

    private fun writeSignature(signature: SootClassMemberSignature<*>) {
        writeString(signature.javaClass.name)
        writeType(signature.declClassType)
        writeString(signature.name)
        writeType(signature.type)
        if (signature is MethodSignature) {
            out.writeInt(signature.parameterTypes.size)
            signature.parameterTypes.forEach(::writeType)
        }
    }

    private fun writeType(type: Type) {
        writeString(type.javaClass.name)
        when (type) {
            is ClassType -> writeString(type.fullyQualifiedName)
            is ArrayType -> { out.writeInt(type.dimension); writeType(type.baseType) }
            else -> writeString(type.toString()) // Primitive, void, null and frontend inference types have no operands.
        }
    }

    private fun writePosition(position: Position?) {
        out.writeBoolean(position != null)
        if (position != null) {
            out.writeInt(position.firstLine)
            out.writeInt(position.firstCol)
            out.writeInt(position.lastLine)
            out.writeInt(position.lastCol)
        }
    }

    private fun writePositionInfo(info: StmtPositionInfo) {
        writeString(info.javaClass.name)
        writePosition(info.stmtPosition)
        if (info is FullStmtPositionInfo) {
            val field = FullStmtPositionInfo::class.java.getDeclaredField("operandPositions")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val positions = field.get(info) as Array<Position?>?
            out.writeInt(positions?.size ?: -1)
            positions?.forEach(::writePosition)
        }
    }

    private fun writeAnnotation(annotation: AnnotationUsage) {
        writeType(annotation.annotation)
        val values = annotation.values.toSortedMap()
        out.writeInt(values.size)
        values.forEach { (name, value) -> writeString(name); writeAnnotationValue(value) }
    }

    private fun writeAnnotationValue(value: Any) {
        writeString(value.javaClass.name)
        when (value) {
            is AnnotationUsage -> writeAnnotation(value)
            is List<*> -> {
                out.writeInt(value.size)
                value.forEach { writeAnnotationValue(checkNotNull(it)) }
            }
            is Constant -> writeValue(value)
            is Float -> out.writeInt(value.toRawBits())
            is Double -> out.writeLong(value.toRawBits())
            is String, is Boolean, is Byte, is Short, is Int, is Long, is Char -> writeString(value.toString())
            else -> error("Unsupported folded-body fingerprint annotation value: ${value.javaClass.name}")
        }
    }

    private fun writeString(value: String) {
        // JVM strings can contain unpaired UTF-16 surrogates. Charset encoders replace
        // those, so encode the original code units rather than an optionally lossy charset.
        out.writeInt(value.length)
        out.writeChars(value)
    }
}
