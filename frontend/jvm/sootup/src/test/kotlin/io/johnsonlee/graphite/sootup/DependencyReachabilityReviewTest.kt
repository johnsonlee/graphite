package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.DependencyBodyScope
import io.johnsonlee.graphite.input.FoldPlan
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reachability behaviours the seventh review of this feature pinned: a root's call followed
 * through the application's own helpers (and not through a call the fold removed, nor through a
 * helper method nothing from a root reaches), the initialisation prerequisites of a reached
 * library class walked through an application class in its hierarchy, and an inherited instance
 * field read through a subclass sharing the declaration's dispatch slot.
 */
class DependencyReachabilityReviewTest : DependencyScopeFixture() {

    private val ext = "sample.scope.ext"

    /** Library classes the application's helpers call, one per helper path. */
    private val liveSources = listOf("Live", "Gone", "Other").associate { name ->
        "$name.java" to """
            package sample.scope.ext;
            public class $name {
                public static String read() { return java.util.ResourceBundle.getBundle("messages").getString("${name.lowercase()}"); }
            }
        """.trimIndent()
    }

    /** A root whose surviving call goes through an application helper; the gated call and the helper's other method do not. */
    private val helperSources = mapOf(
        "Root.java" to """
            package sample.scope.app;
            public class Root {
                public String run() {
                    if (Ab.enabled("feature")) return Dead.read();
                    return Helper.read();
                }
            }
        """.trimIndent(),
        "Helper.java" to """
            package sample.scope.app;
            public class Helper {
                public static String read() { return sample.scope.ext.Live.read(); }
                public static String other() { return sample.scope.ext.Other.read(); }
            }
        """.trimIndent(),
        "Dead.java" to """
            package sample.scope.app;
            public class Dead { public static String read() { return sample.scope.ext.Gone.read(); } }
        """.trimIndent()
    )

    /** A library class whose superclass is the application's, whose superclass is the library's again. */
    private val chainSources = mapOf(
        "Base.java" to """
            package sample.scope.ext;
            public class Base {
                public static final String seeded = java.util.ResourceBundle.getBundle("messages").getString("base");
            }
        """.trimIndent(),
        "Owner.java" to """
            package sample.scope.ext;
            public class Owner extends sample.scope.app.Mid { public static String read() { return "owner"; } }
        """.trimIndent(),
        "Mid.java" to """
            package sample.scope.app;
            public class Mid extends sample.scope.ext.Base { }
        """.trimIndent()
    )
    private val chainRootSource = "Root.java" to """
        package sample.scope.app;
        public class Root { public String run() { return sample.scope.ext.Owner.read(); } }
    """.trimIndent()

    /** An inherited instance field a superclass stores a function value into and a subclass reference reads back. */
    private val aliasSources = mapOf(
        "Base.java" to """
            package sample.scope.app;
            import java.util.function.Supplier;
            public class Base {
                public Supplier<String> callback;
                public String instanceValue() { return "base"; }
                public void install() { callback = this::instanceValue; }
            }
        """.trimIndent(),
        "Child.java" to """
            package sample.scope.app;
            public class Child extends Base { }
        """.trimIndent(),
        "Root.java" to """
            package sample.scope.app;
            public class Root {
                public String replacement() { return "replacement"; }
                public String run(boolean flag) {
                    Child child = new Child();
                    child.install();
                    if (flag) child.callback = this::replacement;
                    return child.callback.get();
                }
            }
        """.trimIndent()
    )

    private fun extJar(sources: Map<String, String>, label: String): Path {
        val classes = compile(sources, label)
        return jar(classes.parent.resolve("ext-1.0.jar"), classes, "sample/scope/ext/")
    }

    private fun reachableFromRoot(maxClasses: Int = 10, folding: FoldPlan? = null) = LoaderConfig(
        includePackages = listOf(appPackage),
        includeLibraries = true,
        buildCallGraph = false,
        dependencyBodies = DependencyBodyScope.Reachable(maxClasses, roots = listOf("$appPackage.Root")),
        folding = folding
    )

    @Test
    fun `a root's surviving calls are followed through the application's own helpers`() {
        val input = bootJar(extraAppSources = helperSources, extraLibs = listOf(extJar(liveSources, "ext-live")))
        val graph = load(input, reachableFromRoot(folding = FoldPlan(listOf(gateOff))))
        val completeness = assertNotNull(graph.analysisCompleteness())
        val calls = graph.nodes<CallSiteNode>().filter { it.caller.declaringClass.className.startsWith(appPackage) }.toList()
        assertTrue(calls.any { it.caller.name == "run" && it.callee.declaringClass.className.endsWith("Helper") }, "the surviving call")
        assertFalse(calls.any { it.caller.name == "run" && it.callee.declaringClass.className.endsWith("Dead") }, "folded away")
        assertEquals(AnalysisStatus.ANALYZED, completeness.classStatus("$appPackage.Helper"), "the application is read")
        assertEquals(AnalysisStatus.ANALYZED, completeness.classStatus("$appPackage.Dead"))
        assertEquals(AnalysisStatus.ANALYZED, completeness.classStatus("$ext.Live"), "reached from the root through Helper.read")
        assertEquals(AnalysisStatus.EXCLUDED_BY_SCOPE, completeness.classStatus("$ext.Gone"), "the call to Dead.read was folded away")
        assertEquals(AnalysisStatus.EXCLUDED_BY_SCOPE, completeness.classStatus("$ext.Other"), "nothing from the root reaches Helper.other")
    }

    @Test
    fun `initialisation prerequisites are walked through an application class in a reached library class's hierarchy`() {
        val appSources = mapOf(chainRootSource, "Mid.java" to chainSources.getValue("Mid.java"))
        val input = bootJar(extraAppSources = appSources, extraLibs = listOf(extJar(chainSources, "ext-chain")))
        val graph = load(input, reachableFromRoot())
        val completeness = assertNotNull(graph.analysisCompleteness())
        assertEquals(AnalysisStatus.ANALYZED, completeness.classStatus("$ext.Owner"), "the root's callee")
        assertEquals(AnalysisStatus.ANALYZED, completeness.classStatus("$appPackage.Mid"), "its application superclass")
        assertEquals(AnalysisStatus.ANALYZED, completeness.classStatus("$ext.Base"), "initialised before Mid, before Owner")
        assertTrue(
            graph.nodes<CallSiteNode>().any {
                it.caller.declaringClass.className == "$ext.Base" && it.caller.name == "<clinit>" && it.callee.name == "getBundle"
            },
            "the initialiser the root's call runs is in the graph"
        )
    }

    @Test
    fun `an inherited instance field read through a subclass shares the declaration's dispatch slot`() {
        val input = bootJar(extraAppSources = aliasSources)
        val graph = load(input, LoaderConfig(includePackages = listOf(appPackage), includeLibraries = true, buildCallGraph = false))
        val completeness = assertNotNull(graph.analysisCompleteness())
        val calls = graph.nodes<CallSiteNode>().toList()
        val get = calls.single { it.caller.name == "run" && it.callee.name == "get" }
        assertEquals(
            setOf("instanceValue", "replacement"),
            calls.filter { it.origin == get.id }.map { it.callee.name }.toSet(),
            "the value Base.install stored through Base.callback is read back through Child.callback"
        )
        assertNull(completeness.callSiteGaps[get.id.value], "both alternatives are known bodies")
    }
}
