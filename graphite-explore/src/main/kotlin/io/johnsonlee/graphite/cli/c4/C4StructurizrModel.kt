package io.johnsonlee.graphite.cli.c4

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken

internal data class C4StructurizrWorkspace(
    val name: String,
    val description: String,
    val properties: Map<String, String>,
    val model: C4StructurizrModel,
    val views: C4StructurizrViews
)

internal data class C4StructurizrModel(
    val people: List<C4StructurizrElement>,
    val softwareSystems: List<C4StructurizrElement>
)

internal data class C4StructurizrElement(
    val id: String,
    val name: String,
    val description: String = "",
    val technology: String? = null,
    val tags: String = "",
    val properties: Map<String, String> = emptyMap(),
    val relationships: List<C4StructurizrRelationship> = emptyList(),
    val containers: List<C4StructurizrElement> = emptyList(),
    val components: List<C4StructurizrElement> = emptyList()
)

internal data class C4StructurizrRelationship(
    val id: String,
    val destinationId: String,
    val description: String = "uses",
    val technology: String? = null,
    val tags: String = "",
    val properties: Map<String, String> = emptyMap()
)

internal data class C4StructurizrViews(
    val systemContextViews: List<C4StructurizrView>,
    val containerViews: List<C4StructurizrView>,
    val componentViews: List<C4StructurizrView>,
    val configuration: Map<String, Any?> = emptyMap()
)

internal data class C4StructurizrView(
    val key: String,
    val description: String,
    val softwareSystemId: String? = null,
    val containerId: String? = null,
    val elements: List<String>,
    val relationships: List<String>,
    val properties: Map<String, String>
)

internal object C4StructurizrCodec {
    private val gson = GsonBuilder().create()
    private val mapType = object : TypeToken<Map<String, Any?>>() {}.type

    fun encode(workspace: C4StructurizrWorkspace): Map<String, Any?> =
        gson.fromJson<Map<String, Any?>>(gson.toJsonTree(workspace.toWire()), mapType)
            .removeNullValues()

    fun decode(raw: Map<String, Any?>): C4StructurizrWorkspace {
        val wire = gson.fromJson(gson.toJsonTree(raw), WireWorkspace::class.java)
        return wire.toDomain()
    }

    private fun C4StructurizrWorkspace.toWire(): WireWorkspace =
        WireWorkspace(
            name = name,
            description = description,
            properties = properties,
            model = WireModel(
                people = model.people.map { it.toWire() },
                softwareSystems = model.softwareSystems.map { it.toWire() }
            ),
            views = WireViews(
                systemContextViews = views.systemContextViews.map { it.toWire() },
                containerViews = views.containerViews.map { it.toWire() },
                componentViews = views.componentViews.map { it.toWire() },
                configuration = views.configuration
            )
        )

    private fun WireWorkspace?.toDomain(): C4StructurizrWorkspace =
        C4StructurizrWorkspace(
            name = this?.name ?: "Graphite C4 Workspace",
            description = this?.description.orEmpty(),
            properties = this?.properties.toStringProperties(),
            model = C4StructurizrModel(
                people = this?.model?.people.orEmpty().mapNotNull { it.toDomain() },
                softwareSystems = this?.model?.softwareSystems.orEmpty().mapNotNull { it.toDomain() }
            ),
            views = C4StructurizrViews(
                systemContextViews = this?.views?.systemContextViews.orEmpty().map { it.toDomain() },
                containerViews = this?.views?.containerViews.orEmpty().map { it.toDomain() },
                componentViews = this?.views?.componentViews.orEmpty().map { it.toDomain() },
                configuration = this?.views?.configuration.orEmpty()
            )
        )

    private fun C4StructurizrElement.toWire(): WireElement =
        WireElement(
            id = id,
            name = name,
            description = description,
            technology = technology,
            tags = tags,
            properties = properties,
            relationships = relationships.map { it.toWire() },
            containers = containers.map { it.toWire() },
            components = components.map { it.toWire() }
        )

    private fun WireElement.toDomain(): C4StructurizrElement? {
        val id = id?.takeIf(String::isNotBlank) ?: return null
        return C4StructurizrElement(
            id = id,
            name = name ?: id,
            description = description.orEmpty(),
            technology = technology,
            tags = tags.orEmpty(),
            properties = properties.toStringProperties(),
            relationships = relationships.orEmpty().mapNotNull { it.toDomain() },
            containers = containers.orEmpty().mapNotNull { it.toDomain() },
            components = components.orEmpty().mapNotNull { it.toDomain() }
        )
    }

    private fun C4StructurizrRelationship.toWire(): WireRelationship =
        WireRelationship(
            id = id,
            destinationId = destinationId,
            description = description,
            technology = technology,
            tags = tags,
            properties = properties
        )

    private fun WireRelationship.toDomain(): C4StructurizrRelationship? {
        val destinationId = destinationId?.takeIf(String::isNotBlank) ?: return null
        return C4StructurizrRelationship(
            id = id.orEmpty(),
            destinationId = destinationId,
            description = description ?: "uses",
            technology = technology,
            tags = tags.orEmpty(),
            properties = properties.toStringProperties()
        )
    }

    private fun C4StructurizrView.toWire(): WireView =
        WireView(
            key = key,
            description = description,
            softwareSystemId = softwareSystemId,
            containerId = containerId,
            elements = elements.distinct().map(::WireReference),
            relationships = relationships.distinct().map(::WireReference),
            properties = properties
        )

    private fun WireView.toDomain(): C4StructurizrView =
        C4StructurizrView(
            key = key.orEmpty(),
            description = description.orEmpty(),
            softwareSystemId = softwareSystemId,
            containerId = containerId,
            elements = elements.orEmpty().mapNotNull(WireReference::id),
            relationships = relationships.orEmpty().mapNotNull(WireReference::id),
            properties = properties.toStringProperties()
        )

    private fun Map<String, Any?>?.toStringProperties(): Map<String, String> =
        orEmpty().mapNotNull { (key, value) -> value?.toString()?.let { key to it } }.toMap()

    private fun Map<String, Any?>.removeNullValues(): Map<String, Any?> =
        mapValues { (_, value) ->
            when (value) {
                is Map<*, *> -> value.stringKeyMap().removeNullValues()
                is List<*> -> value.map { item ->
                    if (item is Map<*, *>) {
                        item.stringKeyMap().removeNullValues()
                    } else {
                        item
                    }
                }
                else -> value
            }
        }.filterValues { value -> value != null }

    private fun Map<*, *>.stringKeyMap(): Map<String, Any?> =
        entries.mapNotNull { (key, value) -> (key as? String)?.let { it to value } }.toMap()

    private data class WireWorkspace(
        val name: String?,
        val description: String?,
        val properties: Map<String, Any?>?,
        val model: WireModel?,
        val views: WireViews?
    )

    private data class WireModel(
        val people: List<WireElement>?,
        val softwareSystems: List<WireElement>?
    )

    private data class WireElement(
        val id: String?,
        val name: String?,
        val description: String?,
        val technology: String?,
        val tags: String?,
        val properties: Map<String, Any?>?,
        val relationships: List<WireRelationship>?,
        val containers: List<WireElement>?,
        val components: List<WireElement>?
    )

    private data class WireRelationship(
        val id: String?,
        val destinationId: String?,
        val description: String?,
        val technology: String?,
        val tags: String?,
        val properties: Map<String, Any?>?
    )

    private data class WireViews(
        val systemContextViews: List<WireView>?,
        val containerViews: List<WireView>?,
        val componentViews: List<WireView>?,
        val configuration: Map<String, Any?>?
    )

    private data class WireView(
        val key: String?,
        val description: String?,
        val softwareSystemId: String?,
        val containerId: String?,
        val elements: List<WireReference>?,
        val relationships: List<WireReference>?,
        val properties: Map<String, Any?>?
    )

    private data class WireReference(
        val id: String?
    )
}

internal fun Any?.asStringKeyMap(): Map<String, Any?>? =
    (this as? Map<*, *>)?.entries
        ?.mapNotNull { (key, value) -> (key as? String)?.let { it to value } }
        ?.toMap()

internal fun Any?.asStructurizrMaps(): List<Map<String, Any?>> =
    (this as? List<*>)
        ?.mapNotNull { it.asStringKeyMap() }
        .orEmpty()

internal fun Any?.asStringProperties(): Map<String, String> =
    (this as? Map<*, *>)?.entries
        ?.mapNotNull { (key, value) ->
            val propertyKey = key as? String ?: return@mapNotNull null
            value?.toString()?.let { propertyKey to it }
        }
        ?.toMap()
        .orEmpty()
