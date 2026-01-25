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
                        .forEach { entry ->
                            val jarName = entry.name.substringAfterLast("/")
                            val targetFile = libDir.resolve(jarName)
                            zip.getInputStream(entry).use { input ->
                                Files.copy(input, targetFile)
                            }
                            locations.add(PathBasedAnalysisInputLocation.create(targetFile, SourceType.Library))
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

                zip.entries().asSequence()
                    .filter { it.name.startsWith("WEB-INF/classes/") && !it.isDirectory }
                    .forEach { entry ->
                        val relativePath = entry.name.removePrefix("WEB-INF/classes/")
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
                        .filter { it.name.startsWith("WEB-INF/lib/") && it.name.endsWith(".jar") }
                        .forEach { entry ->
                            val jarName = entry.name.substringAfterLast("/")
                            val targetFile = libDir.resolve(jarName)
                            zip.getInputStream(entry).use { input ->
                                Files.copy(input, targetFile)
                            }
                            locations.add(PathBasedAnalysisInputLocation.create(targetFile, SourceType.Library))
                        }
                }
            }
        } catch (e: Exception) {
            tempDir.toFile().deleteRecursively()
            throw e
        }

        return locations
    }
}
