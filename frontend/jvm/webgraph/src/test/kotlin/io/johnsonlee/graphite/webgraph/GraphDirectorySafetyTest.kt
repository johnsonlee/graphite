package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reproductions for saving into a directory that already holds a graph. */
class GraphDirectorySafetyTest : GraphStoreTestFixture() {

    private fun chain(values: List<Int>): Graph {
        val builder = DefaultGraph.Builder()
        val method = MethodDescriptor(TypeDescriptor("com.example.Chain"), "run", emptyList(), TypeDescriptor("void"))
        builder.addMethod(method)
        val local = LocalVariable(NodeId.next(), "x", TypeDescriptor("int"), method)
        builder.addNode(local)
        for (value in values) {
            val constant = IntConstant(NodeId.next(), value)
            builder.addNode(constant)
            builder.addEdge(DataFlowEdge(constant.id, local.id, DataFlowKind.ASSIGN))
        }
        return builder.build()
    }

    private fun incomingSignature(graph: Graph): Map<Int, Int> =
        graph.nodes(Node::class.java).associate { it.id.value to graph.incoming(it.id).count() }

    @Test
    fun `saving another graph into a directory does not reuse the earlier graph's backward adjacency`() {
        val dir = Files.createTempDirectory("graph-dir-safety")
        try {
            GraphStore.save(chain(listOf(1, 2, 3)), dir)
            // A query on incoming edges persists backward.* beside the first graph.
            val first = GraphStore.load(dir, GraphStore.LoadMode.MAPPED)
            incomingSignature(first)
            (first as? AutoCloseable)?.close()

            val second = chain(listOf(7))
            GraphStore.save(second, dir)
            val reloaded = GraphStore.load(dir, GraphStore.LoadMode.MAPPED)
            assertEquals(incomingSignature(second), incomingSignature(reloaded))
            (reloaded as? AutoCloseable)?.close()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `saving a mapped graph back into its own directory keeps the graph intact`() {
        val dir = Files.createTempDirectory("graph-dir-self")
        try {
            val original = buildTestGraph()
            GraphStore.save(original, dir)
            val mapped = GraphStore.load(dir, GraphStore.LoadMode.MAPPED)
            GraphStore.save(mapped, dir)
            val reloaded = GraphStore.load(dir, GraphStore.LoadMode.MAPPED)
            assertEquals(
                original.nodes(Node::class.java).map { it.toString() }.toSortedSet(),
                reloaded.nodes(Node::class.java).map { it.toString() }.toSortedSet())
            assertEquals(
                original.nodes(CallSiteNode::class.java).map { it.callee.signature }.toList(),
                reloaded.nodes(CallSiteNode::class.java).map { it.callee.signature }.toList())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    internal fun dirFiles(dir: Path): Set<String> = Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList().toSet() }

    private inline fun inTemp(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("graph-dir-safety-root")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a save seals every file with the producer and format`() = inTemp { root ->
        val dir = root.resolve("g")
        GraphStore.save(buildTestGraph(), dir)
        val manifest = assertNotNull(GraphManifest.read(dir))
        assertEquals(dirFiles(dir) - GraphManifest.FILE_NAME, manifest.entries.keys)
        assertTrue(manifest.producer!!.startsWith("graphite-jvm "), manifest.producer)
        assertFalse(manifest.producer!!.endsWith(" unknown"), "the build stamps its version: ${manifest.producer}")
        assertEquals("1", manifest.attributes[GraphManifest.STORAGE_FORMAT])
        assertEquals(emptyList(), GraphStore.verify(dir))
        assertEquals(setOf("g"), dirFiles(root), "no staging or replaced directory is left behind")
    }

    @Test
    fun `a sealed graph refuses a truncated, missing or replaced file instead of reading it`() = inTemp { root ->
        val dir = root.resolve("g")
        GraphStore.save(buildTestGraph(), dir)
        val labels = dir.resolve("graph.labels")
        val original = Files.readAllBytes(labels)
        Files.write(labels, original.copyOf(original.size - 1))
        val truncated = assertFailsWith<GraphIntegrityException> { GraphStore.load(dir) }.message!!
        assertContains(truncated, "graph.labels: ${original.size - 1} bytes, but graph.manifest lists ${original.size}")
        assertFailsWith<GraphIntegrityException> { GraphStore.loadMapped(dir) }

        // Same size, other bytes: only verification reads them.
        Files.write(labels, original.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
        val failures = GraphStore.verify(dir)
        assertEquals(1, failures.size, failures.toString())
        assertTrue(failures.single().startsWith("graph.labels: expected ${original.size} bytes sha256 "), failures.single())

        Files.delete(labels)
        assertContains(assertFailsWith<GraphIntegrityException> { GraphStore.load(dir) }.message!!, "listed in graph.manifest but missing")
        assertEquals(listOf("graph.labels: missing"), GraphStore.verify(dir))
    }

    @Test
    fun `a graph without a manifest loads as before and cannot be verified`() = inTemp { root ->
        val dir = root.resolve("g")
        GraphStore.save(buildTestGraph(), dir)
        unsealGraph(dir)
        assertNull(GraphManifest.checkSizes(dir))
        assertEquals(8, GraphStore.load(dir).nodes(Node::class.java).count())
        assertContains(GraphStore.verify(dir).single(), "graph.manifest: missing")
    }

    @Test
    fun `a manifest of a newer storage format or a malformed one is refused`() = inTemp { root ->
        val dir = root.resolve("g")
        GraphStore.save(buildTestGraph(), dir)
        val path = dir.resolve(GraphManifest.FILE_NAME)
        val entries = requiredEntries()
        val body = Files.readString(path).substringBeforeLast("@${GraphManifest.SEAL} ")
        val newer =
            body.replace("@storage-format 1", "@storage-format 2").replace(Regex("@producer [^\\n]*"), "@producer graphite-jvm 9.0.0")
        Files.writeString(path, sealed(newer))
        assertEquals(
            "$path: storage format 2 was written by graphite-jvm 9.0.0; this reader supports format 1",
            assertFailsWith<GraphIntegrityException> { GraphStore.load(dir) }.message)
        Files.writeString(path, sealed("${GraphManifest.HEADER}\n@storage-format 2\n"))
        assertContains(assertFailsWith<GraphIntegrityException> { GraphStore.load(dir) }.message!!, "written by an unknown producer")
        Files.writeString(path, sealed("not a manifest\n"))
        assertContains(assertFailsWith<GraphIntegrityException> { GraphStore.load(dir) }.message!!, "does not start with")
        for (line in listOf("abc  1  graph.x", "${"a".repeat(64)}  x  graph.x", "${"a".repeat(64)}  1", "${"A".repeat(64)}  1  graph.x")) {
            Files.writeString(path, sealed("${GraphManifest.HEADER}\n\n$line\n"))
            assertContains(assertFailsWith<GraphIntegrityException> { GraphStore.load(dir) }.message!!, "malformed line")
        }
        // An attribute without a value and no format attribute: the current format.
        Files.writeString(path, sealed("${GraphManifest.HEADER}\n@note\n$entries"))
        assertEquals("", GraphManifest.parse(Files.readAllBytes(path), path.toString()).attributes["note"])
    }

    @Test
    fun `a manifest cut short or edited certifies nothing`() = inTemp { root ->
        val dir = root.resolve("g")
        GraphStore.save(buildTestGraph(), dir)
        val path = dir.resolve(GraphManifest.FILE_NAME)
        val whole = Files.readString(path)
        // A damaged data file of the same size, then the manifest cut back line by line down to its header: before the seal, the
        // header alone verified zero files successfully.
        val nodedata = dir.resolve("graph.nodedata")
        val bytes = Files.readAllBytes(nodedata)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        Files.write(nodedata, bytes)
        val lines = whole.lines().filter(String::isNotEmpty)
        for (keep in 1 until lines.size) {
            Files.writeString(path, lines.take(keep).joinToString("") { "$it\n" })
            assertContains(assertFailsWith<GraphIntegrityException> { GraphStore.load(dir) }.message!!, "has no seal line")
            assertContains(GraphStore.verify(dir).single(), "has no seal line")
        }
        Files.writeString(path, whole.dropLast(1))
        assertContains(GraphStore.verify(dir).single(), "has no seal line")
        // Every line kept, one entry rewritten to the damaged bytes' digest.
        val entry = lines.single { it.endsWith("  graph.nodedata") }
        val forged = "${GraphManifestTestDigest.sha256(bytes)}${entry.substring(64)}"
        Files.writeString(path, whole.replace(entry, forged))
        assertContains(GraphStore.verify(dir).single(), "does not match the inventory")
        Files.writeString(path, whole)
        assertContains(GraphStore.verify(dir).single(), "graph.nodedata: expected")
    }

    @Test
    fun `a sealed manifest must list every file every writer produces`() = inTemp { root ->
        val path = root.resolve(GraphManifest.FILE_NAME)
        val partial = requiredEntries().lines().filterNot { it.endsWith("  graph.nodedata") || it.endsWith("  forward.graph") }
        Files.writeString(path, sealed("${GraphManifest.HEADER}\n${partial.joinToString("\n")}"))
        assertEquals(
            "$path: does not list forward.graph, graph.nodedata; it is not the inventory of a complete graph",
            assertFailsWith<GraphIntegrityException> { GraphManifest.read(root) }.message)
        Files.writeString(path, sealed("${GraphManifest.HEADER}\n${requiredEntries()}"))
        assertEquals(GraphManifest.REQUIRED_FILES.toSet(), GraphManifest.read(root)!!.entries.keys)
    }

    private fun sealed(body: String): String = "$body@${GraphManifest.SEAL} ${GraphManifestTestDigest.sha256(body.toByteArray())}\n"

    private fun requiredEntries(): String {
        val empty = GraphManifestTestDigest.sha256(ByteArray(0))
        return GraphManifest.REQUIRED_FILES.joinToString("") { "$empty  0  $it\n" }
    }

    @Test
    fun `a directory holding anything but a graph is never replaced`() = inTemp { root ->
        val dir = root.resolve("mine")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("notes.txt"), "keep me")
        val message = assertFailsWith<IllegalArgumentException> { GraphStore.save(buildTestGraph(), dir) }.message!!
        assertContains(message, "holds files that are not part of a graph (notes.txt)")
        assertEquals("keep me", Files.readString(dir.resolve("notes.txt")))
        assertEquals(setOf("mine"), dirFiles(root))

        val file = root.resolve("file")
        Files.writeString(file, "x")
        assertContains(assertFailsWith<IllegalArgumentException> { GraphStore.save(buildTestGraph(), file) }.message!!, "Not a directory")
        assertContains(
            assertFailsWith<IllegalArgumentException> { GraphStore.save(buildTestGraph(), root.root) }.message!!, "file system root")

        // An unpacked container directory and leftovers of a graph are graph files.
        assertTrue(GraphDirectoryPublication.isGraphFile("META-INF"))
        assertTrue(GraphDirectoryPublication.isGraphFile("backward.graph"))
        assertFalse(GraphDirectoryPublication.isGraphFile("README"))
    }

    @Test
    fun `a failed save leaves the earlier graph in place`() = inTemp { root ->
        val dir = root.resolve("g")
        GraphStore.save(buildTestGraph(), dir)
        val before = Files.readAllBytes(dir.resolve(GraphManifest.FILE_NAME))
        val broken = object : Graph by buildTestGraph() {
            override fun <T : Node> nodes(type: Class<T>): Sequence<T> =
                throw java.io.UncheckedIOException(java.io.IOException("source gone"))
        }
        assertFailsWith<java.io.UncheckedIOException> { GraphStore.save(broken, dir) }
        assertTrue(before.contentEquals(Files.readAllBytes(dir.resolve(GraphManifest.FILE_NAME))))
        assertEquals(emptyList(), GraphStore.verify(dir))
        assertEquals(setOf("g"), dirFiles(root), "the staging directory is removed")
    }

    @Test
    fun `a swap that cannot place the new graph restores the earlier one`() = inTemp { root ->
        val target = root.resolve("g")
        Files.createDirectories(target)
        Files.writeString(target.resolve("graph.marker"), "earlier")
        val aside = root.resolve("aside")
        assertFailsWith<java.io.IOException> { GraphDirectoryPublication.swap(root.resolve("missing-stage"), target, aside) }
        assertEquals("earlier", Files.readString(target.resolve("graph.marker")))
        assertFalse(Files.exists(aside))
    }

    @Test
    fun `a directory that cannot be renamed keeps its earlier graph and the save is refused`() = inTemp { root ->
        val target = root.resolve("mounted")
        GraphStore.save(chain(listOf(1, 2, 3)), target)
        val before = dirFiles(target).associateWith { Files.readAllBytes(target.resolve(it)).toList() }
        val nodes = GraphStore.load(target).nodes(Node::class.java).count()
        val stage = root.resolve("stage")
        GraphStore.save(chain(listOf(9)), stage)
        // A mount point refuses to be moved aside; nothing else does. Emptying it and moving the new files in one by one deleted the
        // earlier graph before the new one was whole.
        val renameRefused: (Path, Path) -> Unit = { source, destination ->
            if (source == target) throw java.nio.file.FileSystemException(source.toString(), null, "Device or resource busy")
            Files.move(source, destination)
        }
        val message = assertFailsWith<java.io.IOException> {
            GraphDirectoryPublication.swap(stage, target, root.resolve("aside"), renameRefused)
        }.message!!
        assertContains(message, "Cannot replace $target as a whole")
        assertContains(message, "Device or resource busy")
        assertEquals(before, dirFiles(target).associateWith { Files.readAllBytes(target.resolve(it)).toList() })
        assertEquals(emptyList(), GraphStore.verify(target))
        assertEquals(nodes, GraphStore.load(target).nodes(Node::class.java).count())
        assertFalse(Files.exists(root.resolve("aside")))
    }

    @Test
    fun `a transpose derived from another graph is rebuilt, not read`() = inTemp { root ->
        val dir = root.resolve("g")
        GraphStore.save(chain(listOf(1, 2)), dir)
        val graph = GraphStore.load(dir, GraphStore.LoadMode.MAPPED)
        incomingSignature(graph)
        (graph as? AutoCloseable)?.close()
        assertEquals(GraphManifest.identity(dir), backwardGraphIdentity(dir), "the transpose names the graph it was derived from")
        assertTrue(GraphManifest.verify(dir).isEmpty(), "a derived file is not part of the sealed graph")
        // Another graph's transpose under this graph's name.
        Files.writeString(
            dir.resolve("backward.properties"),
            Files.readString(dir.resolve("backward.properties")).replace(GraphManifest.identity(dir)!!, "0".repeat(64)))
        assertNull(loadPersistedBackwardGraph(dir))
        // A legacy directory keeps reading a transpose without an identity.
        unsealGraph(dir)
        Files.writeString(
            dir.resolve("backward.properties"),
            Files.readAllLines(dir.resolve("backward.properties")).filterNot { it.startsWith(BACKWARD_IDENTITY_KEY) }.joinToString("\n"))
        assertNotNull(loadPersistedBackwardGraph(dir))
    }

    @Test
    fun `a sealed CallSite index is never rewritten in place`() = inTemp { root ->
        val dir = root.resolve("g")
        GraphStore.save(buildTestGraph(), dir, prepareCallSiteStringIndex = true)
        assertTrue(GraphManifest.seals(dir, GraphStore.CALL_SITE_STRING_INDEX_FILE))
        assertFalse(GraphManifest.seals(dir, "backward.graph"))
        unsealGraph(dir)
        assertFalse(GraphManifest.seals(dir, GraphStore.CALL_SITE_STRING_INDEX_FILE))
    }

    @Test
    fun `the producer version falls back when the resource is absent`() {
        assertEquals("unknown", GraphManifest.producerVersion("no-such-resource.properties"))
        assertNotNull(GraphManifest.producerVersion())
    }
}

private object GraphManifestTestDigest {
    fun sha256(bytes: ByteArray): String =
        java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
}
