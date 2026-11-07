package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.graph.MmapGraphBuilder
import io.johnsonlee.graphite.input.FoldPlan
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FoldedBodyFailureCleanupTest : FoldedBodyObservationFixture() {
    @Test
    fun `observer failure discards partial mmap files and subsequent load succeeds`() {
        val input = smallInput()
        val directories = mutableListOf<java.nio.file.Path>()
        val loader = JavaProjectLoader(graphBuilderFactory = {
            MmapGraphBuilder(Files.createTempDirectory("observer-failed-mmap").also(directories::add))
        })
        val failure = IllegalArgumentException("cannot capture body")
        try {
            assertSame(failure, assertFails { loader.loadObserved(input) { throw failure } })
            assertEquals(1, directories.size)
            assertFalse(Files.exists(directories.single()), "the failed callback must not leave open graph outputs")
            val loaded = loader.loadObserved(input) { }
            assertTrue(loaded.observations.values.any { it.method.name == "run" })
            assertTrue(Files.exists(directories.last()), "the successful graph retains its own files")
        } finally {
            directories.forEach { it.toFile().deleteRecursively() }
        }
    }

    @Test
    fun `report failure after graph finalization still discards the failed load`() {
        val input = smallInput()
        val directory = Files.createTempDirectory("observer-failed-report")
        val failure = IllegalStateException("cannot consume final fold report")
        val loader = JavaProjectLoader(
            io.johnsonlee.graphite.input.LoaderConfig(folding = FoldPlan(listOf(gateOff)) { throw failure }),
            graphBuilderFactory = { MmapGraphBuilder(directory) }
        )
        try {
            assertSame(failure, assertFails { loader.loadObserved(input) { } })
            assertFalse(Files.exists(directory), "finalization failure must not hand off graph files")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
