package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodMetadataScanConsumer
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.StreamingMethodLookup
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MethodQueryExecutorTest {

    @Test
    fun `parameterized Method query safely falls back to the general executor`() {
        val indexedOnly = method("com.example.Parameterized", "load", emptyList(), "void")
        val graph = DefaultGraph.Builder().apply { addMethod(indexedOnly) }.build()

        val result = CypherExecutor(graph).execute(
            "MATCH (m:Method) WHERE m.name = \$name RETURN m.signature AS signature LIMIT 1",
            mapOf("name" to "load")
        )

        assertEquals(listOf(mapOf("signature" to indexedOnly.signature)), result.rows)
    }

    @Test
    fun `Method source returns indexed methods without graph nodes`() {
        val indexedOnly = method("com.example.Child", "qux", listOf("java.lang.String"), "int")
        val graph = DefaultGraph.Builder().apply { addMethod(indexedOnly) }.build()

        val result = CypherExecutor(graph).execute(
            "MATCH (m:Method) WHERE m.class = 'com.example.Child' AND m.name STARTS WITH 'qu' " +
                "RETURN m.signature, m.class, m.name, m.parameter_types, m.return_type LIMIT 10"
        )

        assertEquals(1, result.rows.size)
        assertEquals(indexedOnly.signature, result.rows.single()["m.signature"])
        assertEquals("com.example.Child", result.rows.single()["m.class"])
        assertEquals("qux", result.rows.single()["m.name"])
        assertEquals(listOf("java.lang.String"), result.rows.single()["m.parameter_types"])
        assertEquals("int", result.rows.single()["m.return_type"])
        assertTrue(graph.nodes(Node::class.java).none())
    }

    @Test
    fun `Method metadata scans do not consume graph work budget`() {
        val methods = (0..2).map { method("com.example.Generated", "method$it", emptyList(), "void") }
        val graph = SyntheticMethodIndexGraph(methods)
        val executor = CypherExecutor(graph, CypherExecutionBudget(maxWorkUnits = 1))

        val result = executor.execute(
            "MATCH (m:Method) WHERE m.name = 'neverPresent' RETURN m.signature LIMIT 1"
        )

        assertTrue(result.rows.isEmpty())
        assertEquals(3L, graph.methodCount())
    }

    @Test
    fun `Method source pushes inline exact list suffix contains and regex filters`() {
        val indexedOnly = method("com.example.Child", "qux", listOf("java.lang.String"), "int")
        val graph = DefaultGraph.Builder().apply { addMethod(indexedOnly) }.build()
        val executor = CypherExecutor(graph)

        val exact = executor.execute(
            "MATCH (m:Method {class: 'com.example.Child'}) " +
                "WHERE 'qux' = m.name AND m.parameter_types = ['java.lang.String'] " +
                "AND m.return_type ENDS WITH 'int' RETURN m.signature SKIP 0 LIMIT 10"
        )
        val stringFilters = executor.execute(
            "MATCH (m:Method) WHERE m.name CONTAINS 'u' AND m.class =~ 'com[.]example[.].*' " +
                "RETURN m.signature LIMIT 10"
        )

        assertEquals(indexedOnly.signature, exact.rows.single()["m.signature"])
        assertEquals(indexedOnly.signature, stringFilters.rows.single()["m.signature"])
    }

    @Test
    fun `Method source falls back to methods sequence when slicing is unavailable`() {
        val indexedOnly = method("com.example.Fallback", "load", emptyList(), "void")
        val delegate = DefaultGraph.Builder().build()
        val graph = object : Graph by delegate {
            override fun methodSlice(pattern: MethodPattern, limit: Int): List<MethodDescriptor>? = null

            override fun methods(pattern: MethodPattern): Sequence<MethodDescriptor> =
                sequenceOf(indexedOnly).filter(pattern::matches)
        }

        val result = CypherExecutor(graph).execute(
            "MATCH (m:Method) WHERE m.return_type = 'void' RETURN m"
        )

        val value = result.rows.single()["m"] as Map<*, *>
        assertEquals(indexedOnly.signature, value["signature"])
    }

    @Test
    fun `Method virtual nodes expose a stable Cypher schema`() {
        val indexedOnly = method("com.example.Schema", "load", listOf("java.lang.String"), "int")
        val graph = DefaultGraph.Builder().apply { addMethod(indexedOnly) }.build()
        val executor = CypherExecutor(graph)

        val result = executor.execute(
            "MATCH (m:Method {signature: '${indexedOnly.signature}'}) " +
                "RETURN id(m) AS id, elementId(m) AS elementId, labels(m) AS labels, " +
                "keys(m) AS keys, properties(m) AS properties LIMIT 1"
        )
        val row = result.rows.single()

        assertNull(row["id"])
        assertEquals("Method:${indexedOnly.signature}", row["elementId"])
        assertEquals(listOf("Method"), row["labels"])
        assertEquals(
            listOf("signature", "class", "name", "parameter_types", "return_type"),
            row["keys"]
        )
        assertEquals(
            linkedMapOf(
                "signature" to indexedOnly.signature,
                "class" to "com.example.Schema",
                "name" to "load",
                "parameter_types" to listOf("java.lang.String"),
                "return_type" to "int"
            ),
            row["properties"]
        )
        assertTrue(executor.execute("MATCH (m:Method) RETURN m LIMIT 0").rows.isEmpty())
    }

    @Test
    fun `Method index path handles residual predicates and declines unsupported shapes`() {
        val graph = DefaultGraph.Builder().build()
        val sources = listOf(CypherGraph("default", graph))
        fun tryFastPath(cypher: String): CypherResult? = MethodQueryExecutor.tryExecute(
            CypherDslAdapter.parse(cypher),
            sources,
            qualified = false,
            checkCancelled = {},
            workTracker = null
        )

        assertNull(tryFastPath("RETURN 1"))
        assertNotNull(tryFastPath("MATCH (m:Method) WHERE true RETURN m"))
        assertNotNull(tryFastPath("MATCH (m:Method) WHERE 1 = 1 RETURN m"))
        assertNull(tryFastPath("MATCH (m:Method {name: other}) RETURN m"))
        assertNull(tryFastPath("MATCH (m:Method) RETURN count(m) AS total ORDER BY total"))
        assertNull(tryFastPath("MATCH (m:Method) RETURN m.name AS name ORDER BY m.name LIMIT 1"))
        assertNull(tryFastPath("MATCH (m:Method) RETURN m.name AS name ORDER BY name LIMIT 10001"))
        assertNull(
            MethodQueryExecutor.tryExecute(
                listOf(
                    CypherClause.Match(
                        listOf(CypherPattern(listOf(PatternElement.RelationshipPattern())))
                    ),
                    CypherClause.Return(listOf(ReturnItem(CypherExpr.Literal(1))))
                ),
                sources,
                qualified = false,
                checkCancelled = {},
                workTracker = null
            )
        )
    }

    @Test
    fun `Method filtered count returns zero without materializing matches`() {
        val graph = DefaultGraph.Builder().apply {
            addMethod(method("com.example.Alpha", "alpha", emptyList(), "void"))
        }.build()

        val result = CypherExecutor(graph).execute(
            "MATCH (m:Method) WHERE m.class = 'com.example.Missing' RETURN count(m) AS total"
        )

        assertEquals(0L, result.rows.single()["total"])
    }

    @Test
    fun `Method bounded order handles equal null and boolean keys`() {
        val graph = DefaultGraph.Builder().apply {
            addMethod(method("com.example.Alpha", "alpha", emptyList(), "void"))
            addMethod(method("com.example.Beta", "beta", listOf("int"), "void"))
        }.build()
        val executor = CypherExecutor(graph)

        val equalNulls = executor.execute(
            "MATCH (m:Method) RETURN null AS rank, m.name AS name ORDER BY rank LIMIT 2"
        )
        val booleans = executor.execute(
            "MATCH (m:Method) RETURN m.name = 'alpha' AS selected, m.name AS name " +
                "ORDER BY selected DESC LIMIT 2"
        )
        val lists = executor.execute(
            "MATCH (m:Method) RETURN m.parameter_types AS parameters ORDER BY parameters LIMIT 2"
        )

        assertEquals(setOf("alpha", "beta"), equalNulls.rows.map { it["name"] }.toSet())
        assertEquals(listOf(true, false), booleans.rows.map { it["selected"] })
        assertEquals(listOf("alpha", "beta"), booleans.rows.map { it["name"] })
        assertEquals(listOf(emptyList<String>(), listOf("int")), lists.rows.map { it["parameters"] })
    }

    @Test
    fun `Method root scan uses NCPU fork join workers and preserves graph order`() {
        assertEquals(Runtime.getRuntime().availableProcessors().coerceAtLeast(1), METHOD_GRAPH_SCAN_PARALLELISM)
        val workerCount = minOf(4, METHOD_GRAPH_SCAN_PARALLELISM)
        if (workerCount == 1) return

        val started = CountDownLatch(workerCount)
        val workers = ConcurrentHashMap.newKeySet<String>()
        val sources = (0 until workerCount).map { index ->
            val indexedMethod = method("com.example.Parallel", "method$index", emptyList(), "void")
            val graph = object : Graph by DefaultGraph.Builder().build(), StreamingMethodLookup {
                override fun methods(
                    pattern: MethodPattern,
                    scanConsumer: MethodMetadataScanConsumer
                ): Sequence<MethodDescriptor> = sequenceOf(indexedMethod).filter(pattern::matches)

                override fun methodSlice(
                    pattern: MethodPattern,
                    limit: Int,
                    scanConsumer: MethodMetadataScanConsumer
                ): List<MethodDescriptor> {
                    workers += Thread.currentThread().name
                    started.countDown()
                    check(started.await(5, TimeUnit.SECONDS)) { "Method graph scans did not run concurrently" }
                    return listOf(indexedMethod).filter(pattern::matches).take(limit)
                }

                override fun methods(pattern: MethodPattern): Sequence<MethodDescriptor> =
                    sequenceOf(indexedMethod).filter(pattern::matches)
            }
            CypherGraph("graph-$index", graph)
        }

        val result = CrossGraphCypherExecutor(sources).execute(
            "MATCH (m:Method) WHERE m.class = 'com.example.Parallel' " +
                "RETURN graphId(m) AS graph, m.name AS name LIMIT ${METHOD_GRAPH_SCAN_PARALLELISM + 1}"
        )

        assertEquals((0 until workerCount).map { "graph-$it" }, result.rows.map { it["graph"] })
        assertEquals((0 until workerCount).map { "method$it" }, result.rows.map { it["name"] })
        assertEquals(workerCount, workers.size)
    }

    @Test
    fun `cancelling a parallel Method scan stops every worker`() {
        val started = CountDownLatch(minOf(2, METHOD_GRAPH_SCAN_PARALLELISM))
        val indexedMethod = method("com.example.Cancel", "running", emptyList(), "void")
        val sources = (0 until 2).map { index ->
            val graph = object : Graph by DefaultGraph.Builder().build(), StreamingMethodLookup {
                override fun methods(pattern: MethodPattern): Sequence<MethodDescriptor> =
                    generateSequence { indexedMethod }.filter(pattern::matches)

                override fun methods(
                    pattern: MethodPattern,
                    scanConsumer: MethodMetadataScanConsumer
                ): Sequence<MethodDescriptor> = sequence {
                    started.countDown()
                    while (true) {
                        scanConsumer.inspect()
                        if (pattern.matches(indexedMethod)) yield(indexedMethod)
                    }
                }
            }
            CypherGraph("graph-$index", graph)
        }
        val signal = CypherCancellationSignal()
        val context = CypherExecutionContext(CypherExecutionBudget(maxWorkUnits = 1), signal)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val query = executor.submit<CypherResult> {
                CrossGraphCypherExecutor(sources, context).execute(
                    "MATCH (m:Method) WHERE m.name = 'missing' OR m.name = 'also-missing' " +
                        "RETURN m LIMIT ${METHOD_GRAPH_SCAN_PARALLELISM + 1}"
                )
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(signal.cancel())
            val error = assertFailsWith<ExecutionException> { query.get(5, TimeUnit.SECONDS) }
            assertTrue(error.cause is CypherQueryCancelledException)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `interrupting a parallel Method coordinator waits for its workers`() {
        if (METHOD_GRAPH_SCAN_PARALLELISM == 1) return
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val coordinator = AtomicReference<Thread>()
        val indexedMethod = method("com.example.Interrupt", "running", emptyList(), "void")
        val sources = (0 until 2).map { index ->
            val graph = object : Graph by DefaultGraph.Builder().build(), StreamingMethodLookup {
                override fun methods(
                    pattern: MethodPattern,
                    scanConsumer: MethodMetadataScanConsumer
                ): Sequence<MethodDescriptor> = sequenceOf(indexedMethod).filter(pattern::matches)

                override fun methodSlice(
                    pattern: MethodPattern,
                    limit: Int,
                    scanConsumer: MethodMetadataScanConsumer
                ): List<MethodDescriptor> {
                    started.countDown()
                    release.await()
                    return listOf(indexedMethod).filter(pattern::matches).take(limit)
                }
            }
            CypherGraph("graph-$index", graph)
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val query = executor.submit<CypherResult> {
                coordinator.set(Thread.currentThread())
                CrossGraphCypherExecutor(sources).execute(
                    "MATCH (m:Method) WHERE m.class = 'com.example.Interrupt' " +
                        "RETURN m LIMIT ${METHOD_GRAPH_SCAN_PARALLELISM + 1}"
                )
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            coordinator.get().interrupt()
            assertFailsWith<TimeoutException> { query.get(100, TimeUnit.MILLISECONDS) }
            assertFalse(query.isDone)
            coordinator.get().interrupt()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (coordinator.get().isInterrupted && System.nanoTime() < deadline) Thread.yield()
            assertFalse(coordinator.get().isInterrupted, "coordinator did not consume the second interrupt")
            release.countDown()
            val error = assertFailsWith<ExecutionException> { query.get(5, TimeUnit.SECONDS) }
            assertTrue(error.cause is CypherQueryCancelledException)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `parallel Method scan wraps a checked source failure`() {
        if (METHOD_GRAPH_SCAN_PARALLELISM == 1) return
        val goodMethod = method("com.example.Checked", "good", emptyList(), "void")
        val good = object : Graph by DefaultGraph.Builder().build(), StreamingMethodLookup {
            override fun methods(
                pattern: MethodPattern,
                scanConsumer: MethodMetadataScanConsumer
            ): Sequence<MethodDescriptor> = sequenceOf(goodMethod).filter(pattern::matches)
        }
        val failed = object : Graph by DefaultGraph.Builder().build(), StreamingMethodLookup {
            override fun methods(
                pattern: MethodPattern,
                scanConsumer: MethodMetadataScanConsumer
            ): Sequence<MethodDescriptor> = sequence {
                throw CheckedMethodSourceFailure()
            }
        }

        val error = assertFailsWith<IllegalStateException> {
            CrossGraphCypherExecutor(listOf(CypherGraph("good", good), CypherGraph("failed", failed))).execute(
                "MATCH (m:Method) WHERE m.class = 'missing' " +
                    "RETURN m LIMIT ${METHOD_GRAPH_SCAN_PARALLELISM + 1}"
            )
        }

        assertEquals("Parallel Method scan failed", error.message)
        assertEquals("checked Method source", error.cause?.message)
    }

    @Test
    fun `parallel Method scan propagates a fatal source error`() {
        if (METHOD_GRAPH_SCAN_PARALLELISM == 1) return
        val goodMethod = method("com.example.Fatal", "good", emptyList(), "void")
        val good = object : Graph by DefaultGraph.Builder().build(), StreamingMethodLookup {
            override fun methods(
                pattern: MethodPattern,
                scanConsumer: MethodMetadataScanConsumer
            ): Sequence<MethodDescriptor> = sequenceOf(goodMethod).filter(pattern::matches)
        }
        val failed = object : Graph by DefaultGraph.Builder().build(), StreamingMethodLookup {
            override fun methods(
                pattern: MethodPattern,
                scanConsumer: MethodMetadataScanConsumer
            ): Sequence<MethodDescriptor> = sequence {
                throw AssertionError("fatal Method source")
            }
        }

        val error = assertFailsWith<AssertionError> {
            CrossGraphCypherExecutor(listOf(CypherGraph("good", good), CypherGraph("failed", failed))).execute(
                "MATCH (m:Method) WHERE m.class = 'missing' " +
                    "RETURN m LIMIT ${METHOD_GRAPH_SCAN_PARALLELISM + 1}"
            )
        }

        assertEquals("fatal Method source", error.message)
    }

    @Test
    fun `parallel Method scan propagates a source failure after workers finish`() {
        if (METHOD_GRAPH_SCAN_PARALLELISM == 1) return
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val goodMethod = method("com.example.Parallel", "good", emptyList(), "void")
        val good = object : Graph by DefaultGraph.Builder().build(), StreamingMethodLookup {
            override fun methods(
                pattern: MethodPattern,
                scanConsumer: MethodMetadataScanConsumer
            ): Sequence<MethodDescriptor> = sequenceOf(goodMethod).filter(pattern::matches)

            override fun methodSlice(
                pattern: MethodPattern,
                limit: Int,
                scanConsumer: MethodMetadataScanConsumer
            ): List<MethodDescriptor> {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "failed Method source did not run" }
                return listOf(goodMethod).also { finished.countDown() }.filter(pattern::matches).take(limit)
            }
        }
        val failed = object : Graph by DefaultGraph.Builder().build(), StreamingMethodLookup {
            override fun methods(
                pattern: MethodPattern,
                scanConsumer: MethodMetadataScanConsumer
            ): Sequence<MethodDescriptor> = emptySequence()

            override fun methodSlice(
                pattern: MethodPattern,
                limit: Int,
                scanConsumer: MethodMetadataScanConsumer
            ): List<MethodDescriptor> {
                check(started.await(5, TimeUnit.SECONDS)) { "good Method source did not start" }
                release.countDown()
                error("failed Method source")
            }
        }

        val error = assertFailsWith<IllegalStateException> {
            CrossGraphCypherExecutor(listOf(CypherGraph("good", good), CypherGraph("failed", failed))).execute(
                "MATCH (m:Method) WHERE m.class = 'com.example.Parallel' " +
                    "RETURN m LIMIT ${METHOD_GRAPH_SCAN_PARALLELISM + 1}"
            )
        }

        assertEquals("failed Method source", error.message)
        assertTrue(finished.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `small Method limit scans graph sources on demand`() {
        val sliceCalls = AtomicInteger()
        val sources = (0 until 8).map { index ->
            val indexedMethod = method("com.example.OnDemand", "method$index", emptyList(), "void")
            val graph = object : Graph by DefaultGraph.Builder().build() {
                override fun methodSlice(pattern: MethodPattern, limit: Int): List<MethodDescriptor> {
                    sliceCalls.incrementAndGet()
                    return listOf(indexedMethod).filter(pattern::matches).take(limit)
                }
            }
            CypherGraph("graph-$index", graph)
        }

        val result = CrossGraphCypherExecutor(sources).execute(
            "MATCH (m:Method) WHERE m.class = 'com.example.OnDemand' " +
                "RETURN graphId(m) AS graph, m.name AS name LIMIT 1"
        )

        assertEquals(listOf("graph-0"), result.rows.map { it["graph"] })
        assertEquals(1, sliceCalls.get())
    }

    @Test
    fun `large Method result limits scan graph sources serially`() {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val sources = (0 until 4).map { index ->
            val indexedMethod = method("com.example.Large", "method$index", emptyList(), "void")
            val graph = object : Graph by DefaultGraph.Builder().build() {
                override fun methodSlice(pattern: MethodPattern, limit: Int): List<MethodDescriptor> {
                    val current = active.incrementAndGet()
                    peak.updateAndGet { previous -> maxOf(previous, current) }
                    return try {
                        listOf(indexedMethod).filter(pattern::matches).take(limit)
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }
            CypherGraph("graph-$index", graph)
        }

        val result = CrossGraphCypherExecutor(sources).execute(
            "MATCH (m:Method) WHERE m.class = 'com.example.Large' " +
                "RETURN graphId(m) AS graph LIMIT 5000"
        )

        assertEquals((0 until 4).map { "graph-$it" }, result.rows.map { it["graph"] })
        assertEquals(1, peak.get())
    }

    @Test
    fun `large source-bounded Method counts scan graph sources serially`() {
        val caller = Thread.currentThread().name
        val workers = ConcurrentHashMap.newKeySet<String>()
        val sources = (0 until 4).map { index ->
            val indexedMethod = method("com.example.Count", "method$index", emptyList(), "void")
            val graph = object : Graph by DefaultGraph.Builder().build() {
                override fun methods(pattern: MethodPattern): Sequence<MethodDescriptor> = sequence {
                    workers += Thread.currentThread().name
                    if (pattern.matches(indexedMethod)) yield(indexedMethod)
                }
            }
            CypherGraph("graph-$index", graph)
        }

        val result = CrossGraphCypherExecutor(sources).execute(
            "MATCH (m:Method) WHERE m.class = 'com.example.Count' " +
                "RETURN graphId(m) AS graph, count(m) AS total LIMIT 5000"
        )

        assertEquals((0 until 4).map { "graph-$it" }, result.rows.map { it["graph"] })
        assertTrue(result.rows.all { it["total"] == 1L })
        assertEquals(setOf(caller), workers)
    }

    @Test
    fun `Method distinct limit stops after the first distinct row`() {
        val inspected = AtomicInteger()
        val methods = (0 until 36_000).map { index ->
            method("com.example.Owner$index", "method$index", emptyList(), "void")
        }
        val graph = object : Graph by SyntheticMethodIndexGraph(methods), StreamingMethodLookup {
            override fun methods(
                pattern: MethodPattern,
                scanConsumer: MethodMetadataScanConsumer
            ): Sequence<MethodDescriptor> = methods.asSequence()
                .onEach {
                    inspected.incrementAndGet()
                    scanConsumer.inspect()
                }
                .filter(pattern::matches)
        }

        val result = CypherExecutor(graph, CypherExecutionBudget(maxWorkUnits = 1)).execute(
            "MATCH (m:Method) RETURN DISTINCT m.class AS owner LIMIT 1"
        )

        assertEquals(listOf("com.example.Owner0"), result.rows.map { it["owner"] })
        assertEquals(1, inspected.get())
    }

    @Test
    fun `qualified Method distinct limit merges provenance from every source`() {
        val indexedMethod = method("com.example.Shared", "shared", emptyList(), "void")
        val sources = listOf("first", "second").map { graphId ->
            CypherGraph(graphId, DefaultGraph.Builder().apply { addMethod(indexedMethod) }.build())
        }

        val result = CrossGraphCypherExecutor(sources).execute(
            "MATCH (m:Method) RETURN DISTINCT m.name AS name LIMIT 1"
        )

        assertEquals(listOf("shared"), result.rows.map { it["name"] })
        val metadata = result.rows.single()[RESULT_METADATA_KEY] as Map<*, *>
        assertEquals(listOf("first", "second"), metadata[RESULT_GRAPH_IDS_KEY])
    }

    @Test
    fun `failing Method source cancels a running peer scan`() {
        if (METHOD_GRAPH_SCAN_PARALLELISM == 1) return
        val peerStarted = CountDownLatch(1)
        val peerStopped = CountDownLatch(1)
        val indexedMethod = method("com.example.Peer", "running", emptyList(), "void")
        val failed = object : Graph by DefaultGraph.Builder().build(), StreamingMethodLookup {
            override fun methods(
                pattern: MethodPattern,
                scanConsumer: MethodMetadataScanConsumer
            ): Sequence<MethodDescriptor> = emptySequence()

            override fun methodSlice(
                pattern: MethodPattern,
                limit: Int,
                scanConsumer: MethodMetadataScanConsumer
            ): List<MethodDescriptor> {
                check(peerStarted.await(5, TimeUnit.SECONDS))
                error("failed Method source")
            }
        }
        val running = object : Graph by DefaultGraph.Builder().build(), StreamingMethodLookup {
            override fun methods(
                pattern: MethodPattern,
                scanConsumer: MethodMetadataScanConsumer
            ): Sequence<MethodDescriptor> = sequence {
                peerStarted.countDown()
                try {
                    while (true) {
                        scanConsumer.inspect()
                        if (pattern.matches(indexedMethod)) yield(indexedMethod)
                    }
                } finally {
                    peerStopped.countDown()
                }
            }
        }

        val executor = Executors.newSingleThreadExecutor()
        try {
            val query = executor.submit<CypherResult> {
                CrossGraphCypherExecutor(
                    listOf(CypherGraph("failed", failed), CypherGraph("running", running))
                ).execute(
                    "MATCH (m:Method) WHERE m.class = 'missing' " +
                        "RETURN m LIMIT ${METHOD_GRAPH_SCAN_PARALLELISM + 1}"
                )
            }
            val error = assertFailsWith<ExecutionException> { query.get(5, TimeUnit.SECONDS) }
            assertEquals("failed Method source", error.cause?.message)
            assertTrue(peerStopped.await(5, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `Method OR count and order stay streaming across 36 graphs`() {
        val graphCount = 36
        val methodsPerGraph = 1_000
        val sources = (0 until graphCount).map { graphIndex ->
            val methods = (0 until methodsPerGraph).map { methodIndex ->
                method("com.example.Generated", "method$methodIndex", emptyList(), "void")
            }
            CypherGraph("graph-$graphIndex", SyntheticMethodIndexGraph(methods))
        }
        val executor = CrossGraphCypherExecutor(sources, CypherExecutionBudget(maxWorkUnits = 1))

        val alternatives = executor.execute(
            "MATCH (m:Method) WHERE m.class = 'com.example.Generated' AND " +
                "(m.name = 'method0' OR m.name = 'method999') " +
                "RETURN graphId(m) AS graph, m.name AS name LIMIT 5000"
        )
        val counts = executor.execute(
            "MATCH (m:Method) WHERE m.class = 'com.example.Generated' " +
                "RETURN graphId(m) AS graph, count(m) AS total LIMIT 5000"
        )
        val ordered = executor.execute(
            "MATCH (m:Method) WHERE m.class = 'com.example.Generated' " +
                "RETURN graphId(m) AS graph, m.signature AS signature " +
                "ORDER BY graph, signature DESC LIMIT 36"
        )

        assertEquals(graphCount * 2, alternatives.rows.size)
        assertEquals(setOf("method0", "method999"), alternatives.rows.map { it["name"] }.toSet())
        assertEquals(graphCount, counts.rows.size)
        assertTrue(counts.rows.all { it["total"] == methodsPerGraph.toLong() })
        assertEquals((0 until graphCount).map { "graph-$it" }, counts.rows.map { it["graph"] })
        assertEquals(36, ordered.rows.size)
        assertTrue(ordered.rows.all { it["graph"] == "graph-0" })
        assertEquals(
            ordered.rows.map { it["signature"] as String }.sortedDescending(),
            ordered.rows.map { it["signature"] }
        )
    }

    @Test
    fun `bounded grouped Method count retains only requested keys across sources`() {
        val methodsPerGraph = 100_000
        val sources = (0 until 8).map { graphIndex ->
            val graph = object : Graph by DefaultGraph.Builder().build() {
                override fun methods(pattern: MethodPattern): Sequence<MethodDescriptor> =
                    (0 until methodsPerGraph).asSequence()
                        .map { index -> method("com.example.Generated", "method$index", emptyList(), "void") }
                        .filter(pattern::matches)
            }
            CypherGraph("graph-$graphIndex", graph)
        }

        val result = CrossGraphCypherExecutor(sources).execute(
            "MATCH (m:Method) RETURN m.name AS name, count(*) AS count LIMIT 1"
        )

        assertEquals(1, result.rows.size)
        assertEquals("method0", result.rows.single()["name"])
        assertEquals(8L, result.rows.single()["count"])
    }

    @Test
    fun `literal Method count group stays source bounded`() {
        val indexedMethod = method("com.example.Group", "grouped", emptyList(), "void")
        val sources = listOf("first", "second").map { graphId ->
            CypherGraph(graphId, DefaultGraph.Builder().apply { addMethod(indexedMethod) }.build())
        }

        val result = CrossGraphCypherExecutor(sources).execute(
            "MATCH (m:Method) RETURN 'all' AS bucket, count(*) AS count LIMIT 1"
        )

        assertEquals("all", result.rows.single()["bucket"])
        assertEquals(2L, result.rows.single()["count"])
    }

    @Test
    fun `Method source preserves general Cypher semantics outside the indexed fast path`() {
        val alpha = method("com.example.Alpha", "alpha", listOf("java.lang.String"), "void")
        val beta = method("com.example.Beta", "beta", listOf("int", "long"), "int")
        val graph = DefaultGraph.Builder().apply {
            addMethod(alpha)
            addMethod(beta)
            addNode(io.johnsonlee.graphite.core.StringConstant(io.johnsonlee.graphite.core.NodeId(1), "beta"))
        }.build()
        val executor = CypherExecutor(graph)

        val alternatives = executor.execute(
            "MATCH (m:Method) WHERE m.name = 'alpha' OR m.name = 'beta' " +
                "RETURN DISTINCT m.name AS name ORDER BY name DESC SKIP 0 LIMIT 2"
        )
        val aggregate = executor.execute("MATCH (m:Method) RETURN count(m) AS total")
        val unwind = executor.execute(
            "MATCH (m:Method) WITH m WHERE m.name = 'beta' " +
                "UNWIND m.parameter_types AS parameter RETURN parameter ORDER BY parameter"
        )
        val joined = executor.execute(
            "MATCH (m:Method), (n:StringConstant) WHERE m.name = n.value RETURN m.signature AS signature"
        )

        assertEquals(listOf("beta", "alpha"), alternatives.rows.map { it["name"] })
        assertEquals(2L, aggregate.rows.single()["total"])
        assertEquals(listOf("int", "long"), unwind.rows.map { it["parameter"] })
        assertEquals(listOf(beta.signature), joined.rows.map { it["signature"] })
        assertTrue(executor.execute("MATCH (m:Method) WHERE m.unknown = 'x' RETURN m").rows.isEmpty())
    }

    private fun method(owner: String, name: String, parameters: List<String>, returnType: String) =
        MethodDescriptor(
            TypeDescriptor(owner),
            name,
            parameters.map(::TypeDescriptor),
            TypeDescriptor(returnType)
        )

    private class SyntheticMethodIndexGraph(
        private val indexedMethods: List<MethodDescriptor>
    ) : Graph by DefaultGraph.Builder().build(), StreamingMethodLookup {
        override fun methodCount(): Long = indexedMethods.size.toLong()

        override fun methods(pattern: MethodPattern): Sequence<MethodDescriptor> =
            indexedMethods.asSequence().filter(pattern::matches)

        override fun methods(
            pattern: MethodPattern,
            scanConsumer: MethodMetadataScanConsumer
        ): Sequence<MethodDescriptor> = indexedMethods.asSequence()
            .onEach { scanConsumer.inspect() }
            .filter(pattern::matches)

        override fun <T : Node> nodes(type: Class<T>): Sequence<T> =
            error("Method metadata queries must not scan graph nodes")
    }

    private class CheckedMethodSourceFailure : Throwable("checked Method source")
}
