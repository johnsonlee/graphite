package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import io.johnsonlee.graphite.webgraph.GraphStore
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(name = "build", description = ["Build graph from JAR/WAR/APK/directory and save to disk"])
class BuildCommand : Callable<Int> {

    @Parameters(index = "0", description = ["Input JAR, WAR, APK, or class directory"])
    lateinit var input: Path

    @Option(names = ["-o", "--output"], description = ["Output directory for saved graph"], required = true)
    lateinit var output: Path

    @Option(names = ["--include"], description = ["Package prefixes to include (comma-separated)"], split = ",")
    var includePackages: List<String> = emptyList()

    @Option(names = ["--exclude"], description = ["Package prefixes to exclude (comma-separated)"], split = ",")
    var excludePackages: List<String> = emptyList()

    @Option(
        names = ["--include-libs"],
        description = ["Include library JARs from WEB-INF/lib or BOOT-INF/lib, or Android platform classes for APK inputs"]
    )
    var includeLibs: Boolean = false

    @Option(
        names = ["--android-sdk"],
        description = [
            "Android SDK root for APK inputs.",
            "When omitted, search order is:",
            "1) ANDROID_HOME, 2) ANDROID_SDK_ROOT.",
            "3) Default roots for the current OS:",
            "   macOS: ~/Library/Android/sdk, /opt/homebrew/share/android-commandlinetools, " +
                "/usr/local/share/android-commandlinetools.",
            "   Linux: ~/Android/Sdk, ~/android-sdk, /opt/android-sdk, " +
                "/usr/local/android-sdk, /usr/lib/android-sdk.",
            "   Windows: %%USERPROFILE%%\\AppData\\Local\\Android\\Sdk.",
            "4) SDK roots inferred from adb, emulator, or sdkmanager on PATH."
        ]
    )
    var androidSdk: Path? = null

    @Option(names = ["--lib-filter"], description = ["Only load JARs matching these patterns (comma-separated)"], split = ",")
    var libFilters: List<String> = emptyList()

    @Option(names = ["-v", "--verbose"], description = ["Enable verbose output"])
    var verbose: Boolean = false

    override fun call(): Int {
        if (!Files.exists(input)) {
            System.err.println("Error: Input does not exist: $input")
            return 1
        }

        try {
            val config = LoaderConfig(
                includePackages = includePackages,
                excludePackages = excludePackages,
                includeLibraries = includeLibs,
                libraryFilters = libFilters,
                buildCallGraph = true,
                androidSdk = androidSdk,
                verbose = if (verbose) { msg -> System.err.println(msg) } else null
            )

            System.err.println("Loading bytecode from: $input")
            val loader = JavaProjectLoader(config)
            val graph = loader.load(input)

            val nodeCount = graph.nodes(Node::class.java).count()
            System.err.println("Graph built: $nodeCount nodes")

            System.err.println("Saving to: $output")
            GraphStore.save(graph, output)
            System.err.println("Done.")

            return 0
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            if (verbose) e.printStackTrace(System.err)
            return 1
        }
    }
}
