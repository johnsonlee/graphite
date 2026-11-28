package io.johnsonlee.graphite.ir

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.AnalysisCompleteness
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.CallSiteGap
import io.johnsonlee.graphite.graph.ClassEvidence
import io.johnsonlee.graphite.graph.MethodEvidence
import io.johnsonlee.graphite.input.AnalysisIdentity
import io.johnsonlee.graphite.ir.v1.Completeness
import io.johnsonlee.graphite.ir.v1.MethodRef

/**
 * The completeness evidence a frontend put in its trailer, as the graph records it (`Graph.analysisCompleteness`). The scope
 * and the artifact roles are the JVM frontend's own notions and stay empty. Evidence is what a proof relies on, so nothing in
 * it is guessed or repaired: an unnamed class, a status or gap the reader does not know, a status that is not a class's or
 * not a method's, a key named twice and a gap on anything but a call site of this stream are all refused.
 *
 * [method] resolves a method reference against the stream's strings; [callSite] answers the node id of an IR id when that
 * node is a call site of the stream, and `null` otherwise.
 */
internal fun readCompleteness(
    evidence: Completeness,
    method: (MethodRef) -> MethodDescriptor,
    callSite: (Int) -> NodeId?
): AnalysisCompleteness =
    AnalysisCompleteness(
        scope = AnalysisIdentity(),
        artifacts = emptyMap(),
        classes = classes(evidence),
        methods = methods(evidence, method),
        callSiteGaps = gaps(evidence, callSite)
    )

private fun classes(evidence: Completeness): Map<String, ClassEvidence> {
    val classes = LinkedHashMap<String, ClassEvidence>()
    for (entry in evidence.classesList) {
        if (entry.className.isEmpty()) malformed("holds a class without a name")
        val subject = "class ${entry.className}"
        val recorded = accepted(subject) { ClassEvidence(if (entry.hasOrigin()) entry.origin else null, status(entry.status, subject)) }
        if (classes.put(entry.className, recorded) != null) malformed("names $subject twice")
    }
    return classes
}

private fun methods(evidence: Completeness, method: (MethodRef) -> MethodDescriptor): Map<String, MethodEvidence> {
    val methods = LinkedHashMap<String, MethodEvidence>()
    for (entry in evidence.methodsList) {
        if (!entry.hasMethod()) malformed("holds a method status without a method")
        val identity = method(entry.method).identity
        val subject = "method $identity"
        val recorded = accepted(subject) {
            MethodEvidence(status(entry.status, subject), if (entry.hasMessage()) entry.message else null)
        }
        if (methods.put(identity, recorded) != null) malformed("names $subject twice")
    }
    return methods
}

private fun gaps(evidence: Completeness, callSite: (Int) -> NodeId?): Map<Int, CallSiteGap> {
    val gaps = LinkedHashMap<Int, CallSiteGap>()
    for (entry in evidence.callSiteGapsList) {
        val named = entry.callSite.toUInt()
        val site = callSite(entry.callSite) ?: malformed("records a dispatch gap on node $named, which is not a call site")
        val gap = CallSiteGap.entries.firstOrNull { it.name == entry.gap.name }
            ?: malformed("records no known dispatch gap on call site $named")
        if (gaps.put(site.value, gap) != null) malformed("records two dispatch gaps on call site $named")
    }
    return gaps
}

private fun status(status: io.johnsonlee.graphite.ir.v1.AnalysisStatus, subject: String): AnalysisStatus =
    AnalysisStatus.entries.firstOrNull { it.name == status.name } ?: malformed("records no known status for $subject")

/** The model's own rules (a class is never ABSTRACT, a method never EXCLUDED_BY_SCOPE), reported as a format error. */
private fun <T> accepted(subject: String, build: () -> T): T =
    try {
        build()
    } catch (e: IllegalArgumentException) {
        throw IrFormatException("IR completeness for $subject: ${e.message}", e)
    }

private fun malformed(what: String): Nothing = throw IrFormatException("IR completeness $what")
