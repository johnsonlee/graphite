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
 * A target that cannot be renamed (a mount point, a volume root) is refused rather than emptied and refilled: replacing its files one by
 * one would delete the earlier graph before the new one is complete, and a directory left half way would have no manifest and read as a
 * legacy graph, unchecked. Such a target keeps its earlier graph and the save asks for a directory below it.
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
        } catch (refused: java.io.IOException) {
            throw java.io.IOException(
                "Cannot replace $target as a whole: it cannot be renamed (${refused.message}), and replacing its files one by one could " +
                    "lose the earlier graph. It is unchanged; save into a directory below it instead.",
                refused)
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

    private fun atomicMove(source: Path, target: Path) {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun deleteRecursively(path: Path) {
        if (Files.exists(path)) path.toFile().deleteRecursively()
    }

    private const val MAX_LISTED = 5
}
