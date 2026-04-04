package io.johnsonlee.graphite.cli

import picocli.CommandLine
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val exitCode = CommandLine(FindDeadCodeCommand()).execute(*args)
    exitProcess(exitCode)
}
