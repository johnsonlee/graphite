package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.AnalysisCompleteness
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.ArtifactEvidence
import io.johnsonlee.graphite.graph.ArtifactRole
import io.johnsonlee.graphite.graph.ClassEvidence
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodEvidence
import io.johnsonlee.graphite.graph.analysisStatus
import io.johnsonlee.graphite.input.AnalysisIdentity
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalysisCompletenessPersistenceTest {

    private val directories = mutableListOf<Path>()

    @AfterTest
    fun cleanUp() {
        directories.forEach { it.toFile().deleteRecursively() }
    }

    private fun directory(name: String): Path = Files.createTempDirectory(name).also(directories::add)

    private fun method(className: String, name: String, vararg parameters: String, returns: String = "void") =
        MethodDescriptor(TypeDescriptor(className), name, parameters.map(::TypeDescriptor), TypeDescriptor(returns))

    private val analysed = method("app.Service", "value", returns = "java.lang.String")
    private val oom = method("lib.Impl", "explode")
    private val abstract = method("lib.Reader", "read", "java.lang.String", returns = "java.lang.String")
    private val excluded = method("other.Util", "helper")
    private val external = method("java.util.ResourceBundle", "getBundle", "java.lang.String", returns = "java.util.ResourceBundle")

    private val completeness = AnalysisCompleteness(
        scope = AnalysisIdentity(
            includePackages = listOf("app"), excludePackages = listOf("app.test"), includeLibraries = true,
            libraryFilters = listOf("*.jar"), androidPlatformSha256 = "ab".repeat(32),
            dependencyArtifacts = listOf("lib-*.jar"), dependencyPackages = listOf("lib.")
        ),
        artifacts = linkedMapOf(
            "app.jar" to ArtifactEvidence(ArtifactRole.APPLICATION),
            "lib-1.0.jar" to ArtifactEvidence(ArtifactRole.LIBRARY, multiRelease = true),
            "android.jar" to ArtifactEvidence(ArtifactRole.PLATFORM)
        ),
        classes = mapOf(
            "app.Service" to ClassEvidence("app.jar", AnalysisStatus.ANALYZED),
            "lib.Reader" to ClassEvidence("lib-1.0.jar", AnalysisStatus.ANALYZED),
            "lib.Impl" to ClassEvidence(null, AnalysisStatus.ANALYZED),
            "lib.Broken" to ClassEvidence("lib-1.0.jar", AnalysisStatus.RESOLUTION_FAILED),
            "other.Util" to ClassEvidence("other.jar", AnalysisStatus.EXCLUDED_BY_SCOPE)
        ),
        methods = mapOf(
            abstract.identity to MethodEvidence(AnalysisStatus.ABSTRACT),
            oom.identity to MethodEvidence(AnalysisStatus.INCOMPLETE, "Java heap space"),
            method("lib.Impl", "broken").identity to MethodEvidence(AnalysisStatus.RESOLUTION_FAILED, "bad frame")
        ),
        duplicateClasses = linkedMapOf("lib.Reader" to listOf("lib-1.0.jar", "other.jar"))
    )

    private fun graph(evidence: AnalysisCompleteness? = completeness): Graph = DefaultGraph.Builder().apply {
        addNode(IntConstant(NodeId(0), 42))
        addNode(CallSiteNode(NodeId(1), analysed, external, 7, null, emptyList(), ordinal = 1))
        addMethod(analysed)
        addMethod(oom)
        addMethod(abstract)
        evidence?.let { setAnalysisCompleteness(it) }
    }.build()

    private fun <T> loaded(dir: Path, action: (Graph) -> T): List<T> =
        listOf(GraphStore.load(dir), GraphStore.loadMapped(dir)).map { graph ->
            try {
                action(graph)
            } finally {
                (graph as? Closeable)?.close()
            }
        }

    @Test
    fun `the evidence survives a save and both loads, and the loaded graphs answer a callee's status`() {
        val dir = directory("completeness-roundtrip")
        GraphStore.save(graph(), dir)
        assertTrue(Files.isRegularFile(dir.resolve(AnalysisCompletenessStore.FILE_NAME)))

        loaded(dir) { graph ->
            assertEquals(completeness, graph.analysisCompleteness())
            assertEquals(completeness.artifacts.keys.toList(), graph.analysisCompleteness()!!.artifacts.keys.toList(), "in order")
            assertTrue(graph.hasMethod(analysed))
            assertFalse(graph.hasMethod(analysed.copy(returnType = TypeDescriptor("int"))), "the return type is part of the identity")
            assertFalse(graph.hasMethod(external))
            assertEquals(AnalysisStatus.ANALYZED, graph.analysisStatus(analysed))
            assertEquals(AnalysisStatus.INCOMPLETE, graph.analysisStatus(oom))
            assertEquals(AnalysisStatus.ABSTRACT, graph.analysisStatus(abstract))
            assertEquals(AnalysisStatus.EXCLUDED_BY_SCOPE, graph.analysisStatus(excluded))
            assertEquals(AnalysisStatus.EXTERNAL, graph.analysisStatus(external))
            assertEquals(AnalysisStatus.UNKNOWN_MEMBER, graph.analysisStatus(method("app.Service", "missing")))
            assertEquals(AnalysisStatus.RESOLUTION_FAILED, graph.analysisStatus(method("lib.Broken", "any")))
        }
    }

    @Test
    fun `a graph without evidence writes no file and removes the one an earlier save left`() {
        val dir = directory("completeness-none")
        GraphStore.save(graph(), dir)
        assertTrue(Files.isRegularFile(dir.resolve(AnalysisCompletenessStore.FILE_NAME)))
        GraphStore.save(graph(evidence = null), dir)
        assertFalse(Files.exists(dir.resolve(AnalysisCompletenessStore.FILE_NAME)), "a stale sidecar would describe another build")
        loaded(dir) { graph ->
            assertNull(graph.analysisCompleteness())
            assertNull(graph.analysisStatus(analysed), "no evidence is unknown, not analysed")
        }
    }

    @Test
    fun `a file another save wrote reads as absent, a bound file that no longer hashes throws`() {
        val first = directory("completeness-first")
        val second = directory("completeness-second")
        GraphStore.save(graph(), first)
        GraphStore.save(DefaultGraph.Builder().apply {
            addNode(IntConstant(NodeId(0), 43))
            setAnalysisCompleteness(completeness.copy(classes = emptyMap()))
        }.build(), second)
        val file = first.resolve(AnalysisCompletenessStore.FILE_NAME)
        val foreign = second.resolve(AnalysisCompletenessStore.FILE_NAME)
        assertFalse(Files.readAllBytes(file).contentEquals(Files.readAllBytes(foreign)))

        Files.copy(foreign, file, StandardCopyOption.REPLACE_EXISTING)
        assertNull(AnalysisCompletenessStore.load(first), "bound to the other graph's node layout")
        loaded(first) { assertNull(it.analysisCompleteness()) }

        GraphStore.save(graph(), first)
        val bytes = Files.readAllBytes(file)
        bytes[bytes.size - 40] = (bytes[bytes.size - 40].toInt() xor 1).toByte()
        Files.write(file, bytes)
        assertEquals(
            "${AnalysisCompletenessStore.FILE_NAME} does not match its digest",
            assertFailsWith<IllegalStateException> { AnalysisCompletenessStore.load(first) }.message
        )
        loaded(first) { graph -> assertFailsWith<IllegalStateException> { graph.analysisCompleteness() } }

        Files.write(file, bytes.copyOf(20))
        assertNull(AnalysisCompletenessStore.load(first), "too short to be this format")
        Files.write(file, ByteArray(100))
        assertNull(AnalysisCompletenessStore.load(first), "not this format")
        Files.delete(file)
        assertNull(AnalysisCompletenessStore.load(first))

        GraphStore.save(graph(), first)
        ForwardProperties.write(first, mapOf(ForwardProperties.NODE_LAYOUT_KEY to null))
        assertNull(AnalysisCompletenessStore.load(first), "a directory that names no layout binds nothing")
    }

    @Test
    fun `a payload that was cut or padded after binding is refused`() {
        val dir = directory("completeness-shape")
        val layout = ByteArray(32) { it.toByte() }
        ForwardProperties.write(dir, mapOf(ForwardProperties.NODE_LAYOUT_KEY to layout.joinToString("") { "%02x".format(it) }))
        AnalysisCompletenessStore.save(dir, completeness, layout)
        assertEquals(completeness, AnalysisCompletenessStore.load(dir))

        val file = dir.resolve(AnalysisCompletenessStore.FILE_NAME)
        val bytes = Files.readAllBytes(file)
        fun rebound(payload: ByteArray): ByteArray =
            payload + java.security.MessageDigest.getInstance("SHA-256").digest(payload)
        Files.write(file, rebound(bytes.copyOf(bytes.size - 32 - 10)))
        assertEquals(
            "${AnalysisCompletenessStore.FILE_NAME} is truncated",
            assertFailsWith<IllegalStateException> { AnalysisCompletenessStore.load(dir) }.message
        )
        Files.write(file, rebound(bytes.copyOf(bytes.size - 32) + byteArrayOf(0)))
        assertEquals(
            "${AnalysisCompletenessStore.FILE_NAME} has bytes after its last entry",
            assertFailsWith<IllegalStateException> { AnalysisCompletenessStore.load(dir) }.message
        )
        assertEquals(
            "Expected a SHA-256 digest, got 3 bytes",
            assertFailsWith<IllegalArgumentException> { AnalysisCompletenessStore.save(dir, completeness, ByteArray(3)) }.message
        )
    }

    @Test
    fun `a status or role this reader does not know is refused by name, and a code past the table too`() {
        val dir = directory("completeness-names")
        val layout = ByteArray(32)
        ForwardProperties.write(dir, mapOf(ForwardProperties.NODE_LAYOUT_KEY to "00".repeat(32)))
        AnalysisCompletenessStore.save(dir, completeness, layout)
        val file = dir.resolve(AnalysisCompletenessStore.FILE_NAME)
        val original = Files.readAllBytes(file)
        fun rebound(payload: ByteArray): ByteArray =
            payload + java.security.MessageDigest.getInstance("SHA-256").digest(payload)
        fun replaced(from: String, to: String): ByteArray {
            val text = String(original.copyOf(original.size - 32), Charsets.ISO_8859_1)
            val at = text.indexOf(from)
            assertTrue(at > 0, from)
            return rebound(text.replaceRange(at, at + from.length, to).toByteArray(Charsets.ISO_8859_1))
        }
        Files.write(file, replaced("ANALYZED", "ANALYSED"))
        assertEquals(
            "${AnalysisCompletenessStore.FILE_NAME} names a status 'ANALYSED' this reader does not know",
            assertFailsWith<IllegalStateException> { AnalysisCompletenessStore.load(dir) }.message
        )
        Files.write(file, replaced("PLATFORM", "PLATFORN"))
        assertEquals(
            "${AnalysisCompletenessStore.FILE_NAME} names a role 'PLATFORN' this reader does not know",
            assertFailsWith<IllegalStateException> { AnalysisCompletenessStore.load(dir) }.message
        )
        // The first artifact's role byte follows its name; a code past the role table is refused.
        val payload = original.copyOf(original.size - 32)
        val text = String(payload, Charsets.ISO_8859_1)
        val roleAt = text.indexOf("app.jar") + "app.jar".length
        payload[roleAt] = 9
        Files.write(file, rebound(payload))
        assertEquals(
            "${AnalysisCompletenessStore.FILE_NAME} names code 9 among ${ArtifactRole.entries.size}",
            assertFailsWith<IllegalStateException> { AnalysisCompletenessStore.load(dir) }.message
        )
        val countAt = text.indexOf("app.jar") - 2 - Int.SIZE_BYTES
        payload[roleAt] = ArtifactRole.APPLICATION.ordinal.toByte()
        payload[countAt] = 0xFF.toByte()
        Files.write(file, rebound(payload))
        assertEquals(
            "${AnalysisCompletenessStore.FILE_NAME} holds a negative count",
            assertFailsWith<IllegalStateException> { AnalysisCompletenessStore.load(dir) }.message
        )
    }
}
