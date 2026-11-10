package io.johnsonlee.graphite.webgraph

import java.nio.file.FileSystems
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class AtomicFileReplacementTest {
    @Test
    fun `unsupported cross provider atomic move still replaces the complete sidecar`() {
        val dir = Files.createTempDirectory("atomic-replacement-contract")
        try {
            FileSystems.newFileSystem(dir.resolve("sidecars.zip"), mapOf("create" to "true")).use { archive ->
                val source = dir.resolve("completed.tmp")
                val target = archive.getPath("/published")
                Files.writeString(source, "complete-new-content")
                Files.writeString(target, "old-content")
                replaceAtomically(source, target)
                assertEquals("complete-new-content", Files.readString(target))
                assertFalse(Files.exists(source))
                assertFailsWith<java.nio.file.NoSuchFileException> { replaceAtomically(source, target) }
                assertEquals("complete-new-content", Files.readString(target), "unrelated errors must preserve the published file")
            }
        } finally { dir.toFile().deleteRecursively() }
    }
}
