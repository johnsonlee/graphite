package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidSdkDiscoveryContractTest {
    @Test
    fun `explicit SDK wins over environment and configured SDK avoids fallback discovery`() {
        val sdk = Files.createTempDirectory("sdk-precedence")
        try {
            val platforms = Files.createDirectories(sdk.resolve("platforms/android-21"))
            Files.createFile(platforms.resolve("android.jar"))
            val expected = platforms.parent
            assertEquals(expected, resolveAndroidPlatformsPath(
                LoaderConfig(androidSdk = sdk), mapOf("ANDROID_HOME" to "does-not-exist")
            ) { error("Explicit configuration must not inspect fallback roots") })
            assertEquals(expected, resolveAndroidPlatformsPath(
                LoaderConfig(), mapOf("ANDROID_HOME" to sdk.toString(), "ANDROID_SDK_ROOT" to "does-not-exist")
            ) { error("Environment configuration must not inspect fallback roots") })
            assertEquals(expected, resolveAndroidPlatformsPath(LoaderConfig(), emptyMap()) { listOf(sdk) })
        } finally {
            sdk.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing SDK provides all supported installation choices`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            resolveAndroidPlatformsPath(LoaderConfig(), mapOf("PATH" to "")) { emptyList() }
        }
        assertEquals(
            "APK input requires an Android SDK. Pass --android-sdk <sdk-root>, set " +
                "ANDROID_HOME or ANDROID_SDK_ROOT, install the Android SDK in a default location, " +
                "or put adb, emulator, or sdkmanager on PATH.",
            failure.message
        )
    }

    @Test
    fun `default discovery uses this operating system and home without requiring an installed SDK`() {
        val roots = defaultAndroidSdkRoots()
        val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let(Path::of)
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val expected = when {
            "mac" in os || "darwin" in os -> listOfNotNull(
                home?.resolve("Library/Android/sdk"), Path.of("/opt/homebrew/share/android-commandlinetools"),
                Path.of("/usr/local/share/android-commandlinetools")
            )
            "windows" in os -> listOfNotNull(home?.resolve("AppData/Local/Android/Sdk"))
            else -> listOfNotNull(
                home?.resolve("Android/Sdk"), home?.resolve("android-sdk"),
                Path.of("/opt/android-sdk"), Path.of("/usr/local/android-sdk"), Path.of("/usr/lib/android-sdk")
            )
        }
        assertEquals(expected, roots)
        assertTrue(roots.distinct() == roots, "Discovery must not try a root twice")
    }
}
