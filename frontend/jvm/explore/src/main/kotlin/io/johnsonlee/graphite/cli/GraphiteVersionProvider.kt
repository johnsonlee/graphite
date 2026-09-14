package io.johnsonlee.graphite.cli

import picocli.CommandLine.IVersionProvider

class GraphiteVersionProvider : IVersionProvider {
    override fun getVersion(): Array<String> = arrayOf("graphite ${currentVersion()}")

    companion object {
        internal fun currentVersion(): String =
            GraphiteVersionProvider::class.java.`package`.implementationVersion
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: System.getProperty(VERSION_SYSTEM_PROPERTY)?.trim()?.takeIf(String::isNotEmpty)
                ?: UNKNOWN_VERSION

        private const val VERSION_SYSTEM_PROPERTY = "graphite.version"
        private const val UNKNOWN_VERSION = "unknown"
    }
}
