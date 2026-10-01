package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ConstantNode
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.FloatConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.core.TypeRelation
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.incoming
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.graph.outgoing
import io.johnsonlee.graphite.input.LoaderConfig
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Blocking semantic gate for the JVM frontend.
 *
 * Each input is a small program whose source is reviewed as part of this repository. Expected
 * semantics are independently written in `frontend-correctness/expectations.tsv`: they are not a
 * snapshot emitted by SootUp or Graphite. Facts deliberately omit node IDs, local names and Jimple
 * statement order, so a frontend upgrade may change representation without changing meaning.
 */
class FrontendCorrectnessGateTest {

    @Test
    fun `Java Kotlin and Android frontends satisfy the reviewed semantic contract`() {
        val expectations = expectations()
        val knownDeviations = knownDeviations()
        CoveragePolicy.verify(expectations, coverage(), schemaCoverage(), knownDeviations)

        fixtures().forEach { fixture ->
            val facts = SemanticFacts.capture(load(fixture), fixture.packageName)
            val failures = SemanticOracle.verify(expectations.filter { it.fixture == fixture.id }, facts)
            val allowed = knownDeviations.filter { it.key.fixture == fixture.id }
            val actualKeys = failures.mapTo(mutableSetOf()) { it.expectation.key }
            val allowedKeys = allowed.mapTo(mutableSetOf()) { it.key }
            val unexpectedFailures = failures.filter { it.expectation.key !in allowedKeys }
            val unexpectedPasses = allowed.filter { it.key !in actualKeys }
            assertTrue(
                unexpectedFailures.isEmpty() && unexpectedPasses.isEmpty(),
                buildString {
                    appendLine("${fixture.id} frontend semantic contract failed:")
                    unexpectedFailures.forEach { appendLine("- ${it.message}") }
                    unexpectedPasses.forEach {
                        appendLine("- known deviation unexpectedly passed; remove it from known-deviations.tsv: ${it.key}")
                    }
                    appendLine("Captured ${facts.size} stable facts:")
                    facts.forEach { appendLine("  $it") }
                }
            )
        }
    }

    @Test
    fun `oracle rejects inverted branch membership`() {
        val expected = "branch-call|owner.branch(boolean)|EQ|int:0|true|owner.sink()|string:expected"
        val inverted = "branch-call|owner.branch(boolean)|EQ|int:0|true|owner.sink()|string:inverted"
        val failures = SemanticOracle.verify(
            listOf(
                Expectation("java", "branch-direction", Verdict.REQUIRE, expected),
                Expectation("java", "branch-direction", Verdict.FORBID, inverted)
            ),
            setOf(inverted)
        )

        assertEquals(2, failures.size)
        assertTrue(failures.any { "required fact is missing" in it.message })
        assertTrue(failures.any { "forbidden fact is present" in it.message })
    }

    @Test
    fun `oracle rejects a dropped call argument`() {
        val requiredArgument = "call-arg|owner.call()|owner.sink(java.lang.String,int)|1|int:7"
        val failures = SemanticOracle.verify(
            listOf(Expectation("android", "static-arguments", Verdict.REQUIRE, requiredArgument)),
            emptySet()
        )

        assertEquals(listOf("static-arguments: required fact is missing: $requiredArgument"), failures.map { it.message })
    }

    private fun fixtures(): List<Fixture> {
        val androidSdk = minimalAndroidSdk()
        return listOf(
            Fixture("java", requiredPath("frontend.correctness.java"), "fixture.frontend.java"),
            Fixture("kotlin", requiredPath("frontend.correctness.kotlin"), "fixture.frontend.kotlin"),
            Fixture("android", requiredPath("frontend.correctness.android"), "fixture.frontend.android", androidSdk)
        )
    }

    private fun load(fixture: Fixture): Graph = JavaProjectLoader(
        LoaderConfig(
            includePackages = listOf(fixture.packageName),
            includeLibraries = false,
            buildCallGraph = true,
            androidSdk = fixture.androidSdk
        )
    ).load(fixture.input)

    private fun expectations(): List<Expectation> {
        val resource = checkNotNull(javaClass.getResourceAsStream("/frontend-correctness/expectations.tsv"))
        val seen = mutableSetOf<String>()
        return resource.bufferedReader().useLines { lines ->
            lines.mapIndexedNotNull { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith('#')) return@mapIndexedNotNull null
                val columns = line.split('\t', limit = 4)
                require(columns.size == 4) { "expectations.tsv:${index + 1}: expected fixture, case, verdict and fact" }
                val expectation = Expectation(columns[0], columns[1], Verdict.parse(columns[2]), columns[3])
                require(seen.add(line)) { "expectations.tsv:${index + 1}: duplicate expectation" }
                expectation
            }.toList()
        }
    }

    private fun coverage(): List<CoverageEntry> {
        val resource = checkNotNull(javaClass.getResourceAsStream("/frontend-correctness/coverage.tsv"))
        val seen = mutableSetOf<Pair<String, String>>()
        return resource.bufferedReader().useLines { lines ->
            lines.mapIndexedNotNull { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith('#')) return@mapIndexedNotNull null
                val columns = line.split('\t', limit = 4)
                require(columns.size == 4) { "coverage.tsv:${index + 1}: expected fixture, family, case and risk" }
                val entry = CoverageEntry(columns[0], columns[1], columns[2], columns[3])
                require(entry.risk.isNotBlank()) { "coverage.tsv:${index + 1}: risk must explain why the case exists" }
                require(seen.add(entry.fixture to entry.case)) {
                    "coverage.tsv:${index + 1}: duplicate fixture/case ${entry.fixture}/${entry.case}"
                }
                entry
            }.toList()
        }
    }

    private fun knownDeviations(): List<KnownDeviation> {
        val resource = checkNotNull(javaClass.getResourceAsStream("/frontend-correctness/known-deviations.tsv"))
        val seen = mutableSetOf<ExpectationKey>()
        return resource.bufferedReader().useLines { lines ->
            lines.mapIndexedNotNull { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith('#')) return@mapIndexedNotNull null
                val columns = line.split('\t', limit = 5)
                require(columns.size == 5) {
                    "known-deviations.tsv:${index + 1}: expected fixture, case, verdict, fact and evidence"
                }
                val key = ExpectationKey(columns[0], columns[1], Verdict.parse(columns[2]), columns[3])
                require(columns[4].isNotBlank()) {
                    "known-deviations.tsv:${index + 1}: evidence must explain the verified frontend defect"
                }
                require(seen.add(key)) { "known-deviations.tsv:${index + 1}: duplicate deviation $key" }
                KnownDeviation(key, columns[4])
            }.toList()
        }
    }

    private fun schemaCoverage(): List<SchemaCoverageEntry> {
        val resource = checkNotNull(javaClass.getResourceAsStream("/frontend-correctness/schema-coverage.tsv"))
        val seen = mutableSetOf<String>()
        return resource.bufferedReader().useLines { lines ->
            lines.mapIndexedNotNull { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith('#')) return@mapIndexedNotNull null
                val columns = line.split('\t', limit = 4)
                require(columns.size == 4) {
                    "schema-coverage.tsv:${index + 1}: expected element, status, cases and evidence"
                }
                require(seen.add(columns[0])) { "schema-coverage.tsv:${index + 1}: duplicate ${columns[0]}" }
                val cases = columns[2].takeUnless { it == "-" }.orEmpty().split(',').filter { it.isNotBlank() }
                SchemaCoverageEntry(columns[0], SchemaStatus.parse(columns[1]), cases, columns[3]).also {
                    require(it.evidence.isNotBlank()) {
                        "schema-coverage.tsv:${index + 1}: evidence must explain coverage or the gap"
                    }
                }
            }.toList()
        }
    }

    private fun requiredPath(property: String): Path {
        val value = System.getProperty(property)
        require(!value.isNullOrBlank()) { "Missing -D$property=<fixture path>" }
        return Path.of(value).also { require(Files.exists(it)) { "$property does not exist: $it" } }
    }

    /** ApkAnalysisInputLocation requires an SDK layout even when the fixture references no Android API. */
    private fun minimalAndroidSdk(): Path {
        val root = Files.createTempDirectory("frontend-correctness-android-sdk")
        val platform = Files.createDirectories(root.resolve("platforms/android-1"))
        JarOutputStream(Files.newOutputStream(platform.resolve("android.jar"))).use { }
        return root
    }
}

private data class Fixture(val id: String, val input: Path, val packageName: String, val androidSdk: Path? = null)
private data class Expectation(val fixture: String, val case: String, val verdict: Verdict, val fact: String) {
    val key: ExpectationKey get() = ExpectationKey(fixture, case, verdict, fact)
}
private data class ExpectationKey(val fixture: String, val case: String, val verdict: Verdict, val fact: String)
private data class CoverageEntry(val fixture: String, val family: String, val case: String, val risk: String)
private data class KnownDeviation(val key: ExpectationKey, val evidence: String)
private data class SemanticFailure(val expectation: Expectation, val message: String)
private data class SchemaCoverageEntry(
    val element: String,
    val status: SchemaStatus,
    val cases: List<String>,
    val evidence: String
)

private enum class Verdict {
    REQUIRE,
    FORBID;

    companion object {
        fun parse(value: String): Verdict = entries.singleOrNull { it.name.equals(value, ignoreCase = true) }
            ?: error("unknown expectation verdict '$value'; use require or forbid")
    }
}

private enum class SchemaStatus {
    COVERED,
    GAP;

    companion object {
        fun parse(value: String): SchemaStatus = entries.singleOrNull { it.name.equals(value, ignoreCase = true) }
            ?: error("unknown schema coverage status '$value'; use covered or gap")
    }
}

private object CoveragePolicy {
    private val requiredFixtures = setOf("java", "kotlin", "android")
    private val requiredFamilies = setOf("control-flow", "data-flow", "invocation")

    fun verify(
        expectations: List<Expectation>,
        coverage: List<CoverageEntry>,
        schemaCoverage: List<SchemaCoverageEntry>,
        knownDeviations: List<KnownDeviation>
    ) {
        assertEquals(requiredFixtures, coverage.map { it.fixture }.toSet(), "coverage must include every frontend")
        requiredFixtures.forEach { fixture ->
            val families = coverage.filter { it.fixture == fixture }.map { it.family }.toSet()
            assertTrue(
                families.containsAll(requiredFamilies),
                "$fixture is missing required semantic families: ${requiredFamilies - families}"
            )
        }

        val expectedCases = expectations.map { it.fixture to it.case }.toSet()
        val coveredCases = coverage.map { it.fixture to it.case }.toSet()
        val deviationKeys = knownDeviations.mapTo(mutableSetOf()) { it.key }
        assertEquals(coveredCases, expectedCases, "coverage.tsv and expectations.tsv must catalogue the same cases")
        expectations.groupBy { it.fixture to it.case }.forEach { (key, rules) ->
            assertTrue(
                rules.any { it.verdict == Verdict.REQUIRE && it.key !in deviationKeys },
                "$key has no positive semantic assertion that is expected to pass"
            )
            assertTrue(rules.any { it.verdict == Verdict.FORBID }, "$key has no negative semantic assertion")
            rules.groupBy { it.fact }.forEach { (fact, factRules) ->
                assertEquals(1, factRules.map { it.verdict }.distinct().size, "$key contradicts itself for $fact")
            }
        }
        val expectationKeys = expectations.mapTo(mutableSetOf()) { it.key }
        knownDeviations.forEach { deviation ->
            assertTrue(
                deviation.key in expectationKeys,
                "known deviation does not reference an expectation: ${deviation.key}"
            )
        }

        val schemaByElement = schemaCoverage.associateBy { it.element }
        assertEquals(requiredSchemaElements, schemaByElement.keys, "schema coverage must inventory every node and edge kind")
        schemaCoverage.forEach { entry ->
            when (entry.status) {
                SchemaStatus.COVERED -> {
                    assertTrue(entry.cases.isNotEmpty(), "${entry.element} is covered but names no semantic case")
                    val referencedCases = entry.cases.map { reference ->
                        val parts = reference.split(':', limit = 2)
                        assertTrue(
                            parts.size == 2 && (parts[0] to parts[1]) in expectedCases,
                            "${entry.element} references unknown semantic case $reference"
                        )
                        parts[0] to parts[1]
                    }.toSet()
                    val schemaFact = entry.element.toSchemaFact()
                    assertTrue(
                        expectations.any {
                            (it.fixture to it.case) in referencedCases &&
                                it.verdict == Verdict.REQUIRE &&
                                it.fact == schemaFact &&
                                it.key !in deviationKeys
                        },
                        "${entry.element} has no non-deviation REQUIRE for $schemaFact in its cited cases"
                    )
                }
                SchemaStatus.GAP -> assertTrue(entry.cases.isEmpty(), "${entry.element} is a gap but names covered cases")
            }
        }
    }

    private val requiredSchemaElements: Set<String> = buildSet {
        addAll(concreteSealedLeaves(Node::class.java).map { "node:${it.simpleName}" })
        concreteSealedLeaves(Edge::class.java).forEach { edgeType ->
            addAll(edgeSchemaElements(edgeType))
        }
    }

    private fun edgeSchemaElements(edgeType: Class<*>): Set<String> = when (edgeType) {
        DataFlowEdge::class.java -> DataFlowKind.entries.mapTo(mutableSetOf()) { "edge:DataFlowEdge:$it" }
        ResourceEdge::class.java -> ResourceRelation.entries.mapTo(mutableSetOf()) { "edge:ResourceEdge:$it" }
        CallEdge::class.java -> setOf("STATIC", "VIRTUAL", "DYNAMIC").mapTo(mutableSetOf()) { "edge:CallEdge:$it" }
        TypeEdge::class.java -> TypeRelation.entries.mapTo(mutableSetOf()) { "edge:TypeEdge:$it" }
        ControlFlowEdge::class.java -> ControlFlowKind.entries.mapTo(mutableSetOf()) { "edge:ControlFlowEdge:$it" }
        else -> error("Add schema-kind discovery for new Edge subtype ${edgeType.name}")
    }

    private fun concreteSealedLeaves(type: Class<*>): Set<Class<*>> {
        val children = type.permittedSubclasses.orEmpty()
        if (children.isNotEmpty()) return children.flatMapTo(mutableSetOf(), ::concreteSealedLeaves)
        return if (!type.isInterface && !Modifier.isAbstract(type.modifiers)) setOf(type) else emptySet()
    }

    private fun String.toSchemaFact(): String = when {
        startsWith("node:") -> "schema-node|${substringAfter("node:")}"
        startsWith("edge:") -> "schema-edge|${substringAfter("edge:").replace(':', '|')}"
        else -> error("Unknown schema element $this")
    }
}

private object SemanticOracle {
    fun verify(expectations: List<Expectation>, facts: Set<String>): List<SemanticFailure> = expectations.mapNotNull { expectation ->
        val present = expectation.fact in facts
        when {
            expectation.verdict == Verdict.REQUIRE && !present ->
                SemanticFailure(expectation, "${expectation.case}: required fact is missing: ${expectation.fact}")
            expectation.verdict == Verdict.FORBID && present ->
                SemanticFailure(expectation, "${expectation.case}: forbidden fact is present: ${expectation.fact}")
            else -> null
        }
    }
}

private object SemanticFacts {
    fun capture(graph: Graph, packageName: String): Set<String> {
        val nodes = graph.nodes<Node>().associateBy { it.id }
        val methods = graph.methods(MethodPattern()).filter { it.declaringClass.className.startsWith(packageName) }.toList()
        val calls = nodes.values.filterIsInstance<CallSiteNode>().filter { it.caller.declaringClass.className.startsWith(packageName) }
        val facts = sortedSetOf<String>()

        methods.forEach { facts += "method|${it.key()}" }
        calls.forEach { call -> facts += call.facts(graph, nodes) }

        graph.branchScopes().filter { it.method.declaringClass.className.startsWith(packageName) }.forEach { scope ->
            facts += scope.callFacts(graph, "true", scope.trueBranchNodeIds.toIntArray(), nodes)
            facts += scope.callFacts(graph, "false", scope.falseBranchNodeIds.toIntArray(), nodes)
        }

        nodes.values.forEach { node ->
            facts += node.facts(graph, packageName)
            graph.outgoing(node.id).forEach { edge ->
                facts += edge.schemaFact()
                if (edge is ResourceEdge) {
                    facts += "resource-edge|${edge.kind}|${nodes[edge.from].token()}|${nodes[edge.to].token()}"
                }
            }
            graph.outgoing<DataFlowEdge>(node.id).forEach { edge ->
                val from = nodes[edge.from]
                val to = nodes[edge.to]
                val method = from.method() ?: to.method() ?: return@forEach
                if (method.declaringClass.className.startsWith(packageName)) {
                    facts += edge.facts(method, from, to)
                }
            }
        }
        facts += graph.returnConstantFacts(nodes, packageName)
        facts += graph.returnParameterFacts(nodes, packageName)
        val fixtureTypes = methods.mapTo(mutableSetOf()) { it.declaringClass.className }
        fixtureTypes.forEach { className ->
            graph.supertypes(TypeDescriptor(className)).forEach { supertype ->
                facts += "type-relation|$className|${supertype.className}"
            }
        }
        return facts
    }

    private fun Node.facts(graph: Graph, packageName: String): Set<String> = buildSet {
        add("schema-node|${this@facts::class.simpleName}")
        add("node|${this@facts.token()}")
        if (this@facts is ParameterNode && method.declaringClass.className.startsWith(packageName)) {
            add("method-parameter|${method.key()}|$index|${type.className}")
        }
        if (
            this@facts is ReturnNode &&
            method.declaringClass.className.startsWith(packageName) &&
            graph.incoming<DataFlowEdge>(id).any()
        ) {
            add("return-flow|${method.key()}")
        }
    }

    private fun DataFlowEdge.facts(method: MethodDescriptor, from: Node?, to: Node?): Set<String> = buildSet {
        add("flow|${method.key()}|$kind|${from.token()}|${to.token()}")
        if (kind == DataFlowKind.FIELD_LOAD || kind == DataFlowKind.FIELD_STORE) {
            listOf(from, to).filterIsInstance<FieldNode>().singleOrNull()?.let { field ->
                add("field-access|${method.key()}|${field.token()}")
            }
        }
        if (kind == DataFlowKind.ARRAY_LOAD || kind == DataFlowKind.ARRAY_STORE) {
            add("array-access|${method.key()}")
        }
    }

    private fun CallSiteNode.facts(graph: Graph, nodes: Map<NodeId, Node>): Set<String> = buildSet {
        add("call|${caller.key()}|${callee.key()}")
        if (graph.outgoing<CallEdge>(id).any { it.isDynamic }) {
            add("dynamic-call|${caller.key()}|${callee.key()}|${arguments.size}")
        }
        receiver?.let { add("call-receiver|${caller.key()}|${callee.key()}|${nodes[it].token()}") }
        arguments.forEachIndexed { index, argument ->
            add("call-arg|${caller.key()}|${callee.key()}|$index|${nodes[argument].token()}")
        }
        lambdaOwner(caller)?.let { owner -> add("lambda-call|$owner|${callee.key()}") }
    }

    private fun Graph.returnConstantFacts(nodes: Map<NodeId, Node>, packageName: String): Set<String> {
        val facts = mutableSetOf<String>()
        for (source in nodes.values.filterIsInstance<ConstantNode>()) {
            for (nodeId in reachableDataFlowNodes(source.id)) {
                val target = nodes[nodeId] as? ReturnNode ?: continue
                if (target.method.declaringClass.className.startsWith(packageName)) {
                    facts += "return-constant|${target.method.key()}|${source.token()}"
                }
            }
        }
        return facts
    }

    private fun Graph.returnParameterFacts(nodes: Map<NodeId, Node>, packageName: String): Set<String> {
        val facts = mutableSetOf<String>()
        for (source in nodes.values.filterIsInstance<ParameterNode>()) {
            for (nodeId in reachableDataFlowNodes(source.id)) {
                val target = nodes[nodeId] as? ReturnNode ?: continue
                if (target.method == source.method && target.method.declaringClass.className.startsWith(packageName)) {
                    facts += "return-parameter|${target.method.key()}|${source.index}|${source.type.className}"
                }
            }
        }
        return facts
    }

    private fun Graph.reachableDataFlowNodes(source: NodeId): Set<NodeId> {
        val pending = ArrayDeque<NodeId>()
        val visited = mutableSetOf(source)
        pending += source
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            outgoing<DataFlowEdge>(current)
                .map { it.to }
                .filter(visited::add)
                .forEach(pending::addLast)
        }
        return visited
    }

    private fun BranchScope.callFacts(
        graph: Graph,
        side: String,
        ids: IntArray,
        nodes: Map<NodeId, Node>
    ): Set<String> {
        val operator = comparison.operator.name
        val comparand = graph.semanticSourceToken(comparison.comparandNodeId, nodes)
        return ids.asSequence()
            .mapNotNull { nodes[NodeId(it)] as? CallSiteNode }
            .mapTo(mutableSetOf()) { call ->
                val arguments = call.arguments.joinToString(",") { nodes[it].token() }
                "branch-call|${method.key()}|$operator|$comparand|$side|${call.callee.key()}|$arguments"
            }
    }

    private fun Graph.semanticSourceToken(nodeId: NodeId, nodes: Map<NodeId, Node>): String {
        val pending = ArrayDeque<NodeId>()
        val visited = mutableSetOf(nodeId)
        val constants = mutableSetOf<String>()
        pending += nodeId
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            val node = nodes[current]
            if (node is ConstantNode) constants += node.token()
            incoming<DataFlowEdge>(current)
                .map { it.from }
                .filter(visited::add)
                .forEach(pending::addLast)
        }
        return constants.singleOrNull() ?: nodes[nodeId].token()
    }

    private fun lambdaOwner(method: MethodDescriptor): String? {
        val owner = JAVA_LAMBDA_METHOD.matchEntire(method.name)?.groupValues?.get(1)
            ?: KOTLIN_LAMBDA_METHOD.matchEntire(method.name)?.groupValues?.get(1)
            ?: return null
        return "${method.declaringClass.className}.$owner"
    }

    private fun MethodDescriptor.key(): String = signature

    private fun io.johnsonlee.graphite.core.Edge.schemaFact(): String = when (this) {
        is DataFlowEdge -> "schema-edge|DataFlowEdge|$kind"
        is ResourceEdge -> "schema-edge|ResourceEdge|$kind"
        is CallEdge -> "schema-edge|CallEdge|${when {
            isDynamic -> "DYNAMIC"
            isVirtual -> "VIRTUAL"
            else -> "STATIC"
        }}"
        is TypeEdge -> "schema-edge|TypeEdge|$kind"
        is ControlFlowEdge -> "schema-edge|ControlFlowEdge|$kind"
    }

    private fun Node?.method(): MethodDescriptor? = when (this) {
        is LocalVariable -> method
        is ParameterNode -> method
        is ReturnNode -> method
        is CallSiteNode -> caller
        else -> null
    }

    private fun Node?.token(): String = when {
        this == null -> "missing"
        this is ResourceValueNode -> resourceValueToken()
        this is ConstantNode -> constantToken()
        else -> nonConstantToken()
    }

    private fun ConstantNode.constantToken(): String = when (this) {
        is StringConstant -> "string:${value.escape()}"
        is BooleanConstant -> "boolean:$value"
        is IntConstant -> "int:$value"
        is LongConstant -> "long:$value"
        is FloatConstant -> "float:$value"
        is DoubleConstant -> "double:$value"
        is NullConstant -> "null"
        else -> "constant:${value.toString().escape()}"
    }

    private fun ResourceValueNode.resourceValueToken(): String =
        "resource-value:${path.escape()}:${key.escape()}:${value.toString().escape()}"

    private fun Node.nonConstantToken(): String = when (this) {
        is ParameterNode -> "parameter:$index:${type.className}"
        is LocalVariable -> "local:${type.className}"
        is FieldNode -> "field:${descriptor.declaringClass.className}.${descriptor.name}:${descriptor.type.className}"
        is ReturnNode -> "return:${method.key()}"
        is CallSiteNode -> "call:${callee.key()}"
        is ResourceFileNode -> "resource-file:${path.escape()}:${format.escape()}:${profile.orEmpty().escape()}"
        is AnnotationNode -> "annotation:${name.escape()}:${className.escape()}:${memberName.escape()}:" +
            values.toSortedMap().entries.joinToString(",") { "${it.key.escape()}=${it.value.toString().escape()}" }
        else -> this::class.simpleName ?: "node"
    }

    private fun String.escape(): String = replace("\\", "\\\\").replace("|", "\\|").replace(",", "\\,")
    private val JAVA_LAMBDA_METHOD = Regex("lambda\\$([^$]+)\\$\\d+")
    private val KOTLIN_LAMBDA_METHOD = Regex("([^$]+)\\\$lambda\\\$\\d+")
}
