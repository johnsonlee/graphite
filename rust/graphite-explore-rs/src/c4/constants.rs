//! Verbatim constants from `C4ModelConstants.kt` and `C4InferenceConstants.kt`.

pub const DEFAULT_SYSTEM_BOUNDARY: &str = "(default)";
pub const SUBJECT_APPLICATION_ID: &str = "system:application";
pub const SUBJECT_LIBRARY_ID: &str = "system:library";
pub const SUBJECT_FALLBACK_ID: &str = "system:subject";
pub const SUBJECT_FALLBACK_NAME: &str = "Subject";
pub const SOFTWARE_SYSTEM_SCOPE_KEY: &str = "softwareSystemId";
pub const CONTAINER_SCOPE_KEY: &str = "containerId";
pub const GRAPHITE_TAG: &str = "Graphite";
pub const SOFTWARE_SYSTEM_TAG: &str = "Software System";
pub const CONTAINER_TAG: &str = "Container";
pub const COMPONENT_TAG: &str = "Component";

pub const ARTIFACT_ID_PREFIX: &str = "artifact:";
pub const COMPONENT_ID_PREFIX: &str = "component:";
pub const CONTAINER_ID_PREFIX: &str = "container:";
pub const DEPENDENCY_ID_PREFIX: &str = "dependency:";
pub const DEPENDENCY_LIBRARY_ID_PREFIX: &str = "dependency:library:";
pub const NAMESPACE_ID_PREFIX: &str = "namespace:";
pub const RUNTIME_ID_PREFIX: &str = "runtime:";

pub const GRAPHITE_ARCHITECTURE_TYPE_PROPERTY: &str = "graphite.architectureType";
pub const GRAPHITE_AVAILABLE_LEVELS_PROPERTY: &str = "graphite.availableLevels";
pub const GRAPHITE_CONFIDENCE_PROPERTY: &str = "graphite.confidence";
pub const GRAPHITE_CONTAINER_PROPERTY: &str = "graphite.container";
pub const GRAPHITE_CONTAINER_ID_PROPERTY: &str = "graphite.containerId";
pub const GRAPHITE_EVIDENCE_PROPERTY: &str = "graphite.evidence";
pub const GRAPHITE_KIND_PROPERTY: &str = "graphite.kind";
pub const GRAPHITE_LEVEL_PROPERTY: &str = "graphite.level";
pub const GRAPHITE_RELATIONSHIP_KIND_PROPERTY: &str = "graphite.relationshipKind";
pub const GRAPHITE_RESPONSIBILITY_PROPERTY: &str = "graphite.responsibility";
pub const GRAPHITE_SOURCE_PROPERTY: &str = "graphite.source";
pub const GRAPHITE_SYSTEM_BOUNDARY_PROPERTY: &str = "graphite.systemBoundary";
pub const GRAPHITE_WEIGHT_PROPERTY: &str = "graphite.weight";

pub const WIRE_AVAILABLE_LEVELS: &str = "availableLevels";
pub const WIRE_CONTEXT: &str = "context";
pub const WIRE_DESCRIPTION: &str = "description";
pub const WIRE_ELEMENTS: &str = "elements";
pub const WIRE_EXTERNAL_DEPENDENCIES: &str = "externalDependencies";
pub const WIRE_ID: &str = "id";
pub const WIRE_KIND: &str = "kind";
pub const WIRE_LEVEL: &str = "level";
pub const WIRE_NAME: &str = "name";
pub const WIRE_RELATIONSHIPS: &str = "relationships";
pub const WIRE_RESPONSIBILITY: &str = "responsibility";
pub const WIRE_SKIPPED_REASON: &str = "skippedReason";
pub const WIRE_SYSTEM_BOUNDARY: &str = "systemBoundary";
pub const WIRE_TYPE: &str = "type";
pub const WIRE_WEIGHT: &str = "weight";

pub const WIRE_ACTOR: &str = "actor";
pub const WIRE_APPLICATION: &str = "application";
pub const WIRE_APPLICATION_RUNTIME: &str = "application-runtime";
pub const WIRE_APPLICATION_SERVICE: &str = "application-service";
pub const WIRE_CAPABILITY: &str = "capability";
pub const WIRE_COORDINATION: &str = "coordination";
pub const WIRE_DOMAIN_COMPONENT: &str = "domain-component";
pub const WIRE_ENTRYPOINT: &str = "entrypoint";
pub const WIRE_EXTERNAL_SYSTEM: &str = "external-system";
pub const WIRE_INTEGRATION: &str = "integration";
pub const WIRE_INTERFACE: &str = "interface";
pub const WIRE_LIBRARY: &str = "library";
pub const WIRE_ORCHESTRATOR: &str = "orchestrator";
pub const WIRE_RUNTIME: &str = "runtime";
pub const WIRE_SHARED_CAPABILITY: &str = "shared-capability";

pub mod view_limits {
    pub const UNBOUNDED_MODEL_ELEMENTS: usize = i32::MAX as usize;
    pub const DEFAULT_CONTEXT_DIAGRAM_ELEMENTS: usize = 12;
    pub const DEFAULT_CONTAINER_DIAGRAM_ELEMENTS: usize = 12;
    pub const DEFAULT_COMPONENT_DIAGRAM_ELEMENTS: usize = 16;
    pub const MAX_TEXT_DIAGRAM_EDGES: usize = 200;
    pub const MAX_INTERNAL_EDGES_PER_CONTAINER: usize = 1;
    pub const FALLBACK_MODEL_ELEMENTS: usize = UNBOUNDED_MODEL_ELEMENTS;
}

pub mod namespace_heuristics {
    pub const FAMILY_SEGMENTS: usize = 3;
    pub const DEFAULT_SEGMENTS: usize = 3;
    pub const REVERSE_DNS_ROOT_SEGMENTS: usize = 2;
    pub const NON_REVERSE_DNS_ROOT_SEGMENTS: usize = 1;
    pub const REVERSE_DNS_PREFIXES: [&str; 12] =
        ["app", "biz", "co", "com", "dev", "edu", "gov", "io", "me", "mil", "net", "org"];
}

pub mod evidence_limits {
    pub const MAX_PRIMARY_CLASSES_PER_CONTAINER: usize = 3;
    pub const MAX_ENTRYPOINTS_PER_CONTAINER: usize = 5;
    pub const MAX_CLASSES_PER_COMPONENT: usize = 5;
}

pub mod component_limits {
    pub const MAX_VIEW_EDGES: usize = 12;
    pub const MAX_OUTGOING_EDGES_PER_COMPONENT: i64 = 2;
    pub const MAX_INCOMING_EDGES_PER_COMPONENT: i64 = 2;
    pub const MIN_EDGES_AFTER_CAP_RELAXATION: usize = 6;
    pub const MIN_CAPABILITY_LAYOUT_CANDIDATES: usize = 8;
}

pub mod container_scoring {
    pub const METHOD_WEIGHT: i64 = 1;
    pub const ENDPOINT_WEIGHT: i64 = 10;
    pub const TRAFFIC_WEIGHT: i64 = 1;
    pub const REPRESENTATIVE_METHOD_WEIGHT: i64 = 2;
}

pub mod container_layer_ranks {
    pub const INTERFACE: i64 = 0;
    pub const ORCHESTRATION: i64 = 1;
    pub const CAPABILITY: i64 = 2;
    pub const SHARED_CAPABILITY: i64 = 3;
}

pub mod component_scoring {
    pub const ENDPOINT_WEIGHT: i64 = 300;
    pub const CROSS_CAPABILITY_WEIGHT: i64 = 200;
    pub const EXTERNAL_CALL_WEIGHT: i64 = 100;
    pub const METHOD_WEIGHT: i64 = 1;
    pub const CALL_WEIGHT: i64 = 1;
    pub const REPRESENTATIVE_CROSS_CAPABILITY_WEIGHT: i64 = 200;
    pub const REPRESENTATIVE_ENDPOINT_WEIGHT: i64 = 100;
    pub const LOW_SIGNAL_HELPER_PENALTY: i64 = 4;
}

pub mod naming_heuristics {
    pub const MIN_TOKENS_FOR_SECOND_TOKEN: usize = 2;
    pub const MIN_POSITIVE_TOKEN_SCORE: f64 = 0.0;
    pub const ACRONYM_TOKEN_MAX_LENGTH: usize = 3;
    pub const SECOND_TOKEN_MIN_SCORE_RATIO: f64 = 0.7;
}

pub mod reduction_limits {
    pub const MAX_TRANSITIVE_REDUCTION_EDGES: usize = 200;
    pub const HIERARCHY_REDUCTION_KINDS: [&str; 2] = ["runs-on", "builds-on"];
    pub const MAX_CONTAINER_ENTRYPOINTS_PER_SHARED_DEPENDENCY: usize = 2;
    pub const MAX_ENTRYPOINTS_PER_SHARED_CONTAINER: usize = 3;
}

pub mod boundary_heuristics {
    pub const MAX_PREFIX_DEPTH: usize = 4;
    pub const DOMINANCE_THRESHOLD: f64 = 0.85;
    pub const RUNNER_UP_SEPARATION: i64 = 3;
}

// Flat re-exports so callers do not need to know which grouping a constant lives in.
pub use boundary_heuristics::*;
pub use component_limits::*;
pub use container_scoring::*;
pub use evidence_limits::*;
pub use namespace_heuristics::*;
pub use naming_heuristics::*;
pub use view_limits::*;

pub const TECHNOLOGY_JVM_BYTECODE: &str = "JVM bytecode";
