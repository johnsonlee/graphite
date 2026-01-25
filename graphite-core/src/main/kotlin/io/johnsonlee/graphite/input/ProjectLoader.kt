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
     * Call graph algorithm
     */
    val callGraphAlgorithm: CallGraphAlgorithm = CallGraphAlgorithm.CHA
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
