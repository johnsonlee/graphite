package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.sootup.SootUpAdapter.BundleControlSpec

/** Resolves bundle names and locale candidates to persisted resource paths. */
internal class BundlePaths(private val context: SootUpAdapter) {
    private val resourceFilesByPath = context.resourceFilesByPath

    fun collectMatchingResourceBundlePaths(
        baseName: String,
        basePath: String,
        controlSpec: BundleControlSpec?
    ): LinkedHashSet<String> {
        val candidates = resourceFilesByPath.keys
            .filter {
                it == defaultBundlePropertiesPath(basePath) ||
                    (it.startsWith("${basePath}_") && it.endsWith(PROPERTIES_FILE_SUFFIX)) ||
                    matchesBundleClassPath(it, baseName)
            }
            .sortedWith(compareByDescending<String> { it.count { ch -> ch == '_' } }.thenBy { it })
        return LinkedHashSet(
            candidates
                .asSequence()
                .filter { controlAllowsPath(it, controlSpec) }
                .toList()
                .ifEmpty { collectBundleCandidates(baseName, basePath, controlSpec) }
        )
    }

    fun buildResourceBundleCandidatePaths(
        baseName: String,
        basePath: String,
        localeSpec: String,
        controlSpec: BundleControlSpec?
    ): LinkedHashSet<String> {
        val candidates = LinkedHashSet<String>()
        val localeCandidates = controlSpec?.candidateLocales ?: defaultBundleCandidateLocales(localeSpec)
        for (candidateLocale in localeCandidates) {
            if (candidateLocale.isBlank()) {
                candidates.add(defaultBundlePropertiesPath(basePath))
                candidates.add(baseName)
            } else {
                candidates.add("${basePath}_$candidateLocale$PROPERTIES_FILE_SUFFIX")
                candidates.add("${baseName}_${candidateLocale}")
            }
        }
        return LinkedHashSet(
            candidates.filter {
                (it in resourceFilesByPath || it == defaultBundlePropertiesPath(basePath) || it == baseName) &&
                    controlAllowsPath(it, controlSpec)
            }
        )
    }

    fun defaultBundleCandidateLocales(localeSpec: String): List<String> {
        val parts = localeSpec.split('_').filter { it.isNotBlank() }
        return buildList {
            for (size in parts.size downTo 1) {
                add(parts.take(size).joinToString("_"))
            }
            add("")
        }
    }

    fun collectBundleCandidates(baseName: String, basePath: String, controlSpec: BundleControlSpec?): LinkedHashSet<String> =
        LinkedHashSet(
            listOf(defaultBundlePropertiesPath(basePath), baseName)
                .filter { controlAllowsPath(it, controlSpec) }
        )

    fun defaultBundlePropertiesPath(basePath: String): String = "$basePath$PROPERTIES_FILE_SUFFIX"

    fun controlAllowsPath(path: String, controlSpec: BundleControlSpec?): Boolean {
        if (controlSpec == null) return true
        val isProperties = path.endsWith(PROPERTIES_FILE_SUFFIX)
        val isClassBundle = !isProperties
        return (isProperties && RESOURCE_BUNDLE_FORMAT_PROPERTIES in controlSpec.formats) ||
            (isClassBundle && RESOURCE_BUNDLE_FORMAT_CLASS in controlSpec.formats)
    }

    fun matchesBundleClassPath(path: String, baseName: String): Boolean =
        path == baseName || path.startsWith("${baseName}_")
}
