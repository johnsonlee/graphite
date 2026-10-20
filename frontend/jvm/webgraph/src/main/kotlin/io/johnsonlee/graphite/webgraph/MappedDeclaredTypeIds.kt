package io.johnsonlee.graphite.webgraph

/** Type storage of a mapped table that can bind members by the graph's string IDs. */
internal interface MappedDeclaredTypeIds {
    /** The ID-keyed lookup, or null when the table does not share the graph's strings or has no erased rows. */
    fun binding(): MappedDeclaredTypeBinding?
}
