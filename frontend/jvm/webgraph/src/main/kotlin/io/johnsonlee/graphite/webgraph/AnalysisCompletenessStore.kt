package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.AnalysisCompleteness
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.ArtifactEvidence
import io.johnsonlee.graphite.graph.ArtifactRole
import io.johnsonlee.graphite.graph.CallSiteGap
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
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipException

/**
 * The `graph.completeness` sidecar: the build's [AnalysisCompleteness], read on the first call
 * for it and never before, since no query needs it. A graph saved by a frontend that recorded
 * none has no file, and the graph answers `null` rather than a status it cannot know.
 *
 * Layout, big-endian: `int32 header = MAGIC | VERSION`, the 32-byte SHA-256 of `graph.nodedata`
 * that `forward.properties` binds ([ForwardProperties.NODE_LAYOUT_KEY]), then the payload
 * ([write]: the scope, the status and role names the codes index, the artifacts, classes,
 * methods and duplicate definitions, each as a count and its entries) gzip-compressed, then the
 * SHA-256 of every byte before it. The payload is mostly class names, which compress five to
 * eight times; uncompressed it added 2.5% to a persisted graph for a file no query reads.
 * The evidence names classes and methods by name, not by node id, so it needs no
 * node layout to read. The save records the file's own trailer digest in `forward.properties`
 * ([ForwardProperties.COMPLETENESS_KEY]): that is what ties the file to this save of this graph.
 * The node layout alone cannot, since two saves of the same nodes under different scopes write
 * the same layout and different evidence, and a sidecar copied from the one would read as the
 * other's. A file whose trailer is not the one the directory recorded is another save's and
 * reads as absent; a file that is bound but no longer hashes, or does not parse, and a file too
 * short or of another format to carry a binding at all, is corruption and throws rather than
 * answers.
 */
internal object AnalysisCompletenessStore {
    const val FILE_NAME = "graph.completeness"
    internal const val MAGIC = 0x47435000 // "GCP"
    internal const val VERSION = 1
    private const val DIGEST_BYTES = 32
    private const val HEADER_BYTES = Int.SIZE_BYTES + DIGEST_BYTES
    private const val NO_BUDGET = -1
    private const val MIN_BYTES = HEADER_BYTES + DIGEST_BYTES

    /**
     * Write [completeness] beside the graph in [dir], carrying the node layout digest the save
     * computed, and record the file's trailer digest in `forward.properties` as the binding the
     * load checks. Without evidence, the file and the binding an earlier save left are removed.
     */
    fun save(dir: Path, completeness: AnalysisCompleteness?, layoutDigest: ByteArray) {
        require(layoutDigest.size == DIGEST_BYTES) { "Expected a SHA-256 digest, got ${layoutDigest.size} bytes" }
        if (completeness == null) {
            Files.deleteIfExists(dir.resolve(FILE_NAME))
            ForwardProperties.write(dir, mapOf(ForwardProperties.COMPLETENESS_KEY to null))
            return
        }
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { out ->
            out.writeInt(MAGIC or VERSION)
            out.write(layoutDigest)
            out.flush()
            DataOutputStream(GZIPOutputStream(body)).use { payload -> write(payload, completeness) }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
        Files.write(dir.resolve(FILE_NAME), body.toByteArray() + digest)
        ForwardProperties.write(dir, mapOf(ForwardProperties.COMPLETENESS_KEY to HexFormat.of().formatHex(digest)))
    }

    /**
     * The evidence `forward.properties` binds in [dir], or null when there is no file, the
     * directory records no binding or no node layout, or the file is another save's: its trailer
     * is not the digest the directory recorded, or it carries another node layout.
     */
    fun load(dir: Path): AnalysisCompleteness? {
        val layout = ForwardProperties.read(dir, ForwardProperties.NODE_LAYOUT_KEY)
        val binding = ForwardProperties.read(dir, ForwardProperties.COMPLETENESS_KEY)
        val bytes = if (layout != null && binding != null) fileBytes(dir.resolve(FILE_NAME)) else null
        return bytes?.takeIf { boundTo(it, layout!!, binding!!) }?.let(::readBound)
    }

    /** Whether [bytes] is the sidecar the directory recorded: its trailer is [binding] and it carries [layout]. */
    private fun boundTo(bytes: ByteArray, layout: String, binding: String): Boolean {
        // A file too short to carry a binding, or of another format, is not another save's: it is
        // what an interrupted or foreign write left, and a proof must not read it as "no evidence".
        check(bytes.size >= MIN_BYTES && DataInputStream(ByteArrayInputStream(bytes)).readInt() == MAGIC or VERSION) {
            "$FILE_NAME is not a completeness sidecar"
        }
        val hex = HexFormat.of()
        return hex.formatHex(bytes, bytes.size - DIGEST_BYTES, bytes.size).equals(binding, ignoreCase = true) &&
            hex.formatHex(bytes, Int.SIZE_BYTES, HEADER_BYTES).equals(layout, ignoreCase = true)
    }

    private fun readBound(bytes: ByteArray): AnalysisCompleteness {
        val payload = bytes.size - DIGEST_BYTES
        val expected = bytes.copyOfRange(payload, bytes.size)
        val actual = MessageDigest.getInstance("SHA-256").also { it.update(bytes, 0, payload) }.digest()
        check(MessageDigest.isEqual(expected, actual)) { "$FILE_NAME does not match its digest" }
        return try {
            DataInputStream(GZIPInputStream(ByteArrayInputStream(bytes, HEADER_BYTES, payload - HEADER_BYTES))).use(::read)
        } catch (e: EOFException) {
            throw IllegalStateException("$FILE_NAME is truncated", e)
        } catch (e: ZipException) {
            throw IllegalStateException("$FILE_NAME is not a gzip payload", e)
        }
    }

    private fun fileBytes(path: Path): ByteArray? = if (Files.isRegularFile(path)) Files.readAllBytes(path) else null

    internal fun write(out: DataOutputStream, completeness: AnalysisCompleteness) {
        val scope = completeness.scope
        out.writeStrings(scope.includePackages)
        out.writeStrings(scope.excludePackages)
        out.writeBoolean(scope.includeLibraries)
        out.writeStrings(scope.libraryFilters)
        out.writeOptional(scope.androidPlatformSha256)
        out.writeStrings(scope.dependencyArtifacts)
        out.writeStrings(scope.dependencyPackages)
        out.writeInt(scope.dependencyReachable ?: NO_BUDGET)
        out.writeStrings(scope.dependencyRoots)
        // The codes index these names, so a value added or reordered later still reads as itself.
        out.writeStrings(AnalysisStatus.entries.map { it.name })
        out.writeStrings(ArtifactRole.entries.map { it.name })
        out.writeStrings(CallSiteGap.entries.map { it.name })
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
        out.writeInt(completeness.callSiteGaps.size)
        for ((id, gap) in completeness.callSiteGaps) {
            out.writeInt(id)
            out.writeByte(gap.ordinal)
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
            dependencyPackages = input.readStrings(),
            dependencyReachable = input.readInt().takeIf { it != NO_BUDGET },
            dependencyRoots = input.readStrings()
        )
        val statuses = input.readStrings().map { named(it, AnalysisStatus.entries, "status") }
        val roles = input.readStrings().map { named(it, ArtifactRole.entries, "role") }
        val gaps = input.readStrings().map { named(it, CallSiteGap.entries, "gap") }
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
        val callSiteGaps = HashMap<Int, CallSiteGap>()
        repeat(input.readCount()) {
            callSiteGaps[input.readInt()] = gaps[input.code(gaps)]
        }
        check(input.read() < 0) { "$FILE_NAME has bytes after its last entry" }
        return AnalysisCompleteness(scope, artifacts, classes, methods, duplicates, callSiteGaps)
    }

    private fun <E : Enum<E>> named(name: String, entries: List<E>, kind: String): E =
        entries.firstOrNull { it.name == name } ?: error("$FILE_NAME names a $kind '$name' this reader does not know")

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
