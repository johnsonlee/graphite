package io.johnsonlee.graphite.input

/**
 * Which library classes have their method bodies analysed, beyond the application's own.
 *
 * [LoaderConfig.includePackages] says what the application is; it used to say, at the same
 * time, which bodies are built, so a build that named its application packages built no body
 * of any library, and a value that crossed into `kotlin-stdlib` or a helper jar stopped at the
 * call. This scope separates the two: a library class it covers is processed as an application
 * class is (its methods, their bodies, its fields), without becoming one. It registers no
 * endpoint and runs no extension, its artifact keeps the `LIBRARY` role, and the completeness
 * evidence (`io.johnsonlee.graphite.graph.AnalysisCompleteness`) tells it apart from a class the
 * scope left out (`EXCLUDED_BY_SCOPE`) and from one the build never loaded (`EXTERNAL`).
 *
 * A library class outside the scope still resolves types and overrides, as before.
 */
sealed interface DependencyBodyScope {
    /** Glob patterns over artifact names (`kotlin-stdlib-*.jar`, `helper-*.jar`); every class of a matching artifact is covered. */
    val artifacts: List<String>

    /** Class name prefixes (`kotlin.`, `com.acme.helper`), as [LoaderConfig.includePackages] spells them. */
    val packages: List<String>

    /** Whether the bodies of the library class [className], read from [artifact], are analysed. */
    fun covers(className: String, artifact: String?): Boolean

    /**
     * Whether the scope names a library archive called [artifact] (by its file name, whatever
     * directory it sits in). A loader that would skip a library jar because no application
     * package is in it keeps one the scope names, or one that holds a class of its [packages].
     */
    fun coversArtifact(artifact: String): Boolean

    /** No library body: the default, and the build every earlier release did. */
    object None : DependencyBodyScope {
        override val artifacts: List<String> get() = emptyList()
        override val packages: List<String> get() = emptyList()
        override fun covers(className: String, artifact: String?): Boolean = false
        override fun coversArtifact(artifact: String): Boolean = false
        override fun toString(): String = "none"
    }

    /**
     * The library classes the application reaches: every library archive is loaded, and after
     * the application's bodies are read, the classes their calls name are read too, then the
     * classes those calls name, and so on: the declared callee of each call, the overrides of a
     * virtual callee the view knows, and the class a `new` names. At most [maxClasses] classes
     * are read this way; a class reached past the budget is `NOT_EXPANDED` in the evidence, which
     * is not a class with no effect. A library class nothing reaches stays `EXCLUDED_BY_SCOPE`.
     *
     * [roots] names the application classes whose calls seed the expansion, by class name
     * prefix; empty means every application class. Every application class is still read
     * (they are the application), and a root's call is followed through them: an application
     * method a followed call reaches, directly, through other application methods, through an
     * override or a resolved function value, or as the initialiser of a class a followed access
     * initialises, has its own calls followed. What no root reaches, and a call the fold
     * removed, is not followed into the libraries, so a consumer can expand from the entries
     * it declares.
     */
    data class Reachable(val maxClasses: Int = DEFAULT_MAX_CLASSES, val roots: List<String> = emptyList()) : DependencyBodyScope {
        init {
            require(maxClasses > 0) { "a reachable dependency body scope reads at least one class" }
            require(roots.none { it.isBlank() }) { "a reachable dependency body scope names no blank root" }
        }

        /** Whether the calls of the application class [className] seed the expansion. */
        fun seeds(className: String): Boolean = roots.isEmpty() || roots.any { className.startsWith(it) }

        override val artifacts: List<String> get() = emptyList()
        override val packages: List<String> get() = emptyList()
        override fun covers(className: String, artifact: String?): Boolean = false
        override fun coversArtifact(artifact: String): Boolean = true
        override fun toString(): String =
            if (roots.isEmpty()) "reachable(maxClasses=$maxClasses)" else "reachable(maxClasses=$maxClasses, roots=$roots)"

        companion object {
            const val DEFAULT_MAX_CLASSES = 50_000
        }
    }

    /** The library classes of the named [artifacts] and [packages]; at least one of the two is given. */
    data class Declared(
        override val artifacts: List<String> = emptyList(),
        override val packages: List<String> = emptyList()
    ) : DependencyBodyScope {
        init {
            require(artifacts.isNotEmpty() || packages.isNotEmpty()) {
                "a declared dependency body scope names at least one artifact pattern or package prefix"
            }
            require(artifacts.none { it.isBlank() } && packages.none { it.isBlank() }) {
                "a dependency body scope names no blank artifact pattern or package prefix"
            }
        }

        /** Compiled once: `covers` runs for every library class of the view. */
        private val artifactPatterns: List<Regex> = artifacts.map(GlobPatterns::toRegex)

        override fun covers(className: String, artifact: String?): Boolean =
            (artifact != null && coversArtifact(artifact)) || packages.any { className.startsWith(it) }

        override fun coversArtifact(artifact: String): Boolean {
            val fileName = artifact.substringAfterLast('/')
            return artifactPatterns.any { it.matches(fileName) }
        }
    }
}

/** The glob dialect of [LoaderConfig.libraryFilters] and [DependencyBodyScope.artifacts]: `*` matches any text, nothing else is special. */
object GlobPatterns {
    fun matches(name: String, pattern: String): Boolean = toRegex(pattern).matches(name)

    fun toRegex(pattern: String): Regex = pattern
        .replace(".", "\\.")
        .replace("*", ".*")
        .let { "^$it$" }
        .toRegex()
}
