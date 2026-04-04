package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.EmptyResourceAccessor
import sootup.core.model.SootMethod
import java.text.MessageFormat

/**
 * Context provided to [GraphiteExtension] implementations during class processing.
 */
class GraphiteContext(
    private val methodDescriptorFactory: (SootMethod) -> MethodDescriptor,
    private val logger: (String) -> Unit,
    val resources: ResourceAccessor = EmptyResourceAccessor
) {
    /**
     * Convert a SootUp method to a Graphite [MethodDescriptor].
     */
    fun toMethodDescriptor(method: SootMethod): MethodDescriptor = methodDescriptorFactory(method)

    /**
     * Log a message via the configured verbose logger.
     * Supports [MessageFormat] placeholders: `{0}`, `{1}`, etc.
     */
    fun log(message: String, vararg args: Any?) {
        logger(if (args.isEmpty()) message else MessageFormat.format(message, *args))
    }
}
