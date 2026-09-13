rootProject.name = "graphite"

// JVM frontend modules live under frontend/jvm/<name>. Project names stay
// prefix-free so Maven artifactIds are clean: io.johnsonlee.graphite:core, etc.
listOf("core", "cypher", "sootup", "webgraph", "explore", "query").forEach { name ->
    include(":$name")
    project(":$name").projectDir = file("frontend/jvm/$name")
}
