package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.ArtifactRole
import io.johnsonlee.graphite.graph.CallSiteGap
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
/**
 * The dependency-scope behaviours the reviews of this feature pinned, each on the shape that
 * reproduced the finding: a callee noted non-virtually before virtually, a call on a function
 * value resolved after every class was processed, an application-package class a nested library
 * jar holds, an APK beside its platform jar, a zip input, and an application definition beside
 * an indexed library's.
 */
class DependencyScopeReviewTest : DependencyScopeFixture() {

    /** A root whose only dependency access is a static field read: `getstatic` runs the class initialiser. */
    private val staticAccessSource = "Init.java" to """
        package sample.scope.app;
        public class Init { public String viaStatic() { return sample.scope.dep.Config.value; } }
    """.trimIndent()

    /** A receiver that merges a lambda with a value the scope cannot name, beside a known-only control. */
    private val mixedDispatchSource = "Mixed.java" to """
        package sample.scope.app;
        import java.util.function.Supplier;
        public class Mixed {
            public String viaMixed(Supplier<String> s, boolean flag) {
                Supplier<String> selected = flag ? () -> "k" : s;
                return selected.get();
            }
            public String viaKnown(boolean flag) {
                Supplier<String> selected = flag ? () -> "a" : () -> "b";
                return selected.get();
            }
        }
    """.trimIndent()

    @Test
    fun `an unloaded library keeps its indexed place in the duplicate evidence`() {
        val input = bootJar(classpathIndex = listOf("dep-1.0.jar", "other-1.0.jar"))
        val config = LoaderConfig(
            includePackages = listOf(appPackage),
            includeLibraries = true,
            buildCallGraph = false,
            dependencyBodies = DependencyBodyScope.Declared(artifacts = listOf("dep-*.jar"))
        )
        val completeness = assertNotNull(load(input, config).analysisCompleteness())
        assertFalse(completeness.artifacts.containsKey("other-1.0.jar"), "not loaded: no application package, not named")
        assertEquals("dep-1.0.jar", completeness.classes.getValue("$depPackage.Source").origin)
        assertEquals(
            listOf("dep-1.0.jar", "other-1.0.jar"),
            completeness.duplicateClasses.getValue("$depPackage.Source"),
            "the index ranks an archive the build never loaded as the library it is"
        )
    }

    @Test
    fun `a static field access reaches the class whose initialiser it runs`() {
        val input = bootJar(extraAppSources = mapOf(staticAccessSource))
        val config = LoaderConfig(
            includePackages = listOf(appPackage),
            includeLibraries = true,
            buildCallGraph = false,
            dependencyBodies = DependencyBodyScope.Reachable(roots = listOf("$appPackage.Init"))
        )
        val graph = load(input, config)
        val completeness = assertNotNull(graph.analysisCompleteness())
        assertEquals(AnalysisStatus.ANALYZED, completeness.classStatus("$depPackage.Config"), "initialised by the read")
        assertTrue(
            graph.nodes<CallSiteNode>().any {
                it.caller.declaringClass.className == "$depPackage.Config" && it.caller.name == "<clinit>" && it.callee.name == "getBundle"
            },
            "the initialiser's resource read is in the graph"
        )
        assertEquals(AnalysisStatus.EXCLUDED_BY_SCOPE, completeness.classStatus(reader), "nothing else is reached from the root")
    }

    @Test
    fun `a call whose receiver may hold a value the scope cannot name is a partial gap`() {
        val input = bootJar(extraAppSources = mapOf(mixedDispatchSource))
        val graph = load(input, LoaderConfig(includePackages = listOf(appPackage), includeLibraries = true, buildCallGraph = false))
        val completeness = assertNotNull(graph.analysisCompleteness())
        val calls = graph.nodes<CallSiteNode>().toList()
        fun get(caller: String) = calls.single { it.caller.name == caller && it.callee.name == "get" }
        fun bodies(caller: String) = calls.count { it.origin == get(caller).id }
        assertEquals(1, bodies("viaMixed"), "the known lambda is dispatched")
        assertEquals(CallSiteGap.DISPATCH_PARTIAL, completeness.callSiteGaps[get("viaMixed").id.value], "and the parameter is not")
        assertEquals(2, bodies("viaKnown"))
        assertNull(completeness.callSiteGaps[get("viaKnown").id.value], "every alternative is a known body")
        assertEquals(CallSiteGap.DISPATCH_UNRESOLVED, completeness.callSiteGaps[get("viaUnknown").id.value], "no body at all")
    }

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
