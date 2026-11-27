package io.johnsonlee.graphite.webgraph

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Writes a graph directory as a whole or not at all.
 *
 * The writer fills a sibling staging directory and only then takes the target's place, so:
 * - a graph mapped from the target (`save(loadMapped(dir), dir)`) keeps reading its own files while the new ones are written, where
 *   truncating them in place left it, and the saved graph, with no nodes;
 * - no file of an earlier graph survives beside the new one (a `backward.*` transpose or a sidecar this writer does not know), so the
 *   writer no longer keeps a list of files to delete;
 * - a failed or interrupted save leaves the earlier graph, not a mixture.
 *
 * An existing target is replaced only when it is empty or holds nothing but graph files, so a mistaken output path never deletes a
 * directory of anything else.
 */
internal object GraphDirectoryPublication {
    private val GRAPH_FILE_PREFIXES = listOf("graph.", "forward.", "backward.")
    private const val CONTAINER_METADATA = "META-INF"

    fun <T> publish(dir: Path, write: (Path) -> T): T {
        val target = dir.toAbsolutePath().normalize()
        val name = requireNotNull(target.fileName) { "Cannot save a graph as the file system root: $dir" }.toString()
        val parent = checkNotNull(target.parent)
        if (Files.exists(target)) requireReplaceable(target)
        Files.createDirectories(parent)
        val stage = Files.createTempDirectory(parent, ".$name.saving-")
        try {
            val result = write(stage)
            swap(stage, target, parent.resolve(".$name.replaced-${UUID.randomUUID()}"))
            return result
        } finally {
            deleteRecursively(stage)
        }
    }

    internal fun isGraphFile(name: String): Boolean = name == CONTAINER_METADATA || GRAPH_FILE_PREFIXES.any(name::startsWith)

    private fun requireReplaceable(target: Path) {
        require(Files.isDirectory(target)) { "${GraphStore.NOT_A_DIRECTORY_PREFIX} $target" }
        val foreign =
            Files.list(target).use { paths -> paths.map { it.fileName.toString() }.filter { !isGraphFile(it) }.sorted().toList() }
        require(foreign.isEmpty()) {
            "Refusing to replace $target: it holds files that are not part of a graph (${foreign.take(MAX_LISTED).joinToString()})"
        }
    }

    internal fun swap(stage: Path, target: Path, aside: Path, move: (Path, Path) -> Unit = ::atomicMove) {
        if (!Files.exists(target)) {
            move(stage, target)
            return
        }
        // Two renames: the earlier graph steps aside, the new one takes its name. A failure between them restores the earlier graph.
        try {
            move(target, aside)
        } catch (_: java.io.IOException) {
            // A directory that cannot be renamed (a mount point, a volume) keeps its name and has its files replaced instead.
            replaceContents(stage, target)
            return
        }
        try {
            move(stage, target)
        } catch (failure: java.io.IOException) {
            move(aside, target)
            throw failure
        }
        // A process that still maps the earlier graph keeps its open files; only the names go.
        deleteRecursively(aside)
    }

    /**
     * Replaces the graph files of [target] by those of [stage] without renaming [target]. The manifest goes first and comes back last,
     * so a directory interrupted half way has no manifest and is not read as the sealed graph it no longer is.
     */
    internal fun replaceContents(stage: Path, target: Path) {
        Files.deleteIfExists(target.resolve(GraphManifest.FILE_NAME))
        Files.list(target).use { paths -> paths.toList() }.forEach(::deleteRecursively)
        val files = Files.list(stage).use { paths -> paths.toList() }.sortedBy { it.fileName.toString() == GraphManifest.FILE_NAME }
        for (file in files) Files.move(file, target.resolve(file.fileName.toString()), StandardCopyOption.REPLACE_EXISTING)
        Files.delete(stage)
    }

    private fun atomicMove(source: Path, target: Path) {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun deleteRecursively(path: Path) {
        if (Files.exists(path)) path.toFile().deleteRecursively()
    }

    private const val MAX_LISTED = 5
}
