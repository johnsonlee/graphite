package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.Node

internal fun MappedWebGraphBackedGraph.callSiteStringIndexLookupCount(): Long = with(context) { callSiteStringIndexLookupCount.get() }

internal fun MappedWebGraphBackedGraph.callSiteStringPreflightCount(): Long = with(context) { callSiteStringPreflightCount.get() }

internal fun MappedWebGraphBackedGraph.callSiteStringProjectionLookupCount(): Long =
    with(context) { callSiteStringProjectionLookupCount.get() }

internal fun MappedWebGraphBackedGraph.callSiteScanPeakActiveWorkers(): Int = with(context) { callSiteScanPeakActiveWorkers.get() }

internal fun MappedWebGraphBackedGraph.callSiteSegmentPeakActiveWorkers(): Int = with(context) { splitCallSitePeakActiveWorkers() }

internal fun MappedWebGraphBackedGraph.callSiteScanActiveWorkers(): Int = with(context) { callSiteScanActiveWorkers.get() }

internal fun MappedWebGraphBackedGraph.callSiteScanAbortedWorkers(): Long = with(context) { callSiteScanAbortedWorkers.get() }

internal fun MappedWebGraphBackedGraph.resetCallSiteScanMetrics() =
    with(context) {
        check(callSiteScanActiveWorkers.get() == 0) { "Cannot reset active CallSite scan metrics" }
        resetSplitCallSiteWorkerMetrics()
        callSiteParallelScanCount.set(0L)
        callSiteStringLookupEntryCount.set(0L)
        callSiteStringIndexLookupCount.set(0L)
        callSiteStringPreflightCount.set(0L)
        callSiteStringProjectionLookupCount.set(0L)
        callSiteScanPeakActiveWorkers.set(0)
        callSiteScanAbortedWorkers.set(0L)
    }

internal fun MappedWebGraphBackedGraph.stringPropertyIndexCount(type: Class<out Node>? = null, property: String? = null): Int =
    with(context) {
        synchronized(stringPropertyIndexLock) {
            stringPropertyIndexes.keys.count { key -> (type == null || key.type == type) && (property == null || key.property == property) }
        }
    }
