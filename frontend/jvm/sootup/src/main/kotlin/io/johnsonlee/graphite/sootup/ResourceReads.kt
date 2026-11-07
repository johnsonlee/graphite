package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.ValueNode
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.constant.StringConstant as SootStringConstant
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr
import sootup.core.jimple.common.expr.AbstractInvokeExpr
import sootup.core.signatures.MethodSignature
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.RESOURCE_LOOKUP_METHODS
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.RESOURCE_LOOKUP_CLASSES
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.RESOURCE_PATH_METHODS
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.RESOURCE_BUNDLE_READ_METHODS
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.PROPERTIES_LOAD_METHODS
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.READER_BRIDGE_CLASSES
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.PROPERTIES_CLASS
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.GSON_CLASS
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.DOCUMENT_BUILDER_CLASS
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.URL_CLASS
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.CHANNELS_CLASS

/** Links resource API calls to the files and bundle values they read. */
internal class ResourceReads(private val context: SootUpAdapter) {
    private val localeSpecsByLocal = context.localeSpecsByLocal
    private val resourceHandlePathsByLocal = context.resourceHandlePathsByLocal
    private val propertiesPathsByLocal = context.propertiesPathsByLocal
    private val resourceBundlePaths = context.resourceBundlePaths
    private val resourceFilesByPath = context.resourceFilesByPath
    private val configurationResourcePaths = context.configurationResourcePaths
    private val graphBuilder = context.graphBuilder
    private val bundleControl get() = context.bundleControl
    private val bundlePaths get() = context.bundlePaths
    private val callKinds get() = context.callKinds
    private val localeValues get() = context.localeValues
    private val metadata get() = context.metadata

    fun linkResourceReads(callSite: CallSiteNode, calleeSignature: MethodSignature, invokeExpr: AbstractInvokeExpr) {
        val configKey = extractResourceLookupKey(calleeSignature, invokeExpr)
        val resourcePaths = extractBoundResourcePaths(callSite.caller, calleeSignature, invokeExpr)
        if (configKey != null) {
            lookupResourceFiles(resourcePaths).forEach { resourceFile ->
                graphBuilder.addEdge(ResourceEdge(resourceFile.id, callSite.id, ResourceRelation.LOOKUP))
            }
            return
        }

        if (isResourceEnumerationCall(calleeSignature)) {
            resourcePaths?.forEach { path ->
                resourceFilesByPath[path]?.forEach { resourceFile ->
                    graphBuilder.addEdge(ResourceEdge(resourceFile.id, callSite.id, ResourceRelation.ENUMERATES))
                }
            }
        }
    }

    fun linkResourceFileReads(
        callSite: CallSiteNode,
        calleeSignature: MethodSignature,
        invokeExpr: AbstractInvokeExpr,
        caller: MethodDescriptor
    ) {
        val resourcePaths = extractResourceBundlePaths(caller, calleeSignature, invokeExpr)
            ?: extractResourceLookupPath(caller, calleeSignature, invokeExpr)?.let(::listOf)
            ?: return
        resourcePaths.forEach { resourcePath ->
            resourceFilesByPath[resourcePath]?.forEach { resourceFile ->
                graphBuilder.addEdge(
                    ResourceEdge(
                        from = resourceFile.id,
                        to = callSite.id,
                        kind = if (isResourceBundleCall(calleeSignature)) ResourceRelation.BUNDLE_CANDIDATE else ResourceRelation.OPENS
                    )
                )
            }
        }
    }

    fun extractResourceLookupKey(calleeSignature: MethodSignature, invokeExpr: AbstractInvokeExpr): String? {
        val declaringClass = calleeSignature.declClassType.fullyQualifiedName
        val methodName = calleeSignature.name
        if (methodName !in RESOURCE_LOOKUP_METHODS || declaringClass !in RESOURCE_LOOKUP_CLASSES || invokeExpr.args.isEmpty()) {
            return null
        }
        val firstArg = invokeExpr.args[0]
        return if (firstArg is SootStringConstant) firstArg.value else null
    }

    fun extractResourceLookupPath(
        caller: MethodDescriptor,
        calleeSignature: MethodSignature,
        invokeExpr: AbstractInvokeExpr
    ): String? {
        val declaringClass = calleeSignature.declClassType.fullyQualifiedName
        val methodName = calleeSignature.name
        val supported = (declaringClass == "java.lang.ClassLoader" && methodName in RESOURCE_PATH_METHODS) ||
            (declaringClass == "java.lang.Class" && methodName in RESOURCE_PATH_METHODS)
        val firstArg = if (supported) invokeExpr.args.firstOrNull() as? SootStringConstant else null
        return firstArg?.let { localeValues.normalizeResourcePath(caller, declaringClass, it.value) }
    }

    fun extractResourceBundlePaths(
        caller: MethodDescriptor,
        calleeSignature: MethodSignature,
        invokeExpr: AbstractInvokeExpr
    ): LinkedHashSet<String>? {
        val declaringClass = calleeSignature.declClassType.fullyQualifiedName
        val methodName = calleeSignature.name
        val firstArg = invokeExpr.args.firstOrNull() as? SootStringConstant
        if (declaringClass != RESOURCE_BUNDLE_CLASS || methodName != GET_BUNDLE_METHOD || firstArg == null) return null
        val baseName = firstArg.value
        val basePath = baseName.replace('.', '/')
        val localeArg = calleeSignature.parameterTypes
            .indexOfFirst { it.toString() == LOCALE_CLASS }
            .takeIf { it >= 0 }
            ?.let(invokeExpr.args::getOrNull)
        val controlArg = calleeSignature.parameterTypes
            .indexOfFirst { callKinds.isResourceBundleControlTypeName(it.toString()) }
            .takeIf { it >= 0 }
            ?.let(invokeExpr.args::getOrNull)
        val localeSpec = localeArg?.let { localeValues.extractLocaleSpec(caller, it) }
        val controlSpec = controlArg?.let { bundleControl.extractBundleControlSpec(caller, it, baseName, localeSpec) }
        bundleControl.ensureRuntimeBundleIndexed(baseName, localeSpec, controlSpec)
        return if (localeArg == null) {
            bundlePaths.collectBundleCandidates(baseName, basePath, controlSpec)
        } else if (localeSpec != null) {
            bundlePaths.buildResourceBundleCandidatePaths(baseName, basePath, localeSpec, controlSpec)
        } else {
            bundlePaths.collectMatchingResourceBundlePaths(baseName, basePath, controlSpec)
        }
    }

    fun linkResourceBundleReads(
        callSite: CallSiteNode,
        calleeSignature: MethodSignature,
        invokeExpr: AbstractInvokeExpr,
        caller: MethodDescriptor
    ) {
        val declaringClass = calleeSignature.declClassType.fullyQualifiedName
        val methodName = calleeSignature.name
        val receiverLocal = (invokeExpr as? AbstractInstanceInvokeExpr)?.base as? Local
        val paths = receiverLocal?.let { resourceBundlePaths[metadata.localKey(caller, it.name)] }
        if (declaringClass != RESOURCE_BUNDLE_CLASS || methodName !in RESOURCE_BUNDLE_READ_METHODS || paths == null) return
        val relation = if (methodName == GET_KEYS_METHOD) ResourceRelation.ENUMERATES else ResourceRelation.LOOKUP
        paths.asSequence().flatMap { resourceFilesByPath[it].orEmpty() }.forEach { resourceFile ->
            graphBuilder.addEdge(ResourceEdge(resourceFile.id, callSite.id, relation))
        }
    }

    fun linkStructuredResourceLoads(
        callSite: CallSiteNode,
        calleeSignature: MethodSignature,
        invokeExpr: AbstractInvokeExpr,
        caller: MethodDescriptor
    ) {
        if (!isStructuredResourceLoadCall(calleeSignature)) return
        extractBoundResourcePaths(caller, calleeSignature, invokeExpr)?.forEach { resourcePath ->
            resourceFilesByPath[resourcePath]?.forEach { resourceFile ->
                graphBuilder.addEdge(ResourceEdge(resourceFile.id, callSite.id, ResourceRelation.LOADS))
            }
        }
    }

    fun trackResourceAssociations(
        callSite: CallSiteNode,
        calleeSignature: MethodSignature,
        invokeExpr: AbstractInvokeExpr,
        caller: MethodDescriptor,
        resultNode: ValueNode?
    ) {
        val receiverLocal = (invokeExpr as? AbstractInstanceInvokeExpr)?.base as? Local
        if (calleeSignature.declClassType.fullyQualifiedName == LOCALE_CLASS && calleeSignature.name == INIT_METHOD) {
            val localeSpec = localeValues.extractConstructedLocaleSpec(invokeExpr)
            if (receiverLocal != null && localeSpec != null) {
                localeSpecsByLocal[metadata.localKey(caller, receiverLocal.name)] = localeSpec
            }
        }

        val boundPaths = extractBoundResourcePaths(caller, calleeSignature, invokeExpr)
        if (boundPaths != null) {
            when {
                isPropertiesLoadCall(calleeSignature) && receiverLocal != null -> {
                    propertiesPathsByLocal[metadata.localKey(caller, receiverLocal.name)] = LinkedHashSet(boundPaths)
                    emitResourceLoads(boundPaths, callSite)
                }
                isPropertyResourceBundleConstructor(calleeSignature) && receiverLocal != null -> {
                    val receiverKey = metadata.localKey(caller, receiverLocal.name)
                    resourceBundlePaths[receiverKey] = LinkedHashSet(boundPaths)
                    emitResourceLoads(boundPaths, callSite)
                }
                isReaderBridgeConstructor(calleeSignature) && receiverLocal != null -> {
                    resourceHandlePathsByLocal[metadata.localKey(caller, receiverLocal.name)] = LinkedHashSet(boundPaths)
                }
            }
        }

        if (resultNode is LocalVariable) {
            val resultKey = metadata.localKey(caller, resultNode.name)
            extractResourceLookupPath(caller, calleeSignature, invokeExpr)?.let {
                resourceHandlePathsByLocal[resultKey] = linkedSetOf(it)
            }
            if (isReaderFactoryCall(calleeSignature)) {
                boundPaths?.let { resourceHandlePathsByLocal[resultKey] = LinkedHashSet(it) }
            }
        }
    }

    private fun emitResourceLoads(paths: Collection<String>, callSite: CallSiteNode) {
        paths.asSequence().flatMap { resourceFilesByPath[it].orEmpty() }.forEach { resourceFile ->
            graphBuilder.addEdge(ResourceEdge(resourceFile.id, callSite.id, ResourceRelation.LOADS))
        }
    }

    fun extractBoundResourcePaths(
        caller: MethodDescriptor,
        calleeSignature: MethodSignature,
        invokeExpr: AbstractInvokeExpr
    ): LinkedHashSet<String>? {
        val directPath = extractResourceLookupPath(caller, calleeSignature, invokeExpr)
        if (directPath != null) return linkedSetOf(directPath)

        val boundPaths = LinkedHashSet<String>()
        (invokeExpr as? AbstractInstanceInvokeExpr)?.base
            ?.let { extractBoundResourcePaths(caller, it) }
            ?.let(boundPaths::addAll)
        invokeExpr.args.forEach { arg ->
            extractBoundResourcePaths(caller, arg)?.let(boundPaths::addAll)
        }
        return boundPaths.takeIf { it.isNotEmpty() }
    }

    fun extractBoundResourcePaths(caller: MethodDescriptor, value: Value): LinkedHashSet<String>? = when (value) {
        is Local -> {
            val key = metadata.localKey(caller, value.name)
            resourceHandlePathsByLocal[key]
                ?: propertiesPathsByLocal[key]
                ?: resourceBundlePaths[key]
        }
        else -> null
    }?.let(::LinkedHashSet)

    fun lookupResourceFiles(resourcePaths: Collection<String>?): Sequence<ResourceFileNode> {
        val scopedPaths = resourcePaths?.takeIf { it.isNotEmpty() } ?: configurationResourcePaths
        return scopedPaths.asSequence()
            .flatMap { path -> resourceFilesByPath[path].orEmpty().asSequence() }
            .distinctBy { it.id }
    }

    fun isResourceBundleCall(calleeSignature: MethodSignature): Boolean =
        calleeSignature.declClassType.fullyQualifiedName == RESOURCE_BUNDLE_CLASS &&
            calleeSignature.name == GET_BUNDLE_METHOD

    fun isResourceEnumerationCall(calleeSignature: MethodSignature): Boolean {
        val declaringClass = calleeSignature.declClassType.fullyQualifiedName
        return (declaringClass == RESOURCE_BUNDLE_CLASS || declaringClass == PROPERTY_RESOURCE_BUNDLE_CLASS) &&
            calleeSignature.name == GET_KEYS_METHOD
    }

    fun isPropertiesLoadCall(calleeSignature: MethodSignature): Boolean =
        calleeSignature.declClassType.fullyQualifiedName == PROPERTIES_CLASS &&
            calleeSignature.name in PROPERTIES_LOAD_METHODS

    fun isPropertyResourceBundleConstructor(calleeSignature: MethodSignature): Boolean =
        calleeSignature.declClassType.fullyQualifiedName == PROPERTY_RESOURCE_BUNDLE_CLASS &&
            calleeSignature.name == INIT_METHOD

    fun isReaderBridgeConstructor(calleeSignature: MethodSignature): Boolean {
        val declaringClass = calleeSignature.declClassType.fullyQualifiedName
        return calleeSignature.name == INIT_METHOD && declaringClass in READER_BRIDGE_CLASSES
    }

    fun isReaderFactoryCall(calleeSignature: MethodSignature): Boolean {
        val declaringClass = calleeSignature.declClassType.fullyQualifiedName
        val methodName = calleeSignature.name
        return (declaringClass == URL_CLASS && methodName == OPEN_STREAM_METHOD) ||
            (declaringClass == CHANNELS_CLASS && methodName == NEW_READER_METHOD)
    }

    fun isStructuredResourceLoadCall(calleeSignature: MethodSignature): Boolean {
        val declaringClass = calleeSignature.declClassType.fullyQualifiedName
        val methodName = calleeSignature.name
        return isPropertiesLoadCall(calleeSignature) ||
            isPropertyResourceBundleConstructor(calleeSignature) ||
            (declaringClass == GSON_CLASS && methodName == FROM_JSON_METHOD) ||
            (declaringClass == DOCUMENT_BUILDER_CLASS && methodName == PARSE_METHOD)
    }
}
