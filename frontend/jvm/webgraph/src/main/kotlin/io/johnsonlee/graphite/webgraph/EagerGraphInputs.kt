package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.AnalysisCompleteness
import io.johnsonlee.graphite.graph.ClassOverview
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.input.ResourceAccessor
import it.unimi.dsi.webgraph.ImmutableGraph

/** Heap records and their persisted encoding version. */
internal class EagerNodeStorage(val nodesById: Map<Int, Node>, val nodeDataVersion: Int)

/** Compressed adjacency and the label/comparison data needed to reconstruct its edges. */
internal class EagerEdgeStorage(
    val forward: ImmutableGraph,
    val backward: Lazy<ImmutableGraph>,
    val forwardLabels: ByteArray,
    val cumulativeOutdeg: IntArray,
    val comparisonLookup: BranchComparisonLookup
)

/** Metadata and resources, retaining the reader's original lazy sidecar boundaries. */
internal class EagerGraphSidecars(
    val metadata: GraphMetadata,
    val classOverviewProvider: (Int) -> ClassOverview?,
    val resources: ResourceAccessor,
    val branchDefinitions: Lazy<PersistedBranchDefinitions>,
    val declaredTypeTable: Lazy<DeclaredTypeTable>,
    val analysisCompleteness: Lazy<AnalysisCompleteness?>
)
