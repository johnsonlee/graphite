package io.johnsonlee.graphite.cli.c4

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken

internal object C4Metadata {
    private const val TECHNOLOGY_JVM_BYTECODE = "JVM bytecode"
    private const val SOURCE_ARTIFACT_METADATA = "artifact-metadata"
    private const val CONFIDENCE_HIGH = "high"

    private val gson = GsonBuilder().create()
    private val mapType = object : TypeToken<Map<String, Any?>>() {}.type

    fun toProperties(metadata: Any?): Map<String, Any?> =
        when (metadata) {
            null -> emptyMap()
            is Map<*, *> -> metadata.entries.mapNotNull { (key, value) ->
                (key as? String)?.let { it to value }
            }.toMap()
            else -> gson.fromJson<Map<String, Any?>>(gson.toJsonTree(metadata), mapType)
        }.withoutNullOrEmptyValues()

    fun externalDependencyMetadata(dependency: ExternalDependency): ExternalDependencyMetadata =
        ExternalDependencyMetadata(
            confidence = dependency.confidence,
            source = dependency.source,
            artifacts = dependency.artifacts
        )

    fun runtimeDependencyMetadata(dependency: ExternalDependency): RuntimeDependencyMetadata =
        RuntimeDependencyMetadata(
            confidence = dependency.confidence,
            source = dependency.source
        )

    fun subjectMetadata(
        classCount: Int,
        methodCount: Int,
        containerCount: Int,
        endpointCount: Int,
        systemBoundary: String
    ): SubjectMetadata =
        SubjectMetadata(
            classes = classCount,
            methods = methodCount,
            containers = containerCount,
            endpoints = endpointCount,
            systemBoundary = systemBoundary,
            whySelected = "Dominant namespace boundary inferred from internal classes and call-site traffic"
        )

    fun externalDependencyEvidence(dependency: ExternalDependency): ExternalDependencyEvidence =
        ExternalDependencyEvidence(
            crossContainerCalls = dependency.weight,
            source = dependency.source,
            confidence = dependency.confidence,
            artifacts = dependency.artifacts
        )

    fun runtimeDependencyEvidence(dependency: ExternalDependency): RuntimeDependencyEvidence =
        RuntimeDependencyEvidence(
            source = dependency.source,
            kind = dependency.kind.wireName
        )

    fun boundaryDependencyEvidence(dependency: ExternalDependency, crossBoundaryCalls: Int): BoundaryDependencyEvidence =
        BoundaryDependencyEvidence(
            crossBoundaryCalls = crossBoundaryCalls,
            source = dependency.source,
            confidence = dependency.confidence
        )

    fun artifactDependencyEvidence(weight: Int): ArtifactDependencyEvidence =
        ArtifactDependencyEvidence(
            observedReferences = weight,
            source = SOURCE_ARTIFACT_METADATA,
            confidence = CONFIDENCE_HIGH
        )

    fun componentCallEvidence(calls: Int): ComponentCallEvidence =
        ComponentCallEvidence(calls = calls)

    fun containerMetadata(container: ContainerDescriptor, capabilityLayout: ContainerLayout): ContainerMetadata =
        ContainerMetadata(
            technology = TECHNOLOGY_JVM_BYTECODE,
            methods = container.methodCount,
            callSites = container.callSiteCount,
            endpoints = container.endpointCount,
            entrypoints = container.entrypoints,
            primaryClasses = container.primaryClasses,
            packageUnits = container.packageUnits.sorted(),
            internalCapabilities = capabilityLayout.containers.map(::internalCapabilityMetadata),
            whySelected = container.rationale
        )

    fun componentMetadata(
        capability: ContainerDescriptor,
        runtimeContainer: ContainerDescriptor,
        callSiteCount: Int,
        endpointCount: Int,
        externalCallCount: Int,
        representativeClasses: List<String>
    ): ComponentMetadata =
        ComponentMetadata(
            fullName = capability.packageUnits.sorted().joinToString(","),
            container = runtimeContainer.name,
            containerId = runtimeContainer.id,
            methods = capability.methodCount,
            callSites = callSiteCount,
            endpoints = endpointCount,
            incomingCrossContainerCalls = capability.inboundCrossContainer,
            outgoingCrossContainerCalls = capability.outboundCrossContainer,
            packageUnits = capability.packageUnits.sorted(),
            classes = representativeClasses,
            entrypoints = capability.entrypoints,
            whySelected = componentSelectionReasons(capability, endpointCount, externalCallCount)
        )

    private fun internalCapabilityMetadata(capability: ContainerDescriptor): InternalCapabilityMetadata =
        InternalCapabilityMetadata(
            name = capability.name,
            kind = containerKind(capability),
            packageUnits = capability.packageUnits.sorted(),
            methods = capability.methodCount,
            callSites = capability.callSiteCount,
            endpoints = capability.endpointCount,
            primaryClasses = capability.primaryClasses,
            whySelected = capability.rationale
        )

    private fun componentSelectionReasons(
        capability: ContainerDescriptor,
        endpointCount: Int,
        externalCallCount: Int
    ): List<String> =
        buildList {
            if (endpointCount > 0) add("entrypoint-facing capability")
            if (capability.inboundCrossContainer > 0) add("used by neighboring capabilities")
            if (capability.outboundCrossContainer > 0) add("depends on neighboring capabilities")
            if (externalCallCount > 0) add("external dependency touchpoint")
        }.ifEmpty { listOf("cohesive internal capability") }

    private fun Map<String, Any?>.withoutNullOrEmptyValues(): Map<String, Any?> =
        filterValues { value ->
            value != null && (value !is Collection<*> || value.isNotEmpty())
        }
}

internal data class ExternalDependencyMetadata(
    val confidence: String,
    val source: String,
    val artifacts: List<String> = emptyList()
)

internal data class RuntimeDependencyMetadata(
    val confidence: String,
    val source: String
)

internal data class SubjectMetadata(
    val classes: Int,
    val methods: Int,
    val containers: Int,
    val endpoints: Int,
    val systemBoundary: String,
    val whySelected: String
)

internal data class ContainerMetadata(
    val technology: String,
    val methods: Int,
    val callSites: Int,
    val endpoints: Int,
    val entrypoints: List<String>,
    val primaryClasses: List<String>,
    val packageUnits: List<String>,
    val internalCapabilities: List<InternalCapabilityMetadata>,
    val whySelected: String
)

internal data class InternalCapabilityMetadata(
    val name: String,
    val kind: String,
    val packageUnits: List<String>,
    val methods: Int,
    val callSites: Int,
    val endpoints: Int,
    val primaryClasses: List<String>,
    val whySelected: String
)

internal data class ComponentMetadata(
    val fullName: String,
    val container: String,
    val containerId: String,
    val methods: Int,
    val callSites: Int,
    val endpoints: Int,
    val incomingCrossContainerCalls: Int,
    val outgoingCrossContainerCalls: Int,
    val packageUnits: List<String>,
    val classes: List<String>,
    val entrypoints: List<String>,
    val whySelected: List<String>
)

internal data class ExternalDependencyEvidence(
    val crossContainerCalls: Int,
    val source: String,
    val confidence: String,
    val artifacts: List<String> = emptyList()
)

internal data class RuntimeDependencyEvidence(
    val source: String,
    val kind: String
)

internal data class BoundaryDependencyEvidence(
    val crossBoundaryCalls: Int,
    val source: String,
    val confidence: String
)

internal data class ArtifactDependencyEvidence(
    val observedReferences: Int,
    val source: String,
    val confidence: String
)

internal data class ComponentCallEvidence(
    val calls: Int
)
