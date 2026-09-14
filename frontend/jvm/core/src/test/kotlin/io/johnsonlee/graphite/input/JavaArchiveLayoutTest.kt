package io.johnsonlee.graphite.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaArchiveLayoutTest {

    @Test
    fun `defines Java archive layout evidence paths and manifest attributes`() {
        assertEquals("BOOT-INF/classes/", JavaArchiveLayout.BOOT_INF_CLASSES)
        assertEquals("BOOT-INF/lib/", JavaArchiveLayout.BOOT_INF_LIB)
        assertEquals("WEB-INF/classes/", JavaArchiveLayout.WEB_INF_CLASSES)
        assertEquals("WEB-INF/lib/", JavaArchiveLayout.WEB_INF_LIB)
        assertEquals("META-INF/MANIFEST.MF", JavaArchiveLayout.META_INF_MANIFEST)
        assertEquals("Main-Class", JavaArchiveLayout.MAIN_CLASS_ATTRIBUTE)
        assertEquals("Start-Class", JavaArchiveLayout.START_CLASS_ATTRIBUTE)
        assertTrue(JavaArchiveLayout.SPRING_BOOT_LAUNCH_JAR_LAUNCHER in JavaArchiveLayout.SPRING_BOOT_LAUNCHERS)
        assertEquals("BOOT-INF/classes/com/example/App.class", JavaArchiveLayout.bootInfClassEntry("com/example/App.class"))
        assertEquals("BOOT-INF/lib/app.jar", JavaArchiveLayout.bootInfLibEntry("app.jar"))
        assertEquals("WEB-INF/classes/com/example/App.class", JavaArchiveLayout.webInfClassEntry("/com/example/App.class"))
        assertEquals("WEB-INF/lib/app.jar", JavaArchiveLayout.webInfLibEntry("/app.jar"))
    }
}
