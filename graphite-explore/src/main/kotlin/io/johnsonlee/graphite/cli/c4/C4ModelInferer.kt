package io.johnsonlee.graphite.cli.c4

import io.johnsonlee.graphite.cli.ApiSpecExtractor
import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern

internal class C4ModelInferer(
    private val apiSpecExtractor: ApiSpecExtractor = ApiSpecExtractor()
) {

    internal fun buildViewModel(
        graph: Graph,
        level: String,
        limit: Int = C4ViewLimits.FALLBACK_MODEL_ELEMENTS,
    ): Map<String, Any?> {
        val methods = graph.methods(MethodPattern()).toList()
        val callSites = graph.nodes(CallSiteNode::class.java).toList()
        val classes = (methods.map { it.declaringClass.className } +
            callSites.flatMap { listOf(it.caller.declaringClass.className, it.callee.declaringClass.className) })
            .filterNot { isSyntheticArchitectureClass(it) }
            .distinct()
            .sorted()
        val apiEndpoints = apiSpecExtractor.extract(graph)
        val systemBoundary = deriveSystemBoundary(methods, callSites)
        fun subject() = inferSubjectDescriptor(graph, methods, callSites, apiEndpoints.size, systemBoundary)
        val inferred = if (level == "all") {
            val subject = subject()
            val capabilityLayout = inferContainerLayout(graph, methods, callSites, apiEndpoints, systemBoundary, limit)
            val containerView = buildContainerView(graph, callSites, subject, capabilityLayout)
            val componentView = ComponentSelector.buildView(
                graph = graph,
                methods = methods,
                callSites = callSites,
                apiEndpoints = apiEndpoints,
                systemBoundary = systemBoundary,
                limit = limit,
                subject = subject,
                capabilityLayout = capabilityLayout
            )
            val contextView = buildContextView(
                graph = graph,
                classCount = classes.size,
                methodCount = methods.size,
                endpointCount = apiEndpoints.size,
                callSites = callSites,
                systemBoundary = systemBoundary,
                subject = subject,
                containerCount = containerView.containerCount(),
                limit = limit
            )
            mapOf(
                "level" to "all",
                "availableLevels" to C4ArchitectureService.LEVELS,
                "context" to contextView,
                "container" to containerView,
                "component" to componentView
            )
        } else {
            val view = when (level) {
                "context" -> {
                    val subject = subject()
                    val containerCount = if (subject.role == "application") 1 else 0
                    buildContextView(
                        graph = graph,
                        classCount = classes.size,
                        methodCount = methods.size,
                        endpointCount = apiEndpoints.size,
                        callSites = callSites,
                        systemBoundary = systemBoundary,
                        subject = subject,
                        containerCount = containerCount,
                        limit = limit
                    )
                }
                "container" -> buildContainerView(graph, methods, callSites, apiEndpoints, systemBoundary, limit)
                "component" -> ComponentSelector.buildView(graph, methods, callSites, apiEndpoints, systemBoundary, limit)
                else -> {
                    val subject = subject()
                    val containerCount = if (subject.role == "application") 1 else 0
                    buildContextView(
                        graph = graph,
                        classCount = classes.size,
                        methodCount = methods.size,
                        endpointCount = apiEndpoints.size,
                        callSites = callSites,
                        systemBoundary = systemBoundary,
                        subject = subject,
                        containerCount = containerCount,
                        limit = limit
                    )
                }
            }
            mapOf(
                "level" to level,
                "availableLevels" to C4ArchitectureService.LEVELS,
                "view" to view
            )
        }
        return inferred
    }

    private fun buildContextView(
        graph: Graph,
        classCount: Int,
        methodCount: Int,
        endpointCount: Int,
        callSites: List<CallSiteNode>,
        systemBoundary: String,
        subject: SubjectDescriptor,
        containerCount: Int,
        limit: Int
    ): Map<String, Any?> {
        val externalWeights = mutableMapOf<String, Int>()
        callSites.forEach { cs ->
            val callerClass = cs.caller.declaringClass.className
            val calleeClass = cs.callee.declaringClass.className
            if (isSyntheticArchitectureClass(callerClass) || isSyntheticArchitectureClass(calleeClass)) return@forEach
            if (isInternalArchitectureClass(callerClass, systemBoundary) && !isInternalArchitectureClass(calleeClass, systemBoundary)) {
                externalWeights[calleeClass] = (externalWeights[calleeClass] ?: 0) + 1
            }
        }
        val externalSystems = summarizeExternalSystems(graph, externalWeights, limit)
        val runtimeDependencies = externalSystems.filter { it["kind"] == "runtime" }
        val collapsedContextDependencies = collapseContextLibraryDependencies(
            externalSystems.filter { it["kind"] != "runtime" }
        )
        val thirdPartyDependencies = collapsedContextDependencies.dependencies.filter { it["kind"] == "library" }
        val externalServiceDependencies = collapsedContextDependencies.dependencies.filter { it["kind"] == "external-system" }
        val libraryRelationships = inferContextLibraryDependencyRelationships(
            graph = graph,
            artifactToContextId = collapsedContextDependencies.artifactToContextId,
            contextDependencies = thirdPartyDependencies,
            limit = limit
        )
        val runtimeBoundaryLibraries = selectRuntimeBoundaryLibraries(thirdPartyDependencies, libraryRelationships)
        val elements = buildList<C4Element> {
            if (subject.actorId != null) {
                add(
                    C4Element(
                        id = subject.actorId,
                        type = "person",
                        name = subject.actorName.orEmpty(),
                        description = subject.actorDescription,
                        kind = "actor",
                        responsibility = subject.actorResponsibility
                    )
                )
            }
            if (thirdPartyDependencies.isNotEmpty() || externalServiceDependencies.isNotEmpty()) {
                (thirdPartyDependencies + externalServiceDependencies).forEach { dependency ->
                    add(
                        C4Element(
                            id = dependency["id"]?.toString().orEmpty(),
                            type = "softwareSystem",
                            name = dependency["name"]?.toString().orEmpty(),
                            description = externalSystemDescription(dependency["kind"]?.toString().orEmpty()),
                            kind = dependency["kind"]?.toString(),
                            architectureType = externalArchitectureType(dependency["kind"]?.toString().orEmpty()),
                            responsibility = dependency["responsibility"]?.toString(),
                            properties = mapOf(
                                "confidence" to dependency["confidence"],
                                "source" to dependency["source"],
                                "artifacts" to dependency["artifacts"]
                            )
                        )
                    )
                }
            }
            runtimeDependencies.forEach { dependency ->
                add(
                    C4Element(
                        id = dependency["id"]?.toString().orEmpty(),
                        type = "softwareSystem",
                        name = dependency["name"]?.toString().orEmpty(),
                        description = "Language and platform runtime supporting the application and its libraries",
                        kind = dependency["kind"]?.toString(),
                        architectureType = externalArchitectureType(dependency["kind"]?.toString().orEmpty()),
                        responsibility = dependency["responsibility"]?.toString(),
                        properties = mapOf(
                            "confidence" to dependency["confidence"],
                            "source" to dependency["source"]
                        )
                    )
                )
            }
            add(
                C4Element(
                    id = subject.id,
                    type = "softwareSystem",
                    name = subject.name,
                    description = subject.description,
                    kind = subject.role,
                    architectureType = if (subject.role == "application") "software-system" else "library",
                    responsibility = subject.responsibility,
                    properties = mapOf(
                        "classes" to classCount,
                        "methods" to methodCount,
                        "containers" to containerCount,
                        "endpoints" to endpointCount,
                        "systemBoundary" to systemBoundary,
                        "whySelected" to "Dominant namespace boundary inferred from internal classes and call-site traffic"
                    )
                )
            )
        }
        val relationships = buildList<C4Relationship> {
            if (subject.actorId != null) {
                add(
                    C4Relationship(
                        from = subject.actorId,
                        to = subject.id,
                        type = "uses",
                        description = describeSubjectInvocation(subject, endpointCount),
                        evidence = subjectInvocationEvidence(subject, endpointCount)
                    )
                )
            }
            if (thirdPartyDependencies.isNotEmpty() || externalServiceDependencies.isNotEmpty()) {
                (thirdPartyDependencies + externalServiceDependencies).forEach { dependency ->
                    add(
                        C4Relationship(
                            from = subject.id,
                            to = dependency["id"]?.toString().orEmpty(),
                            type = "uses",
                            kind = "uses",
                            description = "${subject.name} uses ${dependency["name"]}",
                            weight = dependency["weight"] as? Int,
                            evidence = mapOf(
                                "crossContainerCalls" to dependency["weight"],
                                "source" to dependency["source"],
                                "confidence" to dependency["confidence"],
                                "artifacts" to dependency["artifacts"]
                            )
                        )
                    )
                }
                addAll(libraryRelationships.map(::relationshipFromMap))
            }
            if (runtimeDependencies.isNotEmpty()) {
                if (runtimeBoundaryLibraries.isNotEmpty()) {
                    runtimeBoundaryLibraries.forEach { library ->
                        runtimeDependencies.forEach { dependency ->
                            add(
                                C4Relationship(
                                    from = library["id"]?.toString().orEmpty(),
                                    to = dependency["id"]?.toString().orEmpty(),
                                    type = "runs-on",
                                    kind = "runs-on",
                                    description = "${library["name"]} runs on ${dependency["name"]}",
                                    evidence = mapOf(
                                        "source" to dependency["source"],
                                        "kind" to dependency["kind"]
                                    )
                                )
                            )
                        }
                    }
                } else {
                    runtimeDependencies.forEach { dependency ->
                        add(
                            C4Relationship(
                                from = subject.id,
                                to = dependency["id"]?.toString().orEmpty(),
                                type = "runs-on",
                                kind = "runs-on",
                                description = "${subject.name} runs on ${dependency["name"]}",
                                evidence = mapOf(
                                    "source" to dependency["source"],
                                    "kind" to dependency["kind"]
                                )
                            )
                        )
                    }
                }
            }
        }
        return C4View("context", elements, relationships).toMap()
    }

    private fun collapseContextLibraryDependencies(dependencies: List<Map<String, Any?>>): ContextDependencyCollapse {
        if (dependencies.isEmpty()) {
            return ContextDependencyCollapse(emptyList(), emptyMap(), emptyMap())
        }
        val artifactBases = dependencies.mapNotNull { dependency ->
            artifactNameFromDependencyId(dependency["id"]?.toString().orEmpty())?.let { artifact ->
                artifact to artifactBaseName(artifact)
            }
        }
        val sharedPrefixes = artifactBases
            .mapNotNull { (_, base) ->
                base.split('-').firstOrNull()?.takeIf { it.isNotBlank() && base.contains('-') }
            }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys

        fun familyIdFor(dependency: Map<String, Any?>): String {
            val id = dependency["id"]?.toString().orEmpty()
            val artifact = artifactNameFromDependencyId(id) ?: return id
            val base = artifactBaseName(artifact)
            val prefix = base.split('-').firstOrNull().orEmpty()
            return if (prefix in sharedPrefixes) {
                "dependency:library:$prefix"
            } else {
                id
            }
        }

        val dependencyIdToContextId = dependencies.associate { dependency ->
            dependency["id"].toString() to familyIdFor(dependency)
        }
        val artifactToContextId = dependencies.mapNotNull { dependency ->
            val artifact = artifactNameFromDependencyId(dependency["id"]?.toString().orEmpty()) ?: return@mapNotNull null
            artifact to familyIdFor(dependency)
        }.toMap()

        val collapsed = dependencies
            .groupBy(::familyIdFor)
            .map { (familyId, members) ->
                if (members.size == 1 && familyId == members.single()["id"]) {
                    members.single() + ("artifacts" to listOf(members.single()["name"]?.toString().orEmpty()))
                } else {
                    val familyName = familyId.removePrefix("dependency:library:")
                    val artifactNames = members.mapNotNull { it["name"]?.toString() }.sorted()
                    mapOf(
                        "id" to familyId,
                        "name" to familyName,
                        "weight" to members.sumOf { (it["weight"] as? Int) ?: 0 },
                        "source" to "artifact-family",
                        "kind" to "library",
                        "confidence" to if (members.all { it["confidence"] == "high" }) "high" else "medium",
                        "responsibility" to "Provides the ${humanizeArtifactLabel(familyName)} third-party library capabilities used by the application",
                        "artifacts" to artifactNames
                    )
                }
            }
            .sortedByDescending { (it["weight"] as? Int) ?: 0 }

        return ContextDependencyCollapse(collapsed, dependencyIdToContextId, artifactToContextId)
    }

    private fun inferContextLibraryDependencyRelationships(
        graph: Graph,
        artifactToContextId: Map<String, String>,
        contextDependencies: List<Map<String, Any?>>,
        limit: Int
    ): List<Map<String, Any?>> {
        if (artifactToContextId.isEmpty()) return emptyList()
        val dependencyNames = contextDependencies.associate { it["id"].toString() to it["name"].toString() }
        val weights = linkedMapOf<Pair<String, String>, Int>()
        graph.artifactDependencies().forEach { (fromArtifact, dependencies) ->
            val fromId = artifactToContextId[fromArtifact] ?: return@forEach
            dependencies.forEach { (toArtifact, weight) ->
                val toId = artifactToContextId[toArtifact] ?: return@forEach
                if (fromId == toId || weight <= 0) return@forEach
                weights[fromId to toId] = (weights[fromId to toId] ?: 0) + weight
            }
        }
        return weights.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { (pair, weight) ->
                val fromName = dependencyNames[pair.first] ?: pair.first
                val toName = dependencyNames[pair.second] ?: pair.second
                mapOf(
                    "from" to pair.first,
                    "to" to pair.second,
                    "type" to "uses",
                    "kind" to "builds-on",
                    "description" to "$fromName builds on $toName",
                    "weight" to weight,
                    "evidence" to mapOf(
                        "observedReferences" to weight,
                        "source" to "artifact-metadata",
                        "confidence" to "high"
                    )
                )
            }
    }

    private fun buildContainerView(
        graph: Graph,
        methods: List<MethodDescriptor>,
        callSites: List<CallSiteNode>,
        apiEndpoints: List<Map<String, Any?>>,
        systemBoundary: String,
        limit: Int
    ): Map<String, Any?> {
        val subject = inferSubjectDescriptor(graph, methods, callSites, apiEndpoints.size, systemBoundary)
        val capabilityLayout = inferContainerLayout(graph, methods, callSites, apiEndpoints, systemBoundary, limit)
        return buildContainerView(graph, callSites, subject, capabilityLayout)
    }

    private fun buildContainerView(
        graph: Graph,
        callSites: List<CallSiteNode>,
        subject: SubjectDescriptor,
        capabilityLayout: ContainerLayout
    ): Map<String, Any?> {
        val runtimeLayout = inferOperationalContainerLayout(subject, capabilityLayout)
        val relationships = buildRuntimeContainerDependencyRelationships(graph, callSites, runtimeLayout)
            .map(::relationshipFromMap)
        val elements = runtimeLayout.containers.map { container ->
            val kind = containerKind(container)
            C4Element(
                id = container.id,
                type = "container",
                name = container.name,
                description = containerDescription(kind),
                kind = kind,
                architectureType = inferContainerArchitectureType(kind),
                responsibility = operationalContainerResponsibility(kind),
                properties = mapOf(
                    "technology" to "JVM bytecode",
                    "methods" to container.methodCount,
                    "callSites" to container.callSiteCount,
                    "endpoints" to container.endpointCount,
                    "entrypoints" to container.entrypoints,
                    "primaryClasses" to container.primaryClasses,
                    "packageUnits" to container.packageUnits.sorted(),
                    "internalCapabilities" to capabilityLayout.containers.map { capability ->
                        mapOf(
                            "name" to capability.name,
                            "kind" to containerKind(capability),
                            "packageUnits" to capability.packageUnits.sorted(),
                            "methods" to capability.methodCount,
                            "callSites" to capability.callSiteCount,
                            "endpoints" to capability.endpointCount,
                            "primaryClasses" to capability.primaryClasses,
                            "whySelected" to capability.rationale
                        )
                    },
                    "whySelected" to container.rationale
                )
            )
        }
        return C4View(
            type = "container",
            elements = elements,
            relationships = relationships,
            properties = mapOf(
                "externalDependencies" to runtimeLayout.externalDependencies,
                "systemBoundary" to runtimeLayout.systemBoundary
            )
        ).toMap()
    }

    private fun Map<String, Any?>.containerCount(): Int =
        (this["elements"] as? List<*>)?.size ?: 0

}
