package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.graph.AnalysisCompleteness
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.ArtifactRole
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.FoldReport
import java.util.Collections
import sootup.core.model.Body
import sootup.core.model.MethodModifier

/** A complete JVM identity, including the return type (unlike a display signature). */
data class JvmMethodIdentity(val owner: String, val name: String, val descriptor: String) {
    val identity: String get() = "$owner.$name$descriptor"

    internal companion object {
        fun of(method: MethodDescriptor): JvmMethodIdentity =
            JvmMethodIdentity(method.declaringClass.className, method.name, method.descriptor)
    }
}

/** Interception recovered a body using [chain], rather than completing the requested pipeline. */
data class BodyInterceptionEvidence(val chain: String, val reason: String)

/**
 * Folding evidence for the exact body consumed by graph extraction, keyed by rule index in
 * this load's FoldReport. The enclosing method's JVM identity disambiguates covariant bridges.
 * Counts are zero/absent when a rule made no replacement; unsupported reasons are not success.
 */
class MethodFoldObservation internal constructor(
    foldedCalls: Map<Int, Int>,
    unsupportedReasons: Map<Int, List<String>>,
    val statementsBefore: Int?,
    val statementsAfter: Int?
) {
    val foldedCalls: Map<Int, Int> = Collections.unmodifiableMap(LinkedHashMap(foldedCalls))
    val unsupportedReasons: Map<Int, List<String>> = Collections.unmodifiableMap(
        unsupportedReasons.mapValues { (_, reasons) -> Collections.unmodifiableList(ArrayList(reasons)) }
    )

    internal fun agreesWith(other: MethodFoldObservation): Boolean =
        foldedCalls == other.foldedCalls && unsupportedReasons == other.unsupportedReasons &&
            statementsBefore == other.statementsBefore && statementsAfter == other.statementsAfter
}

/**
 * A receipt for one method's observation. [status] describes local graph extraction, not final
 * dispatch completeness. Reconcile with [ObservedJavaLoad.completeness] after load returns.
 * Null [modifiers] or [application] means declaration resolution failed before they were known.
 * [origin] joins classes[method.owner].origin and artifacts[origin] in final completeness.
 */
@Suppress("LongParameterList")
class MethodObservationEvidence internal constructor(
    val method: JvmMethodIdentity,
    modifiers: Set<MethodModifier>?,
    val origin: String?,
    val role: ArtifactRole?,
    val application: Boolean?,
    val status: AnalysisStatus,
    val message: String?,
    val recovery: BodyInterceptionEvidence?,
    val folding: MethodFoldObservation?
) {
    val modifiers: Set<MethodModifier>? = modifiers?.let { Collections.unmodifiableSet(LinkedHashSet(it)) }

    internal fun agreesWith(other: MethodObservationEvidence): Boolean =
        method == other.method && modifiers == other.modifiers && origin == other.origin && role == other.role &&
            application == other.application && status == other.status && message == other.message && recovery == other.recovery &&
            (if (folding == null) other.folding == null else other.folding?.let(folding::agreesWith) == true)
}

/**
 * One method of this load, delivered once, including no-body declarations and known failures.
 * A non-null snapshot is owned, immutable and safe to retain after the callback and load.
 * Null is an explicit unavailable body, never an empty, effect-free successful body.
 */
class FoldedMethodObservation internal constructor(
    val evidence: MethodObservationEvidence,
    val snapshot: FoldedBodySnapshot?
)

/**
 * Invocation-local, synchronous observation, independent of GraphiteExtension. No discovery,
 * view lookup, selection query, interception or body expansion is performed for observation.
 * The observer decides which snapshots to retain. Graphite retains receipts, not bodies.
 *
 * Every delivered observation is provisional until loadObserved returns successfully. Any
 * observer or snapshot failure aborts the load; discard that invocation's provisional output.
 * A method's body can be rematerialized internally without redelivery. Callback order is not
 * identity. Separate loads must use separate mutable collectors, including base/head loads.
 */
fun interface FoldedBodyObserver {
    fun observe(method: FoldedMethodObservation)
}

/**
 * Final evidence of the same build that streamed the observations. The graph (including its
 * final callSiteGaps) remains authoritative. A successful body capture does not erase gaps or
 * promote a library method to an application root. ANALYZED_WITHOUT_CONTROL_FLOW retains its
 * normal meaning: body/dataflow read, graph branch scopes omitted.
 *
 * Methods excluded by scope or budget are not resolved for callbacks: consult completeness's
 * class statuses. A class whose methods could not be enumerated has class RESOLUTION_FAILED;
 * its missing method callbacks never establish successful capture. EXTERNAL/UNKNOWN_MEMBER
 * retain their existing meaning. This result adds no persistent graph format or IR storage.
 */
class ObservedJavaLoad internal constructor(
    val graph: Graph,
    val foldReport: FoldReport?,
    observations: Map<String, MethodObservationEvidence>
) {
    val observations: Map<String, MethodObservationEvidence> = Collections.unmodifiableMap(LinkedHashMap(observations))
    val completeness: AnalysisCompleteness = requireNotNull(graph.analysisCompleteness())
}

/** Internal delivery state exists only for an opted-in invocation. */
internal class FoldedBodyDelivery(private val observer: FoldedBodyObserver) {
    private val observations = LinkedHashMap<String, MethodObservationEvidence>()

    fun deliver(evidence: MethodObservationEvidence, body: Body?) {
        val identity = evidence.method.identity
        observations[identity]?.let { prior ->
            // JavaView's first enumeration can yield its selected cached class once per
            // duplicate classpath entry. Preserve graph behavior but deliver that definition once.
            check(prior.agreesWith(evidence)) { "Conflicting folded-body observations: $identity" }
            return
        }
        check(!evidence.status.bodyComplete || body != null) { "Analyzed method has no captured body: $identity" }
        val snapshot = body?.let(FoldedBodySnapshot::capture)
        // Keep capture and callback failures outside the adapter's legacy OOM recovery.
        observer.observe(FoldedMethodObservation(evidence, snapshot))
        observations[identity] = evidence
    }

    fun finish(graph: Graph, report: FoldReport?): ObservedJavaLoad {
        val completeness = requireNotNull(graph.analysisCompleteness())
        // Resolution can fail before processMethod (or before enumerating any method). Only
        // identifiable failed methods get an event; class failures stay explicit in final evidence.
        completeness.methods.forEach { (identity, failure) ->
            if (identity !in observations) {
                check(failure.status == AnalysisStatus.RESOLUTION_FAILED) { "Missing body observation: $identity" }
                val descriptorStart = identity.indexOf('(')
                val nameStart = identity.lastIndexOf('.', descriptorStart)
                val method = JvmMethodIdentity(
                    identity.substring(0, nameStart), identity.substring(nameStart + 1, descriptorStart),
                    identity.substring(descriptorStart)
                )
                val origin = completeness.classes[method.owner]?.origin
                deliver(MethodObservationEvidence(method, null, origin, completeness.artifacts[origin]?.role,
                    null, failure.status, failure.message, null, null), null)
            }
        }
        graph.methods(MethodPattern()).forEach { method ->
            check(method.identity in observations) { "Missing body observation: ${method.identity}" }
        }
        return ObservedJavaLoad(graph, report, observations)
    }
}
