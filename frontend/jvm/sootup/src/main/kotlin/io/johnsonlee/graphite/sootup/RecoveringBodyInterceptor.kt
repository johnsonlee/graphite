package io.johnsonlee.graphite.sootup

import sootup.core.graph.MutableBlockControlFlowGraph
import sootup.core.graph.MutableBasicBlock
import sootup.core.interceptor.BodyInterceptor
import sootup.core.jimple.basic.NoPositionInformation
import sootup.core.model.Body
import sootup.core.views.View

/** A less ambitious chain that still produces a usable body after the primary chain fails. */
internal data class BodyRecoveryChain(val description: String, val interceptors: List<BodyInterceptor>)

/** Must escape the adapter's legacy handling of IllegalStateException during class resolution. */
internal class BodyRecoveryException(message: String, cause: Exception) : RuntimeException(message, cause)

/**
 * The dex frontend intercepts bodies while enumerating a class's methods, before the adapter
 * can guard individual methods. Keep one independent backup, run the normal chain in place,
 * and restore only after a failure. Statements and locals are immutable; the backup owns its
 * graph blocks, edges and sets. VM errors propagate without attempting an allocating recovery.
 */
internal class RecoveringBodyInterceptor(
    private val interceptors: List<BodyInterceptor>,
    private val onFallback: (String) -> Unit,
    private val discard: (Body.BodyBuilder) -> Unit = {},
    private val recoveryChains: List<BodyRecoveryChain> = listOf(BodyRecoveryChain("unintercepted body", emptyList())),
    private val onRecovered: (Body.BodyBuilder, Body.BodyBuilder, View, String) -> Unit = { _, _, _, _ -> }
) : BodyInterceptor {
    override fun interceptBody(builder: Body.BodyBuilder, view: View) {
        val backup = Body.builder(MutableBlockControlFlowGraph(builder.controlFlowGraph))
            .setMethodSignature(builder.methodSignature)
            .setModifiers(LinkedHashSet(builder.modifiers))
            .setLocals(LinkedHashSet(builder.locals))
        builder.position?.let(backup::setPosition)
        val failure = applyChain(builder, view, interceptors) ?: return
        discard(builder)
        var lastFailure = failure
        for (recovery in recoveryChains) {
            restore(builder, backup)
            val retryFailure = applyChain(builder, view, recovery.interceptors)
            if (retryFailure == null) {
                onRecovered(builder, backup, view, failure.description)
                onFallback("Recovered body for ${builder.methodSignature} using ${recovery.description}: ${failure.description}")
                return
            }
            lastFailure = retryFailure
        }
        // Raw dex constants may still be integer/long bit patterns for null, float or double.
        // If even the essential default transforms fail, no safe recovery remains.
        restore(builder, backup)
        throw BodyRecoveryException(
            "Cannot recover body for ${backup.methodSignature}; required dex normalization failed: ${lastFailure.description}",
            lastFailure.cause
        )
    }

    private data class Failure(val phase: String, val cause: Exception) {
        val description: String get() = "$phase failed: ${cause.message}"
    }

    // Any interceptor/validation exception may trigger a recovery; VM errors still propagate.
    @Suppress("TooGenericExceptionCaught")
    private fun applyChain(builder: Body.BodyBuilder, view: View, chain: List<BodyInterceptor>): Failure? {
        var active: BodyInterceptor? = null
        return try {
            for (interceptor in chain) {
                active = interceptor
                interceptor.interceptBody(builder, view)
            }
            active = null
            // DexMethodSource validates only at the end. Intermediate passes may temporarily
            // leave the graph invalid until a later pass repairs it.
            builder.build()
            null
        } catch (failure: Exception) {
            Failure(active?.toString()?.substringBefore('@') ?: "body validation", failure)
        }
    }

    /** BodyBuilder has no graph setter; mirror SootUp's graph copy constructor only on recovery. */
    @Suppress("TooGenericExceptionCaught") // A failed rollback must abort, never become a skipped class.
    private fun restore(targetBuilder: Body.BodyBuilder, backup: Body.BodyBuilder): Unit = try {
        val target = targetBuilder.controlFlowGraph
        target.blocks.toList().forEach(target::removeBlock)
        val source = backup.controlFlowGraph
        source.blocks.forEach { block ->
            target.addBlock(block.stmts, block.exceptionalSuccessors.mapValues { it.value.head })
        }
        source.blocks.forEach { block ->
            val copied = target.getBlockOf(block.tail) as MutableBasicBlock
            block.successors.forEachIndexed { index, successor ->
                copied.linkSuccessor(index, target.getBlockOf(successor.head) as MutableBasicBlock)
            }
        }
        source.startingStmt?.let(target::setStartingStmt)
        // Every retry gets fresh sets so it cannot corrupt the one preserved backup.
        targetBuilder.setLocals(LinkedHashSet(backup.locals))
        targetBuilder.setModifiers(LinkedHashSet(backup.modifiers))
        targetBuilder.setMethodSignature(backup.methodSignature)
        targetBuilder.setPosition(backup.position ?: NoPositionInformation.getInstance())
        Unit
    } catch (failure: Exception) {
        throw BodyRecoveryException("Cannot restore body for ${backup.methodSignature} after interception failed", failure)
    }
}
