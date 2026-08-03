package io.johnsonlee.graphite.cli

import picocli.CommandLine
import kotlin.system.exitProcess

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    val exitCode = CommandLine(ExploreCommand()).execute(*args)
    exitProcess(exitCode)
}
