package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.input.ProjectLoader
import sootup.core.inputlocation.AnalysisInputLocation
import sootup.core.model.SourceType
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation
import sootup.java.core.views.JavaView
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.io.path.isDirectory

/**
 * SootUp-based loader for Java projects.
 *
 * Supports loading from various sources:
 * - JAR files
 * - WAR files
 * - Directories containing .class files
 * - Spring Boot fat JARs (BOOT-INF layout)
 */
class JavaProjectLoader(
    private val config: LoaderConfig = LoaderConfig()
) : ProjectLoader {

    override fun load(path: Path): Graph {
        val inputLocations = createInputLocations(path)
        val view = JavaView(inputLocations)

        val adapter = SootUpAdapter(view, config)
        return adapter.buildGraph()
    }

    override fun canLoad(path: Path): Boolean {
        if (path.isDirectory()) {
            return true
        }
        val ext = path.extension.lowercase()
        return ext in listOf("jar", "war", "zip")
    }

    private fun createInputLocations(path: Path): List<AnalysisInputLocation> {
        return when {
            path.isDirectory() -> listOf(
                PathBasedAnalysisInputLocation.create(path, SourceType.Application)
            )
            isSpringBootJar(path) -> createSpringBootInputLocations(path)
            isWarFile(path) -> createWarInputLocations(path)
            else -> listOf(
                PathBasedAnalysisInputLocation.create(path, SourceType.Application)
            )
        }
    }

    /**
     * Check if this is a Spring Boot fat JAR by looking for BOOT-INF directory
     */
    private fun isSpringBootJar(path: Path): Boolean {
        if (path.extension.lowercase() != "jar") return false

        return try {
            ZipFile(path.toFile()).use { zip ->
                zip.getEntry("BOOT-INF/classes/") != null
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun isWarFile(path: Path): Boolean {
        return path.extension.lowercase() == "war"
    }

    /**
     * Create input locations for Spring Boot fat JAR.
     *
     * Spring Boot layout:
     * - BOOT-INF/classes/ - Application classes
     * - BOOT-INF/lib/ - Dependency JARs
     */
    private fun createSpringBootInputLocations(path: Path): List<AnalysisInputLocation> {
        val locations = mutableListOf<AnalysisInputLocation>()
        val tempDir = Files.createTempDirectory("graphite-springboot")

        try {
            ZipFile(path.toFile()).use { zip ->
                // Extract BOOT-INF/classes
                val classesDir = tempDir.resolve("classes")
                Files.createDirectories(classesDir)

                zip.entries().asSequence()
                    .filter { it.name.startsWith("BOOT-INF/classes/") && !it.isDirectory }
                    .forEach { entry ->
                        val relativePath = entry.name.removePrefix("BOOT-INF/classes/")
                        val targetFile = classesDir.resolve(relativePath)
                        Files.createDirectories(targetFile.parent)
                        zip.getInputStream(entry).use { input ->
                            Files.copy(input, targetFile)
                        }
                    }

                locations.add(PathBasedAnalysisInputLocation.create(classesDir, SourceType.Application))

                // Optionally include libraries
                if (config.includeLibraries) {
                    val libDir = tempDir.resolve("lib")
                    Files.createDirectories(libDir)

                    zip.entries().asSequence()
                        .filter { it.name.startsWith("BOOT-INF/lib/") && it.name.endsWith(".jar") }
                        .filter { entry -> matchesLibraryFilter(entry.name.substringAfterLast("/")) }
                        .forEach { entry ->
                            val jarName = entry.name.substringAfterLast("/")
                            val targetFile = libDir.resolve(jarName)
                            zip.getInputStream(entry).use { input ->
                                Files.copy(input, targetFile)
                            }
                            // Only add JAR if it contains classes from included packages
                            if (jarContainsIncludedPackages(targetFile)) {
                                locations.add(PathBasedAnalysisInputLocation.create(targetFile, SourceType.Library))
                            }
                        }
                }
            }
        } catch (e: Exception) {
            // Cleanup on failure
            tempDir.toFile().deleteRecursively()
            throw e
        }

        return locations
    }

    /**
     * Create input locations for WAR file.
     *
     * WAR layout:
     * - WEB-INF/classes/ - Application classes
     * - WEB-INF/lib/ - Dependency JARs
     */
    private fun createWarInputLocations(path: Path): List<AnalysisInputLocation> {
        val locations = mutableListOf<AnalysisInputLocation>()
        val tempDir = Files.createTempDirectory("graphite-war")

        try {
            ZipFile(path.toFile()).use { zip ->
                // Extract WEB-INF/classes
                val classesDir = tempDir.resolve("classes")
                Files.createDirectories(classesDir)

                var classFileCount = 0
                zip.entries().asSequence()
                    .filter { it.name.startsWith("WEB-INF/classes/") && !it.isDirectory }
                    .forEach { entry ->
                        val relativePath = entry.name.removePrefix("WEB-INF/classes/")
                        val targetFile = classesDir.resolve(relativePath)
                        Files.createDirectories(targetFile.parent)
                        zip.getInputStream(entry).use { input ->
                            Files.copy(input, targetFile)
                        }
                        if (entry.name.endsWith(".class")) classFileCount++
                    }

                log("Extracted $classFileCount class files from WEB-INF/classes")
                locations.add(PathBasedAnalysisInputLocation.create(classesDir, SourceType.Application))

                // Optionally include libraries
                if (config.includeLibraries) {
                    val libDir = tempDir.resolve("lib")
                    Files.createDirectories(libDir)

                    val allJars = zip.entries().asSequence()
                        .filter { it.name.startsWith("WEB-INF/lib/") && it.name.endsWith(".jar") }
                        .toList()

                    log("Found ${allJars.size} JARs in WEB-INF/lib")

                    var loadedJarCount = 0
                    var skippedByFilter = 0
                    var skippedByPackage = 0

                    allJars.forEach { entry ->
                        val jarName = entry.name.substringAfterLast("/")

                        if (!matchesLibraryFilter(jarName)) {
                            skippedByFilter++
                            return@forEach
                        }

                        val targetFile = libDir.resolve(jarName)
                        zip.getInputStream(entry).use { input ->
                            Files.copy(input, targetFile)
                        }

                        // Only add JAR if it contains classes from included packages
                        if (jarContainsIncludedPackages(targetFile)) {
                            locations.add(PathBasedAnalysisInputLocation.create(targetFile, SourceType.Library))
                            loadedJarCount++
                            log("  + Loading JAR: $jarName")
                        } else {
                            skippedByPackage++
                            // Clean up unneeded JAR
                            Files.deleteIfExists(targetFile)
                        }
                    }

                    log("Loaded $loadedJarCount JARs (skipped: $skippedByFilter by filter, $skippedByPackage by package)")
                }
            }
        } catch (e: Exception) {
            tempDir.toFile().deleteRecursively()
            throw e
        }

        return locations
    }

    private fun log(message: String) {
        config.verbose?.invoke(message)
    }

    /**
     * Check if a library JAR matches the configured filters.
     *
     * If libraryFilters is specified, match against those patterns.
     * Otherwise, if includePackages is specified, only include JARs that contain classes from those packages.
     * If neither is specified, include all JARs.
     */
    private fun matchesLibraryFilter(jarName: String): Boolean {
        // If explicit library filters are specified, use them
        if (config.libraryFilters.isNotEmpty()) {
            return config.libraryFilters.any { pattern ->
                matchesGlobPattern(jarName, pattern)
            }
        }

        // If no filters specified, include all
        return true
    }

    /**
     * Check if a JAR contains classes from the included packages.
     * Used to skip JARs that don't contain relevant classes.
     */
    private fun jarContainsIncludedPackages(jarPath: Path): Boolean {
        if (config.includePackages.isEmpty()) {
            return true // No filter, include all
        }

        return try {
            ZipFile(jarPath.toFile()).use { jar ->
                jar.entries().asSequence()
                    .filter { it.name.endsWith(".class") }
                    .any { entry ->
                        val className = entry.name
                            .removeSuffix(".class")
                            .replace('/', '.')
                        config.includePackages.any { pkg ->
                            className.startsWith(pkg)
                        }
                    }
            }
        } catch (e: Exception) {
            true // On error, include to be safe
        }
    }

    private fun matchesGlobPattern(name: String, pattern: String): Boolean {
        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .let { "^$it$" }
            .toRegex()
        return regex.matches(name)
    }
}
