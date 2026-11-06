package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies branch scope data. */
class BranchScopeDataTest : GraphStoreTestFixture() {

    @Test
    fun `BranchScopeData equals identity`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val comp = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val data = BranchScopeData(0, method, comp, intArrayOf(1), intArrayOf(2))

        // Identity
        assertTrue(data == data)
    }

    @Test
    fun `BranchScopeData equals with different type returns false`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val comp = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val data = BranchScopeData(0, method, comp, intArrayOf(1), intArrayOf(2))

        assertTrue(data.equals("not a BranchScopeData").not())
    }

    @Test
    fun `BranchScopeData equals with equal values`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val comp = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val data1 = BranchScopeData(0, method, comp, intArrayOf(1, 2), intArrayOf(3))
        val data2 = BranchScopeData(0, method, comp, intArrayOf(1, 2), intArrayOf(3))

        assertEquals(data1, data2)
        assertEquals(data1.hashCode(), data2.hashCode())
    }

    @Test
    fun `BranchScopeData not equal when conditionNodeId differs`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val comp = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val data1 = BranchScopeData(0, method, comp, intArrayOf(1), intArrayOf(2))
        val data2 = BranchScopeData(1, method, comp, intArrayOf(1), intArrayOf(2))

        assertTrue(data1 != data2)
    }

    @Test
    fun `BranchScopeData not equal when method differs`() {
        val method1 = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val method2 = MethodDescriptor(TypeDescriptor("com.example.B"), "m", emptyList(), TypeDescriptor("void"))
        val comp = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val data1 = BranchScopeData(0, method1, comp, intArrayOf(1), intArrayOf(2))
        val data2 = BranchScopeData(0, method2, comp, intArrayOf(1), intArrayOf(2))

        assertTrue(data1 != data2)
    }

    @Test
    fun `BranchScopeData not equal when comparison differs`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val comp1 = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val comp2 = BranchComparison(ComparisonOp.NE, NodeId(1))
        val data1 = BranchScopeData(0, method, comp1, intArrayOf(1), intArrayOf(2))
        val data2 = BranchScopeData(0, method, comp2, intArrayOf(1), intArrayOf(2))

        assertTrue(data1 != data2)
    }

    @Test
    fun `BranchScopeData not equal when trueBranchNodeIds differ`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val comp = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val data1 = BranchScopeData(0, method, comp, intArrayOf(1), intArrayOf(2))
        val data2 = BranchScopeData(0, method, comp, intArrayOf(9), intArrayOf(2))

        assertTrue(data1 != data2)
    }

    @Test
    fun `BranchScopeData not equal when falseBranchNodeIds differ`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val comp = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val data1 = BranchScopeData(0, method, comp, intArrayOf(1), intArrayOf(2))
        val data2 = BranchScopeData(0, method, comp, intArrayOf(1), intArrayOf(3))

        assertTrue(data1 != data2)
    }

    @Test
    fun `BranchScopeData hashCode is consistent`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val comp = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val data = BranchScopeData(0, method, comp, intArrayOf(1, 2), intArrayOf(3, 4))

        // hashCode should be consistent across calls
        assertEquals(data.hashCode(), data.hashCode())
    }
}
