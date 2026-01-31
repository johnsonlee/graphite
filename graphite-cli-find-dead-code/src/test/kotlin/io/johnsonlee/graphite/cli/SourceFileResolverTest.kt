package io.johnsonlee.graphite.cli

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceFileResolverTest {

    // ========================================================================
    // Java source resolution
    // ========================================================================

    @Test
    fun `resolve finds Java source file`() {
        val sourceDir = createSourceTree(
            "com/example/MyClass.java" to "public class MyClass {}"
        )
        val resolver = SourceFileResolver(listOf(sourceDir))

        val file = resolver.resolve("com.example.MyClass")
        assertNotNull(file)
        assertEquals("MyClass.java", file.name)
    }

    @Test
    fun `resolve finds Kotlin source file`() {
        val sourceDir = createSourceTree(
            "com/example/MyClass.kt" to "class MyClass"
        )
        val resolver = SourceFileResolver(listOf(sourceDir))

        val file = resolver.resolve("com.example.MyClass")
        assertNotNull(file)
        assertEquals("MyClass.kt", file.name)
    }

    @Test
    fun `resolve prefers Java over Kotlin when both exist`() {
        val sourceDir = createSourceTree(
            "com/example/MyClass.java" to "public class MyClass {}",
            "com/example/MyClass.kt" to "class MyClass"
        )
        val resolver = SourceFileResolver(listOf(sourceDir))

        val file = resolver.resolve("com.example.MyClass")
        assertNotNull(file)
        assertEquals("MyClass.java", file.name)
    }

    // ========================================================================
    // Kotlin file facade resolution
    // ========================================================================

    @Test
    fun `resolve finds Kotlin file facade (UtilsKt to Utils dot kt)`() {
        val sourceDir = createSourceTree(
            "com/example/Utils.kt" to "fun helper() {}"
        )
        val resolver = SourceFileResolver(listOf(sourceDir))

        val file = resolver.resolve("com.example.UtilsKt")
        assertNotNull(file)
        assertEquals("Utils.kt", file.name)
    }

    @Test
    fun `resolve handles file facade in default package`() {
        val sourceDir = createSourceTree(
            "Helpers.kt" to "fun help() {}"
        )
        val resolver = SourceFileResolver(listOf(sourceDir))

        val file = resolver.resolve("HelpersKt")
        assertNotNull(file)
        assertEquals("Helpers.kt", file.name)
    }

    @Test
    fun `resolve does not treat short Kt suffix as file facade`() {
        // "Kt" alone (2 chars) should not be treated as a file facade
        val sourceDir = createSourceTree(
            "com/example/Kt.kt" to "class Kt"
        )
        val resolver = SourceFileResolver(listOf(sourceDir))

        // "com.example.Kt" - simpleName is "Kt", endsWith("Kt") but length == 2
        val file = resolver.resolve("com.example.Kt")
        assertNotNull(file)
        assertEquals("Kt.kt", file.name) // Direct match, not file facade
    }

    // ========================================================================
    // Inner class resolution
    // ========================================================================

    @Test
    fun `resolve finds outer Java class for inner class`() {
        val sourceDir = createSourceTree(
            "com/example/Outer.java" to "public class Outer { static class Inner {} }"
        )
        val resolver = SourceFileResolver(listOf(sourceDir))

        val file = resolver.resolve("com.example.Outer\$Inner")
        assertNotNull(file)
        assertEquals("Outer.java", file.name)
    }

    @Test
    fun `resolve finds outer Kotlin class for inner class`() {
        val sourceDir = createSourceTree(
            "com/example/Outer.kt" to "class Outer { class Inner }"
        )
        val resolver = SourceFileResolver(listOf(sourceDir))

        val file = resolver.resolve("com.example.Outer\$Inner")
        assertNotNull(file)
        assertEquals("Outer.kt", file.name)
    }

    @Test
    fun `resolve handles deeply nested inner class`() {
        val sourceDir = createSourceTree(
            "com/example/A.java" to "class A { class B { class C {} } }"
        )
        val resolver = SourceFileResolver(listOf(sourceDir))

        // A$B$C - substringBefore('$') gives "com.example.A"
        val file = resolver.resolve("com.example.A\$B\$C")
        assertNotNull(file)
        assertEquals("A.java", file.name)
    }

    // ========================================================================
    // Multiple source directories
    // ========================================================================

    @Test
    fun `resolve searches across multiple source directories`() {
        val srcMain = createSourceTree(
            "com/example/Main.java" to "class Main {}"
        )
        val srcTest = createSourceTree(
            "com/example/Test.java" to "class Test {}"
        )
        val resolver = SourceFileResolver(listOf(srcMain, srcTest))

        val mainFile = resolver.resolve("com.example.Main")
        assertNotNull(mainFile)
        assertEquals("Main.java", mainFile.name)

        val testFile = resolver.resolve("com.example.Test")
        assertNotNull(testFile)
        assertEquals("Test.java", testFile.name)
    }

    @Test
    fun `resolve returns first match from ordered source directories`() {
        val dir1 = createSourceTree(
            "com/example/Shared.java" to "// from dir1"
        )
        val dir2 = createSourceTree(
            "com/example/Shared.java" to "// from dir2"
        )
        val resolver = SourceFileResolver(listOf(dir1, dir2))

        val file = resolver.resolve("com.example.Shared")
        assertNotNull(file)
        assertTrue(file.readText().contains("from dir1"))
    }

    // ========================================================================
    // Not found
    // ========================================================================

    @Test
    fun `resolve returns null when class not found`() {
        val sourceDir = createSourceTree(
            "com/example/Existing.java" to "class Existing {}"
        )
        val resolver = SourceFileResolver(listOf(sourceDir))

        val file = resolver.resolve("com.example.NonExistent")
        assertNull(file)
    }

    @Test
    fun `resolve returns null with empty source dirs`() {
        val resolver = SourceFileResolver(emptyList())

        val file = resolver.resolve("com.example.Anything")
        assertNull(file)
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun createSourceTree(vararg files: Pair<String, String>): Path {
        val root = Files.createTempDirectory("source-resolver-test")
        root.toFile().deleteOnExit()

        for ((relativePath, content) in files) {
            val file = root.resolve(relativePath).toFile()
            file.parentFile.mkdirs()
            file.writeText(content)
            file.deleteOnExit()
            // Also mark parent dirs for cleanup
            var parent = file.parentFile
            while (parent != root.toFile() && parent != null) {
                parent.deleteOnExit()
                parent = parent.parentFile
            }
        }

        return root
    }
}
