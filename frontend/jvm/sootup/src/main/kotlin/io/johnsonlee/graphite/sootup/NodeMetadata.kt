package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.analysis.DispatchTarget

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import sootup.core.model.SootClass
import io.johnsonlee.graphite.sootup.SootUpAdapter.LocalKey
import io.johnsonlee.graphite.sootup.SootUpAdapter.ParameterBinding

/** Maintains canonical local bindings, annotations and method-state cleanup. */
internal class NodeMetadata(private val context: SootUpAdapter) {
    private val localNodes = context.localNodes
    private val parameterNodes = context.parameterNodes
    private val methodReturnNodes = context.methodReturnNodes
    private val allocationNodes = context.allocationNodes
    private val dynamicTargets = context.dynamicTargets
    private val localToParamIndex = context.localToParamIndex
    private val arrayDynamicTargets = context.arrayDynamicTargets
    private val slots = context.slots
    private val localeSpecsByLocal = context.localeSpecsByLocal
    private val localeBuilderSpecsByLocal = context.localeBuilderSpecsByLocal
    private val stringValuesByLocal = context.stringValuesByLocal
    private val bundleControlFormatsByLocal = context.bundleControlFormatsByLocal
    private val resourceHandlePathsByLocal = context.resourceHandlePathsByLocal
    private val propertiesPathsByLocal = context.propertiesPathsByLocal
    private val resourceBundlePaths = context.resourceBundlePaths
    private val bundleControlSpecsByLocal = context.bundleControlSpecsByLocal
    private var stmtNodeIds
        get() = context.stmtNodeIds
        set(value) { context.stmtNodeIds = value }
    private var stmtLocalWrites
        get() = context.stmtLocalWrites
        set(value) { context.stmtLocalWrites = value }
    private var stmtIdentityWrites
        get() = context.stmtIdentityWrites
        set(value) { context.stmtIdentityWrites = value }
    private var activeMethod
        get() = context.activeMethod
        set(value) { context.activeMethod = value }
    private var activeMethodLocals
        get() = context.activeMethodLocals
        set(value) { context.activeMethodLocals = value }
    private var bodyCallOrdinals
        get() = context.bodyCallOrdinals
        set(value) { context.bodyCallOrdinals = value }
    private var activeLocalKeysByName
        get() = context.activeLocalKeysByName
        set(value) { context.activeLocalKeysByName = value }
    private var activeMethodParameters
        get() = context.activeMethodParameters
        set(value) { context.activeMethodParameters = value }
    private var activeParameterBindingsByIndex
        get() = context.activeParameterBindingsByIndex
        set(value) { context.activeParameterBindingsByIndex = value }
    private val config = context.config
    private val graphBuilder = context.graphBuilder

    fun extractAnnotations(annotations: Iterable<*>, className: String, memberName: String) {
        for (annot in annotations) {
            val fullName = getAnnotationFullName(annot)
            if (fullName.isEmpty()) continue

            val values = getAnnotationValues(annot)
            val cleanValues = mutableMapOf<String, Any?>()
            for ((key, value) in values) {
                cleanValues[key] = normalizeAnnotationValue(value)
            }
            graphBuilder.addMemberAnnotation(className, memberName, fullName, cleanValues)
            graphBuilder.addNode(AnnotationNode(
                id = NodeId.next(),
                name = fullName,
                className = className,
                memberName = memberName,
                values = cleanValues
            ))
        }
    }

    fun normalizeAnnotationValue(value: Any?): Any? = when (value) {
        null -> null
        is String -> value.removeSurrounding("\"").takeIf { it.isNotEmpty() }
        is Int, is Long, is Float, is Double, is Boolean -> value
        is List<*> -> value.mapNotNull { normalizeAnnotationValue(it) }.takeIf { it.isNotEmpty() }
        is Array<*> -> value.mapNotNull { normalizeAnnotationValue(it) }.takeIf { it.isNotEmpty() }
        else -> value.toString()
            .removeSurrounding("\"")
            .removeSurrounding("[", "]")
            .removeSurrounding("\"")
            .takeIf { it.isNotEmpty() && it != "null" }
    }

    fun getAnnotationFullName(annot: Any?): String {
        if (annot == null) return ""
        return try {
            val annotationProp = annot::class.java.getMethod("getAnnotation").invoke(annot)
            annotationProp?.let {
                it::class.java.getMethod("getFullyQualifiedName").invoke(it)?.toString() ?: ""
            } ?: ""
        } catch (_: ReflectiveOperationException) {
            ""
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun getAnnotationValues(annot: Any?): Map<String, Any?> {
        if (annot == null) return emptyMap()
        return try {
            annot::class.java.getMethod("getValues").invoke(annot) as? Map<String, Any?> ?: emptyMap()
        } catch (_: ReflectiveOperationException) {
            emptyMap()
        }
    }

    fun shouldIncludeClass(sootClass: SootClass): Boolean {
        val className = sootClass.type.toString()
        val excluded = config.excludePackages.any(className::startsWith)
        val included = config.includePackages.isEmpty() || config.includePackages.any(className::startsWith)
        return !excluded && included
    }

    @Suppress("UNUSED_PARAMETER")
    fun nextNodeId(prefix: String): NodeId = NodeId.next()

    fun localKey(method: MethodDescriptor, localName: String): LocalKey {
        if (activeMethod === method) {
            return activeLocalKeysByName.getOrPut(localName) {
                LocalKey(method, localName).also(activeMethodLocals::add)
            }
        }
        return LocalKey(method, localName)
    }

    fun parameterBinding(method: MethodDescriptor, index: Int): ParameterBinding {
        if (activeMethod === method) {
            return activeParameterBindingsByIndex.getOrPut(index) {
                ParameterBinding(method, index).also(activeMethodParameters::add)
            }
        }
        return ParameterBinding(method, index)
    }

    /**
     * [existing] with [newTargets] appended: [existing] itself when it already holds them all
     * (or is saturated), [newTargets] itself when there is nothing yet or it holds everything
     * in [existing], so a copy between locals or slots that hold the same function values
     * (every copy in a dex body, whose registers are reused; an interface method's parameter
     * reaching every implementation's) allocates nothing. A union past [MAX_TARGETS] is
     * [SATURATED_TARGETS], which nothing changes after; a saturated (so empty) [newTargets]
     * adds nothing. The result is never mutated.
     */
    fun mergeTargets(
        existing: Set<DispatchTarget>?,
        newTargets: Collection<DispatchTarget>
    ): Set<DispatchTarget> = when {
        existing === SATURATED_TARGETS || newTargets.isEmpty() -> existing ?: emptySet()
        existing === newTargets || (existing != null && existing.size >= newTargets.size && existing.containsAll(newTargets)) -> existing
        newTargets.size > MAX_TARGETS -> SATURATED_TARGETS
        existing == null || (newTargets is Set<DispatchTarget> && newTargets.containsAll(existing)) ->
            newTargets as? Set<DispatchTarget> ?: LinkedHashSet(newTargets)
        else -> LinkedHashSet(existing).apply { addAll(newTargets) }.takeIf { it.size <= MAX_TARGETS } ?: SATURATED_TARGETS
    }

    fun clearMethodState(method: MethodDescriptor) {
        activeMethodLocals.forEach { key ->
            localNodes.remove(key)
            allocationNodes.remove(key)
            localToParamIndex.remove(key)
            dynamicTargets.remove(key)
            arrayDynamicTargets.remove(key)
            localeSpecsByLocal.remove(key)
            localeBuilderSpecsByLocal.remove(key)
            stringValuesByLocal.remove(key)
            bundleControlFormatsByLocal.remove(key)
            resourceHandlePathsByLocal.remove(key)
            propertiesPathsByLocal.remove(key)
            resourceBundlePaths.remove(key)
            bundleControlSpecsByLocal.remove(key)
        }
        activeMethodParameters.forEach { parameterNodes.remove(it) }
        methodReturnNodes.remove(method)
        stmtNodeIds.clear()
        stmtLocalWrites.clear()
        stmtIdentityWrites.clear()
        activeMethod = null
        activeMethodLocals = mutableListOf()
        bodyCallOrdinals = emptyMap()
        activeLocalKeysByName = mutableMapOf()
        activeMethodParameters = mutableListOf()
        activeParameterBindingsByIndex = mutableMapOf()
    }
}
