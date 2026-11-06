package io.johnsonlee.graphite.sootup

import java.lang.reflect.Method

/** Binds a white-box assertion to the build stage that owns the implementation. */
internal class AdapterOperation(private val component: Any, private val method: Method) {
    var isAccessible: Boolean = false
        set(value) {
            method.isAccessible = value
            field = value
        }

    fun invoke(target: Any, vararg args: Any?): Any? =
        method.invoke(if (target is SootUpAdapter) component else target, *args)
}

internal fun SootUpAdapter.operation(name: String, vararg parameterTypes: Class<*>): AdapterOperation =
    operationFor(this, name, *parameterTypes)

internal fun operationFor(target: Any, name: String, vararg parameterTypes: Class<*>): AdapterOperation {
    val candidates = if (target is SootUpAdapter) listOf(
        target,
        target.bodies,
        target.bundleControl,
        target.bundlePaths,
        target.bytecodeMethods,
        target.callKinds,
        target.calls,
        target.completeness,
        target.construction,
        target.controlFlow,
        target.declarations,
        target.descriptors,
        target.dispatchFlows,
        target.dispatchHierarchy,
        target.dispatchResolution,
        target.functionObjects,
        target.literals,
        target.localeValues,
        target.locales,
        target.metadata,
        target.methodSources,
        target.reachability,
        target.resourceReads,
        target.resources,
        target.values,
    ) else listOf(target)
    val component = candidates.firstOrNull { candidate ->
        candidate.javaClass.declaredMethods.any { it.name == name && it.parameterTypes.contentEquals(parameterTypes) }
    } ?: error("No build stage declares $name(${parameterTypes.joinToString { it.simpleName }})")
    return AdapterOperation(component, component.javaClass.getDeclaredMethod(name, *parameterTypes))
}
