package io.johnsonlee.graphite.c4

/**
 * Materialised C4 view derived from a [io.johnsonlee.graphite.graph.Graph].
 *
 * Three viewpoints are derived from the same model — see [C4Level] — by
 * folding components or containers at render time.
 */
data class C4Model(
    val systemName: String,
    val level: C4Level,
    val containers: List<Container>,
    val externalContainers: List<ExternalContainer>,
    val persons: List<Person>,
    val relationships: List<Relationship>
)

enum class C4Level { CONTEXT, CONTAINER, COMPONENT }

data class Container(
    val id: String,
    val name: String,
    val technology: String?,
    val components: List<Component>
)

data class ExternalContainer(
    val id: String,
    val name: String,
    val technology: String?
)

data class Component(
    val id: String,
    val name: String,
    val technology: String?,
    val description: String?,
    val classNames: List<String>
)

data class Person(
    val id: String,
    val name: String,
    val description: String?
)

data class Relationship(
    val from: String,
    val to: String,
    val description: String,
    val technology: String?,
    val weight: Int
)

/**
 * Query-time options that control how the model is assembled.
 */
data class C4Options(
    val systemName: String = "Application",
    val level: C4Level = C4Level.COMPONENT,
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val groupByPackage: Boolean = false,
    val groupDepth: Int = 3
)
