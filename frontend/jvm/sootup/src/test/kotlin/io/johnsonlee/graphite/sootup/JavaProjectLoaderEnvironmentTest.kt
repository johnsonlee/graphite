package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaProjectLoaderEnvironmentTest {
    @Test
    fun `Android platform lookup skips non APK inputs and disabled libraries`() {
        val absentSdk = Path.of("missing-sdk-for-disabled-lookup")
        assertNull(androidPlatformJar(Path.of("app.jar"), LoaderConfig(androidSdk = absentSdk, includeLibraries = true)))
        assertNull(androidPlatformJar(Path.of("app.apk"), LoaderConfig(androidSdk = absentSdk, includeLibraries = false)))
        val error = assertFailsWith<IllegalArgumentException> {
            androidPlatformJar(Path.of("app.apk"), LoaderConfig(androidSdk = absentSdk, includeLibraries = true))
        }
        assertTrue(error.message.orEmpty().contains("Android SDK"))
    }

    @Test
    fun `android SDK resolution accepts sdk root only`() {
        val sdkRoot = Files.createTempDirectory("graphite-android-sdk-root")
        val platformsFromRoot = Files.createDirectories(sdkRoot.resolve("platforms"))
        val directPlatforms = Files.createTempDirectory("graphite-android-platforms")
        try {
            createAndroidJar(platformsFromRoot, 36)
            createAndroidJar(directPlatforms, 35)

            assertEquals(
                platformsFromRoot,
                invokeLoaderFilePrivate<Path>(
                    "resolveAndroidPlatformsPath",
                    arrayOf(LoaderConfig::class.java),
                    LoaderConfig(androidSdk = sdkRoot)
                )
            )
            val directPlatformsError = assertFailsWith<IllegalArgumentException> {
                invokeLoaderFilePrivate<Path>(
                    "resolveAndroidPlatformsPath",
                    arrayOf(LoaderConfig::class.java),
                    LoaderConfig(androidSdk = directPlatforms)
                )
            }
            assertTrue(directPlatformsError.message.orEmpty().contains("Android SDK root must contain"))
            assertTrue(
                invokeLoaderFilePrivate<Boolean>(
                    "containsAndroidPlatformJar",
                    arrayOf(Path::class.java),
                    directPlatforms
                )
            )
            assertFalse(
                invokeLoaderFilePrivate<Boolean>(
                    "containsAndroidPlatformJar",
                    arrayOf(Path::class.java),
                    directPlatforms.resolve("missing")
                )
            )
        } finally {
            sdkRoot.toFile().deleteRecursively()
            directPlatforms.toFile().deleteRecursively()
        }
    }

    @Test
    fun `android SDK environment lookup keeps configured priority`() {
        val androidHome = Path.of("/env/home")
        val androidSdkRoot = Path.of("/env/sdk-root")

        assertEquals(
            androidHome,
            invokeLoaderFilePrivate<Path>(
                "findAndroidSdkRootFromEnvironment",
                arrayOf(Map::class.java),
                mapOf(
                    "ANDROID_HOME" to androidHome.toString(),
                    "ANDROID_SDK_ROOT" to androidSdkRoot.toString()
                )
            )
        )
        assertEquals(
            androidSdkRoot,
            invokeLoaderFilePrivate<Path>(
                "findAndroidSdkRootFromEnvironment",
                arrayOf(Map::class.java),
                mapOf("ANDROID_SDK_ROOT" to androidSdkRoot.toString())
            )
        )
        assertNull(
            invokeLoaderFilePrivate<Path?>(
                "findAndroidSdkRootFromEnvironment",
                arrayOf(Map::class.java),
                mapOf("ANDROID_HOME" to "")
            )
        )
    }

    @Test
    fun `android SDK discovery includes platform default install roots`() {
        val home = Path.of("/home/tester")

        val macRoots = invokeLoaderFilePrivate<List<Path>>(
            "defaultAndroidSdkRoots",
            arrayOf(String::class.java, Path::class.java),
            "Mac OS X",
            home
        )
        val linuxRoots = invokeLoaderFilePrivate<List<Path>>(
            "defaultAndroidSdkRoots",
            arrayOf(String::class.java, Path::class.java),
            "Linux",
            home
        )
        val windowsRoots = invokeLoaderFilePrivate<List<Path>>(
            "defaultAndroidSdkRoots",
            arrayOf(String::class.java, Path::class.java),
            "Windows 11",
            home
        )

        assertTrue(macRoots.contains(home.resolve("Library").resolve("Android").resolve("sdk")))
        assertTrue(macRoots.contains(Path.of("/opt/homebrew/share/android-commandlinetools")))
        assertTrue(linuxRoots.contains(home.resolve("Android").resolve("Sdk")))
        assertTrue(linuxRoots.contains(Path.of("/usr/lib/android-sdk")))
        assertTrue(
            windowsRoots.contains(
                home.resolve("AppData").resolve("Local").resolve("Android").resolve("Sdk")
            )
        )
    }

    @Test
    fun `android SDK discovery tries default roots before tool paths`() {
        val defaultSdkRoot = Files.createTempDirectory("graphite-default-android-sdk")
        val toolSdkRoot = Files.createTempDirectory("graphite-tool-android-sdk")
        val defaultPlatforms = Files.createDirectories(defaultSdkRoot.resolve("platforms"))
        val toolPlatforms = Files.createDirectories(toolSdkRoot.resolve("platforms"))
        val sdkmanagerDir = Files.createDirectories(toolSdkRoot.resolve("cmdline-tools/latest/bin"))
        try {
            createAndroidJar(defaultPlatforms, 34)
            createAndroidJar(toolPlatforms, 35)
            createAndroidTool(sdkmanagerDir, "sdkmanager")

            assertEquals(
                defaultPlatforms,
                invokeLoaderFilePrivate<Path>(
                    "discoverAndroidPlatformsPath",
                    arrayOf(List::class.java, String::class.java),
                    listOf(defaultSdkRoot),
                    sdkmanagerDir.toString()
                )
            )
        } finally {
            defaultSdkRoot.toFile().deleteRecursively()
            toolSdkRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `android SDK discovery infers sdk roots from android tools on path`() {
        val sdkRoot = Files.createTempDirectory("graphite-tool-path-android-sdk")
        val platforms = Files.createDirectories(sdkRoot.resolve("platforms"))
        val platformToolsDir = Files.createDirectories(sdkRoot.resolve("platform-tools"))
        val emulatorDir = Files.createDirectories(sdkRoot.resolve("emulator"))
        val sdkmanagerDir = Files.createDirectories(sdkRoot.resolve("cmdline-tools/latest/bin"))
        try {
            createAndroidJar(platforms, 36)
            createAndroidTool(platformToolsDir, "adb")
            createAndroidTool(emulatorDir, "emulator")
            createAndroidTool(sdkmanagerDir, "sdkmanager")

            listOf(platformToolsDir, emulatorDir, sdkmanagerDir).forEach { toolDir ->
                assertEquals(
                    platforms,
                    invokeLoaderFilePrivate<Path>(
                        "discoverAndroidPlatformsPath",
                        arrayOf(List::class.java, String::class.java),
                        emptyList<Path>(),
                        toolDir.toString()
                    )
                )
            }
        } finally {
            sdkRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `android SDK discovery returns null when no fallback candidates are valid`() {
        assertNull(
            invokeLoaderFilePrivate<Path?>(
                "discoverAndroidPlatformsPath",
                arrayOf(List::class.java, String::class.java),
                emptyList<Path>(),
                ""
            )
        )
    }

    @Test
    fun `android SDK resolution reports missing and invalid directories`() {
        val missingSdkRoot = Files.createTempDirectory("graphite-missing-android-sdk")
            .resolve("missing")
        val invalidSdkRoot = Files.createTempDirectory("graphite-invalid-android-sdk")
        try {
            val missing = assertFailsWith<IllegalArgumentException> {
                invokeLoaderFilePrivate<Path>(
                    "resolveAndroidPlatformsPath",
                    arrayOf(LoaderConfig::class.java),
                    LoaderConfig(androidSdk = missingSdkRoot)
                )
            }
            val invalid = assertFailsWith<IllegalArgumentException> {
                invokeLoaderFilePrivate<Path>(
                    "resolveAndroidPlatformsPath",
                    arrayOf(LoaderConfig::class.java),
                    LoaderConfig(androidSdk = invalidSdkRoot)
                )
            }

            assertTrue(missing.message.orEmpty().contains("does not exist"))
            assertTrue(invalid.message.orEmpty().contains("SDK root must contain"))
        } finally {
            missingSdkRoot.parent.toFile().deleteRecursively()
            invalidSdkRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `should extract artifact dependencies from distribution layout at build time`() {
        val distDir = Files.createTempDirectory("graphite-artifact-deps-dist")
        val libDir = Files.createDirectories(distDir.resolve("lib"))
        val supportJar = compileSourcesToJar(
            jarName = "support.jar",
            sources = mapOf(
                "sample/lib2/Support.java" to """
                    package sample.lib2;
                    public class Support {
                        public static String value() { return "ok"; }
                    }
                """.trimIndent()
            )
        )
        val depJar = compileSourcesToJar(
            jarName = "dep.jar",
            classpath = listOf(supportJar),
            sources = mapOf(
                "sample/lib/Dep.java" to """
                    package sample.lib;
                    import sample.lib2.Support;
                    public class Dep {
                        public static String value() { return Support.value(); }
                    }
                """.trimIndent()
            )
        )
        val appJar = compileSourcesToJar(
            jarName = "app.jar",
            classpath = listOf(depJar, supportJar),
            sources = mapOf(
                "sample/app/Main.java" to """
                    package sample.app;
                    import sample.lib.Dep;
                    public class Main {
                        public static void main(String[] args) { Dep.value(); }
                    }
                """.trimIndent()
            )
        )

        try {
            Files.copy(appJar, libDir.resolve("app.jar"))
            Files.copy(depJar, libDir.resolve("dep.jar"))
            Files.copy(supportJar, libDir.resolve("support.jar"))

            val loader = JavaProjectLoader(
                LoaderConfig(
                    includePackages = listOf("sample.app"),
                    includeLibraries = false,
                    buildCallGraph = false
                )
            )

            val graph = loader.load(distDir)
            assertEquals(
                1,
                graph.artifactDependencies()["dep"]?.get("support"),
                "Expected dep.jar to record a bytecode dependency on support.jar"
            )
            assertEquals(
                1,
                graph.artifactDependencies()["app"]?.get("dep"),
                "Expected app.jar to record a bytecode dependency on dep.jar"
            )
        } finally {
            Files.deleteIfExists(appJar)
            Files.deleteIfExists(depJar)
            Files.deleteIfExists(supportJar)
            distDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `should skip artifact dependency extraction for single artifact input`() {
        val appJar = compileSourcesToJar(
            jarName = "single.jar",
            sources = mapOf(
                "sample/single/Helper.java" to """
                    package sample.single;
                    public class Helper {
                        public static String value() { return "ok"; }
                    }
                """.trimIndent(),
                "sample/single/App.java" to """
                    package sample.single;
                    public class App {
                        public static void main(String[] args) { Helper.value(); }
                    }
                """.trimIndent()
            )
        )

        try {
            val loader = JavaProjectLoader(
                LoaderConfig(
                    includePackages = listOf("sample.single"),
                    includeLibraries = false,
                    buildCallGraph = false
                )
            )

            val graph = loader.load(appJar)
            assertTrue(
                graph.artifactDependencies().isEmpty(),
                "Single-jar inputs should not perform artifact-to-artifact dependency extraction"
            )
            assertTrue(
                graph.classOrigins().isEmpty(),
                "Single-jar inputs should not persist redundant class origins"
            )
        } finally {
            Files.deleteIfExists(appJar)
        }
    }
}
