package io.johnsonlee.graphite.input

import io.johnsonlee.graphite.core.MethodDescriptor
import java.util.Collections

/** A complete JVM identity, including the return type (unlike a display signature). */
interface JvmMethodIdentity {
    val owner: String
    val name: String
    val descriptor: String
    val identity: String get() = "$owner.$name$descriptor"

    companion object {
        operator fun invoke(owner: String, name: String, descriptor: String): JvmMethodIdentity =
            DefaultJvmMethodIdentity(owner, name, descriptor)

        fun of(method: MethodDescriptor): JvmMethodIdentity =
            invoke(method.declaringClass.className, method.name, method.descriptor)
    }
}

private data class DefaultJvmMethodIdentity(
    override val owner: String,
    override val name: String,
    override val descriptor: String
) : JvmMethodIdentity

/** Interception recovered a body using [chain] instead of completing the requested pipeline. */
interface BodyInterceptionEvidence {
    val chain: String
    val reason: String

    companion object {
        operator fun invoke(chain: String, reason: String): BodyInterceptionEvidence =
            DefaultBodyInterceptionEvidence(chain, reason)
    }
}

private data class DefaultBodyInterceptionEvidence(
    override val chain: String,
    override val reason: String
) : BodyInterceptionEvidence

/** Folding evidence for the body consumed by graph extraction, keyed by this load's fold rule index. */
interface MethodFoldObservation {
    val foldedCalls: Map<Int, Int>
    val unsupportedReasons: Map<Int, List<String>>
    val statementsBefore: Int?
    val statementsAfter: Int?

    fun agreesWith(other: MethodFoldObservation): Boolean =
        foldedCalls == other.foldedCalls && unsupportedReasons == other.unsupportedReasons &&
            statementsBefore == other.statementsBefore && statementsAfter == other.statementsAfter

    companion object {
        operator fun invoke(
            foldedCalls: Map<Int, Int>,
            unsupportedReasons: Map<Int, List<String>>,
            statementsBefore: Int?,
            statementsAfter: Int?
        ): MethodFoldObservation = DefaultMethodFoldObservation(
            foldedCalls, unsupportedReasons, statementsBefore, statementsAfter
        )
    }
}

private class DefaultMethodFoldObservation(
    foldedCalls: Map<Int, Int>,
    unsupportedReasons: Map<Int, List<String>>,
    override val statementsBefore: Int?,
    override val statementsAfter: Int?
) : MethodFoldObservation {
    override val foldedCalls: Map<Int, Int> = Collections.unmodifiableMap(LinkedHashMap(foldedCalls))
    override val unsupportedReasons: Map<Int, List<String>> = Collections.unmodifiableMap(
        unsupportedReasons.mapValues { (_, reasons) -> Collections.unmodifiableList(ArrayList(reasons)) }
    )
}
