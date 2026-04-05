package io.johnsonlee.graphite.cli

import picocli.CommandLine
import picocli.CommandLine.*
import java.util.concurrent.Callable

@Command(
    name = "graphite-query",
    description = ["Query and visualize saved Graphite graphs"],
    mixinStandardHelpOptions = true,
    subcommands = [
        BuildCommand::class,
        InfoCommand::class,
        NodesCommand::class,
        CallSitesCommand::class,
        MethodsCommand::class,
        AnnotationsCommand::class,
        ServeCommand::class
    ]
)
class QueryCommand : Callable<Int> {
    override fun call(): Int {
        CommandLine(this).usage(System.out)
        return 0
    }
}
