package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.EnumValueReference
import io.johnsonlee.graphite.core.FieldDescriptor
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
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression gate for graph information loss.
 *
 * The baseline is a multiset of stable graph facts from the sample corpus.
 * New facts are allowed, but dropping any baseline fact fails the test.
 */
@Suppress("TooManyFunctions")
class GraphParityRegressionTest {

    @Test
    fun `sample corpus graph facts are a superset of the parity baseline`() {
        val current = GraphParitySnapshot.capture(loadSampleCorpusGraph())

        if (System.getenv(UPDATE_BASELINE_ENV) == "true") {
            writeBaseline(current)
            return
        }

        val baseline = readBaseline()
        val missing = baseline.missingFrom(current)

        assertTrue(
            missing.isEmpty(),
            buildString {
                appendLine("Graph parity regression: current graph is missing ${missing.size} baseline facts.")
                appendLine("Additions are allowed, but removing existing graph facts is not.")
                missing.take(MAX_REPORTED_MISSING_FACTS).forEach { fact ->
                    appendLine("- baseline=${fact.expected}, current=${fact.actual}: ${fact.fact}")
                }
                if (missing.size > MAX_REPORTED_MISSING_FACTS) {
                    appendLine("- ... ${missing.size - MAX_REPORTED_MISSING_FACTS} more missing facts")
                }
            }
        )
    }

    @Test
    fun `graph parity comparison allows additions but rejects missing baseline facts`() {
        val baseline = GraphParitySnapshot(
            mapOf(
                "node\tA" to 1,
                "edge\tB" to 2
            )
        )
        val superset = GraphParitySnapshot(
            mapOf(
                "node\tA" to 1,
                "edge\tB" to 3,
                "node\tC" to 1
            )
        )

        assertTrue(baseline.missingFrom(superset).isEmpty(), "Extra facts should not fail graph parity")
        assertEquals(
            listOf(MissingFact("edge\tB", expected = 2, actual = 1)),
            baseline.missingFrom(
                GraphParitySnapshot(
                    mapOf(
                        "node\tA" to 1,
                        "edge\tB" to 1
                    )
                )
            ),
            "Reducing the count of a baseline fact should fail graph parity"
        )
        assertEquals(
            listOf(
                MissingFact("node\tA", expected = 1, actual = 0),
                MissingFact("edge\tB", expected = 2, actual = 0)
            ),
            baseline.missingFrom(GraphParitySnapshot(emptyMap())),
            "Dropping baseline facts should fail graph parity"
        )
    }

    private fun loadSampleCorpusGraph(): Graph {
        val classesDir = findTestClassesDir()
        assertTrue(classesDir.exists(), "Test classes directory should exist: $classesDir")
        return JavaProjectLoader(
            LoaderConfig(
                includePackages = listOf("sample"),
                buildCallGraph = false
            )
        ).load(classesDir)
    }

    private fun readBaseline(): GraphParitySnapshot {
        val stream = javaClass.getResourceAsStream("/$BASELINE_RESOURCE")
            ?: error("Missing graph parity baseline resource: $BASELINE_RESOURCE")
        return stream.bufferedReader().use { reader ->
            GraphParitySnapshot(
                reader.lineSequence()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .associate { line ->
                        val tab = line.indexOf('\t')
                        require(tab > 0) { "Invalid baseline line: $line" }
                        val count = line.substring(0, tab).toInt()
                        val fact = line.substring(tab + 1)
                        fact to count
                    }
            )
        }
    }

    private fun writeBaseline(snapshot: GraphParitySnapshot) {
        val baselinePath = baselineSourcePath()
        Files.createDirectories(baselinePath.parent)
        Files.writeString(
            baselinePath,
            buildString {
                appendLine("# Graph parity baseline for the sootup sample.* corpus.")
                appendLine("# Regenerate intentionally with:")
                appendLine("# $UPDATE_BASELINE_ENV=true ./gradlew :sootup:test --tests '${GraphParityRegressionTest::class.qualifiedName}'")
                appendLine("# New graph facts are accepted by the test; missing baseline facts fail.")
                snapshot.facts.toSortedMap().forEach { (fact, count) ->
                    appendLine("$count\t$fact")
                }
            }
        )
    }

    private fun baselineSourcePath(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("src/test/resources").resolve(BASELINE_RESOURCE)
        val rootPath = projectDir.resolve("graphite-sootup/src/test/resources").resolve(BASELINE_RESOURCE)
        return if (projectDir.resolve("src/test").exists()) submodulePath else rootPath
    }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }

    private companion object {
        const val BASELINE_RESOURCE = "graph-parity/sample-corpus-baseline.tsv"
        const val UPDATE_BASELINE_ENV = "GRAPHITE_UPDATE_GRAPH_PARITY_BASELINE"
        const val MAX_REPORTED_MISSING_FACTS = 50
    }
}

private data class MissingFact(
    val fact: String,
    val expected: Int,
    val actual: Int
)

private data class GraphParitySnapshot(
    val facts: Map<String, Int>
) {
    fun missingFrom(current: GraphParitySnapshot): List<MissingFact> =
        facts.entries.mapNotNull { (fact, expected) ->
            val actual = current.facts[fact] ?: 0
            if (actual < expected) {
                MissingFact(fact, expected, actual)
            } else {
                null
            }
        }

    companion object {
        fun capture(graph: Graph): GraphParitySnapshot =
            GraphParityCanonicalizer(graph).capture()
    }
}

@Suppress("CyclomaticComplexMethod", "TooManyFunctions")
private class GraphParityCanonicalizer(
    private val graph: Graph
) {
    private val nodeById = graph.nodes(Node::class.java).associateBy { it.id }
    private val nodeKeyCache = mutableMapOf<NodeId, String>()
    private val resolvingNodeIds = mutableSetOf<NodeId>()

    fun capture(): GraphParitySnapshot {
        val facts = TreeMap<String, Int>()
        addNodeFacts(facts)
        addEdgeFacts(facts)
        addMethodFacts(facts)
        addTypeHierarchyFacts(facts)
        addMemberAnnotationFacts(facts)
        addBranchScopeFacts(facts)
        addClassOriginFacts(facts)
        addArtifactDependencyFacts(facts)
        return GraphParitySnapshot(facts)
    }

    private fun addNodeFacts(facts: MutableMap<String, Int>) {
        nodeById.values.forEach { node ->
            facts.increment("node\t${nodeKey(node)}")
            if (node is EnumConstant) {
                facts.increment(
                    "enumValue\t${node.enumType.key()}#${node.enumName}=" +
                        valueKey(graph.enumValues(node.enumType.className, node.enumName))
                )
            }
        }
    }

    private fun addEdgeFacts(facts: MutableMap<String, Int>) {
        nodeById.keys.forEach { nodeId ->
            graph.outgoing(nodeId).forEach { edge ->
                facts.increment("edge\t${edgeKey(edge)}")
            }
        }
    }

    private fun addMethodFacts(facts: MutableMap<String, Int>) {
        graph.methods(MethodPattern()).forEach { method ->
            facts.increment("method\t${method.key()}")
        }
    }

    private fun addTypeHierarchyFacts(facts: MutableMap<String, Int>) {
        graph.typeHierarchyTypes().forEach { typeName ->
            val type = TypeDescriptor(typeName)
            facts.increment("typeHierarchyType\t${type.key()}")
            graph.supertypes(type).forEach { supertype ->
                facts.increment("supertype\t${type.key()}->${supertype.key()}")
            }
            graph.subtypes(type).forEach { subtype ->
                facts.increment("subtype\t${type.key()}<-${subtype.key()}")
            }
        }
    }

    private fun addMemberAnnotationFacts(facts: MutableMap<String, Int>) {
        graph.memberAnnotationIndex()?.toSortedMap()?.forEach { (member, annotations) ->
            annotations.toSortedMap().forEach { (annotation, values) ->
                facts.increment("memberAnnotation\t${escape(member)}\t${escape(annotation)}\t${valueKey(values)}")
            }
        }
    }

    private fun addBranchScopeFacts(facts: MutableMap<String, Int>) {
        graph.branchScopes().forEach { scope ->
            val scopeKey = scope.baseKey()
            facts.increment("branchScope\t$scopeKey")
            scope.trueBranchNodeIds.toIntArray().forEach { nodeId ->
                facts.increment("branchScopeMember\t$scopeKey\ttrue\t${nodeKey(NodeId(nodeId))}")
            }
            scope.falseBranchNodeIds.toIntArray().forEach { nodeId ->
                facts.increment("branchScopeMember\t$scopeKey\tfalse\t${nodeKey(NodeId(nodeId))}")
            }
        }
    }

    private fun addClassOriginFacts(facts: MutableMap<String, Int>) {
        graph.classOrigins().toSortedMap().forEach { (className, origin) ->
            facts.increment("classOrigin\t${escape(className)}=${escape(origin)}")
        }
    }

    private fun addArtifactDependencyFacts(facts: MutableMap<String, Int>) {
        graph.artifactDependencies().toSortedMap().forEach { (source, dependencies) ->
            dependencies.toSortedMap().forEach { (target, weight) ->
                facts.increment("artifactDependency\t${escape(source)}->${escape(target)}=$weight")
            }
        }
    }

    private fun nodeKey(nodeId: NodeId): String =
        nodeKeyCache.getOrPut(nodeId) {
            if (!resolvingNodeIds.add(nodeId)) {
                return@getOrPut "Cycle(${nodeById[nodeId]?.javaClass?.simpleName ?: "MissingNode"})"
            }
            try {
                nodeById[nodeId]?.let(::nodeKey) ?: "MissingNode"
            } finally {
                resolvingNodeIds.remove(nodeId)
            }
        }

    private fun nodeKey(node: Node): String =
        when (node) {
            is AnnotationNode -> "AnnotationNode(" +
                "name=${escape(node.name)}," +
                "class=${escape(node.className)}," +
                "member=${escape(node.memberName)}," +
                "values=${valueKey(node.values)})"
            is BooleanConstant -> "BooleanConstant(${node.value})"
            is CallSiteNode -> "CallSiteNode(" +
                "caller=${node.caller.key()}," +
                "callee=${node.callee.key()}," +
                "line=${node.lineNumber}," +
                "receiver=${node.receiver?.let(::nodeKey)}," +
                "args=${node.arguments.joinToString(prefix = "[", postfix = "]") { nodeKey(it) }})"
            is DoubleConstant -> "DoubleConstant(${node.value})"
            is EnumConstant -> "EnumConstant(" +
                "type=${node.enumType.key()}," +
                "name=${escape(node.enumName)}," +
                "args=${valueKey(node.constructorArgs)})"
            is FieldNode -> "FieldNode(field=${node.descriptor.key()},static=${node.isStatic})"
            is FloatConstant -> "FloatConstant(${node.value})"
            is IntConstant -> "IntConstant(${node.value})"
            is LocalVariable -> "LocalVariable(" +
                "method=${node.method.key()}," +
                "name=${escape(node.name)}," +
                "type=${node.type.key()})"
            is LongConstant -> "LongConstant(${node.value})"
            is NullConstant -> "NullConstant"
            is ParameterNode -> "ParameterNode(" +
                "method=${node.method.key()}," +
                "index=${node.index}," +
                "type=${node.type.key()})"
            is ResourceFileNode -> "ResourceFileNode(" +
                "path=${escape(node.path)}," +
                "source=${escape(node.source)}," +
                "format=${escape(node.format)}," +
                "profile=${escapeNullable(node.profile)})"
            is ResourceValueNode -> "ResourceValueNode(" +
                "path=${escape(node.path)}," +
                "key=${escape(node.key)}," +
                "value=${valueKey(node.value)}," +
                "format=${escape(node.format)}," +
                "profile=${escapeNullable(node.profile)})"
            is ReturnNode -> "ReturnNode(method=${node.method.key()},actualType=${node.actualType?.key()})"
            is StringConstant -> "StringConstant(${escape(node.value)})"
        }

    private fun edgeKey(edge: Edge): String =
        when (edge) {
            is CallEdge -> "CallEdge(" +
                "from=${nodeKey(edge.from)}," +
                "to=${nodeKey(edge.to)}," +
                "virtual=${edge.isVirtual}," +
                "dynamic=${edge.isDynamic})"
            is ControlFlowEdge -> "ControlFlowEdge(" +
                "kind=${edge.kind}," +
                "from=${nodeKey(edge.from)}," +
                "to=${nodeKey(edge.to)}," +
                "comparison=${edge.comparison?.key()})"
            is DataFlowEdge -> "DataFlowEdge(" +
                "kind=${edge.kind}," +
                "from=${nodeKey(edge.from)}," +
                "to=${nodeKey(edge.to)})"
            is ResourceEdge -> "ResourceEdge(" +
                "kind=${edge.kind}," +
                "from=${nodeKey(edge.from)}," +
                "to=${nodeKey(edge.to)})"
            is TypeEdge -> "TypeEdge(" +
                "kind=${edge.kind}," +
                "from=${nodeKey(edge.from)}," +
                "to=${nodeKey(edge.to)})"
        }

    private fun BranchScope.baseKey(): String =
        "condition=${nodeKey(conditionNodeId)}," +
            "method=${method.key()}," +
            "comparison=${comparison.key()}"

    private fun BranchComparison.key(): String =
        "${operator}:${nodeKey(comparandNodeId)}"

    private fun TypeDescriptor.key(): String =
        if (typeArguments.isEmpty()) {
            escape(className)
        } else {
            escape(className) + typeArguments.joinToString(prefix = "<", postfix = ">") { it.key() }
        }

    private fun MethodDescriptor.key(): String =
        "${declaringClass.key()}.${escape(name)}(" +
            parameterTypes.joinToString(",") { it.key() } +
            "):${returnType.key()}"

    private fun FieldDescriptor.key(): String =
        "${declaringClass.key()}.${escape(name)}:${type.key()}"

    private fun valueKey(value: Any?): String =
        when (value) {
            null -> "null"
            is Array<*> -> value.asIterable().joinToString(prefix = "[", postfix = "]") { valueKey(it) }
            is BooleanArray -> value.joinToString(prefix = "[", postfix = "]")
            is ByteArray -> value.joinToString(prefix = "[", postfix = "]")
            is CharArray -> value.joinToString(prefix = "[", postfix = "]") { escape(it.toString()) }
            is DoubleArray -> value.joinToString(prefix = "[", postfix = "]")
            is EnumValueReference -> "EnumValueReference(${escape(value.enumClass)}.${escape(value.enumName)})"
            is FieldDescriptor -> value.key()
            is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
            is IntArray -> value.joinToString(prefix = "[", postfix = "]")
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { valueKey(it) }
            is LongArray -> value.joinToString(prefix = "[", postfix = "]")
            is Map<*, *> -> value.entries
                .map { valueKey(it.key) to valueKey(it.value) }
                .sortedBy { it.first }
                .joinToString(prefix = "{", postfix = "}") { (key, mapValue) -> "$key=$mapValue" }
            is MethodDescriptor -> value.key()
            is ShortArray -> value.joinToString(prefix = "[", postfix = "]")
            is String -> escape(value)
            is TypeDescriptor -> value.key()
            else -> escape(value.toString())
        }

    private fun escapeNullable(value: String?): String =
        value?.let(::escape) ?: "null"

    private fun escape(value: String): String =
        buildString {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }

    private fun MutableMap<String, Int>.increment(fact: String) {
        this[fact] = (this[fact] ?: 0) + 1
    }
}
