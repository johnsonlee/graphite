package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.jvmTypeDescriptor
import io.johnsonlee.graphite.graph.DeclaredTypeBinding
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The ID-keyed binding of a mapped GTY05 graph agrees with the member-key lookup for every node and method. */
class MappedDeclaredTypeBindingTest {
    @Test
    fun `mapped graph binds members by IDs and agrees with the key lookup`() = fixture { dir, graph ->
        val binding = assertNotNull(graph.declaredTypeBinding(), "a GTY05 mapped graph binds by IDs")
        val table = graph.declaredTypes()
        assertTrue(table.fields.isNotEmpty() && table.methods.isNotEmpty())
        var fields = 0
        for (node in graph.nodes(FieldNode::class.java)) {
            val descriptor = node.descriptor
            val key = MemberTypeKey(
                descriptor.declaringClass.className, descriptor.name, jvmTypeDescriptor(descriptor.type.className)
            )
            assertEquals(table.fields[key] ?: DeclaredTypeBinding.UNBOUND, binding.fieldTypeId(node), key.toString())
            fields++
        }
        assertTrue(fields >= 5, "the fixture declares at least five fields, saw $fields")
        var members = 0
        for (node in graph.nodes(ParameterNode::class.java) + graph.nodes(ReturnNode::class.java)) {
            val method = if (node is ParameterNode) node.method else (node as ReturnNode).method
            val declared = table.methods[MemberTypeKey(method.declaringClass.className, method.name, method.descriptor)]
            val expected =
                if (node is ParameterNode) declared?.parameterTypes?.getOrNull(node.index) else declared?.returnType
            assertEquals(expected ?: DeclaredTypeBinding.UNBOUND, binding.memberTypeId(node), method.signature)
            members++
        }
        assertTrue(members >= 6, "parameters and returns of the fixture's methods, saw $members")
        var methods = 0
        for (method in graph.methods(MethodPattern())) {
            assertTrue(method.declarationHandle >= 0, "the mapped index attaches a handle to ${method.signature}")
            val expected = table.methods[MemberTypeKey(method.declaringClass.className, method.name, method.descriptor)]
            assertEquals(expected, binding.methodTypes(method), method.signature)
            val shape = binding.methodShape(method)
            if (expected == null) assertEquals(DeclaredTypeBinding.UNBOUND, shape) else {
                val hasParameters = shape and DeclaredTypeBinding.HAS_PARAMETERS != 0
                val hasBounds = shape and DeclaredTypeBinding.HAS_BOUNDS != 0
                assertEquals(expected.parameterTypes.isNotEmpty(), hasParameters, method.signature)
                assertEquals(expected.typeParameters.any { it.bounds.isNotEmpty() }, hasBounds, method.signature)
            }
            methods++
        }
        assertTrue(methods >= 4)
        // A descriptor nobody materialized carries no handle: the key lookup decides.
        val list = TypeDescriptor("java.util.List")
        val handmade = MethodDescriptor(TypeDescriptor("fixture.ids.Holder"), "echo", listOf(list), list)
        assertSame(DeclaredTypeBinding.UNANSWERED_METHOD, binding.methodTypes(handmade))
        assertEquals(DeclaredTypeBinding.UNANSWERED, binding.methodShape(handmade))
        // A node this graph does not hold, or holds under another tag, is not this binding's to answer.
        val foreign = FieldDescriptor(TypeDescriptor("x.Y"), "z", TypeDescriptor("int"))
        val stranger = FieldNode(NodeId(Int.MAX_VALUE - 1), foreign, false)
        assertEquals(DeclaredTypeBinding.UNANSWERED, binding.fieldTypeId(stranger))
        val someParameter = graph.nodes(ParameterNode::class.java).first()
        val mistagged = FieldNode(someParameter.id, foreign, false)
        assertEquals(DeclaredTypeBinding.UNANSWERED, binding.fieldTypeId(mistagged))
        assertEquals(DeclaredTypeBinding.UNANSWERED, binding.memberTypeId(stranger))
        assertEquals(DeclaredTypeBinding.UNANSWERED, binding.memberTypeId(graph.nodes(FieldNode::class.java).first()))

        // The table-side lookup by string IDs: an unbound member, a string that names no type, an array type.
        val ids = assertNotNull((table.types as MappedDeclaredTypeIds).binding())
        val strings = StringTable.load(dir)
        val holder = strings.findId("fixture.ids.Holder")
        assertTrue(holder >= 0 && strings.findId("items") >= 0 && strings.findId("java.util.List") >= 0)
        assertEquals(
            table.fields.getValue(MemberTypeKey("fixture.ids.Holder", "items", "Ljava/util/List;")),
            ids.fieldTypeId(holder, strings.findId("items"), strings.findId("java.util.List"))
        )
        val items = strings.findId("items")
        val listName = strings.findId("java.util.List")
        assertEquals(DeclaredTypeBinding.UNBOUND, ids.fieldTypeId(holder, items, strings.findId("echo")))
        assertEquals(DeclaredTypeBinding.UNBOUND, ids.fieldTypeId(items, items, listName))
        assertTrue(ids.typeRowOf(strings.findId("int[][]")) >= 0, "the grid field's array type has an erased row")
        assertEquals(
            table.fields.getValue(MemberTypeKey("fixture.ids.Holder", "grid", "[[I")),
            ids.fieldTypeId(holder, strings.findId("grid"), strings.findId("int[][]"))
        )
        val echo = strings.findId("echo")
        assertNull(ids.methodTypes(holder, echo, IntArray(0), listName), "wrong arity")
        assertNull(ids.methodTypes(holder, echo, intArrayOf(echo), listName), "not a type")
        assertEquals(
            table.methods[MemberTypeKey("fixture.ids.Holder", "echo", "(Ljava/util/List;)Ljava/util/List;")],
            ids.methodTypes(holder, echo, intArrayOf(listName), listName)
        )
        val run = assertNotNull(table.methods[MemberTypeKey("fixture.ids.Holder", "run", "(I[Ljava/lang/String;)V")])
        val runIds = intArrayOf(strings.findId("int"), strings.findId("java.lang.String[]"))
        assertEquals(run, ids.methodTypes(holder, strings.findId("run"), runIds, strings.findId("void")))
        val runName = strings.findId("run")
        val void = strings.findId("void")
        assertEquals(run.parameterTypes[1], ids.memberTypeId(holder, runName, runIds, void, 1))
        assertEquals(run.returnType, ids.memberTypeId(holder, runName, runIds, void, -1))
        assertEquals(DeclaredTypeBinding.UNBOUND, ids.memberTypeId(holder, runName, runIds, void, 2))
        assertEquals(DeclaredTypeBinding.UNBOUND, ids.memberTypeId(holder, runName, IntArray(0), void, -1))
    }

    @Test
    fun `an eagerly loaded graph and older table formats bind by member keys only`() = fixture { dir, graph ->
        GraphStore.load(dir).let { eager ->
            try { assertNull(eager.declaredTypeBinding()) } finally { (eager as? AutoCloseable)?.close() }
        }
        val legacySaves = listOf<(Path) -> Unit>(
            { GraphStore.saveLegacyDeclaredTypesV3(graph, it) }, { GraphStore.saveLegacyDeclaredTypesV4(graph, it) }
        )
        for (save in legacySaves) {
            val legacy = Files.createTempDirectory(dir, "legacy")
            save(legacy)
            val restored = GraphStore.loadMapped(legacy)
            try {
                assertNull(restored.declaredTypeBinding())
                assertEquals(graph.declaredTypes(), restored.declaredTypes())
            } finally { (restored as? AutoCloseable)?.close() }
        }
    }

    private fun fixture(block: (Path, Graph) -> Unit) {
        val dir = Files.createTempDirectory("declared-type-binding")
        try {
            val classes = Files.createDirectory(dir.resolve("classes"))
            val source = dir.resolve("Holder.java")
            Files.writeString(source, """
                package fixture.ids;
                import java.util.List;
                public class Holder<T extends Comparable<T>> {
                    public List<String> items;
                    public int count;
                    public int[][] grid;
                    public String[] names;
                    public T value;
                    public List<String> echo(List<String> values) { return values; }
                    public void run(int a, String[] b) { count = a + b.length; }
                    public T identity(T value) { return value; }
                    public static <U> U pick(U[] all) { return all[0]; }
                }
            """.trimIndent())
            val compiler = assertNotNull(ToolProvider.getSystemJavaCompiler())
            assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), source.toString()))
            val jar = dir.resolve("fixture.jar")
            JarOutputStream(Files.newOutputStream(jar)).use { output ->
                output.putNextEntry(JarEntry("fixture/ids/Holder.class"))
                Files.copy(classes.resolve("fixture/ids/Holder.class"), output)
                output.closeEntry()
            }
            val config = LoaderConfig(includePackages = listOf("fixture.ids"), buildCallGraph = false)
            val built = JavaProjectLoader(config).load(jar)
            val output = dir.resolve("graph")
            try {
                Files.createDirectories(output)
                GraphStore.save(built, output)
            } finally { (built as? AutoCloseable)?.close() }
            val mapped = GraphStore.loadMapped(output)
            try { block(output, mapped) } finally { (mapped as? AutoCloseable)?.close() }
        } finally {
            Files.walk(dir).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
