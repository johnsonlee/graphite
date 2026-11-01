package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.input.DependencyBodyScope

/**
 * The [DependencyBodyScope] of `--dependency-artifacts`, `--dependency-packages` and
 * `--dependency-reachable`, as `build` and `fold plan` take them: none when none is given, the
 * declared scope for the first two, the reachable scope for the third; naming both kinds is an
 * error, since a build reads its library bodies one way.
 */
internal fun dependencyBodyScope(artifacts: List<String>, packages: List<String>, reachable: Int? = null): DependencyBodyScope {
    val declared = artifacts.isNotEmpty() || packages.isNotEmpty()
    require(reachable == null || !declared) {
        "--dependency-reachable reads the library classes the application reaches; " +
            "it cannot be combined with --dependency-artifacts or --dependency-packages"
    }
    return when {
        reachable != null -> DependencyBodyScope.Reachable(reachable)
        declared -> DependencyBodyScope.Declared(artifacts, packages)
        else -> DependencyBodyScope.None
    }
}
