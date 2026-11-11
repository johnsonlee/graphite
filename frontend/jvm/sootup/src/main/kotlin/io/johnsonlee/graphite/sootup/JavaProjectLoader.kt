package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.ObservedJavaLoad
import io.johnsonlee.graphite.input.BodyInterceptionEvidence

import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.FullGraphBuilder
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MmapGraphBuilder
import io.johnsonlee.graphite.graph.ArtifactRole
import io.johnsonlee.graphite.input.GlobPatterns
import io.johnsonlee.graphite.input.JavaArchiveLayout
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.input.ArchiveResourceAccessor
import io.johnsonlee.graphite.input.NestedArchiveLibraries
import io.johnsonlee.graphite.input.ProjectLoader
import io.johnsonlee.graphite.input.FoldReport
import sootup.apk.frontend.ApkAnalysisInputLocation
import sootup.apk.frontend.DexBodyInterceptors
import sootup.apk.frontend.main.AndroidVersionInfo
import sootup.core.inputlocation.AnalysisInputLocation
import sootup.core.model.SourceType
import sootup.core.interceptor.BodyInterceptor
import sootup.interceptors.BytecodeBodyInterceptors
import sootup.interceptors.LocalSplitter
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import sootup.core.signatures.MethodSignature

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

/**
 * The nested jars of a Spring Boot archive in the order its runtime loads them, from
 * `BOOT-INF/classpath.idx` (one `- "BOOT-INF/lib/name.jar"` line per jar), by jar name;
 * empty when the archive carries no index. The duplicate-class evidence lists sources in
 * this order, since the first is the definition that runs.
 */
private fun springBootClasspathOrder(zip: ZipFile): List<String> {
    val entry = zip.getEntry(JavaArchiveLayout.BOOT_INF_CLASSPATH_INDEX) ?: return emptyList()
    return zip.getInputStream(entry).bufferedReader().useLines { lines ->
        lines.map { it.trim().removePrefix("-").trim().trim('"') }
            .filter { it.endsWith(JavaArchiveLayout.JAR_EXTENSION) }
            .map { it.substringAfterLast('/') }
            .toList()
    }
}

private data class InputLocations(
    val locations: List<AnalysisInputLocation>,
    val sources: Map<AnalysisInputLocation, String>,
    val dexApi: Int? = null,
    /** The source name of the Android platform jar, whose role is [ArtifactRole.PLATFORM]. */
    val platformSource: String? = null,
    /** The nested jars in the order the runtime loads them, when the archive declares it (`BOOT-INF/classpath.idx`). */
    val artifactOrder: List<String> = emptyList()
) {
    /** The role of every source, by its location's source type, see [ArtifactRole]. */
    val roles: Map<String, ArtifactRole>
        get() = sources.entries.associate { (location, name) ->
            name to when {
                name == platformSource -> ArtifactRole.PLATFORM
                location.sourceType == SourceType.Library -> ArtifactRole.LIBRARY
                else -> ArtifactRole.APPLICATION
            }
        }
}

/**
 * The Android platform jar a build of [input] with [config] reads as a library (`includeLibraries`),
 * `null` when the input is not an APK or the build reads no library: the external input, besides
 * the APK itself, that decides which classes the graph has, for a fold selection's provenance.
 */
fun androidPlatformJar(input: Path, config: LoaderConfig): Path? {
    if (!config.includeLibraries || input.extension.lowercase() != APK_EXTENSION_NAME) return null
    return resolveAndroidJar(input, resolveAndroidPlatformsPath(config))
}

private fun createApkInputLocations(
    path: Path,
    config: LoaderConfig,
    folding: ConstantFolding?,
    interceptorFactory: (List<BodyInterceptor>) -> List<BodyInterceptor>,
    onFallback: (String) -> Unit,
    log: (String) -> Unit,
    tracking: BodyInterceptionTracking?
): InputLocations {
    val platforms = resolveAndroidPlatformsPath(config)
    // Dex registers hold unrelated values over time: split them before functional dispatch.
    // TypeAssigner also needs the Android platform hierarchy, which APK-only views omit.
    val defaults = DexBodyInterceptors.Default.bodyInterceptors()
    val dexInterceptors = listOf(LocalSplitter()) + defaults
    val recoveries = buildList {
        if (folding != null) add(BodyRecoveryChain("split dex defaults without constant folding", dexInterceptors))
        add(BodyRecoveryChain("dex defaults without splitting or constant folding", defaults))
    }
    val guarded = RecoveringBodyInterceptor(
        interceptorFactory(folding?.bodyInterceptors(dexInterceptors) ?: dexInterceptors),
        onFallback,
        { builder -> folding?.discard(builder) },
        recoveries.map { it.copy(interceptors = interceptorFactory(it.interceptors)) },
        { retained, _, view, reason -> folding?.discard(retained, retained, view, reason) },
        tracking?.onRecovery ?: {},
        tracking?.onAttempt ?: {}
    )
    val androidVersion = AndroidVersionInfo(path, platforms.toString())
    val apkLocation = ApkAnalysisInputLocation(
        path,
        androidVersion,
        listOf(guarded)
    )
    val locations = mutableListOf<AnalysisInputLocation>(apkLocation)
    val sources = mutableMapOf<AnalysisInputLocation, String>(apkLocation to path.fileName.toString())
    var platformSource: String? = null

    if (config.includeLibraries) {
        val androidJar = resolveAndroidJar(androidVersion.api_version, platforms)
        val sourceName = platforms.relativize(androidJar).toString().replace('\\', '/')
        // The jar's own location, not a class-path wrapper around it: a class source names the
        // location it was read from, and the adapter's source map must find the platform by it.
        // The chain is the one `JavaClassPathAnalysisInputLocation` installs.
        val androidJarLocation = PathBasedAnalysisInputLocation.create(
            androidJar,
            SourceType.Library,
            folding?.let(::bytecodeInterceptors) ?: BytecodeBodyInterceptors.Default.bodyInterceptors
        )
        locations.add(androidJarLocation)
        sources[androidJarLocation] = sourceName
        platformSource = sourceName
        log("  + Loading Android platform: $sourceName")
    }

    return InputLocations(locations, sources, androidVersion.api_version, platformSource)
}

/**
 * SootUp's default bytecode interceptors with the fold passes, for the Android platform jar:
 * the platform was read through `JavaClassPathAnalysisInputLocation(path, type)`, which
 * installs `BytecodeBodyInterceptors.Default` when given no chain, and its own location keeps
 * that chain, so the fold chain builds on the same one.
 */
private fun bytecodeInterceptors(folding: ConstantFolding): List<BodyInterceptor> =
    folding.bodyInterceptors(BytecodeBodyInterceptors.Default.bodyInterceptors)

private fun resolveAndroidPlatformsPath(config: LoaderConfig): Path =
    resolveAndroidPlatformsPath(config, System.getenv())

internal fun resolveAndroidPlatformsPath(
    config: LoaderConfig,
    environment: Map<String, String>,
    defaultRoots: List<Path>? = null
): Path {
    val configured = config.androidSdk ?: findAndroidSdkRootFromEnvironment(environment)
    if (configured != null) {
        return validateAndroidSdkRoot(configured)
    }
    return discoverAndroidPlatformsPath(defaultRoots ?: defaultAndroidSdkRoots(), environment[PATH_ENV])
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

private fun findAndroidSdkRootFromEnvironment(environment: Map<String, String>): Path? =
    environmentPath(environment, ANDROID_HOME_ENV)
        ?: environmentPath(environment, ANDROID_SDK_ROOT_ENV)

private fun environmentPath(environment: Map<String, String>, name: String): Path? =
    environment[name]?.takeIf { it.isNotBlank() }?.let { Path.of(it) }

private fun discoverAndroidPlatformsPath(
    defaultRoots: List<Path>,
    pathValue: String?
): Path? =
    findValidAndroidPlatformsPath(defaultRoots)
        ?: findValidAndroidPlatformsPath(androidSdkRootsFromTools(pathValue))

internal fun defaultAndroidSdkRoots(
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

private fun resolveAndroidJar(api: Int, platforms: Path): Path {
    val androidJar = platforms.resolve("$ANDROID_PLATFORM_PREFIX$api").resolve(ANDROID_JAR_NAME)
    require(Files.isRegularFile(androidJar)) {
        "Android platform android.jar not found for API $api: $androidJar"
    }
    return androidJar
}

private fun resolveAndroidJar(apkPath: Path, platforms: Path): Path =
    resolveAndroidJar(AndroidVersionInfo(apkPath, platforms.toString()).api_version, platforms)

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
@Suppress("TooManyFunctions") // Input-format handling and the guarded observation lifecycle share one loader configuration.
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

    /**
     * The interceptor chain of the load in progress when it folds constants, `null` for the
     * frontend's own chain. A loader serves one load at a time.
     *
     * `PathBasedAnalysisInputLocation.create(path, type)` runs no body interceptor at all (the
     * ASM frontend's Jimple, locals typed and named as it emits them), so the fold passes go on
     * an empty chain: a body no rule touches is then the one a build without rules produces,
     * byte for byte. `BytecodeBodyInterceptors.Default` would add `LocalSplitter` and the
     * rest to every method and change graphs the rules never named.
     */
    private var foldInterceptors: List<BodyInterceptor>? = null

    /** Number of APK method bodies kept without interception during the latest load. */
    var bodyInterceptionFallbackCount: Int = 0
        private set

    /** Per-loader seam for exercising failures at the APK frontend's interception boundary. */
    internal var apkInterceptorFactory: (List<BodyInterceptor>) -> List<BodyInterceptor> = { it }

    /** Warning destination when verbose logging is disabled; independent for each loader. */
    internal var warningSink: (String) -> Unit = { System.err.println(it) }

    private val loading = AtomicBoolean()

    override fun load(path: Path): Graph = withLoad { loadGraph(path, null) { graph, _ -> graph } }

    /**
     * Stream immutable snapshots of the exact bodies this invocation analyses, including
     * admitted dependency bodies, then return the same graph with final evidence. This never
     * broadens scope, runs library domain extensions or performs a second build/selection.
     * Snapshots may be retained; all provisional observations must be discarded if this throws.
     * An observer/copy failure aborts the load. Ordinary load(path) does no snapshot work.
     *
     * A loader accepts one invocation at a time (including callbacks); overlapping/reentrant
     * invocations fail immediately. Use distinct loaders and mutable sinks for concurrent loads.
     */
    fun loadObserved(path: Path, observer: FoldedBodyObserver): ObservedJavaLoad = withLoad {
        FoldedBodyDelivery(observer).use { delivery ->
            loadGraph(path, delivery) { graph, report -> delivery.finish(graph, report) }
        }
    }

    private inline fun <T> withLoad(action: () -> T): T {
        check(loading.compareAndSet(false, true)) { "JavaProjectLoader already has a load in progress" }
        return try {
            action()
        } finally {
            foldInterceptors = null
            loading.set(false)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Callback/VM failures must preserve their cause while releasing a failed build.
    private fun <T> loadGraph(
        path: Path, delivery: FoldedBodyDelivery?, complete: (Graph, FoldReport?) -> T
    ): T {
        bodyInterceptionFallbackCount = 0
        val folding = config.folding?.let { ConstantFolding(it.rules) }
        foldInterceptors = folding?.bodyInterceptors(emptyList())
        val recoveries = delivery?.let { HashMap<MethodSignature, BodyInterceptionEvidence>() }
        val tracking = recoveries?.let { receipts ->
            BodyInterceptionTracking(
                onRecovery = { recovery ->
                    receipts[recovery.method] = BodyInterceptionEvidence(recovery.chain, recovery.reason)
                },
                onAttempt = { signature -> receipts.remove(signature) }
            )
        }
        val inputLocations = createInputLocations(path, folding, tracking)
        val view = createJavaView(inputLocations.locations)

        val resourceAccessor = ArchiveResourceAccessor.create(path, inputLocations.dexApi)
        val builder = graphBuilderFactory()
        try {
            val adapter = SootUpAdapter(
                view, config,
                resourceAccessor = resourceAccessor,
                declarationWarning = ::warn,
                inputLocationSources = inputLocations.sources,
                artifactRoles = inputLocations.roles,
                singleArtifactSource = singleArtifactSource(path),
                artifactOrder = inputLocations.artifactOrder,
                graphBuilder = builder,
                preFoldOrdinals = folding?.let { it::ordinalsBeforeFolding } ?: { null }
            )
            if (delivery != null) {
                adapter.bodyDelivery = delivery
                adapter.methodFoldObservation = { signature -> folding?.observation(signature) }
                adapter.methodRecovery = { signature -> recoveries?.get(signature) }
            }
            val graph = adapter.buildGraph()
            if (delivery != null) adapter.verifyObservedFolding()
            if (bodyInterceptionFallbackCount > 0) {
                warn("Recovered $bodyInterceptionFallbackCount APK method body/bodies after interceptor failures")
            }
            val report = folding?.report()
            if (report != null) config.folding?.onReport?.invoke(report)
            return complete(graph, report)
        } catch (failure: Throwable) {
            suppressCleanupFailure(failure) { (builder as? MmapGraphBuilder)?.abort() }
            suppressCleanupFailure(failure) { resourceAccessor.close() }
            inputLocations.locations.forEach { location -> suppressCleanupFailure(failure) { location.close() } }
            throw failure
        }
    }

    @Suppress("TooGenericExceptionCaught") // Cleanup must never replace the original analysis or observer failure.
    private fun suppressCleanupFailure(failure: Throwable, cleanup: () -> Unit) {
        try {
            cleanup()
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        }
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

    private fun createInputLocations(
        path: Path, folding: ConstantFolding?, tracking: BodyInterceptionTracking?
    ): InputLocations {
        return when {
            path.isDirectory() -> createDirectoryInputLocations(path)
            path.extension.lowercase() == APK_EXTENSION_NAME -> createApkInputLocations(
                path, config, folding, apkInterceptorFactory,
                { message ->
                    bodyInterceptionFallbackCount++
                    warn(message)
                }, ::log, tracking
            )
            isSpringBootJar(path) -> createSpringBootInputLocations(path)
            isWarFile(path) -> createWarInputLocations(path)
            else -> {
                val location = inputLocation(path, SourceType.Application)
                InputLocations(
                    locations = listOf(location),
                    sources = mapOf(location to path.fileName.toString())
                )
            }
        }
    }

    /**
     * An input location whose classes this frontend parses once each and hands to the view
     * behind SootUp's lazy class source ([ParsedClassLocation]), with the frontend's empty
     * interceptor chain or the fold chain when folding.
     */
    private fun inputLocation(path: Path, sourceType: SourceType): AnalysisInputLocation =
        ParsedClassLocation(path, sourceType, foldInterceptors ?: emptyList())

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
        } catch (_: java.io.IOException) {
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
        var artifactOrder: List<String> = emptyList()
        var completed = false

        try {
            ZipFile(path.toFile()).use { zip ->
                artifactOrder = springBootClasspathOrder(zip)
                // Extract Spring Boot application classes.
                val classesDir = tempDir.resolve("classes")
                Files.createDirectories(classesDir)

                extractArchiveClasses(zip, JavaArchiveLayout.BOOT_INF_CLASSES, classesDir)

                locations.addInputLocation(JavaArchiveLayout.BOOT_INF_CLASSES, sources, classesDir, SourceType.Application)

                // Optionally include libraries
                val nested = if (config.includeLibraries) {
                    NestedArchiveLibraries(::matchesLibraryFilter, ::jarInScope, ::log).springBoot(zip, tempDir, artifactOrder)
                } else emptyList()
                nested.forEach { (jarName, targetFile) ->
                    locations.addInputLocation(jarName, sources, targetFile, SourceType.Library)
                }
            }
            completed = true
        } finally {
            if (!completed) tempDir.toFile().deleteRecursively()
        }

        return InputLocations(locations, sources, artifactOrder = artifactOrder)
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
        var completed = false

        try {
            ZipFile(path.toFile()).use { zip ->
                // Extract WAR application classes.
                val classesDir = tempDir.resolve("classes")
                Files.createDirectories(classesDir)

                val classFileCount = extractArchiveClasses(zip, JavaArchiveLayout.WEB_INF_CLASSES, classesDir)

                log("Extracted $classFileCount class files from ${JavaArchiveLayout.WEB_INF_CLASSES}")
                locations.addInputLocation(JavaArchiveLayout.WEB_INF_CLASSES, sources, classesDir, SourceType.Application)

                // Optionally include libraries
                val nested = if (config.includeLibraries) {
                    NestedArchiveLibraries(::matchesLibraryFilter, ::jarInScope, ::log).war(zip, tempDir)
                } else emptyList()
                nested.forEach { (jarName, targetFile) ->
                    locations.addInputLocation(jarName, sources, targetFile, SourceType.Library)
                }
            }
            completed = true
        } finally {
            if (!completed) tempDir.toFile().deleteRecursively()
        }

        return InputLocations(locations, sources)
    }

    private fun MutableList<AnalysisInputLocation>.addInputLocation(
        sourceName: String,
        sources: MutableMap<AnalysisInputLocation, String>,
        path: Path,
        sourceType: SourceType
    ) {
        val location = inputLocation(path, sourceType)
        add(location)
        sources[location] = sourceName
    }

    private fun log(message: String) {
        config.verbose?.invoke(message)
    }

    private fun warn(message: String) {
        val warning = "Warning: $message"
        config.verbose?.invoke(warning) ?: warningSink(warning)
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
        return jarContainsPackages(jarPath, config.includePackages)
    }

    /**
     * Whether a library jar named [jarName] holds something the build analyses: a class of the
     * included packages, or what the dependency body scope names (the jar by its name, or a class
     * of one of its packages). A jar that holds neither is not loaded.
     */
    private fun jarInScope(jarPath: Path, jarName: String): Boolean {
        if (jarContainsIncludedPackages(jarPath)) return true
        val scope = config.dependencyBodies
        return scope.coversArtifact(jarName) || (scope.packages.isNotEmpty() && jarContainsPackages(jarPath, scope.packages))
    }

    private fun jarContainsPackages(jarPath: Path, packages: List<String>): Boolean = try {
        ZipFile(jarPath.toFile()).use { jar ->
            jar.entries().asSequence()
                .filter { it.name.endsWith(JavaArchiveLayout.CLASS_EXTENSION) }
                .any { entry ->
                    val className = entry.name
                        .removeSuffix(JavaArchiveLayout.CLASS_EXTENSION)
                        .replace('/', '.')
                    packages.any { pkg ->
                        className.startsWith(pkg)
                    }
                }
        }
    } catch (_: java.io.IOException) {
        true // An unreadable JAR must not be excluded by the package prefilter.
    }

    private fun matchesGlobPattern(name: String, pattern: String): Boolean = GlobPatterns.matches(name, pattern)
}

private fun copyArchiveEntry(zip: ZipFile, entry: java.util.zip.ZipEntry, targetFile: Path) {
    zip.getInputStream(entry).use { input -> Files.copy(input, targetFile) }
}

private fun extractArchiveClasses(zip: ZipFile, prefix: String, classesDir: Path): Int =
    zip.entries().asSequence().filter { it.name.startsWith(prefix) && !it.isDirectory }.onEach { entry ->
        val target = classesDir.resolve(entry.name.removePrefix(prefix))
        Files.createDirectories(target.parent)
        copyArchiveEntry(zip, entry, target)
    }.count { it.name.endsWith(JavaArchiveLayout.CLASS_EXTENSION) }
