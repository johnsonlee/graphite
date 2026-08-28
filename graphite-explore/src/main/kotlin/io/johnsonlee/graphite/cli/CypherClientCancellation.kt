package io.johnsonlee.graphite.cli

import io.javalin.http.Context
import io.johnsonlee.graphite.cypher.CypherCancellationSignal
import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import org.eclipse.jetty.io.AbstractConnection
import org.eclipse.jetty.io.Connection
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.util.thread.Scheduler
import java.io.Closeable
import java.nio.channels.ReadPendingException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class CypherClientCancellation private constructor(
    private val cancellationSignal: CypherCancellationSignal,
    private val cancelTask: AtomicReference<(() -> Unit)?>,
    private val connection: Connection,
    private val connectionListener: Connection.Listener,
    private val disconnectMonitor: DisconnectMonitor
) : Closeable {

    fun bind(task: CypherQueryTask<*>) {
        cancelTask.set(task::cancel)
        if (cancellationSignal.isCancelled) task.cancel()
    }

    override fun close() {
        disconnectMonitor.close()
        connection.removeEventListener(connectionListener)
    }

    companion object {
        fun observe(ctx: Context, cancellationSignal: CypherCancellationSignal): CypherClientCancellation {
            val cancelTask = AtomicReference<(() -> Unit)?>(null)
            val cancel: () -> Unit = {
                cancellationSignal.cancel()
                cancelTask.get()?.invoke()
                Unit
            }
            val asyncListener = CypherCancellationAsyncListener(cancel)
            val channel = Request.getBaseRequest(ctx.req()).httpChannel
            val connection = channel.connection
            val connectionListener = CypherCancellationConnectionListener(cancel)
            val disconnectMonitor = DisconnectMonitor(
                channel.scheduler,
                connection as? AbstractConnection,
                cancel
            )

            ctx.req().asyncContext.addListener(asyncListener)
            connection.addEventListener(connectionListener)
            disconnectMonitor.start()

            return CypherClientCancellation(
                cancellationSignal,
                cancelTask,
                connection,
                connectionListener,
                disconnectMonitor
            )
        }
    }
}

internal class CypherCancellationAsyncListener(
    private val cancel: () -> Unit
) : AsyncListener {
    override fun onComplete(event: AsyncEvent) = Unit
    override fun onStartAsync(event: AsyncEvent) = event.asyncContext.addListener(this)
    override fun onError(event: AsyncEvent) = cancel()
    override fun onTimeout(event: AsyncEvent) = cancel()
}

internal class CypherCancellationConnectionListener(
    private val cancel: () -> Unit
) : Connection.Listener {
    override fun onOpened(connection: Connection) = Unit
    override fun onClosed(connection: Connection) = cancel()
}

private class DisconnectMonitor(
    private val scheduler: Scheduler,
    private val connection: AbstractConnection?,
    private val cancel: () -> Unit
) : Runnable, Closeable {
    private val lock = Any()
    private var closed = false
    private var scheduled: Scheduler.Task? = null

    fun start() = schedule(DISCONNECT_READ_INTEREST_DELAY_MILLIS)

    override fun run() {
        synchronized(lock) {
            scheduled = null
            if (closed) return
        }

        val endPoint = connection?.endPoint
        if (endPoint != null && (!endPoint.isOpen || endPoint.isInputShutdown)) {
            cancel()
            return
        }
        if (connection != null && !connection.isFillInterested) {
            try {
                connection.fillInterested()
            } catch (_: ReadPendingException) {
                // Jetty won the race and already registered its own read callback.
            }
        }
        schedule(DISCONNECT_POLL_INTERVAL_MILLIS)
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            scheduled?.cancel()
            scheduled = null
        }
    }

    private fun schedule(delayMillis: Long) {
        synchronized(lock) {
            if (!closed) {
                scheduled = scheduler.schedule(this, delayMillis, TimeUnit.MILLISECONDS)
            }
        }
    }
}

private const val DISCONNECT_READ_INTEREST_DELAY_MILLIS = 10L
private const val DISCONNECT_POLL_INTERVAL_MILLIS = 50L
