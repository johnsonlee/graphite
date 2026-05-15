package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.ResourceEntry
import io.johnsonlee.graphite.input.JavaArchiveLayout
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
 * For fat JARs / WARs, resources inside nested JARs are also accessible.
 * are also accessible.
 */
class ArchiveResourceAccessor private constructor(
    private val sources: List<ResourceSource>,
    private val closers: List<Closeable> = emptyList(),
    private val tempDirs: List<Path> = emptyList()
) : ResourceAccessor, Closeable {

    override fun close() {
        closers.asReversed().forEach {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
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
            val closers = mutableListOf<Closeable>()
            val tempDirs = mutableListOf<Path>()
            when {
                path.isDirectory() -> {
                    sources.add(DirectorySource(path))
                    collectDirectoryJars(path).appendTo(sources, closers)
                }
                path.extension.lowercase() == "jar" -> {
                    val zip = ZipFile(path.toFile())
                    val hasBootInf = zip.getEntry(JavaArchiveLayout.BOOT_INF_CLASSES) != null
                    if (hasBootInf) {
                        // Spring Boot fat JAR
                        sources.addManaged(
                            NestedJarSource(zip, JavaArchiveLayout.BOOT_INF_CLASSES, JavaArchiveLayout.BOOT_INF_CLASSES),
                            zip,
                            closers
                        )
                        collectNestedJars(zip, JavaArchiveLayout.BOOT_INF_LIB).appendTo(sources, closers, tempDirs)
                    } else {
                        sources.addManaged(ZipSource(zip, path.fileName.toString()), zip, closers)
                    }
                }
                path.extension.lowercase() == "war" -> {
                    val zip = ZipFile(path.toFile())
                    sources.addManaged(
                        NestedJarSource(zip, JavaArchiveLayout.WEB_INF_CLASSES, JavaArchiveLayout.WEB_INF_CLASSES),
                        zip,
                        closers
                    )
                    collectNestedJars(zip, JavaArchiveLayout.WEB_INF_LIB).appendTo(sources, closers, tempDirs)
                }
            }
            return ArchiveResourceAccessor(sources, closers, tempDirs)
        }

        private data class ArchiveSourceBundle(
            val sources: List<ResourceSource> = emptyList(),
            val closers: List<Closeable> = emptyList(),
            val tempDir: Path? = null
        )

        private fun ArchiveSourceBundle.appendTo(
            sources: MutableList<ResourceSource>,
            closers: MutableList<Closeable>,
            tempDirs: MutableList<Path>? = null
        ) {
            sources.addAll(this.sources)
            closers.addAll(this.closers)
            tempDir?.let { tempDirs?.add(it) }
        }

        private fun MutableList<ResourceSource>.addManaged(
            source: ResourceSource,
            closer: Closeable,
            closers: MutableList<Closeable>
        ) {
            add(source)
            closers.add(closer)
        }

        private fun collectDirectoryJars(root: Path): ArchiveSourceBundle {
            if (!Files.exists(root)) return ArchiveSourceBundle()
            val sources = mutableListOf<ResourceSource>()
            val closers = mutableListOf<Closeable>()
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .filter {
                        when (it.fileName.toString().substringAfterLast('.', "").lowercase()) {
                            "jar", "war", "zip" -> true
                            else -> false
                        }
                    }
                    .sorted()
                    .forEach { archivePath ->
                        try {
                            val zip = ZipFile(archivePath.toFile())
                            sources.addManaged(
                                ZipSource(zip, root.relativize(archivePath).toString().replace('\\', '/')),
                                zip,
                                closers
                            )
                        } catch (_: Exception) {
                        }
                    }
            }
            return ArchiveSourceBundle(sources = sources, closers = closers)
        }

        private fun collectNestedJars(zip: ZipFile, libPrefix: String): ArchiveSourceBundle {
            val entries = zip.entries().asSequence()
                .filter { it.name.startsWith(libPrefix) && it.name.endsWith(".jar") && !it.isDirectory }
                .toList()
            if (entries.isEmpty()) return ArchiveSourceBundle()

            val sources = mutableListOf<ResourceSource>()
            val closers = mutableListOf<Closeable>()
            val tempDir = Files.createTempDirectory("graphite-resources")

            entries.forEach { entry ->
                val jarName = entry.name.substringAfterLast("/")
                val tempFile = tempDir.resolve(jarName)
                zip.getInputStream(entry).use { input ->
                    Files.copy(input, tempFile)
                }
                try {
                    val nestedZip = ZipFile(tempFile.toFile())
                    sources.addManaged(ZipSource(nestedZip, jarName), nestedZip, closers)
                } catch (e: Exception) {
                    // Skip malformed JARs
                }
            }

            return ArchiveSourceBundle(sources = sources, closers = closers, tempDir = tempDir)
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
                .filter { !isArchive(it) }
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
        return if (file.isRegularFile() && !isArchive(file)) Files.newInputStream(file) else null
    }

    private fun isArchive(path: Path): Boolean =
        when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
            "jar", "war", "zip" -> true
            else -> false
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
 * Resources from a prefix within a ZIP, such as [JavaArchiveLayout.BOOT_INF_CLASSES] inside a fat JAR.
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
