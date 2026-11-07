package io.johnsonlee.graphite.cypher

import kotlin.math.E
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class CypherMathFunctionsTest : CypherFunctionsTestFixture() {

    @Test
    fun `abs of positive int`() {
        assertEquals(5, CypherFunctions.call("abs", listOf(5)))
    }

    @Test
    fun `abs of negative int`() {
        assertEquals(5, CypherFunctions.call("abs", listOf(-5)))
    }

    @Test
    fun `abs of negative long`() {
        assertEquals(5L, CypherFunctions.call("abs", listOf(-5L)))
    }

    @Test
    fun `abs of negative float`() {
        assertEquals(3.14f, CypherFunctions.call("abs", listOf(-3.14f)))
    }

    @Test
    fun `abs of negative double`() {
        assertEquals(2.718, CypherFunctions.call("abs", listOf(-2.718)))
    }

    @Test
    fun `abs of null returns null`() {
        assertNull(CypherFunctions.call("abs", listOf(null)))
    }

    @Test
    fun `ceil rounds up`() {
        assertEquals(3.0, CypherFunctions.call("ceil", listOf(2.3)))
    }

    @Test
    fun `floor rounds down`() {
        assertEquals(2.0, CypherFunctions.call("floor", listOf(2.7)))
    }

    @Test
    fun `round rounds to nearest`() {
        assertEquals(3.0, CypherFunctions.call("round", listOf(2.5)))
        assertEquals(2.0, CypherFunctions.call("round", listOf(2.4)))
    }

    @Test
    fun `sign returns 1 for positive`() {
        assertEquals(1, CypherFunctions.call("sign", listOf(42)))
    }

    @Test
    fun `sign returns -1 for negative`() {
        assertEquals(-1, CypherFunctions.call("sign", listOf(-42)))
    }

    @Test
    fun `sign returns 0 for zero`() {
        assertEquals(0, CypherFunctions.call("sign", listOf(0)))
    }

    @Test
    fun `rand returns a value between 0 and 1`() {
        val result = CypherFunctions.call("rand", emptyList()) as Double
        assertTrue(result >= 0.0 && result < 1.0)
    }

    @Test
    fun `sqrt computes square root`() {
        assertEquals(3.0, CypherFunctions.call("sqrt", listOf(9.0)))
    }

    @Test
    fun `exp computes e to the power`() {
        assertEquals(exp(1.0), CypherFunctions.call("exp", listOf(1.0)))
    }

    @Test
    fun `log computes natural logarithm`() {
        assertEquals(ln(Math.E), CypherFunctions.call("log", listOf(Math.E)))
    }

    @Test
    fun `log10 computes base-10 logarithm`() {
        assertEquals(2.0, CypherFunctions.call("log10", listOf(100.0)))
    }

    @Test
    fun `e returns Euler number`() {
        assertEquals(Math.E, CypherFunctions.call("e", emptyList()))
    }

    @Test
    fun `sin computes sine`() {
        assertEquals(sin(Math.PI / 2), CypherFunctions.call("sin", listOf(Math.PI / 2)))
    }

    @Test
    fun `cos computes cosine`() {
        assertEquals(cos(0.0), CypherFunctions.call("cos", listOf(0.0)))
    }

    @Test
    fun `tan computes tangent`() {
        assertEquals(tan(Math.PI / 4), CypherFunctions.call("tan", listOf(Math.PI / 4)))
    }

    @Test
    fun `asin computes arc sine`() {
        assertEquals(asin(1.0), CypherFunctions.call("asin", listOf(1.0)))
    }

    @Test
    fun `acos computes arc cosine`() {
        assertEquals(acos(1.0), CypherFunctions.call("acos", listOf(1.0)))
    }

    @Test
    fun `atan computes arc tangent`() {
        assertEquals(atan(1.0), CypherFunctions.call("atan", listOf(1.0)))
    }

    @Test
    fun `atan2 computes two-argument arc tangent`() {
        assertEquals(atan2(1.0, 1.0), CypherFunctions.call("atan2", listOf(1.0, 1.0)))
    }

    @Test
    fun `cot computes cotangent`() {
        val angle = Math.PI / 4
        assertEquals(1.0 / tan(angle), CypherFunctions.call("cot", listOf(angle)))
    }

    @Test
    fun `pi returns PI`() {
        assertEquals(Math.PI, CypherFunctions.call("pi", emptyList()))
    }

    @Test
    fun `degrees converts radians to degrees`() {
        assertEquals(180.0, CypherFunctions.call("degrees", listOf(Math.PI)))
    }

    @Test
    fun `radians converts degrees to radians`() {
        assertEquals(Math.PI, CypherFunctions.call("radians", listOf(180.0)))
    }
}
