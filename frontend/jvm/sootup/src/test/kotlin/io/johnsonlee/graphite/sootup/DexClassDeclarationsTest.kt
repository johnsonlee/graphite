package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.ResourceEntry
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.zip.ZipFile
import org.jf.dexlib2.AnnotationVisibility
import org.jf.dexlib2.iface.Annotatable
import org.jf.dexlib2.immutable.ImmutableAnnotation
import org.jf.dexlib2.immutable.ImmutableAnnotationElement
import org.jf.dexlib2.immutable.value.ImmutableArrayEncodedValue
import org.jf.dexlib2.immutable.value.ImmutableIntEncodedValue
import org.jf.dexlib2.immutable.value.ImmutableStringEncodedValue
import org.jf.util.ExceptionWithContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun `signature fragments preserve Unicode and reject malformed or excessive annotations`() {
        assertEquals("Lé中🚀;", DexClassDeclarations.readSignature(signature("Lé", "中", "🚀;")))
        assertNull(DexClassDeclarations.readSignature(signature("\uD800")))
        assertNull(DexClassDeclarations.readSignature(signature("\uDC00")))
        assertNull(DexClassDeclarations.readSignature(signature("a".repeat(1_000_001))))
        assertNull(DexClassDeclarations.readSignature(signature(*Array(100_001) { "" })))
        assertNull(DexClassDeclarations.readSignature(annotatable(
            ImmutableAnnotationElement("value", ImmutableArrayEncodedValue(listOf(ImmutableIntEncodedValue(1))))
        )))
        assertNull(DexClassDeclarations.readSignature(annotatable(
            ImmutableAnnotationElement("value", ImmutableArrayEncodedValue(emptyList())),
            ImmutableAnnotationElement("extra", ImmutableIntEncodedValue(1))
        )))
        assertNull(DexClassDeclarations.readSignature(annotatable(
            ImmutableAnnotationElement("value", ImmutableArrayEncodedValue(emptyList())),
            visibility = AnnotationVisibility.RUNTIME
        )))
        assertNull(DexClassDeclarations.readSignature(object : Annotatable {
            override fun getAnnotations() = throw ExceptionWithContext("bad annotation")
        }))
        assertNull(DexClassDeclarations.readSignature(object : Annotatable {
            override fun getAnnotations() = throw IllegalArgumentException("bad annotation")
        }))
        assertNull(DexClassDeclarations.readSignature(object : Annotatable {
            override fun getAnnotations() = throw IndexOutOfBoundsException("bad annotation")
        }))
    }

    private fun signature(vararg fragments: String): Annotatable = annotatable(
        ImmutableAnnotationElement(
            "value",
            ImmutableArrayEncodedValue(fragments.map(::ImmutableStringEncodedValue))
        )
    )

    private fun annotatable(
        vararg elements: ImmutableAnnotationElement,
        visibility: Int = AnnotationVisibility.SYSTEM
    ): Annotatable {
        val annotation = ImmutableAnnotation(
            visibility,
            "Ldalvik/annotation/Signature;",
            elements.toList()
        )
        return object : Annotatable {
            override fun getAnnotations() = setOf(annotation)
        }
    }
}
