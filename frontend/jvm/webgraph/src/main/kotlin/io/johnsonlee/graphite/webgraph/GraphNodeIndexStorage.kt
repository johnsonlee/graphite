package io.johnsonlee.graphite.webgraph

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * Ensure the `graph.nodeindex` file exists for the given graph directory. If it doesn't exist, scans `graph.nodedata` to build it. This
 * is idempotent — safe to call on graphs that already have the index.
 */
internal fun ensureGraphNodeIndex(dir: Path) {
    val indexFile = dir.resolve(NODE_INDEX_FILE)
    if (Files.exists(indexFile)) return
    val stringTable = StringTable.load(dir)
    buildGraphNodeIndex(dir.resolve(GraphStore.NODE_DATA_FILE), indexFile, stringTable)
}

internal fun buildGraphNodeIndex(nodeDataPath: Path, nodeIndexPath: Path, stringTable: StringTable) {
    // Stream directly: read nodedata, write nodeindex entry by entry (no intermediate list)
    RandomAccessFile(nodeDataPath.toFile(), "r").use { raf ->
        val nodeDataVersion = NodeSerializer.readHeader(raf, NodeSerializer.MAGIC_NODEDATA)
        val count = raf.readInt()
        DataOutputStream(BufferedOutputStream(nodeIndexPath.toFile().outputStream())).use { dos ->
            NodeSerializer.writeHeader(dos, NodeSerializer.MAGIC_NODEINDEX)
            dos.writeInt(count)
            repeat(count) {
                val offset = raf.filePointer
                val nodeId = raf.readInt()
                val tag = raf.readByte().toInt()
                dos.writeInt(nodeId)
                dos.writeByte(tag)
                dos.writeLong(offset)
                // Seek back and read full node to advance raf past the record
                raf.seek(offset)
                val dis =
                    object :
                        DataInputStream(
                            object : InputStream() {
                                override fun read(): Int = raf.read()

                                override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)
                            }) {}
                readGraphNode(dis, stringTable, nodeDataVersion)
            }
        }
    }
}

internal fun readGraphNodeDataHeader(dir: Path): Pair<Int, Int> {
    return DataInputStream(BufferedInputStream(dir.resolve(GraphStore.NODE_DATA_FILE).toFile().inputStream())).use { dis ->
        val version = NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_NODEDATA)
        version to dis.readInt()
    }
}
