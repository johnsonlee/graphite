package io.johnsonlee.graphite.cli.c4

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.graph.Graph

internal object ComponentSelector {
    fun buildView(
        graph: Graph,
        methods: List<MethodDescriptor>,
        callSites: List<CallSiteNode>,
        apiEndpoints: List<Map<String, Any?>>,
        systemBoundary: String,
        limit: Int,
        subject: SubjectDescriptor = inferSubjectDescriptor(graph, methods, callSites, apiEndpoints.size, systemBoundary),
        capabilityLayout: ContainerLayout = inferContainerLayout(
            graph = graph,
            methods = methods,
            callSites = callSites,
            apiEndpoints = apiEndpoints,
            systemBoundary = systemBoundary,
            limit = maxOf(limit, 8)
        )
    ): Map<String, Any?> {
        val runtimeLayout = inferOperationalContainerLayout(subject, capabilityLayout)
        if (runtimeLayout.containers.isEmpty()) {
            return mapOf(
                "type" to "component",
                "elements" to emptyList<Map<String, Any?>>(),
                "relationships" to emptyList<Map<String, Any?>>(),
                "systemBoundary" to runtimeLayout.systemBoundary,
                "skippedReason" to "No C4 runtime container inferred; library/package code is not promoted to component scope without a runtime boundary"
            )
        }
        val runtimeContainer = runtimeLayout.containers.first()
        val capabilityById = capabilityLayout.containers.associateBy { it.id }
        val capabilityIds = capabilityById.keys
        val methodCounts = methods
            .map { it.declaringClass.className }
            .filter { isInternalArchitectureClass(it, systemBoundary) }
            .groupingBy { it }
            .eachCount()
        val endpointCounts = apiEndpoints
            .mapNotNull { it["class"] as? String }
            .filter { isInternalArchitectureClass(it, systemBoundary) }
            .groupingBy { it }
            .eachCount()
        val callCounts = mutableMapOf<String, Int>()
        val externalCallsByCapability = mutableMapOf<String, Int>()
        val capabilityCallCounts = mutableMapOf<String, Int>()
        val relationshipWeights = mutableMapOf<Pair<String, String>, Int>()
        callSites.forEach { cs ->
            val caller = cs.caller.declaringClass.className
            val callee = cs.callee.declaringClass.className
            val callerInternal = isInternalArchitectureClass(caller, systemBoundary)
            val calleeInternal = isInternalArchitectureClass(callee, systemBoundary)
            if (!callerInternal && !calleeInternal) return@forEach
            if (callerInternal) callCounts[caller] = (callCounts[caller] ?: 0) + 1
            if (calleeInternal) callCounts[callee] = (callCounts[callee] ?: 0) + 1
            val callerCapability = if (callerInternal) capabilityLayout.unitToContainerId[internalPackageUnit(caller, systemBoundary)] else null
            val calleeCapability = if (calleeInternal) capabilityLayout.unitToContainerId[internalPackageUnit(callee, systemBoundary)] else null
            if (callerCapability != null) capabilityCallCounts[callerCapability] = (capabilityCallCounts[callerCapability] ?: 0) + 1
            if (calleeCapability != null) capabilityCallCounts[calleeCapability] = (capabilityCallCounts[calleeCapability] ?: 0) + 1
            if (!callerInternal || !calleeInternal) {
                if (callerInternal && !isRuntimeArchitectureClass(callee)) {
                    callerCapability?.let { externalCallsByCapability[it] = (externalCallsByCapability[it] ?: 0) + 1 }
                }
                return@forEach
            }
            if (callerCapability != null && calleeCapability != null && callerCapability != calleeCapability) {
                val callerKind = capabilityById[callerCapability]?.let(::containerKind) ?: "capability"
                val calleeKind = capabilityById[calleeCapability]?.let(::containerKind) ?: "capability"
                val pair = canonicalRelationshipPair(
                    componentId(callerCapability),
                    componentId(calleeCapability),
                    callerKind,
                    calleeKind,
                    callerKind,
                    calleeKind
                )
                relationshipWeights[pair] = (relationshipWeights[pair] ?: 0) + 1
            }
        }
        val candidateClasses = (methodCounts.keys + endpointCounts.keys + callCounts.keys).distinct()
        val classToCapability = candidateClasses.mapNotNull { className ->
            val capabilityId = capabilityLayout.unitToContainerId[internalPackageUnit(className, systemBoundary)] ?: return@mapNotNull null
            if (capabilityId in capabilityIds) className to capabilityId else null
        }.toMap()
        val classesByCapability = classToCapability.entries.groupBy({ it.value }, { it.key })
        val endpointCountsByCapability = endpointCounts.entries
            .mapNotNull { (className, count) -> classToCapability[className]?.let { it to count } }
            .groupingBy { it.first }
            .fold(0) { total, item -> total + item.second }
        val rankedCapabilities = capabilityLayout.containers
            .sortedWith(compareByDescending<ContainerDescriptor> {
                componentCapabilityScore(
                    it,
                    endpointCountsByCapability[it.id] ?: it.endpointCount,
                    capabilityCallCounts[it.id] ?: it.callSiteCount,
                    externalCallsByCapability[it.id] ?: it.externalCallCount
                )
            }.thenBy { it.name })
            .take(limit)
        val selectedCapabilityIds = rankedCapabilities.map { it.id }.toSet()
        val componentKindById = rankedCapabilities.associate { capability ->
            capability.id to inferKind(
                endpointCount = endpointCountsByCapability[capability.id] ?: capability.endpointCount,
                inboundCrossContainer = capability.inboundCrossContainer,
                outboundCrossContainer = capability.outboundCrossContainer,
                externalCalls = externalCallsByCapability[capability.id] ?: capability.externalCallCount
            )
        }
        val elements = rankedCapabilities.map { capability ->
            val kind = componentKindById.getValue(capability.id)
            val representativeClasses = classesByCapability[capability.id].orEmpty()
                .sortedByDescending {
                    score(
                        className = it,
                        systemBoundary = systemBoundary,
                        methodCount = methodCounts[it] ?: 0,
                        callCount = callCounts[it] ?: 0,
                        endpointCount = endpointCounts[it] ?: 0,
                        incomingCrossContainer = capability.inboundCrossContainer,
                        outgoingCrossContainer = capability.outboundCrossContainer
                    )
                }
                .ifEmpty { capability.primaryClasses }
                .take(C4EvidenceLimits.MAX_CLASSES_PER_COMPONENT)
            mapOf(
                "id" to componentId(capability.id),
                "type" to "component",
                "name" to capability.name,
                "fullName" to capability.packageUnits.sorted().joinToString(","),
                "container" to runtimeContainer.name,
                "containerId" to runtimeContainer.id,
                "kind" to kind,
                "architectureType" to architectureType(kind),
                "responsibility" to responsibility(
                    containerName = runtimeContainer.name,
                    endpointCount = endpointCountsByCapability[capability.id] ?: capability.endpointCount,
                    inboundCrossContainer = capability.inboundCrossContainer,
                    outboundCrossContainer = capability.outboundCrossContainer,
                    externalCalls = externalCallsByCapability[capability.id] ?: capability.externalCallCount
                ),
                "methods" to capability.methodCount,
                "callSites" to (capabilityCallCounts[capability.id] ?: capability.callSiteCount),
                "endpoints" to (endpointCountsByCapability[capability.id] ?: capability.endpointCount),
                "incomingCrossContainerCalls" to capability.inboundCrossContainer,
                "outgoingCrossContainerCalls" to capability.outboundCrossContainer,
                "packageUnits" to capability.packageUnits.sorted(),
                "classes" to representativeClasses,
                "entrypoints" to capability.entrypoints,
                "whySelected" to buildList {
                    if ((endpointCountsByCapability[capability.id] ?: capability.endpointCount) > 0) add("entrypoint-facing capability")
                    if (capability.inboundCrossContainer > 0) add("used by neighboring capabilities")
                    if (capability.outboundCrossContainer > 0) add("depends on neighboring capabilities")
                    if ((externalCallsByCapability[capability.id] ?: capability.externalCallCount) > 0) add("external dependency touchpoint")
                }.ifEmpty { listOf("cohesive internal capability") }
            )
        }
        val relationships = selectReadableRelationships(relationshipWeights.entries
            .filter { it.key.first.removePrefix("component:").let { id -> "container:$id" in selectedCapabilityIds } }
            .filter { it.key.second.removePrefix("component:").let { id -> "container:$id" in selectedCapabilityIds } }
            .sortedByDescending { it.value }
            .map { (pair, weight) ->
                val sourceCapabilityId = pair.first.replaceFirst("component:", "container:")
                val targetCapabilityId = pair.second.replaceFirst("component:", "container:")
                val sourceKind = componentKindById[sourceCapabilityId] ?: "domain-component"
                val targetKind = componentKindById[targetCapabilityId] ?: "domain-component"
                val relationshipKind = inferDependencyKind(sourceKind, targetKind)
                mapOf(
                    "from" to pair.first,
                    "to" to pair.second,
                    "type" to relationshipKind,
                    "kind" to relationshipKind,
                    "description" to describeDependency(
                        relationshipKind = relationshipKind,
                        sourceName = capabilityById[sourceCapabilityId]?.name ?: pair.first.removePrefix("component:"),
                        targetName = capabilityById[targetCapabilityId]?.name ?: pair.second.removePrefix("component:")
                    ),
                    "weight" to weight,
                    "evidence" to mapOf("calls" to weight)
                )
            })
        return mapOf(
            "type" to "component",
            "elements" to elements,
            "relationships" to relationships
        )
    }

    fun score(
        className: String,
        systemBoundary: String,
        methodCount: Int,
        callCount: Int,
        endpointCount: Int,
        incomingCrossContainer: Int,
        outgoingCrossContainer: Int
    ): Int {
        val baseScore = (incomingCrossContainer + outgoingCrossContainer) * 200 +
            endpointCount * 100 +
            callCount +
            methodCount
        val utilityPenalty = if (isUtilityLikeClass(className, systemBoundary)) 4 else 1
        return baseScore / utilityPenalty
    }

    fun componentCapabilityScore(
        capability: ContainerDescriptor,
        endpointCount: Int,
        callCount: Int,
        externalCalls: Int
    ): Int =
        endpointCount * 300 +
            (capability.inboundCrossContainer + capability.outboundCrossContainer) * 200 +
            externalCalls * 100 +
            callCount +
            capability.methodCount

    fun componentId(capabilityContainerId: String): String =
        "component:${capabilityContainerId.removePrefix("container:")}"

    fun isUtilityLikeClass(className: String, systemBoundary: String): Boolean {
        return when {
            className.startsWith("$systemBoundary.common.io.") -> true
            className.startsWith("$systemBoundary.common.") -> true
            className.startsWith("$systemBoundary.xcontent.") -> true
            className.startsWith("$systemBoundary.core.") -> true
            else -> className.substringAfterLast('.').let {
                it.endsWith("Builder") ||
                    it.endsWith("Parser") ||
                    it.endsWith("Input") ||
                    it.endsWith("Output") ||
                    it.endsWith("Util") ||
                    it.endsWith("Utils")
            }
        }
    }

    fun canonicalRelationshipPair(
        sourceId: String,
        targetId: String,
        sourceKind: String,
        targetKind: String,
        sourceContainerKind: String,
        targetContainerKind: String
    ): Pair<String, String> {
        val sourceRank = minOf(containerDependencyLayerRank(sourceKind), containerDependencyLayerRank(sourceContainerKind))
        val targetRank = minOf(containerDependencyLayerRank(targetKind), containerDependencyLayerRank(targetContainerKind))
        return if (sourceRank > targetRank) targetId to sourceId else sourceId to targetId
    }

    fun selectReadableRelationships(relationships: List<Map<String, Any?>>): List<Map<String, Any?>> {
        if (relationships.size <= 1) return relationships
        val architecturalEdges = relationships
            .filter { it["kind"]?.toString() != "collaborates-with" }
            .ifEmpty { relationships }
        val reduced = reduceDiagramTransitiveEdges(
            architecturalEdges
                .distinctBy { "${it["from"]}:${it["to"]}:${it["kind"]}" }
                .sortedByDescending { (it["weight"] as? Int) ?: 0 }
        )
        val selected = mutableListOf<Map<String, Any?>>()
        val outgoing = mutableMapOf<String, Int>()
        val incoming = mutableMapOf<String, Int>()

        fun tryAdd(edge: Map<String, Any?>, enforceCaps: Boolean): Boolean {
            val source = edge["from"]?.toString() ?: return false
            val target = edge["to"]?.toString() ?: return false
            if (selected.any { it["from"] == source && it["to"] == target && it["kind"] == edge["kind"] }) return false
            if (enforceCaps &&
                ((outgoing[source] ?: 0) >= C4ComponentLimits.MAX_OUTGOING_EDGES_PER_COMPONENT ||
                    (incoming[target] ?: 0) >= C4ComponentLimits.MAX_INCOMING_EDGES_PER_COMPONENT)
            ) {
                return false
            }
            selected += edge
            outgoing[source] = (outgoing[source] ?: 0) + 1
            incoming[target] = (incoming[target] ?: 0) + 1
            return true
        }

        reduced.forEach { edge ->
            if (selected.size >= C4ComponentLimits.MAX_VIEW_EDGES) return@forEach
            tryAdd(edge, enforceCaps = true)
        }
        if (selected.size < minOf(C4ComponentLimits.MAX_VIEW_EDGES, reduced.size, C4ComponentLimits.MIN_EDGES_AFTER_CAP_RELAXATION)) {
            reduced.forEach { edge ->
                if (selected.size >= C4ComponentLimits.MAX_VIEW_EDGES) return@forEach
                tryAdd(edge, enforceCaps = false)
            }
        }
        return selected
    }

    fun inferKind(endpointCount: Int, inboundCrossContainer: Int, outboundCrossContainer: Int, externalCalls: Int): String {
        if (endpointCount > 0) return "entrypoint"
        return when {
            externalCalls > maxOf(inboundCrossContainer, outboundCrossContainer) -> "integration"
            outboundCrossContainer > inboundCrossContainer -> "orchestrator"
            inboundCrossContainer > outboundCrossContainer -> "shared-capability"
            inboundCrossContainer > 0 || outboundCrossContainer > 0 -> "coordination"
            else -> "domain-component"
        }
    }

    fun architectureType(kind: String): String =
        when (kind) {
            "entrypoint", "orchestrator", "integration", "coordination" -> "application-service"
            else -> "application-component"
        }

    fun inferDependencyKind(sourceKind: String, targetKind: String): String {
        return when {
            sourceKind == "interface" || sourceKind == "entrypoint" -> "routes-to"
            sourceKind == "orchestrator" -> "orchestrates"
            targetKind == "shared-capability" -> "uses"
            targetKind == "integration" -> "uses"
            else -> "collaborates-with"
        }
    }

    fun describeDependency(relationshipKind: String, sourceName: String, targetName: String): String {
        return when (relationshipKind) {
            "routes-to" -> "$sourceName routes work to $targetName"
            "orchestrates" -> "$sourceName orchestrates $targetName"
            "uses" -> "$sourceName uses $targetName"
            else -> "$sourceName collaborates with $targetName"
        }
    }

    fun responsibility(
        containerName: String,
        endpointCount: Int,
        inboundCrossContainer: Int,
        outboundCrossContainer: Int,
        externalCalls: Int
    ): String {
        if (endpointCount > 0) return "Accepts external requests and translates them into internal application operations"
        return when {
            externalCalls > maxOf(inboundCrossContainer, outboundCrossContainer) ->
                "Connects the ${containerName} capability to external collaborators and dependency boundaries"
            outboundCrossContainer > inboundCrossContainer ->
                "Coordinates work across neighboring capabilities inside ${containerName}"
            inboundCrossContainer > outboundCrossContainer ->
                "Provides a shared internal capability that other containers depend on through ${containerName}"
            inboundCrossContainer > 0 || outboundCrossContainer > 0 ->
                "Sits on a coordination path inside ${containerName} and participates in cross-container flows"
            else -> "Implements a structurally central part of the ${containerName} capability"
        }
    }
}

internal fun componentScore(
    className: String,
    systemBoundary: String,
    methodCount: Int,
    callCount: Int,
    endpointCount: Int,
    incomingCrossContainer: Int,
    outgoingCrossContainer: Int
): Int =
    ComponentSelector.score(className, systemBoundary, methodCount, callCount, endpointCount, incomingCrossContainer, outgoingCrossContainer)

internal fun isUtilityLikeClass(className: String, systemBoundary: String): Boolean =
    ComponentSelector.isUtilityLikeClass(className, systemBoundary)

internal fun canonicalComponentRelationshipPair(
    sourceId: String,
    targetId: String,
    sourceKind: String,
    targetKind: String,
    sourceContainerKind: String,
    targetContainerKind: String
): Pair<String, String> =
    ComponentSelector.canonicalRelationshipPair(sourceId, targetId, sourceKind, targetKind, sourceContainerKind, targetContainerKind)

internal fun selectReadableComponentRelationships(relationships: List<Map<String, Any?>>): List<Map<String, Any?>> =
    ComponentSelector.selectReadableRelationships(relationships)

internal fun inferComponentKind(endpointCount: Int, inboundCrossContainer: Int, outboundCrossContainer: Int, externalCalls: Int): String =
    ComponentSelector.inferKind(endpointCount, inboundCrossContainer, outboundCrossContainer, externalCalls)

internal fun inferComponentArchitectureType(kind: String): String =
    ComponentSelector.architectureType(kind)

internal fun inferArchitecturalDependencyKind(sourceKind: String, targetKind: String): String =
    ComponentSelector.inferDependencyKind(sourceKind, targetKind)

internal fun describeArchitecturalDependency(relationshipKind: String, sourceName: String, targetName: String): String =
    ComponentSelector.describeDependency(relationshipKind, sourceName, targetName)

internal fun inferComponentResponsibility(
    containerName: String,
    endpointCount: Int,
    inboundCrossContainer: Int,
    outboundCrossContainer: Int,
    externalCalls: Int
): String =
    ComponentSelector.responsibility(containerName, endpointCount, inboundCrossContainer, outboundCrossContainer, externalCalls)
