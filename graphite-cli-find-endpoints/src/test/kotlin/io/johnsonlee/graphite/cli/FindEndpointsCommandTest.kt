package io.johnsonlee.graphite.cli

import com.google.gson.JsonParser
import io.johnsonlee.graphite.core.*
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FindEndpointsCommandTest {

    private val cmd = FindEndpointsCommand()

    // ========================================================================
    // formatDeclaredType
    // ========================================================================

    @Test
    fun `formatDeclaredType returns simple name for non-generic type`() {
        val type = TypeDescriptor("java.lang.String")
        assertEquals("String", cmd.formatDeclaredType(type))
    }

    @Test
    fun `formatDeclaredType returns name with generics`() {
        val type = TypeDescriptor(
            "java.util.List",
            typeArguments = listOf(TypeDescriptor("com.example.User"))
        )
        assertEquals("List<User>", cmd.formatDeclaredType(type))
    }

    // ========================================================================
    // formatText
    // ========================================================================

    @Test
    fun `formatText shows endpoint listing with path and handler`() {
        val endpoints = listOf(makeEndpoint("/api/users", HttpMethod.GET, "UserController", "getUsers"))
        val output = cmd.formatText(endpoints, emptyMap())

        assertTrue(output.contains("Found 1 endpoint(s):"))
        assertTrue(output.contains("GET"))
        assertTrue(output.contains("/api/users"))
        assertTrue(output.contains("UserController.getUsers()"))
    }

    @Test
    fun `formatText groups endpoints by base path`() {
        val endpoints = listOf(
            makeEndpoint("/api/users", HttpMethod.GET, "UserController", "list"),
            makeEndpoint("/api/users/profile", HttpMethod.GET, "UserController", "profile"),
            makeEndpoint("/api/orders", HttpMethod.POST, "OrderController", "create")
        )
        val output = cmd.formatText(endpoints, emptyMap())

        assertTrue(output.contains("Found 3 endpoint(s):"))
        assertTrue(output.contains("Summary: 3 endpoint(s)"))
    }

    @Test
    fun `formatText shows declared return type`() {
        val endpoints = listOf(makeEndpoint("/api/users", HttpMethod.GET, "UserController", "getUsers", "com.example.User"))
        val output = cmd.formatText(endpoints, emptyMap())

        assertTrue(output.contains("Declared: User"))
    }

    @Test
    fun `formatText shows actual return type structure when available`() {
        val endpoint = makeEndpoint("/api/users", HttpMethod.GET, "UserController", "getUsers")
        val typeStructure = TypeStructure(
            type = TypeDescriptor("com.example.ApiResponse"),
            typeArguments = mapOf("T" to TypeStructure.simple("com.example.User"))
        )
        val returnTypes = mapOf(
            "/api/users" to TypeHierarchyResult(
                method = endpoint.method,
                returnStructures = setOf(typeStructure)
            )
        )

        val output = cmd.formatText(listOf(endpoint), returnTypes)
        assertTrue(output.contains("Actual:"))
        assertTrue(output.contains("ApiResponse<User>"))
    }

    // ========================================================================
    // formatJsonSchema
    // ========================================================================

    @Test
    fun `formatJsonSchema produces valid OpenAPI structure`() {
        val endpoints = listOf(makeEndpoint("/api/users", HttpMethod.GET, "UserController", "getUsers"))
        val json = cmd.formatJsonSchema(endpoints, emptyMap())
        val root = JsonParser.parseString(json).asJsonObject

        assertEquals("3.0.3", root.get("openapi").asString)
        assertTrue(root.has("info"))
        assertTrue(root.has("paths"))

        val paths = root.getAsJsonObject("paths")
        assertTrue(paths.has("/api/users"))
        val getUsersOp = paths.getAsJsonObject("/api/users").getAsJsonObject("get")
        assertTrue(getUsersOp.has("operationId"))
        assertTrue(getUsersOp.has("responses"))
    }

    @Test
    fun `formatJsonSchema includes type schemas in components`() {
        val endpoint = makeEndpoint("/api/users", HttpMethod.GET, "UserController", "getUsers")
        val userType = TypeStructure(
            type = TypeDescriptor("com.example.User"),
            fields = mapOf(
                "name" to FieldStructure("name", TypeDescriptor("java.lang.String")),
                "age" to FieldStructure("age", TypeDescriptor("int"))
            )
        )
        val returnTypes = mapOf(
            "/api/users" to TypeHierarchyResult(
                method = endpoint.method,
                returnStructures = setOf(userType)
            )
        )

        val json = cmd.formatJsonSchema(listOf(endpoint), returnTypes)
        val root = JsonParser.parseString(json).asJsonObject

        assertTrue(root.has("components"))
        val schemas = root.getAsJsonObject("components").getAsJsonObject("schemas")
        assertTrue(schemas.has("User"))
        val userSchema = schemas.getAsJsonObject("User")
        assertEquals("object", userSchema.get("type").asString)
    }

    @Test
    fun `formatJsonSchema with empty endpoints produces empty paths`() {
        val json = cmd.formatJsonSchema(emptyList(), emptyMap())
        val root = JsonParser.parseString(json).asJsonObject

        assertEquals("3.0.3", root.get("openapi").asString)
        val paths = root.getAsJsonObject("paths")
        assertEquals(0, paths.size())
        assertFalse(root.has("components"))
    }

    @Test
    fun `formatJsonSchema with multiple endpoints and HTTP methods`() {
        val endpoints = listOf(
            makeEndpoint("/api/users", HttpMethod.GET, "UserController", "list"),
            makeEndpoint("/api/users", HttpMethod.POST, "UserController", "create")
        )
        val json = cmd.formatJsonSchema(endpoints, emptyMap())
        val root = JsonParser.parseString(json).asJsonObject

        val usersPath = root.getAsJsonObject("paths").getAsJsonObject("/api/users")
        assertTrue(usersPath.has("get"))
        assertTrue(usersPath.has("post"))
    }

    // ========================================================================
    // buildFieldSchema
    // ========================================================================

    @Test
    fun `buildFieldSchema maps int to integer int32`() {
        val field = FieldStructure("count", TypeDescriptor("int"))
        val schema = cmd.buildFieldSchema(field, mutableMapOf(), mutableSetOf(), mutableSetOf(), 0)

        assertEquals("integer", schema["type"])
        assertEquals("int32", schema["format"])
    }

    @Test
    fun `buildFieldSchema maps String to string`() {
        val field = FieldStructure("name", TypeDescriptor("java.lang.String"))
        val schema = cmd.buildFieldSchema(field, mutableMapOf(), mutableSetOf(), mutableSetOf(), 0)

        assertEquals("string", schema["type"])
    }

    @Test
    fun `buildFieldSchema maps boolean to boolean`() {
        val field = FieldStructure("active", TypeDescriptor("java.lang.Boolean"))
        val schema = cmd.buildFieldSchema(field, mutableMapOf(), mutableSetOf(), mutableSetOf(), 0)

        assertEquals("boolean", schema["type"])
    }

    @Test
    fun `buildFieldSchema maps long to integer int64`() {
        val field = FieldStructure("id", TypeDescriptor("java.lang.Long"))
        val schema = cmd.buildFieldSchema(field, mutableMapOf(), mutableSetOf(), mutableSetOf(), 0)

        assertEquals("integer", schema["type"])
        assertEquals("int64", schema["format"])
    }

    @Test
    fun `buildFieldSchema maps List to array`() {
        val field = FieldStructure("items", TypeDescriptor("java.util.List"))
        val schema = cmd.buildFieldSchema(field, mutableMapOf(), mutableSetOf(), mutableSetOf(), 0)

        assertEquals("array", schema["type"])
        assertTrue(schema.containsKey("items"))
    }

    @Test
    fun `buildFieldSchema maps Map to object with additionalProperties`() {
        val field = FieldStructure("metadata", TypeDescriptor("java.util.Map"))
        val schema = cmd.buildFieldSchema(field, mutableMapOf(), mutableSetOf(), mutableSetOf(), 0)

        assertEquals("object", schema["type"])
        assertTrue(schema.containsKey("additionalProperties"))
    }

    @Test
    fun `buildFieldSchema maps Date to string date`() {
        val field = FieldStructure("createdAt", TypeDescriptor("java.util.Date"))
        val schema = cmd.buildFieldSchema(field, mutableMapOf(), mutableSetOf(), mutableSetOf(), 0)

        assertEquals("string", schema["type"])
        assertEquals("date", schema["format"])
    }

    @Test
    fun `buildFieldSchema maps LocalDateTime to string date-time`() {
        val field = FieldStructure("updatedAt", TypeDescriptor("java.time.LocalDateTime"))
        val schema = cmd.buildFieldSchema(field, mutableMapOf(), mutableSetOf(), mutableSetOf(), 0)

        assertEquals("string", schema["type"])
        assertEquals("date-time", schema["format"])
    }

    @Test
    fun `buildFieldSchema maps float to number float`() {
        val field = FieldStructure("score", TypeDescriptor("java.lang.Float"))
        val schema = cmd.buildFieldSchema(field, mutableMapOf(), mutableSetOf(), mutableSetOf(), 0)

        assertEquals("number", schema["type"])
        assertEquals("float", schema["format"])
    }

    @Test
    fun `buildFieldSchema maps double to number double`() {
        val field = FieldStructure("amount", TypeDescriptor("java.lang.Double"))
        val schema = cmd.buildFieldSchema(field, mutableMapOf(), mutableSetOf(), mutableSetOf(), 0)

        assertEquals("number", schema["type"])
        assertEquals("double", schema["format"])
    }

    // ========================================================================
    // buildTypeSchema
    // ========================================================================

    @Test
    fun `buildTypeSchema returns ref for complex type with fields`() {
        val structure = TypeStructure(
            type = TypeDescriptor("com.example.User"),
            fields = mapOf(
                "name" to FieldStructure("name", TypeDescriptor("java.lang.String"))
            )
        )
        val schemas = mutableMapOf<String, Any>()
        val result = cmd.buildTypeSchema(structure, schemas, mutableSetOf(), mutableSetOf(), 0)

        assertEquals("#/components/schemas/User", result["\$ref"])
        assertTrue(schemas.containsKey("User"))
    }

    @Test
    fun `buildTypeSchema handles circular reference`() {
        val structure = TypeStructure(
            type = TypeDescriptor("com.example.TreeNode"),
            fields = mapOf(
                "value" to FieldStructure("value", TypeDescriptor("java.lang.String"))
            )
        )
        val visited = mutableSetOf("com.example.TreeNode")
        val result = cmd.buildTypeSchema(structure, mutableMapOf(), mutableSetOf(), visited, 0)

        assertEquals("#/components/schemas/TreeNode", result["\$ref"])
    }

    // ========================================================================
    // call() error handling
    // ========================================================================

    @Test
    fun `call returns 1 when input path does not exist`() {
        val cmd = FindEndpointsCommand()
        cmd.input = Path.of("/nonexistent/path/to/nothing.jar")

        val exitCode = cmd.call()
        assertEquals(1, exitCode)
    }

    // ========================================================================
    // formatTypeStructure
    // ========================================================================

    @Test
    fun `formatTypeStructure with flat structure no fields`() {
        val structure = TypeStructure.simple("com.example.User")
        val sb = StringBuilder()
        cmd.formatTypeStructure(structure, sb, "", mutableSetOf(), 0)
        assertEquals("", sb.toString()) // No fields = no output
    }

    @Test
    fun `formatTypeStructure with nested fields`() {
        val structure = TypeStructure(
            type = TypeDescriptor("com.example.User"),
            fields = mapOf(
                "name" to FieldStructure("name", TypeDescriptor("java.lang.String")),
                "age" to FieldStructure("age", TypeDescriptor("int"))
            )
        )
        val sb = StringBuilder()
        cmd.formatTypeStructure(structure, sb, "", mutableSetOf(), 0)
        val output = sb.toString()
        assertTrue(output.contains("name: String"))
        assertTrue(output.contains("age: int"))
    }

    @Test
    fun `formatTypeStructure prevents circular reference`() {
        val structure = TypeStructure(
            type = TypeDescriptor("com.example.Node"),
            fields = mapOf(
                "value" to FieldStructure("value", TypeDescriptor("java.lang.String"))
            )
        )
        val visited = mutableSetOf("com.example.Node")
        val sb = StringBuilder()
        cmd.formatTypeStructure(structure, sb, "", visited, 0)
        assertTrue(sb.toString().contains("circular reference"))
    }

    @Test
    fun `formatTypeStructure respects depth limit`() {
        val structure = TypeStructure(
            type = TypeDescriptor("com.example.Deep"),
            fields = mapOf(
                "value" to FieldStructure("value", TypeDescriptor("java.lang.String"))
            )
        )
        val sb = StringBuilder()
        cmd.formatTypeStructure(structure, sb, "", mutableSetOf(), 11)
        assertEquals("", sb.toString()) // Over depth limit, no output
    }

    @Test
    fun `formatTypeStructure filters JsonIgnored fields`() {
        val structure = TypeStructure(
            type = TypeDescriptor("com.example.User"),
            fields = mapOf(
                "name" to FieldStructure("name", TypeDescriptor("java.lang.String")),
                "secret" to FieldStructure("secret", TypeDescriptor("java.lang.String"), isJsonIgnored = true)
            )
        )
        val sb = StringBuilder()
        cmd.formatTypeStructure(structure, sb, "", mutableSetOf(), 0)
        val output = sb.toString()
        assertTrue(output.contains("name:"))
        assertFalse(output.contains("secret:"))
    }

    @Test
    fun `formatTypeStructure uses effectiveJsonName`() {
        val structure = TypeStructure(
            type = TypeDescriptor("com.example.User"),
            fields = mapOf(
                "userName" to FieldStructure("userName", TypeDescriptor("java.lang.String"), jsonName = "user_name")
            )
        )
        val sb = StringBuilder()
        cmd.formatTypeStructure(structure, sb, "", mutableSetOf(), 0)
        assertTrue(sb.toString().contains("user_name:"))
    }

    // ========================================================================
    // formatFieldStructure
    // ========================================================================

    @Test
    fun `formatFieldStructure with no actual types shows declared type`() {
        val field = FieldStructure("name", TypeDescriptor("java.lang.String"))
        val sb = StringBuilder()
        cmd.formatFieldStructure(field, sb, "", mutableSetOf(), 0)
        assertTrue(sb.toString().contains("String"))
    }

    @Test
    fun `formatFieldStructure with single actual type same as declared`() {
        val field = FieldStructure(
            "name", TypeDescriptor("com.example.User"),
            actualTypes = setOf(TypeStructure(TypeDescriptor("com.example.User")))
        )
        val sb = StringBuilder()
        cmd.formatFieldStructure(field, sb, "", mutableSetOf(), 0)
        val output = sb.toString()
        assertTrue(output.contains("User"))
        assertFalse(output.contains("→"))
    }

    @Test
    fun `formatFieldStructure with single actual type different from declared`() {
        val field = FieldStructure(
            "data", TypeDescriptor("java.lang.Object"),
            actualTypes = setOf(TypeStructure.simple("com.example.UserData"))
        )
        val sb = StringBuilder()
        cmd.formatFieldStructure(field, sb, "", mutableSetOf(), 0)
        val output = sb.toString()
        assertTrue(output.contains("Object"))
        assertTrue(output.contains("→"))
        assertTrue(output.contains("UserData"))
    }

    @Test
    fun `formatFieldStructure with multiple actual types`() {
        val field = FieldStructure(
            "data", TypeDescriptor("java.lang.Object"),
            actualTypes = setOf(
                TypeStructure.simple("com.example.User"),
                TypeStructure.simple("com.example.Admin")
            )
        )
        val sb = StringBuilder()
        cmd.formatFieldStructure(field, sb, "", mutableSetOf(), 0)
        val output = sb.toString()
        assertTrue(output.contains("→ ["))
        assertTrue(output.contains("|"))
    }

    // ========================================================================
    // buildFieldSchema additional tests
    // ========================================================================

    @Test
    fun `buildFieldSchema maps Instant to string date-time`() {
        val field = FieldStructure("timestamp", TypeDescriptor("java.time.Instant"))
        val schema = cmd.buildFieldSchema(field, mutableMapOf(), mutableSetOf(), mutableSetOf(), 0)
        assertEquals("string", schema["type"])
        assertEquals("date-time", schema["format"])
    }

    @Test
    fun `buildFieldSchema maps BigDecimal to number double`() {
        val field = FieldStructure("amount", TypeDescriptor("java.math.BigDecimal"))
        val schema = cmd.buildFieldSchema(field, mutableMapOf(), mutableSetOf(), mutableSetOf(), 0)
        assertEquals("number", schema["type"])
        assertEquals("double", schema["format"])
    }

    @Test
    fun `buildFieldSchema maps Object with actualTypes`() {
        val actual = TypeStructure(
            type = TypeDescriptor("com.example.User"),
            fields = mapOf("name" to FieldStructure("name", TypeDescriptor("java.lang.String")))
        )
        val field = FieldStructure(
            "data", TypeDescriptor("java.lang.Object"),
            actualTypes = setOf(actual)
        )
        val schemas = mutableMapOf<String, Any>()
        val schema = cmd.buildFieldSchema(field, schemas, mutableSetOf(), mutableSetOf(), 0)
        assertTrue(schema.containsKey("\$ref") || schema["type"] == "object")
    }

    @Test
    fun `buildFieldSchema maps Object without actualTypes to plain object`() {
        val field = FieldStructure("data", TypeDescriptor("java.lang.Object"))
        val schema = cmd.buildFieldSchema(field, mutableMapOf(), mutableSetOf(), mutableSetOf(), 0)
        assertEquals("object", schema["type"])
    }

    @Test
    fun `buildFieldSchema maps complex type with actualTypes`() {
        val actual = TypeStructure(
            type = TypeDescriptor("com.example.Address"),
            fields = mapOf("city" to FieldStructure("city", TypeDescriptor("java.lang.String")))
        )
        val field = FieldStructure(
            "address", TypeDescriptor("com.example.Address"),
            actualTypes = setOf(actual)
        )
        val schemas = mutableMapOf<String, Any>()
        val schema = cmd.buildFieldSchema(field, schemas, mutableSetOf(), mutableSetOf(), 0)
        assertTrue(schema.containsKey("\$ref"))
    }

    @Test
    fun `buildFieldSchema maps complex type without actualTypes to object with description`() {
        val field = FieldStructure("address", TypeDescriptor("com.example.Address"))
        val schema = cmd.buildFieldSchema(field, mutableMapOf(), mutableSetOf(), mutableSetOf(), 0)
        assertEquals("object", schema["type"])
        assertEquals("Address", schema["description"])
    }

    @Test
    fun `buildFieldSchema maps LocalDate to string date`() {
        val field = FieldStructure("birthday", TypeDescriptor("java.time.LocalDate"))
        val schema = cmd.buildFieldSchema(field, mutableMapOf(), mutableSetOf(), mutableSetOf(), 0)
        assertEquals("string", schema["type"])
        assertEquals("date", schema["format"])
    }

    @Test
    fun `buildFieldSchema maps ZonedDateTime to string date-time`() {
        val field = FieldStructure("created", TypeDescriptor("java.time.ZonedDateTime"))
        val schema = cmd.buildFieldSchema(field, mutableMapOf(), mutableSetOf(), mutableSetOf(), 0)
        assertEquals("string", schema["type"])
        assertEquals("date-time", schema["format"])
    }

    @Test
    fun `buildFieldSchema maps char to string`() {
        val field = FieldStructure("initial", TypeDescriptor("char"))
        val schema = cmd.buildFieldSchema(field, mutableMapOf(), mutableSetOf(), mutableSetOf(), 0)
        assertEquals("string", schema["type"])
    }

    @Test
    fun `buildFieldSchema maps Integer to integer int32`() {
        val field = FieldStructure("count", TypeDescriptor("java.lang.Integer"))
        val schema = cmd.buildFieldSchema(field, mutableMapOf(), mutableSetOf(), mutableSetOf(), 0)
        assertEquals("integer", schema["type"])
        assertEquals("int32", schema["format"])
    }

    @Test
    fun `buildFieldSchema maps short to integer int32`() {
        val field = FieldStructure("count", TypeDescriptor("short"))
        val schema = cmd.buildFieldSchema(field, mutableMapOf(), mutableSetOf(), mutableSetOf(), 0)
        assertEquals("integer", schema["type"])
        assertEquals("int32", schema["format"])
    }

    @Test
    fun `buildFieldSchema maps Collection to array`() {
        val field = FieldStructure("items", TypeDescriptor("java.util.Collection"))
        val schema = cmd.buildFieldSchema(field, mutableMapOf(), mutableSetOf(), mutableSetOf(), 0)
        assertEquals("array", schema["type"])
    }

    @Test
    fun `buildFieldSchema maps Set to array`() {
        val field = FieldStructure("items", TypeDescriptor("java.util.Set"))
        val schema = cmd.buildFieldSchema(field, mutableMapOf(), mutableSetOf(), mutableSetOf(), 0)
        assertEquals("array", schema["type"])
    }

    @Test
    fun `buildFieldSchema maps List with actual types to array with ref items`() {
        val elementType = TypeStructure(
            type = TypeDescriptor("com.example.User"),
            fields = mapOf("name" to FieldStructure("name", TypeDescriptor("java.lang.String")))
        )
        val field = FieldStructure(
            "users", TypeDescriptor("java.util.List"),
            actualTypes = setOf(elementType)
        )
        val schemas = mutableMapOf<String, Any>()
        val schema = cmd.buildFieldSchema(field, schemas, mutableSetOf(), mutableSetOf(), 0)
        assertEquals("array", schema["type"])
        @Suppress("UNCHECKED_CAST")
        val items = schema["items"] as Map<String, Any>
        assertTrue(items.containsKey("\$ref"))
    }

    @Test
    fun `buildFieldSchema maps array type to array`() {
        val field = FieldStructure("data", TypeDescriptor("byte[]"))
        val schema = cmd.buildFieldSchema(field, mutableMapOf(), mutableSetOf(), mutableSetOf(), 0)
        assertEquals("array", schema["type"])
    }

    @Test
    fun `buildFieldSchema maps primitive boolean to boolean`() {
        val field = FieldStructure("active", TypeDescriptor("boolean"))
        val schema = cmd.buildFieldSchema(field, mutableMapOf(), mutableSetOf(), mutableSetOf(), 0)
        assertEquals("boolean", schema["type"])
    }

    // ========================================================================
    // buildTypeSchema additional tests
    // ========================================================================

    @Test
    fun `buildTypeSchema returns ref for already processed type`() {
        val structure = TypeStructure(
            type = TypeDescriptor("com.example.User"),
            fields = mapOf("name" to FieldStructure("name", TypeDescriptor("java.lang.String")))
        )
        val processedTypes = mutableSetOf("User")
        val result = cmd.buildTypeSchema(structure, mutableMapOf(), processedTypes, mutableSetOf(), 0)
        assertEquals("#/components/schemas/User", result["\$ref"])
    }

    @Test
    fun `buildTypeSchema returns simple object for type without fields`() {
        val structure = TypeStructure.simple("com.example.Marker")
        val result = cmd.buildTypeSchema(structure, mutableMapOf(), mutableSetOf(), mutableSetOf(), 0)
        assertEquals("object", result["type"])
    }

    @Test
    fun `buildTypeSchema includes generic description`() {
        val structure = TypeStructure(
            type = TypeDescriptor("com.example.Response"),
            typeArguments = mapOf("T" to TypeStructure.simple("com.example.User")),
            fields = mapOf("data" to FieldStructure("data", TypeDescriptor("java.lang.Object")))
        )
        val schemas = mutableMapOf<String, Any>()
        cmd.buildTypeSchema(structure, schemas, mutableSetOf(), mutableSetOf(), 0)
        @Suppress("UNCHECKED_CAST")
        val responseSchema = schemas["Response"] as Map<String, Any>
        assertTrue(responseSchema.containsKey("description"))
    }

    @Test
    fun `buildTypeSchema depth limit returns ref`() {
        val structure = TypeStructure(
            type = TypeDescriptor("com.example.Deep"),
            fields = mapOf("v" to FieldStructure("v", TypeDescriptor("int")))
        )
        val result = cmd.buildTypeSchema(structure, mutableMapOf(), mutableSetOf(), mutableSetOf(), 11)
        assertEquals("#/components/schemas/Deep", result["\$ref"])
    }

    // ========================================================================
    // formatJsonSchema with return type structures
    // ========================================================================

    @Test
    fun `formatJsonSchema with return type structure contains content and schema`() {
        val endpoint = makeEndpoint("/api/users", HttpMethod.GET, "UserController", "getUsers")
        val userType = TypeStructure(
            type = TypeDescriptor("com.example.User"),
            fields = mapOf(
                "name" to FieldStructure("name", TypeDescriptor("java.lang.String")),
                "age" to FieldStructure("age", TypeDescriptor("int"))
            )
        )
        val returnTypes = mapOf(
            "/api/users" to TypeHierarchyResult(
                method = endpoint.method,
                returnStructures = setOf(userType)
            )
        )

        val json = cmd.formatJsonSchema(listOf(endpoint), returnTypes)
        val root = JsonParser.parseString(json).asJsonObject
        val getUsersOp = root.getAsJsonObject("paths")
            .getAsJsonObject("/api/users")
            .getAsJsonObject("get")
        val response = getUsersOp.getAsJsonObject("responses").getAsJsonObject("200")
        assertTrue(response.has("content"))
        val content = response.getAsJsonObject("content")
        assertTrue(content.has("application/json"))
    }

    @Test
    fun `formatJsonSchema with multiple return types uses oneOf`() {
        val endpoint = makeEndpoint("/api/data", HttpMethod.GET, "DataController", "getData")
        val type1 = TypeStructure(
            type = TypeDescriptor("com.example.User"),
            fields = mapOf("name" to FieldStructure("name", TypeDescriptor("java.lang.String")))
        )
        val type2 = TypeStructure(
            type = TypeDescriptor("com.example.Admin"),
            fields = mapOf("role" to FieldStructure("role", TypeDescriptor("java.lang.String")))
        )
        val returnTypes = mapOf(
            "/api/data" to TypeHierarchyResult(
                method = endpoint.method,
                returnStructures = setOf(type1, type2)
            )
        )

        val json = cmd.formatJsonSchema(listOf(endpoint), returnTypes)
        val root = JsonParser.parseString(json).asJsonObject
        val response = root.getAsJsonObject("paths")
            .getAsJsonObject("/api/data")
            .getAsJsonObject("get")
            .getAsJsonObject("responses")
            .getAsJsonObject("200")
        val content = response.getAsJsonObject("content")
            .getAsJsonObject("application/json")
        val schema = content.getAsJsonObject("schema")
        assertTrue(schema.has("oneOf"))
    }

    @Test
    fun `buildTypeSchema filters JsonIgnored fields from properties`() {
        val structure = TypeStructure(
            type = TypeDescriptor("com.example.User"),
            fields = mapOf(
                "name" to FieldStructure("name", TypeDescriptor("java.lang.String")),
                "secret" to FieldStructure("secret", TypeDescriptor("java.lang.String"), isJsonIgnored = true)
            )
        )
        val schemas = mutableMapOf<String, Any>()
        cmd.buildTypeSchema(structure, schemas, mutableSetOf(), mutableSetOf(), 0)
        @Suppress("UNCHECKED_CAST")
        val userSchema = schemas["User"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val properties = userSchema["properties"] as Map<String, Any>
        assertTrue(properties.containsKey("name"))
        assertFalse(properties.containsKey("secret"))
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun makeEndpoint(
        path: String,
        httpMethod: HttpMethod,
        className: String,
        methodName: String,
        returnType: String = "java.lang.Object"
    ): EndpointInfo {
        val type = TypeDescriptor("com.example.$className")
        val method = MethodDescriptor(type, methodName, emptyList(), TypeDescriptor(returnType))
        return EndpointInfo(
            method = method,
            httpMethod = httpMethod,
            path = path,
            produces = listOf("application/json")
        )
    }
}
