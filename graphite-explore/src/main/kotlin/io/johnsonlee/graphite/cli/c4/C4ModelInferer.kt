package io.johnsonlee.graphite.cli.c4

import io.johnsonlee.graphite.cli.ApiSpecExtractor
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern

internal class C4ModelInferer(
    private val apiSpecExtractor: ApiSpecExtractor = ApiSpecExtractor()
) {

    internal fun buildViewModel(
        graph: Graph,
        level: String,
        limit: Int = C4ViewLimits.FALLBACK_MODEL_ELEMENTS,
    ): Map<String, Any?> =
        C4WireCodec.encode(inferViewModel(graph, level, limit))

    internal fun inferViewModel(
        graph: Graph,
        level: String,
        limit: Int = C4ViewLimits.FALLBACK_MODEL_ELEMENTS,
    ): C4ViewModel =
        inferViewModel(graph, C4Level.fromWire(level), limit)

    internal fun inferViewModel(
        graph: Graph,
        level: C4Level,
        limit: Int = C4ViewLimits.FALLBACK_MODEL_ELEMENTS,
    ): C4ViewModel {
        val methods = graph.methods(MethodPattern()).toList()
        val callSites = graph.nodes(CallSiteNode::class.java).toList()
        val classes = (methods.map { it.declaringClass.className } +
            callSites.flatMap { listOf(it.caller.declaringClass.className, it.callee.declaringClass.className) })
            .filterNot { isSyntheticArchitectureClass(it) }
            .distinct()
            .sorted()
        val apiEndpoints = apiSpecExtractor.extract(graph).mapNotNull(::apiEndpointEvidenceFromMap)
        val systemBoundary = deriveSystemBoundary(methods, callSites)
        fun subject() = inferSubjectDescriptor(graph, methods, callSites, apiEndpoints.size, systemBoundary)

        return if (level == C4Level.ALL) {
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
                containerCount = containerView.elementCount,
                limit = limit
            )
            C4ViewModel(
                level = C4Level.ALL,
                availableLevels = C4Level.modelLevels,
                context = contextView,
                container = containerView,
                component = componentView
            )
        } else {
            val view = when (level) {
                C4Level.CONTEXT -> {
                    val subject = subject()
                    val containerCount = if (subject.role == WIRE_APPLICATION) 1 else 0
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
                C4Level.CONTAINER -> buildContainerView(graph, methods, callSites, apiEndpoints, systemBoundary, limit)
                C4Level.COMPONENT -> ComponentSelector.buildView(graph, methods, callSites, apiEndpoints, systemBoundary, limit)
                C4Level.ALL -> error("C4 all level is handled before single-view selection")
                else -> {
                    val subject = subject()
                    val containerCount = if (subject.role == WIRE_APPLICATION) 1 else 0
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
            C4ViewModel(
                level = level,
                availableLevels = C4Level.modelLevels,
                view = view
            )
        }
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
    ): C4View {
        val externalWeights = mutableMapOf<String, Int>()
        callSites.forEach { cs ->
            val callerClass = cs.caller.declaringClass.className
            val calleeClass = cs.callee.declaringClass.className
            if (isSyntheticArchitectureClass(callerClass) || isSyntheticArchitectureClass(calleeClass)) return@forEach
            if (isInternalArchitectureClass(callerClass, systemBoundary) && !isInternalArchitectureClass(calleeClass, systemBoundary)) {
                externalWeights[calleeClass] = (externalWeights[calleeClass] ?: 0) + 1
            }
        }
        val externalSystems = ExternalSystemClassifier.summarize(graph, externalWeights, limit)
        val runtimeDependencies = externalSystems.filter { it.kind == ExternalDependencyKind.RUNTIME }
        val collapsedContextDependencies = collapseContextLibraryDependencies(
            externalSystems.filter { it.kind != ExternalDependencyKind.RUNTIME }
        )
        val thirdPartyDependencies = collapsedContextDependencies.dependencies
            .filter { it.kind == ExternalDependencyKind.LIBRARY }
        val externalServiceDependencies = collapsedContextDependencies.dependencies
            .filter { it.kind == ExternalDependencyKind.EXTERNAL_SYSTEM }
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
                        type = C4ElementType.PERSON,
                        name = subject.actorName.orEmpty(),
                        description = subject.actorDescription,
                        kind = C4ElementKind.ACTOR,
                        architectureType = C4ArchitectureType.ACTOR,
                        responsibility = subject.actorResponsibility
                    )
                )
            }
            (thirdPartyDependencies + externalServiceDependencies).forEach { dependency ->
                add(
                    C4Element(
                        id = dependency.id,
                        type = C4ElementType.SOFTWARE_SYSTEM,
                        name = dependency.name,
                        description = ExternalSystemClassifier.description(dependency.kind),
                        kind = dependency.kind.toElementKind(),
                        architectureType = externalArchitectureType(dependency.kind),
                        responsibility = dependency.responsibility,
                        metadata = C4Metadata.externalDependencyMetadata(dependency)
                    )
                )
            }
            runtimeDependencies.forEach { dependency ->
                add(
                    C4Element(
                        id = dependency.id,
                        type = C4ElementType.SOFTWARE_SYSTEM,
                        name = dependency.name,
                        description = "Language and platform runtime supporting the application and its libraries",
                        kind = dependency.kind.toElementKind(),
                        architectureType = externalArchitectureType(dependency.kind),
                        responsibility = dependency.responsibility,
                        metadata = C4Metadata.runtimeDependencyMetadata(dependency)
                    )
                )
            }
            add(
                C4Element(
                    id = subject.id,
                    type = C4ElementType.SOFTWARE_SYSTEM,
                    name = subject.name,
                    description = subject.description,
                    kind = C4ElementKind.fromWire(subject.role) ?: C4ElementKind.APPLICATION,
                    architectureType = if (subject.role == WIRE_APPLICATION) {
                        C4ArchitectureType.SOFTWARE_SYSTEM
                    } else {
                        C4ArchitectureType.LIBRARY
                    },
                    responsibility = subject.responsibility,
                    metadata = C4Metadata.subjectMetadata(
                        classCount = classCount,
                        methodCount = methodCount,
                        containerCount = containerCount,
                        endpointCount = endpointCount,
                        systemBoundary = systemBoundary
                    )
                )
            )
        }
        val relationships = buildList {
            if (subject.actorId != null) {
                add(
                    C4Relationship(
                        from = subject.actorId,
                        to = subject.id,
                        type = C4RelationshipType.USES,
                        kind = C4RelationshipKind.USES,
                        description = describeSubjectInvocation(subject, endpointCount),
                        evidence = subjectInvocationEvidence(subject, endpointCount)
                    )
                )
            }
            (thirdPartyDependencies + externalServiceDependencies).forEach { dependency ->
                add(
                    C4Relationship(
                        from = subject.id,
                        to = dependency.id,
                        type = C4RelationshipType.USES,
                        kind = C4RelationshipKind.USES,
                        description = "${subject.name} uses ${dependency.name}",
                        weight = dependency.weight,
                        evidence = C4Metadata.externalDependencyEvidence(dependency)
                    )
                )
            }
            addAll(libraryRelationships)
            if (runtimeDependencies.isNotEmpty()) {
                if (runtimeBoundaryLibraries.isNotEmpty()) {
                    runtimeBoundaryLibraries.forEach { library ->
                        runtimeDependencies.forEach { dependency ->
                            add(
                                C4Relationship(
                                    from = library.id,
                                    to = dependency.id,
                                    type = C4RelationshipType.RUNS_ON,
                                    kind = C4RelationshipKind.RUNS_ON,
                                    description = "${library.name} runs on ${dependency.name}",
                                    evidence = C4Metadata.runtimeDependencyEvidence(dependency)
                                )
                            )
                        }
                    }
                } else {
                    runtimeDependencies.forEach { dependency ->
                        add(
                            C4Relationship(
                                from = subject.id,
                                to = dependency.id,
                                type = C4RelationshipType.RUNS_ON,
                                kind = C4RelationshipKind.RUNS_ON,
                                description = "${subject.name} runs on ${dependency.name}",
                                evidence = C4Metadata.runtimeDependencyEvidence(dependency)
                            )
                        )
                    }
                }
            }
        }
        return C4View(C4Level.CONTEXT, elements, relationships)
    }

    private fun collapseContextLibraryDependencies(dependencies: List<ExternalDependency>): ContextDependencyCollapse {
        if (dependencies.isEmpty()) {
            return ContextDependencyCollapse(emptyList(), emptyMap(), emptyMap())
        }
        val artifactBases = dependencies.mapNotNull { dependency ->
            ExternalSystemClassifier.artifactNameFromDependencyId(dependency.id)?.let { artifact ->
                artifact to ExternalSystemClassifier.artifactBaseName(artifact)
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

        fun familyIdFor(dependency: ExternalDependency): String {
            val artifact = ExternalSystemClassifier.artifactNameFromDependencyId(dependency.id) ?: return dependency.id
            val base = ExternalSystemClassifier.artifactBaseName(artifact)
            val prefix = base.split('-').firstOrNull().orEmpty()
            return if (prefix in sharedPrefixes) "$DEPENDENCY_LIBRARY_ID_PREFIX$prefix" else dependency.id
        }

        val dependencyIdToContextId = dependencies.associate { dependency ->
            dependency.id to familyIdFor(dependency)
        }
        val artifactToContextId = dependencies.mapNotNull { dependency ->
            val artifact = ExternalSystemClassifier.artifactNameFromDependencyId(dependency.id) ?: return@mapNotNull null
            artifact to familyIdFor(dependency)
        }.toMap()

        val collapsed = dependencies
            .groupBy(::familyIdFor)
            .map { (familyId, members) ->
                if (members.size == 1 && familyId == members.single().id) {
                    members.single().copy(artifacts = listOf(members.single().name))
                } else {
                    val familyName = familyId.removePrefix(DEPENDENCY_LIBRARY_ID_PREFIX)
                    val artifactNames = members.map { it.name }.sorted()
                    ExternalDependency(
                        id = familyId,
                        name = familyName,
                        weight = members.sumOf { it.weight },
                        source = "artifact-family",
                        kind = ExternalDependencyKind.LIBRARY,
                        confidence = if (members.all { it.confidence == "high" }) "high" else "medium",
                        responsibility = "Provides the ${humanizeArtifactLabel(familyName)} " +
                            "third-party library capabilities used by the application",
                        artifacts = artifactNames
                    )
                }
            }
            .sortedByDescending { it.weight }

        return ContextDependencyCollapse(collapsed, dependencyIdToContextId, artifactToContextId)
    }

    private fun inferContextLibraryDependencyRelationships(
        graph: Graph,
        artifactToContextId: Map<String, String>,
        contextDependencies: List<ExternalDependency>,
        limit: Int
    ): List<C4Relationship> {
        if (artifactToContextId.isEmpty()) return emptyList()
        val dependencyNames = contextDependencies.associate { it.id to it.name }
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
                C4Relationship(
                    from = pair.first,
                    to = pair.second,
                    type = C4RelationshipType.USES,
                    kind = C4RelationshipKind.BUILDS_ON,
                    description = "$fromName builds on $toName",
                    weight = weight,
                    evidence = C4Metadata.artifactDependencyEvidence(weight)
                )
            }
    }

    private fun buildContainerView(
        graph: Graph,
        methods: List<MethodDescriptor>,
        callSites: List<CallSiteNode>,
        apiEndpoints: List<ApiEndpointEvidence>,
        systemBoundary: String,
        limit: Int
    ): C4View {
        val subject = inferSubjectDescriptor(graph, methods, callSites, apiEndpoints.size, systemBoundary)
        val capabilityLayout = inferContainerLayout(graph, methods, callSites, apiEndpoints, systemBoundary, limit)
        return buildContainerView(graph, callSites, subject, capabilityLayout)
    }

    private fun buildContainerView(
        graph: Graph,
        callSites: List<CallSiteNode>,
        subject: SubjectDescriptor,
        capabilityLayout: ContainerLayout
    ): C4View {
        val runtimeLayout = inferOperationalContainerLayout(subject, capabilityLayout)
        val relationships = buildRuntimeContainerDependencyRelationships(graph, callSites, runtimeLayout)
        val elements = runtimeLayout.containers.map { container ->
            val kind = containerKind(container)
            C4Element(
                id = container.id,
                type = C4ElementType.CONTAINER,
                name = container.name,
                description = containerDescription(kind),
                kind = kind.toC4ElementKind(),
                architectureType = ContainerClusterer.architectureType(kind),
                responsibility = operationalContainerResponsibility(kind),
                metadata = C4Metadata.containerMetadata(container, capabilityLayout)
            )
        }
        return C4View(
            type = C4Level.CONTAINER,
            elements = elements,
            relationships = relationships,
            externalDependencies = runtimeLayout.externalDependencies,
            systemBoundary = runtimeLayout.systemBoundary
        )
    }
}
