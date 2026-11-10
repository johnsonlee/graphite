package io.johnsonlee.graphite.webgraph

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GraphRoutingLifecycleTest {
    @Test
    fun `launcher writes replay counters and releases resources before announcing completion`() {
        val root = Files.createTempDirectory("routing-lifecycle")
        val artifact = root.resolve("metrics.json")
        val events = mutableListOf<String>()
        try {
            val output = captureOutput {
                // This fixture checks command orchestration, not graph correctness or performance.
                runGraphRoutingCorrectness(arrayOf("cold", "graph-routing", "1000", artifact.toString()),
                    setup = { events += "setup" },
                    prepare = { events += "prepare" },
                    replay = { counters ->
                        events += "replay"
                        counters.queryCount = 3
                        counters.successCount = 3
                        counters.totalRows = 6
                        6L
                    },
                    cleanup = {
                        assertTrue(Files.isRegularFile(artifact))
                        events += "cleanup"
                    })
            }
            assertEquals(listOf("setup", "prepare", "replay", "cleanup"), events)
            assertEquals("CORRECTNESS_PASS\tgraph-routing\tcold\tgraph-routing", output.trim())
            val json = Files.readString(artifact)
            assertTrue(json.contains("\"queryCount\":3"))
            assertTrue(json.contains("\"successCount\":3"))
            assertTrue(json.contains("\"totalRows\":6"))
            assertTrue(json.contains("\"scope\":\"correctness-only\""))
            assertFalse(json.contains("latency"))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun `each failed lifecycle stage releases resources without publishing a success artifact`() {
        val stages = listOf("setup", "prepare", "replay")
        for (failed in stages) {
            val root = Files.createTempDirectory("routing-lifecycle-failure")
            val artifact = root.resolve("metrics.json")
            val events = mutableListOf<String>()
            val failure = IllegalStateException("failed $failed")
            fun stage(name: String) {
                events += name
                if (name == failed) throw failure
            }
            try {
                val output = captureOutput {
                    assertSame(failure, assertFailsWith<IllegalStateException> {
                        runGraphRoutingCorrectness(arrayOf("warm", "graph-routing", "1000", artifact.toString()),
                            setup = { stage("setup") }, prepare = { stage("prepare") },
                            replay = { stage("replay"); 0L }, cleanup = { events += "cleanup" })
                    })
                }
                assertEquals(stages.take(stages.indexOf(failed) + 1) + "cleanup", events)
                assertEquals("", output)
                assertFalse(Files.exists(artifact))
            } finally { root.toFile().deleteRecursively() }
        }
    }

    @Test
    fun `artifact failure still releases the suite and does not announce success`() {
        val directory = Files.createTempDirectory("routing-write-failure")
        var cleaned = false
        try {
            val output = captureOutput {
                assertFailsWith<IOException> {
                    runGraphRoutingCorrectness(arrayOf("cold", "graph-routing", "1000", directory.toString()),
                        setup = {}, prepare = {}, replay = { 0L }, cleanup = { cleaned = true })
                }
            }
            assertTrue(cleaned)
            assertEquals("", output)
        } finally { Files.delete(directory) }
    }

    @Test
    fun `real launcher preserves the required heap gate and emits no false pass`() {
        assertTrue(Runtime.getRuntime().maxMemory() < 8L * 1024 * 1024 * 1024)
        val root = Files.createTempDirectory("routing-heap-gate")
        val artifact = root.resolve("metrics.json")
        try {
            val output = captureOutput {
                val failure = assertFailsWith<IllegalStateException> {
                    GraphRoutingCorrectness.main(arrayOf("cold", "graph-routing", "1000", artifact.toString()))
                }
                assertTrue(failure.message.orEmpty().contains("must run with -Xmx8g"))
            }
            assertEquals("", output)
            assertFalse(Files.exists(artifact))
        } finally { root.toFile().deleteRecursively() }
    }

    private fun captureOutput(action: () -> Unit): String {
        val bytes = ByteArrayOutputStream()
        val previous = System.out
        try {
            PrintStream(bytes).use { output ->
                System.setOut(output)
                action()
            }
        } finally { System.setOut(previous) }
        return bytes.toString(Charsets.UTF_8)
    }
}
