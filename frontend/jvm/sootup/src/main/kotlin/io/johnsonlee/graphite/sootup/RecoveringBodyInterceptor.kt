package io.johnsonlee.graphite.sootup

import sootup.core.graph.MutableBlockControlFlowGraph
import sootup.core.graph.MutableBasicBlock
import sootup.core.interceptor.BodyInterceptor
import sootup.core.model.Body
import sootup.core.views.View

/**
 * Intercepts one APK body transactionally. The dex frontend resolves bodies while enumerating
 * a class's methods, before the adapter can guard an individual method. Keep its original
 * builder intact until the entire chain succeeds, so a failed pass cannot discard that class
 * or leave a partly split/folded body behind. SootUp statements and locals are immutable; the
 * copied graph owns its blocks and edges, and the copied sets own their membership.
 */
internal class RecoveringBodyInterceptor(
    private val interceptors: List<BodyInterceptor>,
    private val onFallback: (String) -> Unit,
    private val discard: (Body.BodyBuilder) -> Unit = {}
) : BodyInterceptor {
    // Any interceptor/validation exception must fall back; VM errors still propagate.
    @Suppress("TooGenericExceptionCaught")
    override fun interceptBody(builder: Body.BodyBuilder, view: View) {
        val candidate = Body.builder(MutableBlockControlFlowGraph(builder.controlFlowGraph))
            .setMethodSignature(builder.methodSignature)
            .setModifiers(LinkedHashSet(builder.modifiers))
            .setLocals(LinkedHashSet(builder.locals))
        builder.position?.let(candidate::setPosition)
        var active: BodyInterceptor? = null
        try {
            for (interceptor in interceptors) {
                active = interceptor
                interceptor.interceptBody(candidate, view)
                candidate.controlFlowGraph.validateStmtConnectionsInGraph()
            }
            // Include final body validation: an interceptor can return normally with an invalid
            // starting statement, which would otherwise fail later in DexMethodSource.
            candidate.build()
        } catch (failure: Exception) {
            discard(candidate)
            onFallback(
                "Keeping unintercepted body for ${builder.methodSignature}: " +
                    "${active?.javaClass?.simpleName ?: "body validation"} failed: ${failure.message}"
            )
            return
        }

        // BodyBuilder has no graph setter. Replace its valid original graph only after all passes
        // succeeded, preserving successor indices (including switch duplicates) and trap edges.
        val target = builder.controlFlowGraph
        target.blocks.toList().forEach(target::removeBlock)
        val source = candidate.controlFlowGraph
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
        builder.setLocals(candidate.locals)
        builder.setModifiers(candidate.modifiers)
        builder.setMethodSignature(candidate.methodSignature)
        candidate.position?.let(builder::setPosition)
    }
}
