package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.ReleasableStringPropertyDisjunctionCache

internal class MappedCacheCapabilities(private val context: MappedGraphContext) : ReleasableStringPropertyDisjunctionCache {
    override fun releaseStringPropertyDisjunctionCache() = context.releaseStringPropertyDisjunctionCache()
}
