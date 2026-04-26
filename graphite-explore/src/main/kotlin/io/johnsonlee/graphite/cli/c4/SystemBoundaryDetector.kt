package io.johnsonlee.graphite.cli.c4

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor

internal object SystemBoundaryDetector {
    fun derive(methods: List<MethodDescriptor>, callSites: List<CallSiteNode>): String {
        val primaryClasses = methods.map { it.declaringClass.className }.ifEmpty {
            callSites.map { it.caller.declaringClass.className }
        }
        val packages = primaryClasses
            .filterNot(::isSyntheticClass)
            .map { it.substringBeforeLast('.', "") }
            .filter { it.isNotBlank() }
        if (packages.isEmpty()) return "(default)"

        val packageWeights = packages.groupingBy { it }.eachCount()
        val prefixWeights = mutableMapOf<String, Int>()
        packageWeights.forEach { (packageName, weight) ->
            val segments = packageName.split('.').filter { it.isNotBlank() }
            val rootDepth = namespaceRootSegmentCount(segments)
            (rootDepth..minOf(C4BoundaryHeuristics.MAX_PREFIX_DEPTH, segments.size)).forEach { depth ->
                val prefix = segments.take(depth).joinToString(".")
                prefixWeights[prefix] = (prefixWeights[prefix] ?: 0) + weight
            }
        }
        val root = packages
            .groupingBy(::dominantNamespace)
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: "(default)"
        if (root == "(default)") return root

        var boundary = root
        while (true) {
            if (!canDescendBoundary(boundary)) break
            val boundaryWeight = prefixWeights[boundary] ?: break
            val nextDepth = boundary.split('.').filter { it.isNotBlank() }.size + 1
            if (nextDepth > C4BoundaryHeuristics.MAX_PREFIX_DEPTH) break
            val children = prefixWeights.entries
                .filter { (prefix, _) ->
                    prefix.count { it == '.' } + 1 == nextDepth &&
                        prefix.startsWith("$boundary.")
                }
                .sortedByDescending { it.value }
            val strongest = children.firstOrNull() ?: break
            val runnerUpWeight = children.drop(1).firstOrNull()?.value ?: 0
            val dominance = strongest.value.toDouble() / boundaryWeight.toDouble()
            val separatesFromPeers = strongest.value >= runnerUpWeight * C4BoundaryHeuristics.RUNNER_UP_SEPARATION
            if (dominance >= C4BoundaryHeuristics.DOMINANCE_THRESHOLD && separatesFromPeers) {
                boundary = strongest.key
            } else {
                break
            }
        }
        return boundary
    }

    fun dominantNamespace(packageName: String): String {
        val segments = packageName.split('.').filter { it.isNotBlank() }
        return segments.take(namespaceRootSegmentCount(segments)).joinToString(".")
    }

    fun isInternalClass(className: String, systemBoundary: String): Boolean =
        !isSyntheticClass(className) &&
            (className == systemBoundary || className.startsWith("$systemBoundary."))

    fun internalPackageUnit(className: String, systemBoundary: String): String {
        val packageName = className.substringBeforeLast('.', "")
        if (packageName.isBlank()) return "(default)"
        val segments = packageName.split('.')
        if (!isInternalClass(className, systemBoundary)) {
            return segments.take(namespaceRootSegmentCount(segments)).joinToString(".")
        }
        val suffix = packageName.removePrefix(systemBoundary).trimStart('.')
        return if (suffix.isBlank()) systemBoundary else "$systemBoundary.${suffix.substringBefore('.')}"
    }

    fun humanizeIdentifier(identifier: String): String {
        return identifier
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replace('-', ' ')
            .replace('_', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.lowercase().replaceFirstChar { it.uppercase() }
            }
    }

    fun isSyntheticClass(className: String): Boolean {
        return className.startsWith("sootup.dummy.") || className == "sootup.dummy"
    }

    fun isRuntimeClass(className: String): Boolean =
        isJavaRuntimeClass(className) ||
            className.startsWith("kotlin.") ||
            className.startsWith("scala.")

    fun isJavaRuntimeClass(className: String): Boolean =
        className.startsWith("java.") ||
            className.startsWith("javax.") ||
            className.startsWith("jakarta.") ||
            className.startsWith("jdk.")

    fun namespaceRootSegmentCount(segments: List<String>): Int {
        if (segments.isEmpty()) return 0
        val target = if (isReverseDnsNamespace(segments)) {
            C4NamespaceHeuristics.REVERSE_DNS_ROOT_SEGMENTS
        } else {
            C4NamespaceHeuristics.NON_REVERSE_DNS_ROOT_SEGMENTS
        }
        return minOf(target, segments.size)
    }

    fun isReverseDnsNamespace(segments: List<String>): Boolean =
        segments.firstOrNull()?.lowercase() in C4NamespaceHeuristics.REVERSE_DNS_PREFIXES

    private fun canDescendBoundary(boundary: String): Boolean =
        isReverseDnsNamespace(boundary.split('.').filter { it.isNotBlank() })
}

internal fun deriveSystemBoundary(methods: List<MethodDescriptor>, callSites: List<CallSiteNode>): String =
    SystemBoundaryDetector.derive(methods, callSites)

internal fun dominantNamespace(packageName: String): String =
    SystemBoundaryDetector.dominantNamespace(packageName)

internal fun isInternalArchitectureClass(className: String, systemBoundary: String): Boolean =
    SystemBoundaryDetector.isInternalClass(className, systemBoundary)

internal fun internalPackageUnit(className: String, systemBoundary: String): String =
    SystemBoundaryDetector.internalPackageUnit(className, systemBoundary)

internal fun humanizeIdentifier(identifier: String): String =
    SystemBoundaryDetector.humanizeIdentifier(identifier)

internal fun isSyntheticArchitectureClass(className: String): Boolean =
    SystemBoundaryDetector.isSyntheticClass(className)

internal fun isRuntimeArchitectureClass(className: String): Boolean =
    SystemBoundaryDetector.isRuntimeClass(className)

internal fun isJavaRuntimeArchitectureClass(className: String): Boolean =
    SystemBoundaryDetector.isJavaRuntimeClass(className)

internal fun namespaceRootSegmentCount(segments: List<String>): Int =
    SystemBoundaryDetector.namespaceRootSegmentCount(segments)

internal fun isReverseDnsNamespace(segments: List<String>): Boolean =
    SystemBoundaryDetector.isReverseDnsNamespace(segments)
