rootProject.name = "graphite"

include(":graphite-core")
include(":graphite-cypher")
include(":graphite-sootup")
include(":graphite-webgraph")
include(":graphite-explore")
include(":graphite-query")

// Strip "graphite-" prefix from project names so Maven artifactIds are clean:
// io.johnsonlee.graphite:core, io.johnsonlee.graphite:cypher, etc.
rootProject.children.forEach { project ->
    project.name = project.name.removePrefix("graphite-")
}
