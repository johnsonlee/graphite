package io.johnsonlee.graphite.cli

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val NATIVE_RESOURCE_ROOT = "/graphite-native/"
private const val NATIVE_BINARY_NAME = "graphite-server"
private const val MAX_MANIFEST_BYTES = 65536
private const val MAX_NATIVE_BYTES = 128L * 1024 * 1024
private const val CHILD_SHUTDOWN_SECONDS = 5L
private const val INTERRUPTED_EXIT_CODE = 130

/** Native server resolution is offline and never falls back to the JVM server. */
internal class NativeServerLauncher(
    private val configuredBinary: String? = System.getProperty("graphite.server.binary")
        ?: System.getenv("GRAPHITE_SERVER_BINARY"),
    private val osName: String = System.getProperty("os.name"),
    private val architecture: String = System.getProperty("os.arch"),
    private val resource: (String) -> InputStream? = { NativeServerLauncher::class.java.getResourceAsStream(it) }
) {
    fun launch(arguments: List<String>): Int {
        resolve().use { binary ->
            val process = ProcessBuilder(listOf(binary.path.toString()) + arguments).inheritIO().start()
            val shutdown = Thread({
                stop(process)
                binary.close()
            }, "graphite-native-shutdown")
            try {
                Runtime.getRuntime().addShutdownHook(shutdown)
                return process.waitFor()
            } catch (interrupted: InterruptedException) {
                stop(process)
                Thread.currentThread().interrupt()
                return INTERRUPTED_EXIT_CODE
            } finally {
                stop(process)
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown)
                } catch (_: IllegalStateException) {
                    // A running shutdown hook owns the same idempotent cleanup.
                }
            }
        }
    }

    internal fun resolve(): NativeServerBinary {
        configuredBinary?.let { value ->
            require(value.isNotBlank()) { "Configured native server path is empty" }
            val path = Path.of(value).toAbsolutePath().normalize()
            require(Files.isRegularFile(path) && Files.isExecutable(path)) {
                "Native server is not an executable file: $path"
            }
            return NativeServerBinary(path)
        }
        val platform = platform(osName, architecture)
        val entry = "$platform/$NATIVE_BINARY_NAME"
        val manifest = resource("${NATIVE_RESOURCE_ROOT}SHA256SUMS")?.use { stream ->
            val bytes = stream.readNBytes(MAX_MANIFEST_BYTES + 1)
            require(bytes.size <= MAX_MANIFEST_BYTES) { "Native server checksum manifest is too large" }
            bytes.toString(Charsets.US_ASCII)
        } ?: error("Native server resources are missing; install a complete Graphite distribution or set GRAPHITE_SERVER_BINARY")
        val matches = manifest.lineSequence().filter { it.isNotBlank() }.map { line ->
            val pieces = line.trim().split(Regex("\\s+"), limit = 2)
            require(pieces.size == 2 && pieces[0].matches(Regex("[0-9a-fA-F]{64}"))) {
                "Invalid native server checksum manifest"
            }
            pieces[1] to pieces[0].lowercase()
        }.filter { it.first == entry }.toList()
        require(matches.size == 1) { "Native server checksum is missing or duplicated for $platform" }
        val source = resource("$NATIVE_RESOURCE_ROOT$entry")
            ?: error("Native server resource is missing for $platform")
        return source.use { input -> extract(input, matches.single().second, platform) }
    }

    private fun extract(input: InputStream, expected: String, platform: String): NativeServerBinary {
        val dir = Files.createTempDirectory("graphite-native-", PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rwx------")
        ))
        val binary = NativeServerBinary(dir.resolve(NATIVE_BINARY_NAME), dir)
        var complete = false
        try {
            val actual = writeBinary(input, binary.path)
            require(actual == expected) { "Native server checksum mismatch for $platform" }
            Files.setPosixFilePermissions(binary.path, PosixFilePermissions.fromString("rwx------"))
            complete = true
            return binary
        } finally {
            if (!complete) binary.close()
        }
    }

    private fun writeBinary(input: InputStream, path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_NATIVE_BYTES) { "Native server resource is too large" }
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun stop(process: Process) {
        if (!process.isAlive) return
        process.destroy()
        try {
            if (!process.waitFor(CHILD_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(CHILD_SHUTDOWN_SECONDS, TimeUnit.SECONDS)
            }
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        internal fun platform(os: String, arch: String): String {
            val operatingSystem = when {
                os.equals("Linux", ignoreCase = true) -> "linux"
                os.equals("Mac OS X", ignoreCase = true) || os.equals("Darwin", ignoreCase = true) -> "darwin"
                else -> error("Native Graphite server is not supported on $os; build and query remain available")
            }
            val cpu = when (arch.lowercase()) {
                "amd64", "x86_64" -> "amd64"
                "aarch64", "arm64" -> "arm64"
                else -> error("Native Graphite server is not supported on architecture $arch")
            }
            return "$operatingSystem-$cpu"
        }
    }
}

internal class NativeServerBinary(val path: Path, private val directory: Path? = null) : AutoCloseable {
    private val closed = AtomicBoolean()
    override fun close() {
        if (directory != null && closed.compareAndSet(false, true)) {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }
}

@Command(
    name = "serve",
    description = ["Serve one or more saved Graphite webgraphs over HTTP"],
    mixinStandardHelpOptions = true
)
open class NativeServeCommand : ServeCommand() {
    @Spec
    private lateinit var nativeCommandSpec: CommandSpec

    @Suppress("TooGenericExceptionCaught") // CLI boundary reports launch failures without running a fallback server.
    override fun call(): Int = try {
        // Picocli already expanded @files. Escape remaining literal @ prefixes
        // so the native parser's single expansion pass cannot open them again.
        val arguments = nativeCommandSpec.commandLine().parseResult.expandedArgs().map {
            if (it.startsWith("@")) "@$it" else it
        }
        NativeServerLauncher().launch(listOf("serve") + arguments)
    } catch (failure: Exception) {
        System.err.println("Error: ${failure.message}")
        1
    }
}

@Command(
    name = "graphite-explore",
    description = ["Interactive web visualization for saved Graphite graphs"],
    mixinStandardHelpOptions = true
)
class NativeExploreCommand : NativeServeCommand()
