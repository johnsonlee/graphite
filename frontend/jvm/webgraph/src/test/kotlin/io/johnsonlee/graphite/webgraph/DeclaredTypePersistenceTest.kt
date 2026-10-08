package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.graph.MethodTypes
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeclaredTypePersistenceTest {
    private val fields = listOf("first", "second").associate {
        MemberTypeKey("sample.Holder", it, "Ljava/util/List;") to 1
    }
    private val table = DeclaredTypeTable(
        listOf(DeclaredType("class", "java.lang.String"), DeclaredType("class", "java.util.List", arguments = listOf(0))),
        fields,
        mapOf(MemberTypeKey("sample.Holder", "echo", "(Ljava/util/List;)Ljava/util/List;") to MethodTypes(listOf(1), 1)),
        emptyMap()
    )

    @Test
    fun `table round trip preserves shared type IDs and legacy absence`() = inDirectory { dir ->
        Files.writeString(dir.resolve("graph.metadata"), "binding")
        assertEquals(DeclaredTypeTable.EMPTY, DeclaredTypeStore.load(dir))
        DeclaredTypeStore.save(table, dir)
        val restored = DeclaredTypeStore.load(dir)
        assertEquals(table, restored)
        assertEquals(2, restored.types.size)
        assertEquals(setOf(1), restored.fields.values.toSet())
        assertEquals(1, restored.methods.values.single().returnType)
        assertEquals("java.util.List<java.lang.String>", restored.render(1))
        DeclaredTypeStore.save(DeclaredTypeTable.EMPTY, dir)
        assertFalse(Files.exists(dir.resolve(DeclaredTypeStore.FILE_NAME)))
    }

    @Test
    fun `invalid references truncated payload and mismatched metadata are rejected`() = inDirectory { dir ->
        Files.writeString(dir.resolve("graph.metadata"), "binding")
        assertFailsWith<IllegalArgumentException> {
            DeclaredTypeStore.save(table.copy(fields = mapOf(fields.keys.first() to 99)), dir)
        }
        val cyclic = table.copy(types = listOf(DeclaredType("array", component = 0)), fields = emptyMap(), methods = emptyMap())
        assertFailsWith<IllegalArgumentException> { DeclaredTypeStore.save(cyclic, dir) }
        DeclaredTypeStore.save(table, dir)
        val path = dir.resolve(DeclaredTypeStore.FILE_NAME)
        val valid = Files.readAllBytes(path)
        Files.write(path, valid.copyOf(valid.size - 1))
        rebind(dir)
        assertFailsWith<IllegalArgumentException> { DeclaredTypeStore.load(dir) }
        Files.write(path, valid)
        rebind(dir)
        Files.writeString(dir.resolve("graph.metadata"), "different binding")
        assertFailsWith<IllegalArgumentException> { DeclaredTypeStore.load(dir) }
        Files.writeString(dir.resolve("graph.metadata"), "binding")
        ByteBuffer.wrap(valid).putInt(36, Int.MAX_VALUE)
        Files.write(path, valid)
        rebind(dir)
        assertFailsWith<IllegalArgumentException> { DeclaredTypeStore.load(dir) }
    }

    @Test
    fun `bound tables reject unsupported versions trailing bytes and invalid lengths`() = inDirectory { dir ->
        Files.writeString(dir.resolve("graph.metadata"), "metadata")
        DeclaredTypeStore.save(table, dir)
        val path = dir.resolve(DeclaredTypeStore.FILE_NAME)
        val valid = Files.readAllBytes(path)
        val corruptions = listOf(
            valid.copyOf().also { ByteBuffer.wrap(it).putInt(0, 0x47545902) } to "Unsupported graph.types header/version",
            valid.copyOf(valid.size + 1) to "Trailing bytes in graph.types",
            valid.copyOf().also { ByteBuffer.wrap(it).putInt(40, -1) } to "Invalid graph.types string length",
            valid.copyOf(35) to "Invalid graph.types length"
        )
        for ((bytes, message) in corruptions) {
            Files.write(path, bytes)
            rebind(dir)
            assertEquals(message, assertFailsWith<IllegalArgumentException> { DeclaredTypeStore.load(dir) }.message)
        }
        Files.writeString(dir.resolve("forward.properties"), "${DeclaredTypeStore.BINDING_KEY}=invalid\n")
        assertEquals("Invalid graph.types binding", assertFailsWith<IllegalArgumentException> { DeclaredTypeStore.load(dir) }.message)
    }

    @Test
    fun `properties bind the actual generic table even when erased metadata is identical`() = inDirectory { dir ->
        Files.writeString(dir.resolve("graph.metadata"), "identical erased metadata")
        DeclaredTypeStore.save(table, dir)
        val original = Files.readAllBytes(dir.resolve(DeclaredTypeStore.FILE_NAME))
        val properties = Files.readString(dir.resolve("forward.properties"))
        val changed = table.copy(types = listOf(DeclaredType("class", "java.lang.Integer"), table.types[1]))
        DeclaredTypeStore.save(changed, dir)
        assertEquals(changed, DeclaredTypeStore.load(dir))
        Files.write(dir.resolve(DeclaredTypeStore.FILE_NAME), original)
        assertFailsWith<IllegalArgumentException> { DeclaredTypeStore.load(dir) }
        Files.writeString(dir.resolve("forward.properties"), properties)
        assertEquals(table, DeclaredTypeStore.load(dir))
        Files.delete(dir.resolve(DeclaredTypeStore.FILE_NAME))
        assertFailsWith<IllegalArgumentException> { DeclaredTypeStore.load(dir) }
    }

    @Test
    fun `older writer properties invalidate orphan tables and preserve unrelated graph properties`() = inDirectory { dir ->
        Files.writeString(dir.resolve("graph.metadata"), "metadata")
        val properties = "nodes=12\narcs=8\n"
        Files.writeString(dir.resolve("forward.properties"), properties)
        DeclaredTypeStore.save(table, dir)
        assertTrue(Files.readString(dir.resolve("forward.properties")).startsWith(properties))
        // An older BVGraph writer replaces forward.properties without the new binding.
        Files.writeString(dir.resolve("forward.properties"), properties)
        assertEquals(DeclaredTypeTable.EMPTY, DeclaredTypeStore.load(dir))
        Files.writeString(dir.resolve(DeclaredTypeStore.FILE_NAME), "orphan is ignored even if damaged")
        assertEquals(DeclaredTypeTable.EMPTY, DeclaredTypeStore.load(dir))
        DeclaredTypeStore.save(table, dir)
        DeclaredTypeStore.save(DeclaredTypeTable.EMPTY, dir)
        assertEquals(properties, Files.readString(dir.resolve("forward.properties")))
        assertFalse(Files.exists(dir.resolve(DeclaredTypeStore.FILE_NAME)))
    }

    @Test
    fun `compiled jar declarations survive save and both loaders with queryable structure`() = inDirectory { dir ->
        val classes = Files.createDirectory(dir.resolve("classes"))
        val source = dir.resolve("Holder.java")
        Files.writeString(source, """
            package fixture.types;
            import java.util.List;
            import java.util.Map;
            public class Holder<T extends Comparable<T>> {
                public List<String> first;
                public List<String> second;
                public T value;
                public T[][] matrix;
                public Map<String, List<? super Number[]>> complex;
                public List<String> echo(List<String> values) { return values; }
                public T identity(T value) { return value; }
                public T[][] echoMatrix(T[][] values) { return values; }
            }
        """.trimIndent())
        val compiler = assertNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), source.toString()))
        val jar = dir.resolve("fixture.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(JarEntry("fixture/types/Holder.class"))
            Files.copy(classes.resolve("fixture/types/Holder.class"), output)
            output.closeEntry()
        }
        val graph = JavaProjectLoader(LoaderConfig(includePackages = listOf("fixture.types"), buildCallGraph = false)).load(jar)
        try {
            val types = graph.declaredTypes()
            val first = assertNotNull(types.fields[MemberTypeKey("fixture.types.Holder", "first", "Ljava/util/List;")])
            assertEquals(first, types.fields[MemberTypeKey("fixture.types.Holder", "second", "Ljava/util/List;")])
            val method = assertNotNull(types.methods[MemberTypeKey("fixture.types.Holder", "echo", "(Ljava/util/List;)Ljava/util/List;")])
            assertEquals(listOf(first), method.parameterTypes)
            assertEquals(first, method.returnType)
            val output = System.getenv("GRAPHITE_TYPES_FIXTURE")?.let(Path::of) ?: dir.resolve("graph")
            Files.createDirectories(output)
            GraphStore.save(graph, output)
            for (load in listOf<() -> io.johnsonlee.graphite.graph.Graph>({ GraphStore.load(output) }, { GraphStore.loadMapped(output) })) {
                val restored = load()
                try {
                    assertEquals(types, restored.declaredTypes())
                    val executor = CypherExecutor(restored)
                    val row = executor.execute(
                        "MATCH (m:Method) WHERE m.name = 'echo' " +
                            "RETURN m.return_type, m.generic_return_type, m.generic_parameter_types, m.return_type_info"
                    ).rows.single()
                    assertEquals("java.util.List", row["m.return_type"])
                    assertEquals("java.util.List<java.lang.String>", row["m.generic_return_type"])
                    assertEquals(listOf("java.util.List<java.lang.String>"), row["m.generic_parameter_types"])
                    assertEquals(types.info(first), row["m.return_type_info"])
                    val field = executor.execute(
                        "MATCH (f:FieldNode) WHERE f.name = 'complex' RETURN f.generic_type"
                    ).rows.single()
                    assertEquals("java.util.Map<java.lang.String, java.util.List<? super java.lang.Number[]>>", field["f.generic_type"])
                    val matrix = executor.execute(
                        "MATCH (f:FieldNode) WHERE f.name = 'matrix' RETURN f.type, f.generic_type"
                    ).rows.single()
                    assertEquals("java.lang.Comparable[][]", matrix["f.type"])
                    assertEquals("T[][]", matrix["f.generic_type"])
                    val matrixMethod = executor.execute(
                        "MATCH (m:Method) WHERE m.name = 'echoMatrix' RETURN m.return_type, m.generic_return_type"
                    ).rows.single()
                    assertEquals("java.lang.Comparable[][]", matrixMethod["m.return_type"])
                    assertEquals("T[][]", matrixMethod["m.generic_return_type"])
                    assertTrue(executor.execute(
                        "MATCH (p:ParameterNode) WHERE p.generic_type = 'java.util.List<java.lang.String>' RETURN p.type_info"
                    ).rows.any { it["p.type_info"] == types.info(first) })
                } finally { (restored as? AutoCloseable)?.close() }
            }
        } finally { (graph as? AutoCloseable)?.close() }
    }

    @Test
    fun `legacy persisted graph exposes null declared types`() = inDirectory { dir ->
        GraphStore.save(DefaultGraph.Builder().build(), dir)
        assertFalse(Files.exists(dir.resolve(DeclaredTypeStore.FILE_NAME)))
        val loaded = GraphStore.loadMapped(dir)
        try {
            assertEquals(DeclaredTypeTable.EMPTY, loaded.declaredTypes())
            assertNull(loaded.declaredTypes().fields[fields.keys.first()])
        } finally { (loaded as? AutoCloseable)?.close() }
    }

    private fun inDirectory(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("declared-type-test")
        try { block(dir) } finally { dir.toFile().deleteRecursively() }
    }

    private fun rebind(dir: Path) {
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dir.resolve(DeclaredTypeStore.FILE_NAME)))
        Files.writeString(dir.resolve("forward.properties"), "${DeclaredTypeStore.BINDING_KEY}=${HexFormat.of().formatHex(digest)}\n")
    }
}
