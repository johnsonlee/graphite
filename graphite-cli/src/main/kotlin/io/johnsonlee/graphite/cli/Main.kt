package io.johnsonlee.graphite.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import kotlin.system.exitProcess

@Command(
    name = "graphite",
    mixinStandardHelpOptions = true,
    version = ["graphite 1.0.0"],
    description = ["Bytecode static analysis tool for finding argument constants, return types, and more."],
    subcommands = [
        FindArgumentsCommand::class,
        FindReturnTypesCommand::class,
        FindEndpointsCommand::class,
        CommandLine.HelpCommand::class
    ]
)
class GraphiteCli : Runnable {
    override fun run() {
        CommandLine(this).usage(System.out)
    }
}

fun main(args: Array<String>) {
    val exitCode = CommandLine(GraphiteCli()).execute(*args)
    exitProcess(exitCode)
}
