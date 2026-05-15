package io.johnsonlee.graphite.cli.c4

import com.google.gson.GsonBuilder

internal class C4StructurizrMapper {

    fun toWorkspace(graphiteModel: Map<String, Any?>): Map<String, Any?> =
        toWorkspace(C4WireCodec.decodeModel(graphiteModel))

    fun toWorkspace(graphiteModel: C4ViewModel): Map<String, Any?> =
        C4StructurizrCodec.encode(toWorkspaceModel(graphiteModel))

    internal fun toWorkspaceModel(graphiteModel: C4ViewModel): C4StructurizrWorkspace {
        val level = graphiteModel.level
        val contextView = when (level) {
            C4Level.ALL -> graphiteModel.context
            C4Level.CONTEXT -> graphiteModel.view
            else -> null
        }
        val containerView = when (level) {
            C4Level.ALL -> graphiteModel.container
            C4Level.CONTAINER -> graphiteModel.view
            else -> null
        }
        val componentView = when (level) {
            C4Level.ALL -> graphiteModel.component
            C4Level.COMPONENT -> graphiteModel.view
            else -> null
        }

        val people = linkedMapOf<String, StructurizrElementBuilder>()
        val softwareSystems = linkedMapOf<String, StructurizrElementBuilder>()
        val containers = linkedMapOf<String, StructurizrElementBuilder>()
        val components = linkedMapOf<String, StructurizrElementBuilder>()
        val relationshipIds = linkedMapOf<Triple<String, String, C4RelationshipKind>, String>()
        val primarySystemId = contextView
            ?.elements
            .orEmpty()
            .firstOrNull { it.type == C4ElementType.SOFTWARE_SYSTEM && it.id.startsWith("system:") }
            ?.id
            ?: "system:subject"

        val primarySystem = StructurizrElementBuilder(
            id = primarySystemId,
            name = "Subject",
            description = "Derived from the Graphite code graph",
            tags = structurizrTags("Software System", "Graphite", "Application"),
            properties = emptyMap()
        )
        softwareSystems[primarySystemId] = primarySystem

        contextView?.elements.orEmpty().forEach { element ->
            when (element.type) {
                C4ElementType.PERSON -> people[element.id] = structurizrElement(element, "Person")
                C4ElementType.SOFTWARE_SYSTEM -> {
                    val target = if (element.id == primarySystemId) {
                        primarySystem
                    } else {
                        softwareSystems.getOrPut(element.id) { structurizrElement(element, "Software System") }
                    }
                    target.replaceWith(structurizrElement(element, "Software System"))
                }
                else -> Unit
            }
        }

        containerView?.externalDependencies.orEmpty().forEach { dependency ->
            val system = softwareSystems.getOrPut(dependency.id) {
                StructurizrElementBuilder(
                    id = dependency.id,
                    name = dependency.name,
                    description = "External dependency inferred from code graph evidence",
                    tags = structurizrTags("Software System", "Graphite", "External Dependency"),
                    properties = emptyMap()
                )
            }
            system.properties = mergeStructurizrProperties(
                system.properties,
                structProperties(
                    "graphite.kind" to dependency.kind,
                    "graphite.architectureType" to externalArchitectureType(dependency.kind),
                    "graphite.source" to dependency.source,
                    "graphite.confidence" to dependency.confidence,
                    "graphite.responsibility" to dependency.responsibility
                )
            )
        }
        containerView?.elements.orEmpty().forEach { element ->
            containers[element.id] = structurizrElement(element, "Container")
        }
        containerView?.systemBoundary?.let { boundary ->
            primarySystem.properties = mergeStructurizrProperties(
                primarySystem.properties,
                structProperties("graphite.systemBoundary" to boundary)
            )
        }

        componentView?.elements.orEmpty().forEach { element ->
            val component = structurizrElement(element, "Component")
            components[element.id] = component
            val containerId = element.containerId() ?: "container:${element.containerName()}"
            if (containerId !in containers) {
                containers[containerId] = StructurizrElementBuilder(
                    id = containerId,
                    name = element.containerName(),
                    description = "Inferred runtime container synthesized for component view",
                    technology = "JVM bytecode",
                    tags = structurizrTags("Container", "Graphite", "Internal Container"),
                    properties = emptyMap()
                )
            }
        }

        registerRelationships(contextView, "context", people, softwareSystems, containers, components, relationshipIds)
        registerRelationships(containerView, "container", people, softwareSystems, containers, components, relationshipIds)
        registerRelationships(componentView, "component", people, softwareSystems, containers, components, relationshipIds)

        containers.values.forEach { container ->
            val nested = components.values
                .filter { component ->
                    component.properties["graphite.containerId"] == container.id ||
                        "container:${component.properties["graphite.container"]}" == container.id
                }
                .map { it.toElement() }
            if (nested.isNotEmpty()) container.components = nested
        }
        primarySystem.containers = containers.values.map { it.toElement() }

        fun relationshipIdsForView(view: C4View): List<String> =
            view.relationships.mapNotNull { relationship ->
                val relationshipKind = relationship.kind ?: C4RelationshipKind.fromType(relationship.type)
                relationshipIds[Triple(relationship.from, relationship.to, relationshipKind)]
            }

        val systemContextViews = contextView?.let { view ->
            listOf(
                structurizrView(
                    key = "graphite-context",
                    description = "Graphite-derived C4 system context view",
                    scopeId = primarySystemId,
                    scopeKey = "softwareSystemId",
                    elements = view.elements.map { it.id },
                    relationships = relationshipIdsForView(view),
                    properties = structProperties("graphite.level" to level)
                )
            )
        } ?: emptyList()

        val containerViews = containerView?.let { view ->
            val elementIds = view.elements.map { it.id } +
                view.externalDependencies.map { it.id } +
                people.keys.toList()
            listOf(
                structurizrView(
                    key = "graphite-container",
                    description = "Graphite-derived C4 container view",
                    scopeId = primarySystemId,
                    scopeKey = "softwareSystemId",
                    elements = elementIds.distinct(),
                    relationships = relationshipIdsForView(view),
                    properties = structProperties(
                        "graphite.level" to level,
                        "graphite.systemBoundary" to view.systemBoundary
                    )
                )
            )
        } ?: emptyList()

        val componentViews = componentView?.let { view ->
            val relationshipSourceComponentIds = components.values
                .flatMap { component ->
                    component.relationships.map { relationship -> relationship.id to component.id }
                }
                .toMap()
            view.elements
                .groupBy { it.containerId() ?: it.containerName().takeIf(String::isNotBlank)?.let { name -> "container:$name" } ?: "" }
                .filterKeys { it.isNotBlank() }
                .map { (containerId, elements) ->
                    val containerName = elements.firstOrNull()?.containerName().orEmpty()
                    val ids = elements.map { it.id }
                    val relIds = relationshipIdsForView(view).filter { relId ->
                        relationshipSourceComponentIds[relId] in ids
                    }
                    structurizrView(
                        key = "graphite-component-${slugify(containerId)}",
                        description = "Graphite-derived C4 component view for $containerName",
                        scopeId = containerId,
                        scopeKey = "containerId",
                        elements = ids,
                        relationships = relIds,
                        properties = structProperties(
                            "graphite.level" to level,
                            "graphite.containerId" to containerId,
                            "graphite.container" to containerName
                        )
                    )
                }
        } ?: emptyList()

        return C4StructurizrWorkspace(
            name = "Graphite C4 Workspace",
            description = "Structurizr workspace derived from the Graphite code graph",
            properties = structProperties(
                "graphite.level" to level,
                "graphite.availableLevels" to graphiteModel.availableLevels,
                "graphite.format" to "structurizr-workspace"
            ),
            model = C4StructurizrModel(
                people = people.values.map { it.toElement() },
                softwareSystems = softwareSystems.values.map { it.toElement() }
            ),
            views = C4StructurizrViews(
                systemContextViews = systemContextViews,
                containerViews = containerViews,
                componentViews = componentViews,
                configuration = structurizrConfiguration(level, graphiteModel.availableLevels)
            )
        )
    }

    private fun registerRelationships(
        view: C4View?,
        sourceView: String,
        people: Map<String, StructurizrElementBuilder>,
        softwareSystems: Map<String, StructurizrElementBuilder>,
        containers: Map<String, StructurizrElementBuilder>,
        components: Map<String, StructurizrElementBuilder>,
        relationshipIds: MutableMap<Triple<String, String, C4RelationshipKind>, String>
    ) {
        view?.relationships.orEmpty().forEach { relationship ->
            val relationshipKind = relationship.kind ?: C4RelationshipKind.fromType(relationship.type)
            val key = Triple(relationship.from, relationship.to, relationshipKind)
            val id = relationshipIds.getOrPut(key) { "rel-${relationshipIds.size + 1}" }
            val source = people[relationship.from]
                ?: softwareSystems[relationship.from]
                ?: containers[relationship.from]
                ?: components[relationship.from]
                ?: return@forEach
            if (source.relationships.any { it.id == id }) return@forEach
            source.relationships += C4StructurizrRelationship(
                id = id,
                destinationId = relationship.to,
                description = relationship.description ?: relationship.type.wireName,
                technology = relationship.type.wireName,
                tags = structurizrTags("Relationship", "Graphite", relationshipKind),
                properties = structProperties(
                    "graphite.view" to sourceView,
                    "graphite.relationshipKind" to relationshipKind,
                    "graphite.evidence" to relationship.evidence,
                    "graphite.weight" to relationship.weight
                )
            )
        }
    }

    private fun structurizrElement(element: C4Element, baseTag: String): StructurizrElementBuilder =
        StructurizrElementBuilder(
            id = element.id,
            name = element.name,
            description = element.description ?: element.responsibility.orEmpty(),
            technology = element.properties["technology"]?.toString(),
            tags = structurizrTags(baseTag, "Graphite", element.kind),
            properties = structProperties(
                "graphite.type" to element.type,
                "graphite.kind" to element.kind,
                "graphite.architectureType" to element.architectureType,
                "graphite.responsibility" to element.responsibility,
                "graphite.whySelected" to element.properties["whySelected"],
                "graphite.container" to element.properties["container"],
                "graphite.containerId" to element.properties["containerId"],
                "graphite.fullName" to element.properties["fullName"],
                "graphite.methods" to element.properties["methods"],
                "graphite.callSites" to element.properties["callSites"],
                "graphite.endpoints" to element.properties["endpoints"],
                "graphite.entrypoints" to element.properties["entrypoints"],
                "graphite.classes" to element.properties["classes"],
                "graphite.packageUnits" to element.properties["packageUnits"],
                "graphite.primaryClasses" to element.properties["primaryClasses"],
                "graphite.internalCapabilities" to element.properties["internalCapabilities"],
                "graphite.evidence" to element.properties["evidence"],
                "graphite.selectors" to element.properties["selectors"],
                "graphite.owners" to element.properties["owners"],
                "graphite.links" to element.properties["links"],
                "graphite.constraints" to element.properties["constraints"],
                "graphite.matchedClassCount" to (element.properties["evidence"] as? Map<*, *>)?.get("matchedClassCount")
            )
        )

    private fun structurizrView(
        key: String,
        description: String,
        scopeId: String,
        scopeKey: String,
        elements: List<String>,
        relationships: List<String>,
        properties: Map<String, String>
    ): C4StructurizrView =
        C4StructurizrView(
            key = key,
            description = description,
            softwareSystemId = scopeId.takeIf { scopeKey == "softwareSystemId" },
            containerId = scopeId.takeIf { scopeKey == "containerId" },
            elements = elements.distinct(),
            relationships = relationships.distinct(),
            properties = properties
        )

    private class StructurizrElementBuilder(
        val id: String,
        var name: String,
        var description: String,
        var technology: String? = null,
        var tags: String = "",
        var properties: Map<String, String> = emptyMap(),
        val relationships: MutableList<C4StructurizrRelationship> = mutableListOf(),
        var containers: List<C4StructurizrElement> = emptyList(),
        var components: List<C4StructurizrElement> = emptyList()
    ) {
        fun replaceWith(other: StructurizrElementBuilder) {
            name = other.name
            description = other.description
            technology = other.technology
            tags = other.tags
            properties = other.properties
        }

        fun toElement(): C4StructurizrElement =
            C4StructurizrElement(
                id = id,
                name = name,
                description = description,
                technology = technology,
                tags = tags,
                properties = properties,
                relationships = relationships.toList(),
                containers = containers,
                components = components
            )
    }

    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().create()

        private fun C4Element.containerId(): String? =
            properties["containerId"]?.toString()

        private fun C4Element.containerName(): String =
            properties["container"]?.toString().orEmpty()

        private fun structProperties(vararg entries: Pair<String, Any?>): Map<String, String> =
            entries.mapNotNull { (key, value) ->
                val stringValue = when (value) {
                    null -> null
                    is String -> value
                    is C4WireEnum -> value.wireName
                    is Number, is Boolean -> value.toString()
                    is Iterable<*> -> gson.toJson(value.map { item -> if (item is C4WireEnum) item.wireName else item })
                    else -> gson.toJson(value)
                }
                stringValue?.let { key to it }
            }.toMap()

        private fun mergeStructurizrProperties(
            current: Map<String, String>,
            updates: Map<String, String>
        ): Map<String, String> = current + updates

        private fun structurizrConfiguration(level: C4Level, availableLevels: List<C4Level>): Map<String, Any?> =
            linkedMapOf(
                "scope" to "softwareSystem",
                "properties" to structProperties(
                    "graphite.level" to level,
                    "graphite.availableLevels" to availableLevels
                )
            )

        private fun structurizrTags(vararg tags: Any?): String =
            tags.mapNotNull { tag ->
                when (tag) {
                    null -> null
                    is C4WireEnum -> tag.wireName
                    else -> tag.toString()
                }?.takeIf { it.isNotBlank() }
            }.joinToString(",")
    }
}
