package sootup.java.core

import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import sootup.core.types.ArrayType
import sootup.core.types.PrimitiveType
import sootup.java.core.types.JavaClassType

class GraphiteIdentifierFactoryTest {
    private val singleton = JavaIdentifierFactory.getInstance()

    @Test
    fun `class owners packages and nested names remain singleton canonical objects`() {
        val factory = GraphiteIdentifierFactory()
        val owner = singleton.getClassType(OWNER)
        assertSame(owner, factory.getClassType(OWNER))
        val explicitOwner = factory.getClassType("Outer\$Inner", PACKAGE)
        assertSame(singleton.getClassType("Outer\$Inner", PACKAGE), explicitOwner)
        assertEquals(owner, explicitOwner)
        assertEquals("Outer\$Inner", explicitOwner.className)
        assertEquals("Outer\$Inner", owner.className)
        assertEquals(OWNER, owner.fullyQualifiedName)
        assertSame(singleton.getPackageName(PACKAGE), factory.getPackageName(PACKAGE))
        assertSame(singleton.getPackageName(""), factory.getPackageName(""))

        val signature = factory.getMethodSignature(OWNER, "apply", STRING, listOf("int"))
        assertSame(owner, signature.declClassType)
        assertSame(singleton.getClassType(STRING), signature.type)
        assertEquals(listOf(PrimitiveType.getInt()), signature.parameterTypes)
    }

    @Test
    fun `raw unusual names are delegated unchanged and only successful nonnull requests are cached`() {
        val delegate = RecordingFactory()
        val factory = GraphiteIdentifierFactory(delegate)
        val names = listOf("", ".Leading", "sample..Nested", "java/lang/String", "[Ljava.lang.String;", null)
        for (name in names) {
            val expected = singleton.getClassType(name)
            assertSame(expected, factory.getClassType(name), "first lookup of $name")
            assertSame(expected, factory.getClassType(name), "repeat lookup of $name")
        }
        assertEquals(names + listOf(null), delegate.names)

        delegate.failNext = true
        val failed = assertFailsWith<IllegalStateException> { factory.getClassType(OWNER) }
        assertEquals(REJECTION, failed.message)
        assertSame(singleton.getClassType(OWNER), factory.getClassType(OWNER))
        assertSame(singleton.getClassType(OWNER), factory.getClassType(OWNER))
        assertEquals(listOf(OWNER, OWNER), delegate.names.takeLast(2))
    }

    @Test
    fun `type parsing retains invalid descriptor failures and fresh array instances`() {
        val factory = GraphiteIdentifierFactory()
        for (descriptor in listOf("", "int[", "int]")) {
            val expected = assertFailsWith<IllegalArgumentException> { singleton.getType(descriptor) }
            repeat(2) {
                val actual = assertFailsWith<IllegalArgumentException> { factory.getType(descriptor) }
                assertEquals(expected.message, actual.message)
            }
        }
        val first = assertIs<ArrayType>(factory.getType("$STRING[][]"))
        val second = factory.getType("$STRING[][]")
        assertEquals(singleton.getType("$STRING[][]"), first)
        assertNotSame(first, second)
        assertSame(singleton.getClassType(STRING), first.baseType)
        assertEquals(2, first.dimension)
        for (name in listOf("int", "null", "void")) assertSame(singleton.getType(name), factory.getType(name))
    }

    @Test
    fun `concurrent lookups publish the canonical object and warm reads stop delegating`() {
        val delegate = RecordingFactory()
        val factory = GraphiteIdentifierFactory(delegate)
        val expected = singleton.getClassType(OWNER)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(WORKERS)
        try {
            val results = List(WORKERS) {
                workers.submit(Callable {
                    check(start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    factory.getClassType(OWNER)
                })
            }
            start.countDown()
            results.forEach { assertSame(expected, it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)) }
            val callsAfterRace = delegate.names.toList()
            assertEquals(setOf(OWNER), callsAfterRace.toSet())
            val warmReads = workers.invokeAll(List(WORKERS) { Callable { factory.getClassType(OWNER) } })
            warmReads.forEach { assertSame(expected, it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)) }
            assertEquals(callsAfterRace, delegate.names, "warm hits must not enter the singleton's cache again")
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `independent factories do not share their strong lookup cache`() {
        val firstDelegate = RecordingFactory()
        val secondDelegate = RecordingFactory()
        val first = GraphiteIdentifierFactory(firstDelegate)
        val second = GraphiteIdentifierFactory(secondDelegate)
        val expected = singleton.getClassType(OWNER)
        repeat(2) { assertSame(expected, first.getClassType(OWNER)) }
        assertEquals(listOf<String?>(OWNER), firstDelegate.names)
        assertEquals(emptyList(), secondDelegate.names)
        repeat(2) { assertSame(expected, second.getClassType(OWNER)) }
        assertEquals(listOf<String?>(OWNER), secondDelegate.names)
        assertEquals(listOf<String?>(OWNER), firstDelegate.names)
    }

    private class RecordingFactory : JavaIdentifierFactory() {
        val names: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        var failNext = false

        override fun getClassType(fullyQualifiedClassName: String?): JavaClassType {
            names.add(fullyQualifiedClassName)
            if (failNext) {
                failNext = false
                error(REJECTION)
            }
            return JavaIdentifierFactory.getInstance().getClassType(fullyQualifiedClassName)
        }
    }

    private companion object {
        const val PACKAGE = "sample.cached"
        const val OWNER = "$PACKAGE.Outer\$Inner"
        const val STRING = "java.lang.String"
        const val REJECTION = "delegate rejected this request"
        const val WORKERS = 4
        const val TIMEOUT_SECONDS = 10L
    }
}
