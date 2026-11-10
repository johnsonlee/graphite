package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.graph.ArtifactRole
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.DependencyBodyScope
import io.johnsonlee.graphite.input.FoldPlan
import io.johnsonlee.graphite.input.FoldReport
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidPlatformFoldingContractTest : DependencyScopeFixture() {
    @Test
    fun `platform library bodies apply folding and keep their platform provenance`() {
        val apk = Path.of(requireNotNull(System.getProperty("frontend.correctness.android")))
        val sdk = Files.createTempDirectory("fold-platform-sdk").also(temporaryRoots::add)
        val platform = Files.createDirectories(sdk.resolve("platforms/android-21"))
        val classes = compile(mapOf(
            "Flags.java" to "package android.review; public class Flags { public static boolean enabled() { return true; } }",
            "Probe.java" to """
                package android.review;
                public class Probe {
                    public static int size() { return Flags.enabled() ? 1 : 2; }
                }
            """.trimIndent()
        ), "platform-fold")
        val androidJar = jar(platform.resolve("android.jar"), classes, "android/review/")
        val reports = mutableListOf<FoldReport>()
        val rule = ConstantFold.parse(mapOf(
            "match" to mapOf("CallSite" to mapOf("caller_class" to "android.review.Probe", "callee_name" to "enabled")),
            "value" to false
        ))
        val config = LoaderConfig(
            includePackages = listOf("fixture.frontend.android"), includeLibraries = true,
            buildCallGraph = false, androidSdk = sdk,
            dependencyBodies = DependencyBodyScope.Declared(artifacts = listOf("android.jar")),
            folding = FoldPlan(listOf(rule), reports::add)
        )
        assertEquals(androidJar, androidPlatformJar(apk, config))
        val graph = load(apk, config)
        val outcome = reports.single().outcomes.single()
        assertEquals(1, outcome.matched)
        assertTrue(outcome.unsupported.isEmpty())
        assertEquals(listOf("android.review.Probe.size()"), reports.single().methods.map { it.method })
        assertFalse(graph.nodes<CallSiteNode>().any {
            it.caller.declaringClass.className == "android.review.Probe" && it.callee.name == "enabled"
        }, "The folded platform call must be removed from the final graph")
        val completeness = assertNotNull(graph.analysisCompleteness())
        assertEquals(ArtifactRole.PLATFORM, completeness.artifacts.getValue("android-21/android.jar").role)
        assertEquals("android-21/android.jar", completeness.classes.getValue("android.review.Probe").origin)
    }
}
