package io.johnsonlee.graphite.input

import java.io.IOException
import java.io.InputStream

/**
 * Provides access to resource files within a project archive (JAR, WAR, directory).
 *
 * Abstracts away the physical layout so extensions don't need to know whether
 * resources live in a plain directory, a JAR, or a nested JAR inside a
 * Spring Boot fat JAR / WAR.
 */
interface ResourceAccessor {

    /**
     * List resource entries matching a glob pattern.
     *
     * Supported patterns:
     * - `**` matches any number of path segments
     * - `*` matches any characters within a single segment
     *
     * @param pattern glob pattern to match against resource paths
     * @return sequence of matching resource entries
     */
    fun list(pattern: String): Sequence<ResourceEntry>

    /**
     * Open a resource for reading.
     *
     * @param path resource path as returned by [ResourceEntry.path]
     * @return input stream for the resource. Caller is responsible for closing the stream.
     * @throws java.io.IOException if the resource does not exist or cannot be read
     */
    @Throws(java.io.IOException::class)
    fun open(path: String): InputStream
}

/**
 * A resource file entry within a project archive.
 *
 * @property path relative path within the archive (e.g., `config/application.yml`)
 * @property source origin of the resource (e.g., `BOOT-INF/classes/`, `my-lib-1.0.jar`)
 */
data class ResourceEntry(
    val path: String,
    val source: String
)

/**
 * A [ResourceAccessor] that always returns empty results.
 */
object EmptyResourceAccessor : ResourceAccessor {
    override fun list(pattern: String): Sequence<ResourceEntry> = emptySequence()
    override fun open(path: String): InputStream = throw IOException("Empty resource accessor: $path")
}
