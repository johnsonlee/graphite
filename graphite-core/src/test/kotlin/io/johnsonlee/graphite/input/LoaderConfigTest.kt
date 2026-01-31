package io.johnsonlee.graphite.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoaderConfigTest {

    @Test
    fun `default values`() {
        val config = LoaderConfig()
        assertFalse(config.includeLibraries)
        assertTrue(config.includePackages.isEmpty())
        assertTrue(config.excludePackages.isEmpty())
        assertTrue(config.libraryFilters.isEmpty())
        assertTrue(config.buildCallGraph)
        assertEquals(CallGraphAlgorithm.CHA, config.callGraphAlgorithm)
        assertNull(config.verbose)
    }

    @Test
    fun `CallGraphAlgorithm has 4 values`() {
        assertEquals(4, CallGraphAlgorithm.entries.size)
    }

    @Test
    fun `CallGraphAlgorithm values`() {
        val values = CallGraphAlgorithm.entries.map { it.name }
        assertTrue("CHA" in values)
        assertTrue("RTA" in values)
        assertTrue("VTA" in values)
        assertTrue("SPARK" in values)
    }

    @Test
    fun `custom config values`() {
        var verboseCalled = false
        val config = LoaderConfig(
            includeLibraries = true,
            includePackages = listOf("com.example"),
            excludePackages = listOf("com.example.internal"),
            libraryFilters = listOf("modular-*"),
            buildCallGraph = false,
            callGraphAlgorithm = CallGraphAlgorithm.RTA,
            verbose = { verboseCalled = true }
        )

        assertTrue(config.includeLibraries)
        assertEquals(listOf("com.example"), config.includePackages)
        assertEquals(listOf("com.example.internal"), config.excludePackages)
        assertEquals(listOf("modular-*"), config.libraryFilters)
        assertFalse(config.buildCallGraph)
        assertEquals(CallGraphAlgorithm.RTA, config.callGraphAlgorithm)

        config.verbose?.invoke("test")
        assertTrue(verboseCalled)
    }
}
