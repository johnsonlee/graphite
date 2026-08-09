package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.FullGraphBuilder
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MmapGraphBuilder
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.input.JavaArchiveLayout
import io.johnsonlee.graphite.input.ProjectLoader
import sootup.apk.frontend.ApkAnalysisInputLocation
import sootup.apk.frontend.DexBodyInterceptors
import sootup.apk.frontend.main.AndroidVersionInfo
import sootup.core.inputlocation.AnalysisInputLocation
import sootup.core.model.SourceType
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation
import sootup.java.core.views.JavaView
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.io.path.isDirectory

private const val JAR_EXTENSION_NAME = "jar"
private const val WAR_EXTENSION_NAME = "war"
private const val ZIP_EXTENSION_NAME = "zip"
private const val APK_EXTENSION_NAME = "apk"
private const val ANDROID_JAR_NAME = "android.jar"
private const val ANDROID_PLATFORM_PREFIX = "android-"
private const val ANDROID_PLATFORMS_DIR = "platforms"
private const val ANDROID_HOME_ENV = "ANDROID_HOME"
private const val ANDROID_SDK_ROOT_ENV = "ANDROID_SDK_ROOT"
private const val PATH_ENV = "PATH"
private const val MAC_OS_NAME = "mac"
private const val DARWIN_OS_NAME = "darwin"
private const val WINDOWS_OS_NAME = "win"
private const val ANDROID_DIR_NAME = "Android"
private const val ANDROID_SDK_DIR_NAME = "Sdk"
private val ANDROID_TOOL_NAMES = listOf("adb", "emulator", "sdkmanager")

private data class InputLocations(
    val locations: List<AnalysisInputLocation>,
    val sources: Map<AnalysisInputLocation, String>
)

private fun createApkInputLocations(
    path: Path,
    config: LoaderConfig,
    log: (String) -> Unit
): InputLocations {
    val platforms = resolveAndroidPlatformsPath(config)
    val apkLocation = ApkAnalysisInputLocation(
        path,
        platforms.toString(),
        DexBodyInterceptors.Default.bodyInterceptors()
    )
    val locations = mutableListOf<AnalysisInputLocation>(apkLocation)
    val sources = mutableMapOf<AnalysisInputLocation, String>(apkLocation to path.fileName.toString())

    if (config.includeLibraries) {
        val androidJar = resolveAndroidJar(path, platforms)
        val sourceName = platforms.relativize(androidJar).toString().replace('\\', '/')
        val androidJarLocation = JavaClassPathAnalysisInputLocation(androidJar.toString(), SourceType.Library)
        locations.add(androidJarLocation)
        sources[androidJarLocation] = sourceName
        log("  + Loading Android platform: $sourceName")
    }

    return InputLocations(locations, sources)
}

private fun resolveAndroidPlatformsPath(config: LoaderConfig): Path {
    val configured = config.androidSdk ?: findAndroidSdkRootFromEnvironment()
    if (configured != null) {
        return validateAndroidSdkRoot(configured)
    }
    return discoverAndroidPlatformsPath()
        ?: throw IllegalArgumentException(
            "APK input requires an Android SDK. Pass --android-sdk <sdk-root>, set " +
                "$ANDROID_HOME_ENV or $ANDROID_SDK_ROOT_ENV, " +
                "install the Android SDK in a default location, or put adb, emulator, " +
                "or sdkmanager on PATH."
        )
}

private fun validateAndroidSdkRoot(path: Path): Path {
    val requested = path.toAbsolutePath().normalize()
    val platforms = requested.resolve(ANDROID_PLATFORMS_DIR)
    require(Files.isDirectory(requested)) {
        "Android SDK root does not exist: $requested"
    }
    require(containsAndroidPlatformJar(platforms)) {
        "Android SDK root must contain platforms/android-<api>/android.jar entries: $requested"
    }
    return platforms
}

private fun findAndroidSdkRootFromEnvironment(environment: Map<String, String> = System.getenv()): Path? =
    environmentPath(environment, ANDROID_HOME_ENV)
        ?: environmentPath(environment, ANDROID_SDK_ROOT_ENV)

private fun environmentPath(environment: Map<String, String>, name: String): Path? =
    environment[name]?.takeIf { it.isNotBlank() }?.let { Path.of(it) }

private fun discoverAndroidPlatformsPath(
    defaultRoots: List<Path> = defaultAndroidSdkRoots(),
    pathValue: String? = System.getenv(PATH_ENV)
): Path? =
    findValidAndroidPlatformsPath(defaultRoots)
        ?: findValidAndroidPlatformsPath(androidSdkRootsFromTools(pathValue))

private fun defaultAndroidSdkRoots(
    osName: String = System.getProperty("os.name").orEmpty(),
    userHome: Path? = System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
): List<Path> {
    val roots = linkedSetOf<Path>()
    val lowerOsName = osName.lowercase()

    when {
        MAC_OS_NAME in lowerOsName || DARWIN_OS_NAME in lowerOsName -> {
            userHome?.let { roots.add(it.resolve("Library").resolve(ANDROID_DIR_NAME).resolve("sdk")) }
            roots.add(Path.of("/opt/homebrew/share/android-commandlinetools"))
            roots.add(Path.of("/usr/local/share/android-commandlinetools"))
        }
        WINDOWS_OS_NAME in lowerOsName -> {
            userHome?.let {
                roots.add(it.resolve("AppData").resolve("Local").resolve(ANDROID_DIR_NAME).resolve(ANDROID_SDK_DIR_NAME))
            }
        }
        else -> {
            userHome?.let {
                roots.add(it.resolve(ANDROID_DIR_NAME).resolve(ANDROID_SDK_DIR_NAME))
                roots.add(it.resolve("android-sdk"))
            }
            roots.add(Path.of("/opt/android-sdk"))
            roots.add(Path.of("/usr/local/android-sdk"))
            roots.add(Path.of("/usr/lib/android-sdk"))
        }
    }

    return roots.toList()
}

private fun androidSdkRootsFromTools(pathValue: String?): List<Path> =
    executablePathsFromPath(pathValue)
        .flatMap(::candidateAndroidSdkRootsForTool)
        .distinct()

private fun executablePathsFromPath(pathValue: String?): List<Path> {
    if (pathValue.isNullOrBlank()) {
        return emptyList()
    }
    return pathValue.split(File.pathSeparator)
        .asSequence()
        .filter { it.isNotBlank() }
        .map { Path.of(it) }
        .flatMap { dir ->
            ANDROID_TOOL_NAMES.asSequence()
                .flatMap(::androidToolExecutableNames)
                .map { dir.resolve(it) }
        }
        .filter { Files.isRegularFile(it) }
        .toList()
}

private fun androidToolExecutableNames(toolName: String): Sequence<String> =
    sequenceOf(toolName, "$toolName.exe", "$toolName.bat", "$toolName.cmd")
        .distinct()

private fun candidateAndroidSdkRootsForTool(toolPath: Path): List<Path> =
    sequenceOf(toolPath.toAbsolutePath().normalize(), realPathOrNull(toolPath))
        .filterNotNull()
        .flatMap { path -> path.ancestors() }
        .distinct()
        .toList()

private fun realPathOrNull(path: Path): Path? =
    runCatching { path.toRealPath().normalize() }.getOrNull()

private fun Path.ancestors(): Sequence<Path> =
    generateSequence(parent) { it.parent }

private fun findValidAndroidPlatformsPath(candidates: Iterable<Path>): Path? =
    candidates.asSequence()
        .map { it.toAbsolutePath().normalize().resolve(ANDROID_PLATFORMS_DIR) }
        .distinct()
        .firstOrNull(::containsAndroidPlatformJar)

private fun containsAndroidPlatformJar(path: Path): Boolean {
    if (!Files.isDirectory(path)) {
        return false
    }
    return Files.list(path).use { stream ->
        stream.anyMatch { child ->
            Files.isDirectory(child) &&
                child.fileName.toString().startsWith(ANDROID_PLATFORM_PREFIX) &&
                Files.isRegularFile(child.resolve(ANDROID_JAR_NAME))
        }
    }
}

private fun resolveAndroidJar(apkPath: Path, platforms: Path): Path {
    val versionInfo = AndroidVersionInfo(apkPath, platforms.toString())
    val api = versionInfo.getApi_version()
    val androidJar = platforms.resolve("$ANDROID_PLATFORM_PREFIX$api").resolve(ANDROID_JAR_NAME)
    require(Files.isRegularFile(androidJar)) {
        "Android platform android.jar not found for API $api: $androidJar"
    }
    return androidJar
}

/**
 * SootUp-based loader for Java projects.
 *
 * Supports loading from various sources:
 * - JAR files
 * - WAR files
 * - APK files
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

        val resourceAccessor = ArchiveResourceAccessor.create(path)
        val adapter = SootUpAdapter(
            view, config,
            resourceAccessor = resourceAccessor,
            inputLocationSources = inputLocations.sources,
            singleArtifactSource = singleArtifactSource(path),
            graphBuilder = graphBuilderFactory()
        )
        return adapter.buildGraph()
    }

    private fun singleArtifactSource(path: Path): String? =
        when {
            path.isDirectory() -> null
            isSpringBootJar(path) -> null
            isWarFile(path) -> null
            else -> path.fileName.toString()
        }

    override fun canLoad(path: Path): Boolean {
        if (path.isDirectory()) {
            return true
        }
        val ext = path.extension.lowercase()
        return ext in listOf(JAR_EXTENSION_NAME, WAR_EXTENSION_NAME, ZIP_EXTENSION_NAME, APK_EXTENSION_NAME)
    }

    private fun createInputLocations(path: Path): InputLocations {
        return when {
            path.isDirectory() -> createDirectoryInputLocations(path)
            path.extension.lowercase() == APK_EXTENSION_NAME -> createApkInputLocations(path, config, ::log)
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
                    it.fileName.toString().endsWith(JavaArchiveLayout.CLASS_EXTENSION, ignoreCase = true) &&
                        !it.toString().contains(".jar!")
                }
        }
    }

    /**
     * Check if this is a Spring Boot fat JAR by looking for BOOT-INF directory
     */
    private fun isSpringBootJar(path: Path): Boolean {
        if (path.extension.lowercase() != JAR_EXTENSION_NAME) return false

        return try {
            ZipFile(path.toFile()).use { zip ->
                zip.getEntry(JavaArchiveLayout.BOOT_INF_CLASSES) != null
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun isWarFile(path: Path): Boolean {
        return path.extension.lowercase() == WAR_EXTENSION_NAME
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
                    .filter { it.name.endsWith(JavaArchiveLayout.CLASS_EXTENSION) }
                    .any { entry ->
                        val className = entry.name
                            .removeSuffix(JavaArchiveLayout.CLASS_EXTENSION)
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
