package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.AnalysisCompleteness
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.ArtifactRole
import io.johnsonlee.graphite.graph.CallSiteGap
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.analysisStatus
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.DependencyBodyScope
import io.johnsonlee.graphite.input.FoldPlan
import io.johnsonlee.graphite.input.JavaArchiveLayout
import io.johnsonlee.graphite.input.LoaderConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.tools.ToolProvider
import kotlin.io.path.name
import kotlin.test.AfterTest
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
class DependencyCompletenessTest {

    private val temporaryRoots = mutableListOf<Path>()

    @AfterTest
    fun cleanUp() {
        temporaryRoots.forEach { it.toFile().deleteRecursively() }
        File(System.getProperty("java.io.tmpdir")).listFiles()
            ?.filter { it.name.startsWith("graphite-springboot") }
            ?.forEach { it.deleteRecursively() }
    }

    private val appPackage = "sample.scope.app"
    private val depPackage = "sample.scope.dep"
    private val example = "$appPackage.Example"
    private val ab = "$appPackage.Ab"
    private val reader = "$depPackage.Reader"

    private val appSources = mapOf(
        "Example.java" to """
            package sample.scope.app;
            import java.util.ResourceBundle;
            public class Example {
                public String value() {
                    ResourceBundle bundle = ResourceBundle.getBundle("messages");
                    if (Ab.enabled("feature")) return bundle.getString("new");
                    return kotlin.text.StringsKt.trim((CharSequence) bundle.getString("old")).toString() + "!";
                }
                public String viaHelper() { return sample.scope.dep.Reader.read("messages", "old"); }
                public String viaSource(sample.scope.dep.Source source) { return source.fetch("old"); }
                public String viaCallback() { return sample.scope.dep.Reader.once(() -> "old"); }
                public String viaUnknown(java.util.function.Supplier<String> s) { return s.get(); }
                public int viaMany() {
                    int n = 0;
            ${(1..65).joinToString("\n") { "        n += sample.scope.dep.Reader.via(() -> \"v$it\").length();" }}
                    return n;
                }
            }
        """.trimIndent(),
        "Ab.java" to """
            package sample.scope.app;
            public class Ab {
                public static boolean enabled(String key) { return key.length() > 3; }
            }
        """.trimIndent(),
        "Huge.java" to """
            package sample.scope.app;
            public class Huge {
                public int count(int n) {
            ${(1..1100).joinToString("\n") { "        if (n == $it) { n = n + 1; }" }}
                    return n;
                }
            }
        """.trimIndent()
    )

    private val depSources = mapOf(
        "Reader.java" to """
            package sample.scope.dep;
            public class Reader {
                public static String read(String bundleName, String key) {
                    return java.util.ResourceBundle.getBundle(bundleName).getString(key);
                }
                public static native int nativeSize();
                public static String fixed() { return java.util.ResourceBundle.getBundle("messages").getString("old"); }
                public static String via(java.util.function.Supplier<String> supplier) { return supplier.get(); }
                public static String once(java.util.function.Supplier<String> supplier) { return supplier.get(); }
            }
        """.trimIndent(),
        "Source.java" to """
            package sample.scope.dep;
            public interface Source { String fetch(String key); }
        """.trimIndent()
    )

    private val otherSources = mapOf(
        "Util.java" to """
            package sample.scope.other;
            public class Util { public static int size() { return 1; } }
        """.trimIndent()
    )

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

    private val gateOff: ConstantFold = ConstantFold.parse(
        mapOf("match" to mapOf("CallSite" to mapOf("callee_class" to ab, "callee_name" to "enabled")), "value" to false)
    )

    private val readMethod = method(reader, "read", "java.lang.String", "java.lang.String", returns = "java.lang.String")
    private val trimMethod = method("kotlin.text.StringsKt", "trim", "java.lang.CharSequence", returns = "java.lang.CharSequence")
    private val getBundleMethod = method("java.util.ResourceBundle", "getBundle", "java.lang.String", returns = "java.util.ResourceBundle")

    private fun method(className: String, name: String, vararg parameters: String, returns: String = "void") =
        MethodDescriptor(TypeDescriptor(className), name, parameters.map(::TypeDescriptor), TypeDescriptor(returns))

    private fun AnalysisCompleteness.roles(): Map<String, ArtifactRole> = artifacts.mapValues { it.value.role }

    /** Whether [to] is [from] or reachable from it over dataflow edges within a few hops. */
    private fun reaches(graph: Graph, from: NodeId, to: NodeId): Boolean {
        var frontier = setOf(from)
        repeat(4) {
            if (to in frontier) return true
            frontier = frontier.flatMap { id -> graph.outgoing(id).filterIsInstance<DataFlowEdge>().map { it.to } }.toSet()
        }
        return to in frontier
    }

    private fun load(input: Path, config: LoaderConfig): Graph = JavaProjectLoader(config).load(input)

    private fun stdlibJar(): Path = System.getProperty("java.class.path").split(File.pathSeparator).map(Path::of)
        .single { it.name.startsWith("kotlin-stdlib-") && it.name.endsWith(".jar") && !it.name.contains("jdk") }

    private var depJar: Path? = null

    /** The helper jar: its two classes, and a multi-release copy of one of them SootUp does not select. */
    private fun depJar(): Path = depJar ?: run {
        val classes = compile(depSources, "dep")
        val readerClass = classes.resolve("sample/scope/dep/Reader.class")
        jar(
            Files.createTempDirectory("scope-dep").also(temporaryRoots::add).resolve("dep-1.0.jar"),
            classes,
            "sample/scope/dep/",
            extra = mapOf("META-INF/versions/9/sample/scope/dep/Reader.class" to Files.readAllBytes(readerClass))
        ).also { depJar = it }
    }

    /** A jar of an unrelated package that also defines the helper's `Source` interface. */
    private fun otherJar(): Path {
        val classes = compile(otherSources, "other")
        val source = compile(depSources, "dep-copy").resolve("sample/scope/dep/Source.class")
        return jar(
            Files.createTempDirectory("scope-other").also(temporaryRoots::add).resolve("other-1.0.jar"),
            classes,
            "sample/scope/other/",
            extra = mapOf("sample/scope/dep/Source.class" to Files.readAllBytes(source))
        )
    }

    private fun bootJar(): Path {
        val appClasses = compile(appSources, "app", classpath = listOf(depJar(), stdlibJar()))
        val jarPath = Files.createTempDirectory("scope-boot").also(temporaryRoots::add).resolve("app.jar")
        JarOutputStream(Files.newOutputStream(jarPath), Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue(JavaArchiveLayout.SPRING_BOOT_CLASSES_ATTRIBUTE, JavaArchiveLayout.BOOT_INF_CLASSES)
            mainAttributes.putValue(JavaArchiveLayout.SPRING_BOOT_LIB_ATTRIBUTE, JavaArchiveLayout.BOOT_INF_LIB)
        }).use { jos ->
            jos.putNextEntry(JarEntry(JavaArchiveLayout.BOOT_INF_CLASSES))
            jos.closeEntry()
            appClasses.toFile().walk().filter { it.isFile && it.name.endsWith(".class") }.forEach { classFile ->
                val relative = classFile.relativeTo(appClasses.toFile()).path.replace(File.separatorChar, '/')
                jos.putNextEntry(JarEntry(JavaArchiveLayout.bootInfClassEntry(relative)))
                classFile.inputStream().use { it.copyTo(jos) }
                jos.closeEntry()
            }
            jos.putNextEntry(JarEntry(JavaArchiveLayout.bootInfClassEntry("messages.properties")))
            jos.write("old=Hello\nnew=Bonjour\n".toByteArray())
            jos.closeEntry()
            jos.putNextEntry(JarEntry(JavaArchiveLayout.BOOT_INF_LIB))
            jos.closeEntry()
            for (lib in listOf(depJar(), otherJar(), stdlibJar())) {
                jos.putNextEntry(JarEntry(JavaArchiveLayout.bootInfLibEntry(lib.name)))
                Files.newInputStream(lib).use { it.copyTo(jos) }
                jos.closeEntry()
            }
        }
        return jarPath
    }

    private fun compile(sources: Map<String, String>, label: String, classpath: List<Path> = emptyList()): Path {
        val root = Files.createTempDirectory("scope-$label").also(temporaryRoots::add)
        val sourceDir = Files.createDirectories(root.resolve("src"))
        val classesDir = Files.createDirectories(root.resolve("classes"))
        val files = sources.map { (name, text) -> sourceDir.resolve(name).also { Files.writeString(it, text) }.toString() }
        val options = listOf("-d", classesDir.toString(), "-cp", classpath.joinToString(File.pathSeparator))
        val result = ToolProvider.getSystemJavaCompiler().run(null, null, null, *options.toTypedArray(), *files.toTypedArray())
        assertEquals(0, result, "$label compiles")
        return classesDir
    }

    private fun jar(path: Path, classes: Path, prefix: String, extra: Map<String, ByteArray> = emptyMap()): Path {
        JarOutputStream(Files.newOutputStream(path), Manifest().apply { mainAttributes.putValue("Manifest-Version", "1.0") }).use { jos ->
            File(classes.toFile(), prefix).walk().filter { it.isFile && it.name.endsWith(".class") }.forEach { classFile ->
                jos.putNextEntry(JarEntry(classFile.relativeTo(classes.toFile()).path.replace(File.separatorChar, '/')))
                classFile.inputStream().use { it.copyTo(jos) }
                jos.closeEntry()
            }
            extra.forEach { (name, bytes) ->
                jos.putNextEntry(JarEntry(name))
                jos.write(bytes)
                jos.closeEntry()
            }
        }
        return path
    }
}
