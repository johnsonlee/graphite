package sample.kotlinlambda

enum class KotlinFoldOption { A, B }

enum class KotlinUnsafeFoldOption {
    A, B;

    init {
        enumInitializerCallback()
    }
}

fun enumOption(): KotlinFoldOption = KotlinFoldOption.B
fun unsafeEnumOption(): KotlinUnsafeFoldOption = KotlinUnsafeFoldOption.B
fun enumInScope(): Boolean = System.nanoTime() > 0
fun enumWork() = Unit
fun enumTail() = Unit
fun enumInitializerCallback() = Unit

fun enumEqual() {
    if (enumInScope() && enumOption() == KotlinFoldOption.A) enumWork()
    enumTail()
}

fun enumNotEqual() {
    if (enumInScope() && enumOption() != KotlinFoldOption.A) enumWork()
    enumTail()
}

fun enumEqualsA() {
    if (enumInScope() && KotlinFoldOption.A.equals(enumOption())) enumWork()
    enumTail()
}

fun enumEqualsB() {
    if (enumInScope() && KotlinFoldOption.B.equals(enumOption())) enumWork()
    enumTail()
}

fun unsafeEnumEquals() {
    if (KotlinUnsafeFoldOption.A.equals(unsafeEnumOption())) enumWork()
    enumTail()
}
