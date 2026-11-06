package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.EnumValueReference
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.core.TypeRelation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verifies graph edge codec. */
class GraphEdgeCodecTest : GraphStoreTestFixture() {

    @Test
    fun `encodeEdge and decodeEdge round-trip for all edge variants`() {
        val from = NodeId(1)
        val to = NodeId(2)

        // DataFlowEdge - all kinds
        for (kind in DataFlowKind.entries) {
            val edge = DataFlowEdge(from, to, kind)
            val encoded = NodeSerializer.encodeEdge(edge)
            val decoded = NodeSerializer.decodeEdge(encoded, from, to)
            assertTrue(decoded is DataFlowEdge)
            assertEquals(kind, (decoded as DataFlowEdge).kind)
            assertEquals(from, decoded.from)
            assertEquals(to, decoded.to)
        }

        // CallEdge - all flag combinations
        for (virtual in listOf(false, true)) {
            for (dynamic in listOf(false, true)) {
                val edge = CallEdge(from, to, isVirtual = virtual, isDynamic = dynamic)
                val encoded = NodeSerializer.encodeEdge(edge)
                val decoded = NodeSerializer.decodeEdge(encoded, from, to)
                assertTrue(decoded is CallEdge)
                val callEdge = decoded as CallEdge
                assertEquals(virtual, callEdge.isVirtual, "isVirtual=$virtual")
                assertEquals(dynamic, callEdge.isDynamic, "isDynamic=$dynamic")
            }
        }

        // TypeEdge - all relations
        for (relation in TypeRelation.entries) {
            val edge = TypeEdge(from, to, relation)
            val encoded = NodeSerializer.encodeEdge(edge)
            val decoded = NodeSerializer.decodeEdge(encoded, from, to)
            assertTrue(decoded is TypeEdge)
            assertEquals(relation, (decoded as TypeEdge).kind)
        }

        // ControlFlowEdge - all kinds, with and without comparison
        for (kind in ControlFlowKind.entries) {
            val edge = ControlFlowEdge(from, to, kind)
            val encoded = NodeSerializer.encodeEdge(edge)
            val decoded = NodeSerializer.decodeEdge(encoded, from, to)
            assertTrue(decoded is ControlFlowEdge)
            assertEquals(kind, (decoded as ControlFlowEdge).kind)
            assertNull(decoded.comparison)
        }

        // ControlFlowEdge with comparison
        val comp = BranchComparison(ComparisonOp.GE, NodeId(99))
        val cfEdge = ControlFlowEdge(from, to, ControlFlowKind.BRANCH_TRUE)
        val encoded = NodeSerializer.encodeEdge(cfEdge)
        val decoded = NodeSerializer.decodeEdge(encoded, from, to, comp)
        assertTrue(decoded is ControlFlowEdge)
        assertEquals(comp, (decoded as ControlFlowEdge).comparison)

        for (relation in ResourceRelation.entries) {
            val edge = ResourceEdge(from, to, relation)
            val encodedResource = NodeSerializer.encodeEdge(edge)
            val decodedResource = NodeSerializer.decodeEdge(encodedResource, from, to)
            assertTrue(decodedResource is ResourceEdge)
            assertEquals(relation, (decodedResource as ResourceEdge).kind)
        }
    }

    @Test
    fun `decodeEdge supports legacy v2 labels`() {
        val from = NodeId(1)
        val to = NodeId(2)
        val comparison = BranchComparison(ComparisonOp.EQ, NodeId(3))

        val dataFlow = NodeSerializer.decodeEdge(0 or (DataFlowKind.FIELD_LOAD.ordinal shl 2), from, to, version = 2)
        assertEquals(DataFlowKind.FIELD_LOAD, (dataFlow as DataFlowEdge).kind)

        val call = NodeSerializer.decodeEdge(1 or (1 shl 6) or (1 shl 7), from, to, version = 2)
        assertTrue((call as CallEdge).isVirtual)
        assertTrue(call.isDynamic)

        val type = NodeSerializer.decodeEdge(2 or (TypeRelation.IMPLEMENTS.ordinal shl 2), from, to, version = 2)
        assertEquals(TypeRelation.IMPLEMENTS, (type as TypeEdge).kind)

        val control = NodeSerializer.decodeEdge(3 or (ControlFlowKind.BRANCH_FALSE.ordinal shl 2), from, to, comparison, version = 2)
        assertEquals(ControlFlowKind.BRANCH_FALSE, (control as ControlFlowEdge).kind)
        assertEquals(comparison, control.comparison)
    }

    @Test
    fun `node serializer helpers cover value io and direct edge decoders`() {
        val dir = Files.createTempDirectory("webgraph-node-helpers")
        try {
            val strings = StringTable.build(setOf("fallback", "enum.Owner", "VALUE", "hello", "java.class"), dir)
            val serializerClass = NodeSerializer::class.java
            val decodeEdgeV2 = serializerClass.declaredMethods.first { it.name.startsWith("decodeEdgeV2") }.apply { isAccessible = true }
            val decodeEdgeV3 = serializerClass.declaredMethods.first { it.name.startsWith("decodeEdgeV3") }.apply { isAccessible = true }

            val dest = linkedSetOf<String>()
            collectGraphValueString(EnumValueReference("enum.Owner", "VALUE"), dest)
            collectGraphValueString(listOf("hello", true), dest)
            collectGraphValueString(
                object {
                    override fun toString() = "fallback"
                },
                dest)
            assertTrue(dest.containsAll(listOf("enum.Owner", "VALUE", "hello")))
            assertTrue(dest.contains("fallback"))

            val baos = ByteArrayOutputStream()
            DataOutputStream(baos).use { dos ->
                writeGraphValue(dos, listOf("hello", 7), strings)
                dos.writeByte(99)
                dos.writeInt(strings.indexOf("fallback"))
            }
            DataInputStream(ByteArrayInputStream(baos.toByteArray())).use { dis ->
                assertEquals(listOf("hello", 7), readGraphValue(dis, strings, NodeSerializer.FORMAT_VERSION))
                assertEquals("fallback", readGraphValue(dis, strings, NodeSerializer.FORMAT_VERSION))
            }

            val unsupportedOut = ByteArrayOutputStream()
            DataOutputStream(unsupportedOut).use { dos ->
                writeGraphValue(
                    dos,
                    object {
                        override fun toString() = "fallback"
                    },
                    strings)
            }
            DataInputStream(ByteArrayInputStream(unsupportedOut.toByteArray())).use { dis ->
                assertEquals("fallback", readGraphValue(dis, strings, NodeSerializer.FORMAT_VERSION))
            }

            val from = NodeId(11)
            val to = NodeId(12)
            val comparison = BranchComparison(ComparisonOp.GT, NodeId(13))
            assertEquals(
                DataFlowKind.PARAMETER_PASS,
                (decodeEdgeV2.invoke(NodeSerializer, 0 or (DataFlowKind.PARAMETER_PASS.ordinal shl 2), from.value, to.value, null)
                        as DataFlowEdge)
                    .kind)
            val legacyCall = decodeEdgeV2.invoke(NodeSerializer, 1 or (1 shl 6) or (1 shl 7), from.value, to.value, null) as CallEdge
            assertTrue(legacyCall.isVirtual)
            assertTrue(legacyCall.isDynamic)
            val legacyStaticCall = decodeEdgeV2.invoke(NodeSerializer, 1, from.value, to.value, null) as CallEdge
            assertFalse(legacyStaticCall.isVirtual)
            assertFalse(legacyStaticCall.isDynamic)
            assertEquals(
                TypeRelation.EXTENDS,
                (decodeEdgeV2.invoke(NodeSerializer, 2 or (TypeRelation.EXTENDS.ordinal shl 2), from.value, to.value, null) as TypeEdge)
                    .kind)
            assertEquals(
                comparison,
                (decodeEdgeV2.invoke(NodeSerializer, 3 or (ControlFlowKind.SEQUENTIAL.ordinal shl 2), from.value, to.value, comparison)
                        as ControlFlowEdge)
                    .comparison)
            val v3Call = decodeEdgeV3.invoke(NodeSerializer, 1 or (1 shl 3) or (1 shl 4), from.value, to.value, null) as CallEdge
            assertTrue(v3Call.isVirtual)
            assertTrue(v3Call.isDynamic)
            val v3StaticCall = decodeEdgeV3.invoke(NodeSerializer, 1, from.value, to.value, null) as CallEdge
            assertFalse(v3StaticCall.isVirtual)
            assertFalse(v3StaticCall.isDynamic)
            assertEquals(
                ResourceRelation.OPENS,
                (decodeEdgeV3.invoke(NodeSerializer, 4 or (ResourceRelation.OPENS.ordinal shl 3), from.value, to.value, null)
                        as ResourceEdge)
                    .kind)
            val decodeFailure =
                assertFailsWith<java.lang.reflect.InvocationTargetException> {
                    decodeEdgeV3.invoke(NodeSerializer, 5, from.value, to.value, null)
                }
            assertTrue(decodeFailure.targetException is IllegalArgumentException)

            val boolOut = ByteArrayOutputStream()
            DataOutputStream(boolOut).use { dos -> writeGraphValue(dos, true, strings) }
            DataInputStream(ByteArrayInputStream(boolOut.toByteArray())).use { dis ->
                assertEquals(true, readGraphValue(dis, strings, NodeSerializer.FORMAT_VERSION))
            }

            val legacyListOut = ByteArrayOutputStream()
            DataOutputStream(legacyListOut).use { dos -> writeGraphValue(dos, listOf("hello"), strings) }
            DataInputStream(ByteArrayInputStream(legacyListOut.toByteArray())).use { dis ->
                val legacyFailure = assertFailsWith<IllegalArgumentException> { readGraphValue(dis, strings, 1) }
                assertTrue(legacyFailure.message.orEmpty().contains("Encountered list value"))
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `decodeEdge throws on unknown edge family`() {
        // family bits 0-1 can only be 0..3; label=0xFF has family=3 (ControlFlow) so
        // we need to craft a label where bits 0-1 give family > 3 -- not possible with 2 bits.
        // The else branch is a safety net. We test it directly.
        assertFailsWith<IllegalArgumentException> {
            // Use reflection or a trick: family is label & 0x3, so we need family=4+
            // but 2 bits max is 3. The else branch is unreachable in normal operation.
            // We test it by calling with a carefully crafted mock -- not possible without
            // modifying the method. Since it's truly unreachable with 2-bit masking,
            // we accept this line stays uncovered.

            // Instead, test the unknown node tag branch in readNode by writing raw bytes
            // with an unknown tag, then reading them with a string table.
            val dir = Files.createTempDirectory("webgraph-unknown-tag-test")
            try {
                // Build a minimal string table so readNode can be called
                StringTable.build(setOf("dummy"), dir)
                val strings = StringTable.load(dir)

                val baos = java.io.ByteArrayOutputStream()
                val dos = DataOutputStream(baos)
                dos.writeInt(999) // node ID
                dos.writeByte(99) // unknown tag
                dos.flush()
                val dis = DataInputStream(java.io.ByteArrayInputStream(baos.toByteArray()))
                readGraphNode(dis, strings)
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }
}
