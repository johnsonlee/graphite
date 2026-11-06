package io.johnsonlee.graphite.webgraph

/** The `graph.callsite-ordinals` sidecar's bytes and the digest `graph.metadata` binds to it. */
internal class EncodedCallSiteOrdinals(val bytes: ByteArray, val digest: ByteArray)
