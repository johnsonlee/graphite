package io.johnsonlee.graphite.sootup

import sootup.core.model.SootClass

/**
 * SPI for extending graph building with domain-specific metadata.
 *
 * Implementations are discovered at runtime via [java.util.ServiceLoader].
 * Extensions write data to the graph via [GraphiteContext.writer] during [visit].
 *
 * Note: Extension authors accept coupling to the SootUp backend via [SootClass].
 * If the backend changes in the future, extensions will need to be updated.
 *
 * To register an extension, add a file at:
 *   META-INF/services/io.johnsonlee.graphite.sootup.GraphiteExtension
 * containing the fully qualified class name of the implementation.
 */
interface GraphiteExtension {
    /**
     * Process a single class during graph building.
     * Called once per filtered class in the analysis scope.
     *
     * Use [GraphiteContext.writer] to write data to the graph.
     */
    fun visit(sootClass: SootClass, context: GraphiteContext)
}
