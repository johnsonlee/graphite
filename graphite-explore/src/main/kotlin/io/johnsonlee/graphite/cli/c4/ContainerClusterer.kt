package io.johnsonlee.graphite.cli.c4

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.graph.Graph

internal object ContainerClusterer {
    fun inferLayout(
        graph: Graph?,
        methods: List<MethodDescriptor>,
        callSites: List<CallSiteNode>,
        apiEndpoints: List<Map<String, Any?>>,
        systemBoundary: String,
        limit: Int
    ): ContainerLayout {
        val internalClasses = linkedSetOf<String>()
        methods.mapTo(internalClasses) { it.declaringClass.className }
        apiEndpoints.mapNotNullTo(internalClasses) { it["class"] as? String }
        callSites.forEach { cs ->
            internalClasses += cs.caller.declaringClass.className
            internalClasses += cs.callee.declaringClass.className
        }
        val filteredClasses = internalClasses.filter { isInternalArchitectureClass(it, systemBoundary) }
        val classMethodCounts = methods.asSequence()
            .map { it.declaringClass.className }
            .filter { isInternalArchitectureClass(it, systemBoundary) }
            .groupingBy { it }
            .eachCount()
        val methodCountsByUnit = methods.asSequence()
            .map { it.declaringClass.className }
            .filter { isInternalArchitectureClass(it, systemBoundary) }
            .groupingBy { internalPackageUnit(it, systemBoundary) }
            .eachCount()
        val endpointCountsByUnit = apiEndpoints.asSequence()
            .mapNotNull { it["class"] as? String }
            .filter { isInternalArchitectureClass(it, systemBoundary) }
            .groupingBy { internalPackageUnit(it, systemBoundary) }
            .eachCount()
        val endpointPathsByUnit = apiEndpoints.asSequence()
            .mapNotNull { endpoint ->
                val className = endpoint["class"] as? String ?: return@mapNotNull null
                if (!isInternalArchitectureClass(className, systemBoundary)) return@mapNotNull null
                internalPackageUnit(className, systemBoundary) to ((endpoint["path"] as? String).orEmpty())
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, paths) -> paths.filter { it.isNotBlank() }.distinct().sorted() }
        val classesByUnit = filteredClasses.groupBy { internalPackageUnit(it, systemBoundary) }
        val undirectedTraffic = mutableMapOf<Pair<String, String>, Int>()
        val externalWeights = mutableMapOf<String, Int>()
        callSites.forEach { cs ->
            val callerClass = cs.caller.declaringClass.className
            val calleeClass = cs.callee.declaringClass.className
            if (isSyntheticArchitectureClass(callerClass) || isSyntheticArchitectureClass(calleeClass)) return@forEach
            val callerInternal = isInternalArchitectureClass(callerClass, systemBoundary)
            val calleeInternal = isInternalArchitectureClass(calleeClass, systemBoundary)
            when {
                callerInternal && calleeInternal -> {
                    val callerUnit = internalPackageUnit(callerClass, systemBoundary)
                    val calleeUnit = internalPackageUnit(calleeClass, systemBoundary)
                    if (callerUnit != calleeUnit) {
                        val key = if (callerUnit < calleeUnit) callerUnit to calleeUnit else calleeUnit to callerUnit
                        undirectedTraffic[key] = (undirectedTraffic[key] ?: 0) + 1
                    }
                }
                callerInternal && !calleeInternal ->
                    externalWeights[calleeClass] = (externalWeights[calleeClass] ?: 0) + 1
            }
        }
        val trafficByUnit = mutableMapOf<String, Int>()
        undirectedTraffic.forEach { (pair, weight) ->
            trafficByUnit[pair.first] = (trafficByUnit[pair.first] ?: 0) + weight
            trafficByUnit[pair.second] = (trafficByUnit[pair.second] ?: 0) + weight
        }
        val allUnits = (methodCountsByUnit.keys + endpointCountsByUnit.keys + classesByUnit.keys).distinct()
        val tokenDocumentFrequency = allUnits
            .flatMap { unit ->
                unit.removePrefix(systemBoundary)
                    .trimStart('.')
                    .split('.')
                    .filter { it.isNotBlank() }
                    .distinct()
            }
            .groupingBy { it }
            .eachCount()
        val unitClusters = clusterInternalPackageUnits(allUnits, undirectedTraffic)
        val scoredClusters = unitClusters.map { packageUnits ->
            val methodCount = packageUnits.sumOf { methodCountsByUnit[it] ?: 0 }
            val endpointCount = packageUnits.sumOf { endpointCountsByUnit[it] ?: 0 }
            val callSiteCount = packageUnits.sumOf { unit -> trafficByUnit[unit] ?: 0 }
            Triple(packageUnits, methodCount + endpointCount * 10 + callSiteCount, callSiteCount)
        }
        val selectedClusters = scoredClusters
            .sortedWith(
                compareByDescending<Triple<Set<String>, Int, Int>> { it.second }
                    .thenByDescending { it.third }
                    .thenBy { it.first.minOrNull().orEmpty() }
            )
            .take(limit)
            .map { it.first }
        val containerDescriptors = selectedClusters.map { packageUnits ->
            val packageUnitSet = packageUnits.toSortedSet()
            val unitScores = packageUnitSet.associateWith { unit ->
                (methodCountsByUnit[unit] ?: 0) * 2 +
                    (endpointCountsByUnit[unit] ?: 0) * 10 +
                    (trafficByUnit[unit] ?: 0)
            }
            val representativeUnit = unitScores.maxByOrNull { it.value }?.key ?: packageUnitSet.first()
            val classes = packageUnitSet.flatMap { classesByUnit[it].orEmpty() }
            val entrypoints = packageUnitSet.flatMap { endpointPathsByUnit[it].orEmpty() }
                .distinct()
                .sorted()
                .take(C4EvidenceLimits.MAX_ENTRYPOINTS_PER_CONTAINER)
            val methodCount = packageUnitSet.sumOf { methodCountsByUnit[it] ?: 0 }
            val endpointCount = packageUnitSet.sumOf { endpointCountsByUnit[it] ?: 0 }
            val callSiteCount = packageUnitSet.sumOf { unit -> trafficByUnit[unit] ?: 0 }
            val name = inferName(packageUnitSet, unitScores, tokenDocumentFrequency, systemBoundary)
            val id = "container:${slugify(name.ifBlank { representativeUnit })}"
            ContainerDescriptor(
                id = id,
                name = name.ifBlank { representativeUnit },
                packageUnits = packageUnitSet,
                methodCount = methodCount,
                callSiteCount = callSiteCount,
                endpointCount = endpointCount,
                inboundCrossContainer = 0,
                outboundCrossContainer = 0,
                externalCallCount = 0,
                entrypoints = entrypoints,
                primaryClasses = classes.groupingBy { it }.eachCount().entries
                    .sortedByDescending { it.value }
                    .take(C4EvidenceLimits.MAX_PRIMARY_CLASSES_PER_CONTAINER)
                    .map { it.key.substringAfterLast('.') },
                rationale = buildRationale(packageUnitSet, representativeUnit, entrypoints)
            )
        }.toMutableList()
        val unitToContainerId = containerDescriptors.flatMap { container ->
            container.packageUnits.map { it to container.id }
        }.toMap()

        val inboundCrossContainer = mutableMapOf<String, Int>()
        val outboundCrossContainer = mutableMapOf<String, Int>()
        val externalCallCounts = mutableMapOf<String, Int>()
        val callSiteCounts = mutableMapOf<String, Int>()
        callSites.forEach { cs ->
            val callerClass = cs.caller.declaringClass.className
            val calleeClass = cs.callee.declaringClass.className
            if (isSyntheticArchitectureClass(callerClass) || isSyntheticArchitectureClass(calleeClass)) return@forEach
            val callerInternal = isInternalArchitectureClass(callerClass, systemBoundary)
            val calleeInternal = isInternalArchitectureClass(calleeClass, systemBoundary)
            val callerContainer = if (callerInternal) unitToContainerId[internalPackageUnit(callerClass, systemBoundary)] else null
            val calleeContainer = if (calleeInternal) unitToContainerId[internalPackageUnit(calleeClass, systemBoundary)] else null
            if (callerContainer != null) callSiteCounts[callerContainer] = (callSiteCounts[callerContainer] ?: 0) + 1
            if (calleeContainer != null) callSiteCounts[calleeContainer] = (callSiteCounts[calleeContainer] ?: 0) + 1
            when {
                callerContainer != null && calleeContainer != null && callerContainer != calleeContainer -> {
                    outboundCrossContainer[callerContainer] = (outboundCrossContainer[callerContainer] ?: 0) + 1
                    inboundCrossContainer[calleeContainer] = (inboundCrossContainer[calleeContainer] ?: 0) + 1
                }
                callerContainer != null && !calleeInternal && !isRuntimeArchitectureClass(calleeClass) ->
                    externalCallCounts[callerContainer] = (externalCallCounts[callerContainer] ?: 0) + 1
            }
        }
        val finalizedContainers = containerDescriptors.map { container ->
            container.copy(
                callSiteCount = callSiteCounts[container.id] ?: container.callSiteCount,
                inboundCrossContainer = inboundCrossContainer[container.id] ?: 0,
                outboundCrossContainer = outboundCrossContainer[container.id] ?: 0,
                externalCallCount = externalCallCounts[container.id] ?: 0
            )
        }
        return ContainerLayout(
            systemBoundary = systemBoundary,
            containers = finalizedContainers,
            unitToContainerId = unitToContainerId,
            externalDependencies = summarizeExternalSystems(graph, externalWeights)
        )
    }

    fun inferOperationalLayout(subject: SubjectDescriptor, capabilityLayout: ContainerLayout): ContainerLayout {
        if (subject.role != "application") {
            return ContainerLayout(
                systemBoundary = capabilityLayout.systemBoundary,
                containers = emptyList(),
                unitToContainerId = emptyMap(),
                externalDependencies = capabilityLayout.externalDependencies
            )
        }
        val runtimeId = "container:application-runtime"
        val runtimeName = "${subject.name} Runtime"
        val runtimeKind = if (capabilityLayout.containers.any { it.endpointCount > 0 }) {
            "application-service"
        } else {
            "application-runtime"
        }
        val runtimeContainer = ContainerDescriptor(
            id = runtimeId,
            name = runtimeName,
            packageUnits = capabilityLayout.containers.flatMap { it.packageUnits }.toSortedSet(),
            methodCount = capabilityLayout.containers.sumOf { it.methodCount },
            callSiteCount = capabilityLayout.containers.sumOf { it.callSiteCount },
            endpointCount = capabilityLayout.containers.sumOf { it.endpointCount },
            inboundCrossContainer = 0,
            outboundCrossContainer = 0,
            externalCallCount = capabilityLayout.containers.sumOf { it.externalCallCount },
            entrypoints = capabilityLayout.containers.flatMap { it.entrypoints }.distinct().sorted().take(C4EvidenceLimits.MAX_ENTRYPOINTS_PER_CONTAINER),
            primaryClasses = capabilityLayout.containers.flatMap { it.primaryClasses }.distinct().take(C4EvidenceLimits.MAX_PRIMARY_CLASSES_PER_CONTAINER),
            rationale = "Selected from C4 semantics as the executable/deployable JVM runtime boundary; package clusters remain internal capability evidence",
            declaredKind = runtimeKind
        )
        return ContainerLayout(
            systemBoundary = capabilityLayout.systemBoundary,
            containers = listOf(runtimeContainer),
            unitToContainerId = capabilityLayout.unitToContainerId.keys.associateWith { runtimeId },
            externalDependencies = capabilityLayout.externalDependencies
        )
    }

    fun buildRuntimeDependencyRelationships(
        graph: Graph,
        callSites: List<CallSiteNode>,
        layout: ContainerLayout
    ): List<Map<String, Any?>> {
        val runtimeContainer = layout.containers.singleOrNull() ?: return emptyList()
        val externalDependenciesById = layout.externalDependencies.associateBy { it["id"]?.toString() }
        val dependencyWeights = mutableMapOf<String, Int>()
        callSites.forEach { cs ->
            val callerClass = cs.caller.declaringClass.className
            val calleeClass = cs.callee.declaringClass.className
            if (isSyntheticArchitectureClass(callerClass) || isSyntheticArchitectureClass(calleeClass)) return@forEach
            if (!isInternalArchitectureClass(callerClass, layout.systemBoundary) || isInternalArchitectureClass(calleeClass, layout.systemBoundary)) {
                return@forEach
            }
            val dependencyId = "dependency:${externalSystemKey(graph, calleeClass)}"
            if (dependencyId !in externalDependenciesById) return@forEach
            dependencyWeights[dependencyId] = (dependencyWeights[dependencyId] ?: 0) + 1
        }

        val runtimeBoundaryLibraryIds = selectRuntimeBoundaryLibraryIds(graph, layout.externalDependencies)
        val libraryToRuntimeRelationships = layout.externalDependencies
            .filter { it["kind"] != "runtime" && it["id"]?.toString() in runtimeBoundaryLibraryIds }
            .flatMap { library ->
                layout.externalDependencies
                    .filter { it["kind"] == "runtime" }
                    .map { runtime ->
                        mapOf(
                            "from" to library["id"],
                            "to" to runtime["id"],
                            "type" to "runs-on",
                            "kind" to "runs-on",
                            "description" to "${library["name"]} runs on ${runtime["name"]}",
                            "evidence" to mapOf(
                                "source" to runtime["source"],
                                "kind" to runtime["kind"]
                            )
                        )
                    }
            }

        val containerToDependencyRelationships = dependencyWeights.entries
            .sortedByDescending { it.value }
            .map { (dependencyId, weight) ->
                val dependency = externalDependenciesById.getValue(dependencyId)
                val kind = if (dependency["kind"] == "runtime") "runs-on" else "depends-on"
                mapOf(
                    "from" to runtimeContainer.id,
                    "to" to dependencyId,
                    "type" to if (kind == "runs-on") "runs-on" else "uses",
                    "kind" to kind,
                    "description" to if (kind == "runs-on") {
                        "${runtimeContainer.name} runs on ${dependency["name"]}"
                    } else {
                        "${runtimeContainer.name} uses ${dependency["name"]}"
                    },
                    "weight" to weight,
                    "evidence" to mapOf(
                        "crossBoundaryCalls" to weight,
                        "source" to dependency["source"],
                        "confidence" to dependency["confidence"]
                    )
                )
            }

        return containerToDependencyRelationships + libraryToRuntimeRelationships
    }

    fun selectRuntimeBoundaryLibraries(
        libraries: List<Map<String, Any?>>,
        libraryRelationships: List<Map<String, Any?>>
    ): List<Map<String, Any?>> {
        val librariesWithDownstreamLibrary = libraryRelationships.mapNotNull { it["from"]?.toString() }.toSet()
        val boundary = libraries.filterNot { it["id"]?.toString() in librariesWithDownstreamLibrary }
        return boundary.ifEmpty { libraries }
    }

    fun selectRuntimeBoundaryLibraryIds(graph: Graph, dependencies: List<Map<String, Any?>>): Set<String> {
        val librariesByArtifact = dependencies
            .filter { it["kind"] != "runtime" }
            .mapNotNull { dependency ->
                val id = dependency["id"]?.toString() ?: return@mapNotNull null
                val artifact = artifactNameFromDependencyId(id) ?: return@mapNotNull null
                artifact to id
            }
            .toMap()
        if (librariesByArtifact.isEmpty()) return emptySet()

        val librariesWithDownstreamLibrary = graph.artifactDependencies().mapNotNull { (fromArtifact, downstream) ->
            val fromId = librariesByArtifact[fromArtifact] ?: return@mapNotNull null
            if (downstream.any { (toArtifact, weight) ->
                    weight > 0 && librariesByArtifact[toArtifact] != null && librariesByArtifact[toArtifact] != fromId
                }
            ) {
                fromId
            } else {
                null
            }
        }.toSet()
        val boundary = librariesByArtifact.values.toSet() - librariesWithDownstreamLibrary
        return boundary.ifEmpty { librariesByArtifact.values.toSet() }
    }

    fun clusterInternalPackageUnits(
        units: List<String>,
        undirectedTraffic: Map<Pair<String, String>, Int>
    ): List<Set<String>> {
        if (units.isEmpty()) return emptyList()
        val adjacency = mutableMapOf<String, MutableMap<String, Int>>()
        fun addEdge(from: String, to: String, weight: Int) {
            adjacency.getOrPut(from) { linkedMapOf() }[to] = (adjacency[from]?.get(to) ?: 0) + weight
        }
        undirectedTraffic.forEach { (pair, weight) ->
            addEdge(pair.first, pair.second, weight)
            addEdge(pair.second, pair.first, weight)
        }
        val strongestNeighbor = units.associateWith { unit ->
            adjacency[unit]
                ?.entries
                ?.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })
                ?.key
        }
        val parents = units.associateWith { it }.toMutableMap()
        fun find(unit: String): String {
            var current = unit
            while (parents[current] != current) {
                current = parents[current]!!
            }
            var cursor = unit
            while (parents[cursor] != cursor) {
                val parent = parents[cursor]!!
                parents[cursor] = current
                cursor = parent
            }
            return current
        }
        fun union(left: String, right: String) {
            val leftRoot = find(left)
            val rightRoot = find(right)
            if (leftRoot != rightRoot) parents[rightRoot] = leftRoot
        }
        units.forEach { unit ->
            val neighbor = strongestNeighbor[unit]
            if (neighbor != null && strongestNeighbor[neighbor] == unit) {
                union(unit, neighbor)
            }
        }
        units.forEach { unit ->
            if (find(unit) != unit) return@forEach
            val neighbor = strongestNeighbor[unit] ?: return@forEach
            if (find(neighbor) != unit) {
                val totalWeight = adjacency[unit]?.values?.sum() ?: 0
                val strongestWeight = adjacency[unit]?.get(neighbor) ?: 0
                if (totalWeight > 0 && strongestWeight * 2 >= totalWeight) {
                    union(unit, neighbor)
                }
            }
        }
        return units.groupBy { find(it) }
            .values
            .map { it.toSortedSet() }
            .sortedBy { it.firstOrNull().orEmpty() }
    }

    fun inferName(
        packageUnits: Set<String>,
        unitScores: Map<String, Int>,
        tokenDocumentFrequency: Map<String, Int>,
        systemBoundary: String
    ): String {
        val tokenScores = mutableMapOf<String, Double>()
        packageUnits.forEach { unit ->
            val suffix = unit.removePrefix(systemBoundary).trimStart('.')
            val tokens = suffix.split('.').filter { it.isNotBlank() }
            val weight = (unitScores[unit] ?: 1).toDouble()
            tokens.forEach { token ->
                val rarity = 1.0 / (tokenDocumentFrequency[token] ?: 1).toDouble()
                tokenScores[token] = (tokenScores[token] ?: 0.0) + weight * rarity
            }
        }
        val rankedTokens = tokenScores.entries
            .sortedByDescending { it.value }
            .toList()
        val selectedTokens = buildList {
            rankedTokens.firstOrNull()?.let { add(humanizeIdentifier(it.key)) }
            if (rankedTokens.size >= C4NamingHeuristics.MIN_TOKENS_FOR_SECOND_TOKEN) {
                val firstScore = rankedTokens[0].value
                val secondScore = rankedTokens[1].value
                if (
                    firstScore > C4NamingHeuristics.MIN_POSITIVE_TOKEN_SCORE &&
                    secondScore >= firstScore * C4NamingHeuristics.SECOND_TOKEN_MIN_SCORE_RATIO
                ) {
                    add(humanizeIdentifier(rankedTokens[1].key))
                }
            }
        }.distinct()
        return when {
            selectedTokens.isEmpty() -> systemBoundary
            selectedTokens.size == 1 -> selectedTokens.single()
            else -> selectedTokens.joinToString(" and ")
        }
    }

    fun buildRationale(
        packageUnits: Set<String>,
        representativeUnit: String,
        entrypoints: List<String>
    ): String {
        val unitCount = packageUnits.size
        return when {
            entrypoints.isNotEmpty() ->
                "Grouped from $unitCount tightly-coupled package unit(s) with visible inbound entrypoints; anchored by $representativeUnit"
            unitCount > 1 ->
                "Grouped from $unitCount mutually dependent package units around the structural center $representativeUnit"
            else ->
                "Derived from the dominant internal package unit $representativeUnit"
        }
    }

    fun inferResponsibility(
        endpointCount: Int,
        methodCount: Int,
        inboundCrossContainer: Int,
        outboundCrossContainer: Int,
        externalCalls: Int
    ): String {
        val dominantInteraction = maxOf(inboundCrossContainer, outboundCrossContainer, externalCalls)
        return when {
            endpointCount > 0 ->
                "Provides an inbound system interface and coordinates downstream execution across internal capabilities"
            externalCalls > 0 && externalCalls == dominantInteraction ->
                "Acts as an outward-facing capability boundary that coordinates internal work and collaborates with external dependencies"
            inboundCrossContainer > outboundCrossContainer ->
                "Acts as a shared internal capability boundary that is consumed by multiple collaborating subsystems"
            outboundCrossContainer > inboundCrossContainer ->
                "Acts as an orchestration boundary that fans out into several other internal capabilities"
            methodCount > 0 && dominantInteraction > 0 ->
                "Acts as a balanced internal collaboration boundary with both implementation depth and cross-capability traffic"
            else ->
                "Implements a cohesive internal capability boundary inferred from code concentration and collaboration patterns"
        }
    }

    fun inferKind(endpointCount: Int, inboundCrossContainer: Int, outboundCrossContainer: Int, externalCalls: Int): String {
        val dominantInteraction = maxOf(inboundCrossContainer, outboundCrossContainer, externalCalls)
        return when {
            endpointCount > 0 -> "interface"
            externalCalls > 0 && externalCalls == dominantInteraction -> "integration"
            outboundCrossContainer > inboundCrossContainer -> "orchestrator"
            inboundCrossContainer > outboundCrossContainer -> "shared-capability"
            else -> "capability"
        }
    }

    fun kind(container: ContainerDescriptor): String =
        container.declaredKind ?: inferKind(
            endpointCount = container.endpointCount,
            inboundCrossContainer = container.inboundCrossContainer,
            outboundCrossContainer = container.outboundCrossContainer,
            externalCalls = container.externalCallCount
        )

    fun description(kind: String): String =
        when (kind) {
            "application-runtime", "application-service" ->
                "Executable/deployable runtime container inferred from entrypoint, endpoint, and archive evidence"
            else ->
                "Internal capability evidence derived from code graph structure"
        }

    fun operationalResponsibility(kind: String): String? =
        when (kind) {
            "application-runtime", "application-service" ->
                "Runs the subject software system and owns the deployable JVM execution boundary"
            else -> null
        }

    fun canonicalRelationshipPair(sourceId: String, targetId: String, sourceKind: String, targetKind: String): Pair<String, String> {
        val sourceRank = dependencyLayerRank(sourceKind)
        val targetRank = dependencyLayerRank(targetKind)
        return if (sourceRank > targetRank) targetId to sourceId else sourceId to targetId
    }

    fun dependencyLayerRank(kind: String): Int =
        when (kind) {
            "interface", "entrypoint" -> 0
            "orchestrator", "integration" -> 1
            "capability", "coordination" -> 2
            "shared-capability" -> 3
            else -> 2
        }

    fun architectureType(kind: String): String =
        when (kind) {
            "application-runtime", "application-service" -> "application-service"
            "interface", "orchestrator", "integration" -> "application-service"
            else -> "application-component"
        }
}

internal fun inferContainerLayout(
    graph: Graph?,
    methods: List<MethodDescriptor>,
    callSites: List<CallSiteNode>,
    apiEndpoints: List<Map<String, Any?>>,
    systemBoundary: String,
    limit: Int
): ContainerLayout =
    ContainerClusterer.inferLayout(graph, methods, callSites, apiEndpoints, systemBoundary, limit)

internal fun inferOperationalContainerLayout(subject: SubjectDescriptor, capabilityLayout: ContainerLayout): ContainerLayout =
    ContainerClusterer.inferOperationalLayout(subject, capabilityLayout)

internal fun buildRuntimeContainerDependencyRelationships(
    graph: Graph,
    callSites: List<CallSiteNode>,
    layout: ContainerLayout
): List<Map<String, Any?>> =
    ContainerClusterer.buildRuntimeDependencyRelationships(graph, callSites, layout)

internal fun selectRuntimeBoundaryLibraries(
    libraries: List<Map<String, Any?>>,
    libraryRelationships: List<Map<String, Any?>>
): List<Map<String, Any?>> =
    ContainerClusterer.selectRuntimeBoundaryLibraries(libraries, libraryRelationships)

internal fun selectRuntimeBoundaryLibraryIds(graph: Graph, dependencies: List<Map<String, Any?>>): Set<String> =
    ContainerClusterer.selectRuntimeBoundaryLibraryIds(graph, dependencies)

internal fun clusterInternalPackageUnits(
    units: List<String>,
    undirectedTraffic: Map<Pair<String, String>, Int>
): List<Set<String>> =
    ContainerClusterer.clusterInternalPackageUnits(units, undirectedTraffic)

internal fun inferContainerName(
    packageUnits: Set<String>,
    unitScores: Map<String, Int>,
    tokenDocumentFrequency: Map<String, Int>,
    systemBoundary: String
): String =
    ContainerClusterer.inferName(packageUnits, unitScores, tokenDocumentFrequency, systemBoundary)

internal fun buildContainerRationale(packageUnits: Set<String>, representativeUnit: String, entrypoints: List<String>): String =
    ContainerClusterer.buildRationale(packageUnits, representativeUnit, entrypoints)

internal fun inferContainerResponsibility(
    endpointCount: Int,
    methodCount: Int,
    inboundCrossContainer: Int,
    outboundCrossContainer: Int,
    externalCalls: Int
): String =
    ContainerClusterer.inferResponsibility(endpointCount, methodCount, inboundCrossContainer, outboundCrossContainer, externalCalls)

internal fun inferContainerKind(endpointCount: Int, inboundCrossContainer: Int, outboundCrossContainer: Int, externalCalls: Int): String =
    ContainerClusterer.inferKind(endpointCount, inboundCrossContainer, outboundCrossContainer, externalCalls)

internal fun containerKind(container: ContainerDescriptor): String =
    ContainerClusterer.kind(container)

internal fun containerDescription(kind: String): String =
    ContainerClusterer.description(kind)

internal fun operationalContainerResponsibility(kind: String): String? =
    ContainerClusterer.operationalResponsibility(kind)

internal fun canonicalContainerRelationshipPair(sourceId: String, targetId: String, sourceKind: String, targetKind: String): Pair<String, String> =
    ContainerClusterer.canonicalRelationshipPair(sourceId, targetId, sourceKind, targetKind)

internal fun containerDependencyLayerRank(kind: String): Int =
    ContainerClusterer.dependencyLayerRank(kind)

internal fun inferContainerArchitectureType(kind: String): String =
    ContainerClusterer.architectureType(kind)
