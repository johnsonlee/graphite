package io.johnsonlee.graphite.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypeStructureTest {

    // ========================================================================
    // TypeStructure.formatTypeName
    // ========================================================================

    @Test
    fun `formatTypeName without generics`() {
        val ts = TypeStructure(TypeDescriptor("com.example.User"))
        assertEquals("User", ts.formatTypeName())
    }

    @Test
    fun `formatTypeName with typeArguments map`() {
        val ts = TypeStructure(
            type = TypeDescriptor("com.example.ApiResponse"),
            typeArguments = mapOf("T" to TypeStructure.simple("com.example.User"))
        )
        assertEquals("ApiResponse<User>", ts.formatTypeName())
    }

    @Test
    fun `formatTypeName with TypeDescriptor typeArguments`() {
        val ts = TypeStructure(
            type = TypeDescriptor("java.util.List", listOf(TypeDescriptor("com.example.User")))
        )
        assertEquals("List<User>", ts.formatTypeName())
    }

    @Test
    fun `formatTypeName prefers typeArguments map over TypeDescriptor typeArguments`() {
        val ts = TypeStructure(
            type = TypeDescriptor("java.util.List", listOf(TypeDescriptor("java.lang.Object"))),
            typeArguments = mapOf("T" to TypeStructure.simple("com.example.User"))
        )
        assertEquals("List<User>", ts.formatTypeName())
    }

    // ========================================================================
    // TypeStructure.toTreeString
    // ========================================================================

    @Test
    fun `toTreeString for flat type without fields`() {
        val ts = TypeStructure.simple("com.example.User")
        val result = ts.toTreeString()
        assertTrue(result.startsWith("User"))
    }

    @Test
    fun `toTreeString with nested fields`() {
        val ts = TypeStructure(
            type = TypeDescriptor("com.example.Response"),
            fields = mapOf(
                "name" to FieldStructure("name", TypeDescriptor("java.lang.String")),
                "age" to FieldStructure("age", TypeDescriptor("int"))
            )
        )
        val result = ts.toTreeString()
        assertTrue(result.contains("name: String"))
        assertTrue(result.contains("age: int"))
        assertTrue(result.contains("├── ") || result.contains("└── "))
    }

    // ========================================================================
    // TypeStructure properties
    // ========================================================================

    @Test
    fun `simpleName property`() {
        val ts = TypeStructure.simple("com.example.User")
        assertEquals("User", ts.simpleName)
    }

    @Test
    fun `className property`() {
        val ts = TypeStructure.simple("com.example.User")
        assertEquals("com.example.User", ts.className)
    }

    @Test
    fun `isGeneric true when typeArguments present`() {
        val ts = TypeStructure(
            type = TypeDescriptor("com.example.List"),
            typeArguments = mapOf("T" to TypeStructure.simple("com.example.User"))
        )
        assertTrue(ts.isGeneric)
    }

    @Test
    fun `isGeneric false when no typeArguments`() {
        val ts = TypeStructure.simple("com.example.User")
        assertFalse(ts.isGeneric)
    }

    @Test
    fun `hasObjectFields true when field declared as Object`() {
        val ts = TypeStructure(
            type = TypeDescriptor("com.example.Response"),
            fields = mapOf(
                "data" to FieldStructure("data", TypeDescriptor("java.lang.Object"))
            )
        )
        assertTrue(ts.hasObjectFields)
    }

    @Test
    fun `hasObjectFields false when no Object fields`() {
        val ts = TypeStructure(
            type = TypeDescriptor("com.example.Response"),
            fields = mapOf(
                "data" to FieldStructure("data", TypeDescriptor("com.example.User"))
            )
        )
        assertFalse(ts.hasObjectFields)
    }

    // ========================================================================
    // Factory methods
    // ========================================================================

    @Test
    fun `simple factory creates structure without fields`() {
        val ts = TypeStructure.simple("com.example.User")
        assertEquals("com.example.User", ts.className)
        assertTrue(ts.fields.isEmpty())
        assertTrue(ts.typeArguments.isEmpty())
    }

    @Test
    fun `from factory creates structure from TypeDescriptor`() {
        val td = TypeDescriptor("com.example.User", listOf(TypeDescriptor("java.lang.String")))
        val ts = TypeStructure.from(td)
        assertEquals("com.example.User", ts.className)
        assertEquals(1, ts.type.typeArguments.size)
    }

    // ========================================================================
    // FieldStructure
    // ========================================================================

    @Test
    fun `effectiveJsonName returns jsonName when present`() {
        val fs = FieldStructure("myField", TypeDescriptor("int"), jsonName = "my_field")
        assertEquals("my_field", fs.effectiveJsonName)
    }

    @Test
    fun `effectiveJsonName returns field name when no jsonName`() {
        val fs = FieldStructure("myField", TypeDescriptor("int"))
        assertEquals("myField", fs.effectiveJsonName)
    }

    @Test
    fun `hasPolymorphicAssignment true when actual types differ from declared`() {
        val fs = FieldStructure(
            "data",
            TypeDescriptor("java.lang.Object"),
            actualTypes = setOf(TypeStructure.simple("com.example.User"))
        )
        assertTrue(fs.hasPolymorphicAssignment)
    }

    @Test
    fun `hasPolymorphicAssignment false when actual types same as declared`() {
        val fs = FieldStructure(
            "data",
            TypeDescriptor("com.example.User"),
            actualTypes = setOf(TypeStructure(TypeDescriptor("com.example.User")))
        )
        assertFalse(fs.hasPolymorphicAssignment)
    }

    @Test
    fun `hasPolymorphicAssignment false when no actual types`() {
        val fs = FieldStructure("data", TypeDescriptor("com.example.User"))
        assertFalse(fs.hasPolymorphicAssignment)
    }

    @Test
    fun `isDeclaredAsObject true for Object type`() {
        val fs = FieldStructure("data", TypeDescriptor("java.lang.Object"))
        assertTrue(fs.isDeclaredAsObject)
    }

    @Test
    fun `isDeclaredAsObject false for non-Object type`() {
        val fs = FieldStructure("data", TypeDescriptor("com.example.User"))
        assertFalse(fs.isDeclaredAsObject)
    }

    // ========================================================================
    // FieldStructure.toTreeString
    // ========================================================================

    @Test
    fun `toTreeString with empty actual types shows declared type`() {
        val fs = FieldStructure("data", TypeDescriptor("com.example.User"))
        val result = fs.toTreeString()
        assertTrue(result.contains("User"))
    }

    @Test
    fun `toTreeString with single actual type same as declared`() {
        val fs = FieldStructure(
            "data",
            TypeDescriptor("com.example.User"),
            actualTypes = setOf(TypeStructure(TypeDescriptor("com.example.User")))
        )
        val result = fs.toTreeString()
        assertTrue(result.contains("User"))
        assertFalse(result.contains("→"))
    }

    @Test
    fun `toTreeString with single actual type different from declared`() {
        val fs = FieldStructure(
            "data",
            TypeDescriptor("java.lang.Object"),
            actualTypes = setOf(TypeStructure.simple("com.example.User"))
        )
        val result = fs.toTreeString()
        assertTrue(result.contains("Object"))
        assertTrue(result.contains("→"))
        assertTrue(result.contains("User"))
    }

    @Test
    fun `toTreeString with multiple actual types`() {
        val fs = FieldStructure(
            "data",
            TypeDescriptor("java.lang.Object"),
            actualTypes = setOf(
                TypeStructure.simple("com.example.User"),
                TypeStructure.simple("com.example.Admin")
            )
        )
        val result = fs.toTreeString()
        assertTrue(result.contains("→ ["))
        assertTrue(result.contains("|"))
    }

    @Test
    fun `toTreeString with generic declared type`() {
        val fs = FieldStructure(
            "items",
            TypeDescriptor("java.util.List", listOf(TypeDescriptor("com.example.User")))
        )
        val result = fs.toTreeString()
        assertTrue(result.contains("List<User>"))
    }

    // ========================================================================
    // TypeHierarchyResult
    // ========================================================================

    @Test
    fun `allReturnTypes returns set of type descriptors`() {
        val method = MethodDescriptor(TypeDescriptor("Foo"), "m", emptyList(), TypeDescriptor("java.lang.Object"))
        val result = TypeHierarchyResult(
            method = method,
            returnStructures = setOf(
                TypeStructure.simple("com.example.User"),
                TypeStructure.simple("com.example.Admin")
            )
        )
        val types = result.allReturnTypes()
        assertEquals(2, types.size)
        assertTrue(types.any { it.className == "com.example.User" })
        assertTrue(types.any { it.className == "com.example.Admin" })
    }

    @Test
    fun `allFieldTypes collects recursive field types`() {
        val method = MethodDescriptor(TypeDescriptor("Foo"), "m", emptyList(), TypeDescriptor("java.lang.Object"))
        val nested = TypeStructure(
            type = TypeDescriptor("com.example.Address"),
            fields = mapOf(
                "city" to FieldStructure("city", TypeDescriptor("java.lang.String"))
            )
        )
        val structure = TypeStructure(
            type = TypeDescriptor("com.example.User"),
            fields = mapOf(
                "address" to FieldStructure(
                    "address",
                    TypeDescriptor("com.example.Address"),
                    actualTypes = setOf(nested)
                )
            )
        )
        val result = TypeHierarchyResult(method = method, returnStructures = setOf(structure))
        val fieldTypes = result.allFieldTypes()
        assertTrue(fieldTypes.any { it.className == "com.example.Address" })
        assertTrue(fieldTypes.any { it.className == "java.lang.String" })
    }

    @Test
    fun `toTreeString includes method and return structures`() {
        val method = MethodDescriptor(TypeDescriptor("Foo"), "getUser", emptyList(), TypeDescriptor("java.lang.Object"))
        val result = TypeHierarchyResult(
            method = method,
            returnStructures = setOf(TypeStructure.simple("com.example.User"))
        )
        val output = result.toTreeString()
        assertTrue(output.contains("Method: getUser()"))
        assertTrue(output.contains("Declared return type: java.lang.Object"))
        assertTrue(output.contains("User"))
    }

    // ========================================================================
    // TypeHierarchyConfig
    // ========================================================================

    @Test
    fun `TypeHierarchyConfig defaults`() {
        val config = TypeHierarchyConfig()
        assertEquals(10, config.maxDepth)
        assertTrue(config.resolveObjectFields)
        assertTrue(config.resolveGenerics)
        assertTrue(config.interProcedural)
        assertTrue(config.includePackages.isEmpty())
        assertEquals(5, config.excludePackages.size)
    }

    // ========================================================================
    // FieldStructure.toTreeString with nested fields in single actual type
    // ========================================================================

    @Test
    fun `toTreeString with single actual type that has nested fields`() {
        val innerField = FieldStructure("city", TypeDescriptor("java.lang.String"))
        val innerField2 = FieldStructure("zip", TypeDescriptor("java.lang.String"))
        val actual = TypeStructure(
            type = TypeDescriptor("com.example.Address"),
            fields = mapOf("city" to innerField, "zip" to innerField2)
        )
        val fs = FieldStructure(
            "address",
            TypeDescriptor("java.lang.Object"),
            actualTypes = setOf(actual)
        )
        val result = fs.toTreeString()
        assertTrue(result.contains("Object"))
        assertTrue(result.contains("→"))
        assertTrue(result.contains("Address"))
        assertTrue(result.contains("city:"))
        assertTrue(result.contains("zip:"))
        assertTrue(result.contains("├── ") || result.contains("└── "))
    }
}
