package io.johnsonlee.graphite.cli

import picocli.CommandLine

/** Internal launcher probe. It parses with the public CLI model without executing a command. */
fun main(args: Array<String>) {
    println(if (routesToNativeServe(args)) "serve" else "jvm")
}

@Suppress("SpreadOperator") // Picocli exposes a Java varargs parser.
internal fun routesToNativeServe(args: Array<String>): Boolean {
    val parsed = try {
        CommandLine(GraphiteCommand()).parseArgs(*args)
    } catch (_: CommandLine.ParameterException) {
        return false
    }
    var current = parsed
    var helpRequested = false
    while (true) {
        helpRequested = helpRequested || current.isUsageHelpRequested || current.isVersionHelpRequested
        val next = current.subcommand() ?: break
        current = next
    }
    return !helpRequested && current.commandSpec().userObject() is NativeServeCommand
}
