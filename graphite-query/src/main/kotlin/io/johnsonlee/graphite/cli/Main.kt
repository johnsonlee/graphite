package io.johnsonlee.graphite.cli

import picocli.CommandLine
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val exitCode = CommandLine(QueryCommand()).execute(*args)
    exitProcess(exitCode)
}
