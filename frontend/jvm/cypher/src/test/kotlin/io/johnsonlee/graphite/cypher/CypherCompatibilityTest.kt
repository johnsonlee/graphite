package io.johnsonlee.graphite.cypher

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class CypherCompatibilityTest : CypherCompatibilityTestFixture() {

    @Test
    fun `MATCH - untyped node returns all nodes`() {
        val result = executor.execute("MATCH (n) RETURN count(*)")
        assertEquals(1, result.rows.size)
        assertEquals(16L, result.rows[0]["count(*)"], "Graph has 16 nodes total")
    }

    @Test
    fun `MATCH - typed node`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN n.value")
        assertEquals(listOf("n.value"), result.columns)
        assertEquals(2, result.rows.size)
        val values = result.rows.map { it["n.value"] }.toSet()
        assertEquals(setOf(42, 7), values)
    }

    @Test
    fun `MATCH - property constraint`() {
        val result = executor.execute("MATCH (n:IntConstant {value: 42}) RETURN n.id")
        assertEquals(1, result.rows.size)
        assertEquals(intConst42.value, result.rows[0]["n.id"])
    }

    @Test
    fun `MATCH - basic relationship outgoing`() {
        val result = executor.execute("MATCH (a)-[r]->(b) RETURN a, r, b")
        assertEquals(3, result.columns.size)
        // 8 edges in the graph, each produces a row
        assertEquals(8, result.rows.size)
    }

    @Test
    fun `MATCH - typed relationship`() {
        val result = executor.execute("MATCH (a)-[r:DATAFLOW]->(b) RETURN a, b")
        assertEquals(2, result.columns.size)
        // 7 DATAFLOW edges in graph
        assertEquals(7, result.rows.size)
    }

    @Test
    fun `MATCH - reverse direction`() {
        // param1 <-[DATAFLOW]- intConst42
        val result = executor.execute("MATCH (a:ParameterNode)<-[r:DATAFLOW]-(b:IntConstant) RETURN b.value")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["b.value"])
    }

    @Test
    fun `MATCH - undirected relationship`() {
        // Undirected matches both directions
        val result = executor.execute("MATCH (a:IntConstant {value: 42})-[r:DATAFLOW]-(b) RETURN b.id")
        // intConst42 has outgoing DATAFLOW to param1, so undirected finds it
        assertTrue(result.rows.isNotEmpty(), "Undirected match should find connected nodes")
        val targetIds = result.rows.map { it["b.id"] }
        assertTrue(targetIds.contains(param1.value), "Should find param1 via undirected traversal")
    }

    @Test
    fun `MATCH - variable length unbounded`() {
        // intConst42 -> param1 -> localVar1 -> localVar2 -> callSite1
        val result = executor.execute("MATCH (a:IntConstant {value: 42})-[:DATAFLOW*]->(b:CallSiteNode) RETURN b.callee_name")
        assertTrue(result.rows.isNotEmpty(), "Variable-length path should reach CallSiteNode")
        val names = result.rows.map { it["b.callee_name"] }
        assertTrue(names.contains("save"), "Should reach callSite1 (save) via multi-hop path")
    }

    @Test
    fun `MATCH - variable length with bounds`() {
        // intConst42 -> param1 (1 hop), intConst42 -> param1 -> localVar1 (2 hops)
        val result = executor.execute("MATCH (a:IntConstant {value: 42})-[:DATAFLOW*1..2]->(b) RETURN b.id")
        assertTrue(result.rows.isNotEmpty(), "Should find nodes within 1-2 hops")
        val targetIds = result.rows.map { it["b.id"] }
        assertTrue(targetIds.contains(param1.value), "Should find param1 at 1 hop")
        assertTrue(targetIds.contains(localVar1.value), "Should find localVar1 at 2 hops")
    }

    @Test
    fun `MATCH - exact hops`() {
        // Exactly 2 hops from intConst42: intConst42 -> param1 -> localVar1
        val result = executor.execute("MATCH (a:IntConstant {value: 42})-[:DATAFLOW*2]->(b) RETURN b.id")
        assertTrue(result.rows.isNotEmpty(), "Should find nodes at exactly 2 hops")
        val targetIds = result.rows.map { it["b.id"] }
        assertTrue(targetIds.contains(localVar1.value), "localVar1 is exactly 2 hops away")
        // param1 is 1 hop - should NOT be in results
        assertTrue(!targetIds.contains(param1.value), "param1 is only 1 hop away, should not match exact 2")
    }

    @Test
    fun `MATCH - path variable`() {
        val result = executor.execute("MATCH p = (a:IntConstant {value: 42})-[]->(b:ParameterNode) RETURN p")
        assertEquals(listOf("p"), result.columns)
        assertEquals(1, result.rows.size)
        val path = result.rows[0]["p"]
        assertNotNull(path, "Path variable should be bound")
        assertTrue(path is List<*>, "Path should be a list of alternating nodes and edges")
    }

    @Test
    fun `MATCH - multiple patterns (cartesian product)`() {
        val result = executor.execute("MATCH (a:IntConstant), (b:StringConstant) RETURN a.value, b.value")
        assertEquals(2, result.columns.size)
        // 2 IntConstants x 1 StringConstant = 2 rows
        assertEquals(2, result.rows.size)
        val combos = result.rows.map { (it["a.value"] as Number).toInt() to it["b.value"] }.toSet()
        assertEquals(setOf(42 to "hello", 7 to "hello"), combos)
    }

    @Test
    fun `OPTIONAL MATCH - no match produces nulls for unbound variables only`() {
        // IntConstant never connects to StringConstant via DATAFLOW directly.
        // Correct Cypher semantics: n should remain bound from the first MATCH,
        // only m should be null.
        val result =
            executor.execute("MATCH (n:IntConstant {value: 42}) OPTIONAL MATCH (n)-[:DATAFLOW]->(m:StringConstant) RETURN n.value, m")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["n.value"])
        assertNull(result.rows[0]["m"], "No DATAFLOW from IntConstant(42) to StringConstant, m should be null")
    }

    @Test
    fun `OPTIONAL MATCH - standalone with no match fills nulls`() {
        val result = executor.execute("OPTIONAL MATCH (m:IntConstant {value: 99999}) RETURN m")
        assertEquals(1, result.rows.size)
        assertNull(result.rows[0]["m"])
    }

    @Test
    fun `OPTIONAL MATCH - with match produces values`() {
        // intConst42 -> param1 via DATAFLOW
        val result =
            executor.execute("MATCH (n:IntConstant {value: 42}) OPTIONAL MATCH (n)-[:DATAFLOW]->(m:ParameterNode) RETURN n.value, m.index")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["n.value"])
        assertEquals(0, result.rows[0]["m.index"])
    }

    @Test
    fun `OPTIONAL MATCH - WHERE rejection preserves the input row and null fills new variables`() {
        val result =
            executor.execute(
                "MATCH (n:IntConstant {value: 42}) " +
                    "OPTIONAL MATCH (n)-[:DATAFLOW]->(m:ParameterNode) WHERE m.index = 999 " +
                    "RETURN n.value, m")

        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows.single()["n.value"])
        assertNull(result.rows.single()["m"])
    }
}
