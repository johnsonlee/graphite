package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.GraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringPropertyTupleSet
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.nodesByStringPropertyDisjunction
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Serial mapped-view engine over the persisted `graph.callsite-string-index`: every lookup runs
 * on the requesting thread and is compared against the in-memory reference graph.
 */
class MappedCallSiteStringIndexViewTest {
    private var previousPreparationProperty: String? = null
    private val noWork = object : GraphWorkBatchConsumer {
        override fun consume(workUnits: Long) = Unit
    }

    @BeforeTest
    fun clearPreparationProperty() {
        previousPreparationProperty = System.getProperty(GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY)
        System.clearProperty(GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY)
    }

    @AfterTest
    fun restorePreparationProperty() {
        previousPreparationProperty?.let { previous ->
            System.setProperty(GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY, previous)
        } ?: System.clearProperty(GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY)
    }

    private fun callSite(id: Int, callerClass: String, callerName: String, calleeClass: String, calleeName: String) =
        CallSiteNode(
            NodeId(id),
            MethodDescriptor(TypeDescriptor(callerClass), callerName, emptyList(), TypeDescriptor("void")),
            MethodDescriptor(TypeDescriptor(calleeClass), calleeName, emptyList(), TypeDescriptor("void")),
            id,
            null,
            emptyList()
        )

    /**
     * 1,300 call sites: a dense `get` trigram in 1,300 distinct callee names, one targeted caller
     * class used twice, one non-ASCII caller class and name, and a two-letter caller name.
     */
    private fun referenceGraph(): Graph {
        val built = builtReferenceGraph()
        // Persist in id order so the mapped encounter order equals the oracle's id order.
        return object : Graph by built {
            override fun <T : Node> nodes(type: Class<T>): Sequence<T> = built.nodes(type).sortedBy { node -> node.id.value }
        }
    }

    private fun builtReferenceGraph(): Graph = DefaultGraph.Builder().apply {
        repeat(1_300) { id ->
            val callerClass = when (id) {
                7 -> "app.target.TargetedCaller"
                8 -> "app.target.TargetedCaller"
                9 -> "app.üñíçødé.Caller"
                else -> "app.pkg${id % 40}.Caller${id % 97}"
            }
            val callerName = when (id) {
                9 -> "ünicodeName"
                11 -> "AB"
                else -> "run${id % 13}"
            }
            addNode(callSite(id, callerClass, callerName, "lib.Dependency${id % 5}", "getValue$id"))
        }
    }.build()

    private fun predicate(
        property: String,
        mode: StringMatchMode,
        expected: String,
        transform: StringValueTransform? = null
    ) = StringPropertyPredicate(property, transform, mode, expected)

    private fun <T> withPersistedGraph(prepareIndex: Boolean, block: (Path, Graph, MappedWebGraphBackedGraph) -> T): T {
        val reference = referenceGraph()
        val dir = Files.createTempDirectory("webgraph-mapped-view-engine")
        try {
            GraphStore.save(reference, dir, prepareCallSiteStringIndex = prepareIndex)
            assertEquals(prepareIndex, Files.isRegularFile(dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)))
            return (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { loaded -> block(dir, reference, loaded) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Independent oracle: evaluates the disjunction over every reference node in id order. */
    private fun referenceNodes(reference: Graph, predicates: List<StringPropertyPredicate>, limit: Int): List<CallSiteNode> =
        reference.nodes(CallSiteNode::class.java)
            .sortedBy { node -> node.id.value }
            .filter { node -> predicates.any { predicate -> predicate.matchesReference(node) } }
            .take(limit)
            .toList()

    private fun StringPropertyPredicate.matchesReference(node: CallSiteNode): Boolean {
        val raw = when (property) {
            "caller_class" -> node.caller.declaringClass.className
            "caller_name" -> node.caller.name
            "callee_class" -> node.callee.declaringClass.className
            "callee_name" -> node.callee.name
            else -> return false
        }
        val actual = if (transform == StringValueTransform.LOWERCASE) raw.lowercase() else raw
        return when (mode) {
            StringMatchMode.EQUALS -> actual == expected
            StringMatchMode.STARTS_WITH -> actual.startsWith(expected)
            StringMatchMode.ENDS_WITH -> actual.endsWith(expected)
            StringMatchMode.CONTAINS -> actual.contains(expected)
        }
    }

    private fun referenceIds(reference: Graph, predicates: List<StringPropertyPredicate>, limit: Int) =
        referenceNodes(reference, predicates, limit).map { node -> node.id.value }

    private fun mappedIds(graph: MappedWebGraphBackedGraph, predicates: List<StringPropertyPredicate>, limit: Int) =
        graph.nodesByStringPropertyDisjunction(CallSiteNode::class.java, predicates, limit, noWork)
            .orEmpty().map { node -> node.id.value }.toList()

    @Test
    fun `mapped view answers every probe kind on the requesting thread like the reference graph`() =
        withPersistedGraph(prepareIndex = true) { _, reference, loaded ->
            val cases = listOf(
                "raw equals" to listOf(predicate("caller_class", StringMatchMode.EQUALS, "app.target.TargetedCaller")),
                "raw equals absent" to listOf(predicate("caller_class", StringMatchMode.EQUALS, "app.target.Missing")),
                "raw starts with" to listOf(predicate("caller_class", StringMatchMode.STARTS_WITH, "app.target.")),
                "raw starts with absent" to listOf(predicate("caller_class", StringMatchMode.STARTS_WITH, "zzz.")),
                "rare trigram contains" to listOf(predicate("caller_class", StringMatchMode.CONTAINS, "TargetedCaller")),
                "absent trigram" to listOf(predicate("callee_name", StringMatchMode.CONTAINS, "no-such-term-xq")),
                "lowercase ends with" to listOf(
                    predicate("callee_name", StringMatchMode.ENDS_WITH, "value30", StringValueTransform.LOWERCASE)
                ),
                "short scan term" to listOf(predicate("caller_name", StringMatchMode.CONTAINS, "AB")),
                "non ascii raw term" to listOf(predicate("caller_name", StringMatchMode.CONTAINS, "ünicode")),
                "non ascii lowercase term" to listOf(
                    predicate("caller_class", StringMatchMode.CONTAINS, "ÜÑÍÇØDÉ", StringValueTransform.LOWERCASE)
                ),
                "dense term" to listOf(predicate("callee_name", StringMatchMode.CONTAINS, "get")),
                "lowercase starts with" to listOf(
                    predicate("caller_class", StringMatchMode.STARTS_WITH, "app.target", StringValueTransform.LOWERCASE)
                ),
                "same property union" to listOf(
                    predicate("caller_class", StringMatchMode.CONTAINS, "TargetedCaller"),
                    predicate("caller_class", StringMatchMode.CONTAINS, "pkg3.")
                ),
                // Dense by postings (23 nodes) but absent from the first raw checkpoint: the probe
                // stops at its first checkpoint and the ordered postings answer instead.
                "late dense term" to listOf(predicate("callee_name", StringMatchMode.CONTAINS, "Value99")),
                // Dense and present early: the probe passes its first checkpoint and fills LIMIT.
                "early dense term" to listOf(predicate("callee_name", StringMatchMode.CONTAINS, "Value1")),
                "same property union reversed" to listOf(
                    predicate("caller_class", StringMatchMode.CONTAINS, "pkg3."),
                    predicate("caller_class", StringMatchMode.CONTAINS, "TargetedCaller")
                ),
                "same property overlapping union" to listOf(
                    predicate("caller_class", StringMatchMode.CONTAINS, "TargetedCaller"),
                    predicate("caller_class", StringMatchMode.CONTAINS, "target.Targeted")
                ),
                // Every trigram exists but no string equals the term once verified.
                "verified empty lowercase equals" to listOf(
                    predicate("callee_name", StringMatchMode.EQUALS, "getvalue", StringValueTransform.LOWERCASE)
                ),
                "mixed disjunction" to listOf(
                    predicate("caller_class", StringMatchMode.CONTAINS, "TargetedCaller"),
                    predicate("caller_name", StringMatchMode.EQUALS, "run3")
                )
            )
            for ((name, predicates) in cases) {
                for (limit in listOf(1, 4, 20, 300)) {
                    assertEquals(referenceIds(reference, predicates, limit), mappedIds(loaded, predicates, limit), "$name limit $limit")
                }
            }
            assertTrue(loaded.isMappedCallSiteStringIndexViewInitialized())
            assertFalse(loaded.isCallSiteStringIndexInitialized())
            assertEquals(0L, loaded.callSiteStringIndexLookupCount())
            assertEquals(0L, loaded.callSiteParallelScanCount())
            assertEquals(0, loaded.callSiteScanPeakActiveWorkers())
            assertTrue(loaded.callSiteMappedViewLookupCount() > 0L)
        }

    @Test
    fun `mapped view projections and counts match the reference graph`() =
        withPersistedGraph(prepareIndex = true) { _, reference, loaded ->
            val predicates = listOf(
                predicate("caller_class", StringMatchMode.CONTAINS, "targetedcaller", StringValueTransform.LOWERCASE),
                predicate("callee_name", StringMatchMode.EQUALS, "getValue3")
            )
            val expectedNodes = referenceNodes(reference, predicates, 500)
            val projected = loaded.projectStringPropertyDisjunction(
                CallSiteNode::class.java,
                predicates,
                listOf("callee_name", "caller_class"),
                limit = 500,
                workConsumer = noWork
            )
            assertNotNull(projected)
            assertEquals(
                expectedNodes.map { node -> listOf(node.callee.name, node.caller.declaringClass.className) },
                projected.map { row -> row.values }
            )
            val distinct = loaded.distinctStringPropertyDisjunction(
                CallSiteNode::class.java,
                predicates,
                listOf("caller_class", "callee_name"),
                limit = 500,
                workConsumer = noWork
            )
            assertNotNull(distinct)
            val expectedDistinct = expectedNodes
                .map { node -> listOf(node.caller.declaringClass.className, node.callee.name) }
                .distinct()
            assertEquals(expectedDistinct, distinct.map { row -> row.values })
            assertEquals(distinct.map { row -> row.encounterOrder }, distinct.map { row -> row.encounterOrder }.sorted())
            assertEquals(
                expectedNodes.size.toLong(),
                loaded.aggregateStringPropertyDisjunction(CallSiteNode::class.java, predicates, null, noWork)?.count
            )
            assertEquals(0, loaded.callSiteStringIndexLookupCount().toInt())
        }

    @Test
    fun `bounded projections reuse remembered rows and evict the oldest dense prefixes`() =
        withPersistedGraph(prepareIndex = true) { _, reference, loaded ->
            fun project(predicates: List<StringPropertyPredicate>, limit: Int) = loaded.projectStringPropertyDisjunction(
                CallSiteNode::class.java,
                predicates,
                listOf("callee_name", "caller_class"),
                limit,
                noWork
            )
            fun expected(predicates: List<StringPropertyPredicate>, limit: Int) =
                referenceNodes(reference, predicates, limit).map { node ->
                    listOf(node.callee.name, node.caller.declaringClass.className)
                }
            val dense = listOf(predicate("callee_name", StringMatchMode.CONTAINS, "get"))
            val first = project(dense, 4)
            assertNotNull(first)
            assertEquals(expected(dense, 4), first.map { row -> row.values })
            val lookupsAfterFirst = loaded.callSiteMappedViewLookupCount()
            // The second identical request is served from the remembered rows without a new plan.
            assertSame(first, project(dense, 4))
            assertEquals(lookupsAfterFirst + 1, loaded.callSiteMappedViewLookupCount())
            assertEquals(1, loaded.rawProjectionMatchCount())

            val absent = listOf(predicate("callee_name", StringMatchMode.CONTAINS, "no-such-term-xq"))
            assertSame(project(absent, 4), project(absent, 4))
            assertTrue(project(absent, 4).orEmpty().isEmpty())
            val late = listOf(predicate("callee_name", StringMatchMode.CONTAINS, "Value99"))
            assertEquals(expected(late, 4), project(late, 4)?.map { row -> row.values })
            // The late term exhausted its raw prefix once; that memory is not a filled prefix.
            assertEquals(1, loaded.rawProjectionMatchCount())
            assertEquals(expected(late, 4), project(late, 4)?.map { row -> row.values })

            // More distinct dense prefixes than the per-graph cache keeps: the oldest are evicted.
            repeat(18) { index ->
                val term = listOf(predicate("callee_name", StringMatchMode.CONTAINS, "Value${index + 1}"))
                assertEquals(expected(term, 1), project(term, 1)?.map { row -> row.values }, "Value${index + 1}")
            }
            assertTrue(loaded.rawProjectionMatchCount() in 1..16)
            assertEquals(expected(dense, 4), project(dense, 4)?.map { row -> row.values })
        }

    @Test
    fun `distinct dense projections probe the raw prefix and fall back to ordered postings`() =
        withPersistedGraph(prepareIndex = true) { _, reference, loaded ->
            fun distinct(predicates: List<StringPropertyPredicate>, projected: List<String>, limit: Int) =
                loaded.distinctStringPropertyDisjunction(CallSiteNode::class.java, predicates, projected, limit, workConsumer = noWork)
            val dense = listOf(predicate("callee_name", StringMatchMode.CONTAINS, "get"))
            val classes = distinct(dense, listOf("callee_class"), 3)
            assertEquals(
                listOf("lib.Dependency0", "lib.Dependency1", "lib.Dependency2").map { listOf(it) },
                classes?.map { row -> row.values }
            )
            val late = listOf(predicate("callee_name", StringMatchMode.CONTAINS, "Value99"))
            val expectedLate = referenceNodes(reference, late, 1_300)
                .map { node -> listOf(node.caller.declaringClass.className) }
                .distinct()
                .take(4)
            assertEquals(expectedLate, distinct(late, listOf("caller_class"), 4)?.map { row -> row.values })
            assertEquals(0L, loaded.callSiteStringIndexLookupCount())
            assertEquals(0L, loaded.callSiteParallelScanCount())
        }

    @Test
    fun `an interrupted request stops its raw prefix probes before touching more storage`() =
        withPersistedGraph(prepareIndex = true) { _, _, loaded ->
            val dense = listOf(predicate("callee_name", StringMatchMode.CONTAINS, "get"))
            Thread.currentThread().interrupt()
            try {
                assertFailsWith<CancellationException> {
                    loaded.projectStringPropertyDisjunction(CallSiteNode::class.java, dense, listOf("callee_name"), 4, noWork)
                }
                assertFailsWith<CancellationException> {
                    loaded.distinctStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        dense,
                        listOf("callee_class"),
                        4,
                        workConsumer = noWork
                    )
                }
            } finally {
                assertTrue(Thread.interrupted())
            }
            assertEquals(0, loaded.rawProjectionMatchCount())
            assertNotNull(loaded.projectStringPropertyDisjunction(CallSiteNode::class.java, dense, listOf("callee_name"), 4, noWork))
            Unit
        }

    @Test
    fun `selected tuple provenance rejects foreign tuples by their leading value and honours residual predicates`() =
        withPersistedGraph(prepareIndex = true) { _, _, loaded ->
            // Node 7 carries the tuple and callee `getValue7`; node 8 carries the same tuple but
            // callee `getValue8`, so the residual predicate decides which node proves the hit.
            val predicates = listOf(predicate("callee_name", StringMatchMode.EQUALS, "getValue7"))
            val present = listOf("app.target.TargetedCaller", "run7")
            val presentButUnmatched = listOf("app.pkg1.Caller1", "run1")
            val foreign = listOf("zzz.Unknown", "run7")
            val selected = StringPropertyTupleSet(listOf(present, foreign, presentButUnmatched, present))
            val unselected = loaded.distinctStringPropertyDisjunction(
                CallSiteNode::class.java,
                predicates,
                listOf("caller_class", "caller_name"),
                limit = 5,
                workConsumer = noWork
            )
            assertEquals(listOf(present), unselected?.map { row -> row.values })
            val hits = loaded.distinctStringPropertyDisjunction(
                CallSiteNode::class.java,
                predicates,
                listOf("caller_class", "caller_name"),
                limit = selected.size,
                selectedValues = selected,
                workConsumer = noWork
            )
            assertNotNull(hits)
            assertEquals(listOf(present), hits.map { row -> row.values })
            assertEquals(unselected?.single()?.encounterOrder, hits.single().encounterOrder)

            val plainCollection = loaded.distinctStringPropertyDisjunction(
                CallSiteNode::class.java,
                predicates,
                listOf("caller_class", "caller_name"),
                limit = 3,
                selectedValues = linkedSetOf(present, foreign, presentButUnmatched),
                workConsumer = noWork
            )
            assertEquals(listOf(present), plainCollection?.map { row -> row.values })

            val projectedOnly = loaded.distinctStringPropertyDisjunction(
                CallSiteNode::class.java,
                listOf(predicate("caller_class", StringMatchMode.CONTAINS, "target")),
                listOf("caller_class", "caller_name"),
                limit = 2,
                selectedValues = StringPropertyTupleSet(listOf(present, presentButUnmatched)),
                workConsumer = noWork
            )
            assertEquals(listOf(present), projectedOnly?.map { row -> row.values })

            val nullOnly = loaded.distinctStringPropertyDisjunction(
                CallSiteNode::class.java,
                listOf(predicate("caller_class", StringMatchMode.CONTAINS, "TargetedCaller")),
                listOf("graphId"),
                limit = 1,
                selectedValues = StringPropertyTupleSet(listOf(listOf(null))),
                workConsumer = noWork
            )
            assertEquals(listOf(listOf<String?>(null)), nullOnly?.map { row -> row.values })
            assertEquals(unselected?.single()?.encounterOrder, nullOnly?.single()?.encounterOrder)
            val nullOnlyAbsent = loaded.distinctStringPropertyDisjunction(
                CallSiteNode::class.java,
                listOf(predicate("caller_class", StringMatchMode.CONTAINS, "no-such-caller-xq")),
                listOf("graphId"),
                limit = 1,
                selectedValues = StringPropertyTupleSet(listOf(listOf(null))),
                workConsumer = noWork
            )
            assertEquals(emptyList(), nullOnlyAbsent)
        }

    @Test
    fun `legacy graph without a sidecar builds persists and serves it from the mapped view`() =
        withPersistedGraph(prepareIndex = false) { dir, reference, loaded ->
            val predicates = listOf(predicate("caller_class", StringMatchMode.CONTAINS, "TargetedCaller"))
            assertEquals(referenceIds(reference, predicates, 10), mappedIds(loaded, predicates, 10))
            assertTrue(Files.isRegularFile(dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)))
            assertTrue(loaded.isMappedCallSiteStringIndexViewInitialized())
            assertFalse(loaded.isCallSiteStringIndexInitialized())
            assertEquals(1L, loaded.callSiteMappedViewLookupCount())
            assertEquals(0L, loaded.callSiteParallelScanCount())

            loaded.clearStringPropertyIndexes()
            assertFalse(loaded.isMappedCallSiteStringIndexViewInitialized())
            assertEquals(referenceIds(reference, predicates, 10), mappedIds(loaded, predicates, 10))
            assertTrue(loaded.isMappedCallSiteStringIndexViewInitialized())
            assertFalse(loaded.isCallSiteStringIndexInitialized())
            assertTrue(loaded.callSiteMappedViewLookupCount() >= 2L)
        }

    @Test
    fun `disabled persistence keeps the serial raw fallback without writing a sidecar`() {
        System.setProperty(GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY, "false")
        withPersistedGraph(prepareIndex = false) { dir, reference, loaded ->
            val predicates = listOf(
                predicate("caller_class", StringMatchMode.CONTAINS, "targetedcaller", StringValueTransform.LOWERCASE),
                predicate("callee_name", StringMatchMode.CONTAINS, "no-such-term-xq")
            )
            assertEquals(referenceIds(reference, predicates, 10), mappedIds(loaded, predicates, 10))
            assertFalse(Files.isRegularFile(dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)))
            assertFalse(loaded.isMappedCallSiteStringIndexViewInitialized())
            assertEquals(0L, loaded.callSiteMappedViewLookupCount())
            assertEquals(0L, loaded.callSiteParallelScanCount())
            val distinct = loaded.distinctStringPropertyDisjunction(
                CallSiteNode::class.java,
                predicates,
                listOf("caller_class"),
                limit = 5,
                workConsumer = noWork
            )
            assertEquals(listOf(listOf("app.target.TargetedCaller")), distinct?.map { row -> row.values })
            val projected = loaded.projectStringPropertyDisjunction(
                CallSiteNode::class.java,
                predicates,
                listOf("caller_class"),
                limit = 5,
                workConsumer = noWork
            )
            assertEquals(List(2) { listOf("app.target.TargetedCaller") }, projected?.map { row -> row.values })
            assertNull(
                loaded.projectStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    predicates,
                    listOf("line"),
                    limit = 5,
                    workConsumer = noWork
                )
            )
        }
    }
}
