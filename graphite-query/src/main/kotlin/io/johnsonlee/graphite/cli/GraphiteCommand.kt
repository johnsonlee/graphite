package io.johnsonlee.graphite.cli

import picocli.CommandLine
import picocli.CommandLine.*
import java.util.concurrent.Callable

@Command(
    name = "graphite",
    description = ["Build and query Graphite graphs"],
    mixinStandardHelpOptions = true,
    subcommands = [
        BuildCommand::class,
        QueryCommand::class
    ]
)
class GraphiteCommand : Callable<Int> {
    override fun call(): Int {
        CommandLine(this).usage(System.out)
        return 0
    }
}
