package io.johnsonlee.graphite.cli.c4

internal object C4ViewLimits {
    // JSON/DSL are machine-consumable architecture models, so their default
    // must not be capped for readability. Text renderers still apply their own
    // defaults and edge caps because they target human-readable diagrams.
    const val UNBOUNDED_MODEL_ELEMENTS = Int.MAX_VALUE

    // Context/container diagrams are high-level review artifacts. A dozen
    // visible elements gives room for the subject, primary actors, runtime, and
    // strongest external collaborators while still fitting on one screen.
    const val DEFAULT_CONTEXT_DIAGRAM_ELEMENTS = 12
    const val DEFAULT_CONTAINER_DIAGRAM_ELEMENTS = 12

    // Component diagrams are one zoom level deeper, so the default allows a
    // little more density without turning the view into a class/call graph.
    const val DEFAULT_COMPONENT_DIAGRAM_ELEMENTS = 16

    // Mermaid's GitHub secure renderer refuses diagrams with 500+ edges. 200 is
    // intentionally below that hard ceiling to leave room for notes/subgraphs,
    // and PlantUML uses the same cap so format choice does not change density.
    const val MAX_TEXT_DIAGRAM_EDGES = 200

    // Container diagrams should explain each container's strongest internal
    // dependency reason, not enumerate every internal call.
    const val MAX_INTERNAL_EDGES_PER_CONTAINER = 1

    // Defensive fallback for direct service/inferer calls that bypass HTTP
    // routing. Keep it aligned with the JSON/DSL model default.
    const val FALLBACK_MODEL_ELEMENTS = UNBOUNDED_MODEL_ELEMENTS
}

internal object C4NamespaceHeuristics {
    // Three package segments is a practical namespace-family fallback:
    // `org.apache.lucene` is useful, while `org.apache` is too broad and deeper
    // package names usually describe implementation detail. Artifact provenance
    // is preferred whenever available.
    const val FAMILY_SEGMENTS = 3
    const val DEFAULT_SEGMENTS = 3

    // Reverse-DNS packages such as `org.apache.lucene` need two root segments
    // before the project name can be discovered by dominance descent. Single-root
    // libraries such as `okhttp3.internal` or `joptsimple.internal` must keep the
    // first segment as the root; otherwise the boundary overfits to implementation
    // packages and excludes public API classes such as `okhttp3.OkHttpClient`.
    const val REVERSE_DNS_ROOT_SEGMENTS = 2
    const val NON_REVERSE_DNS_ROOT_SEGMENTS = 1

    val REVERSE_DNS_PREFIXES = setOf(
        "app",
        "biz",
        "co",
        "com",
        "dev",
        "edu",
        "gov",
        "io",
        "me",
        "mil",
        "net",
        "org"
    )
}

internal object C4EvidenceLimits {
    // Evidence examples are explanatory metadata, not C4 topology. These caps
    // must never remove Structurizr elements or relationships; they only keep
    // representative class/endpoint examples attached to each element.
    const val MAX_PRIMARY_CLASSES_PER_CONTAINER = 3
    const val MAX_ENTRYPOINTS_PER_CONTAINER = 5
    const val MAX_CLASSES_PER_COMPONENT = 5
}

internal object C4ComponentLimits {
    // A component view is a representative architecture view. Twelve edges lines
    // up with the default container/context density and avoids rebuilding a call
    // graph at component level.
    const val MAX_VIEW_EDGES = 12

    // Per-node degree caps prevent one central component from dominating the
    // diagram. The second pass relaxes caps only if the first pass made the graph
    // too sparse.
    const val MAX_OUTGOING_EDGES_PER_COMPONENT = 2
    const val MAX_INCOMING_EDGES_PER_COMPONENT = 2
    const val MIN_EDGES_AFTER_CAP_RELAXATION = 6
}

internal object C4NamingHeuristics {
    const val MIN_TOKENS_FOR_SECOND_TOKEN = 2
    const val MIN_POSITIVE_TOKEN_SCORE = 0.0

    // Short artifact tokens such as API, CLI, JVM, or JDK read better as
    // acronyms. Longer tokens are title-cased as normal words.
    const val ACRONYM_TOKEN_MAX_LENGTH = 3

    // Include a second token in an inferred name only when it is close to the
    // strongest token. This avoids noisy two-word names from weak TF-IDF tails.
    const val SECOND_TOKEN_MIN_SCORE_RATIO = 0.7
}

internal object C4ReductionLimits {
    // Alternate-path reduction is O(E^2). 200 matches the text-diagram edge cap,
    // so dense diagrams skip reduction instead of adding quadratic render cost.
    const val MAX_TRANSITIVE_REDUCTION_EDGES = 200

    // Layering relationships describe placement in an architecture stack rather
    // than independent call evidence. If A -> B -> C exists, A -> C adds noise
    // unless it is the only path. Non-hierarchy call/dependency edges still use
    // capacity-aware reduction so strong direct coupling remains visible.
    val HIERARCHY_REDUCTION_KINDS = setOf("runs-on", "builds-on")

    // Shared targets should keep enough inbound examples to explain why they are
    // shared, but not one edge per caller.
    const val MAX_CONTAINER_ENTRYPOINTS_PER_SHARED_DEPENDENCY = 2
    const val MAX_ENTRYPOINTS_PER_SHARED_CONTAINER = 3
}

internal object C4BoundaryHeuristics {
    // Boundary descent stops before implementation package depth. Four segments
    // can distinguish `org.apache.cassandra` from `org.apache.lucene` while
    // avoiding deep subsystem names as software-system boundaries.
    const val MAX_PREFIX_DEPTH = 4

    // Descend only when one child owns most of the current boundary evidence and
    // is clearly stronger than its nearest sibling. These thresholds prefer a
    // stable, slightly broader software-system boundary over overfitting.
    const val DOMINANCE_THRESHOLD = 0.85
    const val RUNNER_UP_SEPARATION = 3
}
