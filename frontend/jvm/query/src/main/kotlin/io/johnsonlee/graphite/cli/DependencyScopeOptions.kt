package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.input.DependencyBodyScope

/**
 * The [DependencyBodyScope] of `--dependency-artifacts` and `--dependency-packages`, as `build`
 * and `fold plan` take them: none when neither is given.
 */
internal fun dependencyBodyScope(artifacts: List<String>, packages: List<String>): DependencyBodyScope =
    if (artifacts.isEmpty() && packages.isEmpty()) DependencyBodyScope.None else DependencyBodyScope.Declared(artifacts, packages)
