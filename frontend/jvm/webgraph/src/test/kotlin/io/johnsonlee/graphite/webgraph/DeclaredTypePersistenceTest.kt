package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.graph.ClassTypes
import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.graph.MethodTypes
import io.johnsonlee.graphite.graph.TypeParameter
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
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
        DeclaredTypeStore.saveLegacyV2(table, dir)
        val restored = DeclaredTypeStore.load(dir)
        assertEquals(table, restored)
        assertEquals(2, restored.types.size)
        assertEquals(setOf(1), restored.fields.values.toSet())
        assertEquals(1, restored.methods.values.single().returnType)
        assertEquals("java.util.List<java.lang.String>", restored.render(1))
        DeclaredTypeStore.saveLegacyV2(DeclaredTypeTable.EMPTY, dir)
        assertFalse(Files.exists(dir.resolve(DeclaredTypeStore.FILE_NAME)))
    }

    @Test
    fun `mapped collections decode rows on demand and resolve colliding Unicode keys`() = inDirectory { dir ->
        Files.writeString(dir.resolve("graph.metadata"), "binding")
        val names = listOf("Aa", "BB", "泛型\uD83D\uDE80")
        val keys = names.map { MemberTypeKey("sample.Holder", it, "Ljava/lang/Object;") }
        assertEquals(keys[0].hashCode(), keys[1].hashCode())
        val expected = DeclaredTypeTable(
            listOf(DeclaredType("class", "java.lang.Object"), DeclaredType("class", "java.lang.String")),
            keys.mapIndexed { index, key -> key to index.mod(2) }.toMap(),
            keys.mapIndexed { index, key -> key to MethodTypes(listOf(index.mod(2)), index.mod(2)) }.toMap(),
            names.mapIndexed { index, name ->
                name to ClassTypes(listOf(TypeParameter("T", "class:$name", listOf(index.mod(2)))), index.mod(2), listOf(0))
            }.toMap()
        )
        DeclaredTypeStore.saveLegacyV2(expected, dir)
        val restored = DeclaredTypeStore.load(dir)
        assertEquals(expected, restored)
        assertEquals(restored, expected)
        assertEquals(expected.hashCode(), restored.hashCode())
        assertNotSame(restored.types[0], restored.types[0])
        assertNotSame(restored.methods[keys[0]], restored.methods[keys[0]])
        assertNotSame(restored.classes[names[0]], restored.classes[names[0]])
        assertEquals(keys, restored.fields.keys.toList())
        assertEquals(keys, restored.methods.keys.toList())
        assertEquals(names, restored.classes.keys.toList())
        assertEquals(expected.methods.values.toList(), restored.methods.values.toList())
        assertEquals(expected.classes.values.toList(), restored.classes.values.toList())
        for (key in keys) {
            assertTrue(restored.fields.containsKey(key))
            assertEquals(expected.fields[key], restored.fields[key])
            assertEquals(expected.methods[key], restored.methods[key])
        }
        for (name in names) assertEquals(expected.classes[name], restored.classes[name])
        val missing = keys[0].copy(name = "C#")
        assertEquals(keys[0].hashCode(), missing.hashCode())
        assertFalse(restored.fields.containsKey(missing))
        assertNull(restored.methods[missing])
        assertNull(restored.classes[missing.name])
        assertFailsWith<IndexOutOfBoundsException> { restored.types[-1] }
        assertFailsWith<IndexOutOfBoundsException> { restored.types[restored.types.size] }
        val iterator = restored.methods.entries.iterator()
        repeat(keys.size) { iterator.next() }
        assertFalse(iterator.hasNext())
        assertFailsWith<NoSuchElementException> { iterator.next() }
    }

    @Test
    fun `raw key hashes preserve collisions UTF16 Unicode and arbitrary string boundaries`() = inDirectory { dir ->
        Files.writeString(dir.resolve("graph.metadata"), "binding")
        val names = listOf("", "\u0000", "Aa", "BB", "ascii\u007f", "caf\u00e9", "pre\u0800", "类型", "泛型\uD83D\uDE80")
        val keys = names.map { name ->
            MemberTypeKey("Owner".repeat(40) + name, name, "L$name;")
        }
        val expected = DeclaredTypeTable(
            listOf(DeclaredType("class", "java.lang.Object"), DeclaredType("class", "java.lang.String")),
            keys.mapIndexed { index, key -> key to index.mod(2) }.toMap(),
            keys.mapIndexed { index, key -> key to MethodTypes(listOf(index.mod(2)), index.mod(2)) }.toMap(),
            names.mapIndexed { index, name -> name to ClassTypes(emptyList(), index.mod(2), emptyList()) }.toMap()
        )
        DeclaredTypeStore.saveLegacyV2(expected, dir)
        val original = Files.readAllBytes(dir.resolve(DeclaredTypeStore.FILE_NAME))
        val restored = DeclaredTypeStore.load(dir)
        assertEquals(expected, restored)
        for (key in keys) {
            assertTrue(restored.fields.containsKey(key))
            assertEquals(expected.fields[key], restored.fields[key])
            assertEquals(expected.methods[key], restored.methods[key])
            assertNull(restored.fields[key.copy(owner = key.owner + "missing")])
        }
        for (name in names) assertEquals(expected.classes[name], restored.classes[name])
        assertEquals(keys, restored.fields.keys.toList())
        DeclaredTypeStore.saveLegacyV2(restored, dir)
        assertContentEquals(original, Files.readAllBytes(dir.resolve(DeclaredTypeStore.FILE_NAME)))
    }

    @Test
    fun `raw key hashing still rejects malformed UTF8 after an ASCII prefix in every key section`() = inDirectory { dir ->
        Files.writeString(dir.resolve("graph.metadata"), "binding")
        val key = MemberTypeKey("Owner", "Member", "Ljava/lang/Object;")
        val names = listOf("FieldKeyProbe", "MethodKeyProbe", "ClassKeyProbe")
        val value = DeclaredTypeTable(
            listOf(DeclaredType("class", "java.lang.Object")),
            mapOf(key.copy(name = names[0]) to 0),
            mapOf(key.copy(name = names[1]) to MethodTypes(emptyList(), 0)),
            mapOf(names[2] to ClassTypes(emptyList(), 0, emptyList()))
        )
        DeclaredTypeStore.saveLegacyV2(value, dir)
        val path = dir.resolve(DeclaredTypeStore.FILE_NAME)
        val valid = Files.readAllBytes(path)
        for (name in names) {
            val needle = name.toByteArray(Charsets.UTF_8)
            val offset = (0..valid.size - needle.size).single { start ->
                needle.indices.all { index -> valid[start + index] == needle[index] }
            }
            val malformed = valid.copyOf().also { it[offset + 3] = 0xc3.toByte() }
            Files.write(path, malformed)
            rebind(dir)
            assertFailsWith<CharacterCodingException>(name) { DeclaredTypeStore.load(dir) }
        }
    }

    @Test
    fun `raw key lengths are validated before scanning bytes`() = inDirectory { dir ->
        Files.writeString(dir.resolve("graph.metadata"), "binding")
        DeclaredTypeStore.saveLegacyV2(table, dir)
        val path = dir.resolve(DeclaredTypeStore.FILE_NAME)
        val valid = Files.readAllBytes(path)
        val needle = "first".toByteArray(Charsets.UTF_8)
        val offset = (0..valid.size - needle.size).single { start ->
            needle.indices.all { index -> valid[start + index] == needle[index] }
        }
        for (length in listOf(-1, Int.MAX_VALUE)) {
            val malformed = valid.copyOf().also { ByteBuffer.wrap(it).putInt(offset - Int.SIZE_BYTES, length) }
            Files.write(path, malformed)
            rebind(dir)
            assertEquals("Invalid graph.types string length", assertFailsWith<IllegalArgumentException> {
                DeclaredTypeStore.load(dir)
            }.message)
        }
    }

    @Test
    fun `mapped table can be saved over its own file without changing IDs or bytes`() = inDirectory { dir ->
        Files.writeString(dir.resolve("graph.metadata"), "binding")
        DeclaredTypeStore.saveLegacyV2(table, dir)
        val original = Files.readAllBytes(dir.resolve(DeclaredTypeStore.FILE_NAME))
        val mapped = DeclaredTypeStore.load(dir)
        DeclaredTypeStore.saveLegacyV2(mapped, dir)
        assertContentEquals(original, Files.readAllBytes(dir.resolve(DeclaredTypeStore.FILE_NAME)))
        assertEquals(table, DeclaredTypeStore.load(dir))
        assertEquals(table, mapped)
        inDirectory { other ->
            Files.copy(dir.resolve("graph.metadata"), other.resolve("graph.metadata"))
            DeclaredTypeStore.saveLegacyV2(mapped, other)
            assertContentEquals(original, Files.readAllBytes(other.resolve(DeclaredTypeStore.FILE_NAME)))
            assertEquals(table, DeclaredTypeStore.load(other))
        }
    }

    @Test
    fun `mapped indexes reject duplicate field method and class keys before returning`() = inDirectory { dir ->
        Files.writeString(dir.resolve("graph.metadata"), "binding")
        val first = MemberTypeKey("Owner", "Aa", "I")
        val second = first.copy(name = "BB")
        val base = DeclaredTypeTable(listOf(DeclaredType("primitive", "int")), emptyMap(), emptyMap(), emptyMap())
        val cases = listOf(
            base.copy(fields = linkedMapOf(first to 0, second to 0)) to "field",
            base.copy(methods = linkedMapOf(first to MethodTypes(emptyList(), 0), second to MethodTypes(emptyList(), 0))) to "method",
            base.copy(classes = listOf("Aa", "BB").associateWith { ClassTypes(emptyList(), null, emptyList()) }) to "class"
        )
        for ((value, section) in cases) {
            DeclaredTypeStore.saveLegacyV2(value, dir)
            val path = dir.resolve(DeclaredTypeStore.FILE_NAME)
            val bytes = Files.readAllBytes(path)
            val typeStart = DeclaredTypeWireFixture.dictionaryEnd(bytes)
            // One argument-free primitive type (28 bytes); preceding binding sections are empty.
            val sectionOffset = when (section) { "field" -> 0; "method" -> 4; else -> 8 }
            val firstRow = typeStart + 4 + 28 + 4 + sectionOffset
            val rowBytes = when (section) { "field" -> 16; "method" -> 24; else -> 16 }
            val keyBytes = if (section == "class") 4 else 12
            bytes.copyInto(bytes, firstRow + rowBytes, firstRow, firstRow + keyBytes)
            Files.write(path, bytes)
            rebind(dir)
            assertEquals("Duplicate $section in graph.types", assertFailsWith<IllegalArgumentException> {
                DeclaredTypeStore.load(dir)
            }.message)
        }
    }

    @Test
    fun `mapped tables reject malformed UTF8 shape and references during load`() = inDirectory { dir ->
        Files.writeString(dir.resolve("graph.metadata"), "binding")
        DeclaredTypeStore.saveLegacyV2(table, dir)
        val path = dir.resolve(DeclaredTypeStore.FILE_NAME)
        val valid = Files.readAllBytes(path)
        // First dictionary entry is the first type kind, after header/hash/count/length.
        val invalidUtf8 = valid.copyOf().also { it[44] = 0xff.toByte() }
        Files.write(path, invalidUtf8)
        rebind(dir)
        assertFailsWith<CharacterCodingException> { DeclaredTypeStore.load(dir) }
        val invalidShape = valid.copyOf().also { "array".toByteArray().copyInto(it, 44) }
        Files.write(path, invalidShape)
        rebind(dir)
        assertFailsWith<IllegalArgumentException> { DeclaredTypeStore.load(dir) }
        DeclaredTypeStore.saveLegacyV2(table.copy(methods = emptyMap()), dir)
        val fieldTable = Files.readAllBytes(path)
        // Final field reference is followed by the empty method and class counts.
        ByteBuffer.wrap(fieldTable).putInt(fieldTable.size - 12, table.types.size)
        Files.write(path, fieldTable)
        rebind(dir)
        assertFailsWith<IllegalArgumentException> { DeclaredTypeStore.load(dir) }
    }

    @Test
    fun `mapped table validates cycles depth and expansion before any projection`() = inDirectory { dir ->
        Files.writeString(dir.resolve("graph.metadata"), "binding")
        val cycle = listOf(DeclaredType("array", component = 0))
        val tooDeep = listOf(DeclaredType("class", "A")) +
            List(DeclaredTypeTable.MAX_DEPTH) { DeclaredType("array", component = it) }
        val tooManyNodes = listOf(DeclaredType("class", "A")) +
            List(16) { DeclaredType("class", "A", arguments = listOf(it, it)) }
        val tooManyBytes = listOf(DeclaredType("variable", "T", scope = "class:" + "\u754c".repeat(1_000))) +
            List(9) { DeclaredType("class", "A", arguments = listOf(it, it)) }
        for (version in 1..2) for (types in listOf(cycle, tooDeep, tooManyNodes, tooManyBytes)) {
            DeclaredTypeWireFixture.write(dir, DeclaredTypeTable(types, emptyMap(), emptyMap(), emptyMap()), version)
            assertFailsWith<IllegalArgumentException> { DeclaredTypeStore.load(dir) }
        }
        val forwardReferences = listOf(DeclaredType("array", component = 1), DeclaredType("class", "A"))
        writeUncheckedTypes(dir, forwardReferences)
        val restored = DeclaredTypeStore.load(dir)
        assertEquals(forwardReferences, restored.types)
        assertEquals("A[]", restored.render(0))
    }

    @Test
    fun `invalid references truncated payload and mismatched metadata are rejected`() = inDirectory { dir ->
        Files.writeString(dir.resolve("graph.metadata"), "binding")
        assertFailsWith<IllegalArgumentException> {
            DeclaredTypeStore.saveLegacyV2(table.copy(fields = mapOf(fields.keys.first() to 99)), dir)
        }
        val cyclic = table.copy(types = listOf(DeclaredType("array", component = 0)), fields = emptyMap(), methods = emptyMap())
        assertFailsWith<IllegalArgumentException> { DeclaredTypeStore.saveLegacyV2(cyclic, dir) }
        DeclaredTypeStore.saveLegacyV2(table, dir)
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
        DeclaredTypeStore.saveLegacyV2(table, dir)
        val path = dir.resolve(DeclaredTypeStore.FILE_NAME)
        val valid = Files.readAllBytes(path)
        val corruptions = listOf(
            valid.copyOf().also { ByteBuffer.wrap(it).putInt(0, 0x47545906) } to "Unsupported graph.types header/version",
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
        DeclaredTypeStore.saveLegacyV2(table, dir)
        val original = Files.readAllBytes(dir.resolve(DeclaredTypeStore.FILE_NAME))
        val properties = Files.readString(dir.resolve("forward.properties"))
        val changed = table.copy(types = listOf(DeclaredType("class", "java.lang.Integer"), table.types[1]))
        DeclaredTypeStore.saveLegacyV2(changed, dir)
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
        DeclaredTypeStore.saveLegacyV2(table, dir)
        assertTrue(Files.readString(dir.resolve("forward.properties")).startsWith(properties))
        // An older BVGraph writer replaces forward.properties without the new binding.
        Files.writeString(dir.resolve("forward.properties"), properties)
        assertEquals(DeclaredTypeTable.EMPTY, DeclaredTypeStore.load(dir))
        Files.writeString(dir.resolve(DeclaredTypeStore.FILE_NAME), "orphan is ignored even if damaged")
        assertEquals(DeclaredTypeTable.EMPTY, DeclaredTypeStore.load(dir))
        DeclaredTypeStore.saveLegacyV2(table, dir)
        DeclaredTypeStore.saveLegacyV2(DeclaredTypeTable.EMPTY, dir)
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
            assertEquals(0x47545905, ByteBuffer.wrap(Files.readAllBytes(output.resolve("graph.types"))).int)
            val legacy = System.getenv("GRAPHITE_TYPES_V1_FIXTURE")?.let(Path::of) ?: dir.resolve("legacy")
            Files.createDirectories(legacy)
            Files.list(output).use { files ->
                files.filter(Files::isRegularFile).forEach { path ->
                    Files.copy(path, legacy.resolve(path.fileName), StandardCopyOption.REPLACE_EXISTING)
                }
            }
            DeclaredTypeWireFixture.write(legacy, types, 1)
            val version2 = System.getenv("GRAPHITE_TYPES_V2_FIXTURE")?.let(Path::of) ?: dir.resolve("version2")
            Files.createDirectories(version2)
            Files.list(output).use { files ->
                files.filter(Files::isRegularFile).forEach { path ->
                    Files.copy(path, version2.resolve(path.fileName), StandardCopyOption.REPLACE_EXISTING)
                }
            }
            DeclaredTypeWireFixture.write(version2, types, 2)
            val version3 = System.getenv("GRAPHITE_TYPES_V3_FIXTURE")?.let(Path::of) ?: dir.resolve("version3")
            GraphStore.saveLegacyDeclaredTypesV3(graph, version3)
            assertEquals(0x47545903, ByteBuffer.wrap(Files.readAllBytes(version3.resolve("graph.types"))).int)
            val version4 = System.getenv("GRAPHITE_TYPES_V4_FIXTURE")?.let(Path::of) ?: dir.resolve("version4")
            GraphStore.saveLegacyDeclaredTypesV4(graph, version4)
            assertEquals(0x47545904, ByteBuffer.wrap(Files.readAllBytes(version4.resolve("graph.types"))).int)
            val fixtures = listOf(output, legacy, version2, version3, version4)
            for (fixture in fixtures) for (load in listOf<() -> io.johnsonlee.graphite.graph.Graph>(
                { GraphStore.load(fixture) }, { GraphStore.loadMapped(fixture) }
            )) {
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
                    val stringFields = executor.execute(
                        "MATCH (f:FieldNode) WHERE f.generic_type CONTAINS 'String' RETURN f.name LIMIT 20"
                    ).rows.map { it["f.name"] }.toSet()
                    assertEquals(setOf("first", "second", "complex"), stringFields)
                    val anyFields = executor.execute(
                        "MATCH (f:FieldNode) WHERE ANY(k IN keys(f) WHERE toString(f[k]) CONTAINS 'java.lang.String') " +
                            "RETURN f.name LIMIT 20"
                    ).rows.map { it["f.name"] }.toSet()
                    assertEquals(stringFields, anyFields)
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

    /** Emit the documented wire format directly so malformed expressions bypass save-time validation. */
    private fun writeUncheckedTypes(dir: Path, types: List<DeclaredType>) {
        DataOutputStream(Files.newOutputStream(dir.resolve(DeclaredTypeStore.FILE_NAME))).use { out ->
            fun text(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
            out.writeInt(0x47545901)
            out.write(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dir.resolve("graph.metadata"))))
            out.writeInt(types.size)
            for (type in types) {
                text(type.kind)
                text(type.name)
                text(type.scope)
                out.writeInt(type.owner ?: -1)
                out.writeInt(type.component ?: -1)
                text(type.variance)
                out.writeInt(type.arguments.size)
                type.arguments.forEach(out::writeInt)
            }
            out.writeInt(0) // fields
            out.writeInt(0) // methods
            out.writeInt(0) // classes
        }
        rebind(dir)
    }

    private fun rebind(dir: Path) {
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dir.resolve(DeclaredTypeStore.FILE_NAME)))
        Files.writeString(dir.resolve("forward.properties"), "${DeclaredTypeStore.BINDING_KEY}=${HexFormat.of().formatHex(digest)}\n")
    }
}
