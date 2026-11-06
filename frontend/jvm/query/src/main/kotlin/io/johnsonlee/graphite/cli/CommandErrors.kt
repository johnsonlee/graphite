package io.johnsonlee.graphite.cli

/** A command's failure boundary translates unexpected input/engine failures into the CLI exit contract. */
@Suppress("TooGenericExceptionCaught") // The CLI must report any command exception and return exit code 1.
internal inline fun runCliCommand(verbose: Boolean, action: () -> Int): Int =
    try {
        action()
    } catch (failure: Exception) {
        reportCommandException(failure, verbose)
    }

internal fun commandError(message: String?): Int {
    System.err.println("Error: $message")
    return 1
}

internal fun reportCommandException(failure: Exception, verbose: Boolean): Int {
    val status = commandError(failure.message)
    if (verbose) System.err.print(failure.stackTraceToString())
    return status
}
