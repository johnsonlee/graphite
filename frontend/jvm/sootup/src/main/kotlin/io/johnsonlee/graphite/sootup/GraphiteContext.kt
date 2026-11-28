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
    val resources: ResourceAccessor = EmptyResourceAccessor,
    private val annotations: (className: String, memberName: String, annotationFqn: String, values: Map<String, Any?>) -> Unit
) {
    /**
     * Record the annotation [annotationFqn] on [memberName] of [className]; [CLASS_MEMBER] names the class itself. The fact enters
     * the graph exactly as an annotation the bytecode carries does: it is persisted with the graph, read back by
     * `Graph.memberAnnotations`, matched as an `Annotation` node in Cypher, and seen by every view derived from annotations
     * (endpoints, for one). This is how an extension states what a framework knows and the bytecode does not say: a route a
     * router registers in code, a binding a module declares, a role a naming convention implies.
     *
     * [annotationFqn] need not be a class that exists; it is the name queries match. [values] keep strings, numbers, booleans and
     * lists of them; anything else is recorded as its text, and an empty value is dropped, as for bytecode annotations.
     *
     * A member holds one annotation of a name. Recording a name the member already has, from the bytecode or from an earlier
     * call by any extension, throws [IllegalArgumentException] and changes nothing: replacing it would leave the graph's
     * metadata and its `Annotation` nodes telling different stories. To add to what an annotation says (a route with its
     * placeholders resolved, say), record the result under a name of your own on the same member. The class named may be one
     * the build reads later; a name its bytecode turns out to carry fails the build with [IllegalStateException] once every
     * class has been read. The annotations are written to the graph then, so they are complete in the graph the build returns.
     *
     * @throws IllegalArgumentException when a part of the key is empty or the member already has [annotationFqn]
     */
    fun addMemberAnnotation(className: String, memberName: String, annotationFqn: String, values: Map<String, Any?> = emptyMap()) {
        require(className.isNotEmpty() && memberName.isNotEmpty() && annotationFqn.isNotEmpty()) {
            "An annotation needs a class, a member and a name; got class='$className' member='$memberName' annotation='$annotationFqn'"
        }
        annotations(className, memberName, annotationFqn, values)
    }

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

    companion object {
        /** The member name of an annotation on the class itself. */
        const val CLASS_MEMBER = "<class>"
    }
}
