package io.johnsonlee.graphite.c4

import io.johnsonlee.graphite.c4.C4Tags
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.graph.Graph

/**
 * Assembles a [C4Model] from a saved graph.
 *
 * Stereotype and external-system labels are read straight from the synthetic
 * annotations baked at build time (see [C4Tags]); the builder only handles
 * parametric concerns: include/exclude filtering, component grouping, and
 * relationship aggregation.
 */
object C4ModelBuilder {

    private const val CLASS_MEMBER = "<class>"
    private const val APP_CONTAINER_ID = "container.app"

    fun build(graph: Graph, options: C4Options): C4Model {
        val classNames = collectIncludedClasses(graph, options)
        val classToComponentName = mutableMapOf<String, String>()
        for (cls in classNames) {
            classToComponentName[cls] = componentNameFor(graph, cls, options)
        }

        val componentNameToClasses = classToComponentName.entries
            .groupBy({ it.value }, { it.key })
            .toSortedMap()

        val components = componentNameToClasses.entries.map { (name, classes) ->
            Component(
                id = componentId(name),
                name = name,
                technology = technologyFor(name),
                description = descriptionFor(name),
                classNames = classes.sorted()
            )
        }

        val classToComponentId = classToComponentName.mapValues { componentId(it.value) }

        val externalContainerByLabel = mutableMapOf<String, ExternalContainer>()
        val componentToComponent = mutableMapOf<Pair<String, String>, Int>()
        val componentToExternal = mutableMapOf<Pair<String, String>, Int>()

        graph.nodes(CallSiteNode::class.java).forEach { cs ->
            val callerClass = cs.caller.declaringClass.className
            val calleeClass = cs.callee.declaringClass.className
            val fromComponent = classToComponentId[callerClass] ?: return@forEach
            val toComponent = classToComponentId[calleeClass]
            if (toComponent != null) {
                if (fromComponent != toComponent) {
                    val key = fromComponent to toComponent
                    componentToComponent[key] = (componentToComponent[key] ?: 0) + 1
                }
                return@forEach
            }
            val externalLabel = externalSystemLabel(graph, calleeClass) ?: return@forEach
            val external = externalContainerByLabel.getOrPut(externalLabel) {
                ExternalContainer(
                    id = externalContainerId(externalLabel),
                    name = externalLabel,
                    technology = externalTechnologyFor(externalLabel)
                )
            }
            val key = fromComponent to external.id
            componentToExternal[key] = (componentToExternal[key] ?: 0) + 1
        }

        val person = if (components.any { it.name == C4Tags.Stereotype.API }) {
            Person(id = "person.user", name = "User", description = "End user via HTTP")
        } else null

        val apiComponentId = components.firstOrNull { it.name == C4Tags.Stereotype.API }?.id

        val relationships = buildList {
            componentToComponent.forEach { (key, weight) ->
                add(Relationship(from = key.first, to = key.second, description = "calls", technology = null, weight = weight))
            }
            componentToExternal.forEach { (key, weight) ->
                val tech = externalContainerByLabel.values.firstOrNull { it.id == key.second }?.technology
                add(Relationship(from = key.first, to = key.second, description = "uses", technology = tech, weight = weight))
            }
            if (person != null && apiComponentId != null) {
                add(Relationship(from = person.id, to = apiComponentId, description = "uses", technology = "HTTPS", weight = 1))
            }
        }

        val container = Container(
            id = APP_CONTAINER_ID,
            name = options.systemName,
            technology = "Java",
            components = components
        )

        return C4Model(
            systemName = options.systemName,
            level = options.level,
            containers = listOf(container),
            externalContainers = externalContainerByLabel.values.sortedBy { it.name },
            persons = listOfNotNull(person),
            relationships = relationships.sortedWith(compareBy({ it.from }, { it.to }))
        )
    }

    private fun collectIncludedClasses(graph: Graph, options: C4Options): Set<String> {
        val classes = sortedSetOf<String>()
        graph.nodes(CallSiteNode::class.java).forEach {
            classes.add(it.caller.declaringClass.className)
        }
        graph.nodes(FieldNode::class.java).forEach {
            classes.add(it.descriptor.declaringClass.className)
        }
        graph.nodes(ParameterNode::class.java).forEach {
            classes.add(it.method.declaringClass.className)
        }
        graph.nodes(ReturnNode::class.java).forEach {
            classes.add(it.method.declaringClass.className)
        }
        graph.typeHierarchyTypes().forEach { classes.add(it) }
        return classes.filter { matchesIncludes(it, options) }.toSet()
    }

    private fun matchesIncludes(className: String, options: C4Options): Boolean {
        if (options.exclude.any { className.startsWith(it) }) return false
        if (options.include.isEmpty()) return true
        return options.include.any { className.startsWith(it) }
    }

    private fun componentNameFor(graph: Graph, className: String, options: C4Options): String {
        if (!options.groupByPackage) {
            val annotations = graph.memberAnnotations(className, CLASS_MEMBER)
            val stereotype = (annotations[C4Tags.STEREOTYPE_ANNOTATION]?.get(C4Tags.VALUE_ATTRIBUTE) as? String)
            if (stereotype != null) return stereotype
        }
        return packageGroupName(className, options.groupDepth)
    }

    private fun packageGroupName(className: String, depth: Int): String {
        val pkg = className.substringBeforeLast('.', missingDelimiterValue = "")
        if (pkg.isEmpty()) return "Domain: <root>"
        val parts = pkg.split('.')
        val truncated = if (parts.size <= depth) pkg else parts.take(depth).joinToString(".")
        return "Domain: $truncated"
    }

    private fun externalSystemLabel(graph: Graph, calleeClass: String): String? {
        val annotations = graph.memberAnnotations(calleeClass, CLASS_MEMBER)
        return annotations[C4Tags.EXTERNAL_SYSTEM_ANNOTATION]?.get(C4Tags.VALUE_ATTRIBUTE) as? String
    }

    private fun componentId(name: String): String = "component." + sanitizeId(name)

    private fun externalContainerId(name: String): String = "external." + sanitizeId(name)

    private fun sanitizeId(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "x" }

    private fun technologyFor(componentName: String): String? = when (componentName) {
        C4Tags.Stereotype.API -> "Spring MVC"
        C4Tags.Stereotype.SERVICE -> "Spring"
        C4Tags.Stereotype.DATA_ACCESS -> "Spring Data"
        C4Tags.Stereotype.CONFIG -> "Spring"
        else -> null
    }

    private fun descriptionFor(componentName: String): String? = when (componentName) {
        C4Tags.Stereotype.API -> "REST endpoints / web layer"
        C4Tags.Stereotype.SERVICE -> "Business logic"
        C4Tags.Stereotype.DATA_ACCESS -> "Persistence layer"
        C4Tags.Stereotype.CONFIG -> "Configuration beans"
        else -> null
    }

    private fun externalTechnologyFor(label: String): String? = when (label) {
        C4Tags.ExternalSystem.DATABASE -> "JDBC / JPA"
        C4Tags.ExternalSystem.REDIS -> "Redis Client"
        C4Tags.ExternalSystem.KAFKA -> "Kafka"
        C4Tags.ExternalSystem.RABBITMQ -> "AMQP"
        C4Tags.ExternalSystem.EXTERNAL_HTTP_API -> "HTTP"
        C4Tags.ExternalSystem.ELASTICSEARCH -> "Elasticsearch"
        C4Tags.ExternalSystem.AWS -> "AWS SDK"
        else -> null
    }
}
