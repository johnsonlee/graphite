package io.johnsonlee.graphite.cli.c4

internal data class ContainerDescriptor(
    val id: String,
    val name: String,
    val packageUnits: Set<String>,
    val methodCount: Int,
    val callSiteCount: Int,
    val endpointCount: Int,
    val inboundCrossContainer: Int,
    val outboundCrossContainer: Int,
    val externalCallCount: Int,
    val entrypoints: List<String>,
    val primaryClasses: List<String>,
    val rationale: String,
    val declaredKind: String? = null
)

internal data class ContainerLayout(
    val systemBoundary: String,
    val containers: List<ContainerDescriptor>,
    val unitToContainerId: Map<String, String>,
    val externalDependencies: List<Map<String, Any?>>
)

internal data class SubjectDescriptor(
    val id: String,
    val name: String,
    val role: String,
    val description: String,
    val responsibility: String,
    val actorId: String?,
    val actorName: String?,
    val actorDescription: String?,
    val actorResponsibility: String?
)

internal data class MainReachability(
    val mainMethodCount: Int,
    val reachableInternalMethodCount: Int,
    val reachableInternalClassCount: Int,
    val reachableExternalTargetCount: Int
)

internal data class ManifestMetadata(
    val mainClass: String? = null,
    val startClass: String? = null
)

internal data class ContextDependencyCollapse(
    val dependencies: List<Map<String, Any?>>,
    val dependencyIdToContextId: Map<String, String>,
    val artifactToContextId: Map<String, String>
)

internal data class C4Element(
    val id: String,
    val type: String,
    val name: String,
    val description: String? = null,
    val kind: String? = null,
    val architectureType: String? = null,
    val responsibility: String? = null,
    val properties: Map<String, Any?> = emptyMap()
) {
    fun toMap(): Map<String, Any?> =
        (mapOf(
            "id" to id,
            "type" to type,
            "name" to name,
            "description" to description,
            "kind" to kind,
            "architectureType" to architectureType,
            "responsibility" to responsibility
        ) + properties).toMapWithoutNulls()
}

internal data class C4Relationship(
    val from: String,
    val to: String,
    val type: String,
    val kind: String? = null,
    val description: String? = null,
    val weight: Int? = null,
    val evidence: Map<String, Any?> = emptyMap(),
    val properties: Map<String, Any?> = emptyMap()
) {
    fun toMap(): Map<String, Any?> =
        (mapOf(
            "from" to from,
            "to" to to,
            "type" to type,
            "kind" to kind,
            "description" to description,
            "weight" to weight,
            "evidence" to evidence.takeIf { it.isNotEmpty() }
        ) + properties).toMapWithoutNulls()
}

internal data class C4View(
    val type: String,
    val elements: List<C4Element>,
    val relationships: List<C4Relationship>,
    val properties: Map<String, Any?> = emptyMap()
) {
    fun toMap(): Map<String, Any?> =
        (mapOf(
            "type" to type,
            "elements" to elements.map { it.toMap() },
            "relationships" to relationships.map { it.toMap() }
        ) + properties).toMapWithoutNulls()
}

internal fun relationshipFromMap(value: Map<String, Any?>): C4Relationship =
    C4Relationship(
        from = value["from"]?.toString().orEmpty(),
        to = value["to"]?.toString().orEmpty(),
        type = value["type"]?.toString().orEmpty(),
        kind = value["kind"]?.toString(),
        description = value["description"]?.toString(),
        weight = value["weight"] as? Int,
        evidence = mapValue(value["evidence"]),
        properties = value - setOf("from", "to", "type", "kind", "description", "weight", "evidence")
    )

private fun mapValue(value: Any?): Map<String, Any?> =
    (value as? Map<*, *>)
        ?.mapNotNull { (key, mapValue) -> key?.toString()?.let { it to mapValue } }
        ?.toMap()
        ?: emptyMap()
