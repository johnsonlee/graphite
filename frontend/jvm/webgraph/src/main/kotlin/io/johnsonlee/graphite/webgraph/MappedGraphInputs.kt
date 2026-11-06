package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.AnalysisCompleteness
import io.johnsonlee.graphite.graph.ClassOverview
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.input.ResourceAccessor
import it.unimi.dsi.webgraph.ImmutableGraph
import java.io.File
import java.nio.MappedByteBuffer
import java.nio.file.Path

internal class MappedNodeStorage(
    val mappedNodeData: MappedByteBuffer,
    val nodeDataVersion: Int,
    val stringTable: StringTable,
    val nodeOffsets: NodeOffsetIndex,
    val nodeTypeIndex: NodeTypeIndex
)

internal class MappedEdgeStorage(
    val forward: ImmutableGraph,
    val backward: Lazy<ImmutableGraph>,
    val forwardLabels: ByteArray,
    val cumulativeOutdeg: IntArray,
    val edgeCount: Long,
    val comparisonLookup: BranchComparisonLookup
)

internal class MappedGraphDeclarations(
    val declaredTypeTable: Lazy<DeclaredTypeTable> = lazyOf(DeclaredTypeTable.EMPTY),
    val declaredPresence: Lazy<DeclaredPresence?> = lazyOf(null),
    val analysisCompleteness: Lazy<AnalysisCompleteness?> = lazyOf(null)
)

internal class MappedGraphSidecars(
    val metadataFile: File,
    val metadata: Lazy<GraphMetadata>,
    val methodCount: Long,
    val classOverviewProvider: (Int) -> ClassOverview?,
    val resourceAccessor: Lazy<ResourceAccessor>,
    val branchDefinitions: Lazy<PersistedBranchDefinitions> = lazy { PersistedBranchDefinitions.EMPTY },
    val callSiteOrdinals: Lazy<CallSiteOrdinals> = lazy { CallSiteOrdinals.EMPTY },
    val declarations: MappedGraphDeclarations = MappedGraphDeclarations()
)

internal class MappedIndexFiles(val callSiteStringIndexFile: Path, val persistentCallSiteStringIndexEnabled: Boolean)
