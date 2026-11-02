package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.ArtifactRole
import io.johnsonlee.graphite.graph.CallSiteGap
import io.johnsonlee.graphite.graph.analysisStatus
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.DependencyBodyScope
import io.johnsonlee.graphite.input.FoldPlan
import io.johnsonlee.graphite.input.JavaArchiveLayout
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Files
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
/**
 * The dependency body scope and the completeness evidence, on the shape of GraphFold's proof
 * (issue #192): an application whose only reader of a new resource key sits behind a gate the
 * fold rules out, a helper jar that reads bundles for it, and the real `kotlin-stdlib` beside
 * them. The scope decides which library bodies the graph holds; the evidence says, for every
 * callee, whether its body is in the graph, and if not, why.
 */
class DependencyCompletenessTest : DependencyScopeFixture() {

    @Test
    fun `without a dependency scope the helper and the stdlib are external and the application proof stands`() {
        val input = bootJar()
        val graph = load(input, LoaderConfig(includePackages = listOf(appPackage), includeLibraries = true, buildCallGraph = false))
        val completeness = assertNotNull(graph.analysisCompleteness())

        // Only the application was read: a library jar that holds no application package is not loaded.
        assertEquals(mapOf(JavaArchiveLayout.BOOT_INF_CLASSES to ArtifactRole.APPLICATION), completeness.roles())
        assertEquals(listOf(appPackage), completeness.scope.includePackages)
        assertTrue(completeness.scope.includeLibraries)
        assertEquals(emptyList(), completeness.scope.dependencyArtifacts)

        assertEquals(AnalysisStatus.ANALYZED, graph.analysisStatus(method(ab, "enabled", "java.lang.String", returns = "boolean")))
        assertEquals(AnalysisStatus.EXTERNAL, graph.analysisStatus(readMethod), "the helper jar was not loaded")
        assertEquals(AnalysisStatus.EXTERNAL, graph.analysisStatus(trimMethod), "nor the stdlib")
        assertEquals(AnalysisStatus.EXTERNAL, graph.analysisStatus(getBundleMethod), "the JDK is never loaded")
        assertEquals(AnalysisStatus.EXTERNAL, completeness.classStatus(reader))
        assertFalse(graph.hasMethod(readMethod))
        assertTrue(graph.nodes<CallSiteNode>().any { it.caller.name == "viaHelper" && it.callee == readMethod }, "the call, not its body")
        assertEquals(
            mapOf("$depPackage.Source" to listOf("dep-1.0.jar", "other-1.0.jar")),
            completeness.duplicateClasses,
            "every archive of the input is listed, loaded or not"
        )
    }

    @Test
    fun `a declared scope analyses the dependency bodies and the evidence names everything it did not`() {
        val input = bootJar()
        val config = LoaderConfig(
            includePackages = listOf(appPackage),
            includeLibraries = true,
            buildCallGraph = false,
            interproceduralDataflow = true,
            dependencyBodies = DependencyBodyScope.Declared(artifacts = listOf("dep-*.jar"), packages = listOf("kotlin.text.")),
            folding = FoldPlan(listOf(gateOff))
        )
        val graph = load(input, config)
        val completeness = assertNotNull(graph.analysisCompleteness())

        // The scope loaded the helper jar by name and the stdlib for its package; the third jar holds neither.
        val stdlib = completeness.artifacts.keys.single { it.startsWith("kotlin-stdlib") }
        assertEquals(
            mapOf(
                JavaArchiveLayout.BOOT_INF_CLASSES to ArtifactRole.APPLICATION,
                "dep-1.0.jar" to ArtifactRole.LIBRARY,
                stdlib to ArtifactRole.LIBRARY
            ),
            completeness.roles()
        )
        assertTrue(completeness.artifacts.getValue("dep-1.0.jar").multiRelease, "it carries a META-INF/versions copy")
        assertFalse(completeness.artifacts.getValue(JavaArchiveLayout.BOOT_INF_CLASSES).multiRelease)
        assertEquals(listOf("dep-*.jar"), completeness.scope.dependencyArtifacts)
        assertEquals(listOf("kotlin.text."), completeness.scope.dependencyPackages)

        // Every callee has a status, and only the analysed ones promise a complete body.
        assertEquals(AnalysisStatus.ANALYZED, graph.analysisStatus(readMethod))
        // The bytecode spells the stdlib call on the facade; the graph's call site names the part that declares it.
        val trim = graph.nodes<CallSiteNode>().single { it.caller.name == "value" && it.callee.name == "trim" }.callee
        assertTrue(trim.declaringClass.className.startsWith("kotlin.text.StringsKt__"), trim.identity)
        assertEquals(AnalysisStatus.ANALYZED, graph.analysisStatus(trim))
        assertEquals(AnalysisStatus.UNKNOWN_MEMBER, graph.analysisStatus(trimMethod), "the facade declares no such method")
        assertEquals(AnalysisStatus.NATIVE, graph.analysisStatus(method(reader, "nativeSize", returns = "int")))
        val fetch = method("$depPackage.Source", "fetch", "java.lang.String", returns = "java.lang.String")
        assertEquals(AnalysisStatus.ABSTRACT, graph.analysisStatus(fetch))
        assertEquals(AnalysisStatus.EXTERNAL, graph.analysisStatus(getBundleMethod))
        assertEquals(AnalysisStatus.EXCLUDED_BY_SCOPE, completeness.classStatus("kotlin.collections.CollectionsKt"), "types only")
        assertEquals(AnalysisStatus.EXTERNAL, completeness.classStatus("sample.scope.other.Util"), "its jar was not loaded")
        assertEquals(AnalysisStatus.UNKNOWN_MEMBER, graph.analysisStatus(method(reader, "missing")))
        val huge = method("$appPackage.Huge", "count", "int", returns = "int")
        assertEquals(AnalysisStatus.ANALYZED_WITHOUT_CONTROL_FLOW, graph.analysisStatus(huge))
        assertTrue(completeness.methods.keys.none { it.startsWith(example) }, "an analysed method has no record of its own")
        assertEquals("dep-1.0.jar", completeness.classes.getValue(reader).origin)
        assertEquals(JavaArchiveLayout.BOOT_INF_CLASSES, completeness.classes.getValue(example).origin)
        assertEquals(mapOf("$depPackage.Source" to listOf("dep-1.0.jar", "other-1.0.jar")), completeness.duplicateClasses)
        assertEquals("dep-1.0.jar", completeness.classes.getValue("$depPackage.Source").origin, "listing order without an index")

        // The fold removed the only reader of the new key; the surviving read is of the old one.
        val reads = graph.nodes<CallSiteNode>().filter { it.caller.name == "value" && it.callee.name == "getString" }.toList()
        assertEquals(listOf("old"), reads.map { (graph.node(it.arguments.single()) as StringConstant).value })

        // The application's constant reaches the helper's parameter, and from there the bundle loader in its body.
        val bundleName = graph.nodes<ParameterNode>().single { it.method == readMethod && it.index == 0 }
        val messages = graph.nodes<StringConstant>().single { it.value == "messages" }
        assertTrue(
            graph.outgoing(messages.id).any { it is DataFlowEdge && it.kind == DataFlowKind.PARAMETER_PASS && it.to == bundleName.id },
            "the argument is passed to the parameter"
        )
        val loader = graph.nodes<CallSiteNode>().single { it.caller == readMethod && it.callee.name == "getBundle" }
        assertTrue(reaches(graph, bundleName.id, loader.arguments.single()), "the parameter is what the loader reads")
        val returned = graph.nodes<ReturnNode>().single { it.method == readMethod }
        val helperCall = graph.nodes<CallSiteNode>().single { it.caller.name == "viaHelper" && it.callee == readMethod }
        assertTrue(
            graph.outgoing(returned.id).any { it is DataFlowEdge && it.kind == DataFlowKind.RETURN_VALUE },
            "the helper's return reaches the call's result: ${graph.outgoing(returned.id).toList()}"
        )
        assertNotNull(helperCall)

        // A reader hard-coded inside the dependency is discovered although no application argument names it.
        val fixedRead = graph.nodes<CallSiteNode>().single { it.caller.name == "fixed" && it.callee.name == "getString" }
        val messagesFile = graph.nodes<ResourceFileNode>().single { it.path.endsWith("messages.properties") }
        assertTrue(
            graph.outgoing(messagesFile.id).any { it is ResourceEdge && it.kind == ResourceRelation.LOOKUP && it.to == fixedRead.id },
            "the bundle file links to the read in the dependency's body"
        )
        // An application lambda invoked through the dependency dispatches to the lambda's body.
        val callback = graph.nodes<CallSiteNode>()
            .filter { it.caller.name == "once" && it.callee.name.startsWith("lambda\$viaCallback") }.toList()
        assertEquals(1, callback.size, "supplier.get() in the dependency resolves to the application's lambda")
        assertEquals(example, callback.single().callee.declaringClass.className)
        // Past 64 function values the holder saturates: the call resolves to none, and says so.
        val saturated = graph.nodes<CallSiteNode>().single { it.caller.name == "via" && it.callee.name == "get" }
        assertEquals(CallSiteGap.DISPATCH_SATURATED, completeness.callSiteGaps[saturated.id.value])
        assertTrue(graph.nodes<CallSiteNode>().none { it.caller.name == "via" && it.callee.name.startsWith("lambda\$") })
    }

    @Test
    fun `a reachable scope reads the library classes the application reaches and names the rest`() {
        val input = bootJar()
        val config = LoaderConfig(
            includePackages = listOf(appPackage),
            includeLibraries = true,
            buildCallGraph = false,
            dependencyBodies = DependencyBodyScope.Reachable()
        )
        val graph = load(input, config)
        val completeness = assertNotNull(graph.analysisCompleteness())

        // Every library archive is loaded, so an unreached class is excluded, not external.
        assertEquals(setOf(ArtifactRole.APPLICATION, ArtifactRole.LIBRARY), completeness.roles().values.toSet())
        assertTrue(completeness.artifacts.containsKey("other-1.0.jar"), "loaded although nothing of it is in scope")
        val stdlib = completeness.artifacts.keys.single { it.startsWith("kotlin-stdlib") }
        assertEquals(AnalysisStatus.EXCLUDED_BY_SCOPE, completeness.classStatus("sample.scope.other.Util"))
        assertEquals(DependencyBodyScope.Reachable.DEFAULT_MAX_CLASSES, completeness.scope.dependencyReachable)

        // Reached through calls: the helper, the interface the application calls through, the stdlib part.
        assertEquals(AnalysisStatus.ANALYZED, graph.analysisStatus(readMethod))
        assertEquals(AnalysisStatus.ANALYZED, completeness.classStatus("$depPackage.Source"))
        val trim = graph.nodes<CallSiteNode>().single { it.caller.name == "value" && it.callee.name == "trim" }.callee
        assertEquals(AnalysisStatus.ANALYZED, graph.analysisStatus(trim))
        assertEquals(AnalysisStatus.EXCLUDED_BY_SCOPE, completeness.classStatus("kotlin.collections.CollectionsKt"), "unreached")
        assertTrue(
            graph.nodes<CallSiteNode>().any { it.caller == readMethod && it.callee.name == "getBundle" },
            "the reached helper's body is in the graph"
        )
        val stdlibClasses = completeness.classes.filterValues { it.origin == stdlib }
        val unreached = stdlibClasses.count { it.value.status == AnalysisStatus.EXCLUDED_BY_SCOPE }
        assertTrue(
            unreached > 0 && unreached < stdlibClasses.size,
            "the stdlib is read where reached: ${stdlibClasses.size - unreached} of ${stdlibClasses.size}"
        )

        // Calls on function values that resolve to nothing are gaps, not calls with no effect.
        val unknown = graph.nodes<CallSiteNode>().single { it.caller.name == "viaUnknown" && it.callee.name == "get" }
        assertEquals(CallSiteGap.DISPATCH_UNRESOLVED, completeness.callSiteGaps[unknown.id.value], "no caller passes a supplier")
        val saturated = graph.nodes<CallSiteNode>().single { it.caller.name == "via" && it.callee.name == "get" }
        assertEquals(CallSiteGap.DISPATCH_SATURATED, completeness.callSiteGaps[saturated.id.value], "66 lambdas reach the parameter")
        // The reached stdlib bodies call function values of their own; every gap names a call site in the graph.
        val gapped = completeness.callSiteGaps.keys.map { NodeId(it) }
        assertTrue(gapped.all { graph.node(it) is CallSiteNode }, "a gap is a call site")
        assertTrue(gapped.size > 2, "the stdlib's own calls on function values are gaps too: ${gapped.size}")
    }

    @Test
    fun `a reachable scope expands from the roots the consumer names`() {
        val input = bootJar()
        val config = LoaderConfig(
            includePackages = listOf(appPackage),
            includeLibraries = true,
            buildCallGraph = false,
            dependencyBodies = DependencyBodyScope.Reachable(roots = listOf(ab))
        )
        val graph = load(input, config)
        val completeness = assertNotNull(graph.analysisCompleteness())
        assertEquals(listOf(ab), completeness.scope.dependencyRoots)
        // Ab.enabled calls String.length only, on the JDK: nothing in a library is reached from that root.
        assertEquals(AnalysisStatus.EXCLUDED_BY_SCOPE, graph.analysisStatus(readMethod), "reached from Example, which is not a root")
        assertEquals(AnalysisStatus.ANALYZED, completeness.classStatus(example), "an application class outside the roots is still read")
        assertTrue(completeness.classes.values.none { it.status == AnalysisStatus.NOT_EXPANDED })
        assertEquals(
            completeness.classes.count { it.value.origin == JavaArchiveLayout.BOOT_INF_CLASSES },
            completeness.classes.count { it.value.status == AnalysisStatus.ANALYZED },
            "only the application's own classes were read"
        )
    }

    @Test
    fun `a Spring Boot class-path index orders duplicate definitions as the runtime loads them`() {
        val input = bootJar(classpathIndex = listOf("other-1.0.jar", "dep-1.0.jar"))
        val config = LoaderConfig(
            includePackages = listOf(appPackage),
            includeLibraries = true,
            buildCallGraph = false,
            dependencyBodies = DependencyBodyScope.Declared(artifacts = listOf("dep-*.jar", "other-*.jar"))
        )
        val graph = load(input, config)
        val completeness = assertNotNull(graph.analysisCompleteness())
        assertEquals(
            mapOf("$depPackage.Source" to listOf("other-1.0.jar", "dep-1.0.jar")),
            completeness.duplicateClasses,
            "the archive's own order, not the listing order, says which definition runs"
        )
        assertEquals("other-1.0.jar", completeness.classes.getValue("$depPackage.Source").origin, "and the view holds that definition")
        assertEquals(
            listOf(JavaArchiveLayout.BOOT_INF_CLASSES, "other-1.0.jar", "dep-1.0.jar"),
            completeness.artifacts.keys.toList(),
            "the sources are read in the launcher's order"
        )
    }

    @Test
    fun `a reachable scope never reads an application class the configuration excluded`() {
        val input = bootJar()
        val config = LoaderConfig(
            includePackages = listOf(appPackage),
            excludePackages = listOf(ab),
            includeLibraries = true,
            buildCallGraph = false,
            dependencyBodies = DependencyBodyScope.Reachable()
        )
        val graph = load(input, config)
        val completeness = assertNotNull(graph.analysisCompleteness())
        assertEquals(AnalysisStatus.EXCLUDED_BY_SCOPE, completeness.classStatus(ab), "called by Example, excluded all the same")
        assertFalse(graph.hasMethod(method(ab, "enabled", "java.lang.String", returns = "boolean")))
        assertEquals(AnalysisStatus.ANALYZED, graph.analysisStatus(readMethod), "the libraries are still reached")
    }

    @Test
    fun `a call on a function value is a gap when cross-method dispatch is not tracked`() {
        val appClasses = compile(appSources, "untracked", classpath = listOf(depJar(), stdlibJar()))
        val root = Files.createTempDirectory("scope-untracked").also(temporaryRoots::add)
        val single = jar(root.resolve("app.jar"), appClasses, "sample/scope/app/")
        val config = LoaderConfig(
            includePackages = listOf(appPackage),
            buildCallGraph = false,
            trackCrossMethodFunctionalDispatch = false
        )
        val graph = load(single, config)
        val completeness = assertNotNull(graph.analysisCompleteness())
        val unknown = graph.nodes<CallSiteNode>().single { it.caller.name == "viaUnknown" && it.callee.name == "get" }
        assertEquals(CallSiteGap.DISPATCH_UNRESOLVED, completeness.callSiteGaps[unknown.id.value], "nothing resolves it in its own method")
    }

    @Test
    fun `a reachable scope stops at its budget and marks what it reached past it`() {
        val input = bootJar()
        val config = LoaderConfig(
            includePackages = listOf(appPackage),
            includeLibraries = true,
            buildCallGraph = false,
            dependencyBodies = DependencyBodyScope.Reachable(1)
        )
        val graph = load(input, config)
        val completeness = assertNotNull(graph.analysisCompleteness())
        val reached = completeness.classes.filterValues { it.status == AnalysisStatus.NOT_EXPANDED }.keys
        val read = completeness.classes
            .filterValues { it.status == AnalysisStatus.ANALYZED && it.origin != JavaArchiveLayout.BOOT_INF_CLASSES }.keys
        assertEquals(1, read.size, "one dependency class within the budget: $read")
        assertTrue(reached.isNotEmpty(), "the rest of the frontier is reached, not read")
        assertTrue(reader in read || reader in reached)
        assertEquals(AnalysisStatus.EXCLUDED_BY_SCOPE, completeness.classStatus("kotlin.collections.CollectionsKt"), "never reached at all")
        assertEquals(1, completeness.scope.dependencyReachable)
    }

    @Test
    fun `a directory input names its jars by path and the scope matches their file names`() {
        val root = Files.createTempDirectory("scope-dir").also(temporaryRoots::add)
        val appClasses = compile(appSources, "app", classpath = listOf(depJar(), stdlibJar()))
        val lib = Files.createDirectories(root.resolve("lib"))
        jar(root.resolve("app.jar"), appClasses, "sample/scope/app/")
        Files.copy(depJar(), lib.resolve("dep-1.0.jar"))
        val config = LoaderConfig(
            includePackages = listOf(appPackage),
            includeLibraries = true,
            buildCallGraph = false,
            dependencyBodies = DependencyBodyScope.Declared(artifacts = listOf("dep-*.jar"))
        )
        val graph = load(root, config)
        val completeness = assertNotNull(graph.analysisCompleteness())
        assertEquals(mapOf("app.jar" to ArtifactRole.APPLICATION, "lib/dep-1.0.jar" to ArtifactRole.LIBRARY), completeness.roles())
        assertEquals(AnalysisStatus.ANALYZED, graph.analysisStatus(readMethod))
        assertEquals("lib/dep-1.0.jar", completeness.classes.getValue(reader).origin)
    }

    @Test
    fun `a single jar is the application, and a build of it carries evidence for every class`() {
        val appClasses = compile(appSources, "single", classpath = listOf(depJar(), stdlibJar()))
        val root = Files.createTempDirectory("scope-single").also(temporaryRoots::add)
        val single = jar(root.resolve("app-1.0.jar"), appClasses, "sample/scope/app/")
        val graph = load(single, LoaderConfig(includePackages = listOf("$appPackage.Example"), buildCallGraph = false))
        val completeness = assertNotNull(graph.analysisCompleteness())
        assertEquals(mapOf("app-1.0.jar" to ArtifactRole.APPLICATION), completeness.roles())
        assertEquals(AnalysisStatus.ANALYZED, completeness.classStatus(example))
        assertEquals(AnalysisStatus.EXCLUDED_BY_SCOPE, completeness.classStatus(ab), "in the jar, outside the package filter")
        assertEquals(AnalysisStatus.EXCLUDED_BY_SCOPE, graph.analysisStatus(method(ab, "enabled", "java.lang.String", returns = "boolean")))
        assertEquals("app-1.0.jar", completeness.classes.getValue(ab).origin)
        assertTrue(completeness.duplicateClasses.isEmpty())
        assertNull(graph.analysisStatus(readMethod).takeIf { it != AnalysisStatus.EXTERNAL })
    }
}
