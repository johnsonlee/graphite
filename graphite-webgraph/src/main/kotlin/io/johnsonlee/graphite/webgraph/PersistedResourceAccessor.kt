package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.EmptyResourceAccessor
import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.ResourceEntry
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

internal data class PersistedResource(
    val path: String,
    val source: String,
    val content: ByteArray
)

internal class PersistedResourceAccessor(
    private val resources: Map<String, PersistedResource>
) : ResourceAccessor {

    override fun list(pattern: String): Sequence<ResourceEntry> {
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
        return resources.values.asSequence()
            .filter { matcher.matches(Path.of(it.path)) }
            .map { ResourceEntry(it.path, it.source) }
    }

    override fun open(path: String): InputStream =
        resources[path]?.let { ByteArrayInputStream(it.content) }
            ?: throw java.io.IOException("Resource not found: $path")
}

internal object PersistedResourceStore {
    private const val FILE_NAME = "graph.resources"
    private const val MAGIC = 0x47525200 // "GRR" + version byte
    private const val VERSION = 1
    private val PERSISTED_SUFFIXES = setOf(".properties", ".yml", ".yaml", ".json", ".xml", ".txt")

    fun save(graph: Graph, dir: Path) {
        val resources = collect(graph)
        if (resources.isEmpty()) return

        DataOutputStream(BufferedOutputStream(Files.newOutputStream(dir.resolve(FILE_NAME)))).use { dos ->
            writeHeader(dos)
            dos.writeInt(resources.size)
            resources.forEach { resource ->
                writeString(dos, resource.path)
                writeString(dos, resource.source)
                dos.writeInt(resource.content.size)
                dos.write(resource.content)
            }
        }
    }

    fun load(dir: Path): ResourceAccessor {
        val file = dir.resolve(FILE_NAME)
        if (!Files.exists(file)) return EmptyResourceAccessor
        DataInputStream(BufferedInputStream(Files.newInputStream(file))).use { dis ->
            readHeader(dis)
            val count = dis.readInt()
            val resources = LinkedHashMap<String, PersistedResource>(count)
            repeat(count) {
                val path = readString(dis)
                val source = readString(dis)
                val size = dis.readInt()
                val bytes = ByteArray(size)
                dis.readFully(bytes)
                resources[path] = PersistedResource(path, source, bytes)
            }
            return PersistedResourceAccessor(resources)
        }
    }

    private fun collect(graph: Graph): List<PersistedResource> {
        val resources = linkedMapOf<String, PersistedResource>()

        graph.resources.list("**")
            .filter { shouldPersist(it.path) }
            .forEach { entry ->
                runCatching {
                    graph.resources.open(entry.path).use { input ->
                        PersistedResource(
                            path = entry.path,
                            source = entry.source,
                            content = input.readBytes()
                        )
                    }
                }.getOrNull()?.let { resources.putIfAbsent(it.path, it) }
            }

        return resources.values.toList()
    }

    private fun shouldPersist(path: String): Boolean =
        PERSISTED_SUFFIXES.any { path.endsWith(it, ignoreCase = true) }

    private fun writeHeader(dos: DataOutputStream) {
        dos.writeInt(MAGIC or VERSION)
    }

    private fun readHeader(dis: DataInputStream) {
        val header = dis.readInt()
        require(header and 0xFFFFFF00.toInt() == MAGIC) { "Invalid resource file magic: 0x${header.toUInt().toString(16)}" }
        val version = header and 0xFF
        require(version == VERSION) { "Unsupported resource file version: $version" }
    }

    private fun writeString(dos: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        dos.writeInt(bytes.size)
        dos.write(bytes)
    }

    private fun readString(dis: DataInputStream): String {
        val size = dis.readInt()
        val bytes = ByteArray(size)
        dis.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}
