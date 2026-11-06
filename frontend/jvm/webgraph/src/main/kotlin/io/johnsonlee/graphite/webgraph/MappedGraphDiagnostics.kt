package io.johnsonlee.graphite.webgraph

internal fun MappedWebGraphBackedGraph.isMetadataInitialized(): Boolean = with(context) { metadata.isInitialized() }

internal fun MappedWebGraphBackedGraph.isCallSiteOrdinalsInitialized(): Boolean = with(context) { callSiteOrdinals.isInitialized() }

internal fun MappedWebGraphBackedGraph.isMethodIndexInitialized(): Boolean = with(context) { methodIndex != null }

internal fun MappedWebGraphBackedGraph.rawStringMatchStateBytes(): Long = with(context) { rawStringMatchStates.retainedBytes() }

internal fun MappedWebGraphBackedGraph.rawStringMatchStateCount(): Int = with(context) { rawStringMatchStates.size() }

internal fun MappedWebGraphBackedGraph.rawProjectionMatchCount(): Int = with(context) { rawProjectionMatches.size() }

internal fun MappedWebGraphBackedGraph.callSiteStringIndexBytes(): Long = with(context) { callSiteStringIndex?.retainedBytes ?: 0L }

internal fun MappedWebGraphBackedGraph.hasExactCallSiteProjectionTupleIndex(): Boolean =
    with(context) { callSiteStringIndex?.hasExactProjectionTupleIndex() == true }

internal fun MappedWebGraphBackedGraph.isCallSiteStringIndexInitialized(): Boolean = with(context) { callSiteStringIndex != null }

internal fun MappedWebGraphBackedGraph.isMappedCallSiteStringIndexViewInitialized(): Boolean =
    with(context) { mappedCallSiteStringIndexView != null }

internal fun MappedWebGraphBackedGraph.mappedPostingRangeValidationCount(): Int =
    with(context) { mappedCallSiteStringIndexView?.validatedPostingRangeCount() ?: 0 }

internal fun MappedWebGraphBackedGraph.mappedPostingRangeValidationBytes(): Long =
    with(context) { mappedCallSiteStringIndexView?.validatedPostingRangeBytes() ?: 0L }

internal fun MappedWebGraphBackedGraph.isCallSiteTrigramIndexInitialized(): Boolean =
    with(context) { callSiteStringIndex?.isTrigramPostingsInitialized() == true }

internal fun MappedWebGraphBackedGraph.isCallSiteStringIndexLoadedFromPersistence(): Boolean =
    with(context) { callSiteStringIndexLoadedFromPersistence }

internal fun MappedWebGraphBackedGraph.callSiteParallelScanCount(): Long = with(context) { callSiteParallelScanCount.get() }

internal fun MappedWebGraphBackedGraph.callSiteStringLookupEntryCount(): Long = with(context) { callSiteStringLookupEntryCount.get() }
