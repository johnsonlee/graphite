package io.johnsonlee.graphite.sootup.testfixture

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream

/** Creates the SDK shape required by ApkAnalysisInputLocation for fixtures without Android API calls. */
fun minimalAndroidSdk(prefix: String): Path {
    val root = Files.createTempDirectory(prefix)
    val platform = Files.createDirectories(root.resolve("platforms/android-1"))
    JarOutputStream(Files.newOutputStream(platform.resolve("android.jar"))).use { }
    return root
}
