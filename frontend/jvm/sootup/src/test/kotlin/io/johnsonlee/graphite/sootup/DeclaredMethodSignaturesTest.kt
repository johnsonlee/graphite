package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.sootup.testfixture.minimalAndroidSdk
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import sootup.apk.frontend.ApkAnalysisInputLocation
import sootup.apk.frontend.dexpler.DexClassSource
import sootup.apk.frontend.main.AndroidVersionInfo
import sootup.core.interceptor.BodyInterceptor
import sootup.core.model.SourceType
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation
import sootup.java.core.JavaSootClass
import sootup.java.core.views.JavaView

class DeclaredMethodSignaturesTest : DependencyScopeFixture() {
    @Test
    fun `DEX declaration lookup preserves overloads and arrays without entering body interceptors`() {
        val apk = Path.of(requireNotNull(System.getProperty("frontend.correctness.android")))
        val sdk = minimalAndroidSdk("dex-signatures-sdk", apiLevel = 21).also(temporaryRoots::add)
        var bodies = 0
        val location = ApkAnalysisInputLocation(apk, AndroidVersionInfo(apk, sdk.resolve("platforms").toString()),
            listOf(BodyInterceptor { _, _ -> bodies++; error("declaration lookup must not construct a DEX body") }))
        val view = JavaView(location)
        val owner = view.identifierFactory.getClassType("fixture.frontend.android.AndroidCases")
        val declared = view.getClass(owner).get()
        val signatures = declaredMethodSignatures(declared, view.identifierFactory)
        assertEquals(listOf(
            "<fixture.frontend.android.AndroidCases: int sink(int)>",
            "<fixture.frontend.android.AndroidCases: java.lang.String sink(java.lang.String,int)>"
        ), signatures.filter { it.name == "sink" }.map { it.toString() }.sorted())
        assertEquals(listOf("int[]", "int"), signatures.single { it.name == "arrayStore" }.parameterTypes.map(::graphTypeName))
        assertEquals("void", graphTypeName(signatures.single { it.name == "arrayStore" }.type))
        assertEquals(listOf("long"), signatures.single { it.name == "constantNodes" }.parameterTypes.map(::graphTypeName))
        assertEquals("double", graphTypeName(signatures.single { it.name == "constantNodes" }.type))
        assertTrue(signatures.all { it.declClassType == owner })
        assertFalse(signatures.any { it.name == "missing" })
        assertEquals(signatures, declaredMethodSignatures(declared, view.identifierFactory))
        val missingType = view.identifierFactory.getClassType("fixture.frontend.android.Missing")
        val missing = JavaSootClass(DexClassSource(view, location, missingType, apk), SourceType.Application)
        assertEquals(emptyList(), declaredMethodSignatures(missing, view.identifierFactory))
        assertEquals(0, bodies, "neither a present nor a missing declaration may resolve any method body")
    }

    @Test
    fun `bytecode declarations retain exact return types and multidimensional overloads`() {
        val classes = compile(mapOf("Declarations.java" to """
            package sample.signatures;
            public class Declarations {
                public int[][] transform(String[][] value) { return null; }
                public long transform(int[] value) { return 1L; }
            }
        """.trimIndent()), "declaration-signatures")
        var bodies = 0
        val view = JavaView(PathBasedAnalysisInputLocation.create(classes, SourceType.Application,
            listOf(BodyInterceptor { _, _ -> bodies++; error("declaration lookup must not resolve bytecode bodies") })))
        val owner = view.identifierFactory.getClassType("sample.signatures.Declarations")
        val signatures = declaredMethodSignatures(view.getClass(owner).get(), view.identifierFactory)
        assertEquals(listOf(
            "<sample.signatures.Declarations: int[][] transform(java.lang.String[][])>",
            "<sample.signatures.Declarations: long transform(int[])>",
            "<sample.signatures.Declarations: void <init>()>"
        ), signatures.map { it.toString() }.sorted())
        assertEquals(0, bodies)
    }
}
