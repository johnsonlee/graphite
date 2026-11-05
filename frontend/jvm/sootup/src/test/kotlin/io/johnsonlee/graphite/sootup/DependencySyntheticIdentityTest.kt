package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.DependencyBodyScope
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DependencySyntheticIdentityTest : DependencyScopeFixture() {
    @Test
    fun `callback-only dependency methods retain their synthetic fingerprints`() {
        val classes = compile(mapOf("Late.java" to """
            package sample.scope.late;
            import java.util.function.Supplier;
            public class Late {
                public static String read() {
                    Supplier<String> supplier = () -> "late";
                    return supplier.get();
                }
            }
        """.trimIndent()), "late-synthetic")
        val dir = Files.createTempDirectory("scope-late-synthetic").also(temporaryRoots::add)
        val dependency = jar(dir.resolve("late.jar"), classes, "sample/scope/late/")
        val input = bootJar(extraLibs = listOf(dependency), extraAppSources = mapOf(
            "CallbackRoot.java" to """
                package sample.scope.app;
                public class CallbackRoot {
                    public String run() { return Registry.callback.get(); }
                }
            """.trimIndent(),
            "Registry.java" to """
                package sample.scope.app;
                import java.util.function.Supplier;
                public class Registry {
                    public static Supplier<String> callback;
                    public static void install() { callback = sample.scope.late.Late::read; }
                }
            """.trimIndent()
        ))
        val config = LoaderConfig(includePackages = listOf(appPackage), includeLibraries = true,
            buildCallGraph = false, dependencyBodies = DependencyBodyScope.Reachable(roots = listOf("$appPackage.CallbackRoot")))
        val graph = load(input, config)
        val calls = graph.nodes<CallSiteNode>().toList()
        assertTrue(
            calls.any {
                it.caller.name == "run" && it.callee.declaringClass.className == "sample.scope.late.Late" && it.origin != null
            },
            "the root reaches Late.read through resolved field dispatch"
        )
        assertEquals(
            AnalysisStatus.ANALYZED,
            assertNotNull(graph.analysisCompleteness()).classStatus("sample.scope.late.Late")
        )
        val synthetic = calls.first {
            it.caller.declaringClass.className == "sample.scope.late.Late" && it.callee.name.startsWith("lambda\$")
        }.callee
        val eager = load(input, config.copy(dependencyBodies = DependencyBodyScope.Declared(artifacts = listOf("late.jar"))))
        val expected = assertNotNull(
            eager.syntheticIdentity(synthetic.signature),
            "the same synthetic body has a fingerprint when read in pass 2"
        )
        assertEquals(
            expected, graph.syntheticIdentity(synthetic.signature),
            "post-dispatch expansion must finalize the same synthetic identity"
        )
    }
}
