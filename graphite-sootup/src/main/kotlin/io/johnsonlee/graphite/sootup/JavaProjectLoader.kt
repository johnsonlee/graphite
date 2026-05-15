package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.FullGraphBuilder
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MmapGraphBuilder
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.input.JavaArchiveLayout
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
    private val config: LoaderConfig = LoaderConfig(),
    private val graphBuilderFactory: () -> FullGraphBuilder = { MmapGraphBuilder() }
) : ProjectLoader {

    /**
     * Convenience constructor that selects the builder implementation
     * based on a simple flag.
     *
     * When [useMmapBuilder] is `true` (the default), nodes and edges are
     * spilled to disk during construction so that the JVM heap stays small
     * while SootUp processes classes.  See [MmapGraphBuilder] for details.
     */
    constructor(config: LoaderConfig, useMmapBuilder: Boolean) : this(
        config = config,
        graphBuilderFactory = if (useMmapBuilder) ({ MmapGraphBuilder() }) else ({ DefaultGraph.Builder() })
    )

    override fun load(path: Path): Graph {
        val inputLocations = createInputLocations(path)
        val view = JavaView(inputLocations.locations)

        // Load generic signatures from bytecode
        val signatureReader = BytecodeSignatureReader()
        loadSignatures(path, signatureReader)

        val resourceAccessor = ArchiveResourceAccessor.create(path)
        val adapter = SootUpAdapter(
            view, config, signatureReader,
            resourceAccessor = resourceAccessor,
            inputLocationSources = inputLocations.sources,
            graphBuilder = graphBuilderFactory()
        )
        return adapter.buildGraph()
    }

    /**
     * Load generic signatures from bytecode.
     */
    private fun loadSignatures(path: Path, reader: BytecodeSignatureReader) {
        try {
            when {
                path.isDirectory() -> {
                    reader.loadFromDirectory(path)
                    Files.walk(path).use { stream ->
                        stream.filter { Files.isRegularFile(it) }
                            .filter {
                                when (it.fileName.toString().substringAfterLast('.', "").lowercase()) {
                                    "jar" -> true
                                    else -> false
                                }
                            }
                            .forEach { jarPath ->
                                try {
                                    reader.loadFromJar(jarPath)
                                } catch (_: Exception) {
                                }
                            }
                    }
                }
                path.extension.lowercase() == "jar" -> {
                    if (isSpringBootJar(path)) {
                        loadSpringBootSignatures(path, reader)
                    } else {
                        reader.loadFromJar(path)
                    }
                }
                path.extension.lowercase() == "war" -> {
                    loadWarSignatures(path, reader)
                }
            }
        } catch (e: Exception) {
            // Log but don't fail - signatures are optional enhancement
            log("Warning: Failed to load generic signatures: ${e.message}")
        }
    }

    private fun loadSpringBootSignatures(jarPath: Path, reader: BytecodeSignatureReader) {
        ZipFile(jarPath.toFile()).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.startsWith(JavaArchiveLayout.BOOT_INF_CLASSES) && it.name.endsWith(JavaArchiveLayout.CLASS_EXTENSION) }
                .forEach { entry ->
                    zip.getInputStream(entry).use { inputStream ->
                        reader.loadFromStream(inputStream)
                    }
                }
        }
    }

    private fun loadWarSignatures(warPath: Path, reader: BytecodeSignatureReader) {
        ZipFile(warPath.toFile()).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.startsWith(JavaArchiveLayout.WEB_INF_CLASSES) && it.name.endsWith(JavaArchiveLayout.CLASS_EXTENSION) }
                .forEach { entry ->
                    zip.getInputStream(entry).use { inputStream ->
                        reader.loadFromStream(inputStream)
                    }
                }
        }
    }

    override fun canLoad(path: Path): Boolean {
        if (path.isDirectory()) {
            return true
        }
        val ext = path.extension.lowercase()
        return ext in listOf("jar", "war", "zip")
    }

    private data class InputLocations(
        val locations: List<AnalysisInputLocation>,
        val sources: Map<AnalysisInputLocation, String>
    )

    private fun createInputLocations(path: Path): InputLocations {
        return when {
            path.isDirectory() -> createDirectoryInputLocations(path)
            isSpringBootJar(path) -> createSpringBootInputLocations(path)
            isWarFile(path) -> createWarInputLocations(path)
            else -> {
                val location = PathBasedAnalysisInputLocation.create(path, SourceType.Application)
                InputLocations(
                    locations = listOf(location),
                    sources = mapOf(location to path.fileName.toString())
                )
            }
        }
    }

    private fun createDirectoryInputLocations(path: Path): InputLocations {
        val locations = mutableListOf<AnalysisInputLocation>()
        val sources = mutableMapOf<AnalysisInputLocation, String>()
        if (containsLooseClassFiles(path)) {
            locations.addInputLocation(path.fileName.toString(), sources, path, SourceType.Application)
        }

        val jarPaths = Files.walk(path).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".jar", ignoreCase = true) }
                .sorted()
                .toList()
        }

        jarPaths.forEach { jarPath ->
            val relativeJar = path.relativize(jarPath).toString().replace('\\', '/')
            val isApplicationJar = jarContainsIncludedPackages(jarPath)
            if (isApplicationJar) {
                locations.addInputLocation(relativeJar, sources, jarPath, SourceType.Application)
                log("  + Loading application JAR from directory: $relativeJar")
            } else if (config.includeLibraries && matchesLibraryFilter(jarPath.fileName.toString())) {
                locations.addInputLocation(relativeJar, sources, jarPath, SourceType.Library)
                log("  + Loading library JAR from directory: $relativeJar")
            }
        }

        if (locations.isEmpty()) {
            locations.addInputLocation(path.fileName.toString(), sources, path, SourceType.Application)
        }

        return InputLocations(locations, sources)
    }

    private fun containsLooseClassFiles(path: Path): Boolean {
        if (!path.isDirectory()) return false
        return Files.walk(path).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .anyMatch {
                    it.fileName.toString().endsWith(".class", ignoreCase = true) &&
                        !it.toString().contains(".jar!")
                }
        }
    }

    /**
     * Check if this is a Spring Boot fat JAR by looking for BOOT-INF directory
     */
    private fun isSpringBootJar(path: Path): Boolean {
        if (path.extension.lowercase() != "jar") return false

        return try {
            ZipFile(path.toFile()).use { zip ->
                zip.getEntry(JavaArchiveLayout.BOOT_INF_CLASSES) != null
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
     * - [JavaArchiveLayout.BOOT_INF_CLASSES] - Application classes
     * - [JavaArchiveLayout.BOOT_INF_LIB] - Dependency JARs
     */
    private fun createSpringBootInputLocations(path: Path): InputLocations {
        val locations = mutableListOf<AnalysisInputLocation>()
        val sources = mutableMapOf<AnalysisInputLocation, String>()
        val tempDir = Files.createTempDirectory("graphite-springboot")

        try {
            ZipFile(path.toFile()).use { zip ->
                // Extract Spring Boot application classes.
                val classesDir = tempDir.resolve("classes")
                Files.createDirectories(classesDir)

                zip.entries().asSequence()
                    .filter { it.name.startsWith(JavaArchiveLayout.BOOT_INF_CLASSES) && !it.isDirectory }
                    .forEach { entry ->
                        val relativePath = entry.name.removePrefix(JavaArchiveLayout.BOOT_INF_CLASSES)
                        val targetFile = classesDir.resolve(relativePath)
                        Files.createDirectories(targetFile.parent)
                        zip.getInputStream(entry).use { input ->
                            Files.copy(input, targetFile)
                        }
                    }

                locations.addInputLocation(JavaArchiveLayout.BOOT_INF_CLASSES, sources, classesDir, SourceType.Application)

                // Optionally include libraries
                if (config.includeLibraries) {
                    val libDir = tempDir.resolve("lib")
                    Files.createDirectories(libDir)

                    zip.entries().asSequence()
                        .filter { it.name.startsWith(JavaArchiveLayout.BOOT_INF_LIB) && it.name.endsWith(JavaArchiveLayout.JAR_EXTENSION) }
                        .filter { entry -> matchesLibraryFilter(entry.name.substringAfterLast("/")) }
                        .forEach { entry ->
                            val jarName = entry.name.substringAfterLast("/")
                            val targetFile = libDir.resolve(jarName)
                            zip.getInputStream(entry).use { input ->
                                Files.copy(input, targetFile)
                            }
                            // Only add JAR if it contains classes from included packages
                            if (jarContainsIncludedPackages(targetFile)) {
                                locations.addInputLocation(jarName, sources, targetFile, SourceType.Library)
                            }
                        }
                }
            }
        } catch (e: Exception) {
            // Cleanup on failure
            tempDir.toFile().deleteRecursively()
            throw e
        }

        return InputLocations(locations, sources)
    }

    /**
     * Create input locations for WAR file.
     *
     * WAR layout:
     * - [JavaArchiveLayout.WEB_INF_CLASSES] - Application classes
     * - [JavaArchiveLayout.WEB_INF_LIB] - Dependency JARs
     */
    private fun createWarInputLocations(path: Path): InputLocations {
        val locations = mutableListOf<AnalysisInputLocation>()
        val sources = mutableMapOf<AnalysisInputLocation, String>()
        val tempDir = Files.createTempDirectory("graphite-war")

        try {
            ZipFile(path.toFile()).use { zip ->
                // Extract WAR application classes.
                val classesDir = tempDir.resolve("classes")
                Files.createDirectories(classesDir)

                var classFileCount = 0
                zip.entries().asSequence()
                    .filter { it.name.startsWith(JavaArchiveLayout.WEB_INF_CLASSES) && !it.isDirectory }
                    .forEach { entry ->
                        val relativePath = entry.name.removePrefix(JavaArchiveLayout.WEB_INF_CLASSES)
                        val targetFile = classesDir.resolve(relativePath)
                        Files.createDirectories(targetFile.parent)
                        zip.getInputStream(entry).use { input ->
                            Files.copy(input, targetFile)
                        }
                        if (entry.name.endsWith(JavaArchiveLayout.CLASS_EXTENSION)) classFileCount++
                }

                log("Extracted $classFileCount class files from ${JavaArchiveLayout.WEB_INF_CLASSES}")
                locations.addInputLocation(JavaArchiveLayout.WEB_INF_CLASSES, sources, classesDir, SourceType.Application)

                // Optionally include libraries
                if (config.includeLibraries) {
                    val libDir = tempDir.resolve("lib")
                    Files.createDirectories(libDir)

                    val allJars = zip.entries().asSequence()
                        .filter { it.name.startsWith(JavaArchiveLayout.WEB_INF_LIB) && it.name.endsWith(JavaArchiveLayout.JAR_EXTENSION) }
                        .toList()

                    log("Found ${allJars.size} JARs in ${JavaArchiveLayout.WEB_INF_LIB}")

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
                            locations.addInputLocation(jarName, sources, targetFile, SourceType.Library)
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

        return InputLocations(locations, sources)
    }

    private fun MutableList<AnalysisInputLocation>.addInputLocation(
        sourceName: String,
        sources: MutableMap<AnalysisInputLocation, String>,
        path: Path,
        sourceType: SourceType
    ) {
        val location = PathBasedAnalysisInputLocation.create(path, sourceType)
        add(location)
        sources[location] = sourceName
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
