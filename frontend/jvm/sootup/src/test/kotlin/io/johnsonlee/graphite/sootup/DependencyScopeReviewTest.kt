package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.ArtifactRole
import io.johnsonlee.graphite.graph.analysisStatus
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.DependencyBodyScope
import io.johnsonlee.graphite.input.JavaArchiveLayout
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
/**
 * The dependency-scope behaviours the reviews of this feature pinned, each on the shape that
 * reproduced the finding: a callee noted non-virtually before virtually, a call on a function
 * value resolved after every class was processed, an application-package class a nested library
 * jar holds, an APK beside its platform jar, a zip input, and an application definition beside
 * an indexed library's.
 */
class DependencyScopeReviewTest : DependencyScopeFixture() {

    @Test
    fun `a virtual call noted after a non-virtual one on the same callee still reaches the overrides`() {
        val input = bootJar(extraAppSources = mapOf(childSource))
        val config = LoaderConfig(
            includePackages = listOf(appPackage),
            includeLibraries = true,
            buildCallGraph = false,
            dependencyBodies = DependencyBodyScope.Reachable(roots = listOf("$appPackage.Child"))
        )
        val graph = load(input, config)
        val completeness = assertNotNull(graph.analysisCompleteness())
        val base = "$depPackage.Base"
        val calls = graph.nodes<CallSiteNode>().filter { it.callee.name == "value" && it.callee.declaringClass.className == base }
        assertEquals(listOf("apply", "zone"), calls.map { it.caller.name }.sorted().toList(), "both calls name Base.value")
        assertEquals(AnalysisStatus.ANALYZED, completeness.classStatus(base), "the declared callee")
        assertEquals(AnalysisStatus.ANALYZED, completeness.classStatus("$depPackage.Sub"), "the override the virtual call reaches")
        assertEquals(AnalysisStatus.EXCLUDED_BY_SCOPE, completeness.classStatus(reader), "unreached from that root")
    }

    @Test
    fun `a call on a function value resolved after processing seeds the expansion by its own caller`() {
        val input = bootJar(extraAppSources = deferredDispatchSources)
        val config = LoaderConfig(
            includePackages = listOf(appPackage),
            includeLibraries = true,
            buildCallGraph = false,
            dependencyBodies = DependencyBodyScope.Reachable(roots = listOf("$appPackage.Root"))
        )
        val graph = load(input, config)
        val completeness = assertNotNull(graph.analysisCompleteness())
        val resolved = graph.nodes<CallSiteNode>().filter { it.caller.name == "run" && it.callee.name == "fixed" }.toList()
        assertEquals(1, resolved.size, "Root.run reaches Reader.fixed through the supplier Factory made")
        assertEquals(AnalysisStatus.ANALYZED, graph.analysisStatus(resolved.single().callee), "reached from the root's call")
        assertEquals(AnalysisStatus.EXCLUDED_BY_SCOPE, completeness.classStatus("$depPackage.Source"), "nothing else is reached")
    }

    @Test
    fun `roots restrict an application-package class a nested library jar holds, at a deferred dispatch too`() {
        val nestedClasses = compile(nestedAppSources, "nested", classpath = listOf(depJar()))
        val nested = jar(
            Files.createTempDirectory("scope-nested").also(temporaryRoots::add).resolve("appext-1.0.jar"),
            nestedClasses,
            "sample/scope/app/"
        )
        val input = bootJar(extraAppSources = mapOf(quietRootSource), extraLibs = listOf(nested))
        val config = LoaderConfig(
            includePackages = listOf(appPackage),
            includeLibraries = true,
            buildCallGraph = false,
            dependencyBodies = DependencyBodyScope.Reachable(roots = listOf("$appPackage.Root"))
        )
        val graph = load(input, config)
        val completeness = assertNotNull(graph.analysisCompleteness())
        assertEquals("appext-1.0.jar", completeness.classes.getValue("$appPackage.NonRoot").origin)
        assertEquals(AnalysisStatus.ANALYZED, completeness.classStatus("$appPackage.NonRoot"), "the application, wherever it ships")
        assertTrue(
            graph.nodes<CallSiteNode>().any { it.caller.name == "go" && it.callee.name == "fixed" },
            "NonRoot.go reaches Reader.fixed through the supplier"
        )
        val fixed = readMethod.copy(name = "fixed", parameterTypes = emptyList())
        assertEquals(AnalysisStatus.EXCLUDED_BY_SCOPE, graph.analysisStatus(fixed), "not a root's call")
        assertEquals(AnalysisStatus.EXCLUDED_BY_SCOPE, completeness.classStatus(reader), "no root reaches the helper")
    }

    @Test
    fun `an APK's platform classes carry the platform jar's origin, and a declared scope reads them`() {
        val apk = Path.of(requireNotNull(System.getProperty("frontend.correctness.android")))
        val sdk = Files.createTempDirectory("scope-sdk").also(temporaryRoots::add)
        val platform = Files.createDirectories(sdk.resolve("platforms/android-21"))
        val probe = compile(
            mapOf("Probe.java" to "package android.review; public class Probe { public static int size() { return 1; } }"),
            "probe"
        )
        jar(platform.resolve("android.jar"), probe, "android/review/")
        val config = LoaderConfig(
            includePackages = listOf("fixture.frontend.android"),
            includeLibraries = true,
            buildCallGraph = false,
            androidSdk = sdk,
            dependencyBodies = DependencyBodyScope.Declared(artifacts = listOf("android.jar"))
        )
        val graph = load(apk, config)
        val completeness = assertNotNull(graph.analysisCompleteness())
        val platformSource = "android-21/android.jar"
        assertEquals(
            mapOf(apk.fileName.toString() to ArtifactRole.APPLICATION, platformSource to ArtifactRole.PLATFORM),
            completeness.roles()
        )
        val probeEvidence = completeness.classes.getValue("android.review.Probe")
        assertEquals(platformSource, probeEvidence.origin, "a platform class is the platform's, not the APK's")
        assertEquals(AnalysisStatus.ANALYZED, probeEvidence.status, "the declared scope names the platform jar")
        assertEquals(apk.fileName.toString(), completeness.classes.getValue("fixture.frontend.android.AndroidCases").origin)
        assertEquals(emptyMap(), graph.classOrigins(), "the graph's own origin index of a single-artifact input is unchanged")
    }

    @Test
    fun `a zip input is listed like a jar, so its multi-release copies are reported`() {
        val classes = compile(otherSources, "zip")
        val util = classes.resolve("sample/scope/other/Util.class")
        val root = Files.createTempDirectory("scope-zip").also(temporaryRoots::add)
        val input = jar(
            root.resolve("other.zip"),
            classes,
            "sample/scope/other/",
            extra = mapOf("META-INF/versions/9/sample/scope/other/Util.class" to Files.readAllBytes(util)),
            multiRelease = true
        )
        val graph = load(input, LoaderConfig(includePackages = listOf("sample.scope.other"), buildCallGraph = false))
        val completeness = assertNotNull(graph.analysisCompleteness())
        assertEquals(mapOf("other.zip" to ArtifactRole.APPLICATION), completeness.roles())
        assertTrue(completeness.artifacts.getValue("other.zip").multiRelease, "the Java 9 copy is reported")
        assertEquals(AnalysisStatus.ANALYZED, completeness.classStatus("sample.scope.other.Util"))
    }

    @Test
    fun `an application definition keeps its precedence over an indexed library's in the duplicate evidence`() {
        val input = bootJar(classpathIndex = listOf("other-1.0.jar", "dep-1.0.jar"), duplicateInOther = listOf(ab))
        val config = LoaderConfig(
            includePackages = listOf(appPackage),
            includeLibraries = true,
            buildCallGraph = false,
            dependencyBodies = DependencyBodyScope.Declared(artifacts = listOf("dep-*.jar", "other-*.jar"))
        )
        val graph = load(input, config)
        val completeness = assertNotNull(graph.analysisCompleteness())
        assertEquals(JavaArchiveLayout.BOOT_INF_CLASSES, completeness.classes.getValue(ab).origin, "the view holds the application's")
        assertEquals(
            mapOf(
                ab to listOf(JavaArchiveLayout.BOOT_INF_CLASSES, "other-1.0.jar"),
                "$depPackage.Source" to listOf("other-1.0.jar", "dep-1.0.jar")
            ),
            completeness.duplicateClasses,
            "the index orders the libraries behind the application, which it does not list"
        )
    }
}
