package fixture.frontend.kotlin

/** Controlled Kotlin/JVM bytecode inputs for the frontend correctness gate. */
object KotlinCases {
    private var stored: Int = 0

    @JvmStatic
    fun sink(value: String): String = value

    @JvmStatic
    fun sink(value: Int): Int = value

    @JvmStatic
    fun branch(enabled: Boolean): String =
        if (enabled) sink("kotlin-true") else sink("kotlin-false")

    @JvmStatic
    fun choose(value: Int): Int = when (value) {
        1 -> 101
        2 -> 202
        else -> 303
    }

    @JvmStatic
    fun stringWhen(value: String): Int = when (value) {
        "alpha" -> 401
        "beta" -> 402
        else -> 403
    }

    @JvmStatic
    fun parameterReturn(selected: String, other: String): String = selected

    @JvmStatic
    fun nullReturn(): String? = null

    @JvmStatic
    fun nullableElvis(value: String?): String = value ?: "kotlin-fallback"

    @JvmStatic
    fun safeCall(value: String?): String = value?.trim() ?: "kotlin-none"

    @JvmStatic
    fun constantNodes(input: Long): Double {
        val longValue = 8_000_000_001L
        val floatValue = 1.75f
        val doubleValue = 4.5
        return longValue + floatValue + doubleValue + input
    }

    @JvmStatic
    fun primaryLongConstant(): Long = 8_000_000_001L

    @JvmStatic
    fun siblingLongConstant(): Long = 8_000_000_002L

    @JvmStatic
    fun fieldsAndArrays(values: IntArray, value: Int): Int {
        stored = value
        values[0] = value
        return stored + values[0]
    }

    @JvmStatic
    fun instanceField(value: Int): Int {
        val box = KotlinBox(0)
        box.value = value
        return box.value
    }

    @JvmStatic
    fun staticCall(): String = sink("kotlin-static")

    @JvmStatic
    fun virtualCall(value: String): String = KotlinWorker("kotlin:").format(value)

    @JvmStatic
    fun interfaceCall(value: String): String {
        val formatter: KotlinFormatter = KotlinWorker("interface:")
        return formatter.format(value)
    }

    @JvmStatic
    fun constructorCall(value: Int): Int = KotlinBox(value).value

    @JvmStatic
    fun lambda(value: String): String {
        val decorate: (String) -> String = { sink("kotlin:$it") }
        return decorate(value)
    }

    @JvmStatic
    fun capturingLambda(prefix: String, value: String): String {
        val decorate: (String) -> String = { sink(prefix + it) }
        return decorate(value)
    }

    @JvmStatic
    fun extensionCall(value: String): String = value.decorate("-extension")

    @JvmStatic
    suspend fun suspendLowering(value: String): String = sink(value)

    @JvmStatic
    fun recover(value: String): String = try {
        sink(value)
    } catch (_: IllegalArgumentException) {
        sink("kotlin-catch")
    }

    @JvmStatic
    fun defaultArgument(value: String, suffix: String = "-default"): String = sink(value + suffix)

    private fun String.decorate(suffix: String): String = sink(this + suffix)
}

private interface KotlinFormatter {
    fun format(value: String): String
}

private class KotlinWorker(private val prefix: String) : KotlinFormatter {
    override fun format(value: String): String = KotlinCases.sink(prefix + value)
}

private class KotlinBox(@JvmField var value: Int)
