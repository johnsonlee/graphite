package io.johnsonlee.graphite.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DependencyBodyScopeTest {

    @Test
    fun `none covers nothing and names nothing`() {
        val none: DependencyBodyScope = DependencyBodyScope.None
        assertFalse(none.covers("kotlin.text.StringsKt", "kotlin-stdlib-2.1.0.jar"))
        assertFalse(none.coversArtifact("kotlin-stdlib-2.1.0.jar"))
        assertEquals(emptyList(), none.artifacts)
        assertEquals(emptyList(), none.packages)
        assertEquals("none", none.toString())
    }

    @Test
    fun `a declared scope covers a class by its artifact's glob or its package prefix`() {
        val scope = DependencyBodyScope.Declared(artifacts = listOf("kotlin-stdlib-*.jar"), packages = listOf("com.acme.helper"))
        assertTrue(scope.coversArtifact("kotlin-stdlib-2.1.0.jar"))
        assertFalse(scope.coversArtifact("kotlin-stdlib-2.1.0.jar.bak"), "the glob matches the whole name")
        assertFalse(scope.coversArtifact("kotlin-reflect-2.1.0.jar"))
        assertTrue(scope.coversArtifact("lib/kotlin-stdlib-2.1.0.jar"), "a directory input names its jars by path, the pattern its file")
        assertTrue(scope.covers("kotlin.text.StringsKt", "lib/kotlin-stdlib-2.1.0.jar"))
        assertTrue(scope.covers("kotlin.text.StringsKt", "kotlin-stdlib-2.1.0.jar"), "by artifact")
        assertTrue(scope.covers("com.acme.helper.Bundles", "acme-helper-1.0.jar"), "by package")
        assertTrue(scope.covers("com.acme.helper.Bundles", null), "by package, with no artifact known")
        assertFalse(scope.covers("com.acme.other.Util", "acme-other-1.0.jar"))
        assertFalse(scope.covers("kotlin.text.StringsKt", null), "an artifact pattern needs the artifact")
        assertEquals(listOf("kotlin-stdlib-*.jar"), scope.artifacts)
        assertEquals(listOf("com.acme.helper"), scope.packages)
    }

    @Test
    fun `a declared scope names at least one artifact or package, none of them blank`() {
        assertEquals(
            "a declared dependency body scope names at least one artifact pattern or package prefix",
            assertFailsWith<IllegalArgumentException> { DependencyBodyScope.Declared() }.message
        )
        assertEquals(
            "a dependency body scope names no blank artifact pattern or package prefix",
            assertFailsWith<IllegalArgumentException> { DependencyBodyScope.Declared(artifacts = listOf(" ")) }.message
        )
        assertEquals(
            "a dependency body scope names no blank artifact pattern or package prefix",
            assertFailsWith<IllegalArgumentException> { DependencyBodyScope.Declared(packages = listOf("a", "")) }.message
        )
        assertEquals(listOf("a"), DependencyBodyScope.Declared(packages = listOf("a")).packages)
    }

    @Test
    fun `a reachable scope loads every archive and covers no class up front`() {
        val scope: DependencyBodyScope = DependencyBodyScope.Reachable(maxClasses = 10)
        assertTrue(scope.coversArtifact("anything-1.0.jar"), "every library is loaded so its classes can be reached")
        assertFalse(scope.covers("kotlin.text.StringsKt", "kotlin-stdlib-2.1.0.jar"), "a class is read when reached, not up front")
        assertEquals(emptyList(), scope.artifacts)
        assertEquals(emptyList(), scope.packages)
        assertEquals("reachable(maxClasses=10)", scope.toString())
        assertEquals(50_000, DependencyBodyScope.Reachable().maxClasses)
        assertEquals(
            "a reachable dependency body scope reads at least one class",
            assertFailsWith<IllegalArgumentException> { DependencyBodyScope.Reachable(0) }.message
        )
    }

    @Test
    fun `the glob dialect is the library filter's`() {
        assertTrue(GlobPatterns.matches("modular-core.jar", "modular-*"))
        assertTrue(GlobPatterns.matches("business-x.jar", "business-*.jar"))
        assertFalse(GlobPatterns.matches("business-xjar", "business-*.jar"), "a dot is literal")
        assertFalse(GlobPatterns.matches("xbusiness-x.jar", "business-*.jar"), "anchored at both ends")
        assertEquals("^a\\..*\\.jar$", GlobPatterns.toRegex("a.*.jar").pattern)
    }
}
