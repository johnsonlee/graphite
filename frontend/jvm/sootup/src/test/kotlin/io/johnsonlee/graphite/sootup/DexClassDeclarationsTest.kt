package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.ResourceEntry
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.zip.ZipFile
import org.jf.dexlib2.AnnotationVisibility
import org.jf.dexlib2.base.BaseAnnotation
import org.jf.dexlib2.base.BaseAnnotationElement
import org.jf.dexlib2.base.value.BaseMethodEncodedValue
import org.jf.dexlib2.base.value.BaseTypeEncodedValue
import org.jf.dexlib2.iface.Annotatable
import org.jf.dexlib2.iface.AnnotationElement
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.value.EncodedValue
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
        assertNull(DexClassDeclarations.readEnclosingClass(annotatable(
            annotationElement(object : BaseTypeEncodedValue() {
                override fun getValue(): String = throw IndexOutOfBoundsException("bad type index")
            }),
            type = "Ldalvik/annotation/EnclosingClass;"
        )))
        assertNull(DexClassDeclarations.readEnclosingMethod(annotatable(
            annotationElement(object : BaseMethodEncodedValue() {
                override fun getValue(): MethodReference = throw ExceptionWithContext("bad method index")
            }),
            type = "Ldalvik/annotation/EnclosingMethod;"
        )))
    }

    private fun signature(vararg fragments: String): Annotatable = annotatable(
        ImmutableAnnotationElement(
            "value",
            ImmutableArrayEncodedValue(fragments.map(::ImmutableStringEncodedValue))
        )
    )

    private fun annotatable(
        vararg elements: AnnotationElement,
        visibility: Int = AnnotationVisibility.SYSTEM,
        type: String = "Ldalvik/annotation/Signature;"
    ): Annotatable {
        val annotation = object : BaseAnnotation() {
            override fun getVisibility() = visibility
            override fun getType() = type
            override fun getElements() = elements.toSet()
        }
        return object : Annotatable {
            override fun getAnnotations() = setOf(annotation)
        }
    }

    private fun annotationElement(encodedValue: EncodedValue): AnnotationElement = object : BaseAnnotationElement() {
        override fun getName() = "value"
        override fun getValue() = encodedValue
    }
}
