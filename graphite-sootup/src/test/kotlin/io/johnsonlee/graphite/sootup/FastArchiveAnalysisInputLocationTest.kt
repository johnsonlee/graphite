package io.johnsonlee.graphite.sootup

import sootup.core.model.SourceType
import sootup.java.core.JavaIdentifierFactory
import sootup.java.core.views.JavaView
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [FastArchiveAnalysisInputLocation] verifying that the optimized
 * class lookup (bypassing `Files.exists()` on ZipFileSystem paths) behaves
 * correctly for both present and absent classes.
 */
class FastArchiveAnalysisInputLocationTest {

    @Test
    fun `getClassSource returns class that exists in JAR`() {
        val jarPath = buildTestJar("sample/simple/")
        try {
            val location = FastArchiveAnalysisInputLocation(jarPath, SourceType.Application)
            val view = JavaView(listOf(location))

            // First enumerate all classes so we know what is in the JAR
            val allSources = location.getClassSources(view).toList()
            assertTrue(allSources.isNotEmpty(), "JAR should contain at least one class")

            // Look up the first class by its type
            val knownType = allSources.first().classType
            val result = location.getClassSource(knownType, view)
            assertTrue(result.isPresent, "Should find class ${knownType.fullyQualifiedName} that exists in JAR")
            assertEquals(knownType, result.get().classType,
                "Returned class source should match the requested type")
        } finally {
            Files.deleteIfExists(jarPath)
        }
    }

    @Test
    fun `getClassSource returns empty for missing class`() {
        val jarPath = buildTestJar("sample/simple/")
        try {
            val location = FastArchiveAnalysisInputLocation(jarPath, SourceType.Application)
            val view = JavaView(listOf(location))
            val factory = JavaIdentifierFactory.getInstance()
            val missingType = factory.getClassType("com.does.not.Exist")

            val result = location.getClassSource(missingType, view)
            assertFalse(result.isPresent, "Should not find a class that does not exist in the JAR")
        } finally {
            Files.deleteIfExists(jarPath)
        }
    }

    @Test
    fun `getClassSources returns all classes from JAR`() {
        val jarPath = buildTestJar("sample/simple/")
        try {
            val location = FastArchiveAnalysisInputLocation(jarPath, SourceType.Application)
            val view = JavaView(listOf(location))

            val classSources = location.getClassSources(view).toList()
            assertTrue(classSources.isNotEmpty(), "Should return all classes from JAR")

            // SimpleService should be among the classes
            val classNames = classSources.map { it.classType.fullyQualifiedName }.toSet()
            assertTrue(
                classNames.contains("sample.simple.SimpleService"),
                "Should contain SimpleService class; found: $classNames"
            )
        } finally {
            Files.deleteIfExists(jarPath)
        }
    }

    @Test
    fun `getClassSource works with multiple packages in JAR`() {
        // Build JAR containing classes from two different packages
        val jarPath = buildTestJar("sample/simple/", "sample/enums/")
        try {
            val location = FastArchiveAnalysisInputLocation(jarPath, SourceType.Application)
            val view = JavaView(listOf(location))
            val factory = JavaIdentifierFactory.getInstance()

            // Look up a class from the first package
            val simpleType = factory.getClassType("sample.simple.SimpleService")
            val simpleResult = location.getClassSource(simpleType, view)
            assertTrue(simpleResult.isPresent, "Should find SimpleService from sample.simple package")

            // Look up a class from the second package
            val enumType = factory.getClassType("sample.enums.ComplexEnum")
            val enumResult = location.getClassSource(enumType, view)
            assertTrue(enumResult.isPresent, "Should find ComplexEnum from sample.enums package")

            // Look up a class that is not in either package
            val missingType = factory.getClassType("sample.controller.UserController")
            val missingResult = location.getClassSource(missingType, view)
            assertFalse(missingResult.isPresent, "Should not find class from a package not in the JAR")
        } finally {
            Files.deleteIfExists(jarPath)
        }
    }

    @Test
    fun `getClassSource returns empty for empty JAR`() {
        val jarPath = buildEmptyJar()
        try {
            val location = FastArchiveAnalysisInputLocation(jarPath, SourceType.Application)
            val view = JavaView(listOf(location))
            val factory = JavaIdentifierFactory.getInstance()

            val result = location.getClassSource(factory.getClassType("any.Class"), view)
            assertFalse(result.isPresent, "Should return empty for any class lookup on an empty JAR")

            val classSources = location.getClassSources(view).toList()
            assertTrue(classSources.isEmpty(), "Empty JAR should yield no class sources")
        } finally {
            Files.deleteIfExists(jarPath)
        }
    }

    // ========== Helper methods ==========

    private fun buildTestJar(vararg classPathPrefixes: String): Path {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val jarPath = Files.createTempFile("fast-archive-test", ".jar")
        JarOutputStream(Files.newOutputStream(jarPath), Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
        }).use { jos ->
            val baseDir = testClassesDir.toFile()
            for (prefix in classPathPrefixes) {
                val prefixDir = File(baseDir, prefix)
                if (prefixDir.exists()) {
                    prefixDir.walk()
                        .filter { it.isFile && it.name.endsWith(".class") }
                        .forEach { classFile ->
                            val entryName = classFile.relativeTo(baseDir).path
                                .replace(File.separatorChar, '/')
                            jos.putNextEntry(JarEntry(entryName))
                            classFile.inputStream().use { it.copyTo(jos) }
                            jos.closeEntry()
                        }
                }
            }
        }
        return jarPath
    }

    private fun buildEmptyJar(): Path {
        val jarPath = Files.createTempFile("fast-archive-empty", ".jar")
        JarOutputStream(Files.newOutputStream(jarPath), Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
        }).use { /* no entries */ }
        return jarPath
    }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }
}
