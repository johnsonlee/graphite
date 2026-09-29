package sample.constdefs

/**
 * Kotlin fixtures for branch-side constant definitions: nullable booleans box through
 * `Boolean.valueOf`, and `when` lowers to a chain of conditional branches.
 */
@Suppress("unused")
class KotlinLocalDefinitionExample {

    fun nullableBoolean(c: Boolean): Boolean? {
        val on: Boolean? = if (c) true else null
        return on
    }

    fun whenExpression(a: Boolean, b: Boolean): Int {
        val n = when {
            a -> 1
            b -> 2
            else -> 3
        }
        return n
    }
}
