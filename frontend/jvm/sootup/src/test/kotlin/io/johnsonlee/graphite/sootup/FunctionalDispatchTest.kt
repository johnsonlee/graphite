package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FunctionalDispatchTest {

    @Test
    fun `Kotlin lambda, suspend lambda and reference base classes are function bases`() {
        listOf(
            "kotlin.jvm.internal.Lambda",
            "kotlin.jvm.internal.FunctionReferenceImpl",
            "kotlin.jvm.internal.PropertyReference1Impl",
            "kotlin.coroutines.jvm.internal.SuspendLambda",
            "kotlin.coroutines.jvm.internal.RestrictedSuspendLambda"
        ).forEach { assertTrue(isKotlinFunctionBaseClass(it), it) }
        listOf("java.lang.Object", "kotlin.jvm.internal.Intrinsics", "kotlin.coroutines.jvm.internal.ContinuationImpl")
            .forEach { assertFalse(isKotlinFunctionBaseClass(it), it) }
    }

    @Test
    fun `Kotlin function interfaces and property references are recognized`() {
        assertTrue(isKotlinFunctionInterface("kotlin.jvm.functions.Function1"))
        assertTrue(isKotlinFunctionInterface("kotlin.jvm.internal.FunctionBase"))
        assertFalse(isKotlinFunctionInterface("java.util.function.Function"))
        assertTrue(isKotlinPropertyReferenceClass("kotlin.jvm.internal.MutablePropertyReference0Impl"))
        assertFalse(isKotlinPropertyReferenceClass("kotlin.jvm.internal.FunctionReferenceImpl"))
        assertTrue(isKotlinCallableReferenceBaseClass("kotlin.jvm.internal.FunctionReferenceImpl"))
        assertTrue(isKotlinCallableReferenceBaseClass("kotlin.jvm.internal.PropertyReference1Impl"))
        assertFalse(isKotlinCallableReferenceBaseClass("kotlin.jvm.internal.Lambda"))
        assertFalse(isKotlinCallableReferenceBaseClass("kotlin.coroutines.jvm.internal.SuspendLambda"))
    }

    @Test
    fun `anonymous classes are recognized by their binary name`() {
        listOf(
            "com.example.Foo\$1",
            "com.example.Foo\$bar\$fn\$1",
            "com.example.Foo\$sam\$java_lang_Runnable\$0",
            "com.example.Foo\$\$Lambda\$1"
        ).forEach { assertTrue(isAnonymousClassName(it), it) }
        listOf(
            "com.example.Foo",
            "com.example.Foo\$Inner",
            "com.example.Foo\$",
            "com.example.Foo\$-CC",
            "com.example.Foo\$\$ExternalSyntheticLambda0",
            "com.example.-\$\$Lambda\$Foo\$eFOXuxnqz1kRVdEJ-a2Axi8PgVU"
        ).forEach { assertFalse(isAnonymousClassName(it), it) }
    }

    @Test
    fun `adapting a target wraps it until the depth bound`() {
        val obj = TypeDescriptor("java.lang.Object")
        val apply = MethodDescriptor(TypeDescriptor("java.util.function.Function"), "apply", listOf(obj), obj)
        val adapter = SlotAdapter(DispatchSlot.Field("f"), "apply", apply, emptyList())
        val leaf: DispatchTarget = DispatchTarget.FunctionObject("com.example.Foo\$1")
        assertEquals(0, leaf.adaptedDepth())
        val chain = generateSequence(leaf) { adapter.adapt(it) }.toList()
        assertEquals(5, chain.size, "a target is adapted four times, then no further")
        assertEquals(4, chain.last().adaptedDepth())
    }

    @Test
    fun `primitives and concrete JDK types never hold a function value`() {
        assertTrue(isNonFunctionType("int"))
        assertTrue(isNonFunctionType("java.lang.String"))
        assertFalse(isNonFunctionType("java.lang.Object"))
        assertFalse(isNonFunctionType("java.util.function.Function"))
    }
}
