package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.JavaArchiveLayout
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.StandardLocation
import javax.tools.ToolProvider
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Build a regular JAR from compiled test classes under the given prefix.
 */
internal fun buildJarFromClasses(
    classesDir: Path,
    classPathPrefix: String,
    jarName: String = "test.jar"
): Path {
    val jarPath = Files.createTempFile(jarName.removeSuffix(".jar"), ".jar")

    JarOutputStream(Files.newOutputStream(jarPath), Manifest().apply {
        mainAttributes.putValue("Manifest-Version", "1.0")
    }).use { jos ->
        val baseDir = classesDir.toFile()
        val prefixDir = File(baseDir, classPathPrefix)
        if (prefixDir.exists()) {
            prefixDir.walk()
                .filter { it.isFile && it.name.endsWith(".class") }
                .forEach { classFile ->
                    val entryName = classFile.relativeTo(baseDir).path.replace(File.separatorChar, '/')
                    appendArchiveFile(jos, classFile.toPath(), entryName)
                }
        }
    }

    return jarPath
}

/**
 * Build a Spring Boot-style JAR with [JavaArchiveLayout.BOOT_INF_CLASSES] layout.
 */
internal fun buildSpringBootJar(classesDir: Path, classPathPrefix: String): Path {
    val jarPath = Files.createTempFile("springboot", ".jar")

    JarOutputStream(Files.newOutputStream(jarPath), Manifest().apply {
        mainAttributes.putValue("Manifest-Version", "1.0")
        mainAttributes.putValue(JavaArchiveLayout.SPRING_BOOT_CLASSES_ATTRIBUTE, JavaArchiveLayout.BOOT_INF_CLASSES)
    }).use { jos ->
        // Add application classes directory entry.
        jos.putNextEntry(JarEntry(JavaArchiveLayout.BOOT_INF_CLASSES))
        jos.closeEntry()

        val baseDir = classesDir.toFile()
        val prefixDir = File(baseDir, classPathPrefix)
        if (prefixDir.exists()) {
            prefixDir.walk()
                .filter { it.isFile && it.name.endsWith(".class") }
                .forEach { classFile ->
                    val relativePath = classFile.relativeTo(baseDir).path.replace(File.separatorChar, '/')
                    appendArchiveFile(jos, classFile.toPath(), JavaArchiveLayout.bootInfClassEntry(relativePath))
                }
        }
    }

    return jarPath
}

/**
 * Build a Spring Boot JAR with application classes and nested libraries.
 */
internal fun buildSpringBootJarWithLibs(
    classesDir: Path,
    classPathPrefix: String,
    libJars: List<Path>
): Path {
    val jarPath = Files.createTempFile("springboot", ".jar")

    JarOutputStream(Files.newOutputStream(jarPath), Manifest().apply {
        mainAttributes.putValue("Manifest-Version", "1.0")
        mainAttributes.putValue(JavaArchiveLayout.SPRING_BOOT_CLASSES_ATTRIBUTE, JavaArchiveLayout.BOOT_INF_CLASSES)
        mainAttributes.putValue(JavaArchiveLayout.SPRING_BOOT_LIB_ATTRIBUTE, JavaArchiveLayout.BOOT_INF_LIB)
    }).use { jos ->
        // Add application classes.
        jos.putNextEntry(JarEntry(JavaArchiveLayout.BOOT_INF_CLASSES))
        jos.closeEntry()

        val baseDir = classesDir.toFile()
        val prefixDir = File(baseDir, classPathPrefix)
        if (prefixDir.exists()) {
            prefixDir.walk()
                .filter { it.isFile && it.name.endsWith(".class") }
                .forEach { classFile ->
                    val relativePath = classFile.relativeTo(baseDir).path.replace(File.separatorChar, '/')
                    appendArchiveFile(jos, classFile.toPath(), JavaArchiveLayout.bootInfClassEntry(relativePath))
                }
        }

        // Add nested libraries.
        jos.putNextEntry(JarEntry(JavaArchiveLayout.BOOT_INF_LIB))
        jos.closeEntry()

        libJars.forEach { libJar ->
            val jarName = libJar.fileName.toString()
            jos.putNextEntry(JarEntry(JavaArchiveLayout.bootInfLibEntry(jarName)))
            Files.newInputStream(libJar).use { it.copyTo(jos) }
            jos.closeEntry()
        }
    }

    return jarPath
}

/**
 * Build a WAR file with [JavaArchiveLayout.WEB_INF_CLASSES] layout.
 */
internal fun buildWarFile(classesDir: Path, classPathPrefix: String): Path {
    val warPath = Files.createTempFile("test", ".war")

    ZipOutputStream(Files.newOutputStream(warPath)).use { zos ->
        // Add application classes.
        zos.putNextEntry(ZipEntry(JavaArchiveLayout.WEB_INF_CLASSES))
        zos.closeEntry()

        val baseDir = classesDir.toFile()
        val prefixDir = File(baseDir, classPathPrefix)
        if (prefixDir.exists()) {
            prefixDir.walk()
                .filter { it.isFile && it.name.endsWith(".class") }
                .forEach { classFile ->
                    val relativePath = classFile.relativeTo(baseDir).path.replace(File.separatorChar, '/')
                    appendArchiveFile(zos, classFile.toPath(), JavaArchiveLayout.webInfClassEntry(relativePath))
                }
        }
    }

    return warPath
}

/**
 * Build a WAR file with application classes and nested libraries.
 */
internal fun buildWarFileWithLibs(
    classesDir: Path,
    classPathPrefix: String,
    libJars: List<Path>
): Path {
    val warPath = Files.createTempFile("test", ".war")

    ZipOutputStream(Files.newOutputStream(warPath)).use { zos ->
        // Add application classes.
        zos.putNextEntry(ZipEntry(JavaArchiveLayout.WEB_INF_CLASSES))
        zos.closeEntry()

        val baseDir = classesDir.toFile()
        val prefixDir = File(baseDir, classPathPrefix)
        if (prefixDir.exists()) {
            prefixDir.walk()
                .filter { it.isFile && it.name.endsWith(".class") }
                .forEach { classFile ->
                    val relativePath = classFile.relativeTo(baseDir).path.replace(File.separatorChar, '/')
                    appendArchiveFile(zos, classFile.toPath(), JavaArchiveLayout.webInfClassEntry(relativePath))
                }
        }

        // Add nested libraries.
        zos.putNextEntry(ZipEntry(JavaArchiveLayout.WEB_INF_LIB))
        zos.closeEntry()

        libJars.forEach { libJar ->
            val jarName = libJar.fileName.toString()
            zos.putNextEntry(ZipEntry(JavaArchiveLayout.webInfLibEntry(jarName)))
            Files.newInputStream(libJar).use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }

    return warPath
}

internal fun compileSourcesToJar(
    jarName: String,
    sources: Map<String, String>,
    classpath: List<Path> = emptyList()
): Path {
    val compiler = ToolProvider.getSystemJavaCompiler()
    assertNotNull(compiler, "System Java compiler must be available for test compilation")
    val sourceDir = Files.createTempDirectory("graphite-java-src")
    val classesDir = Files.createTempDirectory("graphite-java-classes")
    val jarPath = Files.createTempFile(jarName.removeSuffix(".jar"), ".jar")

    try {
        sources.forEach { (relativePath, source) ->
            val sourceFile = sourceDir.resolve(relativePath)
            Files.createDirectories(sourceFile.parent)
            Files.writeString(sourceFile, source)
        }

        compiler.getStandardFileManager(null, null, null).use { fileManager ->
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, listOf(classesDir.toFile()))
            if (classpath.isNotEmpty()) {
                fileManager.setLocation(StandardLocation.CLASS_PATH, classpath.map { it.toFile() })
            }
            val units = fileManager.getJavaFileObjectsFromFiles(
                Files.walk(sourceDir).use { paths ->
                    paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".java") }
                        .map { it.toFile() }.toList()
                }
            )
            val success = compiler.getTask(null, fileManager, null, null, null, units).call()
            assertTrue(success, "In-memory test sources should compile successfully")
        }

        JarOutputStream(Files.newOutputStream(jarPath), Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
        }).use { jos ->
            appendClassDirectory(jos, classesDir)
        }
        return jarPath
    } finally {
        sourceDir.toFile().deleteRecursively()
        classesDir.toFile().deleteRecursively()
    }
}

internal fun findTestClassesDir(): Path {
    val projectDir = Path.of(System.getProperty("user.dir"))
    val submodulePath = projectDir.resolve("build/classes/java/test")
    val rootPath = projectDir.resolve("frontend/jvm/sootup/build/classes/java/test")
    return if (submodulePath.exists()) submodulePath else rootPath
}

internal fun createAndroidJar(platforms: Path, api: Int): Path {
    val platform = Files.createDirectories(platforms.resolve("android-$api"))
    val androidJar = platform.resolve("android.jar")
    Files.write(androidJar, ByteArray(0))
    return androidJar
}

internal fun createAndroidTool(directory: Path, name: String): Path {
    val tool = directory.resolve(name)
    Files.write(tool, ByteArray(0))
    tool.toFile().setExecutable(true)
    return tool
}

@Suppress("UNCHECKED_CAST")
internal fun <T> invokeLoaderFilePrivate(
    name: String,
    parameterTypes: Array<Class<*>>,
    vararg args: Any?
): T {
    val method = Class.forName("io.johnsonlee.graphite.sootup.JavaProjectLoaderKt")
        .getDeclaredMethod(name, *parameterTypes)
    method.isAccessible = true
    return try {
        method.invoke(null, *args) as T
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }
}

@Suppress("UNCHECKED_CAST")
internal fun <T> invokePrivate(target: Any, name: String, vararg args: Any?): T {
    val parameterTypes = args.map {
        when (it) {
            is Path -> Path::class.java
            else -> it!!::class.java
        }
    }.toTypedArray()
    val method = operationFor(target, name, *parameterTypes)
    method.isAccessible = true
    return method.invoke(target, *args) as T
}
