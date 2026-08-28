package io.johnsonlee.graphite.cli

import io.javalin.http.Context
import io.johnsonlee.graphite.cypher.CypherCancellationSignal
import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import org.eclipse.jetty.io.AbstractEndPoint
import org.eclipse.jetty.io.Connection
import org.eclipse.jetty.io.SocketChannelEndPoint
import org.eclipse.jetty.server.HttpConnection
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.util.Callback
import org.eclipse.jetty.util.thread.Scheduler
import sun.misc.Unsafe
import java.io.Closeable
import java.io.FileDescriptor
import java.io.IOException
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
            return observe(ctx, cancellationSignal) {}
        }

        internal fun observe(
            ctx: Context,
            cancellationSignal: CypherCancellationSignal,
            onBackpressure: () -> Unit
        ): CypherClientCancellation {
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
                connection as? HttpConnection,
                cancel,
                onBackpressure
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

internal class DisconnectMonitor(
    private val scheduler: Scheduler,
    private val connection: HttpConnection?,
    private val cancel: () -> Unit,
    private val onBackpressure: () -> Unit = {}
) : Runnable, Closeable {
    private val endPoint = connection?.endPoint as? AbstractEndPoint
    private val lock = Any()
    private val ownsReadInterest = AtomicBoolean()
    @Volatile
    private var backpressured = false
    private val backpressureReported = AtomicBoolean()
    private var closed = false
    private var scheduled: Scheduler.Task? = null
    private val readCallback = object : Callback {
        override fun succeeded() {
            if (!ownsReadInterest.compareAndSet(true, false) || isClosed()) return
            if (!probeReadableEndpoint()) return
            if (endPoint == null || !endPoint.isOpen) {
                cancel()
            } else if (!endPoint.isInputShutdown) {
                schedule(
                    if (backpressured) DISCONNECT_BACKPRESSURE_PROBE_MILLIS
                    else DISCONNECT_READ_INTEREST_RETRY_MILLIS
                )
            }
        }

        override fun failed(failure: Throwable) {
            if (ownsReadInterest.compareAndSet(true, false) && !isClosed()) cancel()
        }
    }

    private fun probeReadableEndpoint(): Boolean {
        val socketEndPoint = endPoint as? SocketChannelEndPoint
        val monitoredConnection = connection
        if (socketEndPoint == null || monitoredConnection == null) {
            monitoredConnection?.onFillable()
            return true
        }
        return probeSocket(socketEndPoint, monitoredConnection)
    }

    private fun probeSocket(socketEndPoint: SocketChannelEndPoint, monitoredConnection: HttpConnection): Boolean {
        val buffered = monitoredConnection.onUpgradeFrom()
        val bufferedBytes = buffered?.remaining() ?: 0
        val available = ensureRequestBufferCapacity(bufferedBytes)
        buffered?.let(monitoredConnection::onUpgradeTo)
        // Leave excess input in the socket, but keep read interest armed so a later reset is still observed.
        backpressured = available == 0
        return if (backpressured) {
            probeBackpressuredSocket(socketEndPoint)
        } else {
            readSocket(socketEndPoint, monitoredConnection, ByteBuffer.allocate(available))
        }
    }

    private fun probeBackpressuredSocket(socketEndPoint: SocketChannelEndPoint): Boolean {
        if (backpressureReported.compareAndSet(false, true)) onBackpressure()
        if (!SocketErrorProbe.hasError(socketEndPoint.channel)) return true
        val error = IOException("Client connection reset while request input was backpressured")
        socketEndPoint.close(error)
        cancel()
        return false
    }

    private fun readSocket(
        socketEndPoint: SocketChannelEndPoint,
        monitoredConnection: HttpConnection,
        probe: ByteBuffer
    ): Boolean = try {
        val read = socketEndPoint.channel.read(probe)
        when {
            read > 0 -> {
                probe.flip()
                // Keep pipelined bytes buffered until Jetty completes the active response.
                monitoredConnection.onUpgradeTo(probe)
                true
            }
            read < 0 -> {
                monitoredConnection.onFillable()
                true
            }
            else -> true
        }
    } catch (error: IOException) {
        socketEndPoint.close(error)
        cancel()
        false
    }

    @Suppress("ReturnCount")
    private fun ensureRequestBufferCapacity(bufferedBytes: Int): Int {
        val currentCapacity = connection?.inputBufferSize ?: return 0
        if (bufferedBytes < currentCapacity) return currentCapacity - bufferedBytes
        if (currentCapacity >= MAX_MONITORED_PIPELINE_BYTES) return 0
        val expandedCapacity = minOf(currentCapacity * 2, MAX_MONITORED_PIPELINE_BYTES)
        connection.inputBufferSize = expandedCapacity
        return expandedCapacity - bufferedBytes
    }

    fun start() = schedule(DISCONNECT_READ_INTEREST_DELAY_MILLIS)

    override fun run() {
        synchronized(lock) {
            scheduled = null
            if (closed) return
        }

        if (endPoint != null && !endPoint.isOpen) {
            cancel()
        } else if (endPoint?.isInputShutdown != true) {
            val registered = synchronized(lock) {
                if (closed || connection == null || endPoint == null) {
                    false
                } else if (ownsReadInterest.get()) {
                    true
                } else {
                    ownsReadInterest.set(true)
                    endPoint.tryFillInterested(readCallback).also { accepted ->
                        if (!accepted) ownsReadInterest.set(false)
                    }
                }
            }
            if (!registered) schedule(DISCONNECT_READ_INTEREST_RETRY_MILLIS)
        }
    }

    override fun close() {
        val clearReadInterest = synchronized(lock) {
            closed = true
            scheduled?.cancel()
            scheduled = null
            ownsReadInterest.compareAndSet(true, false)
        }
        if (clearReadInterest) endPoint?.fillInterest?.onFail(DISCONNECT_MONITOR_CLOSED)
    }

    private fun schedule(delayMillis: Long) {
        synchronized(lock) {
            if (!closed) {
                scheduled = scheduler.schedule(this, delayMillis, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun isClosed(): Boolean = synchronized(lock) { closed }
}

private const val DISCONNECT_READ_INTEREST_DELAY_MILLIS = 10L
private const val DISCONNECT_READ_INTEREST_RETRY_MILLIS = 50L
private const val DISCONNECT_BACKPRESSURE_PROBE_MILLIS = 250L
private const val MAX_MONITORED_PIPELINE_BYTES = 1_024 * 1_024

private object SocketErrorProbe {
    private data class SocketOptionCodes(val level: Int, val error: Int)

    private val optionCodes = when {
        System.getProperty("os.name").startsWith("Linux", ignoreCase = true) ->
            SocketOptionCodes(LINUX_SOL_SOCKET, LINUX_SO_ERROR)
        else -> SocketOptionCodes(BSD_SOL_SOCKET, BSD_SO_ERROR)
    }
    private val handles: Pair<MethodHandle, MethodHandle>? = runCatching {
        val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null) as Unsafe
        val lookupField = MethodHandles.Lookup::class.java.getDeclaredField("IMPL_LOOKUP")
        val lookup = unsafe.getObject(
            unsafe.staticFieldBase(lookupField),
            unsafe.staticFieldOffset(lookupField)
        ) as MethodHandles.Lookup
        val socketChannelImpl = Class.forName("sun.nio.ch.SocketChannelImpl")
        val net = Class.forName("sun.nio.ch.Net")
        val getFileDescriptor = lookup.findVirtual(
            socketChannelImpl,
            "getFD",
            MethodType.methodType(FileDescriptor::class.java)
        )
        val getSocketError = lookup.findStatic(
            net,
            "getIntOption0",
            MethodType.methodType(
                Int::class.javaPrimitiveType,
                FileDescriptor::class.java,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        )
        getFileDescriptor to getSocketError
    }.getOrNull()

    fun hasError(channel: SocketChannel): Boolean {
        val (getFileDescriptor, getSocketError) = handles ?: return false
        return runCatching {
            // SO_ERROR exposes a pending reset without consuming or producing HTTP bytes.
            val descriptor = getFileDescriptor.invokeWithArguments(channel) as FileDescriptor
            val error = getSocketError.invokeWithArguments(
                descriptor,
                false,
                optionCodes.level,
                optionCodes.error
            ) as Int
            error != 0
        }.getOrDefault(false)
    }
}

private const val LINUX_SOL_SOCKET = 1
private const val LINUX_SO_ERROR = 4
private const val BSD_SOL_SOCKET = 0xffff
private const val BSD_SO_ERROR = 0x1007

private object DisconnectMonitorClosedException : IOException("Cypher disconnect monitor closed") {
    override fun fillInStackTrace(): Throwable = this
}
private val DISCONNECT_MONITOR_CLOSED = DisconnectMonitorClosedException
