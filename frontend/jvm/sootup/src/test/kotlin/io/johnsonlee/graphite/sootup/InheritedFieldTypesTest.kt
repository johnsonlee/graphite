package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.MemberTypeKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class InheritedFieldTypesTest {
    private val interfaces = HashSet<String>()
    @Test
    fun `observed child aliases preserve parent type IDs and variable scope`() {
        val parent = declaration("Parent", signature = "<T:Ljava/lang/Object;>Ljava/lang/Object;",
            fields = listOf(MemberDeclaration("value", "Ljava/lang/Object;", "TT;")))
        val declarations = listOf(parent, declaration("Child", "Parent"), declaration("Unobserved", "Parent"))
            .associateBy { it.name }
        val original = DeclaredTypesReader(declarations).build()
        val node = field("Child", "value")
        val bound = InheritedFieldTypes(declarations, original, interfaces).bind(listOf(node))
        val id = original.fields.getValue(key("Parent", "value"))
        assertEquals(id, bound.fields.getValue(key("Child", "value")))
        assertEquals(mapOf("kind" to "variable", "name" to "T", "scope" to "class:Parent",
            "arguments" to emptyList<Any>()), bound.info(id))
        assertEquals("T", bound.render(id))
        assertSame(original.types, bound.types)
        assertSame(original.methods, bound.methods)
        assertSame(original.classes, bound.classes)
        assertFalse(key("Child", "value") in original.fields)
        assertFalse(key("Unobserved", "value") in bound.fields)
        assertEquals(field("Child", "value"), node)
    }

    @Test
    fun `field lookup matches descriptor and preserves exact child shadowing`() {
        val declarations = listOf(
            declaration("Parent", fields = listOf(MemberDeclaration("same", "Ljava/lang/Object;", null))),
            declaration("Child", "Parent", fields = listOf(MemberDeclaration("same", "I", null)))
        ).associateBy { it.name }
        val original = DeclaredTypesReader(declarations).build()
        val bound = InheritedFieldTypes(declarations, original, interfaces).bind(
            listOf(field("Child", "same"), field("Child", "same", "int")))
        assertEquals("java.lang.Object", bound.render(bound.fields.getValue(key("Child", "same"))))
        assertEquals("int", bound.render(bound.fields.getValue(key("Child", "same", "I"))))
        assertEquals(original.fields.getValue(key("Child", "same", "I")), bound.fields[key("Child", "same", "I")])
    }

    @Test
    fun `recursive interface field precedes superclass and preserves static reference`() {
        val declarations = listOf(
            declaration("Top", fields = listOf(MemberDeclaration("shared", "Ljava/util/List;", "Ljava/util/List<Ljava/lang/String;>;")),
                isInterface = true),
            declaration("Middle", interfaces = listOf("Top"), isInterface = true),
            declaration("Parent", fields = listOf(
                MemberDeclaration("shared", "Ljava/util/List;", "Ljava/util/List<Ljava/lang/Integer;>;"))),
            declaration("Child", "Parent", interfaces = listOf("Middle"))
        ).associateBy { it.name }
        val original = DeclaredTypesReader(declarations).build()
        val node = field("Child", "shared", "java.util.List", isStatic = true)
        val bound = InheritedFieldTypes(declarations, original, interfaces).bind(listOf(node))
        val id = bound.fields.getValue(key("Child", "shared", "Ljava/util/List;"))
        assertEquals(original.fields.getValue(key("Top", "shared", "Ljava/util/List;")), id)
        assertEquals("java.util.List<java.lang.String>", bound.render(id))
        assertEquals(true, node.isStatic)
    }

    @Test
    fun `known empty interface diamond does not block superclass fields or search Object`() {
        val declarations = listOf(
            declaration("Top", "MissingObject", isInterface = true),
            declaration("Left", interfaces = listOf("Top"), isInterface = true),
            declaration("Right", interfaces = listOf("Top"), isInterface = true),
            declaration("Parent", fields = listOf(MemberDeclaration("value", "Ljava/lang/Object;", null))),
            declaration("Child", "Parent", interfaces = listOf("Left", "Right"))
        ).associateBy { it.name }
        val original = DeclaredTypesReader(declarations).build()
        val bound = InheritedFieldTypes(declarations, original, interfaces).bind(listOf(field("Child", "value")))
        assertEquals(original.fields.getValue(key("Parent", "value")), bound.fields.getValue(key("Child", "value")))
    }

    @Test
    fun `unknown earlier interface or superclass never guesses a later declaration`() {
        val declarations = listOf(
            declaration("Known", fields = listOf(MemberDeclaration("value", "Ljava/lang/Object;", null)), isInterface = true),
            declaration("Parent", fields = listOf(MemberDeclaration("value", "Ljava/lang/Object;", null))),
            declaration("Child", "Parent", interfaces = listOf("Missing", "Known")),
            declaration("Orphan", "Missing"),
            declaration("Own", "Missing", fields = listOf(MemberDeclaration("value", "Ljava/lang/Object;", null)))
        ).associateBy { it.name }
        val original = DeclaredTypesReader(declarations).build()
        val bound = InheritedFieldTypes(declarations, original, interfaces).bind(
            listOf(field("Child", "value"), field("Orphan", "value"), field("Absent", "value"), field("Own", "value")))
        assertSame(original, bound)
        assertFalse(key("Child", "value") in bound.fields)
        assertFalse(key("Orphan", "value") in bound.fields)
        assertFalse(key("Absent", "value") in bound.fields)
        assertEquals("java.lang.Object", bound.render(bound.fields.getValue(key("Own", "value"))))
    }

    @Test
    fun `interface cycles block later parent and long acyclic hierarchies use no recursion`() {
        val declarations = mutableListOf(
            declaration("Loop", interfaces = listOf("Loop"), isInterface = true),
            declaration("Parent", fields = listOf(MemberDeclaration("value", "Ljava/lang/Object;", null))),
            declaration("Cyclic", "Parent", interfaces = listOf("Loop")))
        repeat(2048) { index -> declarations.add(declaration("Deep$index", if (index == 0) "Parent" else "Deep${index - 1}")) }
        val indexed = declarations.associateBy { it.name }
        val original = DeclaredTypesReader(indexed).build()
        val bound = InheritedFieldTypes(indexed, original, interfaces).bind(listOf(field("Cyclic", "value"), field("Deep2047", "value")))
        assertFalse(key("Cyclic", "value") in bound.fields)
        assertEquals(original.fields.getValue(key("Parent", "value")), bound.fields.getValue(key("Deep2047", "value")))
    }

    private fun declaration(name: String, parent: String? = null, interfaces: List<String> = emptyList(),
                            fields: List<MemberDeclaration> = emptyList(), signature: String? = null,
                            isInterface: Boolean = false): ClassDeclarations {
        if (isInterface) this.interfaces.add(name)
        return ClassDeclarations(name, signature, parent, interfaces, fields, emptyList(), null, null, false)
    }

    private fun field(owner: String, name: String, type: String = "java.lang.Object", isStatic: Boolean = false) =
        FieldNode(NodeId(1), FieldDescriptor(TypeDescriptor(owner), name, TypeDescriptor(type)), isStatic)

    private fun key(owner: String, name: String, descriptor: String = "Ljava/lang/Object;") = MemberTypeKey(owner, name, descriptor)
}
