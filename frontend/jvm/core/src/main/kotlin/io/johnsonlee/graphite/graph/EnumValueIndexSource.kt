package io.johnsonlee.graphite.graph

/**
 * A graph with enumerable enum declarations, including cases without constant nodes.
 * Keys are "$enumClass#$enumName". An empty argument list denotes a declared case.
 */
interface EnumValueIndexSource {
    fun enumValueIndex(): Map<String, List<Any?>>
}
