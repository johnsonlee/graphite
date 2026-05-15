package io.johnsonlee.graphite.webgraph

internal const val BYTE_MASK = 0xFF
internal const val HEADER_MAGIC_MASK = 0xFFFFFF00.toInt()
internal const val HEX_RADIX = 16
internal const val INT_BITS = 32
internal const val UNSIGNED_INT_MASK = 0xFFFFFFFFL
internal const val EDGE_FAMILY_DATAFLOW = 0
internal const val EDGE_FAMILY_CALL = 1
internal const val EDGE_FAMILY_TYPE = 2
internal const val EDGE_FAMILY_CONTROL_FLOW = 3
internal const val EDGE_FAMILY_RESOURCE = 4
internal const val V2_EDGE_FAMILY_MASK = 0x3
internal const val V3_EDGE_FAMILY_MASK = 0x7
internal const val EDGE_KIND_MASK = 0xF
internal const val V2_EDGE_KIND_SHIFT = 2
internal const val V3_EDGE_KIND_SHIFT = 3
internal const val CALL_EDGE_DYNAMIC_SHIFT = 4
internal const val CALL_EDGE_VIRTUAL_SHIFT_V2 = 6
internal const val CALL_EDGE_DYNAMIC_SHIFT_V2 = 7
internal const val FRONT_CODED_STRING_RATIO = 8
