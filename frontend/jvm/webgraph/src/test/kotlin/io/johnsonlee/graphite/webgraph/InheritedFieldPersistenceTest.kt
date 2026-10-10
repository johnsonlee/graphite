package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.jvmTypeDescriptor
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.LoaderConfig
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InheritedFieldPersistenceTest {
    private val root = Files.createTempDirectory("inherited-field-types")

    @AfterTest
    fun cleanup() { root.toFile().deleteRecursively() }

    @Test
    fun `default inherited field graph preserves aliases through save reload and resave`() = checkGraph(false)

    @Test
    fun `mmap inherited field graph preserves aliases through save reload and resave`() = checkGraph(true)

    private fun checkGraph(mmap: Boolean) {
        val output = System.getenv("GRAPHITE_INHERITED_TYPES_FIXTURE")?.let(Path::of) ?: root.resolve("export")
        val directory = output.resolve(if (mmap) "mmap" else "default")
        require(!Files.exists(directory)) { "Inherited fixture output must be fresh: $directory" }
        Files.createDirectories(directory)
        val graph = JavaProjectLoader(
            LoaderConfig(buildCallGraph = false, includePackages = listOf("fixture")), useMmapBuilder = mmap
        ).load(compile())
        try {
            assertFields(graph)
            val erased = erasedFields(graph)
            val rows = fieldRows(graph)
            val original = directory.resolve("original")
            val resaved = directory.resolve("resaved")
            GraphStore.save(graph, original)
            verifyReload(graph, erased, rows, original) { loaded -> GraphStore.save(loaded, resaved) }
            verifyReload(graph, erased, rows, resaved) { }
        } finally {
            (graph as? AutoCloseable)?.close()
        }
    }

    private fun verifyReload(
        expected: Graph,
        erased: List<List<Any>>,
        rows: List<Map<String, Any?>>,
        path: Path,
        action: (Graph) -> Unit
    ) {
        Files.newInputStream(path.resolve("graph.types")).use {
            assertEquals(0x47545905, java.io.DataInputStream(it).readInt())
        }
        assertTrue(StringTable.load(path, verifySerializedDigest = true).serializedDigest() != null)
        val loaded = GraphStore.loadMapped(path)
        try {
            assertFields(loaded)
            assertEquals(expected.declaredTypes(), loaded.declaredTypes())
            assertEquals(erased, erasedFields(loaded), "Complete erased field identities")
            assertEquals(rows, fieldRows(loaded), "Complete generic render/info query rows")
            action(loaded)
        } finally {
            (loaded as? AutoCloseable)?.close()
        }
    }

    private fun erasedFields(graph: Graph): List<List<Any>> = graph.nodes<FieldNode>().sortedBy { it.id.value }.map {
        listOf(it.id.value, it.descriptor.declaringClass.className, it.descriptor.name,
            jvmTypeDescriptor(it.descriptor.type.className), it.isStatic)
    }.toList()

    private fun fieldRows(graph: Graph): List<Map<String, Any?>> = CypherExecutor(graph).execute(
        "MATCH (f:Field) RETURN f.id AS id, f.class AS owner, f.name AS name, f.type AS erased, " +
            "f.static AS static, f.generic_type AS declared, f.type_info AS info ORDER BY id"
    ).rows

    private fun assertFields(graph: Graph) {
        val table = graph.declaredTypes()
        val nodes = graph.nodes<FieldNode>().toList()
        for ((name, owner, rendered) in listOf(
            Triple("names", PARENT, "java.util.List<java.lang.String>"),
            Triple("value", PARENT, "T"),
            Triple("shared", PARENT, "java.util.List<java.lang.Long>"),
            Triple("ITEMS", ITEMS, "java.util.List<java.lang.Integer>")
        )) {
            val child = nodes.single { it.descriptor.declaringClass.className == CHILD && it.descriptor.name == name }
            val original = nodes.single { it.descriptor.declaringClass.className == owner && it.descriptor.name == name }
            val descriptor = jvmTypeDescriptor(child.descriptor.type.className)
            val aliasKey = MemberTypeKey(CHILD, name, descriptor)
            val originalKey = MemberTypeKey(owner, name, descriptor)
            val type = table.fields.getValue(originalKey)
            assertEquals(type, table.fields.getValue(aliasKey), name)
            assertEquals(rendered, table.render(type), name)
            assertEquals(expectedInfo(name), table.info(type), name)
            assertEquals(table.info(type), table.info(table.fields.getValue(aliasKey)), name)
            assertNotEquals(child.id, original.id, "Original erased field nodes remain distinct")
            assertEquals(original.descriptor.type.className, child.descriptor.type.className)
            assertEquals(name == "shared" || name == "ITEMS", child.isStatic)
        }
        val variable = table.fields.getValue(MemberTypeKey(CHILD, "value", "Ljava/lang/Object;"))
        assertEquals(mapOf("kind" to "variable", "name" to "T", "scope" to "class:$PARENT",
            "arguments" to emptyList<Any>()), table.info(variable))
        assertEquals("fixture.InheritedFixture\$Parent<java.lang.String>",
            table.render(requireNotNull(table.classes.getValue(CHILD).superType)))
        val shadow = table.fields.getValue(MemberTypeKey(SHADOW, "names", "Ljava/util/List;"))
        assertEquals("java.util.List<java.lang.Integer>", table.render(shadow))
        assertNotEquals(shadow, table.fields.getValue(MemberTypeKey(CHILD, "names", "Ljava/util/List;")))
        table.validate()
    }

    private fun expectedInfo(name: String): Map<String, Any?> = if (name == "value") {
        mapOf("kind" to "variable", "name" to "T", "scope" to "class:$PARENT", "arguments" to emptyList<Any>())
    } else {
        val argument = when (name) {
            "names" -> "java.lang.String"
            "shared" -> "java.lang.Long"
            else -> "java.lang.Integer"
        }
        mapOf("kind" to "class", "name" to "java.util.List", "arguments" to listOf(
            mapOf("kind" to "class", "name" to argument, "arguments" to emptyList<Any>())
        ))
    }

    private fun compile(): Path {
        val source = root.resolve("InheritedFixture.java")
        Files.writeString(source, """
            package fixture;
            import java.util.List;
            public class InheritedFixture {
                public interface Empty { }
                public interface Items { List<Integer> ITEMS = null; }
                public static class Parent<T> {
                    public List<String> names;
                    public T value;
                    public static List<Long> shared;
                }
                public static class Child extends Parent<String> implements Empty, Items {
                    public Object[] read(Child child) {
                        return new Object[] { child.names, child.value, Child.shared, Child.ITEMS };
                    }
                }
                public static class Shadow extends Parent<String> {
                    public List<Integer> names;
                    public List<Integer> read(Shadow child) { return child.names; }
                }
            }
        """.trimIndent())
        val classes = Files.createDirectory(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-d", classes.toString(), source.toString()))
        val node = ClassNode()
        ClassReader(Files.readAllBytes(classes.resolve("fixture/InheritedFixture\$Child.class"))).accept(node, 0)
        val references = node.methods.flatMap { method -> method.instructions.toArray().filterIsInstance<FieldInsnNode>() }
            .filter { it.owner == CHILD.replace('.', '/') }.map { it.name to it.desc }.toSet()
        assertEquals(setOf("names" to "Ljava/util/List;", "value" to "Ljava/lang/Object;",
            "shared" to "Ljava/util/List;", "ITEMS" to "Ljava/util/List;"), references,
            "The compiled fixture must actually reference inherited fields through Child")
        assertTrue(node.interfaces.contains(ITEMS.replace('.', '/')))
        val jar = root.resolve("fixture.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            Files.walk(classes).use { files ->
                files.filter(Files::isRegularFile).sorted().forEach { file ->
                    output.putNextEntry(JarEntry(classes.relativize(file).toString().replace('\\', '/')))
                    output.write(Files.readAllBytes(file))
                    output.closeEntry()
                }
            }
        }
        return jar
    }

    private companion object {
        const val PARENT = "fixture.InheritedFixture\$Parent"
        const val CHILD = "fixture.InheritedFixture\$Child"
        const val ITEMS = "fixture.InheritedFixture\$Items"
        const val SHADOW = "fixture.InheritedFixture\$Shadow"
    }
}
