package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class MappedLargeDictionaryContractTest {
    @Test
    fun `dictionary beyond the dense matcher bound keeps exact positive and negative candidates`() {
        val dir = Files.createTempDirectory("large-dictionary-correctness")
        try {
            val strings = StringTable.build(List(DENSE_BOUND + 1) { if (it == 0) "!needle" else "entry-$it" }, dir)
            val node = StringConstant(NodeId(0), "!needle")
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { writeGraphNode(it, node, strings) }
            val offsets = IntNodeOffsetIndex(1).apply { set(0, 0) }
            val types = object : NodeTypeIndex {
                override fun ids(type: Class<out Node>) =
                    if (type.isInstance(node)) sequenceOf(0) else emptySequence()
                override fun count(type: Class<out Node>) = if (type.isInstance(node)) 1L else 0L
            }
            val candidates = MappedPropertyTextCandidates(ByteBuffer.wrap(bytes.toByteArray()), offsets, types, strings)
            var work = 0
            val consume = GraphWorkConsumer { work++ }
            assertEquals(listOf(0), candidates.ids(StringConstant::class.java, listOf("needle"), consume).toList())
            assertEquals(emptyList(), candidates.ids(StringConstant::class.java, listOf("absent"), consume).toList())
            assertEquals(2, work, "each complete candidate scan charges its one inspected node")
        } finally { dir.toFile().deleteRecursively() }
    }

    private companion object {
        const val DENSE_BOUND = 1 shl 20
    }
}
