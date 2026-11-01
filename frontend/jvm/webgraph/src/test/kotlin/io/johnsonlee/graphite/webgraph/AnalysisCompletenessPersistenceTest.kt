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
import io.johnsonlee.graphite.graph.CallSiteGap
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
            "other.Util" to ClassEvidence("other.jar", AnalysisStatus.EXCLUDED_BY_SCOPE),
            "lib.Far" to ClassEvidence("lib-1.0.jar", AnalysisStatus.NOT_EXPANDED)
        ),
        methods = mapOf(
            abstract.identity to MethodEvidence(AnalysisStatus.ABSTRACT),
            oom.identity to MethodEvidence(AnalysisStatus.INCOMPLETE, "Java heap space"),
            method("lib.Impl", "broken").identity to MethodEvidence(AnalysisStatus.RESOLUTION_FAILED, "bad frame")
        ),
        duplicateClasses = linkedMapOf("lib.Reader" to listOf("lib-1.0.jar", "other.jar")),
        callSiteGaps = mapOf(1 to CallSiteGap.DISPATCH_UNRESOLVED, 3 to CallSiteGap.DISPATCH_SATURATED)
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
            assertEquals(AnalysisStatus.NOT_EXPANDED, graph.analysisStatus(method("lib.Far", "any")))
            assertEquals(CallSiteGap.DISPATCH_SATURATED, graph.analysisCompleteness()!!.callSiteGaps.getValue(3))
        }
        val reachable = completeness.copy(
            scope = AnalysisIdentity(includeLibraries = true, dependencyReachable = 1234, dependencyRoots = listOf("a.web", "a.jobs")),
            callSiteGaps = emptyMap()
        )
        GraphStore.save(graph(reachable), dir)
        loaded(dir) { graph -> assertEquals(reachable, graph.analysisCompleteness()) }
    }

    @Test
    fun `a graph without evidence writes no file and removes the one an earlier save left`() {
        val dir = directory("completeness-none")
        GraphStore.save(graph(), dir)
        assertTrue(Files.isRegularFile(dir.resolve(AnalysisCompletenessStore.FILE_NAME)))
        val earlier = Files.readAllBytes(dir.resolve(AnalysisCompletenessStore.FILE_NAME))
        GraphStore.save(graph(evidence = null), dir)
        assertFalse(Files.exists(dir.resolve(AnalysisCompletenessStore.FILE_NAME)), "a stale sidecar would describe another build")
        assertNull(ForwardProperties.read(dir, ForwardProperties.COMPLETENESS_KEY), "and the binding went with it")
        loaded(dir) { graph ->
            assertNull(graph.analysisCompleteness())
            assertNull(graph.analysisStatus(analysed), "no evidence is unknown, not analysed")
        }
        // The same nodes were saved, so the earlier file carries this directory's node layout;
        // without the binding of a save that wrote it, it is still not this save's evidence.
        Files.write(dir.resolve(AnalysisCompletenessStore.FILE_NAME), earlier)
        loaded(dir) { graph -> assertNull(graph.analysisCompleteness()) }
    }

    @Test
    fun `the evidence of another save of the same nodes reads as absent`() {
        val first = directory("completeness-same-nodes-first")
        val second = directory("completeness-same-nodes-second")
        GraphStore.save(graph(), first)
        val far = "lib.Far" to ClassEvidence("lib-1.0.jar", AnalysisStatus.ANALYZED)
        GraphStore.save(graph(completeness.copy(classes = completeness.classes + far)), second)
        assertEquals(
            ForwardProperties.read(first, ForwardProperties.NODE_LAYOUT_KEY),
            ForwardProperties.read(second, ForwardProperties.NODE_LAYOUT_KEY),
            "one node layout, two evidences"
        )
        val file = AnalysisCompletenessStore.FILE_NAME
        Files.copy(second.resolve(file), first.resolve(file), StandardCopyOption.REPLACE_EXISTING)
        assertNull(AnalysisCompletenessStore.load(first), "the node layout alone does not identify the evidence")
        loaded(first) { graph -> assertNull(graph.analysisCompleteness()) }
    }

    @Test
    fun `saving a loaded graph over its own directory keeps the evidence it had not read yet`() {
        val dir = directory("completeness-resave")
        GraphStore.save(graph(), dir)
        GraphStore.save(GraphStore.load(dir), dir)
        assertTrue(Files.isRegularFile(dir.resolve(AnalysisCompletenessStore.FILE_NAME)))
        loaded(dir) { graph -> assertEquals(completeness, graph.analysisCompleteness()) }
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

        // What an interrupted or foreign write leaves is not "no evidence".
        Files.write(file, bytes.copyOf(20))
        assertEquals(
            "${AnalysisCompletenessStore.FILE_NAME} is not a completeness sidecar",
            assertFailsWith<IllegalStateException> { AnalysisCompletenessStore.load(first) }.message
        )
        Files.write(file, ByteArray(100))
        assertEquals(
            "${AnalysisCompletenessStore.FILE_NAME} is not a completeness sidecar",
            assertFailsWith<IllegalStateException> { AnalysisCompletenessStore.load(first) }.message
        )
        Files.delete(file)
        assertNull(AnalysisCompletenessStore.load(first))

        GraphStore.save(graph(), first)
        ForwardProperties.write(first, mapOf(ForwardProperties.NODE_LAYOUT_KEY to null))
        assertNull(AnalysisCompletenessStore.load(first), "a directory that names no layout binds nothing")
    }

    @Test
    fun `a payload that was cut, padded or rewritten after binding is refused`() {
        val dir = directory("completeness-shape")
        val layout = ByteArray(32) { it.toByte() }
        ForwardProperties.write(dir, mapOf(ForwardProperties.NODE_LAYOUT_KEY to layout.joinToString("") { "%02x".format(it) }))
        AnalysisCompletenessStore.save(dir, completeness, layout)
        assertEquals(completeness, AnalysisCompletenessStore.load(dir))

        val file = dir.resolve(AnalysisCompletenessStore.FILE_NAME)
        val header = Files.readAllBytes(file).copyOf(36)
        fun rebound(payload: ByteArray): ByteArray =
            (header + payload).let { it + java.security.MessageDigest.getInstance("SHA-256").digest(it) }
        fun gzipped(block: (java.io.DataOutputStream) -> Unit): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            java.io.DataOutputStream(java.util.zip.GZIPOutputStream(out)).use(block)
            return out.toByteArray()
        }
        fun refused(payload: ByteArray): String {
            val bytes = rebound(payload)
            Files.write(file, bytes)
            val trailer = bytes.copyOfRange(bytes.size - 32, bytes.size).joinToString("") { "%02x".format(it) }
            ForwardProperties.write(dir, mapOf(ForwardProperties.COMPLETENESS_KEY to trailer))
            return assertFailsWith<IllegalStateException> { AnalysisCompletenessStore.load(dir) }.message!!
        }

        assertEquals("${AnalysisCompletenessStore.FILE_NAME} is not a gzip payload", refused(ByteArray(10)))
        assertEquals("${AnalysisCompletenessStore.FILE_NAME} is truncated", refused(gzipped { it.writeInt(0); it.writeInt(0) }))
        val whole = java.io.ByteArrayOutputStream().also { out ->
            java.io.DataOutputStream(out).use { AnalysisCompletenessStore.write(it, completeness) }
        }.toByteArray()
        assertEquals(
            "${AnalysisCompletenessStore.FILE_NAME} has bytes after its last entry",
            refused(gzipped { it.write(whole); it.write(0) })
        )
        assertEquals(
            "Expected a SHA-256 digest, got 3 bytes",
            assertFailsWith<IllegalArgumentException> { AnalysisCompletenessStore.save(dir, completeness, ByteArray(3)) }.message
        )

        // A status or role this reader does not know is refused by name, a code past the table too.
        fun rewritten(edit: (String) -> String): ByteArray =
            gzipped { it.write(edit(String(whole, Charsets.ISO_8859_1)).toByteArray(Charsets.ISO_8859_1)) }
        assertEquals(
            "${AnalysisCompletenessStore.FILE_NAME} names a status 'ANALYSED' this reader does not know",
            refused(rewritten { it.replaceFirst("ANALYZED", "ANALYSED") })
        )
        assertEquals(
            "${AnalysisCompletenessStore.FILE_NAME} names a role 'PLATFORN' this reader does not know",
            refused(rewritten { it.replaceFirst("PLATFORM", "PLATFORN") })
        )
        assertEquals(
            "${AnalysisCompletenessStore.FILE_NAME} names a gap 'DISPATCH_SATURATEX' this reader does not know",
            refused(rewritten { it.replaceFirst("DISPATCH_SATURATED", "DISPATCH_SATURATEX") })
        )
        val text = String(whole, Charsets.ISO_8859_1)
        val roleAt = text.indexOf("app.jar") + "app.jar".length
        assertEquals(
            "${AnalysisCompletenessStore.FILE_NAME} names code 9 among ${ArtifactRole.entries.size}",
            refused(gzipped { it.write(whole.copyOf().also { bytes -> bytes[roleAt] = 9 }) })
        )
        val countAt = text.indexOf("app.jar") - 2 - Int.SIZE_BYTES
        assertEquals(
            "${AnalysisCompletenessStore.FILE_NAME} holds a negative count",
            refused(gzipped { it.write(whole.copyOf().also { bytes -> bytes[countAt] = 0xFF.toByte() }) })
        )
    }
}
