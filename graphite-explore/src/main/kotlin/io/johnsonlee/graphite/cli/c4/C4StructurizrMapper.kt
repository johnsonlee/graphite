package io.johnsonlee.graphite.cli.c4

import com.google.gson.GsonBuilder

internal class C4StructurizrMapper {

private val gson = GsonBuilder().setPrettyPrinting().create()

internal fun toWorkspace(graphiteModel: Map<String, Any?>): Map<String, Any?> {
    val level = graphiteModel["level"] as? String ?: "all"
    val contextView = when (level) {
        "all" -> graphiteModel["context"] as? Map<String, Any?>
        "context" -> graphiteModel["view"] as? Map<String, Any?>
        else -> null
    }
    val containerView = when (level) {
        "all" -> graphiteModel["container"] as? Map<String, Any?>
        "container" -> graphiteModel["view"] as? Map<String, Any?>
        else -> null
    }
    val componentView = when (level) {
        "all" -> graphiteModel["component"] as? Map<String, Any?>
        "component" -> graphiteModel["view"] as? Map<String, Any?>
        else -> null
    }

    val people = linkedMapOf<String, MutableMap<String, Any?>>()
    val softwareSystems = linkedMapOf<String, MutableMap<String, Any?>>()
    val containers = linkedMapOf<String, MutableMap<String, Any?>>()
    val components = linkedMapOf<String, MutableMap<String, Any?>>()
    val relationshipIds = linkedMapOf<Triple<String, String, String>, String>()
    val primarySystemId = (contextView?.get("elements") as? List<Map<String, Any?>>)
        .orEmpty()
        .firstOrNull { it["type"] == "softwareSystem" && (it["id"] as? String)?.startsWith("system:") == true }
        ?.get("id")
        ?.toString()
        ?: "system:subject"

    val primarySystem = mutableMapOf<String, Any?>(
        "id" to primarySystemId,
        "name" to "Subject",
        "description" to "Derived from the Graphite code graph",
        "tags" to structurizrTags("Software System", "Graphite", "Application"),
        "properties" to emptyStructurizrProperties()
    )
    softwareSystems[primarySystemId] = primarySystem

    contextView?.let { view ->
        (view["elements"] as? List<Map<String, Any?>>).orEmpty().forEach { element ->
            when (element["type"]) {
                "person" -> people[element["id"].toString()] = structurizrElement(element, "Person")
                "softwareSystem" -> {
                    val target = if (element["id"] == primarySystemId) {
                        primarySystem
                    } else {
                        softwareSystems.getOrPut(element["id"].toString()) {
                            structurizrElement(element, "Software System")
                        }
                    }
                    target.putAll(structurizrElement(element, "Software System"))
                }
            }
        }
    }

    containerView?.let { view ->
        val externalDependencies = (view["externalDependencies"] as? List<Map<String, Any?>>).orEmpty()
        externalDependencies.forEach { dependency ->
            val dependencyId = dependency["id"].toString()
            val system = softwareSystems.getOrPut(dependencyId) {
                mutableMapOf(
                    "id" to dependencyId,
                    "name" to dependency["name"],
                    "description" to "External dependency inferred from code graph evidence",
                    "tags" to structurizrTags("Software System", "Graphite", "External Dependency"),
                    "properties" to emptyStructurizrProperties()
                )
            }
            system["properties"] = mergeStructurizrProperties(
                system["properties"] as? Map<String, String>,
                mapOf(
                    "graphite.kind" to dependency["kind"],
                    "graphite.architectureType" to externalArchitectureType(dependency["kind"]?.toString().orEmpty()),
                    "graphite.source" to dependency["source"],
                    "graphite.confidence" to dependency["confidence"],
                    "graphite.responsibility" to dependency["responsibility"]
                )
            )
        }
        (view["elements"] as? List<Map<String, Any?>>).orEmpty().forEach { element ->
            val container = structurizrElement(element, "Container")
            containers[element["id"].toString()] = container
        }
        primarySystem["properties"] = mergeStructurizrProperties(
            primarySystem["properties"] as? Map<String, String>,
            mapOf("graphite.systemBoundary" to view["systemBoundary"])
        )
    }

    componentView?.let { view ->
        (view["elements"] as? List<Map<String, Any?>>).orEmpty().forEach { element ->
            val component = structurizrElement(element, "Component")
            components[element["id"].toString()] = component
            val containerId = element["containerId"]?.toString() ?: "container:${element["container"]}"
            if (containerId !in containers) {
                containers[containerId] = mutableMapOf(
                    "id" to containerId,
                    "name" to element["container"],
                    "description" to "Inferred runtime container synthesized for component view",
                    "technology" to "JVM bytecode",
                    "tags" to structurizrTags("Container", "Graphite", "Internal Container"),
                    "properties" to emptyStructurizrProperties()
                )
            }
        }
    }

    fun registerRelationships(
        relationships: List<Map<String, Any?>>,
        sourceView: String
    ) {
        relationships.forEach { relationship ->
            val fromId = relationship["from"]?.toString() ?: return@forEach
            val toId = relationship["to"]?.toString() ?: return@forEach
            val description = relationship["description"]?.toString()
                ?: relationship["type"]?.toString()
                ?: "uses"
            val relationshipKind = relationship["kind"]?.toString()
                ?: relationship["type"]?.toString()
                ?: "uses"
            val key = Triple(fromId, toId, relationshipKind)
            val id = relationshipIds.getOrPut(key) { "rel-${relationshipIds.size + 1}" }
            val source = people[fromId]
                ?: softwareSystems[fromId]
                ?: containers[fromId]
                ?: components[fromId]
                ?: return@forEach
            val sourceRelationships = (source["relationships"] as? MutableList<Map<String, Any?>>)
                ?: mutableListOf<Map<String, Any?>>().also { source["relationships"] = it }
            if (sourceRelationships.any { it["id"] == id }) return@forEach
            sourceRelationships += mapOf(
                "id" to id,
                "destinationId" to toId,
                "description" to description,
                "technology" to relationship["type"]?.toString(),
                "tags" to listOf("Relationship", "Graphite", relationshipKind).joinToString(","),
                "properties" to structProperties(
                    "graphite.view" to sourceView,
                    "graphite.relationshipKind" to relationship["kind"],
                    "graphite.evidence" to relationship["evidence"],
                    "graphite.weight" to relationship["weight"]
                )
            )
        }
    }

    registerRelationships((contextView?.get("relationships") as? List<Map<String, Any?>>).orEmpty(), "context")
    registerRelationships((containerView?.get("relationships") as? List<Map<String, Any?>>).orEmpty(), "container")
    registerRelationships((componentView?.get("relationships") as? List<Map<String, Any?>>).orEmpty(), "component")

    containers.values.forEach { container ->
        val containerId = container["id"].toString()
        val nested = components.values
            .filter {
                it["properties"].asStructurizrProperty("graphite.containerId") == containerId ||
                    "container:${it["properties"].asStructurizrProperty("graphite.container")}" == containerId
            }
            .map { it.toMapWithoutNulls() }
        if (nested.isNotEmpty()) {
            container["components"] = nested
        }
    }
    primarySystem["containers"] = containers.values.map { it.toMapWithoutNulls() }

    fun relationshipIdsForView(view: Map<String, Any?>): List<String> =
        (view["relationships"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { relationship ->
            val fromId = relationship["from"]?.toString() ?: return@mapNotNull null
            val toId = relationship["to"]?.toString() ?: return@mapNotNull null
            val description = relationship["description"]?.toString()
            val relationshipKind = relationship["kind"]?.toString()
                ?: relationship["type"]?.toString()
                ?: description
                ?: "uses"
            relationshipIds[Triple(fromId, toId, relationshipKind)]
        }

    val systemContextViews = contextView?.let { view ->
        listOf(
            structurizrView(
                key = "graphite-context",
                description = "Graphite-derived C4 system context view",
                scopeId = primarySystemId,
                scopeKey = "softwareSystemId",
                elements = (view["elements"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { it["id"]?.toString() },
                relationships = relationshipIdsForView(view),
                properties = structProperties(
                    "graphite.level" to level
                )
            )
        )
    } ?: emptyList()

    val containerViews = containerView?.let { view ->
        val elementIds = (view["elements"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { it["id"]?.toString() } +
            (view["externalDependencies"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { it["id"]?.toString() } +
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
                    "graphite.systemBoundary" to view["systemBoundary"]
                )
            )
        )
    } ?: emptyList()

    val componentViews = componentView?.let { view ->
        val componentElements = (view["elements"] as? List<Map<String, Any?>>).orEmpty()
        val relationshipSourceComponentIds = components.values
            .flatMap { component ->
                val componentId = component["id"]?.toString() ?: return@flatMap emptyList<Pair<String, String>>()
                (component["relationships"] as? List<Map<String, Any?>>)
                    .orEmpty()
                    .mapNotNull { relationship -> relationship["id"]?.toString()?.let { it to componentId } }
            }
            .toMap()
        componentElements.groupBy { element ->
            element["containerId"]?.toString()
                ?: element["container"]?.toString()?.let { "container:$it" }
                ?: ""
        }
            .filterKeys { it.isNotBlank() }
            .map { (containerId, elements) ->
                val containerName = elements.firstOrNull()?.get("container")?.toString().orEmpty()
                val ids = elements.mapNotNull { it["id"]?.toString() }
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

    return mapOf(
        "name" to "Graphite C4 Workspace",
        "description" to "Structurizr workspace derived from the Graphite code graph",
        "properties" to structProperties(
            "graphite.level" to level,
            "graphite.availableLevels" to graphiteModel["availableLevels"],
            "graphite.format" to "structurizr-workspace"
        ),
        "model" to mapOf(
            "people" to people.values.map { it.toMapWithoutNulls() },
            "softwareSystems" to softwareSystems.values.map { it.toMapWithoutNulls() }
        ),
        "views" to mapOf(
            "systemContextViews" to systemContextViews,
            "containerViews" to containerViews,
            "componentViews" to componentViews,
            "configuration" to mapOf(
                "scope" to "softwareSystem",
                "properties" to structProperties(
                    "graphite.level" to level,
                    "graphite.availableLevels" to graphiteModel["availableLevels"]
                )
            )
        )
    )
}

private fun structurizrElement(element: Map<String, Any?>, baseTag: String): MutableMap<String, Any?> =
    mutableMapOf(
        "id" to element["id"],
        "name" to element["name"],
        "description" to (element["description"] ?: element["responsibility"] ?: ""),
        "technology" to element["technology"],
        "tags" to structurizrTags(baseTag, "Graphite", element["kind"]?.toString()),
        "properties" to structProperties(
            "graphite.type" to element["type"],
            "graphite.kind" to element["kind"],
            "graphite.architectureType" to element["architectureType"],
            "graphite.responsibility" to element["responsibility"],
            "graphite.whySelected" to element["whySelected"],
            "graphite.container" to element["container"],
            "graphite.containerId" to element["containerId"],
            "graphite.fullName" to element["fullName"],
            "graphite.methods" to element["methods"],
            "graphite.callSites" to element["callSites"],
            "graphite.endpoints" to element["endpoints"],
            "graphite.entrypoints" to element["entrypoints"],
            "graphite.classes" to element["classes"],
            "graphite.packageUnits" to element["packageUnits"],
            "graphite.primaryClasses" to element["primaryClasses"],
            "graphite.internalCapabilities" to element["internalCapabilities"],
            "graphite.evidence" to element["evidence"],
            "graphite.selectors" to element["selectors"],
            "graphite.owners" to element["owners"],
            "graphite.links" to element["links"],
            "graphite.constraints" to element["constraints"],
            "graphite.matchedClassCount" to (element["evidence"] as? Map<*, *>)?.get("matchedClassCount")
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
): Map<String, Any?> =
    mapOf(
        "key" to key,
        scopeKey to scopeId,
        "description" to description,
        "elements" to elements.distinct().map { mapOf("id" to it) },
        "relationships" to relationships.distinct().map { mapOf("id" to it) },
        "properties" to properties
    )

private fun structProperties(vararg entries: Pair<String, Any?>): Map<String, String> =
    entries.mapNotNull { (key, value) ->
        val stringValue = when (value) {
            null -> null
            is String -> value
            is Number, is Boolean -> value.toString()
            else -> gson.toJson(value)
        }
        stringValue?.let { key to it }
    }.toMap()

private fun mergeStructurizrProperties(
    current: Map<String, String>?,
    updates: Map<String, Any?>
): Map<String, String> =
    (current.orEmpty() + structProperties(*updates.map { it.key to it.value }.toTypedArray()))

private fun emptyStructurizrProperties(): Map<String, String> = emptyMap()

private fun structurizrTags(vararg tags: String?): String =
    tags.filterNotNull().filter { it.isNotBlank() }.joinToString(",")

}
