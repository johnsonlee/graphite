package io.johnsonlee.graphite.cli.c4

internal class C4StructurizrDslRenderer {

    fun render(workspace: Map<String, Any?>): String =
        render(C4StructurizrCodec.decode(workspace))

    fun render(workspace: C4StructurizrWorkspace): String {
        val identifiers = DslIdentifierRegistry()
        return structurizrDsl {
            block("workspace", quoted(workspace.name)) {
                block("model") {
                    workspace.model.people.forEach { person ->
                        element(identifiers.remember(person.id), "person", person.name, person.description)
                    }
                    workspace.model.softwareSystems.forEach { system ->
                        system(identifiers, system)
                    }

                    collectRelationships(workspace.model)
                        .distinctBy { Triple(it.sourceId, it.destinationId, it.description) }
                        .forEach { relationship ->
                            val source = identifiers[relationship.sourceId] ?: return@forEach
                            val target = identifiers[relationship.destinationId] ?: return@forEach
                            relationship(source, target, relationship.description)
                        }
                }
                block("views") {
                    workspace.views.systemContextViews.forEach { view ->
                        val scope = view.softwareSystemId?.let { identifiers[it] } ?: return@forEach
                        viewBlock("systemContext", scope, view.key.ifBlank { "graphite-context" })
                    }
                    workspace.views.containerViews.forEach { view ->
                        val scope = view.softwareSystemId?.let { identifiers[it] } ?: return@forEach
                        viewBlock("container", scope, view.key.ifBlank { "graphite-container" })
                    }
                    workspace.views.componentViews.forEach { view ->
                        val scope = view.containerId?.let { identifiers[it] } ?: return@forEach
                        viewBlock("component", scope, view.key.ifBlank { "graphite-component" })
                    }
                    line("theme default")
                }
            }
        }
    }

    private fun StructurizrDslWriter.system(
        identifiers: DslIdentifierRegistry,
        system: C4StructurizrElement
    ) {
        if (system.containers.isEmpty()) {
            element(identifiers.remember(system.id), "softwareSystem", system.name, system.description)
            return
        }

        block("${identifiers.remember(system.id)} = softwareSystem ${quoted(system.name)} ${quoted(system.description)}") {
            system.containers.forEach { container ->
                container(identifiers, container)
            }
        }
    }

    private fun StructurizrDslWriter.container(
        identifiers: DslIdentifierRegistry,
        container: C4StructurizrElement
    ) {
        if (container.components.isEmpty()) {
            element(
                identifiers.remember(container.id),
                "container",
                container.name,
                container.description,
                container.technology.orEmpty()
            )
            return
        }

        val containerDeclaration = "${identifiers.remember(container.id)} = container " +
            "${quoted(container.name)} ${quoted(container.description)} ${quoted(container.technology.orEmpty())}"
        block(containerDeclaration) {
            container.components.forEach { component ->
                element(
                    identifiers.remember(component.id),
                    "component",
                    component.name,
                    component.description,
                    component.technology.orEmpty()
                )
            }
        }
    }

    private data class DslRelationship(
        val sourceId: String,
        val destinationId: String,
        val description: String
    )

    private fun collectRelationships(model: C4StructurizrModel): List<DslRelationship> {
        val relationships = mutableListOf<DslRelationship>()
        fun visit(source: C4StructurizrElement) {
            source.relationships.forEach { relationship ->
                relationships += DslRelationship(
                    sourceId = source.id,
                    destinationId = relationship.destinationId,
                    description = relationship.description
                )
            }
        }

        model.people.forEach(::visit)
        model.softwareSystems.forEach { system ->
            visit(system)
            system.containers.forEach { container ->
                visit(container)
                container.components.forEach(::visit)
            }
        }
        return relationships
    }

    private class DslIdentifierRegistry {
        private val identifiers = linkedMapOf<String, String>()
        private val usedIdentifiers = mutableSetOf<String>()

        operator fun get(id: String): String? =
            identifiers[id]

        fun remember(id: String): String =
            identifiers.getOrPut(id) {
                val base = "g_${slugify(id).replace('-', '_').ifBlank { "element" }}"
                generateSequence(base) { candidate -> "${candidate}_${usedIdentifiers.size + 1}" }
                    .first { usedIdentifiers.add(it) }
            }
    }

    private fun dslString(value: String): String =
        value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", " ")
            .replace("\n", " ")

    private fun quoted(value: String): String =
        "\"${dslString(value)}\""

    private fun structurizrDsl(block: StructurizrDslWriter.() -> Unit): String =
        StructurizrDslWriter().apply(block).toString()

    private class StructurizrDslWriter {
        private val lines = mutableListOf<String>()
        private var depth = 0

        fun block(command: String, argument: String? = null, body: StructurizrDslWriter.() -> Unit) {
            line(listOf(command, argument).filterNotNull().joinToString(" ") + " {")
            depth += 1
            body()
            depth -= 1
            line("}")
        }

        fun element(identifier: String, type: String, name: String, description: String, technology: String? = null) {
            line(listOfNotNull(
                "$identifier = $type",
                quote(name),
                quote(description),
                technology?.let(::quote)
            ).joinToString(" "))
        }

        fun relationship(source: String, target: String, description: String) {
            line("$source -> $target ${quote(description)}")
        }

        fun viewBlock(type: String, scope: String, key: String) {
            block("$type $scope ${quote(key)}") {
                line("include *")
                line("autolayout tb")
            }
        }

        fun line(value: String) {
            lines += "${"    ".repeat(depth)}$value"
        }

        override fun toString(): String =
            lines.joinToString("\n")

        private fun quote(value: String): String =
            "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " ")}\""
    }
}
