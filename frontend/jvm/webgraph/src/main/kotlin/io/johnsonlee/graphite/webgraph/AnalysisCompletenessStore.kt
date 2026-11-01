package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.AnalysisCompleteness
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.ArtifactEvidence
import io.johnsonlee.graphite.graph.ArtifactRole
import io.johnsonlee.graphite.graph.ClassEvidence
import io.johnsonlee.graphite.graph.MethodEvidence
import io.johnsonlee.graphite.input.AnalysisIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The `graph.completeness` sidecar: the build's [AnalysisCompleteness], read on the first call
 * for it and never before, since no query needs it. A graph saved by a frontend that recorded
 * none has no file, and the graph answers `null` rather than a status it cannot know.
 *
 * Layout, big-endian: `int32 header = MAGIC | VERSION`, the 32-byte SHA-256 of `graph.nodedata`
 * that `forward.properties` binds ([ForwardProperties.NODE_LAYOUT_KEY]), then the payload
 * ([write]: the scope, the status and role names the codes index, the artifacts, classes,
 * methods and duplicate definitions, each as a count and its entries), then the SHA-256 of every
 * byte before it. The evidence names classes and methods by name, not by node id, so it needs no
 * node layout to read; the binding is what ties the file to this save of this graph rather than
 * to a file an earlier writer left beside a rewritten graph. A file bound to another save reads
 * as absent; a file that is bound but no longer hashes, or does not parse, is corruption after
 * binding and throws rather than answers.
 */
internal object AnalysisCompletenessStore {
    const val FILE_NAME = "graph.completeness"
    internal const val MAGIC = 0x47435000 // "GCP"
    internal const val VERSION = 1
    private const val DIGEST_BYTES = 32
    private const val HEADER_BYTES = Int.SIZE_BYTES + DIGEST_BYTES
    private const val MIN_BYTES = HEADER_BYTES + DIGEST_BYTES

    /** Write [completeness] beside the graph in [dir], bound to the node layout digest the save computed. */
    fun save(dir: Path, completeness: AnalysisCompleteness, layoutDigest: ByteArray) {
        require(layoutDigest.size == DIGEST_BYTES) { "Expected a SHA-256 digest, got ${layoutDigest.size} bytes" }
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { out ->
            out.writeInt(MAGIC or VERSION)
            out.write(layoutDigest)
            write(out, completeness)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
        Files.write(dir.resolve(FILE_NAME), body.toByteArray() + digest)
    }

    /**
     * The evidence bound to the node layout `forward.properties` names in [dir], or null when
     * there is no file, the directory names no layout, or the file is another save's.
     */
    fun load(dir: Path): AnalysisCompleteness? {
        val layout = ForwardProperties.read(dir, ForwardProperties.NODE_LAYOUT_KEY)
        val bytes = layout?.let { fileBytes(dir.resolve(FILE_NAME)) }?.takeIf { bound(it, layout) } ?: return null
        val payload = bytes.size - DIGEST_BYTES
        val expected = bytes.copyOfRange(payload, bytes.size)
        val actual = MessageDigest.getInstance("SHA-256").also { it.update(bytes, 0, payload) }.digest()
        check(MessageDigest.isEqual(expected, actual)) { "$FILE_NAME does not match its digest" }
        return try {
            DataInputStream(ByteArrayInputStream(bytes, HEADER_BYTES, payload - HEADER_BYTES)).use(::read)
        } catch (e: EOFException) {
            throw IllegalStateException("$FILE_NAME is truncated", e)
        }
    }

    private fun fileBytes(path: Path): ByteArray? = if (Files.isRegularFile(path)) Files.readAllBytes(path) else null

    /** Whether [bytes] is this format and names the node layout [layout] (a hex SHA-256) does. */
    private fun bound(bytes: ByteArray, layout: String): Boolean =
        bytes.size >= MIN_BYTES &&
            DataInputStream(ByteArrayInputStream(bytes)).readInt() == MAGIC or VERSION &&
            HexFormat.of().formatHex(bytes, Int.SIZE_BYTES, HEADER_BYTES).equals(layout, ignoreCase = true)

    private fun write(out: DataOutputStream, completeness: AnalysisCompleteness) {
        val scope = completeness.scope
        out.writeStrings(scope.includePackages)
        out.writeStrings(scope.excludePackages)
        out.writeBoolean(scope.includeLibraries)
        out.writeStrings(scope.libraryFilters)
        out.writeOptional(scope.androidPlatformSha256)
        out.writeStrings(scope.dependencyArtifacts)
        out.writeStrings(scope.dependencyPackages)
        // The codes index these names, so a value added or reordered later still reads as itself.
        val statuses = AnalysisStatus.entries.map { it.name }
        val roles = ArtifactRole.entries.map { it.name }
        out.writeStrings(statuses)
        out.writeStrings(roles)
        out.writeInt(completeness.artifacts.size)
        for ((name, artifact) in completeness.artifacts) {
            out.writeUTF(name)
            out.writeByte(artifact.role.ordinal)
            out.writeBoolean(artifact.multiRelease)
        }
        out.writeInt(completeness.classes.size)
        for ((name, evidence) in completeness.classes) {
            out.writeUTF(name)
            out.writeOptional(evidence.origin)
            out.writeByte(evidence.status.ordinal)
        }
        out.writeInt(completeness.methods.size)
        for ((identity, evidence) in completeness.methods) {
            out.writeUTF(identity)
            out.writeByte(evidence.status.ordinal)
            out.writeOptional(evidence.message)
        }
        out.writeInt(completeness.duplicateClasses.size)
        for ((name, sources) in completeness.duplicateClasses) {
            out.writeUTF(name)
            out.writeStrings(sources)
        }
    }

    private fun read(input: DataInputStream): AnalysisCompleteness {
        val scope = AnalysisIdentity(
            includePackages = input.readStrings(),
            excludePackages = input.readStrings(),
            includeLibraries = input.readBoolean(),
            libraryFilters = input.readStrings(),
            androidPlatformSha256 = input.readOptional(),
            dependencyArtifacts = input.readStrings(),
            dependencyPackages = input.readStrings()
        )
        val statuses = input.readStrings().map(::statusNamed)
        val roles = input.readStrings().map(::roleNamed)
        fun DataInputStream.code(names: List<*>): Int = readUnsignedByte().also {
            check(it < names.size) { "$FILE_NAME names code $it among ${names.size}" }
        }
        val artifacts = LinkedHashMap<String, ArtifactEvidence>()
        repeat(input.readCount()) {
            artifacts[input.readUTF()] = ArtifactEvidence(roles[input.code(roles)], input.readBoolean())
        }
        val classes = HashMap<String, ClassEvidence>()
        repeat(input.readCount()) {
            classes[input.readUTF()] = ClassEvidence(input.readOptional(), statuses[input.code(statuses)])
        }
        val methods = HashMap<String, MethodEvidence>()
        repeat(input.readCount()) {
            methods[input.readUTF()] = MethodEvidence(statuses[input.code(statuses)], input.readOptional())
        }
        val duplicates = LinkedHashMap<String, List<String>>()
        repeat(input.readCount()) {
            duplicates[input.readUTF()] = input.readStrings()
        }
        check(input.read() < 0) { "$FILE_NAME has bytes after its last entry" }
        return AnalysisCompleteness(scope, artifacts, classes, methods, duplicates)
    }

    private fun statusNamed(name: String): AnalysisStatus = AnalysisStatus.entries.firstOrNull { it.name == name }
        ?: error("$FILE_NAME names a status '$name' this reader does not know")

    private fun roleNamed(name: String): ArtifactRole = ArtifactRole.entries.firstOrNull { it.name == name }
        ?: error("$FILE_NAME names a role '$name' this reader does not know")

    private fun DataOutputStream.writeStrings(values: List<String>) {
        writeInt(values.size)
        values.forEach(::writeUTF)
    }

    private fun DataOutputStream.writeOptional(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataInputStream.readCount(): Int = readInt().also { check(it >= 0) { "$FILE_NAME holds a negative count" } }

    private fun DataInputStream.readStrings(): List<String> = List(readCount()) { readUTF() }

    private fun DataInputStream.readOptional(): String? = if (readBoolean()) readUTF() else null
}
