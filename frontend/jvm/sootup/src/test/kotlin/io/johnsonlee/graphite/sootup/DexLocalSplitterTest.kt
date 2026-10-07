package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import sootup.apk.frontend.ApkAnalysisInputLocation
import sootup.apk.frontend.DexBodyInterceptors
import sootup.apk.frontend.main.AndroidVersionInfo
import sootup.core.interceptor.BodyInterceptor
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr
import sootup.core.jimple.common.expr.JNewExpr
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.ref.JStaticFieldRef
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.InvokableStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.java.core.views.JavaView

class DexLocalSplitterTest {
    @Test
    fun `loader reports method fallbacks keeps the class and resets the count on its next load`() {
        val apk = Path.of(requireNotNull(System.getProperty("frontend.correctness.android")))
        val sdk = Files.createTempDirectory("dex-interceptor-fallback-sdk")
        try {
            val platform = Files.createDirectories(sdk.resolve("platforms/android-21"))
            JarOutputStream(Files.newOutputStream(platform.resolve("android.jar"))).use { }
            val messages = mutableListOf<String>()
            val loader = JavaProjectLoader(LoaderConfig(
                androidSdk = sdk, buildCallGraph = false, verbose = messages::add
            ), useMmapBuilder = false)
            val defaults = DexBodyInterceptors.Default
            // Inject at the actual upstream boundary, without adding a production configuration
            // option just for tests. Restore the enum's chain even if the load/assertions fail.
            val field = defaults.javaClass.getDeclaredField("bodyInterceptors").apply { isAccessible = true }
            synchronized(defaults) {
                val original = defaults.bodyInterceptors()
                try {
                    field.set(defaults, original + BodyInterceptor { builder, _ ->
                        if (builder.methodSignature.name == "pair") {
                            builder.locals.clear()
                            error("injected dex failure")
                        }
                    })
                    val graph = loader.load(apk)
                    assertEquals(1, loader.bodyInterceptionFallbackCount)
                    val calls = graph.nodes<CallSiteNode>().filter {
                        it.callee.declaringClass.className == "java.lang.Runnable" && it.callee.name == "run"
                    }.toList()
                    assertEquals(2, calls.count { it.caller.name == "pair" }, "failed method keeps its original calls")
                    assertEquals(66, calls.count { it.caller.name == "saturated" }, "sibling method still builds")
                    assertEquals(1, messages.count { "Keeping unintercepted body" in it && "injected dex failure" in it })
                    assertTrue(messages.any { "Kept 1 APK method body/bodies" in it })
                } finally {
                    field.set(defaults, original)
                }
                messages.clear()
                loader.load(apk)
                assertEquals(0, loader.bodyInterceptionFallbackCount)
                assertTrue(messages.none { "Keeping unintercepted body" in it || "body/bodies without interception" in it })
            }
        } finally {
            sdk.toFile().deleteRecursively()
        }
    }

    @Test
    fun `APK calls on reused registers resolve only their own lambda beyond the target cap`() {
        val apk = Path.of(requireNotNull(System.getProperty("frontend.correctness.android")))
        val sdk = Files.createTempDirectory("dex-local-splitter-sdk")
        try {
            val platforms = sdk.resolve("platforms")
            val platform = Files.createDirectories(platforms.resolve("android-21"))
            JarOutputStream(Files.newOutputStream(platform.resolve("android.jar"))).use { }
            // Verify the fixture really exercises register reuse in the old APK chain.
            val original = JavaView(ApkAnalysisInputLocation(
                apk, AndroidVersionInfo(apk, platforms.toString()), DexBodyInterceptors.Default.bodyInterceptors()
            ))
            val owner = "fixture.frontend.android.RegisterReuse"
            val type = original.getClass(original.identifierFactory.getClassType(owner)).get()
            val expectedTargets = mutableMapOf<String, List<String>>()
            for ((name, count) in listOf("pair" to 2, "saturated" to 66)) {
                val statements = type.methods.single { it.name == name }.body.stmts
                val receivers = statements
                    .mapNotNull { (it as? InvokableStmt)?.invokeExpr?.orElse(null) as? AbstractInstanceInvokeExpr }
                    .filter { it.methodSignature.name == "run" }.map { it.base.name }
                assertEquals(count, receivers.size)
                assertEquals(1, receivers.toSet().size, "$name must reuse one dex register")
                val targets = expectedTargetClasses(statements)
                assertEquals(count, targets.toSet().size)
                expectedTargets[name] = targets
            }

            val loader = JavaProjectLoader(LoaderConfig(androidSdk = sdk, buildCallGraph = false), useMmapBuilder = false)
            val graph = loader.load(apk)
            val calls = graph.nodes<CallSiteNode>().filter { it.caller.declaringClass.className == owner }.toList()
            for ((name, count) in listOf("pair" to 2, "saturated" to 66)) {
                val direct = calls.filter {
                    it.caller.name == name && it.callee.name == "run" && it.callee.declaringClass.className == "java.lang.Runnable"
                }.sortedBy { it.ordinal }
                assertEquals(count, direct.size)
                val targets = direct.map { call ->
                    val resolved = calls.filter { it.origin == call.id }
                    assertEquals(1, resolved.size, "$name call ${call.ordinal} must have exactly one target")
                    assertEquals("run", resolved.single().callee.name)
                    assertTrue(resolved.single().callee.declaringClass.className.startsWith(owner + "$$"))
                    assertEquals(call.receiver, resolved.single().receiver)
                    resolved.single().callee
                }
                assertEquals(count, targets.toSet().size, "$name must reach each distinct lambda exactly once")
                assertEquals(expectedTargets.getValue(name), targets.map { it.declaringClass.className })
            }
            assertEquals(0, loader.bodyInterceptionFallbackCount)
        } finally {
            sdk.toFile().deleteRecursively()
        }
    }

    private fun expectedTargetClasses(statements: List<Stmt>): List<String> {
        val classesByRegister = mutableMapOf<String, String>()
        val targets = mutableListOf<String>()
        for (stmt in statements) {
            if (stmt is JAssignStmt && stmt.leftOp is Local) {
                val allocated = when (val value = stmt.rightOp) {
                    is JNewExpr -> value.type.toString()
                    is JStaticFieldRef -> value.type.toString()
                    is Local -> classesByRegister[value.name]
                    else -> null
                }
                allocated?.let { classesByRegister[(stmt.leftOp as Local).name] = it }
            }
            val invoke = (stmt as? InvokableStmt)?.invokeExpr?.orElse(null) as? AbstractInstanceInvokeExpr
            if (invoke?.methodSignature?.name == "run") targets.add(classesByRegister.getValue(invoke.base.name))
        }
        return targets
    }
}
