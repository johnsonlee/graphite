package io.johnsonlee.graphite.input

/**
 * Well-known Java archive layouts used by loaders and architecture inference.
 *
 * Keep these strings centralized because they define persisted class-origin and
 * resource-path evidence. Changing one occurrence without the others breaks C4
 * subject detection and archive resource discovery.
 */
object JavaArchiveLayout {
    private const val BOOT_INF_ROOT = "BOOT-INF/"
    private const val WEB_INF_ROOT = "WEB-INF/"
    private const val META_INF_ROOT = "META-INF/"
    private const val CLASSES_DIRECTORY = "classes/"
    private const val LIB_DIRECTORY = "lib/"
    private const val MANIFEST_FILE = "MANIFEST.MF"
    private const val RECURSIVE_GLOB = "**"

    const val BOOT_INF_CLASSES = "${BOOT_INF_ROOT}${CLASSES_DIRECTORY}"
    const val BOOT_INF_LIB = "${BOOT_INF_ROOT}${LIB_DIRECTORY}"
    const val WEB_INF_CLASSES = "${WEB_INF_ROOT}${CLASSES_DIRECTORY}"
    const val WEB_INF_LIB = "${WEB_INF_ROOT}${LIB_DIRECTORY}"
    const val META_INF_MANIFEST = "${META_INF_ROOT}${MANIFEST_FILE}"

    const val BOOT_INF_GLOB = "${BOOT_INF_ROOT}${RECURSIVE_GLOB}"
    const val WEB_INF_GLOB = "${WEB_INF_ROOT}${RECURSIVE_GLOB}"

    const val JAR_EXTENSION = ".jar"
    const val CLASS_EXTENSION = ".class"

    const val MAIN_CLASS_ATTRIBUTE = "Main-Class"
    const val START_CLASS_ATTRIBUTE = "Start-Class"
    const val SPRING_BOOT_CLASSES_ATTRIBUTE = "Spring-Boot-Classes"
    const val SPRING_BOOT_LIB_ATTRIBUTE = "Spring-Boot-Lib"

    private const val SPRING_BOOT_LOADER_PACKAGE = "org.springframework.boot.loader"
    private const val SPRING_BOOT_LAUNCH_LOADER_PACKAGE = "${SPRING_BOOT_LOADER_PACKAGE}.launch"
    private const val JAR_LAUNCHER_CLASS = "JarLauncher"
    private const val WAR_LAUNCHER_CLASS = "WarLauncher"
    private const val PROPERTIES_LAUNCHER_CLASS = "PropertiesLauncher"

    const val SPRING_BOOT_JAR_LAUNCHER = "${SPRING_BOOT_LOADER_PACKAGE}.${JAR_LAUNCHER_CLASS}"
    const val SPRING_BOOT_LAUNCH_JAR_LAUNCHER = "${SPRING_BOOT_LAUNCH_LOADER_PACKAGE}.${JAR_LAUNCHER_CLASS}"
    const val SPRING_BOOT_WAR_LAUNCHER = "${SPRING_BOOT_LOADER_PACKAGE}.${WAR_LAUNCHER_CLASS}"
    const val SPRING_BOOT_LAUNCH_WAR_LAUNCHER = "${SPRING_BOOT_LAUNCH_LOADER_PACKAGE}.${WAR_LAUNCHER_CLASS}"
    const val SPRING_BOOT_PROPERTIES_LAUNCHER = "${SPRING_BOOT_LOADER_PACKAGE}.${PROPERTIES_LAUNCHER_CLASS}"
    const val SPRING_BOOT_LAUNCH_PROPERTIES_LAUNCHER = "${SPRING_BOOT_LAUNCH_LOADER_PACKAGE}.${PROPERTIES_LAUNCHER_CLASS}"

    val SPRING_BOOT_LAUNCHERS = setOf(
        SPRING_BOOT_JAR_LAUNCHER,
        SPRING_BOOT_LAUNCH_JAR_LAUNCHER,
        SPRING_BOOT_WAR_LAUNCHER,
        SPRING_BOOT_LAUNCH_WAR_LAUNCHER,
        SPRING_BOOT_PROPERTIES_LAUNCHER,
        SPRING_BOOT_LAUNCH_PROPERTIES_LAUNCHER
    )

    fun bootInfClassEntry(relativePath: String): String = entry(BOOT_INF_CLASSES, relativePath)

    fun bootInfLibEntry(jarName: String): String = entry(BOOT_INF_LIB, jarName)

    fun webInfClassEntry(relativePath: String): String = entry(WEB_INF_CLASSES, relativePath)

    fun webInfLibEntry(jarName: String): String = entry(WEB_INF_LIB, jarName)

    private fun entry(prefix: String, relativePath: String): String =
        "${prefix}${relativePath.trimStart('/')}"
}
