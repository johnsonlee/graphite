package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ConstantNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.Node
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class MappedNodeTypeIndexTest {
    @Test
    fun `superclass scans follow persisted ranges regardless of class hashes or directory order`() {
        val idsByTag = mapOf(0 to listOf(9, 2), 1 to listOf(1), 15 to listOf(8))
        val directory = Files.createTempDirectory("mapped-type-order")
        try {
            // Different physical layouts ensure a fixed class-hash order cannot accidentally pass.
            for (storageOrder in listOf(listOf(0, 1, 15), listOf(15, 1, 0), listOf(1, 0, 15))) {
                val path = directory.resolve("types-${storageOrder.joinToString("-")}")
                writeTypeIndex(path, storageOrder, idsByTag)
                val index = MappedNodeTypeIndex.load(path)
                val expected = storageOrder.flatMap(idsByTag::getValue)
                assertEquals(expected, index.ids(Node::class.java).toList())
                assertEquals(expected, index.ids(Node::class.java).toList(), "iterators must be independent")
                assertEquals(
                    storageOrder.filter { it != 15 }.flatMap(idsByTag::getValue),
                    index.ids(ConstantNode::class.java).toList()
                )
                assertEquals(listOf(9, 2), index.ids(IntConstant::class.java).toList())
                assertEquals(4L, index.count(Node::class.java))
                assertEquals(3L, index.count(ConstantNode::class.java))
                assertEquals(emptyList(), index.ids(CallSiteNode::class.java).toList())
                assertEquals(0L, index.count(CallSiteNode::class.java))

                val slice = mutableListOf<Int>()
                index.forEachIdWhile(Node::class.java, 1, 3) { id -> slice.add(id); true }
                assertEquals(expected.subList(1, 3), slice)
                val stopped = mutableListOf<Int>()
                index.forEachIdWhile(Node::class.java, 0, expected.size) { id -> stopped.add(id); false }
                assertEquals(expected.take(1), stopped)

                val iterator = index.idIterator(Node::class.java)
                expected.forEach { assertEquals(it, iterator.nextInt()) }
                assertFalse(iterator.hasNext())
                assertFailsWith<NoSuchElementException> { iterator.nextInt() }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun writeTypeIndex(path: Path, storageOrder: List<Int>, idsByTag: Map<Int, List<Int>>) {
        val entryBytes = 1 + Int.SIZE_BYTES + Long.SIZE_BYTES
        var offset = (2 * Int.SIZE_BYTES + storageOrder.size * entryBytes).toLong()
        val offsets = storageOrder.associateWith { tag ->
            offset.also { offset += idsByTag.getValue(tag).size * Int.SIZE_BYTES }
        }
        DataOutputStream(Files.newOutputStream(path)).use { output ->
            NodeSerializer.writeHeader(output, NodeSerializer.MAGIC_TYPEINDEX)
            output.writeInt(storageOrder.size)
            // Deliberately separate directory entry order from the physical ranges it describes.
            storageOrder.reversed().forEach { tag ->
                output.writeByte(tag)
                output.writeInt(idsByTag.getValue(tag).size)
                output.writeLong(offsets.getValue(tag))
            }
            storageOrder.flatMap(idsByTag::getValue).forEach(output::writeInt)
        }
    }
}
