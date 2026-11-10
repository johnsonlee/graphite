package io.johnsonlee.graphite.cli

import io.javalin.http.Context
import io.johnsonlee.graphite.cypher.CypherCancellationSignal
import org.eclipse.jetty.io.ByteArrayEndPoint
import org.eclipse.jetty.server.HttpConfiguration
import org.eclipse.jetty.server.HttpConnection
import org.eclipse.jetty.server.HttpConnectionFactory
import org.eclipse.jetty.server.LocalConnector
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.util.component.AbstractLifeCycle
import org.eclipse.jetty.util.thread.Scheduler
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.EventListener
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisconnectMonitorContractTest {
    @Test
    fun `connection opened notification preserves the query and close notification cancels it`() {
        val scheduler = ControlledScheduler()
        val endPoint = ByteArrayEndPoint(scheduler, 0).apply { onOpen() }
        val connection = ReadingConnection(scheduler, endPoint)
        val signal = CypherCancellationSignal()
        val listener = CypherCancellationConnectionListener { signal.cancel() }
        try {
            listener.onOpened(connection)
            assertFalse(signal.isCancelled)
            listener.onClosed(connection)
            assertTrue(signal.isCancelled)
        } finally {
            endPoint.close()
        }
    }

    @Test
    fun `non socket transport delegates readable bytes and keeps only one read interest`() {
        val scheduler = ControlledScheduler()
        val endPoint = ByteArrayEndPoint(scheduler, 0).apply { onOpen() }
        val connection = ReadingConnection(scheduler, endPoint)
        val cancelled = AtomicInteger()
        val monitor = DisconnectMonitor(scheduler, connection, { cancelled.incrementAndGet() })
        try {
            monitor.start()
            scheduler.runNext()
            assertTrue(endPoint.fillInterest.isInterested)
            monitor.run()
            assertTrue(endPoint.fillInterest.isInterested)
            assertEquals(0, scheduler.tasks.size, "An owned read interest must not install another timer")

            endPoint.addInput("first")
            endPoint.fillInterest.fillable()
            assertEquals(listOf("first"), connection.received)
            assertEquals(0, cancelled.get())
            assertEquals(1, scheduler.tasks.size)

            scheduler.runNext()
            connection.closeAfterRead = true
            endPoint.addInput("last")
            endPoint.fillInterest.fillable()
            assertEquals(listOf("first", "last"), connection.received)
            assertFalse(endPoint.isOpen)
            assertEquals(1, cancelled.get(), "Closing during the readable callback must cancel the query")
        } finally {
            monitor.close()
            endPoint.close()
        }
    }

    @Test
    fun `closed endpoint cancels without rearming and closed monitor ignores a pending timer`() {
        val scheduler = ControlledScheduler()
        val endPoint = ByteArrayEndPoint(scheduler, 0).apply { onOpen() }
        val cancelled = AtomicInteger()
        val monitor = DisconnectMonitor(scheduler, null, { cancelled.incrementAndGet() }, monitoredEndPoint = endPoint)
        monitor.start()
        val pending = scheduler.tasks.single()
        endPoint.close()
        scheduler.runNext()
        assertEquals(1, cancelled.get())
        assertTrue(scheduler.tasks.isEmpty())
        monitor.close()
        pending.run()
        assertEquals(1, cancelled.get())
        assertTrue(scheduler.tasks.isEmpty())
    }

    @Test
    fun `observer startup rejection removes the connection listener and restores idle timeout`() {
        val scheduler = ControlledScheduler()
        val endPoint = ByteArrayEndPoint(scheduler, 5000).apply { onOpen() }
        val connection = ReadingConnection(scheduler, endPoint)
        val signal = CypherCancellationSignal()
        val context = Proxy.newProxyInstance(Context::class.java.classLoader, arrayOf(Context::class.java)) { _, method, _ ->
            check(method.name == "req") { "Unexpected context access: ${method.name}" }
            connection.httpChannel.request
        } as Context
        try {
            scheduler.reject = true
            assertFailsWith<RejectedExecutionException> { CypherClientCancellation.observe(context, signal) }
            assertTrue(connection.listeners.isEmpty(), "Failed setup must remove its disconnect listener")
            assertEquals(5000L, endPoint.idleTimeout)
            assertFalse(signal.isCancelled, "Scheduler rejection is not a client disconnect")
        } finally {
            endPoint.close()
        }
    }

    private class ControlledScheduler : AbstractLifeCycle(), Scheduler {
        val tasks = ArrayDeque<Runnable>()
        var reject = false

        override fun schedule(task: Runnable, delay: Long, units: TimeUnit): Scheduler.Task {
            if (reject) throw RejectedExecutionException("scheduler stopped")
            tasks.addLast(task)
            return Scheduler.Task { tasks.remove(task) }
        }

        fun runNext() = tasks.removeFirst().run()
    }

    private class ReadingConnection(scheduler: Scheduler, private val input: ByteArrayEndPoint) : HttpConnection(
        HttpConfiguration(),
        LocalConnector(Server(), Runnable::run, scheduler, null, 0, HttpConnectionFactory()),
        input,
        false
    ) {
        val received = mutableListOf<String>()
        val listeners = mutableSetOf<EventListener>()
        var closeAfterRead = false

        init {
            input.connection = this
        }

        override fun onFillable() {
            // Jetty EndPoint.fill takes a buffer in flush mode, unlike SocketChannel.read.
            val buffer = ByteBuffer.allocate(64).apply { limit(0) }
            val read = input.fill(buffer)
            if (read > 0) received.add(String(buffer.array(), 0, read, Charsets.UTF_8))
            if (closeAfterRead) input.close()
        }

        override fun addEventListener(listener: EventListener) {
            listeners.add(listener)
            super.addEventListener(listener)
        }

        override fun removeEventListener(listener: EventListener) {
            listeners.remove(listener)
            super.removeEventListener(listener)
        }
    }
}
