package io.johnsonlee.graphite.cli

import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeServerLauncherTest {
    @Test
    fun `selects supported artifact aliases and fails explicitly elsewhere`() {
        assertEquals("linux-amd64", NativeServerLauncher.platform("Linux", "x86_64"))
        assertEquals("linux-arm64", NativeServerLauncher.platform("Linux", "aarch64"))
        assertEquals("darwin-amd64", NativeServerLauncher.platform("Mac OS X", "amd64"))
        assertEquals("darwin-arm64", NativeServerLauncher.platform("Darwin", "arm64"))
        assertFailsWith<IllegalStateException> { NativeServerLauncher.platform("Windows 11", "amd64") }
        assertFailsWith<IllegalStateException> { NativeServerLauncher.platform("Linux", "sparc") }
    }

    @Test
    fun `extracts only checksum-verified resource into private executable then removes it`() {
        val bytes = "#!/bin/sh\nexit 37\n".toByteArray()
        val launcher = resourceLauncher(bytes)
        lateinit var extracted: Path
        launcher.resolve().use { binary ->
            extracted = binary.path
            assertTrue(Files.readAllBytes(extracted).contentEquals(bytes))
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(extracted))
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(extracted.parent))
        }
        assertFalse(Files.exists(extracted))
        assertFalse(Files.exists(extracted.parent))
        assertEquals(37, launcher.launch(emptyList()))
    }

    @Test
    fun `missing corrupt duplicate and oversized manifest resources never fall back`() {
        val bytes = "#!/bin/sh\nexit 0\n".toByteArray()
        assertFailsWith<IllegalStateException> { NativeServerLauncher(null, "Linux", "amd64") { null }.resolve() }
        assertFailsWith<IllegalArgumentException> { resourceLauncher(bytes, "0".repeat(64)).resolve() }
        val line = "${hash(bytes)}  linux-amd64/graphite-server\n"
        for (manifest in listOf(line + line, "bad\n", "x".repeat(65537))) {
            assertFailsWith<IllegalArgumentException> {
                NativeServerLauncher(null, "Linux", "amd64") { name ->
                    if (name.endsWith("SHA256SUMS")) manifest.byteInputStream() else bytes.inputStream()
                }.resolve()
            }
        }
        assertFailsWith<IllegalArgumentException> { NativeServerLauncher("").resolve() }
        assertFailsWith<IllegalArgumentException> { NativeServerLauncher("/no/such/native-graphite").resolve() }
    }

    @Test
    fun `actual child receives whitespace and metacharacters without shell expansion and retains status`() {
        val dir = Files.createTempDirectory("native-child-arguments-")
        try {
            val output = dir.resolve("arguments.txt")
            val child = script(dir, "printf '%s\\n' \"\$@\" > '${output}'\nexit 37\n")
            val args = listOf("serve", "--data", "path with spaces", "--graph", "a:x;echo injected", "\$(touch no)")
            assertEquals(37, NativeServerLauncher(child.toString()).launch(args))
            assertEquals(args, Files.readAllLines(output))
            assertTrue(Files.exists(child)) // An explicit executable is not owned/deleted.
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `both public command classes dispatch parsed arguments to actual native child`() {
        val dir = Files.createTempDirectory("native-command-")
        val previous = System.getProperty("graphite.server.binary")
        try {
            val output = dir.resolve("arguments.txt")
            val child = script(dir, "printf '%s\\n' \"\$@\" > '${output}'\nexit 23\n")
            System.setProperty("graphite.server.binary", child.toString())
            for (command in listOf(NativeServeCommand(), NativeExploreCommand())) {
                val code = CommandLine(command).execute("--id", "app", "path with spaces", "--graph", "x:path;literal")
                assertEquals(23, code)
                assertEquals(listOf("serve", "--id", "app", "path with spaces", "--graph", "x:path;literal"), Files.readAllLines(output))
            }
        } finally {
            if (previous == null) System.clearProperty("graphite.server.binary")
            else System.setProperty("graphite.server.binary", previous)
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `literal argument-file prefixes survive both command parsers`() {
        val dir = Files.createTempDirectory("native-literal-at-")
        val previous = System.getProperty("graphite.server.binary")
        try {
            val output = dir.resolve("arguments.txt")
            val child = script(dir, "printf '%s\\n' \"\$@\" > '${output}'\nexit 23\n")
            System.setProperty("graphite.server.binary", child.toString())
            for (command in listOf(NativeServeCommand(), NativeExploreCommand())) {
                for ((input, transported) in listOf("@@literal" to "@@literal", "@@@literal" to "@@@literal", "@" to "@@")) {
                    assertEquals(23, CommandLine(command).execute("--data", input))
                    assertEquals(listOf("serve", "--data", transported), Files.readAllLines(output))
                }
            }
        } finally {
            if (previous == null) System.clearProperty("graphite.server.binary")
            else System.setProperty("graphite.server.binary", previous)
            dir.toFile().deleteRecursively()
        }
    }

    private fun script(dir: Path, body: String): Path {
        val child = dir.resolve("native server")
        Files.writeString(child, "#!/bin/sh\n$body")
        Files.setPosixFilePermissions(child, PosixFilePermissions.fromString("rwx------"))
        return child
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun resourceLauncher(bytes: ByteArray, digest: String = hash(bytes)): NativeServerLauncher =
        NativeServerLauncher(null, "Linux", "amd64") { name ->
            when (name) {
                "/graphite-native/SHA256SUMS" -> "$digest  linux-amd64/graphite-server\n".byteInputStream()
                "/graphite-native/linux-amd64/graphite-server" -> bytes.inputStream()
                else -> null
            }
        }
}
