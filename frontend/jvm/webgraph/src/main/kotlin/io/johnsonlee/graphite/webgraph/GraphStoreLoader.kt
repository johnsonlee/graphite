package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.Graph
import it.unimi.dsi.fastutil.io.BinIO
import it.unimi.dsi.webgraph.BVGraph
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

internal fun graphNotDirectoryMessage(dir: Path): String = "${GraphStore.NOT_A_DIRECTORY_PREFIX} $dir"

internal fun readPersistedMetadataMethodCount(metadataFile: Path): Long =
    DataInputStream(BufferedInputStream(metadataFile.toFile().inputStream())).use { dis ->
        readGraphMetadataMethodCount(dis).toLong()
    }

internal fun <T> joinGraphLoad(future: CompletableFuture<T>): T =
    try {
        future.join()
    } catch (e: CompletionException) {
        throw e.cause ?: e
    }

/** Load all nodes eagerly into JVM heap. Best for graphs < 1M nodes. */
internal fun loadEagerGraph(dir: Path): Graph {
    val (nodeDataVersion, nodeCount) = readGraphNodeDataHeader(dir)
    val forwardFuture = CompletableFuture.supplyAsync { BVGraph.load(dir.resolve(GraphStore.FORWARD_GRAPH).toString()) }
    val stringTableFuture = CompletableFuture.supplyAsync { StringTable.load(dir, DeclaredTypeStore.needsSerializedStrings(dir)) }
    val labelsFuture = CompletableFuture.supplyAsync { BinIO.loadBytes(dir.resolve(GraphStore.LABELS_FILE).toString()) }

    val forward = forwardFuture.join()
    val stringTable = stringTableFuture.join()
    val labelBytes = labelsFuture.join()

    val cumulativeOutdeg = loadCumulativeOutdeg(dir, forward)
    val backward = lazy { loadBackwardGraph(dir, forward) }

    val comparisonMap =
        DataInputStream(BufferedInputStream(dir.resolve(GraphStore.COMPARISONS_FILE).toFile().inputStream())).use { dis ->
            NodeSerializer.readComparisons(dis)
        }
    val comparisonLookup = MapBranchComparisonLookup(comparisonMap)

    val callSiteOrdinals = loadPersistedCallSiteOrdinals(dir)
    val nodesById = mutableMapOf<Int, Node>()
    DataInputStream(BufferedInputStream(dir.resolve(GraphStore.NODE_DATA_FILE).toFile().inputStream())).use { dis ->
        NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_NODEDATA)
        val count = dis.readInt()
        repeat(count) {
            val node = readGraphNode(dis, stringTable, nodeDataVersion, callSiteOrdinals)
            nodesById[node.id.value] = node
        }
    }

    val metadata = loadGraphMetadataWithTrailer(dir, stringTable)
    val classOverview = PersistedClassOverviewProvider(dir, stringTable)::load

    return WebGraphBackedGraph(
        EagerNodeStorage(nodesById, nodeDataVersion),
        EagerEdgeStorage(forward, backward, labelBytes, cumulativeOutdeg, comparisonLookup),
        EagerGraphSidecars(
            metadata,
            classOverview,
            PersistedResourceStore.load(dir),
            lazy {
                loadPersistedBranchDefinitions(
                    dir, metadata, nodeCount, eagerBranchDefinitionNodeTags(nodesById))
            },
            lazy { DeclaredTypeStore.load(dir, stringTable) },
            lazy { AnalysisCompletenessStore.load(dir) }))
}

@Suppress("TooGenericExceptionCaught")
internal fun loadMappedGraph(dir: Path, prepareCallSiteStringIndex: Boolean, persistentCallSiteStringIndex: Boolean): Graph {
    require(Files.isDirectory(dir)) { graphNotDirectoryMessage(dir) }

    val (nodeDataVersion, nodeCount) = readGraphNodeDataHeader(dir)
    val metadataFile = dir.resolve(GraphStore.METADATA_FILE)
    val forwardFuture = CompletableFuture.supplyAsync { BVGraph.load(dir.resolve(GraphStore.FORWARD_GRAPH).toString()) }
    val stringTableFuture = CompletableFuture.supplyAsync { StringTable.load(dir, DeclaredTypeStore.needsSerializedStrings(dir)) }
    val nodeIndexFuture = CompletableFuture.supplyAsync { readMappedNodeIndex(dir) }
    val labelsFuture = CompletableFuture.supplyAsync { BinIO.loadBytes(dir.resolve(GraphStore.LABELS_FILE).toString()) }
    val methodCountFuture = CompletableFuture.supplyAsync { readPersistedMetadataMethodCount(metadataFile) }
    val comparisonFuture = CompletableFuture.supplyAsync { MappedBranchComparisonLookup.load(dir.resolve(GraphStore.COMPARISONS_FILE)) }

    val nodeDataPath = dir.resolve(GraphStore.NODE_DATA_FILE)
    val channel = FileChannel.open(nodeDataPath, StandardOpenOption.READ)
    val mappedBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
    channel.close()

    val forward = joinGraphLoad(forwardFuture)
    val labelBytes = joinGraphLoad(labelsFuture)
    val stringTable = joinGraphLoad(stringTableFuture)
    val nodeIndex = joinGraphLoad(nodeIndexFuture)
    val methodCount = joinGraphLoad(methodCountFuture)
    val comparisonLookup = joinGraphLoad(comparisonFuture)
    val backward = lazy { loadBackwardGraph(dir, forward) }
    val cumulativeOutdeg = loadCumulativeOutdeg(dir, forward)
    val metadata = lazy { loadGraphMetadataWithTrailer(dir, stringTable) }
    val branchDefinitions = lazy {
        loadPersistedBranchDefinitions(
            dir, metadata.value, nodeCount, mappedBranchDefinitionNodeTags(nodeIndex.nodeOffsets, mappedBuffer))
    }
    val classOverview = PersistedClassOverviewProvider(dir, stringTable)::load

    val graph =
        MappedWebGraphBackedGraph(
            nodes = MappedNodeStorage(mappedBuffer, nodeDataVersion, stringTable, nodeIndex.nodeOffsets, nodeIndex.nodeTypeIndex),
            edges = MappedEdgeStorage(forward, backward, labelBytes, cumulativeOutdeg, labelBytes.size.toLong(), comparisonLookup),
            sidecars =
                MappedGraphSidecars(
                    metadataFile.toFile(),
                    metadata,
                    methodCount,
                    classOverview,
                    lazy { PersistedResourceStore.load(dir) },
                    branchDefinitions,
                    lazy { loadPersistedCallSiteOrdinals(dir) },
                    MappedGraphDeclarations(
                        // Query-only state: preserve the lazy reads, bindings and validation.
                        lazy { DeclaredTypeStore.load(dir, stringTable) },
                        lazy { DeclaredPresence.load(dir, nodeIndex.nodeOffsets.size, methodCount.toInt()) },
                        lazy { AnalysisCompletenessStore.load(dir) })),
            indexes = MappedIndexFiles(dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE), persistentCallSiteStringIndex))
    if (prepareCallSiteStringIndex) {
        try {
            if (graph.prepareCallSiteStringIndex()) graph.persistPreparedCallSiteStringIndex()
        } catch (error: Exception) {
            graph.close()
            throw error
        }
    }
    return graph
}

/**
 * The ordinal sidecar of the graph in [dir], bound by the digest `graph.metadata` ends with: read from the metadata's tail, not by
 * parsing it, so a mapped graph pays nothing for the metadata on the first call site it decodes.
 */
internal fun loadPersistedCallSiteOrdinals(dir: Path): CallSiteOrdinals =
    CallSiteOrdinals.load(
        dir.resolve(GraphStore.CALL_SITE_ORDINALS_FILE),
        readCallSiteOrdinalBinding(dir.resolve(GraphStore.METADATA_FILE)))

/**
 * Parse `graph.metadata` with its optional sections: the trailer that binds it to its branch-definition sidecar and the synthetic
 * identities, when present.
 */
internal fun loadGraphMetadataWithTrailer(dir: Path, stringTable: StringTable): GraphMetadata =
    DataInputStream(BufferedInputStream(dir.resolve(GraphStore.METADATA_FILE).toFile().inputStream())).use { dis ->
        val metadata = readGraphMetadata(dis, stringTable)
        readGraphMetadataOptionalSections(dis, stringTable, metadata)
    }
