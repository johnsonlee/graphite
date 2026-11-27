package io.johnsonlee.graphite.webgraph

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties

/** A persisted graph that is not the graph its manifest describes: a file is missing, cut short, replaced or of a newer format. */
class GraphIntegrityException(message: String) : IllegalStateException(message)

/**
 * The inventory of a saved graph directory (`graph.manifest`), written last by every writer.
 *
 * It answers three questions no single sidecar could: which files make up the graph (one list, instead of each reader and writer
 * knowing its own subset), which producer wrote them (`@producer`), and whether the bytes on disk are still the ones that were written.
 * The format is the container manifest's (`META-INF/graphite.manifest`): a header line, then `sha256  size  name` per file, with
 * `@key value` attribute lines before the entries. A reader checks every listed file's presence and size at load, which costs one `stat`
 * per file and catches truncation, partial copies and files replaced by another graph's; `verify` hashes the bytes. A directory without a
 * manifest is a graph written before it existed and is read as before.
 *
 * The inventory proves itself complete: its last line, `@seal <sha256>`, is the SHA-256 of every byte before it, and it must list each
 * file every writer produces ([REQUIRED_FILES]). A manifest cut short at a line boundary, down to its header, therefore fails instead of
 * certifying the fewer files it still names.
 */
object GraphManifest {
    const val FILE_NAME = "graph.manifest"
    const val HEADER = "graphite-graph 1"
    const val PRODUCER = "producer"
    const val STORAGE_FORMAT = "storage-format"
    const val SEAL = "seal"

    /** The files every graph writer produces; a manifest that does not list them all is not the inventory of a complete graph. */
    val REQUIRED_FILES = listOf(
        "forward.graph", "forward.offsets", "forward.properties", "graph.labels", "graph.metadata", "graph.nodedata",
        "graph.nodeoffsets", "graph.strings", "graph.typeindex",
    )

    /** The storage format this reader understands. A manifest naming a later one was written by a newer producer and is refused. */
    const val CURRENT_STORAGE_FORMAT = 1

    private const val ENTRY_SEPARATOR = "  "
    private const val ENTRY_FIELDS = 3
    private const val SHA256_HEX_LENGTH = 64
    private const val READ_BUFFER_BYTES = 1 shl 16

    data class Entry(val size: Long, val sha256: String)

    class Manifest(val attributes: Map<String, String>, val entries: Map<String, Entry>, val digest: String) {
        val producer: String?
            get() = attributes[PRODUCER]
    }

    /** `graphite-jvm <version>` of the writer in this class path. */
    val producer: String by lazy { "graphite-jvm ${producerVersion()}" }

    internal fun producerVersion(resource: String = "producer.properties"): String {
        val properties = Properties()
        GraphManifest::class.java.getResourceAsStream(resource)?.use(properties::load)
        return properties.getProperty("version")?.takeIf { it.isNotBlank() && !it.startsWith("\${") } ?: "unknown"
    }

    /** Writes the manifest of every regular file in [dir]; the files must be complete. Returns the manifest written. */
    fun write(dir: Path, attributes: Map<String, String> = emptyMap()): Manifest {
        val names = Files.list(dir).use { paths -> paths.filter(Files::isRegularFile).map { it.fileName.toString() }.toList() }
        val entries = names.filter { it != FILE_NAME }.sorted().associateWith { name -> describe(dir.resolve(name)) }
        val all = linkedMapOf(PRODUCER to producer, STORAGE_FORMAT to CURRENT_STORAGE_FORMAT.toString())
        all.putAll(attributes)
        val text = render(all, entries)
        val bytes = text.toByteArray(Charsets.UTF_8)
        Files.write(dir.resolve(FILE_NAME), bytes)
        return Manifest(all, entries, sha256Hex(bytes))
    }

    /** The manifest of [dir], or null for a graph written before manifests existed. */
    fun read(dir: Path): Manifest? {
        val path = dir.resolve(FILE_NAME)
        if (!Files.isRegularFile(path)) return null
        val bytes = Files.readAllBytes(path)
        return parse(bytes, path.toString())
    }

    internal fun parse(bytes: ByteArray, origin: String): Manifest {
        val lines = unseal(String(bytes, Charsets.UTF_8), origin).lines()
        if (lines.firstOrNull() != HEADER) throw GraphIntegrityException("$origin: does not start with \"$HEADER\"")
        val attributes = linkedMapOf<String, String>()
        val entries = linkedMapOf<String, Entry>()
        for (line in lines.drop(1).filter(String::isNotBlank)) {
            if (line.startsWith("@")) {
                val attribute = line.substring(1)
                attributes[attribute.substringBefore(' ')] = attribute.substringAfter(' ', "")
            } else {
                val (name, entry) = entry(line, origin)
                entries[name] = entry
            }
        }
        checkFormat(attributes, origin)
        val missing = REQUIRED_FILES.filterNot(entries::containsKey)
        if (missing.isNotEmpty()) {
            throw GraphIntegrityException("$origin: does not list ${missing.joinToString()}; it is not the inventory of a complete graph")
        }
        return Manifest(attributes, entries, sha256Hex(bytes))
    }

    /** The manifest text before its seal line, once the seal proves it whole. */
    private fun unseal(text: String, origin: String): String {
        val cut = "$origin: has no $SEAL line; the inventory was cut short or edited"
        if (!text.endsWith('\n')) throw GraphIntegrityException(cut)
        val start = text.lastIndexOf('\n', text.length - 2) + 1
        val seal = text.substring(start, text.length - 1).removePrefix("@$SEAL ")
        if (seal.length == text.length - 1 - start) throw GraphIntegrityException(cut)
        val body = text.substring(0, start)
        if (sha256Hex(body.toByteArray(Charsets.UTF_8)) != seal) {
            throw GraphIntegrityException("$origin: the $SEAL does not match the inventory; it was edited after the save")
        }
        return body
    }

    private fun entry(line: String, origin: String): Pair<String, Entry> {
        val parts = line.split(ENTRY_SEPARATOR, limit = ENTRY_FIELDS)
        val sha = parts[0].takeIf { it.length == SHA256_HEX_LENGTH && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
        val size = parts.getOrNull(1)?.toLongOrNull()
        val name = parts.getOrNull(2)
        if (sha == null || size == null || name == null) throw GraphIntegrityException("$origin: malformed line \"$line\"")
        return name to Entry(size, sha)
    }

    /** A manifest naming a later storage format was written by a newer producer: refused by name rather than misread. */
    private fun checkFormat(attributes: Map<String, String>, origin: String) {
        val format = attributes[STORAGE_FORMAT]?.toIntOrNull() ?: CURRENT_STORAGE_FORMAT
        if (format > CURRENT_STORAGE_FORMAT) {
            throw GraphIntegrityException(
                "$origin: storage format $format was written by ${attributes[PRODUCER] ?: "an unknown producer"}; " +
                    "this reader supports format $CURRENT_STORAGE_FORMAT")
        }
    }

    /**
     * Checks that every file the manifest lists is present with its listed size. Cheap enough for every load; returns the manifest, or
     * null for a legacy directory.
     */
    fun checkSizes(dir: Path): Manifest? {
        val manifest = read(dir) ?: return null
        for ((name, entry) in manifest.entries) {
            val path = dir.resolve(name)
            if (!Files.isRegularFile(path)) throw GraphIntegrityException("$path: listed in $FILE_NAME but missing")
            val size = Files.size(path)
            if (size != entry.size) {
                throw GraphIntegrityException("$path: $size bytes, but $FILE_NAME lists ${entry.size}; the graph was modified or cut short")
            }
        }
        return manifest
    }

    /** Hashes every listed file and returns one message per file that does not match; empty when the graph is intact. */
    fun verify(dir: Path): List<String> {
        val manifest = try {
            read(dir)
        } catch (damaged: GraphIntegrityException) {
            return listOf(damaged.message.orEmpty())
        } ?: return listOf("${dir.resolve(FILE_NAME)}: missing; nothing to verify the graph against")
        return manifest.entries.mapNotNull { (name, entry) ->
            val path = dir.resolve(name)
            when {
                !Files.isRegularFile(path) -> "$name: missing"
                else -> {
                    val actual = describe(path)
                    if (actual == entry) null else "$name: expected ${entry.size} bytes sha256 ${entry.sha256}, " +
                        "found ${actual.size} bytes sha256 ${actual.sha256}"
                }
            }
        }
    }

    /** Whether [name] is a file the manifest of [dir] seals; a sealed file must not be rewritten in place. */
    internal fun seals(dir: Path, name: String): Boolean = read(dir)?.entries?.containsKey(name) == true

    /** The identity of the graph in [dir] for files derived from it after the save: the manifest digest, or null for a legacy graph. */
    internal fun identity(dir: Path): String? = read(dir)?.digest

    internal fun render(attributes: Map<String, String>, entries: Map<String, Entry>): String {
        val body = buildString {
            append(HEADER).append('\n')
            for ((key, value) in attributes) append('@').append(key).append(' ').append(value).append('\n')
            for ((name, entry) in entries) {
                append(entry.sha256).append(ENTRY_SEPARATOR).append(entry.size).append(ENTRY_SEPARATOR).append(name).append('\n')
            }
        }
        return "$body@$SEAL ${sha256Hex(body.toByteArray(Charsets.UTF_8))}\n"
    }

    private fun describe(path: Path): Entry {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(READ_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                size += read
            }
        }
        return Entry(size, HexFormat.of().formatHex(digest.digest()))
    }

    private fun sha256Hex(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
