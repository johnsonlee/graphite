package io.johnsonlee.graphite.sootup

import kotlin.test.Test
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
    }

    @Test
    fun `generated function classes are recognized by name`() {
        listOf(
            "com.example.Foo\$1",
            "com.example.Foo\$bar\$fn\$1",
            "com.example.Foo\$sam\$java_lang_Runnable\$0",
            "com.example.Foo\$\$ExternalSyntheticLambda0",
            "com.example.-\$\$Lambda\$Foo\$eFOXuxnqz1kRVdEJ-a2Axi8PgVU",
            "com.example.Foo\$\$Lambda\$1"
        ).forEach { assertTrue(isGeneratedFunctionClassName(it), it) }
        listOf("com.example.Foo", "com.example.Foo\$Inner", "com.example.Foo\$", "com.example.Foo\$-CC")
            .forEach { assertFalse(isGeneratedFunctionClassName(it), it) }
    }

    @Test
    fun `primitives and concrete JDK types never hold a function value`() {
        assertTrue(isNonFunctionType("int"))
        assertTrue(isNonFunctionType("java.lang.String"))
        assertFalse(isNonFunctionType("java.lang.Object"))
        assertFalse(isNonFunctionType("java.util.function.Function"))
    }
}
