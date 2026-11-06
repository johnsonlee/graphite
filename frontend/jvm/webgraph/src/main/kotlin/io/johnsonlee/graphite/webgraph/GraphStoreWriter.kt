package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.ClassOverview
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.graph.MmapGraph
import it.unimi.dsi.fastutil.io.BinIO
import it.unimi.dsi.webgraph.BVGraph
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.HexFormat

/** Writes graph records, adjacency and metadata in their persisted format. */
internal object GraphStoreWriter {

    fun saveGraph(graph: Graph, dir: Path, compressionThreads: Int, prepareCallSiteStringIndex: Boolean, declaredTypeVersion: Int) {
        // A loaded graph reads its evidence on first use, from the directory this save may be
        // rewriting: materialise it before any file of that directory is removed.
        val completeness = graph.analysisCompleteness()
        Files.createDirectories(dir)
        Files.deleteIfExists(dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE))
        Files.deleteIfExists(dir.resolve(GraphStore.BRANCH_DEFINITIONS_FILE))
        Files.deleteIfExists(dir.resolve(GraphStore.CALL_SITE_ORDINALS_FILE))
        Files.deleteIfExists(dir.resolve(DeclaredKeySummaryBuilder.FILE_NAME))
        Files.deleteIfExists(dir.resolve(DeclaredPresence.FILE_NAME))
        Files.deleteIfExists(dir.resolve(AnalysisCompletenessStore.FILE_NAME))

        // 1. Stream nodes: find maxNodeId, count nodes, collect strings
        var maxNodeId = 0
        var nodeCount = 0
        var ordinalCount = 0
        val allStrings = mutableSetOf<String>()
        val nodeTypeCounts = IntArray(UByte.MAX_VALUE.toInt() + 1)
        val classOverviewBuilder = ClassOverviewBuilder()
        for (node in graph.nodes(Node::class.java)) {
            if (node.id.value > maxNodeId) maxNodeId = node.id.value
            nodeCount++
            nodeTypeCounts[NodeRecordCodec.tagOf(node)]++
            collectSingleNodeStrings(node, allStrings)
            if (node is CallSiteNode) {
                classOverviewBuilder.add(node)
                if (node.ordinal != null) ordinalCount++
            }
        }
        val classOverviewCounts = classOverviewBuilder.topClassCounts(ClassOverviewStore.MAX_PERSISTED_CLASSES)
        val classOverviewEdges = ClassOverviewEdgeBuilder(classOverviewCounts.keys)
        val prepareCallSiteIndex =
            classOverviewBuilder.callSiteCount() > 0L && (prepareCallSiteStringIndex || mappedCallSiteStringIndexPreparationEnabled())
        val callSiteIndexInput =
            if (prepareCallSiteIndex) {
                CallSiteIndexPersistenceInput(classOverviewBuilder.callSiteCount().toInt())
            } else {
                null
            }

        val declaredTypes = graph.declaredTypes()

        // 2. Collect metadata
        val metadata = GraphStore.collectMetadata(graph)
        GraphMetadataCodec.collectMetadataStrings(metadata, allStrings)
        ClassOverviewStore.collectStrings(
            ClassOverview(classCounts = classOverviewCounts, classEdges = emptyMap(), callSiteCount = classOverviewBuilder.callSiteCount()),
            allStrings)
        val declaredTypePlan = DeclaredTypeStore.collectStrings(declaredTypes, allStrings, declaredTypeVersion)
        val stringTable = StringTable.build(allStrings, dir, declaredTypes != DeclaredTypeTable.EMPTY)
        allStrings.clear()

        // 3. Build forward adjacency + labels + comparisons
        val numNodes = maxNodeId + 1
        val comparisonMap = mutableMapOf<Long, BranchComparison>()
        val (forwardAdj, labelArray) =
            if (graph is MmapGraph) {
                GraphAdjacencyStorage.buildForwardData(graph, numNodes, comparisonMap)
            } else {
                GraphAdjacencyStorage.buildForwardData(graph, numNodes, comparisonMap)
            }

        // 4. Store BVGraph (forward only)
        val forwardGraph = GraphStore.PrecomputedImmutableGraph(forwardAdj)
        BVGraph.store(
            forwardGraph,
            dir.resolve(GraphStore.FORWARD_GRAPH).toString(),
            BVGraph.DEFAULT_WINDOW_SIZE,
            BVGraph.DEFAULT_MAX_REF_COUNT,
            BVGraph.DEFAULT_MIN_INTERVAL_LENGTH,
            BVGraph.DEFAULT_ZETA_K,
            0,
            compressionThreads)
        stripPropertiesComments(dir.resolve("${GraphStore.FORWARD_GRAPH}.properties"))

        // 5. Store labels + comparisons
        BinIO.storeBytes(labelArray, dir.resolve(GraphStore.LABELS_FILE).toString())
        BinIO.storeInts(forwardAdj.offsets, dir.resolve(LABEL_PREFIX_FILE).toString())
        DataOutputStream(BufferedOutputStream(dir.resolve(GraphStore.COMPARISONS_FILE).toFile().outputStream())).use { dos ->
            NodeSerializer.writeComparisons(dos, comparisonMap)
        }

        // 6. Write nodedata + nodeindex and capture compact ordinal fields and the declared-key
        //    summary in the same pass.
        val ordinalInput = CallSiteOrdinalPersistenceInput(ordinalCount)
        val declaredKeys = if (declaredTypes == DeclaredTypeTable.EMPTY) null else DeclaredKeySummaryBuilder(declaredTypes, maxNodeId)
        val layoutDigest =
            NodeDataWriter(
                    graph,
                    dir,
                    NodeWritePlan(
                        nodeCount,
                        maxNodeId,
                        nodeTypeCounts,
                        classOverviewBuilder.callSiteCount().toInt(),
                        stringTable,
                        classOverviewEdges,
                        callSiteIndexInput,
                        ordinalInput,
                        declaredKeys))
                .write()
        // The node layout identity: which node sits at which id. Sidecars indexed by node id carry it.
        ForwardProperties.write(dir, mapOf(ForwardProperties.NODE_LAYOUT_KEY to HexFormat.of().formatHex(layoutDigest)))
        // The build's evidence, when the graph carries one, bound to this save of the graph.
        AnalysisCompletenessStore.save(dir, completeness, layoutDigest)

        // 7. Save metadata with the trailer that binds it to the branch-definition sidecar and, last,
        //    the binding of the call-site ordinal sidecar, then the two sidecars.
        val branchDefinitions = BranchDefinitionCodec.encodeBranchDefinitions(metadata.branchScopes, metadata.localDefinitions)
        val callSiteOrdinals = ordinalInput.encode()
        DataOutputStream(BufferedOutputStream(dir.resolve(GraphStore.METADATA_FILE).toFile().outputStream())).use { dos ->
            GraphMetadataCodec.saveMetadata(metadata, dos, stringTable)
            BranchDefinitionCodec.writeMetadataTrailer(dos, branchDefinitions.payloadDigest)
            GraphMetadataCodec.writeSyntheticIdentities(metadata, dos, stringTable)
            if (callSiteOrdinals != null) CallSiteOrdinalCodec.writeCallSiteOrdinalBinding(dos, callSiteOrdinals.digest)
        }
        Files.write(dir.resolve(GraphStore.BRANCH_DEFINITIONS_FILE), branchDefinitions.bytes)
        if (callSiteOrdinals != null) Files.write(dir.resolve(GraphStore.CALL_SITE_ORDINALS_FILE), callSiteOrdinals.bytes)

        val typesDigest = DeclaredTypeStore.save(declaredTypePlan, dir, stringTable)
        if (declaredKeys != null && typesDigest != null) {
            Files.write(dir.resolve(DeclaredKeySummaryBuilder.FILE_NAME), declaredKeys.encode(typesDigest, layoutDigest))
            writeDeclaredPresence(dir, metadata, declaredTypes, declaredKeys, typesDigest, layoutDigest)
        }

        // 8. Save class-level overview summary for explorer routes
        ClassOverviewStore.save(
            ClassOverview(
                classCounts = classOverviewCounts,
                classEdges = classOverviewEdges.build(),
                callSiteCount = classOverviewBuilder.callSiteCount()),
            dir,
            stringTable)

        // 9. Save persisted text resources for loaded-graph access
        PersistedResourceStore.save(graph, dir)

        // 10. Build the query-only CallSite index once and persist it for mapped loads.
        if (prepareCallSiteIndex) {
            persistCallSiteStringIndex(checkNotNull(callSiteIndexInput), stringTable, dir)
        }
    }

    /**
     * The saver's presence answers: the node bits the summary builder collected, and a shape per method in saveMetadata's order, which is
     * the mapped index's.
     */
    fun writeDeclaredPresence(
        dir: Path,
        metadata: GraphMetadata,
        declaredTypes: DeclaredTypeTable,
        declaredKeys: DeclaredKeySummaryBuilder,
        typesDigest: ByteArray,
        layoutDigest: ByteArray
    ) {
        val shapes = ByteArray(metadata.methods.size)
        var ordinal = 0
        for ((_, method) in metadata.methods) {
            val key = MemberTypeKey(method.declaringClass.className, method.name, method.descriptor)
            shapes[ordinal++] = DeclaredPresence.shapeByte(declaredTypes.methods[key])
        }
        Files.write(
            dir.resolve(DeclaredPresence.FILE_NAME),
            DeclaredPresence.encode(typesDigest, layoutDigest, declaredKeys.presenceWords, declaredKeys.nodeBits, shapes))
    }

    /** Collect strings from a single node (avoids building a list of all nodes). */
    fun collectSingleNodeStrings(node: Node, dest: MutableSet<String>) {
        NodeRecordCodec.collectNodeStrings(node, dest)
    }
}
