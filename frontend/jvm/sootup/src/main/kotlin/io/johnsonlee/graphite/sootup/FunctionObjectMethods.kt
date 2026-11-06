package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.expr.AbstractInvokeExpr
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.model.SootClass
import java.lang.reflect.Modifier
import sootup.core.model.ClassModifier
import sootup.core.model.MethodModifier
import sootup.core.model.SootMethod

/** Resolves implementations and bridges of SAM, lambda and callable-reference objects. */
internal class FunctionObjectMethods(private val context: SootUpAdapter) {
    private val functionObjectClasses = context.functionObjectClasses
    private val supertypeContractsByClass = context.supertypeContractsByClass
    private val bridgesInvokingByClass = context.bridgesInvokingByClass
    private val implementedByFunctionValue = context.implementedByFunctionValue
    private val view = context.view
    private val graphBuilder = context.graphBuilder
    private val bodies get() = context.bodies
    private val descriptors get() = context.descriptors
    private val dispatchFlows get() = context.dispatchFlows
    private val dispatchHierarchy get() = context.dispatchHierarchy
    private val metadata get() = context.metadata
    private val methodSources get() = context.methodSources
    private val resources get() = context.resources

    /**
     * Whether instances of [className] are function values whose class pins down the
     * implementation: a class without a source name, which only exists to be handed around as
     * a value (an anonymous class, `object :`, a Kotlin lambda, callable reference or SAM
     * wrapper class, or a synthetic class implementing an interface, which is how D8/R8
     * desugar lambdas, even after R8 renames them), or a class implementing a Kotlin function
     * type or extending a Kotlin function base class.
     */
    fun isFunctionObjectClass(className: String): Boolean = functionObjectClasses.getOrPut(className) {
        val sootClass = resources.resolveClassByName(className)
        sootClass != null && !sootClass.isInterface && !sootClass.isAbstract && (
            isAnonymousClassName(className) ||
                (ClassModifier.isSynthetic(sootClass.modifiers) && sootClass.interfaces.isNotEmpty()) ||
                sootClass.interfaces.any { isKotlinFunctionInterface(it.fullyQualifiedName) } ||
                dispatchHierarchy.superclassNames(sootClass).any(::isKotlinFunctionBaseClass)
            )
    }

    /**
     * A function object of class [className] was created (`new Foo$bar$1(...)`) or loaded from
     * its singleton (`Foo$bar$1.INSTANCE`) into [local]: calls on [local] dispatch to the class's
     * methods, and, as for an `invokedynamic`, the creating method gets a dynamic call site to
     * each method the class implements for a supertype, so the lambda body stays reachable when
     * the call that runs it happens in code outside the graph (`lazy {}`, `Executor.execute`,
     * ...). Methods the class adds on its own (`new Object() { void helper() {} }`) are only
     * reachable through calls the graph already records.
     */
    fun trackFunctionObject(
        method: MethodDescriptor,
        local: Local,
        className: String,
        receiver: NodeId,
        stmt: Stmt
    ) {
        if (!isFunctionObjectClass(className)) return
        dispatchFlows.mergeLocalTargets(method, local, listOf(DispatchTarget.FunctionObject(className)))
        val sootClass = resources.resolveClassByName(className) ?: return
        dispatchHierarchy.methodsInSignatureOrder(sootClass)
            .filter { !it.isStatic && !it.isAbstract && it.name != INIT_METHOD && !MethodModifier.isBridge(it.modifiers) }
            .filter { implementsSupertypeMethod(sootClass, it) }
            .forEach { body ->
                val callee = descriptors.toMethodDescriptor(body)
                val callSite = CallSiteNode(
                    id = metadata.nextNodeId("call"),
                    caller = method,
                    callee = callee,
                    lineNumber = null,
                    receiver = receiver,
                    arguments = emptyList(),
                    ordinal = descriptors.nextDerivedCallOrdinal(method, callee)
                )
                graphBuilder.addNode(callSite)
                bodies.recordStmtNode(stmt, callSite.id)
                graphBuilder.addEdge(CallEdge(from = callSite.id, to = callSite.id, isVirtual = false, isDynamic = true))
            }
    }

    /**
     * Whether [method] implements or overrides a method of one of [sootClass]'s supertypes, by
     * name and arity, so that the erased bridge is skipped but the typed implementation kept.
     * A supertype outside the view (`java.lang.Runnable`, `kotlin.jvm.functions.Function1`) is
     * inspected through the analysis JVM's own copy of it when it has one; only a supertype
     * that cannot be inspected at all lets any non-private method count.
     */
    fun implementsSupertypeMethod(sootClass: SootClass, method: SootMethod): Boolean {
        if (MethodModifier.isPrivate(method.modifiers)) return false
        // The erased signatures under which this method can be called through a supertype:
        // its own, and that of a bridge the compiler emitted for it (`apply(Object)` for
        // `apply(String)`), which javac and kotlinc only generate for overriding methods
        val signatures = listOf(erasedSignature(method)) + bridgesInvoking(sootClass)[method.subSignature.toString()].orEmpty()
        return dispatchHierarchy.supertypes(sootClass.type.fullyQualifiedName).any { supertype ->
            supertypeContracts(supertype)?.any(signatures::contains) ?: true
        }
    }

    /**
     * For each method of [sootClass] that a bridge delegates to, keyed by sub-signature, the
     * erased signatures of those bridges. A bridge's body is a cast and one call to its
     * target, so the call names it; an overload sharing the name and arity (`apply(Integer)`
     * next to `apply(String)`) is not the bridge's target and gets nothing.
     */
    fun bridgesInvoking(sootClass: SootClass): Map<String, List<String>> =
        bridgesInvokingByClass.getOrPut(sootClass.type.fullyQualifiedName) {
            dispatchHierarchy.methodsInSignatureOrder(sootClass)
                .filter { MethodModifier.isBridge(it.modifiers) && it.hasBody() }
                .flatMap { bridge ->
                    methodSources.bridgeBody(bridge).controlFlowGraph.stmts.asSequence()
                        .mapNotNull { stmt ->
                            when (stmt) {
                                is JInvokeStmt -> stmt.invokeExpr.orElse(null)
                                is JAssignStmt -> stmt.rightOp as? AbstractInvokeExpr
                                else -> null
                            }?.methodSignature
                        }
                        .filter { it.declClassType == sootClass.type && it.name == bridge.name }
                        .map { it.subSignature.toString() to erasedSignature(bridge) }
                        .toList()
                }
                .groupBy({ it.first }, { it.second })
        }

    fun erasedSignature(method: SootMethod): String =
        dispatchHierarchy.methodKey(method.name, method.parameterTypes.map { descriptors.toTypeDescriptor(it).className })

    /**
     * The erased signatures a subclass of [className] can implement or override: instance
     * methods that are neither private, static nor final (`Function.identity()` is static, so
     * an instance `identity()` helper implements nothing). A class in the view is read from it;
     * one outside it (JDK, Kotlin stdlib) through the analysis JVM's own copy, loaded without
     * initialization, public methods inherited included. Null when neither has the class.
     */
    fun supertypeContracts(className: String): Set<String>? = supertypeContractsByClass.getOrPut(className) {
        resources.resolveClassByName(className)?.let { sootClass ->
            dispatchHierarchy.methodsInSignatureOrder(sootClass)
                .filter { !it.isStatic && !MethodModifier.isPrivate(it.modifiers) && !MethodModifier.isFinal(it.modifiers) }
                .map(::erasedSignature)
                .toSet()
        } ?: runCatching { Class.forName(className, false, SootUpAdapter::class.java.classLoader) }.getOrNull()?.let { clazz ->
            (clazz.methods.asSequence() + clazz.declaredMethods.asSequence())
                .filter { dispatchHierarchy.isOverridable(it.modifiers) }
                .map { dispatchHierarchy.methodKey(it.name, it.parameterTypes.map { type -> type.typeName }) }
                .toSet()
        }
    }

    /**
     * Whether [invoked] is a method a function value implements, so that a call to it runs the
     * implementation: an abstract method of its declaring type. A default method with the same
     * name (`Extra.apply(Integer)` next to `Function.apply(Object)`, `Function.andThen`) runs
     * its own body. A method that neither the view nor the analysis JVM can inspect is assumed
     * abstract.
     */
    fun isImplementedByFunctionValue(invoked: MethodDescriptor): Boolean = implementedByFunctionValue.getOrPut(invoked) {
        val className = invoked.declaringClass.className
        resources.resolveClassByName(className)?.let { dispatchHierarchy.declaredMethod(className, invoked)?.isAbstract ?: true }
            ?: runCatching { Class.forName(className, false, SootUpAdapter::class.java.classLoader) }.getOrNull()?.let { clazz ->
                val signature = dispatchHierarchy.methodKey(invoked.name, invoked.parameterTypes.map { it.className })
                (clazz.methods.asSequence() + clazz.declaredMethods.asSequence())
                    .firstOrNull { dispatchHierarchy.methodKey(it.name, it.parameterTypes.map { type -> type.typeName }) == signature }
                    ?.let { Modifier.isAbstract(it.modifiers) } ?: true
            } ?: true
    }
}
