package io.johnsonlee.graphite.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
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
 * reads as it does from `build --fold`.
 */
@Command(name = "plan", description = ["Validate a fold file and print its rules for this frontend as JSON"])
class FoldPlanCommand : Callable<Int> {

    @Parameters(index = "0", description = ["The fold file (.json, .yml or .yaml)"])
    lateinit var file: Path

    override fun call(): Int = try {
        println(FoldConfig.plan(file))
        0
    } catch (e: FoldConfigException) {
        System.err.println("Error: ${e.message}")
        1
    }
}
