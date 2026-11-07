package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.ArtifactRole
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.FoldReport
import io.johnsonlee.graphite.input.ObservedJavaLoad
import io.johnsonlee.graphite.input.JvmMethodIdentity
import io.johnsonlee.graphite.input.BodyInterceptionEvidence
import io.johnsonlee.graphite.input.MethodFoldObservation
import java.util.Collections
import sootup.core.model.Body
import sootup.core.model.MethodModifier

/**
 * A receipt for one method's observation. [status] describes local graph extraction, not final
 * dispatch completeness. Reconcile with [ObservedJavaLoad.completeness] after load returns.
 * Null [modifiers] or [application] means declaration resolution failed before they were known.
 * [origin] joins classes[method.owner].origin and artifacts[origin] in final completeness.
 */
interface MethodObservationEvidence {
    val method: JvmMethodIdentity
    val modifiers: Set<MethodModifier>?
    val origin: String?
    val role: ArtifactRole?
    val application: Boolean?
    val status: AnalysisStatus
    val message: String?
    val recovery: BodyInterceptionEvidence?
    val folding: MethodFoldObservation?
}

@Suppress("LongParameterList")
internal fun createMethodObservationEvidence(
    method: JvmMethodIdentity,
    modifiers: Set<MethodModifier>?,
    origin: String?,
    role: ArtifactRole?,
    application: Boolean?,
    status: AnalysisStatus,
    message: String?,
    recovery: BodyInterceptionEvidence?,
    folding: MethodFoldObservation?
): MethodObservationEvidence = SootUpMethodObservationEvidence(
    method, modifiers, origin, role, application, status, message, recovery, folding
)

@Suppress("LongParameterList")
private class SootUpMethodObservationEvidence(
    override val method: JvmMethodIdentity,
    modifiers: Set<MethodModifier>?,
    override val origin: String?,
    override val role: ArtifactRole?,
    override val application: Boolean?,
    override val status: AnalysisStatus,
    override val message: String?,
    override val recovery: BodyInterceptionEvidence?,
    override val folding: MethodFoldObservation?
) : MethodObservationEvidence {
    override val modifiers: Set<MethodModifier>? = modifiers?.let { Collections.unmodifiableSet(LinkedHashSet(it)) }
}

internal fun MethodObservationEvidence.agreesWith(other: MethodObservationEvidence): Boolean =
    method.owner == other.method.owner && method.name == other.method.name && method.descriptor == other.method.descriptor &&
        modifiers == other.modifiers && origin == other.origin && role == other.role &&
        application == other.application && status == other.status && message == other.message &&
        recovery?.chain == other.recovery?.chain && recovery?.reason == other.recovery?.reason &&
        (if (folding == null) other.folding == null else other.folding?.let { folding?.agreesWith(it) } == true)

/**
 * One method of this load, delivered once, including no-body declarations and known failures.
 * A non-null snapshot is owned, immutable and safe to retain after the callback and load.
 * Null is an explicit unavailable body, never an empty, effect-free successful body.
 */
interface FoldedMethodObservation {
    val evidence: MethodObservationEvidence
    val snapshot: FoldedBodySnapshot?
}

private class SootUpFoldedMethodObservation(
    override val evidence: MethodObservationEvidence,
    override val snapshot: FoldedBodySnapshot?
) : FoldedMethodObservation

/**
 * Invocation-local, synchronous observation, independent of GraphiteExtension. No discovery,
 * view lookup, selection query, interception or body expansion is performed for observation.
 * The observer decides which snapshots and evidence to retain. Graphite keeps validation state
 * only for the duration of this load.
 *
 * Every delivered observation is provisional until loadObserved returns successfully. Any
 * observer or snapshot failure aborts the load; discard that invocation's provisional output.
 * A method's body can be rematerialized internally without redelivery. Callback order is not
 * identity. Separate loads must use separate mutable collectors, including base/head loads.
 */
fun interface FoldedBodyObserver {
    fun observe(method: FoldedMethodObservation)
}

/** Internal delivery state exists only for an opted-in invocation. */
internal class FoldedBodyDelivery(private val observer: FoldedBodyObserver) : AutoCloseable {
    private val observations = LinkedHashMap<String, MethodObservationEvidence>()
    private val bodyFingerprints = HashMap<String, String?>()

    fun deliver(evidence: MethodObservationEvidence, body: Body?, verifyRematerialization: Boolean = false) {
        val identity = evidence.method.identity
        observations[identity]?.let { prior ->
            // JavaView's first enumeration can yield its selected cached class once per
            // duplicate classpath entry. Preserve graph behavior but deliver that definition once.
            check(prior.agreesWith(evidence)) { "Conflicting folded-body observations: $identity" }
            if (verifyRematerialization || identity in bodyFingerprints) {
                check(identity in bodyFingerprints) { "Missing original folded-body fingerprint: $identity" }
                val current = body?.let { FoldedBodySnapshot.capture(it).semanticFingerprint() }
                check(bodyFingerprints[identity] == current) { "Conflicting rematerialized folded body: $identity" }
            }
            return
        }
        check(!evidence.status.bodyComplete || body != null) { "Analyzed method has no captured body: $identity" }
        val snapshot = body?.let(FoldedBodySnapshot::capture)
        val fingerprint = if (verifyRematerialization) snapshot?.semanticFingerprint() else null
        // Keep capture and callback failures outside the adapter's legacy OOM recovery.
        observer.observe(SootUpFoldedMethodObservation(evidence, snapshot))
        observations[identity] = evidence
        if (verifyRematerialization) bodyFingerprints[identity] = fingerprint
    }

    fun verifyFolding(identity: String, current: MethodFoldObservation?) {
        val prior = observations[identity]?.folding ?: run {
            if (identity in observations) {
                check(current == null) { "Conflicting final fold evidence: $identity" }
            }
            return
        }
        check(current?.let(prior::agreesWith) == true) { "Conflicting final fold evidence: $identity" }
    }

    override fun close() {
        observations.clear()
        bodyFingerprints.clear()
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
                deliver(createMethodObservationEvidence(method, null, origin, completeness.artifacts[origin]?.role,
                    null, failure.status, failure.message, null, null), null)
            }
        }
        graph.methods(MethodPattern()).forEach { method ->
            check(method.identity in observations) { "Missing body observation: ${method.identity}" }
        }
        return ObservedJavaLoad(graph, report)
    }
}
