package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.Graph
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Immutable inputs prepared before streaming node records and their lookup indexes. */
internal class NodeWritePlan(
    val nodeCount: Int,
    val maxNodeId: Int,
    val nodeTypeCounts: IntArray,
    val callSiteCount: Int,
    val stringTable: StringTable,
    val classOverviewEdges: ClassOverviewEdgeBuilder,
    val callSiteIndexInput: CallSiteIndexPersistenceInput?,
    val callSiteOrdinals: CallSiteOrdinalPersistenceInput,
    val declaredKeys: DeclaredKeySummaryBuilder?
)

/** Streams nodes, indexes and content identities in the same pass. */
internal class NodeDataWriter(private val graph: Graph, private val dir: Path, private val plan: NodeWritePlan) {
    private val nodeCount
        get() = plan.nodeCount

    private val maxNodeId
        get() = plan.maxNodeId

    private val nodeTypeCounts
        get() = plan.nodeTypeCounts

    private val callSiteCount
        get() = plan.callSiteCount

    private val stringTable
        get() = plan.stringTable

    private val classOverviewEdges
        get() = plan.classOverviewEdges

    private val callSiteIndexInput
        get() = plan.callSiteIndexInput

    private val callSiteOrdinals
        get() = plan.callSiteOrdinals

    private val declaredKeys
        get() = plan.declaredKeys

    fun write(): ByteArray {
        val callSiteIdentity =
            MessageDigest.getInstance("SHA-256").apply {
                update(stringTable.contentIdentity())
                updateContentInt(callSiteCount)
            }
        // Hashed as it is written: the node layout identity is the SHA-256 of graph.nodedata, and
        // reading the file back for it would be the one read of a file this save had just produced.
        val nodeLayout = MessageDigest.getInstance("SHA-256")
        CountingOutputStream(BufferedOutputStream(dir.resolve(GraphStore.NODE_DATA_FILE).toFile().outputStream())).use { cos ->
            val dataDos = DataOutputStream(DigestOutputStream(cos, nodeLayout))
            DataOutputStream(BufferedOutputStream(dir.resolve(NODE_INDEX_FILE).toFile().outputStream())).use { idxDos ->
                writeRecords(callSiteIdentity, dataDos, idxDos, cos)
            }
        }
        Files.write(dir.resolve(CALL_SITE_STRING_CONTENT_IDENTITY_FILE), callSiteIdentity.digest())
        return nodeLayout.digest()
    }

    private fun writeRecords(
        callSiteIdentity: MessageDigest,
        dataDos: DataOutputStream,
        idxDos: DataOutputStream,
        cos: CountingOutputStream
    ) {
        NodeOffsetIndexWriter(dir.resolve(NODE_OFFSETS_FILE), maxNodeId + 1).use { offsetWriter ->
            TypeIndexWriter(dir.resolve(TYPE_INDEX_FILE), nodeTypeCounts).use { typeIndexWriter ->
                NodeSerializer.writeHeader(dataDos, NodeSerializer.MAGIC_NODEDATA)
                dataDos.writeInt(nodeCount)
                NodeSerializer.writeHeader(idxDos, NodeSerializer.MAGIC_NODEINDEX)
                idxDos.writeInt(nodeCount)
                writeNodes(
                    NodeDataWriteContext(
                        dataDos,
                        idxDos,
                        offsetWriter,
                        typeIndexWriter,
                        cos,
                        stringTable,
                        classOverviewEdges,
                        CallSiteWriteContext(callSiteIdentity, callSiteIndexInput, callSiteOrdinals),
                        declaredKeys))
            }
        }
    }

    private fun writeNodes(context: NodeDataWriteContext) {
        for (node in graph.nodes(Node::class.java)) {
            val offset = context.countingOutput.bytesWritten
            val tag = writeGraphNode(context.dataDos, node, context.stringTable)
            context.idxDos.writeInt(node.id.value)
            context.idxDos.writeByte(tag)
            context.idxDos.writeLong(offset)
            context.offsetWriter.write(node.id.value, offset)
            context.typeIndexWriter.write(tag, node.id.value)
            context.declaredKeys?.add(node)
            if (node is CallSiteNode) {
                context.classOverviewEdges.add(node)
                context.callSites.identity.updateContentInt(node.id.value)
                context.callSites.identity.updateContentLong(offset)
                context.callSites.identity.updateContentInt(context.stringTable.indexOf(node.caller.declaringClass.className))
                context.callSites.identity.updateContentInt(context.stringTable.indexOf(node.caller.name))
                context.callSites.identity.updateContentInt(context.stringTable.indexOf(node.callee.declaringClass.className))
                context.callSites.identity.updateContentInt(context.stringTable.indexOf(node.callee.name))
                context.callSites.indexInput?.add(node, context.stringTable)
                context.callSites.ordinalInput.add(node)
            }
        }
    }
}
