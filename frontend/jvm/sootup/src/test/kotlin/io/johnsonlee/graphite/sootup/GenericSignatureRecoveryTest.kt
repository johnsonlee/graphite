package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.bytecode.GenericSignatureParser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A malformed trailing type must discard the whole generic signature, including parsed prefixes. */
class GenericSignatureRecoveryTest {
    @Test
    fun `invalid method return type discards already parsed parameters`() {
        val signature = "(Ljava/lang/String;)Q"
        assertNull(GenericSignatureParser.parseMethodReturnType(signature))
        assertEquals(emptyList(), GenericSignatureParser.parseMethodParameters(signature))
        assertEquals(
            listOf("java.lang.String"),
            GenericSignatureParser.parseMethodParameters("(Ljava/lang/String;)V").map { it.className }
        )
    }

    @Test
    fun `invalid interface type discards an already parsed superclass`() {
        assertNull(GenericSignatureParser.parseClassSignature("Ljava/lang/Object;Q"))
        assertEquals(
            "java.lang.Object",
            GenericSignatureParser.parseClassSignature("Ljava/lang/Object;")?.superClass?.className
        )
    }
}
