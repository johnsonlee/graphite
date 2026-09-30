package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.LocalDefinition
import io.johnsonlee.graphite.core.ConstantNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.incoming
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.graph.outgoing
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Branch-side constant definitions on [BranchScope]: which `local = <constant>` statements
 * each side of a condition makes. Java fixtures come from `sample.constdefs.LocalDefinitionExample`,
 * Kotlin ones from `sample.constdefs.KotlinLocalDefinitionExample`.
 */
class LocalDefinitionTest {

    private val javaGraph: Graph by lazy { load(findTestClassesDir("java")) }
    private val kotlinGraph: Graph by lazy { load(findTestClassesDir("kotlin")) }

    private fun load(classesDir: Path): Graph {
        assertTrue(classesDir.exists(), "Test classes directory should exist: $classesDir")
        return JavaProjectLoader(
            LoaderConfig(
                includePackages = listOf("sample.constdefs"),
                buildCallGraph = false
            )
        ).load(classesDir)
    }

    /** A definition rendered as `local=value` (or `local=?` for a non-constant write) for readable assertions. */
    private fun Graph.render(definition: LocalDefinition): String {
        val local = assertNotNull(node(definition.localNodeId) as? LocalVariable, "local ${definition.localNodeId}")
        return "${local.name}=${constantValue(definition) ?: "?"}"
    }

    /** The constant a definition assigns, or `null` for a non-constant write. */
    private fun Graph.constantValue(definition: LocalDefinition): Any? {
        val constantId = definition.constantNodeId ?: return null
        return assertNotNull(node(constantId) as? ConstantNode, "constant $constantId").value
    }

    private fun Graph.values(definitions: List<LocalDefinition>): List<Any?> = definitions.map { constantValue(it) }

    private fun Graph.scopesOf(method: String): List<BranchScope> =
        branchScopes().filter { it.method.name == method }.toList()

    private fun BranchScope.sides(): List<List<LocalDefinition>> = listOf(trueDefinitions, falseDefinitions)

    private fun BranchScope.allDefinitions(): List<LocalDefinition> = trueDefinitions + falseDefinitions

    /** Every scope side lists its definitions in ascending statement order. */
    private fun assertOrdered(scopes: List<BranchScope>) {
        scopes.forEach { scope ->
            scope.sides().forEach { side ->
                assertEquals(side.sortedBy { it.stmtOrdinal }, side, "definitions must be ordered by stmtOrdinal")
            }
        }
    }

    /** A definition sits on at most one side of a given scope. */
    private fun assertOneSide(scopes: List<BranchScope>) {
        scopes.forEach { scope ->
            val trueOrdinals = scope.trueDefinitions.map { it.stmtOrdinal }.toSet()
            val falseOrdinals = scope.falseDefinitions.map { it.stmtOrdinal }.toSet()
            assertTrue(trueOrdinals.none { it in falseOrdinals }, "definition on both sides of $scope")
        }
    }

    /** Every constant definition matches exactly one constant ASSIGN edge; they never exceed the local's edges. */
    private fun assertEdgeInvariant(graph: Graph, scopes: List<BranchScope>) {
        val definitionsByLocal = scopes.flatMap { it.allDefinitions() }
            .filter { it.isConstant }
            .distinctBy { it.stmtOrdinal }
            .groupBy { it.localNodeId }
        definitionsByLocal.forEach { (localId, definitions) ->
            val constantEdges = graph.incoming<DataFlowEdge>(localId)
                .filter { it.kind == DataFlowKind.ASSIGN && graph.node(it.from) is ConstantNode }
                .map { it.from }
                .toList()
            assertTrue(
                constantEdges.size >= definitions.size,
                "local $localId has ${definitions.size} definitions but only ${constantEdges.size} constant ASSIGN edges"
            )
            val remaining = constantEdges.toMutableList()
            definitions.forEach { definition ->
                assertTrue(remaining.remove(definition.constantNodeId!!), "no ASSIGN edge for ${graph.render(definition)}")
            }
        }
    }

    /**
     * Every local with a branch-side definition has a full table: ordered by statement, a superset of
     * its branch-side definitions, every constant entry backed by its own constant ASSIGN edge in the
     * source graph (an edge may exist without a definition, e.g. the value flow of `Integer.valueOf(String)`),
     * and every write of the local in the method present (each write statement records exactly one).
     */
    private fun assertDefinitionTables(graph: Graph, scopes: List<BranchScope>) {
        val branchDefinitions = scopes.flatMap { it.allDefinitions() }.distinctBy { it.stmtOrdinal }
        branchDefinitions.groupBy { it.localNodeId }.forEach { (localId, onSides) ->
            val table = graph.localDefinitionsFor(localId)
            assertEquals(table.sortedBy { it.stmtOrdinal }, table, "table of $localId is ordered")
            assertEquals(table.map { it.stmtOrdinal }.distinct().size, table.size, "one entry per statement in $localId")
            assertTrue(table.containsAll(onSides), "table of $localId misses ${onSides - table.toSet()}")
            val constantSources = graph.incoming<DataFlowEdge>(localId)
                .filter { it.kind == DataFlowKind.ASSIGN && graph.node(it.from) is ConstantNode }
                .map { it.from }.toMutableList()
            table.filter { it.isConstant }.forEach {
                assertTrue(constantSources.remove(it.constantNodeId!!), "no ASSIGN edge for ${graph.render(it)}")
            }
        }
        val trackedLocals = branchDefinitions.map { it.localNodeId }.toSet()
        assertEquals(trackedLocals, graph.localDefinitions().keys.filter { it in trackedLocals }.toSet())
    }

    private fun assertInvariants(graph: Graph, method: String): List<BranchScope> {
        val scopes = graph.scopesOf(method)
        assertOrdered(scopes)
        assertOneSide(scopes)
        assertEdgeInvariant(graph, scopes)
        assertDefinitionTables(graph, scopes)
        return scopes
    }

    // --- Java fixtures ---

    @Test
    fun `short-circuit and splits the boolean local's constant definitions across the condition sides`() {
        val scopes = assertInvariants(javaGraph, "shortCircuitAnd")
        assertEquals(3, scopes.size, "f(), g() and if (on): $scopes")

        val callScopes = scopes.filter { it.allDefinitions().isNotEmpty() }
        assertEquals(2, callScopes.size, "the f() and g() conditions carry the definitions of `on`")
        // g() decides between on = 1 and on = 0. f() false reaches on = 0 directly, but f() true reaches it
        // through g() as well, so on = 0 belongs to neither side of f(): only on = 1 is exclusive to f() true.
        assertEquals(
            setOf(setOf(listOf<Any?>(1), listOf(0)), setOf(listOf<Any?>(1), emptyList())),
            callScopes.map { scope -> scope.sides().map { javaGraph.values(it) }.toSet() }.toSet(),
            "sides: ${callScopes.map { scope -> scope.sides().map { javaGraph.values(it) } }}"
        )
        val locals = callScopes.flatMap { it.allDefinitions() }.map { it.localNodeId }.toSet()
        assertEquals(1, locals.size, "all definitions target the single `on` local")

        val onScope = scopes.single { it.allDefinitions().isEmpty() }
        assertEquals(locals.single(), onScope.conditionNodeId, "if (on) tests the same local")
    }

    @Test
    fun `short-circuit or mirrors the and case with the constants swapped`() {
        val scopes = assertInvariants(javaGraph, "shortCircuitOr")
        val callScopes = scopes.filter { it.allDefinitions().isNotEmpty() }
        assertEquals(2, callScopes.size)
        // f() true reaches on = 1 directly and f() false reaches it through g(): only on = 0 is exclusive to f() false.
        assertEquals(
            setOf(setOf(listOf<Any?>(1), listOf(0)), setOf(listOf<Any?>(0), emptyList())),
            callScopes.map { scope -> scope.sides().map { javaGraph.values(it) }.toSet() }.toSet(),
            "sides: ${callScopes.map { scope -> scope.sides().map { javaGraph.values(it) } }}"
        )
    }

    @Test
    fun `nested branches repeat inner definitions in the matching outer side`() {
        val scopes = assertInvariants(javaGraph, "nested")
        assertEquals(2, scopes.size)

        val inner = scopes.single { scope -> scope.sides().all { it.size == 1 } }
        val outer = scopes.single { it !== inner }
        assertEquals(setOf(listOf(1), listOf(2)), inner.sides().map { javaGraph.values(it) }.toSet())

        val outerSides = outer.sides().map { javaGraph.values(it).toSet() }
        assertEquals(setOf(setOf<Any?>(1, 2), setOf<Any?>(3)), outerSides.toSet(), "outer sides: $outerSides")
        val outerStmts = outer.allDefinitions().map { it.stmtOrdinal }.toSet()
        inner.allDefinitions().forEach {
            assertTrue(it.stmtOrdinal in outerStmts, "inner definition ${javaGraph.render(it)} missing from the outer scope")
        }
    }

    @Test
    fun `repeated assignment of the same constant yields two definitions matching two edges`() {
        val scopes = assertInvariants(javaGraph, "repeated")
        val scope = scopes.single()
        val side = scope.sides().single { it.isNotEmpty() }
        assertEquals(listOf<Any?>(1, 1), javaGraph.values(side))
        assertEquals(2, side.map { it.stmtOrdinal }.distinct().size, "distinct statements")
        assertEquals(1, side.map { it.localNodeId }.distinct().size, "same local")

        val local = side.first().localNodeId
        val edgesFromOne = javaGraph.incoming<DataFlowEdge>(local)
            .filter { it.kind == DataFlowKind.ASSIGN && it.from == side.first().constantNodeId }
            .count()
        assertEquals(2, edgesFromOne, "two identical ASSIGN edges back the two definitions")
        assertEquals(listOf<Any?>(0, 1, 1), javaGraph.values(javaGraph.localDefinitionsFor(local)), "x = 0 first, then both x = 1")
    }

    @Test
    fun `straight-line definitions appear in no scope and survive multiset subtraction`() {
        val scopes = assertInvariants(javaGraph, "straightLine")
        val scope = scopes.single()
        val branchDefinitions = scope.allDefinitions()
        assertEquals(listOf<Any?>(6), javaGraph.values(branchDefinitions))

        val local = branchDefinitions.single().localNodeId
        val killed = branchDefinitions.map { it.stmtOrdinal }.toSet()
        val alive = javaGraph.localDefinitionsFor(local).filter { it.stmtOrdinal !in killed }
        assertEquals(listOf<Any?>(5), javaGraph.values(alive), "x = 5 is the only straight-line definition")
        assertEquals(listOf<Any?>(5, 6), javaGraph.values(javaGraph.localDefinitionsFor(local)), "full table in statement order")
    }

    @Test
    fun `a statement reached by both targets of a branch defines neither side`() {
        val scopes = assertInvariants(javaGraph, "emptyThen")
        val scope = scopes.single()
        assertTrue(
            scope.trueBranchNodeIds.isNotEmpty() && scope.trueBranchNodeIds == scope.falseBranchNodeIds,
            "both targets are the same statement, so the node sets coincide: $scope"
        )
        assertEquals(emptyList(), scope.allDefinitions(), "x = 1 executes whichever way the branch goes")
        javaGraph.nodes<LocalVariable>().filter { it.method.name == "emptyThen" }.forEach {
            assertEquals(emptyList(), javaGraph.localDefinitionsFor(it.id), "no table for ${it.name}")
        }
    }

    @Test
    fun `a write at the merge point after a branch defines neither side`() {
        assertNeitherSideDefines("postMerge")
    }

    @Test
    fun `a write at the exit of a loop defines neither side`() {
        assertNeitherSideDefines("loopExit")
    }

    /** The method's scopes carry no definitions and no local has a table, although `x = 1` follows a branch. */
    private fun assertNeitherSideDefines(method: String) {
        val scopes = assertInvariants(javaGraph, method)
        assertTrue(scopes.isNotEmpty(), "$method has a branch")
        assertTrue(scopes.all { it.allDefinitions().isEmpty() }, "x = 1 executes whichever way the branch goes: $scopes")
        javaGraph.nodes<LocalVariable>().filter { it.method.name == method }.forEach {
            assertEquals(emptyList(), javaGraph.localDefinitionsFor(it.id), "no table for ${it.name}")
        }
    }

    @Test
    fun `non-constant right-hand sides produce no definitions`() {
        val scopes = assertInvariants(javaGraph, "nonConstant")
        assertTrue(scopes.isNotEmpty())
        assertTrue(scopes.all { it.allDefinitions().isEmpty() }, "scopes: $scopes")
        javaGraph.nodes<LocalVariable>().filter { it.method.name == "nonConstant" }.forEach {
            assertEquals(emptyList(), javaGraph.localDefinitionsFor(it.id), "no table for ${it.name}")
        }
    }

    @Test
    fun `parsing valueOf overloads never define a constant, only the primitive boxing overload does`() {
        val scopes = assertInvariants(javaGraph, "parsedBoxing")
        val definitions = scopes.flatMap { it.allDefinitions() }.distinctBy { it.stmtOrdinal }
        assertTrue(definitions.isNotEmpty(), "Integer.valueOf(2) is a boxing definition")
        definitions.filter { it.isConstant }.forEach {
            val constant = javaGraph.node(it.constantNodeId!!)
            assertTrue(constant is IntConstant, "only int constants are boxed here, got $constant")
        }
        // Integer.valueOf(2) plus the ternary's 0 and 1; the parsed "1" and "true" never appear as constants.
        assertEquals(setOf<Any?>(0, 1, 2), javaGraph.values(definitions).filterNotNull().toSet())
    }

    @Test
    fun `a surviving non-constant write is recorded so the local cannot fold to the remaining constant`() {
        val scopes = assertInvariants(javaGraph, "nonConstantSurvivor")
        val scope = scopes.single()
        val constantSide = scope.sides().single { side -> side.any { it.isConstant } }
        val otherSide = scope.sides().single { it !== constantSide }
        assertEquals(listOf<Any?>(2), javaGraph.values(constantSide), "x = 2 on one side")
        assertEquals(listOf<Any?>(null), javaGraph.values(otherSide), "x = y on the other side is a non-constant write")

        val local = constantSide.single().localNodeId
        val table = javaGraph.localDefinitionsFor(local)
        assertEquals(listOf<Any?>(1, 2, null), javaGraph.values(table), "x = 1, x = 2, x = y in statement order")

        // Killing the constant side leaves x = 1 and the live x = y: not foldable.
        val killed = constantSide.map { it.stmtOrdinal }.toSet()
        val alive = table.filter { it.stmtOrdinal !in killed }
        assertEquals(listOf<Any?>(1, null), javaGraph.values(alive))
        assertTrue(alive.any { !it.isConstant }, "a non-constant write survives, so x must not fold to 1")
    }

    @Test
    fun `parameters are recorded as non-constant writes of the locals they bind`() {
        val scopes = assertInvariants(javaGraph, "parameterWrite")
        val definitions = scopes.flatMap { it.allDefinitions() }
        assertEquals(listOf<Any?>(5), javaGraph.values(definitions), "only n = 5 sits on a side")
        val table = javaGraph.localDefinitionsFor(definitions.single().localNodeId)
        assertEquals(listOf<Any?>(null, 5), javaGraph.values(table), "the parameter binding precedes n = 5")
        assertTrue(table.first().stmtOrdinal < table.last().stmtOrdinal)
    }

    @Test
    fun `loop body definitions follow the side that already owns the loop body's nodes`() {
        val scopes = assertInvariants(javaGraph, "loop")
        val scope = scopes.single()
        val definition = scope.allDefinitions().single()
        assertEquals(1, javaGraph.constantValue(definition))

        // The loop header is the only condition; its exit side is exclusive to the exit path, so the body
        // (including x = 1) sits on the other side, exactly where the existing node sets already put it.
        val bodySide = scope.sides().single { it.isNotEmpty() }
        val bodyNodeIds = if (bodySide === scope.trueDefinitions) scope.trueBranchNodeIds else scope.falseBranchNodeIds
        val exitNodeIds = if (bodySide === scope.trueDefinitions) scope.falseBranchNodeIds else scope.trueBranchNodeIds
        assertTrue(bodyNodeIds.contains(definition.localNodeId.value), "x lives on the side owning the definition")
        assertFalse(exitNodeIds.contains(definition.localNodeId.value), "the exit side owns neither x nor its definition")
    }

    @Test
    fun `killing the gate's true side leaves only zero definitions of on alive`() {
        val scopes = assertInvariants(javaGraph, "sample")
        assertEquals(4, scopes.size, "gate(), a, b and if (on): $scopes")

        // `on` is the only local that is both defined by constants and tested by a condition.
        val onScope = scopes.single { scope -> hasLocalDefinition(scopes, scope.conditionNodeId) }
        val onLocalId = onScope.conditionNodeId
        assertTrue(onScope.allDefinitions().isEmpty())

        // The gate() condition tests the local that receives gate()'s return value.
        val gateCall = javaGraph.nodes<CallSiteNode>().single { it.caller.name == "sample" && it.callee.name == "gate" }
        val gateResults = javaGraph.outgoing<DataFlowEdge>(gateCall.id).map { it.to }.toSet()
        val gateScope = scopes.single { it.conditionNodeId in gateResults }

        // In source terms gate() == true continues into (a || b), the only path that assigns on = 1. The other
        // side owns nothing: on = 0 is reached by gate() == false directly and by gate() == true through (a || b).
        val gateTrueSide = gateScope.sides().single { javaGraph.values(it) == listOf<Any?>(1) }
        val gateFalseSide = gateScope.sides().single { it !== gateTrueSide }
        assertEquals(emptyList(), gateFalseSide)

        val table = javaGraph.localDefinitionsFor(onLocalId)
        assertEquals(listOf<Any?>(1, 0), javaGraph.values(table), "every write of `on`")

        // gate() folds to false: its true side dies and only on = 0 survives, so `on` folds to 0.
        val killed = gateTrueSide.map { it.stmtOrdinal }.toSet()
        val alive = table.filter { it.stmtOrdinal !in killed }
        assertEquals(listOf<Any?>(0), javaGraph.values(alive), alive.map { javaGraph.render(it) }.toString())

        // gate() folds to true: its false side owns no write, so both writes survive and `on` does not fold,
        // which is right because (a || b) still decides between them.
        assertEquals(table, table.filter { it.stmtOrdinal !in gateFalseSide.map { d -> d.stmtOrdinal }.toSet() })
    }

    // --- Kotlin fixtures ---

    @Test
    fun `nullable boolean records the boxed constant and the null constant on opposite sides`() {
        val scopes = assertInvariants(kotlinGraph, "nullableBoolean")
        val scope = scopes.single()
        val sides = scope.sides()
        assertTrue(sides.all { it.size == 1 }, "one definition per side: $sides")
        val boxed = sides.single { kotlinGraph.node(it.single().constantNodeId!!) !is NullConstant }.single()
        val nulled = sides.single { kotlinGraph.node(it.single().constantNodeId!!) is NullConstant }.single()
        assertEquals(1, kotlinGraph.constantValue(boxed), "Boolean.valueOf(1) is folded")
        assertEquals(boxed.localNodeId, nulled.localNodeId, "both sides define the same local")
    }

    @Test
    fun `when expression places each branch constant on its side`() {
        val scopes = assertInvariants(kotlinGraph, "whenExpression")
        val definitions = scopes.flatMap { it.allDefinitions() }.distinctBy { it.stmtOrdinal }
        assertEquals(setOf<Any?>(1, 2, 3), kotlinGraph.values(definitions).toSet())
        assertEquals(1, definitions.map { it.localNodeId }.distinct().size, "n is the only local")
        scopes.forEach { scope ->
            assertTrue(scope.allDefinitions().isNotEmpty(), "each when branch carries a definition: $scope")
        }
    }

    private fun hasLocalDefinition(scopes: List<BranchScope>, localId: NodeId): Boolean =
        scopes.any { scope -> scope.allDefinitions().any { it.localNodeId == localId } }

    private fun findTestClassesDir(language: String): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/$language/test")
        val rootPath = projectDir.resolve("frontend/jvm/sootup/build/classes/$language/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }
}
