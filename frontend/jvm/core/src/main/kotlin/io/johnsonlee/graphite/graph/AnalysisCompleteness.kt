package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.input.AnalysisIdentity

private val CLASS_STATUSES = setOf(
    AnalysisStatus.ANALYZED, AnalysisStatus.EXCLUDED_BY_SCOPE, AnalysisStatus.NOT_EXPANDED, AnalysisStatus.RESOLUTION_FAILED
)

private val NEVER_A_METHODS = setOf(
    AnalysisStatus.EXCLUDED_BY_SCOPE, AnalysisStatus.NOT_EXPANDED, AnalysisStatus.EXTERNAL, AnalysisStatus.UNKNOWN_MEMBER
)

/**
 * What the build did with a class or a method, as a finite code a proof can act on. A node's
 * existence says nothing of the kind: a method's `ReturnNode` is created before its body is read,
 * and a body the build could not read leaves the same graph as a body with no effect.
 *
 * The codes a method can carry are the first seven; a class carries [ANALYZED],
 * [EXCLUDED_BY_SCOPE], [NOT_EXPANDED] or [RESOLUTION_FAILED]; [AnalysisCompleteness.statusOf]
 * adds [EXTERNAL] and [UNKNOWN_MEMBER] for a callee the graph has no record of.
 */
enum class AnalysisStatus {
    /** The body was read and every node and edge it produces is in the graph. */
    ANALYZED,

    /**
     * The body was read, its dataflow and calls are in the graph, but its branch scopes are not:
     * it had more statements than the control-flow pass takes.
     */
    ANALYZED_WITHOUT_CONTROL_FLOW,

    /** An abstract or interface method: dispatch reaches its overrides, it has no body of its own. */
    ABSTRACT,

    /** A native method: its effect needs a model. */
    NATIVE,

    /** A method without a body that is neither abstract nor native (a stub the input did not compile). */
    NO_BODY,

    /** The body's processing stopped part way (out of memory); what it produced before is in the graph. */
    INCOMPLETE,

    /** The frontend could not read the body or the class's methods; nothing of it is in the graph. */
    RESOLUTION_FAILED,

    /** The class was loaded for type resolution only: the configuration left its bodies out. */
    EXCLUDED_BY_SCOPE,

    /**
     * The class was reached from the application under a reachable scope, and left unread
     * because the scope's class budget ran out first: an incomplete expansion, never a class
     * with no effect.
     */
    NOT_EXPANDED,

    /** The class was not loaded at all: the JDK, a jar the build did not read, or a class no input holds. */
    EXTERNAL,

    /** The class was analysed and declares no such method. */
    UNKNOWN_MEMBER;

    /** Whether the graph holds everything the body produces: a proof may rely on its edges being complete. */
    val bodyComplete: Boolean get() = this == ANALYZED || this == ANALYZED_WITHOUT_CONTROL_FLOW
}

/** Where an artifact stood on the build's class path. */
enum class ArtifactRole {
    /** The application's own classes: the input jar or directory, `BOOT-INF/classes`, `WEB-INF/classes`. */
    APPLICATION,

    /** A library jar read beside the application: `BOOT-INF/lib`, `WEB-INF/lib`, a jar in the input directory. */
    LIBRARY,

    /** The Android platform jar an APK build reads. */
    PLATFORM
}

/**
 * An artifact the build read classes from. [multiRelease] says the archive carries
 * `META-INF/versions/` class entries, which this frontend does not select: every class is read
 * at its base version, as SootUp reads it, so a consumer whose runtime is a newer JDK knows the
 * bodies may differ from the ones that run.
 */
data class ArtifactEvidence(val role: ArtifactRole, val multiRelease: Boolean = false)

/** A class the build loaded: the artifact it was read from, when known, and what the build did with it. */
data class ClassEvidence(val origin: String?, val status: AnalysisStatus) {
    init {
        require(status in CLASS_STATUSES) { "a class is ANALYZED, EXCLUDED_BY_SCOPE, NOT_EXPANDED or RESOLUTION_FAILED, not $status" }
    }
}

/** A method's status and, for a failure, what the frontend said about it. */
data class MethodEvidence(val status: AnalysisStatus, val message: String? = null) {
    init {
        require(status !in NEVER_A_METHODS) { "a method's own status is never $status" }
    }
}

/**
 * What a call on a function value (`fn.apply(x)`, `supplier.get()`, Kotlin's `fn(x)`) could not
 * be resolved to. The call site itself is in the graph, with its interface callee; the graph has
 * no derived call site for the body it runs, and this says why, so that the absence is not read
 * as "calls nothing".
 */
enum class CallSiteGap {
    /** No function value is known to reach the receiver: from outside the analysed scope, or along a flow the adapter does not track. */
    DISPATCH_UNRESOLVED,

    /** More function values reach the receiver than the adapter follows (64), so it resolved none. */
    DISPATCH_SATURATED,

    /**
     * The call resolved to bodies the graph holds, and a value with no known origin in the
     * analysed scope reaches the receiver as well (a parameter no known caller passes a value
     * to, the result of a method outside the scope, a field nothing in the scope assigns), so an
     * alternative the scope cannot name may run too.
     */
    DISPATCH_PARTIAL
}

/**
 * The completeness evidence of a build: which artifacts it read and in which role, what it did
 * with every class it loaded, every method it did not analyse completely, and the duplicate
 * class definitions it saw. Relative to [scope], the options that decided which classes the
 * build read; it claims nothing about code outside them.
 *
 * [methods] holds only the methods whose status is not [AnalysisStatus.ANALYZED], keyed by
 * [MethodDescriptor.identity]: every method of an analysed class the graph holds and this map
 * does not is analysed. A graph built by a frontend before this evidence existed has none, and
 * [Graph.analysisCompleteness] answers `null` for it rather than a status it cannot know.
 *
 * [duplicateClasses] maps a class name to every artifact that defines it, in class-path order,
 * when more than one does; the graph holds the first one's definition.
 *
 * [callSiteGaps] maps the node id of a call on a function value to the reason its dispatch is not
 * complete, see [CallSiteGap]: it dispatches to no body, or to bodies beside an alternative the
 * scope cannot name; a call on a function value absent here resolved to every body it runs that
 * the analysed scope holds.
 */
data class AnalysisCompleteness(
    val scope: AnalysisIdentity,
    val artifacts: Map<String, ArtifactEvidence>,
    val classes: Map<String, ClassEvidence>,
    val methods: Map<String, MethodEvidence>,
    val duplicateClasses: Map<String, List<String>> = emptyMap(),
    val callSiteGaps: Map<Int, CallSiteGap> = emptyMap()
) {
    /** The status of the class [className]: [AnalysisStatus.EXTERNAL] when the build never loaded it. */
    fun classStatus(className: String): AnalysisStatus = classes[className]?.status ?: AnalysisStatus.EXTERNAL

    /**
     * The status of [method], given whether the graph holds it ([inGraph], see [Graph.hasMethod]):
     * its own record when it has one; else, when the graph holds it, [AnalysisStatus.ANALYZED];
     * else its class's status, or [AnalysisStatus.UNKNOWN_MEMBER] when the class was analysed
     * and declares no such method.
     */
    fun statusOf(method: MethodDescriptor, inGraph: Boolean): AnalysisStatus {
        val own = methods[method.identity]?.status
        return when {
            own != null -> own
            inGraph -> AnalysisStatus.ANALYZED
            else -> classStatus(method.declaringClass.className).let {
                if (it == AnalysisStatus.ANALYZED) AnalysisStatus.UNKNOWN_MEMBER else it
            }
        }
    }
}

/**
 * The status of [method] in this graph, or `null` when the graph carries no completeness
 * evidence (built by a frontend before it existed), which a proof must treat as unknown.
 */
fun Graph.analysisStatus(method: MethodDescriptor): AnalysisStatus? =
    analysisCompleteness()?.statusOf(method, hasMethod(method))
