package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.input.AnalysisIdentity
import io.johnsonlee.graphite.input.FoldProvenance
import io.johnsonlee.graphite.input.FoldSites
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.androidPlatformJar
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isDirectory

/**
 * The SHA-256 of a build input, which a resolved `select` rule records as the input its keys were
 * read from. A file (a jar, a war, an APK) is hashed as its bytes. A class directory is hashed as
 * its regular files in the order of their relative paths, `/`-separated, each as its path, a NUL,
 * its size and its bytes, so a renamed, added, removed or edited class changes the digest and the
 * directory's location and the file system's listing order do not.
 */
object InputDigest {
    private const val BUFFER = 1 shl 16

    fun sha256(input: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        if (input.isDirectory()) {
            val files = Files.walk(input).use { paths ->
                paths.filter(Files::isRegularFile).map { input.relativize(it).joinToString("/") to it }.toList()
            }
            files.sortedBy { it.first }.forEach { (relative, file) ->
                digest.update(relative.toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(Files.size(file).toString().toByteArray(Charsets.US_ASCII))
                digest.update(0)
                update(digest, file)
            }
        } else {
            update(digest, input)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun update(digest: MessageDigest, file: Path) {
        Files.newInputStream(file).use { stream ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
    }
}

/** Where the keys of a `select` rule come from, and whether they may be folded on this build. */
internal object SelectionProvenance {

    /**
     * The provenance a key read off a graph of [input] built by this frontend with [config]
     * carries: the input's digest, the frontend's version and the options that decide which
     * classes the build reads (package filters, libraries, and the platform jar an APK build
     * reads, by content).
     */
    fun of(input: Path, config: LoaderConfig = LoaderConfig()): FoldProvenance = FoldProvenance(
        InputDigest.sha256(input),
        GraphiteVersionProvider().version.single().removePrefix("graphite "),
        AnalysisIdentity(
            includePackages = config.includePackages.sorted(),
            excludePackages = config.excludePackages.sorted(),
            includeLibraries = config.includeLibraries,
            libraryFilters = config.libraryFilters.sorted(),
            androidPlatformSha256 = androidPlatformJar(input, config)?.let(InputDigest::sha256)
        )
    )

    /**
     * Why the keys of a resolved [fold] cannot be folded on the build [current] describes, or
     * `null` when they can (or the rule is not resolved): what differs, each as `this vs that`.
     */
    fun staleness(fold: FoldSites, current: FoldProvenance): String? {
        val provenance = fold.provenance
        return when {
            !fold.resolved -> null
            provenance == null -> "its '${FoldSites.SELECTED_KEY}' call sites carry no '${FoldSites.PROVENANCE_KEY}'"
            provenance != current -> "its '${FoldSites.SELECTED_KEY}' call sites were read off a build that differs from " +
                "this one in ${provenance.differences(current).joinToString("; ")} (theirs vs this build's)"
            else -> null
        }
    }
}
