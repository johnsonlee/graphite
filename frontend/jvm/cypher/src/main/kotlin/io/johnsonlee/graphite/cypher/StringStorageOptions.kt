package io.johnsonlee.graphite.cypher

/** Storage routing policy belongs to a scan, independently of its predicate and projection. */
internal data class StringStorageOptions(
    val sourceCount: Int,
    val serial: Boolean = false,
    val raw: Boolean = false,
    val mappedView: Boolean = false
)
