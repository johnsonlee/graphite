package io.johnsonlee.graphite.cli

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.javalin.http.ContentType
import io.javalin.http.Context
import io.johnsonlee.graphite.cypher.CypherCancellationSignal
import java.io.StringWriter
import java.io.Writer

internal val GRAPHITE_GSON: Gson = GsonBuilder().setPrettyPrinting().create()

internal fun interface CypherResponseSerializer {
    fun write(ctx: Context, value: Map<String, Any?>, cancellationSignal: CypherCancellationSignal)
}

internal class GsonCypherResponseSerializer(
    private val gson: Gson = GRAPHITE_GSON,
    private val progress: () -> Unit = {}
) : CypherResponseSerializer {

    override fun write(
        ctx: Context,
        value: Map<String, Any?>,
        cancellationSignal: CypherCancellationSignal
    ) {
        require(value[API_FIELD_ROW_COUNT] is Number) {
            "A successful Cypher response must contain a numeric rowCount"
        }
        ctx.contentType(ContentType.APPLICATION_JSON)
            .result(serialize(value, cancellationSignal))
    }

    internal fun serialize(value: Any, cancellationSignal: CypherCancellationSignal): String {
        val sink = StringWriter()
        val writer = CancellationCheckingWriter(sink, cancellationSignal, progress)
        val jsonWriter = gson.newJsonWriter(writer)
        gson.toJson(value, value.javaClass, jsonWriter)
        jsonWriter.flush()
        cancellationSignal.throwIfCancelled()
        return sink.toString()
    }
}

internal class CancellationCheckingWriter(
    private val delegate: Writer,
    private val cancellationSignal: CypherCancellationSignal,
    private val progress: () -> Unit
) : Writer() {
    private var charactersUntilCheck = 0

    override fun write(characters: CharArray, offset: Int, length: Int) {
        var currentOffset = offset
        var remaining = length
        while (remaining > 0) {
            val chunk = nextChunk(remaining)
            delegate.write(characters, currentOffset, chunk)
            currentOffset += chunk
            remaining -= chunk
        }
    }

    override fun write(value: String, offset: Int, length: Int) {
        var currentOffset = offset
        var remaining = length
        while (remaining > 0) {
            val chunk = nextChunk(remaining)
            delegate.write(value, currentOffset, chunk)
            currentOffset += chunk
            remaining -= chunk
        }
    }

    override fun write(character: Int) {
        nextChunk(1)
        delegate.write(character)
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()

    private fun nextChunk(requested: Int): Int {
        if (charactersUntilCheck == 0) {
            cancellationSignal.throwIfCancelled()
            progress()
            charactersUntilCheck = SERIALIZATION_CANCELLATION_POLL_CHARS
        }
        return minOf(requested, charactersUntilCheck).also { charactersUntilCheck -= it }
    }
}

private const val SERIALIZATION_CANCELLATION_POLL_CHARS = 1_024
