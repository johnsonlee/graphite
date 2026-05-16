package io.johnsonlee.graphite.cli.c4

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken

internal object C4WireCodec {
    private val gson = GsonBuilder().create()
    private val mapType = object : TypeToken<Map<String, Any?>>() {}.type

    private val elementCoreFields = setOf(
        WIRE_ID,
        WIRE_TYPE,
        WIRE_NAME,
        WIRE_DESCRIPTION,
        WIRE_KIND,
        "architectureType",
        WIRE_RESPONSIBILITY
    )

    private val relationshipCoreFields = setOf(
        "from",
        "to",
        WIRE_TYPE,
        WIRE_KIND,
        WIRE_DESCRIPTION,
        WIRE_WEIGHT,
        "evidence"
    )

    private val viewCoreFields = setOf(
        WIRE_TYPE,
        WIRE_ELEMENTS,
        WIRE_RELATIONSHIPS,
        WIRE_EXTERNAL_DEPENDENCIES,
        WIRE_SYSTEM_BOUNDARY,
        WIRE_SKIPPED_REASON
    )

    fun encode(model: C4ViewModel): Map<String, Any?> =
        WireModel(
            level = model.level.wireName,
            availableLevels = model.availableLevels.map { it.wireName },
            context = model.context?.let(::encode),
            container = model.container?.let(::encode),
            component = model.component?.let(::encode),
            view = model.view?.let(::encode)
        ).toJsonObject().toWireMap()

    fun encode(view: C4View): Map<String, Any?> =
        WireView(
            type = view.type.wireName,
            elements = view.elements.map(::encode),
            relationships = view.relationships.map(::encode),
            externalDependencies = view.externalDependencies.map(::encode).takeIf { it.isNotEmpty() },
            systemBoundary = view.systemBoundary,
            skippedReason = view.skippedReason
        ).toJsonObject()
            .flatten(view.properties)
            .toWireMap()

    fun encode(element: C4Element): Map<String, Any?> =
        WireElement(
            id = element.id,
            type = element.type.wireName,
            name = element.name,
            description = element.description,
            kind = element.kind?.wireName,
            architectureType = element.architectureType?.wireName,
            responsibility = element.responsibility
        ).toJsonObject()
            .flatten(element.properties)
            .toWireMap()

    fun encode(relationship: C4Relationship): Map<String, Any?> =
        WireRelationship(
            from = relationship.from,
            to = relationship.to,
            type = relationship.type.wireName,
            kind = relationship.kind?.wireName,
            description = relationship.description,
            weight = relationship.weight,
            evidence = relationship.evidence?.let(C4Metadata::toProperties)?.takeIf { it.isNotEmpty() }
        ).toJsonObject()
            .flatten(relationship.properties)
            .toWireMap()

    fun encode(dependency: ExternalDependency): Map<String, Any?> =
        WireDependency(
            id = dependency.id,
            name = dependency.name,
            weight = dependency.weight,
            source = dependency.source,
            kind = dependency.kind.wireName,
            confidence = dependency.confidence,
            responsibility = dependency.responsibility,
            artifacts = dependency.artifacts.takeIf { it.isNotEmpty() }
        ).toJsonObject().toWireMap()

    fun decodeModel(raw: Map<String, Any?>): C4ViewModel {
        val json = raw.toJsonObject()
        return C4ViewModel(
            level = C4Level.fromWire(json.string(WIRE_LEVEL)),
            availableLevels = json.stringList(WIRE_AVAILABLE_LEVELS).map(C4Level::fromWire),
            context = json.obj(WIRE_CONTEXT)?.let { decodeView(it.toWireMap()) },
            container = json.obj("container")?.let { decodeView(it.toWireMap()) },
            component = json.obj("component")?.let { decodeView(it.toWireMap()) },
            view = json.obj("view")?.let { decodeView(it.toWireMap()) }
        )
    }

    fun decodeView(raw: Map<String, Any?>): C4View {
        val json = raw.toJsonObject()
        return C4View(
            type = C4Level.fromWire(json.string(WIRE_TYPE)),
            elements = json.objectList(WIRE_ELEMENTS).map { decodeElement(it.toWireMap()) },
            relationships = json.objectList(WIRE_RELATIONSHIPS).map { decodeRelationship(it.toWireMap()) },
            externalDependencies = json.objectList(WIRE_EXTERNAL_DEPENDENCIES).map { decodeDependency(it.toWireMap()) },
            systemBoundary = json.string(WIRE_SYSTEM_BOUNDARY),
            skippedReason = json.string(WIRE_SKIPPED_REASON),
            properties = json.without(viewCoreFields)
        )
    }

    fun decodeElement(raw: Map<String, Any?>): C4Element {
        val json = raw.toJsonObject()
        return C4Element(
            id = json.string(WIRE_ID).orEmpty(),
            type = C4ElementType.fromWire(json.string(WIRE_TYPE)),
            name = json.string(WIRE_NAME).orEmpty(),
            description = json.string(WIRE_DESCRIPTION),
            kind = C4ElementKind.fromWire(json.string(WIRE_KIND)),
            architectureType = C4ArchitectureType.fromWire(json.string("architectureType")),
            responsibility = json.string(WIRE_RESPONSIBILITY),
            extensionProperties = json.without(elementCoreFields)
        )
    }

    fun decodeRelationship(raw: Map<String, Any?>): C4Relationship {
        val json = raw.toJsonObject()
        return C4Relationship(
            from = json.string("from").orEmpty(),
            to = json.string("to").orEmpty(),
            type = C4RelationshipType.fromWire(json.string(WIRE_TYPE)),
            kind = C4RelationshipKind.fromWire(json.string(WIRE_KIND)),
            description = json.string(WIRE_DESCRIPTION),
            weight = json.int(WIRE_WEIGHT),
            evidence = json.obj("evidence")?.toWireMap(),
            properties = json.without(relationshipCoreFields)
        )
    }

    fun decodeDependency(raw: Map<String, Any?>): ExternalDependency {
        val json = raw.toJsonObject()
        return ExternalDependency(
            id = json.string(WIRE_ID).orEmpty(),
            name = json.string(WIRE_NAME).orEmpty(),
            weight = json.int(WIRE_WEIGHT) ?: 0,
            source = json.string("source").orEmpty(),
            kind = ExternalDependencyKind.fromWire(json.string(WIRE_KIND)),
            confidence = json.string("confidence").orEmpty(),
            responsibility = json.string("responsibility").orEmpty(),
            artifacts = json.stringList("artifacts")
        )
    }

    private fun Any.toJsonObject(): JsonObject =
        gson.toJsonTree(this).asJsonObject

    private fun JsonObject.flatten(properties: Map<String, Any?>): JsonObject =
        apply {
            properties.forEach { (key, value) ->
                if (value != null) {
                    add(key, gson.toJsonTree(value))
                }
            }
        }

    private fun JsonObject.toWireMap(): Map<String, Any?> =
        gson.fromJson<Map<String, Any?>>(this, mapType).withoutNullValues()

    private fun JsonObject.without(keys: Set<String>): Map<String, Any?> {
        val copy = deepCopy()
        keys.forEach(copy::remove)
        return copy.toWireMap()
    }

    private fun Map<String, Any?>.withoutNullValues(): Map<String, Any?> =
        filterValues { it != null }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeUnless(JsonElement::isJsonNull)?.asString

    private fun JsonObject.int(name: String): Int? =
        get(name)?.takeUnless(JsonElement::isJsonNull)?.let { element ->
            when {
                element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asNumber.toInt()
                element.isJsonPrimitive && element.asJsonPrimitive.isString -> element.asString.toIntOrNull()
                else -> null
            }
        }

    private fun JsonObject.obj(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.objectList(name: String): List<JsonObject> =
        get(name)
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
            .orEmpty()

    private fun JsonObject.stringList(name: String): List<String> =
        get(name)
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { it.takeUnless(JsonElement::isJsonNull)?.asString }
            .orEmpty()

    private data class WireModel(
        val level: String,
        val availableLevels: List<String>,
        val context: Map<String, Any?>?,
        val container: Map<String, Any?>?,
        val component: Map<String, Any?>?,
        val view: Map<String, Any?>?
    )

    private data class WireView(
        val type: String,
        val elements: List<Map<String, Any?>>,
        val relationships: List<Map<String, Any?>>,
        val externalDependencies: List<Map<String, Any?>>?,
        val systemBoundary: String?,
        val skippedReason: String?
    )

    private data class WireElement(
        val id: String,
        val type: String,
        val name: String,
        val description: String?,
        val kind: String?,
        val architectureType: String?,
        val responsibility: String?
    )

    private data class WireRelationship(
        val from: String,
        val to: String,
        val type: String,
        val kind: String?,
        val description: String?,
        val weight: Int?,
        val evidence: Map<String, Any?>?
    )

    private data class WireDependency(
        val id: String,
        val name: String,
        val weight: Int,
        val source: String,
        val kind: String,
        val confidence: String,
        val responsibility: String,
        val artifacts: List<String>?
    )
}

internal fun relationshipFromMap(value: Map<String, Any?>): C4Relationship =
    C4WireCodec.decodeRelationship(value)

internal fun externalDependencyFromMap(value: Map<String, Any?>): ExternalDependency =
    C4WireCodec.decodeDependency(value)

internal fun apiEndpointEvidenceFromMap(value: Map<String, Any?>): ApiEndpointEvidence? {
    val json = GsonBuilder().create().toJsonTree(value).asJsonObject
    val className = json.get("class")?.takeUnless(JsonElement::isJsonNull)?.asString?.takeIf { it.isNotBlank() }
        ?: return null
    return ApiEndpointEvidence(
        className = className,
        path = json.get("path")?.takeUnless(JsonElement::isJsonNull)?.asString.orEmpty()
    )
}
