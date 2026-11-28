package io.johnsonlee.graphite.ir

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.AnalysisCompleteness
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.CallSiteGap
import io.johnsonlee.graphite.graph.ClassEvidence
import io.johnsonlee.graphite.graph.MethodEvidence
import io.johnsonlee.graphite.graph.analysisStatus
import io.johnsonlee.graphite.input.AnalysisIdentity
import io.johnsonlee.graphite.ir.v1.AnalysisStatus as IrStatus
import io.johnsonlee.graphite.ir.v1.CallSite
import io.johnsonlee.graphite.ir.v1.CallSiteDispatchGap
import io.johnsonlee.graphite.ir.v1.ClassCompleteness
import io.johnsonlee.graphite.ir.v1.Completeness
import io.johnsonlee.graphite.ir.v1.DispatchGap
import io.johnsonlee.graphite.ir.v1.IntConstant
import io.johnsonlee.graphite.ir.v1.MethodCompleteness
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The completeness evidence of a Graph IR trailer: what a frontend other than the JVM's says it analysed becomes the graph's
 * `analysisCompleteness`, and evidence that cannot be taken at its word is refused, never repaired.
 */
class IrCompletenessTest {

    private val run = MethodDescriptor(TypeDescriptor("App.Runner"), "run", emptyList(), TypeDescriptor("void"))
    private val perform = MethodDescriptor(TypeDescriptor("App.Performing"), "perform", emptyList(), TypeDescriptor("void"))
    private val legacy = MethodDescriptor(TypeDescriptor("LegacyStore"), "flush", listOf(TypeDescriptor("int")), TypeDescriptor("void"))

    /** A constant (IR id 0) and two calls from `run` (IR ids 1 and 2), with every method the evidence names declared. */
    private fun stream(): IrStream {
        val ir = IrStream().header()
        val runRef = ir.method("App.Runner", "run")
        val performRef = ir.method("App.Performing", "perform")
        ir.method("LegacyStore", "flush", listOf("int"))
        return ir.node(0) { setIntConstant(IntConstant.newBuilder().setValue(7)) }
            .node(1) { setCallSite(CallSite.newBuilder().setCaller(runRef).setCallee(performRef).setLine(3)) }
            .node(2) { setCallSite(CallSite.newBuilder().setCaller(runRef).setCallee(runRef).setLine(4)) }
    }

    private fun classOf(name: String, status: IrStatus, origin: String? = null) =
        ClassCompleteness.newBuilder().setClassName(name).setStatus(status).apply { origin?.let(::setOrigin) }

    private fun IrStream.methodOf(method: MethodDescriptor, status: IrStatus, message: String? = null) =
        MethodCompleteness.newBuilder()
            .setMethod(method(method.declaringClass.className, method.name, method.parameterTypes.map { it.className }))
            .setStatus(status).apply { message?.let(::setMessage) }

    private fun gapOn(callSite: Int, gap: DispatchGap) = CallSiteDispatchGap.newBuilder().setCallSite(callSite).setGap(gap)

    private fun read(bytes: ByteArray) = IrReader().read(ByteArrayInputStream(bytes)).first

    @Test
    fun `a stream without evidence leaves the graph answering not recorded`() {
        val graph = read(stream().bytes())
        assertNull(graph.analysisCompleteness())
        assertNull(graph.analysisStatus(run))
    }

    @Test
    fun `evidence that is present and empty is recorded as such`() {
        assertEquals(
            AnalysisCompleteness(AnalysisIdentity(), emptyMap(), emptyMap(), emptyMap()),
            read(stream().bytes(Completeness.newBuilder())).analysisCompleteness()
        )
    }

    @Test
    fun `classes, method statuses and dispatch gaps become the graph's evidence`() {
        val ir = stream()
        val graph = read(
            ir.bytes(
                Completeness.newBuilder()
                    .addClasses(classOf("App.Runner", IrStatus.ANALYZED, origin = "App"))
                    .addClasses(classOf("App.Broken", IrStatus.RESOLUTION_FAILED))
                    .addMethods(ir.methodOf(perform, IrStatus.ABSTRACT, message = "a protocol requirement"))
                    .addMethods(ir.methodOf(legacy, IrStatus.NO_BODY))
                    .addCallSiteGaps(gapOn(1, DispatchGap.DISPATCH_UNRESOLVED))
                    .addCallSiteGaps(gapOn(2, DispatchGap.DISPATCH_PARTIAL))))

        assertEquals(
            AnalysisCompleteness(
                scope = AnalysisIdentity(),
                artifacts = emptyMap(),
                classes = mapOf(
                    "App.Runner" to ClassEvidence("App", AnalysisStatus.ANALYZED),
                    "App.Broken" to ClassEvidence(null, AnalysisStatus.RESOLUTION_FAILED)),
                methods = mapOf(
                    perform.identity to MethodEvidence(AnalysisStatus.ABSTRACT, "a protocol requirement"),
                    legacy.identity to MethodEvidence(AnalysisStatus.NO_BODY)),
                // A node's id in the graph is its IR id plus one.
                callSiteGaps = mapOf(2 to CallSiteGap.DISPATCH_UNRESOLVED, 3 to CallSiteGap.DISPATCH_PARTIAL)),
            graph.analysisCompleteness())
        assertEquals(AnalysisStatus.ABSTRACT, graph.analysisStatus(perform))
        assertEquals(AnalysisStatus.NO_BODY, graph.analysisStatus(legacy))
        // A class the evidence does not name was not part of the build.
        val outside = MethodDescriptor(TypeDescriptor("Foundation.NSString"), "length", emptyList(), TypeDescriptor("int"))
        assertEquals(AnalysisStatus.EXTERNAL, graph.analysisStatus(outside))
    }

    @Test
    fun `evidence that cannot be taken at its word is refused`() {
        fun refused(build: (IrStream) -> Completeness.Builder): String {
            val ir = stream()
            return assertFailsWith<IrFormatException> { read(ir.bytes(build(ir))) }.message!!
        }
        val evidence = { Completeness.newBuilder() }
        assertEquals(
            "IR completeness holds a class without a name",
            refused { evidence().addClasses(classOf("", IrStatus.ANALYZED)) })
        assertEquals(
            "IR completeness records no known status for class App.Runner",
            refused { evidence().addClasses(ClassCompleteness.newBuilder().setClassName("App.Runner")) })
        assertEquals(
            "IR completeness records no known status for class App.Runner",
            refused { evidence().addClasses(ClassCompleteness.newBuilder().setClassName("App.Runner").setStatusValue(99)) })
        assertEquals(
            "IR completeness for class App.Runner: a class is ANALYZED, EXCLUDED_BY_SCOPE, NOT_EXPANDED or RESOLUTION_FAILED, " +
                "not ABSTRACT",
            refused { evidence().addClasses(classOf("App.Runner", IrStatus.ABSTRACT)) })
        assertEquals(
            "IR completeness names class App.Runner twice",
            refused {
                evidence().addClasses(classOf("App.Runner", IrStatus.ANALYZED)).addClasses(classOf("App.Runner", IrStatus.ANALYZED))
            })
        assertEquals(
            "IR completeness holds a method status without a method",
            refused { evidence().addMethods(MethodCompleteness.newBuilder().setStatus(IrStatus.ABSTRACT)) })
        assertEquals(
            "IR completeness records no known status for method ${perform.identity}",
            refused { it.methodOf(perform, IrStatus.ANALYSIS_STATUS_UNSPECIFIED).let(evidence()::addMethods) })
        assertEquals(
            "IR completeness for method ${perform.identity}: a method's own status is never EXCLUDED_BY_SCOPE",
            refused { it.methodOf(perform, IrStatus.EXCLUDED_BY_SCOPE).let(evidence()::addMethods) })
        assertEquals(
            "IR completeness names method ${perform.identity} twice",
            refused { ir ->
                evidence().addMethods(ir.methodOf(perform, IrStatus.ABSTRACT)).addMethods(ir.methodOf(perform, IrStatus.NO_BODY))
            })
        // A gap is a statement about a call site of this stream: not about a constant, a node the stream never
        // declared, or an id past the signed range.
        for (node in listOf(0, 3, -1)) {
            assertEquals(
                "IR completeness records a dispatch gap on node ${node.toUInt()}, which is not a call site",
                refused { evidence().addCallSiteGaps(gapOn(node, DispatchGap.DISPATCH_PARTIAL)) })
        }
        assertEquals(
            "IR completeness records no known dispatch gap on call site 1",
            refused { evidence().addCallSiteGaps(CallSiteDispatchGap.newBuilder().setCallSite(1)) })
        assertEquals(
            "IR completeness records two dispatch gaps on call site 1",
            refused {
                evidence().addCallSiteGaps(gapOn(1, DispatchGap.DISPATCH_PARTIAL)).addCallSiteGaps(gapOn(1, DispatchGap.DISPATCH_SATURATED))
            })
    }
}
