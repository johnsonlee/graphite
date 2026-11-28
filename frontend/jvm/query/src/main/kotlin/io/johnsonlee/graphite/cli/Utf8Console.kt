package io.johnsonlee.graphite.cli

import java.io.BufferedOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * Makes the process's standard output and error UTF-8, whatever the locale.
 *
 * The JVM encodes `System.out` and `System.err` in the locale's charset, so under `LC_ALL=C` or
 * `POSIX` (or a `C.UTF-8` the platform does not support, as on macOS) every character outside
 * ASCII, in a result row or in a message such as "graph is immutable" after its em dash,
 * was written as `?`. The Rust CLI writes UTF-8 bytes in every locale, so the two engines
 * answered the same query with different bytes (review of #229). Like the JDK's own streams,
 * these flush on every line.
 */
internal fun installUtf8Console(
    out: OutputStream = FileOutputStream(FileDescriptor.out),
    err: OutputStream = FileOutputStream(FileDescriptor.err),
) {
    System.setOut(utf8PrintStream(out))
    System.setErr(utf8PrintStream(err))
}

private fun utf8PrintStream(stream: OutputStream): PrintStream =
    PrintStream(BufferedOutputStream(stream, BUFFER_BYTES), true, Charsets.UTF_8)

private const val BUFFER_BYTES = 8192
