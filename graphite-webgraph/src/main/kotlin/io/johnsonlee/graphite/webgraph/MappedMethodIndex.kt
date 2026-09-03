package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.MethodMetadataScanConsumer
import io.johnsonlee.graphite.graph.MethodPattern
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap
import java.nio.ByteBuffer
import java.util.regex.Pattern

/**
 * Compact, order-preserving view of the method descriptors at the start of graph metadata.
 *
 * The index retains only string-table ids and parameter ranges. Queries therefore avoid
 * constructing a [MethodDescriptor] (and its type list) for records rejected by their pattern.
 */
internal class MappedMethodIndex private constructor(
    private val declaringClassIds: IntArray,
    private val nameIds: IntArray,
    private val parameterOffsets: IntArray,
    private val parameterCounts: IntArray,
    private val parameterTypeIds: IntArray,
    private val returnTypeIds: IntArray,
    private val declaringClassRanges: Int2LongOpenHashMap,
    private val strings: StringTable
) {
    val size: Int
        get() = declaringClassIds.size

    fun methods(
        pattern: MethodPattern,
        scanConsumer: MethodMetadataScanConsumer? = null
    ): Sequence<MethodDescriptor> = sequence {
        val matcher = RawMethodPattern(pattern, strings)
        for (index in candidateIndices(matcher)) {
            scanConsumer?.inspect()
            if (matcher.matches(this@MappedMethodIndex, index)) yield(materialize(index))
        }
    }

    fun slice(
        pattern: MethodPattern,
        limit: Int,
        scanConsumer: MethodMetadataScanConsumer? = null
    ): List<MethodDescriptor> {
        val boundedLimit = limit.coerceAtLeast(0)
        if (boundedLimit == 0) return emptyList()
        val matcher = RawMethodPattern(pattern, strings)
        val result = ArrayList<MethodDescriptor>(minOf(size, boundedLimit))
        for (index in candidateIndices(matcher)) {
            scanConsumer?.inspect()
            if (matcher.matches(this, index)) {
                result += materialize(index)
                if (result.size >= boundedLimit) break
            }
        }
        return result
    }

    private fun candidateIndices(matcher: RawMethodPattern): IntRange {
        val declaringClassId = matcher.exactDeclaringClassId ?: return declaringClassIds.indices
        return when (val packedRange = declaringClassRanges.get(declaringClassId)) {
            MISSING_CLASS_RANGE -> EMPTY_INDEX_RANGE
            NON_CONTIGUOUS_CLASS_RANGE -> declaringClassIds.indices
            else -> unpackStart(packedRange) until unpackEnd(packedRange)
        }
    }

    private fun materialize(index: Int): MethodDescriptor {
        val parameterOffset = parameterOffsets[index]
        val parameters = List(parameterCounts[index]) { parameterIndex ->
            TypeDescriptor(strings.get(parameterTypeIds[parameterOffset + parameterIndex]))
        }
        return MethodDescriptor(
            TypeDescriptor(strings.get(declaringClassIds[index])),
            strings.get(nameIds[index]),
            parameters,
            TypeDescriptor(strings.get(returnTypeIds[index]))
        )
    }

    private class RawMethodPattern(pattern: MethodPattern, private val strings: StringTable) {
        private val declaringClass = StringPattern(pattern.declaringClass, pattern.useRegex, strings)
        private val name = StringPattern(pattern.name, pattern.useRegex, strings)
        private val parameterTypes = pattern.parameterTypes?.map { StringPattern(it, pattern.useRegex, strings) }
        private val returnType = StringPattern(pattern.returnType, pattern.useRegex, strings)

        val exactDeclaringClassId: Int?
            get() = declaringClass.exactStringId

        @Suppress("ReturnCount")
        fun matches(index: MappedMethodIndex, methodIndex: Int): Boolean {
            if (!declaringClass.matches(index.declaringClassIds[methodIndex], strings)) return false
            if (!name.matches(index.nameIds[methodIndex], strings)) return false
            parameterTypes?.let { expected ->
                if (index.parameterCounts[methodIndex] != expected.size) return false
                val offset = index.parameterOffsets[methodIndex]
                for (parameterIndex in expected.indices) {
                    if (!expected[parameterIndex].matches(index.parameterTypeIds[offset + parameterIndex], strings)) {
                        return false
                    }
                }
            }
            return returnType.matches(index.returnTypeIds[methodIndex], strings)
        }
    }

    private class StringPattern(pattern: String?, useRegex: Boolean, strings: StringTable) {
        private val raw = pattern
        private val exactRegex = exactRegex(pattern, useRegex)
        private val regex = pattern?.takeIf { useRegex && exactRegex == null }?.let(Pattern::compile)
        private val prefix = pattern?.takeIf { !useRegex && it.endsWith("*") }?.dropLast(1)
        private val expectedId = when {
            exactRegex != null -> strings.findId(exactRegex)
            pattern != null && regex == null && prefix == null -> strings.findId(pattern)
            else -> null
        }
        private val matchStates by lazy { ByteArray(strings.size()) }

        val exactStringId: Int?
            get() = expectedId

        @Suppress("ReturnCount")
        fun matches(stringId: Int, strings: StringTable): Boolean {
            raw ?: return true
            expectedId?.let { return stringId == it }
            if (prefix?.isEmpty() == true) return true
            when (matchStates[stringId]) {
                STRING_MATCH -> return true
                STRING_MISS -> return false
            }
            val actual = strings.get(stringId)
            return when {
                regex != null -> regex.matcher(actual).matches()
                else -> actual.startsWith(requireNotNull(prefix))
            }.also { matched -> matchStates[stringId] = if (matched) STRING_MATCH else STRING_MISS }
        }

        private companion object {
            fun exactRegex(pattern: String?, useRegex: Boolean): String? {
                if (!useRegex || pattern == null) return null
                return pattern.takeIf { it.startsWith("\\Q") && it.endsWith("\\E") }
                    ?.substring(2, pattern.length - 2)
                    ?.replace("\\E\\\\E\\Q", "\\E")
                    ?.takeIf { Pattern.quote(it) == pattern }
            }
        }
    }

    companion object {
        @Suppress("ReturnCount", "ComplexCondition")
        fun sliceExact(
            metadata: ByteBuffer,
            strings: StringTable,
            expectedMethodCount: Long,
            pattern: MethodPattern,
            limit: Int,
            scanConsumer: MethodMetadataScanConsumer?
        ): List<MethodDescriptor>? {
            val exact = ExactMethodPattern.from(pattern, strings) ?: return null
            if (limit <= 0 || exact.missingString) return emptyList()
            val input = ByteBufferDataInput(metadata, 0)
            NodeSerializer.readHeader(input, NodeSerializer.MAGIC_METADATA)
            val methodCount = input.readInt()
            require(methodCount >= 0) { "Invalid metadata method count: $methodCount" }
            require(methodCount.toLong() == expectedMethodCount) {
                "Metadata method count changed while loading: expected $expectedMethodCount, found $methodCount"
            }
            val result = ArrayList<MethodDescriptor>(minOf(methodCount, limit))
            repeat(methodCount) { index ->
                if ((index and BUILD_INTERRUPTION_POLL_MASK) == 0 && Thread.currentThread().isInterrupted) {
                    throw java.util.concurrent.CancellationException("Mapped method scan interrupted")
                }
                scanConsumer?.inspect()
                val declaringClassId = input.readInt()
                val nameId = input.readInt()
                val parameterCount = input.readInt()
                require(parameterCount >= 0) { "Invalid method parameter count: $parameterCount" }
                var parametersMatch = parameterCount == exact.parameterTypeIds.size
                repeat(parameterCount) { parameterIndex ->
                    val parameterTypeId = input.readInt()
                    if (parameterIndex >= exact.parameterTypeIds.size ||
                        parameterTypeId != exact.parameterTypeIds[parameterIndex]
                    ) {
                        parametersMatch = false
                    }
                }
                val returnTypeId = input.readInt()
                if (declaringClassId == exact.declaringClassId &&
                    nameId == exact.nameId &&
                    parametersMatch &&
                    (exact.returnTypeId == null || returnTypeId == exact.returnTypeId)
                ) {
                    result += exact.materialize(strings, returnTypeId)
                    if (result.size >= limit) return result
                }
            }
            return result
        }

        fun build(
            metadata: ByteBuffer,
            strings: StringTable,
            expectedMethodCount: Long
        ): MappedMethodIndex {
            val input = ByteBufferDataInput(metadata, 0)
            NodeSerializer.readHeader(input, NodeSerializer.MAGIC_METADATA)
            val methodCount = input.readInt()
            require(methodCount >= 0) { "Invalid metadata method count: $methodCount" }
            require(methodCount.toLong() == expectedMethodCount) {
                "Metadata method count changed while loading: expected $expectedMethodCount, found $methodCount"
            }

            val declaringClassIds = IntArray(methodCount)
            val nameIds = IntArray(methodCount)
            val parameterOffsets = IntArray(methodCount)
            val parameterCounts = IntArray(methodCount)
            val returnTypeIds = IntArray(methodCount)
            val declaringClassRanges = Int2LongOpenHashMap().apply {
                defaultReturnValue(MISSING_CLASS_RANGE)
            }
            var parameterTypeIds = IntArray(methodCount)
            var parameterSize = 0
            var previousDeclaringClassId = -1
            var declaringClassRangeStart = 0

            repeat(methodCount) { index ->
                if ((index and BUILD_INTERRUPTION_POLL_MASK) == 0 && Thread.currentThread().isInterrupted) {
                    throw java.util.concurrent.CancellationException("Mapped method-index build interrupted")
                }
                val declaringClassId = input.readInt()
                declaringClassIds[index] = declaringClassId
                if (index > 0 && declaringClassId != previousDeclaringClassId) {
                    recordClassRange(
                        declaringClassRanges,
                        previousDeclaringClassId,
                        declaringClassRangeStart,
                        index
                    )
                    declaringClassRangeStart = index
                }
                previousDeclaringClassId = declaringClassId
                nameIds[index] = input.readInt()
                val parameterCount = input.readInt()
                require(parameterCount >= 0) { "Invalid method parameter count: $parameterCount" }
                parameterOffsets[index] = parameterSize
                parameterCounts[index] = parameterCount
                val requiredParameterSize = Math.addExact(parameterSize, parameterCount)
                if (requiredParameterSize > parameterTypeIds.size) {
                    parameterTypeIds = parameterTypeIds.copyOf(grownCapacity(parameterTypeIds.size, requiredParameterSize))
                }
                repeat(parameterCount) {
                    parameterTypeIds[parameterSize++] = input.readInt()
                }
                returnTypeIds[index] = input.readInt()
            }
            if (methodCount > 0) {
                recordClassRange(
                    declaringClassRanges,
                    previousDeclaringClassId,
                    declaringClassRangeStart,
                    methodCount
                )
            }

            return MappedMethodIndex(
                declaringClassIds,
                nameIds,
                parameterOffsets,
                parameterCounts,
                parameterTypeIds.copyOf(parameterSize),
                returnTypeIds,
                declaringClassRanges,
                strings
            )
        }

        private fun recordClassRange(
            ranges: Int2LongOpenHashMap,
            declaringClassId: Int,
            start: Int,
            end: Int
        ) {
            if (ranges.containsKey(declaringClassId)) {
                ranges.put(declaringClassId, NON_CONTIGUOUS_CLASS_RANGE)
            } else {
                ranges.put(declaringClassId, packRange(start, end))
            }
        }

        private fun packRange(start: Int, end: Int): Long =
            (start.toLong() shl INT_BITS) or (end.toLong() and UNSIGNED_INT_MASK)

        private fun unpackStart(range: Long): Int = (range ushr INT_BITS).toInt()

        private fun unpackEnd(range: Long): Int = range.toInt()

        private fun grownCapacity(current: Int, required: Int): Int {
            var capacity = maxOf(current, MIN_PARAMETER_CAPACITY)
            while (capacity < required) {
                capacity = if (capacity > Int.MAX_VALUE / 2) required else capacity * 2
            }
            return capacity
        }

        private const val MIN_PARAMETER_CAPACITY = 16
        private const val BUILD_INTERRUPTION_POLL_MASK = 1_023
        private const val STRING_MISS: Byte = 1
        private const val STRING_MATCH: Byte = 2
        private const val INT_BITS = 32
        private const val UNSIGNED_INT_MASK = 0xffff_ffffL
        private const val MISSING_CLASS_RANGE = -1L
        private const val NON_CONTIGUOUS_CLASS_RANGE = -2L
        private val EMPTY_INDEX_RANGE = IntRange.EMPTY
    }

    private data class ExactMethodPattern(
        val declaringClass: String,
        val name: String,
        val parameterTypes: List<String>,
        val returnType: String?,
        val declaringClassId: Int,
        val nameId: Int,
        val parameterTypeIds: IntArray,
        val returnTypeId: Int?
    ) {
        val missingString: Boolean
            get() = declaringClassId < 0 || nameId < 0 ||
                parameterTypeIds.any { it < 0 } || returnTypeId != null && returnTypeId < 0

        fun materialize(strings: StringTable, actualReturnTypeId: Int): MethodDescriptor = MethodDescriptor(
            TypeDescriptor(declaringClass),
            name,
            parameterTypes.map(::TypeDescriptor),
            TypeDescriptor(returnType ?: strings.get(actualReturnTypeId))
        )

        companion object {
            @Suppress("ReturnCount")
            fun from(pattern: MethodPattern, strings: StringTable): ExactMethodPattern? {
                if (!pattern.useRegex) return null
                val declaringClass = exactValue(pattern.declaringClass ?: return null) ?: return null
                val name = exactValue(pattern.name ?: return null) ?: return null
                val parameterTypes = pattern.parameterTypes?.map { exactValue(it) ?: return null } ?: return null
                val returnType = pattern.returnType?.let { exactValue(it) ?: return null }
                return ExactMethodPattern(
                    declaringClass,
                    name,
                    parameterTypes,
                    returnType,
                    strings.findId(declaringClass),
                    strings.findId(name),
                    parameterTypes.map(strings::findId).toIntArray(),
                    returnType?.let(strings::findId)
                )
            }

            private fun exactValue(pattern: String): String? = pattern
                .takeIf { it.startsWith("\\Q") && it.endsWith("\\E") }
                ?.substring(2, pattern.length - 2)
                ?.replace("\\E\\\\E\\Q", "\\E")
                ?.takeIf { Pattern.quote(it) == pattern }
        }
    }
}
