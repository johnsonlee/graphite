package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import sootup.core.jimple.basic.StmtPositionInfo
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.constant.MethodHandle
import sootup.core.jimple.common.constant.StringConstant as SootStringConstant
import sootup.core.jimple.common.expr.JDynamicInvokeExpr
import sootup.core.jimple.common.ref.JArrayRef
import sootup.core.jimple.common.ref.JStaticFieldRef
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.signatures.MethodSignature
import sootup.java.core.jimple.basic.JavaLocal

internal class AdapterDispatchHelpersTest : AdapterTestFixtures() {
    @Test
    fun `bytecode reference extraction includes invokedynamic bootstrap references`() {
        val adapter = createAdapter(includePackages = listOf("sample.lambda"))
        val references = invokePrivate<Set<String>>(
            adapter,
            "extractReferencedClasses",
            arrayOf(ByteArray::class.java),
            findCompiledClassBytes("sample/lambda/LambdaExample.class")
        )

        assertTrue(references.any { it == "java.lang.invoke.LambdaMetafactory" || it == "sample.lambda.LambdaExample" })

        val descriptorReferences = invokePrivate<Set<String>>(
            adapter,
            "extractReferencedClasses",
            arrayOf(ByteArray::class.java),
            referencedClassBytes()
        )
        assertTrue(descriptorReferences.contains("java.io.Serializable"))
        assertTrue(descriptorReferences.contains("java.util.List"))
        assertTrue(descriptorReferences.contains("java.util.Map"))
        assertTrue(descriptorReferences.contains("java.util.Optional"))
        assertTrue(descriptorReferences.contains("java.io.IOException"))
    }

    @Test
    fun `dynamic invoke helpers resolve locals fields fallback and defining classes`() {
        val adapter = createAdapter(listOf("sample.resources", "sample.resolution", "sample.lambda"), buildCallGraph = true)
        val caller = MethodDescriptor(TypeDescriptor("sample.lambda.LambdaExample"), "useFactory", emptyList(),
            TypeDescriptor("java.lang.String"))
        val argLocal = JavaLocal("input", identifierFactory.getType("java.lang.String"), emptyList())
        val targetSig = identifierFactory.getMethodSignature("sample.lambda.LambdaExample", "transform", "java.lang.String",
            listOf("java.lang.String"))
        val bootstrapSig = identifierFactory.getMethodSignature("java.lang.invoke.LambdaMetafactory", "metafactory",
            "java.lang.Object", emptyList())
        val applySig = identifierFactory.getMethodSignature(JDynamicInvokeExpr.INVOKEDYNAMIC_DUMMY_CLASS_NAME, "apply",
            "java.lang.Object", listOf("java.lang.Object"))
        val methodHandle = MethodHandle(targetSig, MethodHandle.Kind.REF_INVOKE_STATIC, identifierFactory.getType("java.lang.String"))

        val localResult = LocalVariable(NodeId.next(), "fn", TypeDescriptor("java.util.function.Function"), caller)
        invokePrivate<Unit>(
            adapter,
            "processDynamicInvoke",
            arrayOf(JDynamicInvokeExpr::class.java, MethodDescriptor::class.java,
                io.johnsonlee.graphite.core.ValueNode::class.java, sootup.core.jimple.common.stmt.Stmt::class.java),
            JDynamicInvokeExpr(bootstrapSig, listOf(methodHandle), applySig, listOf(argLocal)),
            caller,
            localResult,
            null
        )
        val dynamicTargets = readField<MutableMap<Any, Set<DispatchTarget>>>(adapter, "dynamicTargets")
        val resultKey = invokePrivate<Any>(adapter, "localKey", arrayOf(MethodDescriptor::class.java, String::class.java), caller, "fn")
        val handle = dynamicTargets[resultKey].orEmpty().single() as DispatchTarget.Handle
        assertEquals(invokePrivate<MethodDescriptor>(adapter, "toMethodDescriptor", arrayOf(MethodSignature::class.java),
            targetSig), handle.method)
        assertEquals(HandleKind.STATIC, handle.kind)
        assertEquals("apply", handle.samName)
        assertEquals(1, handle.captures.size)

        val fieldSig = identifierFactory.getFieldSignature("callback",
            identifierFactory.getClassType("sample.lambda.FieldCallbackExample"), identifierFactory.getType("java.lang.Object"))
        val fieldNode = FieldNode(NodeId.next(), FieldDescriptor(TypeDescriptor("sample.lambda.FieldCallbackExample"),
            "callback", TypeDescriptor("java.lang.Object")), true)
        invokePrivate<Unit>(
            adapter,
            "processDynamicInvoke",
            arrayOf(JDynamicInvokeExpr::class.java, MethodDescriptor::class.java,
                io.johnsonlee.graphite.core.ValueNode::class.java, sootup.core.jimple.common.stmt.Stmt::class.java),
            JDynamicInvokeExpr(bootstrapSig, listOf(methodHandle), applySig, listOf(argLocal)),
            caller,
            fieldNode,
            JAssignStmt(JStaticFieldRef(fieldSig), JDynamicInvokeExpr(bootstrapSig, listOf(methodHandle), applySig,
                listOf(argLocal)), StmtPositionInfo.getNoStmtPositionInfo())
        )
        assertTrue(targetsOf(adapter, DispatchSlot.Field(fieldSig.toString())).orEmpty().isNotEmpty())

        invokePrivate<Unit>(
            adapter,
            "processDynamicInvoke",
            arrayOf(JDynamicInvokeExpr::class.java, MethodDescriptor::class.java,
                io.johnsonlee.graphite.core.ValueNode::class.java, sootup.core.jimple.common.stmt.Stmt::class.java),
            JDynamicInvokeExpr(bootstrapSig, listOf(SootStringConstant("template",
                identifierFactory.getType("java.lang.String"))), applySig, emptyList()),
            caller,
            null,
            null
        )
        val builtGraph = readField<DefaultGraph.Builder>(adapter, "graphBuilder").build()
        assertTrue(builtGraph.nodes(io.johnsonlee.graphite.core.CallSiteNode::class.java).any { it.callee.name == "apply" })

        val concreteProcess = identifierFactory.getMethodSignature("sample.resolution.ConcreteService", "process", "int", listOf("int"))
        val defaultGreet = identifierFactory.getMethodSignature("sample.resolution.FormalGreeter", "greet",
            "java.lang.String", listOf("java.lang.String"))
        assertEquals("sample.resolution.ConcreteService", invokePrivate<MethodSignature>(adapter,
            "resolveMethodDefiningClass", arrayOf(MethodSignature::class.java), concreteProcess).declClassType.fullyQualifiedName)
        assertEquals("sample.resolution.Greeter", invokePrivate<MethodSignature>(adapter, "resolveMethodDefiningClass",
            arrayOf(MethodSignature::class.java), defaultGreet).declClassType.fullyQualifiedName)
    }

    @Test
    fun `functional dispatch propagation resolves return parameter and unresolved locals`() {
        val adapter = createAdapter(listOf("sample.lambda"), buildCallGraph = true)
        val caller = MethodDescriptor(TypeDescriptor("sample.lambda.CallbackExample"), "useCallback", emptyList(),
            TypeDescriptor("java.lang.String"))
        val callee = MethodDescriptor(TypeDescriptor("sample.lambda.CallbackExample"), "processWithCallback", emptyList(),
            TypeDescriptor("java.lang.String"))
        val target = MethodDescriptor(TypeDescriptor("sample.lambda.CallbackExample"), "transform",
            listOf(TypeDescriptor("java.lang.String")), TypeDescriptor("java.lang.String"))
        val apply = MethodDescriptor(TypeDescriptor("java.util.function.Function"), "apply",
            listOf(TypeDescriptor("java.lang.Object")), TypeDescriptor("java.lang.Object"))
        val handle = DispatchTarget.Handle(target, HandleKind.STATIC, "apply", emptyList())
        val otherSam = DispatchTarget.Handle(target, HandleKind.STATIC, "get", emptyList())
        val anonymous = DispatchTarget.FunctionObject("sample.lambda.AnonymousClassExample\$1")
        val adapted = DispatchTarget.Adapted("apply", apply, listOf(NodeId.next()), handle)
        val otherAdapted = DispatchTarget.Adapted("get", apply, emptyList(), handle)
        val applyCharSequence = apply.copy(parameterTypes = listOf(TypeDescriptor("java.lang.CharSequence")))
        val bridgeCall = io.johnsonlee.graphite.core.CallSiteNode(NodeId.next(), caller, applyCharSequence, 12,
            NodeId.next(), listOf(NodeId.next()))
        val parameterCall = io.johnsonlee.graphite.core.CallSiteNode(NodeId.next(), callee, apply, 10, NodeId.next(), listOf(NodeId.next()))
        val fieldLocalCall = io.johnsonlee.graphite.core.CallSiteNode(NodeId.next(), caller, apply, 11, NodeId.next(),
            listOf(NodeId.next()))
        val parameter = DispatchSlot.Parameter(callee, 0)
        val result = DispatchSlot.Local(caller, "result")
        val field = DispatchSlot.Field("sample.lambda.CallbackExample.callback")
        val fieldLocal = DispatchSlot.Local(caller, "fieldLocal")

        setTargets(adapter, DispatchSlot.Return(callee), linkedSetOf(handle))
        flow(adapter, DispatchSlot.Return(callee), result)
        setTargets(adapter, parameter, linkedSetOf(handle, otherSam, anonymous))
        waitOn(adapter, parameter, PendingDispatch(parameterCall, NodeId.next(), listOf(null), null, mutableSetOf()))
        flow(adapter, field, fieldLocal)
        setTargets(adapter, field, linkedSetOf(handle, adapted, otherAdapted))
        waitOn(adapter, fieldLocal, PendingDispatch(fieldLocalCall, null, listOf(null), null, mutableSetOf(handle)))
        // no method with the invoked erasure: the bridge with the same name and arity stands in
        val bridgeSlot = DispatchSlot.Local(caller, "bridged")
        setTargets(adapter, bridgeSlot, linkedSetOf(anonymous))
        waitOn(adapter, bridgeSlot, PendingDispatch(bridgeCall, null, listOf(null), null, mutableSetOf()))

        invokePrivate<Unit>(adapter, "resolveFunctionalDispatch", emptyArray())

        assertEquals<Set<DispatchTarget>?>(setOf(handle), targetsOf(adapter, result))
        assertEquals<Set<DispatchTarget>?>(setOf(handle, adapted, otherAdapted), targetsOf(adapter, fieldLocal))
        val resolved = readField<DefaultGraph.Builder>(adapter, "graphBuilder").build()
            .nodes(io.johnsonlee.graphite.core.CallSiteNode::class.java).toList()
        // parameter: the handle and the anonymous class's apply bridge resolve, the `get` handle does not
        assertEquals(
            setOf("sample.lambda.CallbackExample.transform", "sample.lambda.AnonymousClassExample\$1.apply"),
            resolved.filter { it.caller == callee }.map { "${it.callee.declaringClass.className}.${it.callee.name}" }.toSet()
        )
        // field local: the handle was already resolved in-method, only the adapted target is new;
        // the bridged call reaches the anonymous class's erased apply(Object)
        assertEquals(
            setOf("sample.lambda.CallbackExample.transform(java.lang.String)",
                "sample.lambda.AnonymousClassExample\$1.apply(java.lang.Object)"),
            resolved.filter { it.caller == caller }.map { it.callee.signature }.toSet()
        )
    }

    /**
     * A dex body keeps the compiler's registers as locals, each reused for many values: one
     * register receives function object after function object, and the body copies it into
     * an array and other registers between every two. The targets a local holds must stay a
     * set through all of that, or the array targets double on every round trip between two
     * registers and one large method exhausts the heap (a 6,250-statement Android method did).
     */
    @Test
    fun `copies between reused locals keep dispatch targets de-duplicated`() {
        val adapter = createAdapter(listOf("sample.lambda"))
        val method = MethodDescriptor(TypeDescriptor("sample.lambda.AnonymousClassExample"), "reuse", emptyList(), TypeDescriptor("void"))
        val objectType = identifierFactory.getType("java.lang.Object")
        val register = JavaLocal("\$u0", objectType, emptyList())
        val other = JavaLocal("\$u1", objectType, emptyList())
        val array = JavaLocal("\$stack", identifierFactory.getArrayType(objectType, 1), emptyList())
        val targets = (1..8).map { DispatchTarget.FunctionObject("sample.lambda.AnonymousClassExample\$$it") }
        val dynamicTargets = readField<MutableMap<Any, Set<DispatchTarget>>>(adapter, "dynamicTargets")
        val arrayDynamicTargets = readField<MutableMap<Any, Set<DispatchTarget>>>(adapter, "arrayDynamicTargets")
        fun keyOf(local: Local): Any =
            invokePrivate(adapter, "localKey", arrayOf(MethodDescriptor::class.java, String::class.java), method, local.name)
        fun copy(source: Local, target: Local) =
            invokePrivate<Unit>(adapter, "copyFunctionValue", arrayOf(MethodDescriptor::class.java, Local::class.java,
                Local::class.java), method, source, target)

        targets.forEach { target ->
            invokePrivate<Unit>(adapter, "mergeLocalTargets", arrayOf(MethodDescriptor::class.java, Local::class.java,
                Collection::class.java), method, register, listOf(target))
            val store = JAssignStmt(JArrayRef(array, IntConstant.getInstance(0)), register, StmtPositionInfo.getNoStmtPositionInfo())
            invokePrivate<Unit>(adapter, "trackFunctionValueAssignment", arrayOf(JAssignStmt::class.java,
                MethodDescriptor::class.java, io.johnsonlee.graphite.core.ValueNode::class.java), store, method, null)
            repeat(4) {
                copy(register, other)
                copy(other, register)
                copy(array, other)
                copy(other, array)
            }
        }

        assertEquals(targets.toSet(), dynamicTargets[keyOf(register)])
        assertEquals(targets.toSet(), dynamicTargets[keyOf(other)])
        assertEquals(targets.toSet(), arrayDynamicTargets[keyOf(array)])
        assertEquals(targets.toSet(), arrayDynamicTargets[keyOf(other)])
        // a copy that brings nothing new keeps the set the target already holds
        val held = dynamicTargets[keyOf(other)]
        copy(register, other)
        assertSame(held, dynamicTargets[keyOf(other)])
    }
}
