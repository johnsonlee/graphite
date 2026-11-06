package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionLookup
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class PipelineStringLookupTest : QueryPipelineTestFixture() {

    @Test
    fun `single unlabeled string filter uses typed property lookup instead of a full node scan`() {
        val lookupGraph =
            object : Graph by graph, StringPropertyDisjunctionLookup {
                override fun <T : Node> nodes(type: Class<T>): Sequence<T> =
                    error("single direct string filter unexpectedly used a full node scan")

                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> {
                    if (type != CallSiteNode::class.java) return emptySequence()
                    assertEquals(listOf("caller_class"), predicates.map { it.property })
                    assertEquals(listOf("example"), predicates.map { it.expected })
                    @Suppress("UNCHECKED_CAST")
                    return graph.nodes(CallSiteNode::class.java).take(limit) as Sequence<T>
                }
            }

        val result =
            CypherExecutor(lookupGraph)
                .execute("MATCH (n) WHERE n.caller_class CONTAINS 'example' " + "RETURN n.caller_class AS caller LIMIT 1")

        assertEquals(listOf("com.example.Service"), result.rows.map { it["caller"] })
    }

    @Test
    fun `parameterized string disjunction retains the direct lookup path`() {
        val observed = mutableListOf<StringPropertyPredicate>()
        val lookupGraph =
            object : Graph by graph, StringPropertyDisjunctionLookup {
                override fun <T : Node> nodes(type: Class<T>): Sequence<T> =
                    error("parameterized direct string filter unexpectedly used a full node scan")

                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> {
                    if (type != CallSiteNode::class.java) return emptySequence()
                    observed += predicates
                    @Suppress("UNCHECKED_CAST")
                    return graph.nodes(CallSiteNode::class.java).take(limit) as Sequence<T>
                }
            }

        val result =
            CypherExecutor(lookupGraph)
                .execute(
                    "MATCH (n) WHERE n.caller_class CONTAINS \$term OR n.callee_class CONTAINS \$term " +
                        "RETURN n.caller_class AS caller LIMIT 1",
                    mapOf("term" to "example"))

        assertEquals(listOf("com.example.Service"), result.rows.map { it["caller"] })
        assertEquals(listOf("caller_class", "callee_class"), observed.map { it.property })
        assertTrue(observed.all { it.expected == "example" })
    }

    @Test
    fun `string IN predicate expands into exact direct lookup candidates`() {
        val observed = mutableListOf<StringPropertyPredicate>()
        val lookupGraph =
            object : Graph by graph, StringPropertyDisjunctionLookup {
                override fun <T : Node> nodes(type: Class<T>): Sequence<T> = error("string IN predicate unexpectedly used a full node scan")

                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> {
                    if (type != CallSiteNode::class.java) return emptySequence()
                    observed += predicates
                    @Suppress("UNCHECKED_CAST")
                    return graph.nodes(CallSiteNode::class.java).take(1).take(limit) as Sequence<T>
                }
            }

        val result =
            CypherExecutor(lookupGraph)
                .execute("MATCH (n) WHERE n.callee_name IN ['save', 'load'] " + "RETURN n.callee_name AS callee LIMIT 2")

        assertEquals(listOf("save"), result.rows.map { it["callee"] })
        assertEquals(listOf("save", "load"), observed.map { it.expected })
        assertTrue(observed.all { it.property == "callee_name" && it.mode == StringMatchMode.EQUALS })
    }

    @Test
    fun `quoted contains regex uses direct string lookup`() {
        val observed = mutableListOf<StringPropertyPredicate>()
        val lookupGraph =
            object : Graph by graph, StringPropertyDisjunctionLookup {
                override fun <T : Node> nodes(type: Class<T>): Sequence<T> =
                    error("quoted contains regex unexpectedly used a full node scan")

                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> {
                    if (type != CallSiteNode::class.java) return emptySequence()
                    observed += predicates
                    @Suppress("UNCHECKED_CAST")
                    return graph.nodes(CallSiteNode::class.java).take(1).take(limit) as Sequence<T>
                }
            }

        val result =
            CypherExecutor(lookupGraph)
                .execute(
                    "MATCH (n) WHERE n.callee_name =~ \$pattern RETURN n.callee_name AS callee LIMIT 2", mapOf("pattern" to ".*\\Qav\\E.*"))

        assertEquals(listOf("save"), result.rows.map { it["callee"] })
        assertEquals(listOf("av"), observed.map { it.expected })
        assertTrue(observed.all { it.property == "callee_name" && it.mode == StringMatchMode.CONTAINS })
    }

    @Test
    fun `general regex is not lowered to a contains predicate`() {
        var directLookups = 0
        val lookupGraph =
            object : Graph by graph, StringPropertyDisjunctionLookup {
                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> {
                    directLookups++
                    return emptySequence()
                }
            }

        val result =
            CypherExecutor(lookupGraph).execute("MATCH (n:CallSiteNode) WHERE n.callee_name =~ 'sa.*' RETURN n.callee_name AS callee")

        assertEquals(listOf("save"), result.rows.map { it["callee"] })
        assertEquals(0, directLookups)
    }

    @Test
    fun `ordered string pagination consumes direct candidates`() {
        val lookupGraph =
            object : Graph by graph, StringPropertyDisjunctionLookup {
                override fun <T : Node> nodes(type: Class<T>): Sequence<T> =
                    error("ordered direct string query unexpectedly used a full node scan")

                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> {
                    if (type != CallSiteNode::class.java) return emptySequence()
                    assertEquals(listOf("caller_class"), predicates.map { it.property })
                    @Suppress("UNCHECKED_CAST")
                    return graph.nodes(CallSiteNode::class.java).take(limit) as Sequence<T>
                }
            }

        val result =
            CypherExecutor(lookupGraph)
                .execute(
                    "MATCH (n) WHERE n.caller_class CONTAINS 'example' " + "RETURN n.callee_name AS callee ORDER BY callee SKIP 1 LIMIT 1")

        assertEquals(listOf("save"), result.rows.map { it["callee"] })
    }

    @Test
    fun `positive string conjunct supplies candidates for a residual NOT predicate`() {
        val lookupGraph =
            object : Graph by graph, StringPropertyDisjunctionLookup {
                override fun <T : Node> nodes(type: Class<T>): Sequence<T> =
                    error("boolean direct string query unexpectedly used a full node scan")

                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> {
                    if (type != CallSiteNode::class.java) return emptySequence()
                    assertEquals(listOf("caller_class"), predicates.map { it.property })
                    @Suppress("UNCHECKED_CAST")
                    return graph.nodes(CallSiteNode::class.java).take(limit) as Sequence<T>
                }
            }

        val result =
            CypherExecutor(lookupGraph)
                .execute(
                    "MATCH (n) WHERE n.caller_class CONTAINS 'example' " +
                        "AND NOT n.callee_class CONTAINS 'Logger' " +
                        "RETURN n.callee_class AS callee LIMIT 2")

        assertEquals(listOf("com.example.Repository"), result.rows.map { it["callee"] })
    }

    @Test
    fun `AND of string disjunctions chooses the narrower candidate family`() {
        val observedProperties = mutableListOf<String>()
        val lookupGraph =
            object : Graph by graph, StringPropertyDisjunctionLookup {
                override fun <T : Node> nodes(type: Class<T>): Sequence<T> = error("AND of disjunctions unexpectedly used a full node scan")

                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> {
                    if (type != CallSiteNode::class.java) return emptySequence()
                    observedProperties += predicates.map { it.property }
                    @Suppress("UNCHECKED_CAST")
                    return graph.nodes(CallSiteNode::class.java).take(limit) as Sequence<T>
                }
            }

        val result =
            CypherExecutor(lookupGraph)
                .execute(
                    "MATCH (n) " +
                        "WHERE (n.caller_class CONTAINS 'e' OR n.callee_class CONTAINS 'e') " +
                        "AND (n.caller_name CONTAINS 'e' OR n.callee_name CONTAINS 'e') " +
                        "RETURN n.callee_name AS callee LIMIT 1")

        assertEquals(listOf("save"), result.rows.map { it["callee"] })
        assertEquals(listOf("caller_name", "callee_name"), observedProperties)
    }

    @Test
    fun `filtered string counts stream direct candidates without materializing MATCH rows`() {
        val lookupGraph =
            object : Graph by graph, StringPropertyDisjunctionLookup {
                override fun <T : Node> nodes(type: Class<T>): Sequence<T> =
                    error("filtered string count unexpectedly used a full node scan")

                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> {
                    if (type != CallSiteNode::class.java) return emptySequence()
                    @Suppress("UNCHECKED_CAST")
                    return graph.nodes(CallSiteNode::class.java).take(limit) as Sequence<T>
                }
            }
        val executor = CypherExecutor(lookupGraph)

        val count = executor.execute("MATCH (n) WHERE n.caller_class CONTAINS 'example' RETURN count(*) AS matches")
        val distinct =
            executor.execute("MATCH (n) WHERE n.caller_class CONTAINS 'example' " + "RETURN count(DISTINCT n.caller_class) AS classes")

        assertEquals(2L, count.rows.single()["matches"])
        assertEquals(1L, distinct.rows.single()["classes"])
    }

    @Test
    fun `parallel filtered counts evaluate residual predicates and distinct expressions`() {
        fun lookupGraph(): Graph =
            object : Graph by graph, StringPropertyDisjunctionLookup {
                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> {
                    if (type != CallSiteNode::class.java) return emptySequence()
                    @Suppress("UNCHECKED_CAST")
                    return graph.nodes(CallSiteNode::class.java).take(limit) as Sequence<T>
                }
            }
        val executor = CrossGraphCypherExecutor(listOf(CypherGraph("first", lookupGraph()), CypherGraph("second", lookupGraph())))

        val count =
            executor.execute("MATCH (n) WHERE n.caller_class CONTAINS 'example' AND n.line > 5 " + "RETURN count(n.caller_name) AS matches")
        val distinct =
            executor.execute(
                "MATCH (n) WHERE n.caller_class CONTAINS 'example' AND n.line > 5 " + "RETURN count(DISTINCT n.callee_name) AS methods")

        assertEquals(4L, count.rows.single()["matches"])
        assertEquals(2L, distinct.rows.single()["methods"])
    }

    @Test
    fun `parallel ordered direct string projection keeps global order and pagination`() {
        fun lookupGraph(): Graph =
            object : Graph by graph, StringPropertyDisjunctionLookup {
                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> {
                    if (type != CallSiteNode::class.java) return emptySequence()
                    @Suppress("UNCHECKED_CAST")
                    return graph.nodes(CallSiteNode::class.java).take(limit) as Sequence<T>
                }
            }
        val result =
            CrossGraphCypherExecutor(listOf(CypherGraph("first", lookupGraph()), CypherGraph("second", lookupGraph())))
                .execute(
                    "MATCH (n) WHERE n.caller_class CONTAINS 'example' " +
                        "RETURN n.callee_name AS method ORDER BY method DESC SKIP 1 LIMIT 2")

        assertEquals(listOf("save", "log"), result.rows.map { it["method"] })
    }

    @Test
    fun `two direct string conjuncts choose exact candidates and retain residual matching`() {
        val result =
            CypherExecutor(graph)
                .execute(
                    "MATCH (n) WHERE n.caller_class = 'com.example.Service' " +
                        "AND n.callee_name CONTAINS 'save' " +
                        "RETURN DISTINCT n.callee_name AS method LIMIT 10")

        assertEquals(listOf("save"), result.rows.map { it["method"] })
    }
}
