package io.johnsonlee.graphite.cli

import kotlin.system.exitProcess
import picocli.CommandLine

fun main(args: Array<String>) {
    // Picocli accepts command-line arguments through its vararg API.
    @Suppress("SpreadOperator") val exitCode = CommandLine(GraphiteCommand()).execute(*args)
    exitProcess(exitCode)
}
