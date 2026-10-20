package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.MemberTypeKey
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeclaredTypesExpansionFallbackTest {
    @Test
    fun `legal owner signature exceeding byte expansion falls back without orphan types`() {
        val signature = ownerSignature(5000)
        assertEquals(5402, signature.toByteArray(Charsets.UTF_8).size)
        val descriptor = signature.replace('.', '$')
        val node = declaration().apply {
            visitField(Opcodes.ACC_PUBLIC, "large", descriptor, signature, null)
            goodField(this)
        }
        val table = read(node)
        val id = table.fields.getValue(MemberTypeKey("Example", "large", descriptor))
        assertEquals(descriptor.substring(1, descriptor.lastIndex), table.render(id))
        assertNull(table.types[id].owner)
        assertTrue(table.types.none { it.owner != null })
        assertGoodField(table)
    }

    @Test
    fun `method expansion rejection restores descriptor parameters result and formal bounds`() {
        val signature = ownerSignature(5000)
        val descriptor = "(Ljava/lang/String;)Ljava/lang/Object;"
        val node = declaration().apply {
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "large", descriptor,
                "<T:Ljava/lang/Number;>(Ljava/lang/String;)$signature", null)
            goodField(this)
        }
        val table = read(node)
        val method = table.methods.getValue(MemberTypeKey("Example", "large", descriptor))
        assertEquals(listOf("java.lang.String"), method.parameterTypes.map(table::render))
        assertEquals("java.lang.Object", table.render(method.returnType))
        assertTrue(method.typeParameters.isEmpty())
        assertTrue(table.types.none { it.owner != null || it.name == "java.lang.Number" })
        assertGoodField(table)
    }

    @Test
    fun `class expansion rejection preserves erased superclass and interfaces`() {
        val signature = ownerSignature(5000)
        val erasedName = signature.substring(1, signature.lastIndex).replace('.', '$')
        val node = declaration(signature = "<T:Ljava/lang/Number;>${signature}Ljava/io/Serializable;",
            superName = erasedName, interfaces = arrayOf("java/io/Serializable")).apply { goodField(this) }
        val table = read(node)
        val type = table.classes.getValue("Example")
        assertTrue(type.typeParameters.isEmpty())
        assertEquals(erasedName, table.render(requireNotNull(type.superType)))
        assertEquals(listOf("java.io.Serializable"), type.interfaces.map(table::render))
        assertTrue(table.types.none { it.owner != null || it.name == "java.lang.Number" })
        assertGoodField(table)
    }

    @Test
    fun `owner expansion immediately below byte limit retains full structure`() {
        // 201 rows expand to 201 * (4770 + 205) = 999975 bytes.
        val signature = ownerSignature(4770)
        val descriptor = signature.replace('.', '$')
        val table = read(declaration().apply { visitField(Opcodes.ACC_PUBLIC, "valid", descriptor, signature, null) })
        val id = table.fields.getValue(MemberTypeKey("Example", "valid", descriptor))
        assertEquals(signature.substring(1, signature.lastIndex), table.render(id))
        var current: Int? = id
        var rows = 0
        while (current != null) {
            val type = table.types[current]
            assertEquals("class", type.kind)
            assertEquals("A".repeat(4770) + "\$I".repeat(200 - rows), type.name)
            assertTrue(type.arguments.isEmpty())
            current = type.owner
            rows++
        }
        assertEquals(201, rows)
        assertEquals("class", table.info(id)["kind"])
    }

    @Test
    fun `repeated interned arguments count every occurrence and rollback retains earlier IDs`() {
        val name = "A".repeat(5000)
        val node = declaration(name, "<T:Ljava/lang/Object;>Ljava/lang/Object;").apply {
            visitField(Opcodes.ACC_PUBLIC, "before", "Ljava/lang/Object;", "TT;", null)
            visitField(Opcodes.ACC_PUBLIC, "large", "LTuple;", "LTuple<" + "TT;".repeat(200) + ">;", null)
            visitField(Opcodes.ACC_PUBLIC, "after", "Ljava/lang/Object;", "TT;", null)
            visitField(Opcodes.ACC_PUBLIC, "valid", "LTuple;", "LTuple<" + "TT;".repeat(199) + ">;", null)
        }
        val table = read(node)
        val before = table.fields.getValue(MemberTypeKey(name, "before", "Ljava/lang/Object;"))
        val after = table.fields.getValue(MemberTypeKey(name, "after", "Ljava/lang/Object;"))
        assertEquals(before, after)
        assertEquals(mapOf("kind" to "variable", "name" to "T", "scope" to "class:$name",
            "arguments" to emptyList<Any>()), table.info(before))
        val rejected = table.fields.getValue(MemberTypeKey(name, "large", "LTuple;"))
        assertEquals("Tuple", table.render(rejected))
        assertTrue(table.types[rejected].arguments.isEmpty())
        val valid = table.fields.getValue(MemberTypeKey(name, "valid", "LTuple;"))
        assertEquals(List(199) { before }, table.types[valid].arguments)
        assertEquals(1, table.types.count { it.kind == "variable" })
    }

    private fun ownerSignature(length: Int): String = "L" + "A".repeat(length) + ".I".repeat(200) + ";"

    private fun declaration(
        name: String = "Example",
        signature: String? = null,
        superName: String = "java/lang/Object",
        interfaces: Array<String> = emptyArray()
    ): ClassNode = ClassNode().apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, name, signature, superName, interfaces)
    }

    private fun goodField(node: ClassNode) {
        node.visitField(Opcodes.ACC_PUBLIC, "good", "Ljava/util/List;", "Ljava/util/List<Ljava/lang/String;>;", null)
    }

    private fun assertGoodField(table: DeclaredTypeTable) {
        val id = table.fields.getValue(MemberTypeKey("Example", "good", "Ljava/util/List;"))
        assertEquals("java.util.List<java.lang.String>", table.render(id))
        assertEquals(listOf("java.lang.String"), table.types[id].arguments.map(table::render))
    }

    /** A real constant-pool UTF8 Signature entry must survive serialization and parsing. */
    private fun read(node: ClassNode): DeclaredTypeTable {
        val writer = ClassWriter(0)
        node.accept(writer)
        val parsed = ClassNode()
        ClassReader(writer.toByteArray()).accept(parsed, 0)
        assertEquals(node.signature, parsed.signature)
        assertEquals(node.fields.map { it.signature }, parsed.fields.map { it.signature })
        assertEquals(node.methods.map { it.signature }, parsed.methods.map { it.signature })
        val declaration = ClassDeclarations.from(parsed)
        return DeclaredTypesReader(mapOf(declaration.name to declaration)).build().also { it.validate() }
    }
}
