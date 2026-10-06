package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.input.LoaderConfig
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.Callable

/** `graphite fold`: what the frontend does with a fold file apart from building with it. */
@Command(name = "fold", description = ["Validate and normalise fold files"], subcommands = [FoldPlanCommand::class])
class FoldCommand : Callable<Int> {
    override fun call(): Int {
        CommandLine(this).usage(System.out)
        return 0
    }
}

/**
 * `graphite fold plan <file>`: validate the file and print its rules for this frontend as JSON,
 * in the file's shape. The graphite CLI reads that to find the `select` rules it has to
 * resolve on a graph built without rules; the file is validated once, here, and every error
 * reads as it does from `build --fold`. With `--input`, the plan is for a build of that input: it
 * carries the provenance of a build of that input with the options given (the same ones `build`
 * takes), and a `select` rule resolved on another input, frontend or set of options comes back
 * unresolved, so the CLI runs its query again.
 */
@Command(name = "plan", description = ["Validate a fold file and print its rules for this frontend as JSON"])
class FoldPlanCommand : Callable<Int> {

    @Parameters(index = "0", description = ["The fold file (.json, .yml or .yaml)"])
    lateinit var file: Path

    @Option(names = ["--input"], description = ["The input the plan is for: its provenance is recorded and stale selections dropped"])
    var input: Path? = null

    @Option(names = ["--include"], description = ["The build's package prefixes to include (comma-separated)"], split = ",")
    var includePackages: List<String> = emptyList()

    @Option(names = ["--exclude"], description = ["The build's package prefixes to exclude (comma-separated)"], split = ",")
    var excludePackages: List<String> = emptyList()

    @Option(names = ["--include-libs"], description = ["Whether the build includes library JARs or the Android platform"])
    var includeLibs: Boolean = false

    @Option(names = ["--android-sdk"], description = ["The build's Android SDK root, for an APK input"])
    var androidSdk: Path? = null

    @Option(names = ["--lib-filter"], description = ["The build's library JAR patterns (comma-separated)"], split = ",")
    var libFilters: List<String> = emptyList()

    override fun call(): Int = try {
        val config = LoaderConfig(
            includePackages = includePackages,
            excludePackages = excludePackages,
            includeLibraries = includeLibs,
            libraryFilters = libFilters,
            androidSdk = androidSdk
        )
        println(FoldConfig.plan(file, input, config))
        0
    } catch (e: FoldConfigException) {
        System.err.println("Error: ${e.message}")
        1
    } catch (e: IOException) {
        System.err.println("Error: cannot read the input $input: ${e.message}")
        1
    }
}
