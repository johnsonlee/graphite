package sample.kotlinlambda

/**
 * Fixtures covering every shape a Kotlin function value can take in bytecode.
 *
 * The same source is compiled twice, once with `-Xlambdas=indy -Xsam-conversions=indy`
 * (the Kotlin 2.x default: `invokedynamic` through `LambdaMetafactory`) and once with
 * `-Xlambdas=class -Xsam-conversions=class` (the Kotlin 1.x default: a synthetic class per
 * lambda, a singleton `INSTANCE` for non-capturing ones, `$sam$` wrappers). Every lambda body
 * calls a distinct [Sink] method so tests can tell which body a dispatch reached.
 */
object Sink {
    @JvmStatic fun nonCapturing(s: String): String = s
    @JvmStatic fun capturing(prefix: String, s: String): String = prefix + s
    @JvmStatic fun parameter(s: String): String = s
    @JvmStatic fun forwarded(s: String): String = s
    @JvmStatic fun returned(s: String): String = s
    @JvmStatic fun field(s: String): String = s
    @JvmStatic fun injected(s: String): String = s
    @JvmStatic fun nestedInner(s: String): String = s
    @JvmStatic fun reference(s: String): String = s
    @JvmStatic fun boundReference(s: String): String = s
    @JvmStatic fun property(s: String): String = s
    @JvmStatic fun suspended(s: String): String = s
    @JvmStatic fun funInterface(s: String): String = s
    @JvmStatic fun javaSam(s: String): String = s
    @JvmStatic fun wrappedSam(s: String): String = s
    @JvmStatic fun anonymous(s: String): String = s
    @JvmStatic fun withReceiver(s: String): String = s
    @JvmStatic fun inlined(s: String): String = s
    @JvmStatic fun deferred(s: String): String = s
    @JvmStatic fun dataFlow(prefix: String, s: String): String = prefix + s
}

class NonCapturingLambda {
    fun use(input: String): String {
        val fn: (String) -> String = { Sink.nonCapturing(it) }
        return fn(input)
    }
}

class CapturingLambda {
    fun use(prefix: String, input: String): String {
        val fn: (String) -> String = { Sink.capturing(prefix, it) }
        return fn(input)
    }
}

class ParameterLambda {
    fun call(fn: (String) -> String, input: String): String = fn(input)

    fun use(input: String): String = call({ Sink.parameter(it) }, input)
}

class ForwardedLambda {
    fun outer(fn: (String) -> String, input: String): String = inner(fn, input)

    fun inner(fn: (String) -> String, input: String): String = fn(input)

    fun use(input: String): String = outer({ Sink.forwarded(it) }, input)
}

class ReturnedLambda {
    fun make(): (String) -> String = { Sink.returned(it) }

    fun use(input: String): String {
        val fn = make()
        return fn(input)
    }
}

class FieldLambda {
    private val fn: (String) -> String = { Sink.field(it) }

    fun use(input: String): String = fn(input)
}

class InjectedLambda(private val fn: (String) -> String) {
    fun use(input: String): String = fn(input)

    companion object {
        @JvmStatic
        fun create(): InjectedLambda = InjectedLambda { Sink.injected(it) }
    }
}

class NestedLambda {
    fun use(input: String): String {
        val inner: (String) -> String = { Sink.nestedInner(it) }
        val outer: (String) -> String = { inner(it) }
        return outer(input)
    }
}

class FunctionReference {
    fun target(s: String): String = Sink.reference(s)

    fun call(fn: (FunctionReference, String) -> String, input: String): String = fn(this, input)

    fun use(input: String): String = call(FunctionReference::target, input)
}

class BoundReference {
    fun target(s: String): String = Sink.boundReference(s)

    fun call(fn: (String) -> String, input: String): String = fn(input)

    fun use(input: String): String = call(this::target, input)
}

class PropertyReference(private val raw: String) {
    val name: String get() = Sink.property(raw)

    fun call(fn: (PropertyReference) -> String, target: PropertyReference): String = fn(target)

    fun use(): String = call(PropertyReference::name, this)
}

class SuspendLambda {
    suspend fun call(block: suspend (String) -> String, input: String): String = block(input)

    suspend fun use(input: String): String = call({ Sink.suspended(it) }, input)
}

fun interface Transformer {
    fun transform(s: String): String
}

class FunInterfaceLambda {
    fun call(t: Transformer, input: String): String = t.transform(input)

    fun use(input: String): String = call(Transformer { Sink.funInterface(it) }, input)
}

class JavaSamLambda {
    fun call(fn: java.util.function.Function<String, String>, input: String): String = fn.apply(input)

    fun use(input: String): String = call({ Sink.javaSam(it) }, input)
}

class WrappedSamLambda {
    fun call(fn: java.util.function.Function<String, String>, input: String): String = fn.apply(input)

    fun use(input: String): String {
        val fn: (String) -> String = { Sink.wrappedSam(it) }
        return call(fn, input)
    }
}

class AnonymousObject {
    fun call(t: Transformer, input: String): String = t.transform(input)

    fun use(input: String): String = call(object : Transformer {
        override fun transform(s: String): String = Sink.anonymous(s)
    }, input)
}

class ReceiverLambda {
    fun call(fn: String.() -> String, input: String): String = input.fn()

    fun use(input: String): String = call({ Sink.withReceiver(this) }, input)
}

class InlineLambda {
    fun use(input: String): String = input.let { Sink.inlined(it) }
}

class DeferredLambda {
    fun use(input: String): Lazy<String> = lazy { Sink.deferred(input) }
}

class DataFlowLambda {
    fun run(prefix: String, input: String): String {
        val fn: (String) -> String = { Sink.dataFlow(prefix, it) }
        return fn(input)
    }

    fun entry(): String = run("captured-constant", "argument-constant")
}
