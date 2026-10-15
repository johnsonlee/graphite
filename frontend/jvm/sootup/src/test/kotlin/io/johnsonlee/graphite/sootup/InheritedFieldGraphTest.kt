package io.johnsonlee.graphite.sootup

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

class InheritedFieldGraphTest {
    private val root = Files.createTempDirectory("inherited-field-types")

    @AfterTest
    fun cleanup() { root.toFile().deleteRecursively() }

    @Test
    fun `default graph binds compiled Child fieldrefs to original generic declarations`() = checkGraph(false)

    @Test
    fun `mmap graph binds compiled Child fieldrefs to original generic declarations`() = checkGraph(true)

    private fun checkGraph(mmap: Boolean) {
        val jar = compile()
        val graph = JavaProjectLoader(
            LoaderConfig(buildCallGraph = false, includePackages = listOf("fixture")), useMmapBuilder = mmap
        ).load(jar)
        try {
            assertFields(graph)
        } finally {
            (graph as? AutoCloseable)?.close()
        }
    }

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
