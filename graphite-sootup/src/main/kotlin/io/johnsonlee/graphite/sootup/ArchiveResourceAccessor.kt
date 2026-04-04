package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.ResourceEntry
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * [ResourceAccessor] that supports directories, JARs, Spring Boot fat JARs, and WAR files.
 *
 * For fat JARs / WARs, resources inside nested JARs (e.g., BOOT-INF/lib/)
 * are also accessible.
 */
class ArchiveResourceAccessor private constructor(
    private val sources: List<ResourceSource>,
    private val tempDirs: List<Path> = emptyList()
) : ResourceAccessor, Closeable {

    override fun close() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    override fun list(pattern: String): Sequence<ResourceEntry> {
        val regex = globToRegex(pattern)
        return sources.asSequence().flatMap { source ->
            source.entries().filter { regex.matches(it.path) }
        }
    }

    private fun globToRegex(pattern: String): Regex {
        val expr = buildString {
            var i = 0
            val p = pattern
            while (i < p.length) {
                when {
                    p.startsWith("**/", i) -> { append("(.+/)?"); i += 3 }
                    p.startsWith("**", i) -> { append(".*"); i += 2 }
                    p[i] == '*' -> { append("[^/]*"); i++ }
                    p[i] == '.' -> { append("\\."); i++ }
                    else -> { append(p[i]); i++ }
                }
            }
        }
        return Regex("^$expr$")
    }

    override fun open(path: String): InputStream =
        sources.firstNotNullOfOrNull { it.read(path) }
            ?: throw IOException("Resource not found: $path")

    companion object {
        /**
         * Create a ResourceAccessor for the given path.
         *
         * The returned accessor should be [closed][close] when no longer needed
         * to clean up any temporary files extracted from nested JARs.
         */
        fun create(path: Path): ArchiveResourceAccessor {
            val sources = mutableListOf<ResourceSource>()
            val tempDirs = mutableListOf<Path>()
            when {
                path.isDirectory() -> {
                    sources.add(DirectorySource(path))
                }
                path.extension.lowercase() == "jar" -> {
                    val zip = ZipFile(path.toFile())
                    val hasBootInf = zip.getEntry("BOOT-INF/classes/") != null
                    if (hasBootInf) {
                        // Spring Boot fat JAR
                        sources.add(NestedJarSource(zip, "BOOT-INF/classes/", "BOOT-INF/classes/"))
                        val (nestedSources, nestedTempDir) = collectNestedJars(zip, "BOOT-INF/lib/")
                        sources.addAll(nestedSources)
                        nestedTempDir?.let { tempDirs.add(it) }
                    } else {
                        sources.add(ZipSource(zip, path.fileName.toString()))
                    }
                }
                path.extension.lowercase() == "war" -> {
                    val zip = ZipFile(path.toFile())
                    sources.add(NestedJarSource(zip, "WEB-INF/classes/", "WEB-INF/classes/"))
                    val (nestedSources, nestedTempDir) = collectNestedJars(zip, "WEB-INF/lib/")
                    sources.addAll(nestedSources)
                    nestedTempDir?.let { tempDirs.add(it) }
                }
            }
            return ArchiveResourceAccessor(sources, tempDirs)
        }

        private fun collectNestedJars(zip: ZipFile, libPrefix: String): Pair<List<ResourceSource>, Path?> {
            val entries = zip.entries().asSequence()
                .filter { it.name.startsWith(libPrefix) && it.name.endsWith(".jar") && !it.isDirectory }
                .toList()
            if (entries.isEmpty()) return Pair(emptyList(), null)

            val sources = mutableListOf<ResourceSource>()
            val tempDir = Files.createTempDirectory("graphite-resources")

            entries.forEach { entry ->
                val jarName = entry.name.substringAfterLast("/")
                val tempFile = tempDir.resolve(jarName)
                zip.getInputStream(entry).use { input ->
                    Files.copy(input, tempFile)
                }
                try {
                    sources.add(ZipSource(ZipFile(tempFile.toFile()), jarName))
                } catch (e: Exception) {
                    // Skip malformed JARs
                }
            }

            return Pair(sources, tempDir)
        }
    }
}

/**
 * Internal interface for resource sources.
 */
private interface ResourceSource {
    fun entries(): Sequence<ResourceEntry>
    fun read(path: String): InputStream?
}

/**
 * Resources from a filesystem directory.
 */
private class DirectorySource(private val root: Path) : ResourceSource {
    override fun entries(): Sequence<ResourceEntry> {
        if (!Files.exists(root)) return emptySequence()
        return Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .map { file ->
                    ResourceEntry(
                        path = root.relativize(file).toString().replace('\\', '/'),
                        source = root.fileName.toString()
                    )
                }
                .toList()
        }.asSequence()
    }

    override fun read(path: String): InputStream? {
        val file = root.resolve(path)
        return if (file.isRegularFile()) Files.newInputStream(file) else null
    }
}

/**
 * Resources from a ZIP/JAR file.
 */
private class ZipSource(
    private val zip: ZipFile,
    private val sourceName: String
) : ResourceSource {
    override fun entries(): Sequence<ResourceEntry> {
        return zip.entries().asSequence()
            .filter { !it.isDirectory }
            .map { entry ->
                ResourceEntry(
                    path = entry.name,
                    source = sourceName
                )
            }
    }

    override fun read(path: String): InputStream? {
        val entry = zip.getEntry(path) ?: return null
        return zip.getInputStream(entry)
    }
}

/**
 * Resources from a prefix within a ZIP (e.g., BOOT-INF/classes/ inside a fat JAR).
 * The prefix is stripped from the returned paths.
 */
private class NestedJarSource(
    private val zip: ZipFile,
    private val prefix: String,
    private val sourceName: String
) : ResourceSource {
    override fun entries(): Sequence<ResourceEntry> {
        return zip.entries().asSequence()
            .filter { it.name.startsWith(prefix) && !it.isDirectory && it.name != prefix }
            .map { entry ->
                ResourceEntry(
                    path = entry.name.removePrefix(prefix),
                    source = sourceName
                )
            }
    }

    override fun read(path: String): InputStream? {
        val entry = zip.getEntry("$prefix$path") ?: return null
        return zip.getInputStream(entry)
    }
}