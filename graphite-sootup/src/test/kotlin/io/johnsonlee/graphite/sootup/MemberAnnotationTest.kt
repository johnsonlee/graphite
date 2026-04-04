package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemberAnnotationTest {

    // ========================================================================
    // memberAnnotations via DefaultGraph.Builder
    // ========================================================================

    @Test
    fun `memberAnnotations returns empty map for unknown member`() {
        val builder = DefaultGraph.Builder()
        val graph = builder.build()
        val result = graph.memberAnnotations("com.example.Foo", "bar")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `memberAnnotations stores and retrieves annotation`() {
        val builder = DefaultGraph.Builder()
        builder.addMemberAnnotation("com.example.User", "name", "com.fasterxml.jackson.annotation.JsonProperty", mapOf("value" to "user_name"))
        val graph = builder.build()
        val annotations = graph.memberAnnotations("com.example.User", "name")
        assertEquals("user_name", annotations["com.fasterxml.jackson.annotation.JsonProperty"]?.get("value"))
    }

    @Test
    fun `memberAnnotations supports multiple annotations on same member`() {
        val builder = DefaultGraph.Builder()
        builder.addMemberAnnotation("com.example.User", "secret", "com.fasterxml.jackson.annotation.JsonProperty", mapOf("value" to "secret"))
        builder.addMemberAnnotation("com.example.User", "secret", "com.fasterxml.jackson.annotation.JsonIgnore", emptyMap())
        val graph = builder.build()
        val annotations = graph.memberAnnotations("com.example.User", "secret")
        assertEquals(2, annotations.size)
        assertTrue(annotations.containsKey("com.fasterxml.jackson.annotation.JsonProperty"))
        assertTrue(annotations.containsKey("com.fasterxml.jackson.annotation.JsonIgnore"))
    }

    @Test
    fun `memberAnnotations with empty values`() {
        val builder = DefaultGraph.Builder()
        builder.addMemberAnnotation("com.example.User", "name", "com.fasterxml.jackson.annotation.JsonIgnore")
        val graph = builder.build()
        val annotations = graph.memberAnnotations("com.example.User", "name")
        assertTrue(annotations.containsKey("com.fasterxml.jackson.annotation.JsonIgnore"))
        assertTrue(annotations["com.fasterxml.jackson.annotation.JsonIgnore"]!!.isEmpty())
    }

    @Test
    fun `memberAnnotations equality across same data`() {
        val builder1 = DefaultGraph.Builder()
        builder1.addMemberAnnotation("com.example.User", "name", "com.fasterxml.jackson.annotation.JsonProperty", mapOf("value" to "user_name"))
        val graph1 = builder1.build()

        val builder2 = DefaultGraph.Builder()
        builder2.addMemberAnnotation("com.example.User", "name", "com.fasterxml.jackson.annotation.JsonProperty", mapOf("value" to "user_name"))
        val graph2 = builder2.build()

        assertEquals(
            graph1.memberAnnotations("com.example.User", "name"),
            graph2.memberAnnotations("com.example.User", "name")
        )
    }

    @Test
    fun `memberAnnotations toString contains values`() {
        val builder = DefaultGraph.Builder()
        builder.addMemberAnnotation("com.example.User", "name", "com.fasterxml.jackson.annotation.JsonProperty", mapOf("value" to "x"))
        val graph = builder.build()
        val str = graph.memberAnnotations("com.example.User", "name").toString()
        assertTrue(str.contains("x"))
    }

    // ========================================================================
    // Builder-based tests: annotations written to builder
    // ========================================================================

    @Test
    fun `builder memberAnnotations returns annotations for field`() {
        val builder = DefaultGraph.Builder()
        builder.addMemberAnnotation("com.example.User", "name", "com.fasterxml.jackson.annotation.JsonProperty", mapOf("value" to "user_name"))
        val graph = builder.build()
        val result = graph.memberAnnotations("com.example.User", "name")
        assertEquals("user_name", result["com.fasterxml.jackson.annotation.JsonProperty"]?.get("value"))
    }

    @Test
    fun `builder memberAnnotations returns empty for wrong class`() {
        val builder = DefaultGraph.Builder()
        builder.addMemberAnnotation("com.example.User", "name", "com.fasterxml.jackson.annotation.JsonProperty", mapOf("value" to "user_name"))
        val graph = builder.build()
        assertTrue(graph.memberAnnotations("com.example.Other", "name").isEmpty())
    }

    @Test
    fun `builder memberAnnotations returns empty for wrong field`() {
        val builder = DefaultGraph.Builder()
        builder.addMemberAnnotation("com.example.User", "name", "com.fasterxml.jackson.annotation.JsonProperty", mapOf("value" to "user_name"))
        val graph = builder.build()
        assertTrue(graph.memberAnnotations("com.example.User", "email").isEmpty())
    }

    @Test
    fun `builder memberAnnotations returns annotations for getter`() {
        val builder = DefaultGraph.Builder()
        builder.addMemberAnnotation("com.example.User", "getName", "com.fasterxml.jackson.annotation.JsonProperty", mapOf("value" to "user_name"))
        val graph = builder.build()
        val result = graph.memberAnnotations("com.example.User", "getName")
        assertEquals("user_name", result["com.fasterxml.jackson.annotation.JsonProperty"]?.get("value"))
    }

    @Test
    fun `builder memberAnnotations returns empty for wrong getter class`() {
        val builder = DefaultGraph.Builder()
        builder.addMemberAnnotation("com.example.User", "getName", "com.fasterxml.jackson.annotation.JsonProperty", mapOf("value" to "user_name"))
        val graph = builder.build()
        assertTrue(graph.memberAnnotations("com.example.Other", "getName").isEmpty())
    }

    @Test
    fun `builder memberAnnotations returns empty for wrong getter method`() {
        val builder = DefaultGraph.Builder()
        builder.addMemberAnnotation("com.example.User", "getName", "com.fasterxml.jackson.annotation.JsonProperty", mapOf("value" to "user_name"))
        val graph = builder.build()
        assertTrue(graph.memberAnnotations("com.example.User", "getEmail").isEmpty())
    }
}
