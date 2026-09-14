package io.johnsonlee.graphite.cli.c4

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.graph.Graph

internal object ComponentSelector {
    private val utilityPackageSignals = setOf("common", "shared", "support", "util", "utils")
    private val helperClassSignals = setOf("Helper", "Support", "Util", "Utils")

    fun buildView(
        graph: Graph,
        methods: List<MethodDescriptor>,
        callSites: List<CallSiteNode>,
        apiEndpoints: List<ApiEndpointEvidence>,
        systemBoundary: String,
        limit: Int,
        subject: SubjectDescriptor = inferSubjectDescriptor(graph, methods, callSites, apiEndpoints.size, systemBoundary),
        capabilityLayout: ContainerLayout = inferContainerLayout(
            graph = graph,
            methods = methods,
            callSites = callSites,
            apiEndpoints = apiEndpoints,
            systemBoundary = systemBoundary,
            limit = maxOf(limit, C4ComponentLimits.MIN_CAPABILITY_LAYOUT_CANDIDATES)
        )
    ): C4View {
        val runtimeLayout = inferOperationalContainerLayout(subject, capabilityLayout)
        if (runtimeLayout.containers.isEmpty()) {
            return C4View(
                type = C4Level.COMPONENT,
                elements = emptyList(),
                relationships = emptyList(),
                systemBoundary = runtimeLayout.systemBoundary,
                skippedReason = "No C4 runtime container inferred; library/package code is not promoted " +
                    "to component scope without a runtime boundary"
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
            .map { it.className }
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
            val callerCapability = if (callerInternal) {
                capabilityLayout.unitToContainerId[internalPackageUnit(caller, systemBoundary)]
            } else {
                null
            }
            val calleeCapability = if (calleeInternal) {
                capabilityLayout.unitToContainerId[internalPackageUnit(callee, systemBoundary)]
            } else {
                null
            }
            if (callerCapability != null) {
                capabilityCallCounts[callerCapability] = (capabilityCallCounts[callerCapability] ?: 0) + 1
            }
            if (calleeCapability != null) {
                capabilityCallCounts[calleeCapability] = (capabilityCallCounts[calleeCapability] ?: 0) + 1
            }
            if (!callerInternal || !calleeInternal) {
                if (callerInternal && !isRuntimeArchitectureClass(callee)) {
                    callerCapability?.let { externalCallsByCapability[it] = (externalCallsByCapability[it] ?: 0) + 1 }
                }
                return@forEach
            }
            if (callerCapability != null && calleeCapability != null && callerCapability != calleeCapability) {
                val callerKind = capabilityById[callerCapability]?.let(::containerKind) ?: WIRE_CAPABILITY
                val calleeKind = capabilityById[calleeCapability]?.let(::containerKind) ?: WIRE_CAPABILITY
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
                        outgoingCrossContainer = capability.outboundCrossContainer,
                        capabilityClassCount = classesByCapability[capability.id].orEmpty().size
                    )
                }
                .ifEmpty { capability.primaryClasses }
                .take(C4EvidenceLimits.MAX_CLASSES_PER_COMPONENT)
            C4Element(
                id = componentId(capability.id),
                type = C4ElementType.COMPONENT,
                name = capability.name,
                kind = kind.toC4ElementKind(),
                architectureType = architectureType(kind),
                responsibility = responsibility(
                    containerName = runtimeContainer.name,
                    endpointCount = endpointCountsByCapability[capability.id] ?: capability.endpointCount,
                    inboundCrossContainer = capability.inboundCrossContainer,
                    outboundCrossContainer = capability.outboundCrossContainer,
                    externalCalls = externalCallsByCapability[capability.id] ?: capability.externalCallCount
                ),
                metadata = C4Metadata.componentMetadata(
                    capability = capability,
                    runtimeContainer = runtimeContainer,
                    callSiteCount = capabilityCallCounts[capability.id] ?: capability.callSiteCount,
                    endpointCount = endpointCountsByCapability[capability.id] ?: capability.endpointCount,
                    externalCallCount = externalCallsByCapability[capability.id] ?: capability.externalCallCount,
                    representativeClasses = representativeClasses
                )
            )
        }
        val relationships = selectReadableRelationships(relationshipWeights.entries
            .filter { it.key.first.removePrefix(COMPONENT_ID_PREFIX).let { id -> "$CONTAINER_ID_PREFIX$id" in selectedCapabilityIds } }
            .filter { it.key.second.removePrefix(COMPONENT_ID_PREFIX).let { id -> "$CONTAINER_ID_PREFIX$id" in selectedCapabilityIds } }
            .sortedByDescending { it.value }
            .map { (pair, weight) ->
                val sourceCapabilityId = pair.first.replaceFirst(COMPONENT_ID_PREFIX, CONTAINER_ID_PREFIX)
                val targetCapabilityId = pair.second.replaceFirst(COMPONENT_ID_PREFIX, CONTAINER_ID_PREFIX)
                val sourceKind = componentKindById[sourceCapabilityId] ?: WIRE_DOMAIN_COMPONENT
                val targetKind = componentKindById[targetCapabilityId] ?: WIRE_DOMAIN_COMPONENT
                val relationshipKind = inferDependencyKind(sourceKind, targetKind)
                val relationshipKindType = relationshipKind.toC4RelationshipKind()
                C4Relationship(
                    from = pair.first,
                    to = pair.second,
                    type = relationshipKind.toC4RelationshipType(),
                    kind = relationshipKindType,
                    description = describeDependency(
                        relationshipKind = relationshipKind,
                        sourceName = capabilityById[sourceCapabilityId]?.name ?: pair.first.removePrefix(COMPONENT_ID_PREFIX),
                        targetName = capabilityById[targetCapabilityId]?.name ?: pair.second.removePrefix(COMPONENT_ID_PREFIX)
                    ),
                    weight = weight,
                    evidence = C4Metadata.componentCallEvidence(weight)
                )
            })
        return C4View(
            type = C4Level.COMPONENT,
            elements = elements,
            relationships = relationships
        )
    }

    fun score(
        className: String,
        systemBoundary: String,
        methodCount: Int,
        callCount: Int,
        endpointCount: Int,
        incomingCrossContainer: Int,
        outgoingCrossContainer: Int,
        capabilityClassCount: Int = 1
    ): Int {
        val baseScore = (incomingCrossContainer + outgoingCrossContainer) * C4ComponentScoring.REPRESENTATIVE_CROSS_CAPABILITY_WEIGHT +
            endpointCount * C4ComponentScoring.REPRESENTATIVE_ENDPOINT_WEIGHT +
            callCount * C4ComponentScoring.CALL_WEIGHT +
            methodCount * C4ComponentScoring.METHOD_WEIGHT
        val utilityPenalty = if (isUtilityLikeClass(
                className = className,
                systemBoundary = systemBoundary,
                endpointCount = endpointCount,
                incomingCrossContainer = incomingCrossContainer,
                outgoingCrossContainer = outgoingCrossContainer,
                capabilityClassCount = capabilityClassCount
            )
        ) {
            C4ComponentScoring.LOW_SIGNAL_HELPER_PENALTY
        } else {
            1
        }
        return baseScore / utilityPenalty
    }

    fun componentCapabilityScore(
        capability: ContainerDescriptor,
        endpointCount: Int,
        callCount: Int,
        externalCalls: Int
    ): Int =
        endpointCount * C4ComponentScoring.ENDPOINT_WEIGHT +
            (capability.inboundCrossContainer + capability.outboundCrossContainer) * C4ComponentScoring.CROSS_CAPABILITY_WEIGHT +
            externalCalls * C4ComponentScoring.EXTERNAL_CALL_WEIGHT +
            callCount * C4ComponentScoring.CALL_WEIGHT +
            capability.methodCount * C4ComponentScoring.METHOD_WEIGHT

    fun componentId(capabilityContainerId: String): String =
        "$COMPONENT_ID_PREFIX${capabilityContainerId.removePrefix(CONTAINER_ID_PREFIX)}"

    fun isUtilityLikeClass(
        className: String,
        systemBoundary: String,
        endpointCount: Int = 0,
        incomingCrossContainer: Int = 0,
        outgoingCrossContainer: Int = 0,
        capabilityClassCount: Int = 1
    ): Boolean =
        utilityEvidence(
            className = className,
            systemBoundary = systemBoundary,
            endpointCount = endpointCount,
            incomingCrossContainer = incomingCrossContainer,
            outgoingCrossContainer = outgoingCrossContainer,
            capabilityClassCount = capabilityClassCount
        ).isLowSignalHelper

    fun utilityEvidence(
        className: String,
        systemBoundary: String,
        endpointCount: Int,
        incomingCrossContainer: Int,
        outgoingCrossContainer: Int,
        capabilityClassCount: Int
    ): ClassUtilityEvidence {
        val relativePackage = className
            .removePrefix("$systemBoundary.")
            .substringBeforeLast('.', missingDelimiterValue = "")
        val packageSignal = relativePackage
            .split('.')
            .any { it in utilityPackageSignals }
        val simpleName = className.substringAfterLast('.')
        val helperSignal = helperClassSignals.any(simpleName::endsWith)
        return ClassUtilityEvidence(
            hasNamingSignal = packageSignal || helperSignal,
            hasEntrypointEvidence = endpointCount > 0,
            hasCrossCapabilityEvidence = incomingCrossContainer > 0 || outgoingCrossContainer > 0,
            isSoleCapabilityClass = capabilityClassCount <= 1
        )
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

    fun selectReadableRelationships(relationships: List<C4Relationship>): List<C4Relationship> {
        if (relationships.size <= 1) return relationships
        val architecturalEdges = relationships
            .filter { it.kind != C4RelationshipKind.COLLABORATES_WITH }
            .ifEmpty { relationships }
        val reduced = reduceTransitiveEdges(
            architecturalEdges
                .distinctBy { "${it.from}:${it.to}:${it.kind}" }
                .sortedByDescending { it.weight ?: 0 },
            preserveRuntimeEdges = false
        )
        val selected = mutableListOf<C4Relationship>()
        val outgoing = mutableMapOf<String, Int>()
        val incoming = mutableMapOf<String, Int>()

        fun tryAdd(edge: C4Relationship, enforceCaps: Boolean): Boolean {
            val source = edge.from
            val target = edge.to
            if (source.isBlank() || target.isBlank()) return false
            if (selected.any { it.from == source && it.to == target && it.kind == edge.kind }) return false
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
        if (endpointCount > 0) return WIRE_ENTRYPOINT
        return when {
            externalCalls > maxOf(inboundCrossContainer, outboundCrossContainer) -> WIRE_INTEGRATION
            outboundCrossContainer > inboundCrossContainer -> WIRE_ORCHESTRATOR
            inboundCrossContainer > outboundCrossContainer -> WIRE_SHARED_CAPABILITY
            inboundCrossContainer > 0 || outboundCrossContainer > 0 -> WIRE_COORDINATION
            else -> WIRE_DOMAIN_COMPONENT
        }
    }

    fun architectureType(kind: String): C4ArchitectureType =
        when (kind) {
            WIRE_ENTRYPOINT, WIRE_ORCHESTRATOR, WIRE_INTEGRATION, WIRE_COORDINATION -> C4ArchitectureType.APPLICATION_SERVICE
            else -> C4ArchitectureType.APPLICATION_COMPONENT
        }

    fun inferDependencyKind(sourceKind: String, targetKind: String): String {
        return when {
            sourceKind == WIRE_INTERFACE || sourceKind == WIRE_ENTRYPOINT -> C4RelationshipKind.ROUTES_TO.wireName
            sourceKind == WIRE_ORCHESTRATOR -> C4RelationshipKind.ORCHESTRATES.wireName
            targetKind == WIRE_SHARED_CAPABILITY -> C4RelationshipKind.USES.wireName
            targetKind == WIRE_INTEGRATION -> C4RelationshipKind.USES.wireName
            else -> C4RelationshipKind.COLLABORATES_WITH.wireName
        }
    }

    fun describeDependency(relationshipKind: String, sourceName: String, targetName: String): String {
        return when (relationshipKind) {
            C4RelationshipKind.ROUTES_TO.wireName -> "$sourceName routes work to $targetName"
            C4RelationshipKind.ORCHESTRATES.wireName -> "$sourceName orchestrates $targetName"
            C4RelationshipKind.USES.wireName -> "$sourceName uses $targetName"
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
    ComponentSelector.score(
        className,
        systemBoundary,
        methodCount,
        callCount,
        endpointCount,
        incomingCrossContainer,
        outgoingCrossContainer
    )

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

internal fun selectReadableComponentRelationships(relationships: List<C4Relationship>): List<C4Relationship> =
    ComponentSelector.selectReadableRelationships(relationships)

internal fun inferComponentKind(endpointCount: Int, inboundCrossContainer: Int, outboundCrossContainer: Int, externalCalls: Int): String =
    ComponentSelector.inferKind(endpointCount, inboundCrossContainer, outboundCrossContainer, externalCalls)

internal fun inferComponentArchitectureType(kind: String): String =
    ComponentSelector.architectureType(kind).wireName

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
