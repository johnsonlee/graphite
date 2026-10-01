package sample.synthetic

/**
 * Kotlin fixture for synthetic identities: an `invokedynamic` lambda (`run$lambda$0`), an
 * object expression (`KotlinSyntheticExample$anonymous$1`) and a `when` over an enum, whose
 * mapping table lives in an `ACC_SYNTHETIC` `$WhenMappings` class.
 */
@Suppress("unused")
class KotlinSyntheticExample {

    enum class Mode { ON, OFF }

    fun run(prefix: String): Runnable = Runnable { println(prefix) }

    fun anonymous(): Runnable = object : Runnable {
        override fun run() = println("anonymous")
    }

    fun describe(mode: Mode): Int = when (mode) {
        Mode.ON -> 1
        Mode.OFF -> 0
    }
}
