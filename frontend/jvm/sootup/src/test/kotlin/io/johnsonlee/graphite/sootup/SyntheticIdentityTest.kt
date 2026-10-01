package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stable identities for compiler-numbered synthetic members: the same source compiled with a
 * sibling inserted in front renumbers every lambda and anonymous class, and the fingerprints
 * must not move with the numbers.
 */
class SyntheticIdentityTest {

    private val packageName = "sample.shift"
    private val className = "$packageName.Shift"

    /** The members under test; [inserted] adds a lambda and an anonymous class in front of them. */
    private fun source(inserted: Boolean): String = """
        package sample.shift;

        import java.util.function.Function;
        import java.util.function.Supplier;

        public class Shift {
            private final String prefix = "p";
            ${if (inserted) """
            public Runnable inserted() { return () -> System.out.println("inserted"); }
            public Object insertedAnonymous() { return new Object() { public String toString() { return prefix; } }; }
            """ else ""}
            public Runnable first() { return () -> System.out.println("first"); }
            public Supplier<String> capturing(String x) { return () -> prefix + x; }
            public Runnable anonymous() {
                return new Runnable() { public void run() { System.out.println(prefix); } };
            }
            public Function<Integer, Integer> nested() {
                return i -> {
                    Runnable r = () -> System.out.println(i);
                    r.run();
                    return i + 1;
                };
            }
            public Supplier<Runnable> mixed() { return () -> new Runnable() { public void run() { } }; }
            public Supplier<Integer> twinA() { return () -> 1; }
            public Supplier<Integer> twinB() { return () -> 1; }
            public int loop(int n) {
                int total = 0;
                for (int i = 0; i < n; i++) {
                    try { total += i; } catch (RuntimeException e) { total = -1; }
                }
                return total;
            }
        }
    """.trimIndent()

    private fun compile(inserted: Boolean): Path = compile("Shift.java", source(inserted))

    private val temporaryRoots = mutableListOf<Path>()

    @AfterTest
    fun deleteCompiledFixtures() {
        temporaryRoots.forEach { it.toFile().deleteRecursively() }
        temporaryRoots.clear()
    }

    private fun compile(fileName: String, source: String): Path {
        val root = Files.createTempDirectory("synthetic-identity").also(temporaryRoots::add)
        val sourceDir = root.resolve("src/sample/shift").also { Files.createDirectories(it) }
        val classesDir = root.resolve("classes").also { Files.createDirectories(it) }
        val file = sourceDir.resolve(fileName)
        Files.writeString(file, source)
        val compiler = ToolProvider.getSystemJavaCompiler()
        val result = compiler.run(null, null, null, "-d", classesDir.toString(), file.toString())
        assertEquals(0, result, "fixture compiles")
        return classesDir
    }

    /** `Probe.java` in the fixture package with [body] as the class body. */
    private fun probe(body: String): Map<String, String> = load(
        compile("Probe.java", "package sample.shift;\n\nimport java.util.function.Supplier;\n\npublic class Probe {\n$body\n}\n")
    ).syntheticIdentities()

    private fun load(classesDir: Path): Graph =
        JavaProjectLoader(LoaderConfig(includePackages = listOf(packageName), buildCallGraph = false)).load(classesDir)

    private val baseline: Graph by lazy { load(compile(inserted = false)) }
    private val shifted: Graph by lazy { load(compile(inserted = true)) }

    @Test
    fun `only numbered members and synthetic members get a fingerprint`() {
        val identities = baseline.syntheticIdentities()
        assertTrue("$className\$1" in identities, "anonymous class: ${identities.keys}")
        assertTrue("$className.lambda\$first\$0()" in identities, "lambda method: ${identities.keys}")
        assertNull(baseline.syntheticIdentity("$className.first()"), "a stable method has no fingerprint")
        assertNull(baseline.syntheticIdentity(className), "a stable class has no fingerprint")
        assertFalse(identities.keys.any { it.startsWith("$className.loop") })
        identities.values.forEach { assertEquals(32, it.length, "128-bit hex fingerprint: $it") }
    }

    @Test
    fun `fingerprints survive renumbering by an inserted sibling`() {
        val before = baseline.syntheticIdentities()
        val after = shifted.syntheticIdentities()
        assertEquals(before.size + 2, after.size, "one lambda and one anonymous class were inserted: ${after.keys}")
        val afterByFingerprint = after.entries.associate { (key, fingerprint) -> fingerprint to key }
        before.forEach { (key, fingerprint) ->
            assertTrue(fingerprint in afterByFingerprint, "$key keeps its identity after the shift; after=$after")
        }
        val moved = before.filter { (key, fingerprint) -> afterByFingerprint[fingerprint] != key }
        assertTrue(moved.isNotEmpty(), "the compiler did renumber something: before=$before after=$after")
        assertEquals("$className\$2", afterByFingerprint[before.getValue("$className\$1")], "anonymous class moved by one")
        assertEquals(
            "$className.lambda\$first\$1()",
            afterByFingerprint[before.getValue("$className.lambda\$first\$0()")],
            "lambda moved by one"
        )
    }

    @Test
    fun `identical siblings are told apart by appearance order and keep their identity`() {
        val before = baseline.syntheticIdentities()
        val twins = before.filterKeys { it.contains("lambda\$twin") }
        assertEquals(2, twins.size, "both twins have a fingerprint: ${before.keys}")
        assertEquals(2, twins.values.toSet().size, "identical bodies get distinct identities")
        val after = shifted.syntheticIdentities()
        twins.forEach { (key, fingerprint) ->
            assertTrue(fingerprint in after.values, "$key keeps its identity when both twins shift")
        }
    }

    @Test
    fun `a member that references a renumbered member keeps its identity`() {
        val before = baseline.syntheticIdentities()
        val after = shifted.syntheticIdentities()
        val nested = before.filterKeys { it.contains("lambda\$nested") }
        assertEquals(2, nested.size, "outer and inner lambda: ${before.keys}")
        assertEquals(2, nested.values.toSet().size)
        nested.forEach { (key, fingerprint) ->
            assertTrue(fingerprint in after.values, "$key: the outer lambda references the inner one by identity, not by number")
        }
        val mixed = before.entries.single { it.key.contains("lambda\$mixed") }
        assertTrue(mixed.value in after.values, "lambda that allocates an anonymous class keeps its identity")
    }

    @Test
    fun `fingerprints are deterministic across loads`() {
        assertEquals(baseline.syntheticIdentities(), load(compile(inserted = false)).syntheticIdentities())
    }

    @Test
    fun `kotlin lambdas, object expressions and when mappings get fingerprints`() {
        val graph = JavaProjectLoader(
            LoaderConfig(includePackages = listOf("sample.synthetic"), buildCallGraph = false)
        ).load(findTestClassesDir("kotlin"))
        val identities = graph.syntheticIdentities()
        val example = "sample.synthetic.KotlinSyntheticExample"
        assertTrue(
            identities.keys.any { it.startsWith("$example.run\$lambda\$0(") || it.startsWith("$example.run\$lambda-0(") },
            "indy lambda: ${identities.keys}"
        )
        assertTrue("$example\$anonymous\$1" in identities, "object expression: ${identities.keys}")
        assertTrue("$example\$WhenMappings" in identities, "ACC_SYNTHETIC class: ${identities.keys}")
        assertNull(graph.syntheticIdentity("$example\$Mode"), "a nested enum is a stable name")
    }

    @Test
    fun `a string constant is literal text, so changing it changes the identity`() {
        val before = probe("""public Supplier<String> value() { return () -> "a.Foo${'$'}1"; }""")
        val after = probe("""public Supplier<String> value() { return () -> "a.Foo${'$'}2"; }""")
        val key = "$packageName.Probe.lambda\$value\$0()"
        assertNotEquals(before.getValue(key), after.getValue(key), "the returned constant is part of the body")
        val same = probe("""public Supplier<String> value() { return () -> "a.Foo${'$'}1"; }""")
        assertEquals(before.getValue(key), same.getValue(key))
    }

    @Test
    fun `numbered types inside a signature are canonicalized`() {
        fun source(inserted: Boolean) = """
            ${if (inserted) "public Object first() { return new Object() { public String toString() { return \"x\"; } }; }" else ""}
            public Runnable make() {
                return new Runnable() {
                    class Inner { }
                    Inner inner() { return new Inner(); }
                    public void run() { System.out.println(inner()); }
                };
            }
        """.trimIndent()
        val before = probe(source(inserted = false))
        val after = probe(source(inserted = true))
        val runnable = before.getValue("$packageName.Probe\$1")
        assertEquals(
            runnable,
            after["$packageName.Probe\$2"],
            "the Runnable's own methods name Inner through a numbered type: after=$after"
        )
        assertEquals(before.getValue("$packageName.Probe\$1\$Inner"), after["$packageName.Probe\$2\$Inner"])
        assertEquals(before.size + 1, after.size)
    }

    @Test
    fun `field modifiers are part of a class identity`() {
        fun source(modifier: String) = """
            public Runnable counter() {
                return new Runnable() {
                    $modifier int count;
                    public void run() { count++; }
                };
            }
        """.trimIndent()
        val plain = probe(source(""))
        val volatileField = probe(source("volatile"))
        val key = "$packageName.Probe\$1"
        assertNotEquals(plain.getValue(key), volatileField.getValue(key), "volatile changes memory semantics, so it changes the identity")
        assertEquals(plain.getValue(key), probe(source("")).getValue(key))
    }

    @Test
    fun `stripClassOrdinalsIn strips every numbered name in a text`() {
        assertEquals(
            "<a.Probe\$: a.Probe\$\$Inner make()>",
            SyntheticIdentity.stripClassOrdinalsIn("<a.Probe\$1: a.Probe\$1\$Inner make()>")
        )
        assertEquals("plain text", SyntheticIdentity.stripClassOrdinalsIn("plain text"))
    }

    @Test
    fun `ordinal patterns`() {
        assertTrue(SyntheticIdentity.hasMethodOrdinal("lambda\$run\$0"))
        assertTrue(SyntheticIdentity.hasMethodOrdinal("lambda\$new\$12"))
        assertTrue(SyntheticIdentity.hasMethodOrdinal("run\$lambda\$0"))
        assertTrue(SyntheticIdentity.hasMethodOrdinal("run\$lambda-3"))
        assertTrue(SyntheticIdentity.hasMethodOrdinal("access\$000"))
        assertFalse(SyntheticIdentity.hasMethodOrdinal("run"))
        assertFalse(SyntheticIdentity.hasMethodOrdinal("lambda\$run\$x"))
        assertFalse(SyntheticIdentity.hasMethodOrdinal("run0"))
        assertTrue(SyntheticIdentity.hasClassOrdinal("a.Foo\$1"))
        assertTrue(SyntheticIdentity.hasClassOrdinal("a.Foo\$bar\$1"))
        assertTrue(SyntheticIdentity.hasClassOrdinal("a.Foo\$\$ExternalSyntheticLambda0"))
        assertTrue(SyntheticIdentity.hasClassOrdinal("a.Foo\$\$ExternalSyntheticOutline12"))
        assertTrue(SyntheticIdentity.hasClassOrdinal("a.Foo\$\$Lambda\$12"))
        assertFalse(SyntheticIdentity.hasClassOrdinal("a.Foo\$Companion"))
        assertFalse(SyntheticIdentity.hasClassOrdinal("a.Foo1"))
        assertFalse(SyntheticIdentity.hasClassOrdinal("a.Foo\$1x"))
        assertTrue(SyntheticIdentity.hasClassOrdinal("a.Foo\$1\$Local"))
    }

    @Test
    fun `ordinals are stripped, enclosing ones included`() {
        assertEquals("a.Foo\$\$", SyntheticIdentity.stripClassOrdinals("a.Foo\$1\$2"))
        assertEquals("a.Foo\$bar\$", SyntheticIdentity.stripClassOrdinals("a.Foo\$bar\$1"))
        assertEquals("a.Foo\$\$ExternalSyntheticLambda", SyntheticIdentity.stripClassOrdinals("a.Foo\$\$ExternalSyntheticLambda3"))
        assertEquals("a.Foo\$\$Lambda\$", SyntheticIdentity.stripClassOrdinals("a.Foo\$\$Lambda\$12"))
        assertEquals("a.Foo", SyntheticIdentity.stripClassOrdinals("a.Foo"))
        assertEquals("a.Foo\$\$Local", SyntheticIdentity.stripClassOrdinals("a.Foo\$1\$Local"))
        assertEquals("lambda\$run\$", SyntheticIdentity.stripMethodOrdinal("lambda\$run\$7"))
        assertEquals("run\$lambda\$", SyntheticIdentity.stripMethodOrdinal("run\$lambda\$7"))
        assertEquals("access\$", SyntheticIdentity.stripMethodOrdinal("access\$100"))
        assertEquals("run", SyntheticIdentity.stripMethodOrdinal("run"))
    }

    @Test
    fun `fingerprint is 128 bits of SHA-256 in hex`() {
        assertEquals("9f86d081884c7d659a2feaa0c55ad015", SyntheticIdentity.fingerprint("test"))
        assertEquals(SyntheticIdentity.fingerprint("a"), SyntheticIdentity.fingerprint("a"))
        assertNotEquals(SyntheticIdentity.fingerprint("a"), SyntheticIdentity.fingerprint("b"))
    }

    @Test
    fun `an empty collector resolves to nothing`() {
        assertEquals(emptyMap(), SyntheticIdentity.Collector().resolve())
    }

    private fun findTestClassesDir(language: String): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/$language/test")
        val rootPath = projectDir.resolve("frontend/jvm/sootup/build/classes/$language/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }
}
