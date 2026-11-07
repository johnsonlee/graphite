package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.ArtifactRole
import io.johnsonlee.graphite.graph.CallSiteGap
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.CallSiteKey
import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.ConstantPattern
import io.johnsonlee.graphite.input.DependencyBodyScope
import io.johnsonlee.graphite.input.FoldPlan
import io.johnsonlee.graphite.input.FoldReport
import io.johnsonlee.graphite.input.FoldSites
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
import sootup.core.jimple.common.constant.StringConstant
import sootup.core.jimple.common.expr.AbstractInvokeExpr
import sootup.core.model.Body
import sootup.core.model.MethodModifier
import sootup.core.model.SootClass
import sootup.core.model.SourceType

/** Semantic assertions use the actual body/CFG; timing these small fixtures is not performance evidence. */
class FoldedBodyObservationTest : FoldedBodyObservationFixture() {
    @Test
    fun `declared observation preserves folded graph facts and reports explicit body availability`() {
        val input = bootJar()
        var normalReport: FoldReport? = null
        val config = LoaderConfig(includePackages = listOf(appPackage), includeLibraries = true,
            dependencyBodies = DependencyBodyScope.Declared(artifacts = listOf("dep-*.jar")),
            folding = FoldPlan(listOf(gateOff)) { normalReport = it })
        val normal = JavaProjectLoader(config, false).load(input)
        val expectedReport = normalReport
        val (observed, events) = observe(input, config)

        assertEquals(graphFacts(normal), graphFacts(observed.graph))
        assertEquals(assertNotNull(normal.analysisCompleteness()).copy(callSiteGaps = emptyMap()),
            observed.completeness.copy(callSiteGaps = emptyMap()))
        assertEquals(expectedReport, observed.foldReport)
        assertEquals(observed.graph.methods(MethodPattern()).map { it.identity }.toSet(), events.keys)
        assertEquals(events.keys, observed.observations.keys)
        val value = events.getValue(method(example, "value", returns = "java.lang.String").identity)
        val body = assertNotNull(value.snapshot).body
        assertEquals(listOf("old"), calls(body).filter { it.methodSignature.name == "getString" }
            .map { (it.args.single() as StringConstant).value })
        assertFalse(calls(body).any { it.methodSignature.name == "enabled" })
        assertEquals(mapOf(0 to 1), assertNotNull(value.evidence.folding).foldedCalls)
        assertTrue(assertNotNull(value.evidence.folding).statementsBefore!! > body.stmts.size)
        assertEquals(body.stmts.size, assertNotNull(value.evidence.folding).statementsAfter)
        assertEquals(JavaArchiveLayout.BOOT_INF_CLASSES, value.evidence.origin)
        assertEquals(ArtifactRole.APPLICATION, value.evidence.role)
        assertEquals(true, value.evidence.application)

        val dependency = events.getValue(readMethod.identity)
        assertEquals("dep-1.0.jar", dependency.evidence.origin)
        assertEquals(ArtifactRole.LIBRARY, dependency.evidence.role)
        assertEquals(false, dependency.evidence.application)
        assertEquals(AnalysisStatus.ANALYZED, dependency.evidence.status)
        assertEquals(listOf("getBundle", "getString"), calls(assertNotNull(dependency.snapshot).body).map { it.methodSignature.name })
        val native = events.getValue(method(reader, "nativeSize", returns = "int").identity)
        assertEquals(AnalysisStatus.NATIVE, native.evidence.status)
        assertTrue(MethodModifier.NATIVE in assertNotNull(native.evidence.modifiers))
        assertNull(native.snapshot)
        val abstract = events.getValue(method("$depPackage.Source", "fetch", "java.lang.String", returns = "java.lang.String").identity)
        assertEquals(AnalysisStatus.ABSTRACT, abstract.evidence.status)
        assertNull(abstract.snapshot)
        val huge = events.getValue(method("$appPackage.Huge", "count", "int", returns = "int").identity)
        assertEquals(AnalysisStatus.ANALYZED_WITHOUT_CONTROL_FLOW, huge.evidence.status)
        assertNotNull(huge.snapshot)
        assertTrue(assertNotNull(huge.snapshot).body.stmts.any { it.isJIfStmt })
    }

    @Test
    fun `reachable observation retains original dispatch gaps and does not expand excluded bodies`() {
        val input = bootJar()
        val config = LoaderConfig(includePackages = listOf(appPackage), includeLibraries = true,
            dependencyBodies = DependencyBodyScope.Reachable())
        val (observed, events) = observe(input, config)
        val normal = JavaProjectLoader(config, false).load(input)
        assertEquals(assertNotNull(normal.analysisCompleteness()).copy(callSiteGaps = emptyMap()),
            observed.completeness.copy(callSiteGaps = emptyMap()))
        assertEquals(graphFacts(normal), graphFacts(observed.graph))
        assertNotNull(events[readMethod.identity]?.snapshot)
        assertFalse(events.values.any { it.evidence.method.owner == "kotlin.collections.CollectionsKt" })
        val calls = observed.graph.nodes<CallSiteNode>().toList()
        val unknown = calls.single { it.caller.name == "viaUnknown" && it.callee.name == "get" }
        val saturated = calls.single { it.caller.name == "via" && it.callee.name == "get" }
        assertEquals(CallSiteGap.DISPATCH_UNRESOLVED, observed.completeness.callSiteGaps[unknown.id.value])
        assertEquals(CallSiteGap.DISPATCH_SATURATED, observed.completeness.callSiteGaps[saturated.id.value])
        assertNotNull(events[saturated.caller.identity]?.snapshot, "capture success cannot erase a dispatch gap")
        assertTrue(calls.any { it.caller.name == "once" && it.callee.name.startsWith("lambda\$viaCallback") })

        val (bounded, boundedEvents) = observe(input, config.copy(dependencyBodies = DependencyBodyScope.Reachable(maxClasses = 1)))
        val unexpanded = bounded.completeness.classes.filterValues { it.status == AnalysisStatus.NOT_EXPANDED }.keys
        assertTrue(unexpanded.isNotEmpty())
        assertTrue(boundedEvents.values.none { it.evidence.method.owner in unexpanded })
    }

    @Test
    fun `folding inside a dependency uses the same observed instructions for match and selected rules`() {
        val classes = compile(depSources + mapOf(
            "Gate.java" to "package sample.scope.dep; public class Gate { public static boolean enabled() { return true; } }",
            "Choice.java" to """
                package sample.scope.dep;
                public class Choice {
                    public static String read() {
                        if (Gate.enabled()) return java.util.ResourceBundle.getBundle("messages").getString("new");
                        return java.util.ResourceBundle.getBundle("messages").getString("old");
                    }
                }
            """.trimIndent()
        ), "observer-dependency-fold")
        val root = Files.createTempDirectory("observer-fold-dependency").also(temporaryRoots::add)
        depJar = jar(root.resolve("dep-1.0.jar"), classes, "sample/scope/dep/")
        val input = bootJar()
        val config = LoaderConfig(includePackages = listOf(appPackage), includeLibraries = true,
            dependencyBodies = DependencyBodyScope.Declared(artifacts = listOf("dep-*.jar")))
        val normal = JavaProjectLoader(config, false).load(input)
        val selected = normal.nodes<CallSiteNode>().single {
            it.caller.declaringClass.className == "$depPackage.Choice" && it.callee.name == "enabled"
        }
        val key = CallSiteKey(selected.caller.signature, selected.caller.descriptor,
            selected.callee.signature, selected.callee.descriptor, assertNotNull(selected.ordinal))
        val match = ConstantFold(mapOf("callee_class" to "$depPackage.Gate", "callee_name" to "enabled"),
            value = ConstantPattern.parse(false, "value"))
        val selection = FoldSites("MATCH (cs:CallSite {callee_name:'enabled'}) RETURN cs", setOf(key),
            ConstantPattern.parse(false, "value"))
        for (rule in listOf(match, selection)) {
            val (observed, events) = observe(input, config.copy(folding = FoldPlan(listOf(rule))))
            val event = events.getValue(selected.caller.identity)
            val body = assertNotNull(event.snapshot).body
            assertFalse(calls(body).any { it.methodSignature.name == "enabled" })
            assertEquals(listOf("old"), calls(body).filter { it.methodSignature.name == "getString" }
                .map { (it.args.single() as StringConstant).value })
            assertEquals(mapOf(0 to 1), assertNotNull(event.evidence.folding).foldedCalls)
            assertEquals(1, assertNotNull(observed.foldReport).outcomes.single().matched)
            val live = observed.graph.nodes<CallSiteNode>().single {
                it.caller.identity == selected.caller.identity && it.callee.name == "getString"
            }
            assertEquals("old", (observed.graph.node(live.arguments.single()) as io.johnsonlee.graphite.core.StringConstant).value)
            assertEquals(AnalysisStatus.ANALYZED, event.evidence.status)
        }
    }

    @Test
    fun `initializers overloads bridges anonymous and lambda methods have unambiguous identities`() {
        val input = smallInput(mapOf(
            "Parent.java" to """
                package sample.scope.dep;
                public class Parent<T> {
                    public static final String BASE_KEY = new String("base");
                    public T value() { return null; }
                }
            """.trimIndent(),
            "HasDefault.java" to """
                package sample.scope.dep;
                public interface HasDefault {
                    int INITIALIZED = initialize();
                    static int initialize() { return 1; }
                    default int marker() { return 1; }
                }
            """.trimIndent(),
            "Child.java" to """
                package sample.scope.dep;
                public class Child extends Parent<String> implements HasDefault {
                    public static final String KEY = new String("live");
                    public String value() { return "child"; }
                    public int pick(int n) { return n; }
                    public String pick(String s) { return s; }
                }
            """.trimIndent(),
            "App.java" to """
                package sample.scope.app;
                public class App {
                    public String run() { return sample.scope.dep.Child.KEY + sample.scope.dep.Child.BASE_KEY; }
                    public java.util.function.Supplier<String> lambda() { return () -> "lambda"; }
                    public java.util.function.Supplier<String> anonymous() {
                        return new java.util.function.Supplier<String>() { public String get() { return "anonymous"; } };
                    }
                }
            """.trimIndent()
        ))
        val config = LoaderConfig(includePackages = listOf(appPackage), includeLibraries = true,
            dependencyBodies = DependencyBodyScope.Reachable())
        val (observed, events) = observe(input, config)
        val children = events.values.filter { it.evidence.method.owner == "$depPackage.Child" }
        assertEquals(setOf("()Ljava/lang/String;", "()Ljava/lang/Object;"),
            children.filter { it.evidence.method.name == "value" }.map { it.evidence.method.descriptor }.toSet())
        val bridge = children.single { it.evidence.method.name == "value" && it.evidence.method.descriptor == "()Ljava/lang/Object;" }
        assertTrue(MethodModifier.BRIDGE in assertNotNull(bridge.evidence.modifiers))
        assertEquals("value", calls(assertNotNull(bridge.snapshot).body).single().methodSignature.name)
        assertEquals(setOf("(I)I", "(Ljava/lang/String;)Ljava/lang/String;"),
            children.filter { it.evidence.method.name == "pick" }.map { it.evidence.method.descriptor }.toSet())
        assertNotNull(events["$depPackage.Child.<clinit>()V"]?.snapshot)
        assertNotNull(events["$depPackage.Parent.<clinit>()V"]?.snapshot, "the inherited field's declaring owner is observed")
        val defaultInitializer = assertNotNull(events["$depPackage.HasDefault.<clinit>()V"]?.snapshot).body
        assertEquals(listOf("initialize"), calls(defaultInitializer).map { it.methodSignature.name })
        assertTrue(events.values.any { it.evidence.method.owner == "$appPackage.App" && it.evidence.method.name.startsWith("lambda\$") })
        assertNotNull(events["$appPackage.App\$1.get()Ljava/lang/String;"]?.snapshot)
        assertEquals(AnalysisStatus.ANALYZED, observed.completeness.classStatus("$depPackage.Child"))
    }

    @Test
    fun `body delivered during late reachable expansion still retains final partial dispatch evidence`() {
        val input = smallInput(mapOf(
            "Late.java" to """
                package sample.scope.dep;
                import java.util.function.Supplier;
                public class Late {
                    public static Supplier<String> external;
                    public static String read() {
                        Supplier<String> supplier = System.nanoTime() > 0 ? () -> "late" : external;
                        return supplier.get();
                    }
                }
            """.trimIndent(),
            "CallbackRoot.java" to """
                package sample.scope.app;
                public class CallbackRoot {
                    public String run() { Registry.callback.get(); return Registry.selected.get(); }
                }
            """.trimIndent(),
            "Registry.java" to """
                package sample.scope.app;
                import java.util.function.Supplier;
                public class Registry {
                    public static Supplier<String> callback;
                    public static Supplier<String> selected = () -> "original";
                    public static void install() { callback = sample.scope.dep.Late::read; }
                }
            """.trimIndent()
        ))
        val (result, events) = observe(input, LoaderConfig(includePackages = listOf(appPackage), includeLibraries = true,
            dependencyBodies = DependencyBodyScope.Reachable(roots = listOf("$appPackage.CallbackRoot"))))
        val captured = events.getValue("$depPackage.Late.read()Ljava/lang/String;")
        assertNotNull(captured.snapshot)
        assertEquals(AnalysisStatus.ANALYZED, captured.evidence.status)
        assertEquals(false, captured.evidence.application)
        val dispatch = result.graph.nodes<CallSiteNode>().single {
            it.caller.identity == captured.evidence.method.identity && it.callee.name == "get"
        }
        assertEquals(CallSiteGap.DISPATCH_PARTIAL, result.completeness.callSiteGaps[dispatch.id.value])
        assertTrue(result.graph.nodes<CallSiteNode>().any {
            it.caller.identity == captured.evidence.method.identity && it.callee.name.startsWith("lambda\$")
        })
    }

    @Test
    fun `selected duplicate origin and multi release evidence agree with delivered declarations`() {
        val input = bootJar(classpathIndex = listOf("other-1.0.jar", "dep-1.0.jar"))
        val (observed, events) = observe(input, LoaderConfig(includePackages = listOf(appPackage), includeLibraries = true,
            dependencyBodies = DependencyBodyScope.Declared(artifacts = listOf("dep-*.jar", "other-*.jar"))))
        val source = events.getValue(method("$depPackage.Source", "fetch", "java.lang.String", returns = "java.lang.String").identity)
        assertEquals("other-1.0.jar", source.evidence.origin)
        assertEquals(source.evidence.origin, observed.completeness.classes.getValue("$depPackage.Source").origin)
        assertEquals(listOf("other-1.0.jar", "dep-1.0.jar"), observed.completeness.duplicateClasses.getValue("$depPackage.Source"))
        assertEquals(ArtifactRole.LIBRARY, source.evidence.role)
        assertEquals(AnalysisStatus.ABSTRACT, source.evidence.status)
        assertNull(source.snapshot)
        assertTrue(observed.completeness.artifacts.getValue("dep-1.0.jar").multiRelease)
        assertEquals("dep-1.0.jar", events.getValue(readMethod.identity).evidence.origin)
    }

    @Test
    fun `duplicate class bodies come only from the actual first selected definition`() {
        val baseInput = smallInput()
        Files.move(baseInput.resolve("dep.jar"), baseInput.resolve("dep-a.jar"))
        val headInput = smallInput(mapOf(
            "App.java" to "package sample.scope.app; public class App { public String run() { return sample.scope.dep.Helper.read(); } }",
            "Helper.java" to "package sample.scope.dep; public class Helper { public static String read() { return \"second\"; } }"
        ))
        Files.move(headInput.resolve("dep.jar"), headInput.resolve("dep-a.jar"))
        Files.copy(headInput.resolve("dep-a.jar"), baseInput.resolve("dep-b.jar"))
        Files.copy(baseInput.resolve("dep-a.jar"), headInput.resolve("dep-b.jar"))
        val config = LoaderConfig(includePackages = listOf(appPackage), includeLibraries = true,
            dependencyBodies = DependencyBodyScope.Declared(artifacts = listOf("dep-*.jar")))
        for ((input, expected) in listOf(baseInput to "dependency", headInput to "second")) {
            val (result, events) = observe(input, config)
            val read = events.getValue("$depPackage.Helper.read()Ljava/lang/String;")
            val body = assertNotNull(read.snapshot).body
            assertEquals(listOf(expected), body.stmts.flatMap { it.uses }.filterIsInstance<StringConstant>().map { it.value })
            assertEquals("dep-a.jar", read.evidence.origin)
            assertEquals(listOf("dep-a.jar", "dep-b.jar"), result.completeness.duplicateClasses.getValue("$depPackage.Helper"))
            assertEquals("dep-a.jar", result.completeness.classes.getValue("$depPackage.Helper").origin)
            assertEquals(setOf("dep-a.jar"), events.values.filter { it.evidence.method.owner == "$depPackage.Helper" }
                .map { it.evidence.origin }.toSet())
        }
    }

    @Test
    fun `library observation never runs domain extensions and rematerialization does not redeliver`() {
        val input = smallInput()
        val config = LoaderConfig(includePackages = listOf(appPackage), includeLibraries = true, buildCallGraph = true,
            dependencyBodies = DependencyBodyScope.Declared(artifacts = listOf("dep.jar")))
        val appLocation = ParsedClassLocation(input.resolve("app.jar"), SourceType.Application, emptyList())
        val depLocation = ParsedClassLocation(input.resolve("dep.jar"), SourceType.Library, emptyList())
        try {
            val visited = mutableListOf<String>()
            val extension = object : GraphiteExtension {
                override fun visit(sootClass: SootClass, context: GraphiteContext) {
                    visited += sootClass.name
                    // Domain consumers may rematerialize streamed methods. This must not invoke the observer again.
                    repeat(2) { sootClass.methods.filter { it.hasBody() }.forEach { assertTrue(it.body.stmts.isNotEmpty()) } }
                }
            }
            val events = linkedMapOf<String, FoldedMethodObservation>()
            val delivery = FoldedBodyDelivery(FoldedBodyObserver { event ->
                assertNull(events.put(event.evidence.method.identity, event), "exactly once despite extension/callgraph materialization")
            })
            val adapter = SootUpAdapter(createJavaView(listOf(appLocation, depLocation)), config,
                extensions = listOf(extension), inputLocationSources = mapOf(appLocation to "app.jar", depLocation to "dep.jar"),
                artifactRoles = mapOf("app.jar" to ArtifactRole.APPLICATION, "dep.jar" to ArtifactRole.LIBRARY))
            adapter.bodyDelivery = delivery
            val result = delivery.finish(adapter.buildGraph(), null)
            assertEquals(listOf("$appPackage.App"), visited)
            assertEquals(false, events.getValue("$depPackage.Helper.read()Ljava/lang/String;").evidence.application)
            assertEquals(setOf("$appPackage.App.<init>()V", "$appPackage.App.run()Ljava/lang/String;",
                "$depPackage.Helper.<init>()V", "$depPackage.Helper.read()Ljava/lang/String;"), events.keys)
            assertEquals(events.keys, result.observations.keys)
        } finally {
            appLocation.close()
            depLocation.close()
        }
    }

    @Test
    fun `real Kotlin application captures admitted stdlib bodies without promoting them to applications`() {
        val input = Files.createTempDirectory("observed-kotlin-app").also(temporaryRoots::add)
        Files.copy(Path.of(System.getProperty("frontend.correctness.kotlin")), input.resolve("app.jar"))
        Files.copy(stdlibJar(), input.resolve(stdlibJar().name))
        val (observed, events) = observe(input, LoaderConfig(includePackages = listOf("fixture.frontend.kotlin"),
            includeLibraries = true, dependencyBodies = DependencyBodyScope.Declared(packages = listOf("kotlin.jvm.internal."))))
        val application = events.getValue("fixture.frontend.kotlin.KotlinCases.safeCall(Ljava/lang/String;)Ljava/lang/String;")
        assertEquals(true, application.evidence.application)
        assertNotNull(application.snapshot)
        val intrinsics = events.values.filter { it.evidence.method.owner == "kotlin.jvm.internal.Intrinsics" }
        assertTrue(intrinsics.isNotEmpty())
        val check = intrinsics.single { it.evidence.method.name == "checkNotNullParameter" }
        assertEquals(false, check.evidence.application)
        assertEquals(ArtifactRole.LIBRARY, check.evidence.role)
        assertEquals(stdlibJar().name, check.evidence.origin)
        assertNotNull(check.snapshot)
        assertEquals(AnalysisStatus.EXCLUDED_BY_SCOPE, observed.completeness.classStatus("kotlin.collections.CollectionsKt"))
    }
}

abstract class FoldedBodyObservationFixture : DependencyScopeFixture() {
    protected fun observe(input: Path, config: LoaderConfig): Pair<ObservedJavaLoad, Map<String, FoldedMethodObservation>> {
        val events = linkedMapOf<String, FoldedMethodObservation>()
        val result = JavaProjectLoader(config, false).loadObserved(input) { event ->
            assertNull(events.put(event.evidence.method.identity, event), "a method is delivered exactly once")
        }
        return result to events
    }

    protected fun calls(body: Body): List<AbstractInvokeExpr> = body.stmts.mapNotNull { stmt ->
        if (stmt.isInvokableStmt) stmt.asInvokableStmt().invokeExpr.orElse(null) else null
    }

    protected fun graphFacts(graph: Graph): List<String> {
        // NodeIds are process-global; compare the same allocation order and every reference
        // after renumbering, rather than resetting global state (which would break concurrent loads).
        val ids = graph.nodes<Node>().map { it.id.value }.sorted().toList()
            .mapIndexed { index, id -> id to index }.toMap()
        fun normalized(value: Any): String = Regex("node#([0-9]+)").replace(value.toString()) {
            "node#${ids.getValue(it.groupValues[1].toInt())}"
        }
        return graph.methods(MethodPattern()).map { "method:${it.identity}" }.toList().sorted() +
            graph.nodes<Node>().map { "node:${normalized(it)}" }.toList().sorted() +
            graph.nodes<Node>().flatMap { graph.outgoing(it.id) }.map { "edge:${normalized(it)}" }.toList().sorted() +
            graph.branchScopes().map { branch ->
                "branch:${normalized(branch.conditionNodeId)}:${branch.method.identity}:${normalized(branch.comparison)}:" +
                    "true=${branch.trueBranchNodeIds.map { ids.getValue(it) }.sorted()}:" +
                    "false=${branch.falseBranchNodeIds.map { ids.getValue(it) }.sorted()}:" +
                    "definitions=${normalized(branch.trueDefinitions)}/${normalized(branch.falseDefinitions)}"
            }.toList().sorted() +
            assertNotNull(graph.analysisCompleteness()).callSiteGaps.map { (id, gap) -> "gap:${ids.getValue(id)}:$gap" }.sorted()
    }

    protected fun smallInput(sources: Map<String, String> = mapOf(
        "App.java" to """
            package sample.scope.app;
            public class App { public String run() { return sample.scope.dep.Helper.read(); } }
        """.trimIndent(),
        "Helper.java" to """
            package sample.scope.dep;
            public class Helper { public static String read() { return "dependency"; } }
        """.trimIndent()
    )): Path {
        val classes = compile(sources, "observed-small")
        val input = Files.createTempDirectory("observed-small-input").also(temporaryRoots::add)
        jar(input.resolve("app.jar"), classes, "sample/scope/app/")
        jar(input.resolve("dep.jar"), classes, "sample/scope/dep/")
        return input
    }
}
