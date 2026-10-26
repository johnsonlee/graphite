package io.johnsonlee.graphite.webgraph

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * The bindings a save records in `forward.properties`, the file BVGraph rewrites on every save: a
 * key an older writer does not know is dropped with the rest, so a sidecar it leaves behind names a
 * binding the directory no longer has and reads as absent.
 *
 * [DECLARED_TYPES_KEY] is the SHA-256 of `graph.types`, which the load verifies against the file.
 * [NODE_LAYOUT_KEY] is the SHA-256 of `graph.nodedata` as the save wrote it: the identity of which
 * node, with which members, sits at which node id. A sidecar indexed by node id (the presence bits,
 * the key summary) carries a copy and is read only when it names this directory's; two graphs with
 * one type table and different node layouts never accept each other's. No reader hashes the node
 * data to check it: the value is compared, as the save that wrote both files recorded it.
 */
internal object ForwardProperties {
    const val FILE_NAME = "forward.properties"
    const val DECLARED_TYPES_KEY = "graphite.declaredTypes.sha256"
    const val NODE_LAYOUT_KEY = "graphite.nodeLayout.sha256"

    fun read(dir: Path, key: String): String? {
        val path = dir.resolve(FILE_NAME)
        if (!Files.exists(path)) return null
        return Files.newInputStream(path).use { input -> Properties().apply { load(input) }.getProperty(key) }
    }

    /** Replaces each of [bindings] in the file; a null value removes the key. Other lines are kept. */
    fun write(dir: Path, bindings: Map<String, String?>) {
        val path = dir.resolve(FILE_NAME)
        val text = if (Files.exists(path)) Files.readString(path) else ""
        val lines = text.lineSequence().filterNot { line -> bindings.keys.any { line.startsWith("$it=") } }.toList()
        val original = lines.joinToString("\n").trimEnd('\n')
        val content = buildString {
            if (original.isNotEmpty()) append(original).append('\n')
            for ((key, value) in bindings) if (value != null) append(key).append('=').append(value).append('\n')
        }
        if (text != content) Files.writeString(path, content)
    }
}
