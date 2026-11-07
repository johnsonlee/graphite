package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.analysis.PendingDispatch

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.graph.AnalysisCompleteness
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.ArtifactEvidence
import io.johnsonlee.graphite.graph.ArtifactRole
import io.johnsonlee.graphite.graph.CallSiteGap
import io.johnsonlee.graphite.graph.MethodEvidence
import io.johnsonlee.graphite.input.AnalysisIdentity
import org.objectweb.asm.Type as AsmType
import sootup.core.types.ClassType

/** Records class, method and call-site evidence for analysis completeness. */
internal class AnalysisEvidence(private val context: SootUpAdapter) {
    private val slotTargets = context.slotTargets
    private val slotCalls = context.slotCalls
    private var openSlots
        get() = context.openSlots
        set(value) { context.openSlots = value }
    private var lostSlots
        get() = context.lostSlots
        set(value) { context.lostSlots = value }
    private val classEvidence = context.classEvidence
    private val methodEvidence = context.methodEvidence
    private val duplicateClasses = context.duplicateClasses
    private val multiReleaseArtifacts = context.multiReleaseArtifacts
    private var resolutionFailures
        get() = context.resolutionFailures
        set(value) { context.resolutionFailures = value }
    private val callSiteGaps = context.callSiteGaps
    private val untrackedDispatches = context.untrackedDispatches
    private val view = context.view
    private val config = context.config
    private val inputLocationSources = context.inputLocationSources
    private val artifactRoles = context.artifactRoles
    private val singleArtifactSource = context.singleArtifactSource
    private val artifactOrder = context.artifactOrder
    private val descriptors get() = context.descriptors

    /**
     * After the dispatch fixpoint, every call on a function value that resolved to no body, with
     * the reason: the receiver's holder saturated, or nothing is known to reach it.
     */
    fun collectDispatchGaps(lateClassesRead: Boolean) {
        for (entry in slotCalls.int2ObjectEntrySet()) {
            val lost = slotTargets[entry.intKey] === SATURATED_TARGETS || lostSlots?.get(entry.intKey) == true
            for (pending in entry.value) {
                val unexpanded = lateClassesRead && slotTargets[entry.intKey]?.any { it !in pending.resolved } == true
                val gap = dispatchGap(pending, lost, open = openSlots?.get(entry.intKey) == true || unexpanded) ?: continue
                callSiteGaps[pending.callSite.id.value] = gap
            }
        }
        for (untracked in untrackedDispatches) {
            val gap = dispatchGap(untracked.pending, untracked.lost, untracked.open) ?: continue
            callSiteGaps[untracked.pending.callSite.id.value] = gap
        }
        untrackedDispatches.clear()
    }

    /**
     * The gap of a call on a function value, or null when every alternative resolved: [lost]
     * when a saturated holder reaches the receiver (its targets were lost: the call is saturated
     * when nothing resolved, partial when something did), [open] when a value with no known
     * origin reaches it (partial when something resolved).
     */
    fun dispatchGap(pending: PendingDispatch, lost: Boolean, open: Boolean): CallSiteGap? = when {
        lost && pending.resolved.isEmpty() -> CallSiteGap.DISPATCH_SATURATED
        pending.resolved.isEmpty() -> CallSiteGap.DISPATCH_UNRESOLVED
        lost || open -> CallSiteGap.DISPATCH_PARTIAL
        else -> null
    }

    /**
     * The evidence of this build: every source in its role, every class of the view with what
     * the build did with it, the methods not analysed completely, and the duplicate definitions.
     * The scope is the config's; the platform jar an APK build reads is named by its SDK root
     * only, so its digest is not part of it (the CLI's fold provenance carries that).
     */
    fun analysisCompleteness(): AnalysisCompleteness {
        val names = LinkedHashSet(inputLocationSources.values)
        singleArtifactSource?.let(names::add)
        val artifacts = names.associateWith { name ->
            val role = artifactRoles[name] ?: if (name == singleArtifactSource) ArtifactRole.APPLICATION else ArtifactRole.LIBRARY
            ArtifactEvidence(role, name in multiReleaseArtifacts)
        }
        return AnalysisCompleteness(
            AnalysisIdentity.of(config), artifacts, classEvidence, methodEvidence, orderedDuplicates(), callSiteGaps
        )
    }

    /**
     * The duplicate definitions with their sources in the runtime's order: the application's
     * own sources first, as the loader places them, then the libraries in the order the input
     * declares ([artifactOrder], which names the libraries only), then the libraries it does not
     * name in listing order. Every listed archive is ranked, loaded or not: an archive the build
     * did not load has no role, and is a library all the same (the application's sources are
     * always loaded). The first source is the one the runtime would load; the view's choice is
     * the class's origin.
     */
    fun orderedDuplicates(): Map<String, List<String>> {
        if (artifactOrder.isEmpty()) return duplicateClasses
        val rank = artifactOrder.withIndex().associate { (index, name) -> name to index }
        fun precedence(source: String): Int =
            if (artifactRoles[source] == ArtifactRole.APPLICATION) -1 else rank[source] ?: Int.MAX_VALUE
        return duplicateClasses.mapValues { (_, sources) -> sources.sortedBy(::precedence) }
    }

    /**
     * Record [status] for [method]: a method the build analysed completely has no record, so
     * the evidence holds only the exceptions; a record an earlier resolution of the same method
     * left (a `<clinit>` the enum pass could not read) is dropped when the method pass reads it.
     */
    fun recordMethodEvidence(method: MethodDescriptor, status: AnalysisStatus, message: String?) {
        when (status) {
            // Only a resolution failure can precede a later, successful read of the same method,
            // so only then is an identity spelled on the hot path.
            AnalysisStatus.ANALYZED -> if (resolutionFailures > 0) methodEvidence.remove(method.identity)
            AnalysisStatus.RESOLUTION_FAILED -> {
                resolutionFailures++
                methodEvidence[method.identity] = MethodEvidence(status, message)
            }
            else -> methodEvidence[method.identity] = MethodEvidence(status, message)
        }
    }

    /** A method whose body the frontend could not resolve, named by its class, name and JVM descriptor. */
    fun recordResolutionFailure(declaringClass: ClassType, name: String, desc: String, message: String?) {
        val factory = view.identifierFactory
        val signature = factory.getMethodSignature(
            declaringClass,
            name,
            factory.getType(AsmType.getReturnType(desc).className),
            AsmType.getArgumentTypes(desc).map { factory.getType(it.className) }
        )
        recordMethodEvidence(descriptors.toMethodDescriptor(signature), AnalysisStatus.RESOLUTION_FAILED, message)
    }
}
