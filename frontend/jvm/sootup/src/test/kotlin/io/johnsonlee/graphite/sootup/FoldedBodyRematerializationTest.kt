package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.BodyInterceptionEvidence
import io.johnsonlee.graphite.input.JvmMethodIdentity
import io.johnsonlee.graphite.input.MethodFoldObservation
import io.johnsonlee.graphite.graph.AnalysisCompleteness
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.input.AnalysisIdentity
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.ArtifactRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import sootup.core.graph.MutableBlockControlFlowGraph
import sootup.core.jimple.basic.SimpleStmtPositionInfo
import sootup.core.jimple.basic.StmtPositionInfo
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.constant.StringConstant
import sootup.core.jimple.common.expr.JDynamicInvokeExpr
import sootup.core.jimple.common.expr.JEqExpr
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.jimple.common.ref.JCaughtExceptionRef
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JIdentityStmt
import sootup.core.jimple.common.stmt.JIfStmt
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.core.jimple.common.stmt.JReturnStmt
import sootup.core.jimple.javabytecode.stmt.JSwitchStmt
import sootup.core.model.Body
import sootup.core.model.MethodModifier
import sootup.java.core.JavaIdentifierFactory

class FoldedBodyRematerializationTest {
    private val factory = JavaIdentifierFactory.getInstance()
    private val position = StmtPositionInfo.getNoStmtPositionInfo()

    @Test
    fun `delivery releases validation indexes after success and failure while consumer retains its event`() {
        for (fail in listOf(false, true)) {
            val events = mutableListOf<FoldedMethodObservation>()
            val delivery = FoldedBodyDelivery { events += it }
            val graph = DefaultGraph.Builder().setAnalysisCompleteness(
                AnalysisCompleteness(AnalysisIdentity(), emptyMap(), emptyMap(), emptyMap())
            ).build()
            val load = {
                delivery.use {
                    it.deliver(evidence(), literalBody(7), verifyRematerialization = true)
                    if (fail) it.verifyFolding("sample.Duplicate.read()I", folds(calls = 2))
                    it.finish(graph, null)
                }
            }
            if (fail) assertFailsWith<IllegalStateException> { load() } else assertEquals(graph, load().graph)
            // Inspect retained references directly so the memory lifecycle check does not depend on GC timing.
            val indexes = FoldedBodyDelivery::class.java.declaredFields.filter { Map::class.java.isAssignableFrom(it.type) }
            assertEquals(2, indexes.size)
            indexes.forEach { field ->
                field.isAccessible = true
                assertTrue((field.get(delivery) as Map<*, *>).isEmpty(), "${field.name} retained build validation state")
            }
            assertEquals(1, events.size)
            assertEquals(1, events.single().evidence.folding!!.foldedCalls[0])
            val statement = events.single().snapshot!!.body.stmts.single() as JReturnStmt
            assertEquals(7, (statement.op as IntConstant).value)
        }
    }

    @Test
    fun `same body and fold evidence deliver once across independent materializations`() {
        val events = mutableListOf<FoldedMethodObservation>()
        val delivery = FoldedBodyDelivery { events += it }
        val original = literalBody(7)
        val rematerialized = literalBody(7)
        delivery.deliver(evidence(), original, verifyRematerialization = true)
        delivery.deliver(evidence(), rematerialized, verifyRematerialization = true)
        assertEquals(1, events.size)
        val returned = events.single().snapshot!!.body.stmts.single() as JReturnStmt
        assertEquals(7, (returned.op as IntConstant).value)
    }

    @Test
    fun `alternate identity and recovery implementations preserve duplicate validation`() {
        val events = mutableListOf<FoldedMethodObservation>()
        val original = object : MethodObservationEvidence by evidence() {
            override val recovery = BodyInterceptionEvidence("fallback", "unavailable instruction")
        }
        val alternate = object : MethodObservationEvidence by original {
            override val method = object : JvmMethodIdentity {
                override val owner = original.method.owner
                override val name = original.method.name
                override val descriptor = original.method.descriptor
            }
            override val recovery = object : BodyInterceptionEvidence {
                override val chain = original.recovery.chain
                override val reason = original.recovery.reason
            }
            override val folding = object : MethodFoldObservation {
                override val foldedCalls = mapOf(0 to 1)
                override val unsupportedReasons = emptyMap<Int, List<String>>()
                override val statementsBefore = 8
                override val statementsAfter = 6
            }
        }
        FoldedBodyDelivery { events += it }.use { delivery ->
            delivery.deliver(original, literalBody(7), verifyRematerialization = true)
            delivery.deliver(alternate, literalBody(7), verifyRematerialization = true)
        }
        assertEquals(1, events.size)
        assertEquals("fallback", events.single().evidence.recovery!!.chain)
    }

    @Test
    fun `equal metadata and fold counts cannot hide changed literals`() {
        val events = mutableListOf<FoldedMethodObservation>()
        val delivery = FoldedBodyDelivery { events += it }
        delivery.deliver(evidence(), literalBody(7), verifyRematerialization = true)
        val failure = assertFailsWith<IllegalStateException> {
            delivery.deliver(evidence(), literalBody(9), verifyRematerialization = true)
        }
        assertTrue(failure.message.orEmpty().contains("Conflicting rematerialized folded body"))
        assertEquals(1, events.size)
    }

    @Test
    fun `unpaired surrogate literals cannot collide with charset replacement characters`() {
        val delivery = FoldedBodyDelivery { }
        val method = evidence(descriptor = "()Ljava/lang/String;")
        delivery.deliver(method, stringBody("\uD800"), verifyRematerialization = true)
        val failure = assertFailsWith<IllegalStateException> {
            delivery.deliver(method, stringBody("?"), verifyRematerialization = true)
        }
        assertTrue(failure.message.orEmpty().contains("Conflicting rematerialized folded body"))
    }

    @Test
    fun `equal instructions and fold counts cannot hide reordered branch successors`() {
        val delivery = FoldedBodyDelivery { }
        delivery.deliver(evidence(), branchBody(false), verifyRematerialization = true)
        val failure = assertFailsWith<IllegalStateException> {
            delivery.deliver(evidence(), branchBody(true), verifyRematerialization = true)
        }
        assertTrue(failure.message.orEmpty().contains("Conflicting rematerialized folded body"))
    }

    @Test
    fun `fingerprints include dynamic bootstrap signatures arguments and handle tags`() {
        val original = dynamicBody(6, "bootstrap", 3)
        val fingerprint = snapshotFingerprint(original)
        assertNotEquals(fingerprint, snapshotFingerprint(dynamicBody(5, "bootstrap", 3)))
        assertNotEquals(fingerprint, snapshotFingerprint(dynamicBody(6, "otherBootstrap", 3)))
        assertNotEquals(fingerprint, snapshotFingerprint(dynamicBody(6, "bootstrap", 4)))
        assertEquals(fingerprint, snapshotFingerprint(dynamicBody(6, "bootstrap", 3)))
    }

    @Test
    fun `fingerprints include exceptional edge types switch representation and source positions`() {
        assertNotEquals(snapshotFingerprint(exceptionBody("java.lang.RuntimeException")),
            snapshotFingerprint(exceptionBody("java.lang.Exception")))
        assertNotEquals(snapshotFingerprint(switchBody(true)), snapshotFingerprint(switchBody(false)))
        assertNotEquals(snapshotFingerprint(literalBody(7, SimpleStmtPositionInfo(8))),
            snapshotFingerprint(literalBody(7, SimpleStmtPositionInfo(9))))
    }

    @Test
    fun `final fold evidence must still agree after later internal resolutions`() {
        val delivery = FoldedBodyDelivery { }
        delivery.deliver(evidence(), literalBody(7), verifyRematerialization = true)
        delivery.verifyFolding("sample.Duplicate.read()I", folds())
        val failure = assertFailsWith<IllegalStateException> {
            delivery.verifyFolding("sample.Duplicate.read()I", folds(calls = 2))
        }
        assertTrue(failure.message.orEmpty().contains("Conflicting final fold evidence"))
        assertFailsWith<IllegalStateException> { delivery.verifyFolding("sample.Duplicate.read()I", null) }
        delivery.verifyFolding("sample.NotObserved.read()I", folds(calls = 99))
    }

    @Test
    fun `a load without folds cannot acquire final fold evidence silently`() {
        val delivery = FoldedBodyDelivery { }
        delivery.deliver(evidence(folding = null), literalBody(7))
        delivery.verifyFolding("sample.Duplicate.read()I", null)
        assertFailsWith<IllegalStateException> { delivery.verifyFolding("sample.Duplicate.read()I", folds()) }
    }

    private fun snapshotFingerprint(body: Body): String = FoldedBodySnapshot.capture(body).semanticFingerprint()

    private fun evidence(
        folding: MethodFoldObservation? = folds(), descriptor: String = "()I"
    ): MethodObservationEvidence =
        MethodObservationEvidence(JvmMethodIdentity("sample.Duplicate", "read", descriptor),
            setOf(MethodModifier.PUBLIC, MethodModifier.STATIC), "duplicates.jar", ArtifactRole.LIBRARY,
            false, AnalysisStatus.ANALYZED, null, null, folding)

    private fun folds(calls: Int = 1) = MethodFoldObservation(mapOf(0 to calls), emptyMap(), 8, 6)

    private fun literalBody(value: Int, statementPosition: StmtPositionInfo = position): Body {
        val graph = MutableBlockControlFlowGraph()
        val returned = JReturnStmt(IntConstant.getInstance(value), statementPosition)
        graph.addNode(returned)
        graph.setStartingStmt(returned)
        return body(graph)
    }

    private fun stringBody(value: String): Body {
        val graph = MutableBlockControlFlowGraph()
        val returned = JReturnStmt(StringConstant(value, factory.getClassType("java.lang.String")), position)
        graph.addNode(returned)
        graph.setStartingStmt(returned)
        return body(graph, returns = "java.lang.String")
    }

    private fun branchBody(reverse: Boolean): Body {
        val local = Local("value", factory.getType("int"))
        val assigned = JAssignStmt(local, IntConstant.getInstance(0), position)
        val branch = JIfStmt(JEqExpr(local, IntConstant.getInstance(0)), position)
        val one = JReturnStmt(IntConstant.getInstance(1), position)
        val two = JReturnStmt(IntConstant.getInstance(2), position)
        val graph = MutableBlockControlFlowGraph()
        graph.addBlock(listOf(assigned, branch))
        graph.addNode(one)
        graph.addNode(two)
        graph.setEdges(branch, if (reverse) listOf(two, one) else listOf(one, two))
        graph.setStartingStmt(assigned)
        return body(graph, setOf(local))
    }

    private fun dynamicBody(tag: Int, bootstrapName: String, bootstrapArgument: Int): Body {
        val bootstrap = factory.getMethodSignature("sample.Bootstrap", bootstrapName, "java.lang.invoke.CallSite", emptyList())
        val signature = factory.getMethodSignature("sootup.dummy.InvokeDynamic", "read", "int", emptyList())
        val invoke = JInvokeStmt(JDynamicInvokeExpr(bootstrap, listOf(IntConstant.getInstance(bootstrapArgument)),
            signature, tag, emptyList()), position)
        val returned = JReturnStmt(IntConstant.getInstance(7), position)
        val graph = MutableBlockControlFlowGraph()
        graph.addBlock(listOf(invoke, returned))
        graph.setStartingStmt(invoke)
        return body(graph)
    }

    private fun exceptionBody(exceptionName: String): Body {
        val exception = Local("failure", factory.getType("java.lang.Throwable"))
        val handler = JIdentityStmt(exception, JCaughtExceptionRef(factory.getType("java.lang.Throwable")), position)
        val handlerReturn = JReturnStmt(IntConstant.getInstance(2), position)
        val call = JInvokeStmt(JStaticInvokeExpr(
            factory.getMethodSignature("sample.Reader", "read", "void", emptyList()), emptyList()), position)
        val returned = JReturnStmt(IntConstant.getInstance(1), position)
        val graph = MutableBlockControlFlowGraph()
        graph.addBlock(listOf(handler, handlerReturn))
        graph.addBlock(listOf(call, returned), mapOf(factory.getClassType(exceptionName) to handler))
        graph.setStartingStmt(call)
        return body(graph, setOf(exception))
    }

    private fun switchBody(table: Boolean): Body {
        val key = Local("key", factory.getType("int"))
        val assigned = JAssignStmt(key, IntConstant.getInstance(1), position)
        val switch = if (table) JSwitchStmt(key, 1, 2, position)
            else JSwitchStmt(key, listOf(IntConstant.getInstance(1), IntConstant.getInstance(2)), position)
        val exits = List(3) { JReturnStmt(IntConstant.getInstance(it), position) }
        val graph = MutableBlockControlFlowGraph()
        graph.addBlock(listOf(assigned, switch))
        exits.forEach(graph::addNode)
        graph.setEdges(switch, exits)
        graph.setStartingStmt(assigned)
        return body(graph, setOf(key))
    }

    private fun body(
        graph: MutableBlockControlFlowGraph, locals: Set<Local> = emptySet(), returns: String = "int"
    ): Body = Body.builder(graph)
            .setMethodSignature(factory.getMethodSignature("sample.Duplicate", "read", returns, emptyList()))
            .setLocals(LinkedHashSet(locals))
            .build()
}
