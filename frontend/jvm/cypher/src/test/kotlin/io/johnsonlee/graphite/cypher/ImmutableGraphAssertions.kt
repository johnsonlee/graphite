package io.johnsonlee.graphite.cypher

import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/** A write clause fails with the engines' shared one-line message, never an uncaught NotImplementedError. */
internal fun assertRefusedAsImmutable(block: () -> Unit) {
    assertContains(assertFailsWith<CypherException>(block = block).message!!, "graph is immutable")
}
