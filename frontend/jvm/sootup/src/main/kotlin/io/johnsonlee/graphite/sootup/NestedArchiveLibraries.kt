package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.JavaArchiveLayout
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Extracts selected nested archives while preserving each launcher's classpath order. */
internal class NestedArchiveLibraries(
    private val matchesLibraryFilter: (String) -> Boolean,
    private val jarInScope: (Path, String) -> Boolean,
    private val log: (String) -> Unit
) {
    fun springBoot(zip: ZipFile, tempDir: Path, artifactOrder: List<String>): List<Pair<String, Path>> {
        val libDir = Files.createDirectories(tempDir.resolve("lib"))
        val nested = zip.entries().asSequence()
            .filter { it.name.startsWith(JavaArchiveLayout.BOOT_INF_LIB) && it.name.endsWith(JavaArchiveLayout.JAR_EXTENSION) }
            .filter { matchesLibraryFilter(it.name.substringAfterLast("/")) }
            .mapNotNull { extractSelectedLibrary(zip, it, libDir) }
            .toList()
        val rank = artifactOrder.withIndex().associate { (index, name) -> name to index }
        return nested.sortedBy { (name, _) -> rank[name] ?: Int.MAX_VALUE }
    }

    fun war(zip: ZipFile, tempDir: Path): List<Pair<String, Path>> {
        val libDir = Files.createDirectories(tempDir.resolve("lib"))
        val jars = zip.entries().asSequence()
            .filter { it.name.startsWith(JavaArchiveLayout.WEB_INF_LIB) && it.name.endsWith(JavaArchiveLayout.JAR_EXTENSION) }
            .toList()
        log("Found ${jars.size} JARs in ${JavaArchiveLayout.WEB_INF_LIB}")
        var skippedByFilter = 0
        var skippedByPackage = 0
        val selected = jars.mapNotNull { entry ->
            val jarName = entry.name.substringAfterLast("/")
            when {
                !matchesLibraryFilter(jarName) -> {
                    skippedByFilter++
                    null
                }
                else -> {
                    val extracted = extractSelectedLibrary(zip, entry, libDir)
                    if (extracted == null) {
                        skippedByPackage++
                        Files.deleteIfExists(libDir.resolve(jarName))
                    } else log("  + Loading JAR: $jarName")
                    extracted
                }
            }
        }
        log("Loaded ${selected.size} JARs (skipped: $skippedByFilter by filter, $skippedByPackage by package)")
        return selected
    }

    private fun extractSelectedLibrary(zip: ZipFile, entry: ZipEntry, libDir: Path): Pair<String, Path>? {
        val name = entry.name.substringAfterLast("/")
        val target = libDir.resolve(name)
        zip.getInputStream(entry).use { Files.copy(it, target) }
        return (name to target).takeIf { jarInScope(target, name) }
    }
}
