package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.graph.MethodMetadataScanConsumer
import io.johnsonlee.graphite.graph.MethodPattern

internal fun MappedGraphContext.methods(pattern: MethodPattern): Sequence<MethodDescriptor> = streamMethods(pattern)

internal fun MappedGraphContext.methods(pattern: MethodPattern, scanConsumer: MethodMetadataScanConsumer): Sequence<MethodDescriptor> =
    streamMethods(pattern, scanConsumer)

internal fun MappedGraphContext.methodCount(): Long = methodCount

internal fun MappedGraphContext.methodSlice(pattern: MethodPattern, limit: Int): List<MethodDescriptor> =
    indexedMethodSlice(pattern, limit, scanConsumer = null)

internal fun MappedGraphContext.methodSlice(
    pattern: MethodPattern,
    limit: Int,
    scanConsumer: MethodMetadataScanConsumer
): List<MethodDescriptor> = indexedMethodSlice(pattern, limit, scanConsumer)

internal fun MappedGraphContext.indexedMethodSlice(
    pattern: MethodPattern,
    limit: Int,
    scanConsumer: MethodMetadataScanConsumer?
): List<MethodDescriptor> {
    if (limit <= 0 || MappedMethodIndex.cannotMatch(pattern, stringTable)) return emptyList()
    val exact =
        if (methodIndex == null) {
            MappedMethodIndex.sliceExact(mappedMethodMetadata.duplicate(), stringTable, methodCount, pattern, limit, scanConsumer)
        } else null
    return exact ?: methodIndex().slice(pattern, limit, scanConsumer)
}

internal fun MappedGraphContext.streamMethods(
    pattern: MethodPattern,
    scanConsumer: MethodMetadataScanConsumer? = null
): Sequence<MethodDescriptor> = sequence {
    if (!MappedMethodIndex.cannotMatch(pattern, stringTable)) {
        yieldAll(methodIndex().methods(pattern, scanConsumer))
    }
}

internal fun MappedGraphContext.methodIndex(): MappedMethodIndex {
    methodIndex?.let {
        return it
    }
    return synchronized(methodIndexLock) {
        methodIndex ?: MappedMethodIndex.build(mappedMethodMetadata.duplicate(), stringTable, methodCount).also { methodIndex = it }
    }
}
