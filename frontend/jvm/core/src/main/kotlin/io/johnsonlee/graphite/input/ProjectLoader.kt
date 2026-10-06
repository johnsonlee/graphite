package io.johnsonlee.graphite.input

import io.johnsonlee.graphite.graph.Graph
import java.nio.file.Path

/**
 * Entry point for loading different project types.
 *
 * This is the core interface that different backends (SootUp, ASM, etc.)
 * implement to load bytecode and build the analysis graph.
 */
interface ProjectLoader {
    /**
     * Load a project and build the analysis graph
     */
    fun load(path: Path): Graph

    /**
     * Check if this loader can handle the given path
     */
    fun canLoad(path: Path): Boolean
}

/**
 * Configuration for project loading.
 * Backend-agnostic settings that apply to all loaders.
 */
data class LoaderConfig(
    /**
     * Whether to include library code in the analysis
     */
    val includeLibraries: Boolean = false,

    /**
     * Packages to include (empty = all)
     */
    val includePackages: List<String> = emptyList(),

    /**
     * Packages to exclude
     */
    val excludePackages: List<String> = emptyList(),

    /**
     * JAR name patterns to include from lib directories (empty = all).
     * Supports glob patterns like "modular-*", "business-*.jar"
     */
    val libraryFilters: List<String> = emptyList(),

    /**
     * Whether to build call graph
     */
    val buildCallGraph: Boolean = true,

    /**
     * Whether to extract annotations into graph metadata/nodes.
     */
    val extractAnnotations: Boolean = true,

    /**
     * Whether to resolve lambda/method-reference dispatch across method boundaries.
     */
    val trackCrossMethodFunctionalDispatch: Boolean = true,

    /**
     * Call graph algorithm
     */
    val callGraphAlgorithm: CallGraphAlgorithm = CallGraphAlgorithm.CHA,

    /**
     * Android SDK location used when loading APK inputs.
     *
     * Must be an Android SDK root containing a platforms directory.
     */
    val androidSdk: Path? = null,

    /**
     * Calls folded to constants while the graph is built, see [FoldPlan] and [FoldRule]. `null`
     * leaves every body exactly as the frontend's default interceptors do.
     */
    val folding: FoldPlan? = null,

    /**
     * Whether to link every call to its callee's body: a `DATAFLOW` edge from each argument to
     * the callee's `ParameterNode` and from the callee's `ReturnNode` to the call's result, for
     * the declared callee and, on a virtual call, every override the view knows. Off by default,
     * so a graph is per-method dataflow joined only at call sites; `graphite build` turns it on
     * for the graph a `select` fold rule runs on, where a query must follow a value through a
     * helper's parameter and back out of its return.
     */
    val interproceduralDataflow: Boolean = false,

    /**
     * Verbose logging callback
     */
    val verbose: ((String) -> Unit)? = null
)

enum class CallGraphAlgorithm {
    /**
     * Class Hierarchy Analysis - fast but imprecise
     */
    CHA,

    /**
     * Rapid Type Analysis - better precision than CHA
     */
    RTA,

    /**
     * Variable Type Analysis
     */
    VTA,

    /**
     * Spark pointer analysis (most precise, slowest)
     */
    SPARK
}
