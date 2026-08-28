package io.johnsonlee.graphite.cli

import io.javalin.http.Context
import io.johnsonlee.graphite.cypher.CypherCancellationSignal
import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import org.eclipse.jetty.io.Connection
import org.eclipse.jetty.server.HttpChannel
import org.eclipse.jetty.server.Request
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

internal class CypherClientCancellation private constructor(
    private val cancellationSignal: CypherCancellationSignal,
    private val cancelTask: AtomicReference<(() -> Unit)?>,
    private val channel: HttpChannel,
    private val channelListener: HttpChannel.Listener,
    private val connection: Connection,
    private val connectionListener: Connection.Listener
) : Closeable {
    fun bind(task: CypherQueryTask<*>) {
        cancelTask.set(task::cancel)
        if (cancellationSignal.isCancelled) task.cancel()
    }

    override fun close() {
        channel.removeListener(channelListener)
        connection.removeEventListener(connectionListener)
    }

    companion object {
        fun observe(ctx: Context, cancellationSignal: CypherCancellationSignal): CypherClientCancellation {
            val cancelTask = AtomicReference<(() -> Unit)?>(null)
            val cancel = {
                cancellationSignal.cancel()
                cancelTask.get()?.invoke()
            }
            val asyncListener = object : AsyncListener {
                override fun onComplete(event: AsyncEvent) = Unit
                override fun onStartAsync(event: AsyncEvent) = event.asyncContext.addListener(this)
                override fun onError(event: AsyncEvent) {
                    cancel()
                }

                override fun onTimeout(event: AsyncEvent) {
                    cancel()
                }
            }
            val channel = Request.getBaseRequest(ctx.req()).httpChannel
            val channelListener = object : HttpChannel.Listener {
                override fun onRequestFailure(request: Request, failure: Throwable) {
                    cancel()
                }

                override fun onResponseFailure(request: Request, failure: Throwable) {
                    cancel()
                }
            }
            val connection = channel.connection
            val connectionListener = object : Connection.Listener {
                override fun onOpened(connection: Connection) = Unit
                override fun onClosed(connection: Connection) {
                    cancel()
                }
            }
            val cancellation = CypherClientCancellation(
                cancellationSignal,
                cancelTask,
                channel,
                channelListener,
                connection,
                connectionListener
            )

            ctx.req().asyncContext.addListener(asyncListener)
            channel.addListener(channelListener)
            connection.addEventListener(connectionListener)

            return cancellation
        }
    }
}
