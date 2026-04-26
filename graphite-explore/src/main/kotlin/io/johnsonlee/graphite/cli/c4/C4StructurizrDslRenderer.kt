package io.johnsonlee.graphite.cli.c4

internal class C4StructurizrDslRenderer {

    fun render(workspace: Map<String, Any?>): String {
        val model = workspace.mapValue("model")
        val views = workspace.mapValue("views")
        val identifiers = linkedMapOf<String, String>()
        val usedIdentifiers = mutableSetOf<String>()
        val lines = mutableListOf<String>()

        fun remember(id: String): String =
            identifiers.getOrPut(id) {
                val base = "g_${slugify(id).replace('-', '_').ifBlank { "element" }}"
                generateSequence(base) { candidate -> "${candidate}_${usedIdentifiers.size + 1}" }
                    .first { usedIdentifiers.add(it) }
            }

        lines += "workspace \"${dslString(workspace["name"]?.toString() ?: "Graphite C4 Workspace")}\" {"
        lines += "    model {"

        val people = model.listOfMaps("people")
        val systems = model.listOfMaps("softwareSystems")
        people.forEach { person ->
            val id = person.id() ?: return@forEach
            lines += "        ${remember(id)} = person \"${dslString(person.name())}\" \"${dslString(person.description())}\""
        }
        systems.forEach { system ->
            val id = system.id() ?: return@forEach
            val containers = system.listOfMaps("containers")
            if (containers.isEmpty()) {
                lines += "        ${remember(id)} = softwareSystem \"${dslString(system.name())}\" \"${dslString(system.description())}\""
            } else {
                lines += "        ${remember(id)} = softwareSystem \"${dslString(system.name())}\" \"${dslString(system.description())}\" {"
                containers.forEach { container ->
                    val containerId = container.id() ?: return@forEach
                    val components = container.listOfMaps("components")
                    if (components.isEmpty()) {
                        lines += "            ${remember(containerId)} = container \"${dslString(container.name())}\" \"${dslString(container.description())}\" \"${dslString(container.technology())}\""
                    } else {
                        lines += "            ${remember(containerId)} = container \"${dslString(container.name())}\" \"${dslString(container.description())}\" \"${dslString(container.technology())}\" {"
                        components.forEach { component ->
                            val componentId = component.id() ?: return@forEach
                            lines += "                ${remember(componentId)} = component \"${dslString(component.name())}\" \"${dslString(component.description())}\" \"${dslString(component.technology())}\""
                        }
                        lines += "            }"
                    }
                }
                lines += "        }"
            }
        }

        collectRelationships(people, systems).distinctBy { Triple(it.sourceId, it.destinationId, it.description) }
            .forEach { relationship ->
                val source = identifiers[relationship.sourceId] ?: return@forEach
                val target = identifiers[relationship.destinationId] ?: return@forEach
                lines += "        $source -> $target \"${dslString(relationship.description)}\""
            }

        lines += "    }"
        lines += "    views {"
        views.listOfMaps("systemContextViews").forEach { view ->
            val scope = view["softwareSystemId"]?.toString()?.let { identifiers[it] } ?: return@forEach
            lines += "        systemContext $scope \"${dslString(view.key("graphite-context"))}\" {"
            lines += "            include *"
            lines += "            autolayout tb"
            lines += "        }"
        }
        views.listOfMaps("containerViews").forEach { view ->
            val scope = view["softwareSystemId"]?.toString()?.let { identifiers[it] } ?: return@forEach
            lines += "        container $scope \"${dslString(view.key("graphite-container"))}\" {"
            lines += "            include *"
            lines += "            autolayout tb"
            lines += "        }"
        }
        views.listOfMaps("componentViews").forEach { view ->
            val scope = view["containerId"]?.toString()?.let { identifiers[it] } ?: return@forEach
            lines += "        component $scope \"${dslString(view.key("graphite-component"))}\" {"
            lines += "            include *"
            lines += "            autolayout tb"
            lines += "        }"
        }
        lines += "        theme default"
        lines += "    }"
        lines += "}"
        return lines.joinToString("\n")
    }

    private data class DslRelationship(
        val sourceId: String,
        val destinationId: String,
        val description: String
    )

    private fun collectRelationships(
        people: List<Map<String, Any?>>,
        systems: List<Map<String, Any?>>
    ): List<DslRelationship> {
        val relationships = mutableListOf<DslRelationship>()
        fun visit(source: Map<String, Any?>) {
            val sourceId = source.id() ?: return
            source.listOfMaps("relationships").forEach { relationship ->
                val destinationId = relationship["destinationId"]?.toString() ?: return@forEach
                relationships += DslRelationship(
                    sourceId = sourceId,
                    destinationId = destinationId,
                    description = relationship["description"]?.toString() ?: "uses"
                )
            }
        }

        people.forEach(::visit)
        systems.forEach { system ->
            visit(system)
            system.listOfMaps("containers").forEach { container ->
                visit(container)
                container.listOfMaps("components").forEach(::visit)
            }
        }
        return relationships
    }

    private fun dslString(value: String): String =
        value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", " ")
            .replace("\n", " ")

    private fun Map<String, Any?>.id(): String? = this["id"]?.toString()

    private fun Map<String, Any?>.name(): String = this["name"]?.toString().orEmpty()

    private fun Map<String, Any?>.description(): String = this["description"]?.toString().orEmpty()

    private fun Map<String, Any?>.technology(): String = this["technology"]?.toString().orEmpty()

    private fun Map<String, Any?>.key(default: String): String = this["key"]?.toString() ?: default

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.mapValue(key: String): Map<String, Any?> =
        this[key] as? Map<String, Any?> ?: emptyMap()

    private fun Map<String, Any?>.listOfMaps(key: String): List<Map<String, Any?>> =
        (this[key] as? List<*>).orEmpty().filterIsInstance<Map<String, Any?>>()
}
