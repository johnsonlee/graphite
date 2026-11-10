package io.johnsonlee.graphite.cli

import io.javalin.Javalin
import io.johnsonlee.graphite.cli.c4.C4ViewLimits
import io.johnsonlee.graphite.graph.DefaultGraph
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class ServeLifecycleContractTest {
    @Test
    fun `stopping the server completes the command and releases topology`() {
        val command = CapturingCommand()
        val result = AtomicReference<Result<Int>>()
        val thread = Thread { result.set(runCatching { command.call() }) }
        try {
            thread.start()
            command.awaitRunning(thread)
            val metrics = java.net.URI("http://localhost:${command.app.port()}/metrics").toURL().readText()
            assertTrue(metrics.contains("jvm_memory"), metrics)
            command.app.stop()
            thread.join(5_000)
            assertFalse(thread.isAlive, "command must exit after Jetty stops")
            assertEquals(0, assertNotNull(result.get()).getOrThrow())
            command.assertClosed()
        } finally {
            thread.interrupt()
            thread.join(5_000)
            command.cleanUp()
        }
    }

    @Test
    fun `interrupting the command closes the running server`() {
        val command = CapturingCommand()
        val result = AtomicReference<Result<Int>>()
        val thread = Thread { result.set(runCatching { command.call() }) }
        try {
            thread.start()
            command.awaitRunning(thread)
            thread.interrupt()
            thread.join(5_000)
            assertFalse(thread.isAlive)
            assertTrue(assertNotNull(result.get()).exceptionOrNull() is InterruptedException)
            assertTrue(command.app.jettyServer().server().isStopped)
            command.assertClosed()
        } finally {
            thread.interrupt()
            thread.join(5_000)
            command.cleanUp()
        }
    }

    @Test
    fun `port conflict releases prepared topology without running service`() {
        ServerSocket(0).use { occupied ->
            val command = CapturingCommand().apply { port = occupied.localPort }
            try {
                assertTrue(runCatching { command.call() }.isFailure)
                command.assertClosed()
            } finally {
                command.cleanUp()
            }
        }
    }

    @Test
    fun `default C4 view limit and registry route registration remain usable`() {
        val command = ServeCommand()
        val graph = DefaultGraph.Builder().build()
        assertEquals(
            command.buildC4Model(graph, "context", C4ViewLimits.FALLBACK_MODEL_ELEMENTS), command.buildC4Model(graph, "context"))
        val root = Files.createTempDirectory("serve-default-routes")
        val registry = GraphRegistry(root, io.johnsonlee.graphite.webgraph.GraphStore.LoadMode.MAPPED)
        val topology = TopologyService(registry, emptyList(), root).also { it.rebuild() }
        val app = Javalin.create { it.jsonMapper(io.javalin.json.JavalinGson(com.google.gson.Gson())) }.start(0)
        try {
            command.registerApiRoutes(app, registry, topology)
            assertTrue(java.net.URI("http://localhost:${app.port()}/api/graphs").toURL().readText().contains("\"count\":0"))
        } finally {
            app.stop()
            topology.close()
            registry.close()
            root.toFile().deleteRecursively()
        }
    }

    private class CapturingCommand : ServeCommand() {
        val root: Path = Files.createTempDirectory("serve-lifecycle")
        val started = CountDownLatch(1)
        lateinit var app: Javalin
        lateinit var registry: GraphRegistry
        lateinit var topologyDirectory: Path

        init {
            data = root
            port = 0
            metricsEnabled = true
        }

        override fun registerApiRoutes(
            app: Javalin,
            registry: GraphRegistry,
            topology: TopologyService,
            performanceMetrics: ServerPerformanceMetrics?
        ) {
            this.app = app
            this.registry = registry
            topologyDirectory = topology.currentBuildDir()
            app.events { events -> events.serverStarted { started.countDown() } }
            super.registerApiRoutes(app, registry, topology, performanceMetrics)
        }

        fun awaitRunning(thread: Thread) {
            assertTrue(started.await(5, TimeUnit.SECONDS), "server did not start")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (thread.state != Thread.State.WAITING && thread.isAlive && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(Thread.State.WAITING, thread.state)
        }

        fun assertClosed() {
            assertFalse(Files.exists(topologyDirectory), "service must release mapped topology")
            assertTrue(registry.ids().isEmpty())
        }

        fun cleanUp() {
            if (::app.isInitialized) app.stop()
            root.toFile().deleteRecursively()
        }
    }
}
