package io.johnsonlee.graphite.cli.c4

/** Counts only calls whose endpoint was assigned to the current architecture scope. */
internal fun MutableMap<String, Int>.incrementCount(key: String?) {
    if (key != null) this[key] = (this[key] ?: 0) + 1
}
