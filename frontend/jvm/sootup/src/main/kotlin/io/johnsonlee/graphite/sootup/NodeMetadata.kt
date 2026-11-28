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
    private val stmtNodeIds
        get() = context.stmtNodeIds
    private val stmtLocalWrites
        get() = context.stmtLocalWrites
    private val stmtIdentityWrites
        get() = context.stmtIdentityWrites
    private var activeMethod
        get() = context.activeMethod
        set(value) { context.activeMethod = value }
    private var activeMethodLocals
        get() = context.activeMethodLocals
        set(value) { context.activeMethodLocals = value }
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

            recordAnnotation(className, memberName, fullName, getAnnotationValues(annot))
        }
    }

    /**
     * Records the annotation [fullName] the bytecode carries on [memberName] of [className] (`<class>` for the class itself), as
     * member metadata and as an [AnnotationNode]. Values are normalized to what the graph persists.
     */
    fun recordAnnotation(className: String, memberName: String, fullName: String, values: Map<String, Any?>) {
        writeAnnotation(AnnotationKey(className, memberName, fullName), normalizeAnnotationValues(values))
    }

    /**
     * Holds the annotation a [GraphiteExtension] states until every class has been read ([commitExtensionAnnotations]).
     *
     * A member holds one annotation of a name: the metadata is keyed by (class, member, name), so a second write would replace
     * the first there while the graph kept a node for each, and the two views of one fact would disagree. An extension states
     * what the bytecode does not say, so a name that is already taken, by the bytecode or by an earlier extension write, is
     * refused before anything is written. The refusal is checked here, where the stack names the extension, and again when
     * the annotations are committed, because the class an extension names may not have been read yet.
     */
    fun stageExtensionAnnotation(className: String, memberName: String, fullName: String, values: Map<String, Any?>) {
        val key = AnnotationKey(className, memberName, fullName)
        check(!extensionAnnotationsCommitted) {
            "$key was recorded after the build had read every class; an extension writes during visit()"
        }
        require(key !in extensionAnnotations) {
            "An extension already recorded $key; a member holds one annotation of a name. Record the second fact under a name of its own"
        }
        require(!graphBuilder.hasMemberAnnotation(className, memberName, fullName)) { key.carriedByBytecode() }
        extensionAnnotations[key] = normalizeAnnotationValues(values)
    }

    /** Starts a build's extension writes: nothing is held over from a build that failed, and writes are accepted again. */
    fun openExtensionAnnotations() {
        extensionAnnotations.clear()
        extensionAnnotationsCommitted = false
    }

    /**
     * Writes the annotations the extensions stated, once the bytecode's are all recorded; fails, having written none of them,
     * when the bytecode carries one of them after all.
     */
    fun commitExtensionAnnotations() {
        extensionAnnotationsCommitted = true
        extensionAnnotations.keys
            .firstOrNull { graphBuilder.hasMemberAnnotation(it.className, it.memberName, it.name) }
            ?.let { error(it.carriedByBytecode()) }
        extensionAnnotations.forEach(::writeAnnotation)
        extensionAnnotations.clear()
    }

    private data class AnnotationKey(val className: String, val memberName: String, val name: String) {
        override fun toString(): String = "annotation '$name' on '$className#$memberName'"

        fun carriedByBytecode(): String =
            "An extension recorded $this, which the bytecode already carries. An extension states what the bytecode does " +
                "not say: record the added fact under a name of its own"
    }

    private val extensionAnnotations = LinkedHashMap<AnnotationKey, Map<String, Any?>>()
    private var extensionAnnotationsCommitted = false

    /** The one place an annotation enters the graph: its metadata entry and its node, written together. */
    private fun writeAnnotation(key: AnnotationKey, values: Map<String, Any?>) {
        graphBuilder.addMemberAnnotation(key.className, key.memberName, key.name, values)
        graphBuilder.addNode(AnnotationNode(
            id = NodeId.next(),
            name = key.name,
            className = key.className,
            memberName = key.memberName,
            values = values
        ))
    }

    private fun normalizeAnnotationValues(values: Map<String, Any?>): Map<String, Any?> {
        val cleanValues = mutableMapOf<String, Any?>()
        for ((key, value) in values) {
            cleanValues[key] = normalizeAnnotationValue(value)
        }
        return cleanValues
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
        context.bodyCallOrdinals = emptyMap()
        activeLocalKeysByName = mutableMapOf()
        activeMethodParameters = mutableListOf()
        activeParameterBindingsByIndex = mutableMapOf()
    }
}
