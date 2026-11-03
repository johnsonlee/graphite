package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.AnalysisCompleteness
import io.johnsonlee.graphite.graph.ArtifactRole
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.ConstantFold
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
import kotlin.test.assertEquals
/**
 * The fixture of the dependency-scope tests: an application whose only reader of a new resource
 * key sits behind a gate the fold rules out, a helper jar that reads bundles for it (with a
 * multi-release copy), a jar of an unrelated package that duplicates one of the helper's
 * classes, and the real `kotlin-stdlib`, assembled as a Spring Boot jar, a directory or a plain
 * archive; the compilers and archivers the tests build them with; and the descriptors the
 * assertions name.
 */
abstract class DependencyScopeFixture {

    protected val temporaryRoots = mutableListOf<Path>()

    @AfterTest
    fun cleanUp() {
        temporaryRoots.forEach { it.toFile().deleteRecursively() }
        File(System.getProperty("java.io.tmpdir")).listFiles()
            ?.filter { it.name.startsWith("graphite-springboot") }
            ?.forEach { it.deleteRecursively() }
    }

    protected val appPackage = "sample.scope.app"
    protected val depPackage = "sample.scope.dep"
    protected val example = "$appPackage.Example"
    protected val ab = "$appPackage.Ab"
    protected val reader = "$depPackage.Reader"

    protected val appSources = mapOf(
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

    protected val depSources = mapOf(
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
        """.trimIndent(),
        "Base.java" to """
            package sample.scope.dep;
            public class Base { public String value() { return "base"; } }
        """.trimIndent(),
        "Sub.java" to """
            package sample.scope.dep;
            public class Sub extends Base { @Override public String value() { return "sub"; } }
        """.trimIndent(),
        "Config.java" to """
            package sample.scope.dep;
            public class Config {
                public static final String value = java.util.ResourceBundle.getBundle("messages").getString("live");
            }
        """.trimIndent()
    )

    /**
     * A root whose call on a function value resolves, after class processing, to a helper body a
     * non-root produced; the non-root `Zzz` is processed last and seeds nothing.
     */
    protected val deferredDispatchSources = mapOf(
        "Root.java" to """
            package sample.scope.app;
            public class Root {
                public String run() { return new Factory().make().get(); }
            }
        """.trimIndent(),
        "Factory.java" to """
            package sample.scope.app;
            public class Factory {
                public java.util.function.Supplier<String> make() { return sample.scope.dep.Reader::fixed; }
            }
        """.trimIndent(),
        "Zzz.java" to """
            package sample.scope.app;
            public class Zzz { public int size() { return "zzz".length(); } }
        """.trimIndent()
    )

    /**
     * Application-package classes shipped in a nested library jar: a non-root whose call on a
     * function value resolves to a helper body, beside a root that calls no library at all.
     */
    protected val nestedAppSources = mapOf(
        "NonRoot.java" to """
            package sample.scope.app;
            public class NonRoot {
                public String go() { return new Factory().make().get(); }
            }
        """.trimIndent(),
        "Factory.java" to """
            package sample.scope.app;
            public class Factory {
                public java.util.function.Supplier<String> make() { return sample.scope.dep.Reader::fixed; }
            }
        """.trimIndent()
    )
    protected val quietRootSource = "Root.java" to """
        package sample.scope.app;
        public class Root { public int run() { return 1; } }
    """.trimIndent()

    /** An application subclass whose `super.value()` is processed before any virtual call on `Base.value`. */
    protected val childSource = "Child.java" to """
        package sample.scope.app;
        public class Child extends sample.scope.dep.Base {
            public String apply() { return super.value() + "!"; }
            public String zone(sample.scope.dep.Base base) { return base.value(); }
        }
    """.trimIndent()

    protected val otherSources = mapOf(
        "Util.java" to """
            package sample.scope.other;
            public class Util { public static int size() { return 1; } }
        """.trimIndent()
    )

    protected val gateOff: ConstantFold = ConstantFold.parse(
        mapOf("match" to mapOf("CallSite" to mapOf("callee_class" to ab, "callee_name" to "enabled")), "value" to false)
    )

    protected val readMethod = method(reader, "read", "java.lang.String", "java.lang.String", returns = "java.lang.String")
    protected val trimMethod = method("kotlin.text.StringsKt", "trim", "java.lang.CharSequence", returns = "java.lang.CharSequence")
    protected val getBundleMethod =
        method("java.util.ResourceBundle", "getBundle", "java.lang.String", returns = "java.util.ResourceBundle")

    protected fun method(className: String, name: String, vararg parameters: String, returns: String = "void") =
        MethodDescriptor(TypeDescriptor(className), name, parameters.map(::TypeDescriptor), TypeDescriptor(returns))

    protected fun AnalysisCompleteness.roles(): Map<String, ArtifactRole> = artifacts.mapValues { it.value.role }

    /** Whether [to] is [from] or reachable from it over dataflow edges within a few hops. */
    protected fun reaches(graph: Graph, from: NodeId, to: NodeId): Boolean {
        var frontier = setOf(from)
        repeat(4) {
            if (to in frontier) return true
            frontier = frontier.flatMap { id -> graph.outgoing(id).filterIsInstance<DataFlowEdge>().map { it.to } }.toSet()
        }
        return to in frontier
    }

    protected fun load(input: Path, config: LoaderConfig): Graph = JavaProjectLoader(config).load(input)

    protected fun stdlibJar(): Path = System.getProperty("java.class.path").split(File.pathSeparator).map(Path::of)
        .single { it.name.startsWith("kotlin-stdlib-") && it.name.endsWith(".jar") && !it.name.contains("jdk") }

    protected var depJar: Path? = null

    /** The helper jar: its two classes, and a multi-release copy of one of them SootUp does not select. */
    protected fun depJar(): Path = depJar ?: run {
        val classes = compile(depSources, "dep")
        val readerClass = classes.resolve("sample/scope/dep/Reader.class")
        jar(
            Files.createTempDirectory("scope-dep").also(temporaryRoots::add).resolve("dep-1.0.jar"),
            classes,
            "sample/scope/dep/",
            extra = mapOf("META-INF/versions/9/sample/scope/dep/Reader.class" to Files.readAllBytes(readerClass))
        ).also { depJar = it }
    }

    /** A jar of an unrelated package that also defines the helper's `Source` interface, and [extra]. */
    protected fun otherJar(extra: Map<String, ByteArray> = emptyMap()): Path {
        val classes = compile(otherSources, "other")
        val source = compile(depSources, "dep-copy").resolve("sample/scope/dep/Source.class")
        return jar(
            Files.createTempDirectory("scope-other").also(temporaryRoots::add).resolve("other-1.0.jar"),
            classes,
            "sample/scope/other/",
            extra = mapOf("sample/scope/dep/Source.class" to Files.readAllBytes(source)) + extra
        )
    }

    /**
     * The Spring Boot jar: the application classes (and [extraAppSources]) under `BOOT-INF/classes/`,
     * the helper, the unrelated jar (also defining the application classes [duplicateInOther] names)
     * and the stdlib under `BOOT-INF/lib/`, and the launcher's [classpathIndex] when given.
     */
    protected fun bootJar(
        classpathIndex: List<String>? = null,
        extraAppSources: Map<String, String> = emptyMap(),
        duplicateInOther: List<String> = emptyList(),
        extraLibs: List<Path> = emptyList()
    ): Path {
        val appClasses = compile(appSources + extraAppSources, "app", classpath = listOf(depJar(), stdlibJar()) + extraLibs)
        val otherExtra = duplicateInOther.associate { className ->
            val entry = className.replace('.', '/') + ".class"
            entry to Files.readAllBytes(appClasses.resolve(entry))
        }
        val otherJar = otherJar(otherExtra)
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
            if (classpathIndex != null) {
                jos.putNextEntry(JarEntry(JavaArchiveLayout.BOOT_INF_CLASSPATH_INDEX))
                jos.write(classpathIndex.joinToString("") { "- \"${JavaArchiveLayout.BOOT_INF_LIB}$it\"\n" }.toByteArray())
                jos.closeEntry()
            }
            jos.putNextEntry(JarEntry(JavaArchiveLayout.BOOT_INF_LIB))
            jos.closeEntry()
            for (lib in listOf(depJar(), otherJar, stdlibJar()) + extraLibs) {
                jos.putNextEntry(JarEntry(JavaArchiveLayout.bootInfLibEntry(lib.name)))
                Files.newInputStream(lib).use { it.copyTo(jos) }
                jos.closeEntry()
            }
        }
        return jarPath
    }

    protected fun compile(sources: Map<String, String>, label: String, classpath: List<Path> = emptyList()): Path {
        val root = Files.createTempDirectory("scope-$label").also(temporaryRoots::add)
        val sourceDir = Files.createDirectories(root.resolve("src"))
        val classesDir = Files.createDirectories(root.resolve("classes"))
        val files = sources.map { (name, text) -> sourceDir.resolve(name).also { Files.writeString(it, text) }.toString() }
        val options = listOf("-d", classesDir.toString(), "-cp", classpath.joinToString(File.pathSeparator))
        val result = ToolProvider.getSystemJavaCompiler().run(null, null, null, *options.toTypedArray(), *files.toTypedArray())
        assertEquals(0, result, "$label compiles")
        return classesDir
    }

    protected fun jar(
        path: Path,
        classes: Path,
        prefix: String,
        extra: Map<String, ByteArray> = emptyMap(),
        multiRelease: Boolean = false
    ): Path {
        val manifest = Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            if (multiRelease) mainAttributes.putValue("Multi-Release", "true")
        }
        JarOutputStream(Files.newOutputStream(path), manifest).use { jos ->
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
