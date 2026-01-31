package io.johnsonlee.graphite.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EndpointTest {

    // ========================================================================
    // HttpMethod.fromAnnotation
    // ========================================================================

    @Test
    fun `fromAnnotation maps GetMapping`() {
        assertEquals(HttpMethod.GET, HttpMethod.fromAnnotation("org.springframework.web.bind.annotation.GetMapping"))
    }

    @Test
    fun `fromAnnotation maps PostMapping`() {
        assertEquals(HttpMethod.POST, HttpMethod.fromAnnotation("PostMapping"))
    }

    @Test
    fun `fromAnnotation maps PutMapping`() {
        assertEquals(HttpMethod.PUT, HttpMethod.fromAnnotation("PutMapping"))
    }

    @Test
    fun `fromAnnotation maps DeleteMapping`() {
        assertEquals(HttpMethod.DELETE, HttpMethod.fromAnnotation("DeleteMapping"))
    }

    @Test
    fun `fromAnnotation maps PatchMapping`() {
        assertEquals(HttpMethod.PATCH, HttpMethod.fromAnnotation("PatchMapping"))
    }

    @Test
    fun `fromAnnotation maps RequestMapping to ANY`() {
        assertEquals(HttpMethod.ANY, HttpMethod.fromAnnotation("RequestMapping"))
    }

    @Test
    fun `fromAnnotation returns null for unknown annotation`() {
        assertNull(HttpMethod.fromAnnotation("SomeOtherAnnotation"))
    }

    @Test
    fun `HttpMethod has all expected values`() {
        assertEquals(8, HttpMethod.entries.size)
    }

    // ========================================================================
    // EndpointInfo.matchesPattern
    // ========================================================================

    private fun endpoint(path: String): EndpointInfo {
        val method = MethodDescriptor(TypeDescriptor("Ctrl"), "handler", emptyList(), TypeDescriptor("void"))
        return EndpointInfo(method, HttpMethod.GET, path)
    }

    @Test
    fun `matchesPattern exact match`() {
        assertTrue(endpoint("/api/users").matchesPattern("/api/users"))
    }

    @Test
    fun `matchesPattern single wildcard`() {
        assertTrue(endpoint("/api/users/123").matchesPattern("/api/users/*"))
    }

    @Test
    fun `matchesPattern double wildcard`() {
        assertTrue(endpoint("/api/users/123/profile").matchesPattern("/api/**"))
    }

    @Test
    fun `matchesPattern path param treated as wildcard`() {
        assertTrue(endpoint("/api/users/123").matchesPattern("/api/users/{id}"))
    }

    @Test
    fun `matchesPattern no match`() {
        assertFalse(endpoint("/api/users").matchesPattern("/api/orders"))
    }

    @Test
    fun `matchesPattern double wildcard at end matches everything below`() {
        assertTrue(endpoint("/api/users/123/profile/edit").matchesPattern("/api/**"))
    }

    @Test
    fun `matchesPattern handles trailing slashes`() {
        assertTrue(endpoint("/api/users/").matchesPattern("/api/users"))
    }

    @Test
    fun `matchesPattern single wildcard does not match multiple segments`() {
        assertFalse(endpoint("/api/users/123/profile").matchesPattern("/api/users/*"))
    }

    @Test
    fun `matchesPattern double wildcard in middle`() {
        assertTrue(endpoint("/api/users/123/profile").matchesPattern("/api/**/profile"))
    }

    // ========================================================================
    // EndpointInfo properties
    // ========================================================================

    @Test
    fun `fullPath returns path`() {
        val ep = endpoint("/api/users")
        assertEquals("/api/users", ep.fullPath)
    }

    @Test
    fun `produces and consumes default to empty`() {
        val ep = endpoint("/api/users")
        assertTrue(ep.produces.isEmpty())
        assertTrue(ep.consumes.isEmpty())
    }

    @Test
    fun `matchesPattern double wildcard followed by non-matching segment`() {
        assertFalse(endpoint("/api/users/123/profile").matchesPattern("/api/**/settings"))
    }

    @Test
    fun `matchesPattern trailing double wildcard after matching prefix`() {
        assertTrue(endpoint("/api").matchesPattern("/api/**"))
    }

    @Test
    fun `matchesPattern empty path matches empty pattern`() {
        assertTrue(endpoint("/").matchesPattern("/"))
    }
}
