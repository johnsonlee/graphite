package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.ResourceEntry
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DexClassDeclarationsTest {
    @Test
    fun `reads generic declarations from double digit multidex entries`() {
        val apk = Path.of(requireNotNull(System.getProperty("frontend.correctness.android")))
        val dex = ZipFile(apk.toFile()).use { zip ->
            assertFalse(zip.entries().asSequence().any { it.name.endsWith(".class") })
            zip.getInputStream(requireNotNull(zip.getEntry("classes.dex"))).use { it.readBytes() }
        }
        val resources = object : ResourceAccessor {
            override fun list(pattern: String) = sequenceOf(ResourceEntry("classes10.dex", "fixture"))
            override fun open(path: String) = ByteArrayInputStream(dex)
        }

        val declarations = DexClassDeclarations.read(resources)

        assertTrue("fixture.frontend.android.AndroidCases\$GenericBox" in declarations)
        assertTrue(declarations.values.any { declaration -> declaration.methods.any { it.signature != null } })
    }
}
