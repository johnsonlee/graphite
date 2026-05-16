package io.johnsonlee.graphite.cli.c4

internal data class ApiEndpointEvidence(
    val className: String,
    val path: String
)

internal data class ExternalDependency(
    val id: String,
    val name: String,
    val weight: Int,
    val source: String,
    val kind: ExternalDependencyKind,
    val confidence: String,
    val responsibility: String,
    val artifacts: List<String> = emptyList()
)

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
    val externalDependencies: List<ExternalDependency>
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

internal data class ClassUtilityEvidence(
    val hasNamingSignal: Boolean,
    val hasEntrypointEvidence: Boolean,
    val hasCrossCapabilityEvidence: Boolean,
    val isSoleCapabilityClass: Boolean
) {
    val isLowSignalHelper: Boolean
        get() = hasNamingSignal && !hasEntrypointEvidence && !hasCrossCapabilityEvidence && !isSoleCapabilityClass
}

internal data class ManifestMetadata(
    val mainClass: String? = null,
    val startClass: String? = null
)

internal data class ContextDependencyCollapse(
    val dependencies: List<ExternalDependency>,
    val dependencyIdToContextId: Map<String, String>,
    val artifactToContextId: Map<String, String>
)

internal data class C4ViewModel(
    val level: C4Level,
    val availableLevels: List<C4Level>,
    val context: C4View? = null,
    val container: C4View? = null,
    val component: C4View? = null,
    val view: C4View? = null
)

internal data class C4Element(
    val id: String,
    val type: C4ElementType,
    val name: String,
    val description: String? = null,
    val kind: C4ElementKind? = null,
    val architectureType: C4ArchitectureType? = null,
    val responsibility: String? = null,
    val metadata: Any? = null,
    val extensionProperties: Map<String, Any?> = emptyMap()
) {
    val properties: Map<String, Any?>
        get() = C4Metadata.toProperties(metadata) + extensionProperties
}

internal data class C4Relationship(
    override val from: String,
    override val to: String,
    val type: C4RelationshipType,
    override val kind: C4RelationshipKind? = null,
    val description: String? = null,
    override val weight: Int? = null,
    val evidence: Any? = null,
    val properties: Map<String, Any?> = emptyMap()
) : C4DirectedEdge

internal data class C4View(
    val type: C4Level,
    val elements: List<C4Element>,
    val relationships: List<C4Relationship>,
    val externalDependencies: List<ExternalDependency> = emptyList(),
    val systemBoundary: String? = null,
    val skippedReason: String? = null,
    val properties: Map<String, Any?> = emptyMap()
) {
    val elementCount: Int
        get() = elements.size
}

internal interface C4DirectedEdge {
    val from: String
    val to: String
    val kind: C4RelationshipKind?
    val weight: Int?
}

internal interface C4WireEnum {
    val wireName: String
}

internal enum class C4Level(override val wireName: String) : C4WireEnum {
    CONTEXT("context"),
    CONTAINER("container"),
    COMPONENT("component"),
    ALL("all");

    companion object {
        val modelLevels: List<C4Level> = listOf(CONTEXT, CONTAINER, COMPONENT, ALL)
        val wireNames: List<String> = entries.map { it.wireName }

        fun fromWire(value: String?): C4Level =
            entries.firstOrNull { it.wireName == value } ?: ALL
    }
}

internal enum class C4ElementType(override val wireName: String) : C4WireEnum {
    PERSON("person"),
    SOFTWARE_SYSTEM("softwareSystem"),
    CONTAINER("container"),
    COMPONENT("component");

    companion object {
        fun fromWire(value: String?): C4ElementType =
            entries.firstOrNull { it.wireName == value } ?: SOFTWARE_SYSTEM
    }
}

internal enum class C4ElementKind(override val wireName: String) : C4WireEnum {
    ACTOR(WIRE_ACTOR),
    APPLICATION(WIRE_APPLICATION),
    LIBRARY(WIRE_LIBRARY),
    RUNTIME(WIRE_RUNTIME),
    EXTERNAL_SYSTEM(WIRE_EXTERNAL_SYSTEM),
    APPLICATION_RUNTIME(WIRE_APPLICATION_RUNTIME),
    APPLICATION_SERVICE(WIRE_APPLICATION_SERVICE),
    INTERFACE(WIRE_INTERFACE),
    INTEGRATION(WIRE_INTEGRATION),
    ORCHESTRATOR(WIRE_ORCHESTRATOR),
    SHARED_CAPABILITY(WIRE_SHARED_CAPABILITY),
    CAPABILITY(WIRE_CAPABILITY),
    ENTRYPOINT(WIRE_ENTRYPOINT),
    COORDINATION(WIRE_COORDINATION),
    DOMAIN_COMPONENT(WIRE_DOMAIN_COMPONENT);

    companion object {
        fun fromWire(value: String?): C4ElementKind? =
            entries.firstOrNull { it.wireName == value }
    }
}

internal enum class C4ArchitectureType(override val wireName: String) : C4WireEnum {
    ACTOR(WIRE_ACTOR),
    SOFTWARE_SYSTEM("software-system"),
    LIBRARY(WIRE_LIBRARY),
    APPLICATION_RUNTIME(WIRE_APPLICATION_RUNTIME),
    APPLICATION_SERVICE(WIRE_APPLICATION_SERVICE),
    APPLICATION_COMPONENT("application-component"),
    RUNTIME_PLATFORM("runtime-platform"),
    EXTERNAL_LIBRARY("external-library"),
    EXTERNAL_SYSTEM(WIRE_EXTERNAL_SYSTEM);

    companion object {
        fun fromWire(value: String?): C4ArchitectureType? =
            entries.firstOrNull { it.wireName == value }
    }
}

internal enum class ExternalDependencyKind(override val wireName: String) : C4WireEnum {
    RUNTIME(WIRE_RUNTIME),
    LIBRARY(WIRE_LIBRARY),
    EXTERNAL_SYSTEM(WIRE_EXTERNAL_SYSTEM);

    companion object {
        fun fromWire(value: String?): ExternalDependencyKind =
            entries.firstOrNull { it.wireName == value } ?: EXTERNAL_SYSTEM
    }
}

internal enum class C4RelationshipType(override val wireName: String) : C4WireEnum {
    USES("uses"),
    RUNS_ON("runs-on"),
    ROUTES_TO("routes-to"),
    ORCHESTRATES("orchestrates"),
    BUILDS_ON("builds-on"),
    COLLABORATES_WITH("collaborates-with");

    companion object {
        fun fromWire(value: String?): C4RelationshipType =
            entries.firstOrNull { it.wireName == value } ?: USES
    }
}

internal enum class C4RelationshipKind(override val wireName: String) : C4WireEnum {
    USES("uses"),
    RUNS_ON("runs-on"),
    BUILDS_ON("builds-on"),
    DEPENDS_ON("depends-on"),
    ROUTES_TO("routes-to"),
    ORCHESTRATES("orchestrates"),
    COLLABORATES_WITH("collaborates-with");

    companion object {
        fun fromWire(value: String?): C4RelationshipKind? =
            entries.firstOrNull { it.wireName == value }

        fun fromType(type: C4RelationshipType): C4RelationshipKind =
            when (type) {
                C4RelationshipType.USES -> USES
                C4RelationshipType.RUNS_ON -> RUNS_ON
                C4RelationshipType.ROUTES_TO -> ROUTES_TO
                C4RelationshipType.ORCHESTRATES -> ORCHESTRATES
                C4RelationshipType.BUILDS_ON -> BUILDS_ON
                C4RelationshipType.COLLABORATES_WITH -> COLLABORATES_WITH
            }
    }
}

internal fun String.toC4ElementKind(): C4ElementKind =
    C4ElementKind.fromWire(this) ?: C4ElementKind.CAPABILITY

internal fun String.toC4RelationshipKind(): C4RelationshipKind =
    C4RelationshipKind.fromWire(this) ?: C4RelationshipKind.USES

internal fun String.toC4RelationshipType(): C4RelationshipType =
    C4RelationshipType.fromWire(this)

internal fun String.toC4ArchitectureType(): C4ArchitectureType? =
    C4ArchitectureType.fromWire(this)

internal fun ExternalDependencyKind.toElementKind(): C4ElementKind =
    when (this) {
        ExternalDependencyKind.RUNTIME -> C4ElementKind.RUNTIME
        ExternalDependencyKind.LIBRARY -> C4ElementKind.LIBRARY
        ExternalDependencyKind.EXTERNAL_SYSTEM -> C4ElementKind.EXTERNAL_SYSTEM
    }

internal fun C4ElementKind.toArchitectureType(): C4ArchitectureType =
    when (this) {
        C4ElementKind.ACTOR -> C4ArchitectureType.ACTOR
        C4ElementKind.APPLICATION -> C4ArchitectureType.SOFTWARE_SYSTEM
        C4ElementKind.LIBRARY -> C4ArchitectureType.LIBRARY
        C4ElementKind.RUNTIME -> C4ArchitectureType.RUNTIME_PLATFORM
        C4ElementKind.EXTERNAL_SYSTEM -> C4ArchitectureType.EXTERNAL_SYSTEM
        C4ElementKind.APPLICATION_RUNTIME -> C4ArchitectureType.APPLICATION_RUNTIME
        C4ElementKind.APPLICATION_SERVICE -> C4ArchitectureType.APPLICATION_SERVICE
        C4ElementKind.INTERFACE,
        C4ElementKind.INTEGRATION,
        C4ElementKind.ORCHESTRATOR,
        C4ElementKind.COORDINATION,
        C4ElementKind.ENTRYPOINT -> C4ArchitectureType.APPLICATION_SERVICE
        C4ElementKind.SHARED_CAPABILITY,
        C4ElementKind.CAPABILITY,
        C4ElementKind.DOMAIN_COMPONENT -> C4ArchitectureType.APPLICATION_COMPONENT
    }

internal fun ExternalDependencyKind.toArchitectureType(): C4ArchitectureType =
    when (this) {
        ExternalDependencyKind.RUNTIME -> C4ArchitectureType.RUNTIME_PLATFORM
        ExternalDependencyKind.LIBRARY -> C4ArchitectureType.EXTERNAL_LIBRARY
        ExternalDependencyKind.EXTERNAL_SYSTEM -> C4ArchitectureType.EXTERNAL_SYSTEM
    }

internal fun C4WireEnum?.wireNameOrNull(): String? =
    this?.wireName
