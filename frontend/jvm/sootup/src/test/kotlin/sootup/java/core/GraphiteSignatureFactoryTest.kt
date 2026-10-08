package sootup.java.core

import java.util.LinkedList
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import sootup.core.signatures.FieldSignature
import sootup.core.signatures.FieldSubSignature
import sootup.core.signatures.MethodSignature
import sootup.core.signatures.MethodSubSignature
import sootup.core.signatures.PackageName
import sootup.core.types.ArrayType
import sootup.core.types.NullType
import sootup.core.types.PrimitiveType
import sootup.core.types.Type
import sootup.core.types.UnknownType
import sootup.core.types.VoidType
import sootup.java.core.types.JavaClassType

class GraphiteSignatureFactoryTest {
    private val stock = JavaIdentifierFactory.getInstance()
    private val factory = GraphiteIdentifierFactory()
    private val owner = stock.getClassType(OWNER)
    private val string = stock.getClassType(STRING)
    private val int = PrimitiveType.getInt()
    private val long = PrimitiveType.getLong()

    @Test
    fun `typed signatures share subs while retaining fresh outers and every supplied type`() {
        val parameters = listOf<Type>(int, string)
        val expected = stock.getMethodSignature(owner, APPLY, string, parameters)
        val first = factory.getMethodSignature(owner, APPLY, string, parameters)
        val second = factory.getMethodSignature(owner, APPLY, string, parameters)
        assertMethod(expected, first)
        assertMethod(expected, second)
        assertNotSame(first, second)
        assertSame(first.subSignature, second.subSignature)
        assertEquals("<sample.Owner: java.lang.String apply(int,java.lang.String)>", first.toString())
        assertEquals(APPLY, first.name)
        assertEquals(2, first.parameterCount)

        val field = factory.getFieldSignature(COUNT, owner, int)
        val another = factory.getFieldSignature(COUNT, owner, int)
        assertField(stock.getFieldSignature(COUNT, owner, int), field)
        assertField(stock.getFieldSignature(COUNT, owner, int), another)
        assertNotSame(field, another)
        assertSame(field.subSignature, another.subSignature)
        assertEquals("<sample.Owner: int count>", field.toString())

        val otherOwner = stock.getClassType("sample.Other")
        val otherMethod = factory.getMethodSignature(otherOwner, APPLY, string, parameters)
        val otherField = factory.getFieldSignature(COUNT, otherOwner, int)
        assertMethod(stock.getMethodSignature(otherOwner, APPLY, string, parameters), otherMethod)
        assertField(stock.getFieldSignature(COUNT, otherOwner, int), otherField)
        assertSame(first.subSignature, otherMethod.subSignature)
        assertSame(field.subSignature, otherField.subSignature)
    }

    @Test
    fun `method and field keys preserve names return types and parameter order`() {
        val inputs = listOf(
            Triple(APPLY, string, listOf<Type>(int, string)),
            Triple("other", string, listOf<Type>(int, string)),
            Triple(APPLY, long, listOf<Type>(int, string)),
            Triple(APPLY, string, listOf<Type>(string, int)),
            Triple(APPLY, string, listOf<Type>(int))
        )
        val expected = inputs.map { (name, type, parameters) -> stock.getMethodSignature(owner, name, type, parameters) }
        val actual = inputs.map { (name, type, parameters) -> factory.getMethodSignature(owner, name, type, parameters) }
        for (i in inputs.indices) {
            assertMethod(expected[i], actual[i])
            for (j in inputs.indices) {
                assertEquals(expected[i].compareTo(expected[j]), actual[i].compareTo(actual[j]))
                assertEquals(
                    expected[i].subSignature.compareTo(expected[j].subSignature),
                    actual[i].subSignature.compareTo(actual[j].subSignature)
                )
                if (i != j) assertNotSame(actual[i].subSignature, actual[j].subSignature)
            }
        }
        // Upstream sub-signature ordering intentionally ignores parameter types.
        assertEquals(0, actual[0].subSignature.compareTo(actual[REORDERED_PARAMETERS].subSignature))
        val fieldInputs = listOf(COUNT to int, "other" to int, COUNT to long)
        val fields = fieldInputs.map { (name, type) -> factory.getFieldSignature(name, owner, type) }
        val stockFields = fieldInputs.map { (name, type) -> stock.getFieldSignature(name, owner, type) }
        for (i in fields.indices) {
            assertField(stockFields[i], fields[i])
            for (j in fields.indices) {
                assertEquals(stockFields[i].compareTo(stockFields[j]), fields[i].compareTo(fields[j]))
                if (i != j) assertNotSame(fields[i].subSignature, fields[j].subSignature)
            }
        }
    }

    @Test
    fun `equal but distinct class types are never substituted in signatures`() {
        val firstType = JavaClassType("Value", PackageName("sample"))
        val secondType = JavaClassType("Value", PackageName("sample"))
        assertEquals(firstType, secondType)
        assertNotSame(firstType, secondType)
        val first = factory.getMethodSignature(owner, APPLY, firstType, listOf(firstType))
        val changedReturn = factory.getMethodSignature(owner, APPLY, secondType, listOf(firstType))
        val changedParameter = factory.getMethodSignature(owner, APPLY, firstType, listOf(secondType))
        assertMethod(stock.getMethodSignature(owner, APPLY, firstType, listOf(firstType)), first)
        assertMethod(stock.getMethodSignature(owner, APPLY, secondType, listOf(firstType)), changedReturn)
        assertMethod(stock.getMethodSignature(owner, APPLY, firstType, listOf(secondType)), changedParameter)
        assertEquals(first, changedReturn)
        assertEquals(first, changedParameter)
        assertNotSame(first.subSignature, changedReturn.subSignature)
        assertNotSame(first.subSignature, changedParameter.subSignature)
        val firstField = factory.getFieldSignature(VALUE, owner, firstType)
        val secondField = factory.getFieldSignature(VALUE, owner, secondType)
        assertField(stock.getFieldSignature(VALUE, owner, firstType), firstField)
        assertField(stock.getFieldSignature(VALUE, owner, secondType), secondField)
        assertEquals(firstField, secondField)
        assertNotSame(firstField.subSignature, secondField.subSignature)
    }

    @Test
    fun `mutable caller lists never become retained keys or signature parameters`() {
        val parameters = mutableListOf<Type>(int, string)
        val first = factory.getMethodSignature(owner, APPLY, string, parameters)
        parameters.clear()
        parameters.add(long)
        val second = factory.getMethodSignature(owner, APPLY, string, parameters)
        assertMethod(stock.getMethodSignature(owner, APPLY, string, listOf(int, string)), first)
        assertMethod(stock.getMethodSignature(owner, APPLY, string, listOf(long)), second)
        assertNotSame(first.subSignature, second.subSignature)
        assertSame(first.subSignature, factory.getMethodSignature(owner, APPLY, string, listOf(int, string)).subSignature)
        val linkedParameters = LinkedList<Type>(listOf(int, string))
        val linked = factory.getMethodSignature(owner, APPLY, string, linkedParameters)
        assertMethod(stock.getMethodSignature(owner, APPLY, string, listOf(int, string)), linked)
        assertSame(first.subSignature, linked.subSignature)
        linkedParameters.clear()
        linkedParameters.add(long)
        val changedLinked = factory.getMethodSignature(owner, APPLY, string, linkedParameters)
        assertMethod(stock.getMethodSignature(owner, APPLY, string, listOf(long)), changedLinked)
        assertSame(second.subSignature, changedLinked.subSignature)
        assertEquals(listOf(int, string), linked.parameterTypes)
        assertFailsWith<UnsupportedOperationException> { (first.parameterTypes as MutableList<Type>).add(long) }
        assertEquals(listOf(int, string), first.parameterTypes)
        assertEquals(listOf(long), second.parameterTypes)
    }

    @Test
    fun `arrays unknown null and custom mutable types keep fresh stock subs`() {
        val mutable = MutableIntType()
        val customPackage = MutablePackageName("before")
        val customClass = JavaClassType("Value", customPackage)
        val types = listOf(ArrayType(string, 2), UnknownType.getInstance(), NullType.getInstance(), mutable, customClass)
        for (type in types) {
            val first = factory.getMethodSignature(owner, APPLY, type, listOf(type))
            val second = factory.getMethodSignature(owner, APPLY, type, listOf(type))
            assertMethod(stock.getMethodSignature(owner, APPLY, type, listOf(type)), first)
            assertMethod(stock.getMethodSignature(owner, APPLY, type, listOf(type)), second)
            assertNotSame(first.subSignature, second.subSignature)
            val field = factory.getFieldSignature(VALUE, owner, type)
            val another = factory.getFieldSignature(VALUE, owner, type)
            assertField(stock.getFieldSignature(VALUE, owner, type), field)
            assertField(stock.getFieldSignature(VALUE, owner, type), another)
            assertNotSame(field.subSignature, another.subSignature)
        }
        mutable.text = "changed"
        customPackage.text = "after"
        for (type in listOf(mutable, customClass)) {
            assertMethod(
                stock.getMethodSignature(owner, APPLY, type, listOf(type)),
                factory.getMethodSignature(owner, APPLY, type, listOf(type))
            )
            assertField(stock.getFieldSignature(VALUE, owner, type), factory.getFieldSignature(VALUE, owner, type))
        }
        assertEquals("<sample.Owner: changed value>", factory.getFieldSignature(VALUE, owner, mutable).toString())
        assertEquals("<sample.Owner: after.Value value>", factory.getFieldSignature(VALUE, owner, customClass).toString())
    }

    @Test
    fun `unsupported parameter or declaring owner alone retains stock fallback`() {
        val unsupportedParameters = listOf(ArrayType(string, 1), UnknownType.getInstance(), MutableIntType())
        for (parameter in unsupportedParameters) {
            val expected = stock.getMethodSignature(owner, APPLY, string, listOf(parameter))
            val first = factory.getMethodSignature(owner, APPLY, string, listOf(parameter))
            val second = factory.getMethodSignature(owner, APPLY, string, listOf(parameter))
            assertMethod(expected, first)
            assertMethod(expected, second)
            assertNotSame(first.subSignature, second.subSignature)
            assertSame(parameter, first.parameterTypes.single())
            assertSame(string, first.type)
        }
        val customOwner = object : JavaClassType("CustomOwner", PackageName("sample")) {}
        val expectedMethod = stock.getMethodSignature(customOwner, APPLY, string, listOf(int))
        val firstMethod = factory.getMethodSignature(customOwner, APPLY, string, listOf(int))
        val secondMethod = factory.getMethodSignature(customOwner, APPLY, string, listOf(int))
        assertMethod(expectedMethod, firstMethod)
        assertMethod(expectedMethod, secondMethod)
        assertNotSame(firstMethod.subSignature, secondMethod.subSignature)
        assertSame(customOwner, firstMethod.declClassType)
        val expectedField = stock.getFieldSignature(VALUE, customOwner, int)
        val firstField = factory.getFieldSignature(VALUE, customOwner, int)
        val secondField = factory.getFieldSignature(VALUE, customOwner, int)
        assertField(expectedField, firstField)
        assertField(expectedField, secondField)
        assertNotSame(firstField.subSignature, secondField.subSignature)
        assertSame(customOwner, firstField.declClassType)
    }

    @Test
    fun `null inputs retain superclass outcomes and do not poison valid entries`() {
        @Suppress("UNCHECKED_CAST")
        val nullElement = listOf<Type?>(null) as List<Type>
        val methodCases = listOf<(JavaIdentifierFactory) -> MethodSignature>(
            { it.getMethodSignature(null, APPLY, string, listOf(int)) },
            { it.getMethodSignature(owner, null, string, listOf(int)) },
            { it.getMethodSignature(owner, APPLY, null as Type?, listOf(int)) },
            { it.getMethodSignature(owner, APPLY, string, null as List<Type>?) },
            { it.getMethodSignature(owner, APPLY, string, nullElement) }
        )
        for (call in methodCases) assertOutcome(call)
        val fieldCases = listOf<(JavaIdentifierFactory) -> FieldSignature>(
            { it.getFieldSignature(null, owner, int) },
            { it.getFieldSignature(VALUE, null, int) },
            { it.getFieldSignature(VALUE, owner, null as Type?) }
        )
        for (call in fieldCases) assertOutcome(call)
        assertMethod(
            stock.getMethodSignature(owner, APPLY, string, listOf(int)),
            factory.getMethodSignature(owner, APPLY, string, listOf(int))
        )
        assertField(stock.getFieldSignature(VALUE, owner, int), factory.getFieldSignature(VALUE, owner, int))
    }

    @Test
    fun `string explicit sub and direct sub APIs remain separate from typed caches`() {
        factory.getMethodSignature(owner, APPLY, string, listOf(int))
        factory.getFieldSignature(VALUE, owner, int)
        val method = factory.getMethodSignature(OWNER, APPLY, STRING, listOf("int"))
        assertMethod(stock.getMethodSignature(OWNER, APPLY, STRING, listOf("int")), method)
        assertNotSame(method.subSignature, factory.getMethodSignature(OWNER, APPLY, STRING, listOf("int")).subSignature)
        val explicitOwnerStringTypes = factory.getMethodSignature(owner, APPLY, STRING, listOf("int"))
        assertMethod(method, explicitOwnerStringTypes)
        assertNotSame(method.subSignature, explicitOwnerStringTypes.subSignature)
        val parsed = factory.parseMethodSignature("<sample.Owner: java.lang.String apply(int)>")
        assertMethod(method, parsed)
        assertNotSame(method.subSignature, parsed.subSignature)
        val field = factory.getFieldSignature(VALUE, owner, "int")
        assertField(stock.getFieldSignature(VALUE, owner, "int"), field)
        assertNotSame(field.subSignature, factory.parseFieldSignature("<sample.Owner: int value>").subSignature)
        val methodSub = MethodSubSignature(APPLY, listOf(int), string)
        val fieldSub = FieldSubSignature(VALUE, int)
        assertSame(methodSub, factory.getMethodSignature(owner, methodSub).subSignature)
        assertSame(fieldSub, factory.getFieldSignature(owner, fieldSub).subSignature)
        assertNotSame(factory.getMethodSubSignature(APPLY, string, listOf(int)), factory.getMethodSubSignature(APPLY, string, listOf(int)))
        assertNotSame(factory.getFieldSubSignature(VALUE, int), factory.getFieldSubSignature(VALUE, int))
    }

    @Test
    fun `safe primitive types share only within their own factory`() {
        val second = GraphiteIdentifierFactory()
        val types = listOf(
            int, long, PrimitiveType.getBoolean(), PrimitiveType.getByte(), PrimitiveType.getShort(),
            PrimitiveType.getChar(), PrimitiveType.getFloat(), PrimitiveType.getDouble(), VoidType.getInstance()
        )
        for (type in types) {
            val method = factory.getMethodSignature(owner, APPLY, type, emptyList())
            assertMethod(stock.getMethodSignature(owner, APPLY, type, emptyList()), method)
            assertSame(method.subSignature, factory.getMethodSignature(owner, APPLY, type, emptyList()).subSignature)
            assertNotSame(method.subSignature, second.getMethodSignature(owner, APPLY, type, emptyList()).subSignature)
            val field = factory.getFieldSignature(VALUE, owner, type)
            assertField(stock.getFieldSignature(VALUE, owner, type), field)
            assertSame(field.subSignature, factory.getFieldSignature(VALUE, owner, type).subSignature)
            assertNotSame(field.subSignature, second.getFieldSignature(VALUE, owner, type).subSignature)
        }
    }

    @Test
    fun `concurrent typed requests publish shared subs with fresh outer signatures`() {
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(WORKERS)
        try {
            val futures = List(WORKERS) {
                workers.submit(Callable {
                    check(start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    factory.getMethodSignature(owner, APPLY, string, listOf(int)) to factory.getFieldSignature(VALUE, owner, int)
                })
            }
            start.countDown()
            val results = futures.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            for ((method, field) in results) {
                assertMethod(stock.getMethodSignature(owner, APPLY, string, listOf(int)), method)
                assertField(stock.getFieldSignature(VALUE, owner, int), field)
                assertSame(results.first().first.subSignature, method.subSignature)
                assertSame(results.first().second.subSignature, field.subSignature)
            }
            for (i in results.indices) for (j in 0 until i) {
                assertNotSame(results[i].first, results[j].first)
                assertNotSame(results[i].second, results[j].second)
            }
        } finally {
            workers.shutdownNow()
        }
    }

    private fun assertMethod(expected: MethodSignature, actual: MethodSignature) {
        assertEquals(expected, actual)
        assertEquals(expected.hashCode(), actual.hashCode())
        assertEquals(expected.toString(), actual.toString())
        assertEquals(expected.subSignature.hashCode(), actual.subSignature.hashCode())
        assertEquals(0, expected.compareTo(actual))
        assertSame(expected.declClassType, actual.declClassType)
        assertSame(expected.type, actual.type)
        assertEquals(expected.parameterTypes.size, actual.parameterTypes.size)
        expected.parameterTypes.indices.forEach { assertSame(expected.parameterTypes[it], actual.parameterTypes[it]) }
    }

    private fun assertField(expected: FieldSignature, actual: FieldSignature) {
        assertEquals(expected, actual)
        assertEquals(expected.hashCode(), actual.hashCode())
        assertEquals(expected.toString(), actual.toString())
        assertEquals(expected.subSignature.hashCode(), actual.subSignature.hashCode())
        assertEquals(0, expected.compareTo(actual))
        assertSame(expected.declClassType, actual.declClassType)
        assertSame(expected.type, actual.type)
    }

    private fun <T> assertOutcome(call: (JavaIdentifierFactory) -> T) {
        val expected = runCatching { call(stock) }
        val actual = runCatching { call(factory) }
        if (expected.isFailure) {
            assertEquals(expected.exceptionOrNull()?.javaClass, actual.exceptionOrNull()?.javaClass)
            assertEquals(expected.exceptionOrNull()?.message, actual.exceptionOrNull()?.message)
        } else {
            assertEquals(expected.getOrThrow(), actual.getOrThrow())
            assertEquals(expected.getOrThrow().hashCode(), actual.getOrThrow().hashCode())
        }
    }

    private class MutableIntType : PrimitiveType.IntType("custom") {
        var text = "before"
        override fun toString(): String = text
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = text.hashCode()
    }

    private class MutablePackageName(var text: String) : PackageName(text) {
        override fun getName(): String = text
    }

    private companion object {
        const val OWNER = "sample.Owner"
        const val STRING = "java.lang.String"
        const val APPLY = "apply"
        const val COUNT = "count"
        const val VALUE = "value"
        const val REORDERED_PARAMETERS = 3
        const val WORKERS = 4
        const val TIMEOUT_SECONDS = 10L
    }
}
