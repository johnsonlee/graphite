package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.ResourceEntry
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.AnnotationVisibility
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.base.BaseAnnotation
import org.jf.dexlib2.base.BaseAnnotationElement
import org.jf.dexlib2.base.value.BaseMethodEncodedValue
import org.jf.dexlib2.base.value.BaseTypeEncodedValue
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.Annotation
import org.jf.dexlib2.iface.AnnotationElement
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.value.EncodedValue
import org.jf.dexlib2.immutable.ImmutableAnnotation
import org.jf.dexlib2.immutable.ImmutableAnnotationElement
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableField
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.value.ImmutableArrayEncodedValue
import org.jf.dexlib2.immutable.value.ImmutableIntEncodedValue
import org.jf.dexlib2.immutable.value.ImmutableStringEncodedValue
import org.jf.dexlib2.immutable.value.ImmutableTypeEncodedValue
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
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

        val names = setOf("fixture.frontend.android.AndroidCases\$GenericBox")
        val declarations = DexClassDeclarations.read(resources, names)

        assertTrue("fixture.frontend.android.AndroidCases\$GenericBox" in declarations)
        assertTrue(declarations.values.any { declaration -> declaration.methods.any { it.signature != null } })
    }

    @Test
    fun `signature fragments preserve Unicode and reject malformed or excessive annotations`() {
        assertEquals("Lé中🚀;", readSignature("Lé", "中", "🚀;"))
        assertNull(readSignature("\uD800"))
        assertNull(readSignature("\uDC00"))
        assertNull(readSignature("a".repeat(1_000_001)))
        assertNull(readSignature(*Array(100_001) { "" }))
        assertNull(readSignatureAnnotation(annotation(
            ImmutableAnnotationElement("value", ImmutableArrayEncodedValue(listOf(ImmutableIntEncodedValue(1))))
        )))
        assertNull(readSignatureAnnotation(annotation(
            ImmutableAnnotationElement("value", ImmutableArrayEncodedValue(emptyList())),
            ImmutableAnnotationElement("extra", ImmutableIntEncodedValue(1))
        )))
        assertNull(readSignatureAnnotation(annotation(
            ImmutableAnnotationElement("value", ImmutableArrayEncodedValue(emptyList())),
            visibility = AnnotationVisibility.RUNTIME
        )))
        listOf(
            ExceptionWithContext("bad annotation"),
            IllegalArgumentException("bad annotation"),
            IndexOutOfBoundsException("bad annotation")
        ).forEach { failure ->
            val base = testDefinition()
            val invalid = object : ClassDef by base {
                override fun getAnnotations(): Set<Annotation> = throw failure
            }
            assertNull(read(invalid).signature)
        }
        val badEnclosingClass = annotation(
            annotationElement(object : BaseTypeEncodedValue() {
                override fun getValue(): String = throw IndexOutOfBoundsException("bad type index")
            }),
            type = "Ldalvik/annotation/EnclosingClass;"
        )
        val enclosingClassBase = testDefinition()
        val invalidEnclosingClass = object : ClassDef by enclosingClassBase {
            override fun getAnnotations() = setOf(badEnclosingClass)
        }
        assertNull(read(invalidEnclosingClass).enclosingClass)
        val badEnclosingMethod = annotation(
            annotationElement(object : BaseMethodEncodedValue() {
                override fun getValue(): MethodReference = throw ExceptionWithContext("bad method index")
            }),
            type = "Ldalvik/annotation/EnclosingMethod;"
        )
        val enclosingMethodBase = testDefinition()
        val invalidEnclosingMethod = object : ClassDef by enclosingMethodBase {
            override fun getAnnotations() = setOf(badEnclosingMethod)
        }
        assertNull(read(invalidEnclosingMethod).enclosingMethod)
    }

    @Test
    fun `dex backed signature rejects an oversized string before projection`() {
        val annotation = ImmutableAnnotation(
            AnnotationVisibility.SYSTEM,
            "Ldalvik/annotation/Signature;",
            listOf(
                ImmutableAnnotationElement(
                    "value",
                    ImmutableArrayEncodedValue(listOf(ImmutableStringEncodedValue("a".repeat(1_000_001))))
                )
            )
        )
        val definition = ImmutableClassDef(
            "Lfixture/Oversized;",
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            listOf(annotation),
            emptyList(),
            emptyList()
        )
        val store = MemoryDataStore()
        DexPool.writeTo(store, ImmutableDexFile(Opcodes.getDefault(), listOf(definition)))
        val dex = DexBackedDexFile.fromInputStream(null, ByteArrayInputStream(store.data))

        assertNull(read(dex.classes.single()).signature)
    }

    @Test
    fun `pinned dexlib exposes the raw string index used by the allocation guard`() {
        assertEquals(Int::class.javaPrimitiveType, requireNotNull(DexClassDeclarations.dexStringIndexField).type)
    }

    @Test
    fun `missing raw string index falls back to the post-decode budget`() {
        val warnings = mutableListOf<String>()
        val store = MemoryDataStore()
        DexPool.writeTo(store, ImmutableDexFile(Opcodes.getDefault(), listOf(classDefinition(
            "Ljava/util/List<Ljava/lang/String;>;"
        ))))
        val dex = DexBackedDexFile.fromInputStream(null, ByteArrayInputStream(store.data))

        val declarations = DexClassDeclarations.read(
            dex.classes.asSequence(),
            setOf("fixture.Duplicate"),
            warnings::add,
            stringIndexField = null
        )

        assertEquals(
            "Ljava/util/List<Ljava/lang/String;>;",
            declarations.getValue("fixture.Duplicate").signature
        )
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("post-decode signature budget"))
    }

    @Test
    fun `invalid dex entries and classes fall back without aborting declaration loading`() {
        val resources = dexResources("classes.dex" to "not a dex".toByteArray())

        assertEquals(emptyMap(), DexClassDeclarations.read(resources, setOf("fixture.Invalid")))
    }

    @Test
    fun `resource failures and invalid class types are reported`() {
        val warnings = mutableListOf<String>()
        val resources = object : ResourceAccessor {
            override fun list(pattern: String) = sequenceOf(ResourceEntry("classes.dex", "fixture"))
            override fun open(path: String) = error("unreadable DEX")
        }
        val base = testDefinition()
        val invalidType = object : ClassDef by base {
            override fun getType() = "not-a-class-descriptor"
        }

        assertEquals(emptyMap(), DexClassDeclarations.read(resources, setOf("fixture.Test"), warnings::add))
        assertEquals(emptyMap(), DexClassDeclarations.read(sequenceOf(invalidType), setOf("fixture.Test"), warnings::add))
        assertEquals(2, warnings.size)
        assertTrue(warnings[0].contains("classes.dex"))
        assertTrue(warnings[1].contains("invalid class type"))
    }

    @Test
    fun `resource fallback uses the same duplicate precedence as SootUp`() {
        val primary = dex(classDefinition("Ljava/lang/Object;"))
        val second = dex(classDefinition("Ljava/util/List<Ljava/lang/String;>;"))
        val tenth = dex(classDefinition("Ljava/util/List<Ljava/lang/Integer;>;"))

        val declarations = DexClassDeclarations.read(dexResources(
            "classes10.dex" to tenth,
            "classes.dex" to primary,
            "classes2.dex" to second
        ), setOf("fixture.Duplicate"))

        assertEquals("Ljava/util/List<Ljava/lang/Integer;>;", declarations.getValue("fixture.Duplicate").signature)
    }

    @Test
    fun `generic resource fallback only opens dex entry names`() {
        val first = dex(classDefinition("Ljava/util/List<Ljava/lang/String;>;"))
        val unusual = dex(classDefinition("Ljava/util/List<Ljava/lang/Integer;>;"))
        val opened = mutableListOf<String>()
        val content = mapOf(
            "classes1.dex" to first,
            "payload.bin" to unusual,
            "not-dex.dat" to "dex\\n039\\u0000broken".toByteArray()
        )
        val resources = object : ResourceAccessor {
            override fun list(pattern: String) = content.keys.asSequence()
                .filter { it.endsWith(".dex") }
                .map { ResourceEntry(it, "fixture") }
            override fun open(path: String) = ByteArrayInputStream(content.getValue(path)).also { opened.add(path) }
        }

        val declarations = DexClassDeclarations.read(resources, setOf("fixture.Duplicate"))

        assertEquals("Ljava/util/List<Ljava/lang/String;>;", declarations.getValue("fixture.Duplicate").signature)
        assertEquals(listOf("classes1.dex"), opened)
    }

    @Test
    fun `APK archive without a dex API falls back to named dex entries`() {
        val apk = Files.createTempFile("declared-types", ".apk")
        ZipOutputStream(Files.newOutputStream(apk)).use { output ->
            output.putNextEntry(ZipEntry("classes.dex"))
            output.write(dex(classDefinition("Ljava/util/List<Ljava/lang/String;>;")))
            output.closeEntry()
        }
        val warnings = mutableListOf<String>()

        try {
            val declarations = ArchiveResourceAccessor.create(apk).use { resources ->
                DexClassDeclarations.read(resources, setOf("fixture.Duplicate"), warnings::add)
            }

            assertEquals(
                "Ljava/util/List<Ljava/lang/String;>;",
                declarations.getValue("fixture.Duplicate").signature
            )
            assertEquals(1, warnings.size)
            assertTrue(warnings.single().contains("no DEX API"))
        } finally {
            Files.deleteIfExists(apk)
        }
    }

    @Test
    fun `resource fallback deduplicates nested dex basenames like SootUp`() {
        val first = dex(classDefinition("Ljava/util/List<Ljava/lang/String;>;"))
        val nested = dex(classDefinition("Ljava/util/List<Ljava/lang/Integer;>;"))
        val opened = mutableListOf<String>()
        val content = linkedMapOf("classes2.dex" to first, "assets/classes2.dex" to nested)
        val resources = object : ResourceAccessor {
            override fun list(pattern: String) = content.keys.asSequence().map { ResourceEntry(it, "fixture") }
            override fun open(path: String) = ByteArrayInputStream(content.getValue(path)).also { opened.add(path) }
        }

        val declarations = DexClassDeclarations.read(resources, setOf("fixture.Duplicate"))

        assertEquals("Ljava/util/List<Ljava/lang/String;>;", declarations.getValue("fixture.Duplicate").signature)
        assertEquals(listOf("classes2.dex"), opened)
    }

    @Test
    fun `SootUp failure fallback filters dex magic before basename deduplication`() {
        val valid = dex(classDefinition("Ljava/util/List<Ljava/lang/String;>;"))
        val opened = mutableListOf<String>()
        val content = linkedMapOf(
            "lib/x.bin" to "not a dex".toByteArray(),
            "assets/x.bin" to valid
        )
        val resources = object : ResourceAccessor {
            override fun list(pattern: String) = content.keys.asSequence().map { ResourceEntry(it, "fixture") }
            override fun open(path: String) = ByteArrayInputStream(content.getValue(path)).also { opened.add(path) }
        }

        val declarations = DexClassDeclarations.read(
            DexClassDeclarations.resourceClasses(resources, includeNonDexNames = true) {},
            setOf("fixture.Duplicate")
        )

        assertEquals(
            "Ljava/util/List<Ljava/lang/String;>;",
            declarations.getValue("fixture.Duplicate").signature
        )
        assertEquals(listOf("lib/x.bin", "assets/x.bin"), opened)
    }

    @Test
    fun `initializer enclosure without an outer field remains unknown`() {
        val annotations = listOf(
            ImmutableAnnotation(
                AnnotationVisibility.SYSTEM,
                "Ldalvik/annotation/EnclosingClass;",
                listOf(ImmutableAnnotationElement("value", ImmutableTypeEncodedValue("Lfixture/Outer;")))
            ),
            ImmutableAnnotation(
                AnnotationVisibility.SYSTEM,
                "Ldalvik/annotation/InnerClass;",
                listOf(ImmutableAnnotationElement("accessFlags", ImmutableIntEncodedValue(0)))
            )
        )
        val definition = ImmutableClassDef(
            "Lfixture/InitializerLocal;",
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            annotations,
            emptyList(),
            emptyList()
        )

        val declaration = DexClassDeclarations.read(
            dexResources("classes.dex" to dex(definition)),
            setOf("fixture.InitializerLocal")
        )
            .getValue("fixture.InitializerLocal")

        assertEquals("fixture.Outer", declaration.enclosingClass)
        assertNull(declaration.enclosingInstance)
    }

    @Test
    fun `only requested class declarations are decoded`() {
        var ignoredDecoded = false
        val base = testDefinition(type = "Lfixture/Ignored;")
        val ignored = object : ClassDef by base {
            override fun getAnnotations(): Set<Annotation> {
                ignoredDecoded = true
                return emptySet()
            }
        }
        val requested = testDefinition(type = "Lfixture/Requested;")

        val declarations = DexClassDeclarations.read(
            sequenceOf(ignored, requested),
            setOf("fixture.Requested")
        )

        assertEquals(setOf("fixture.Requested"), declarations.keys)
        assertFalse(ignoredDecoded)
    }

    @Test
    fun `requested class annotations are decoded once`() {
        val annotations = listOf(
            annotation(ImmutableAnnotationElement(
                "value",
                ImmutableArrayEncodedValue(listOf(ImmutableStringEncodedValue("Ljava/lang/Object;")))
            )),
            annotation(
                ImmutableAnnotationElement("value", ImmutableTypeEncodedValue("Lfixture/Outer;")),
                type = "Ldalvik/annotation/EnclosingClass;"
            ),
            annotation(
                ImmutableAnnotationElement("accessFlags", ImmutableIntEncodedValue(0)),
                ImmutableAnnotationElement("name", ImmutableStringEncodedValue("Member")),
                type = "Ldalvik/annotation/InnerClass;"
            )
        )
        val base = testDefinition(type = "Lfixture/Outer\$Member;", annotations = annotations)
        var annotationReads = 0
        val requested = object : ClassDef by base {
            override fun getAnnotations(): Set<Annotation> {
                annotationReads++
                return base.annotations
            }
        }

        val declaration = read(requested)

        assertEquals(1, annotationReads)
        assertEquals("Ljava/lang/Object;", declaration.signature)
        assertEquals("fixture.Outer", declaration.enclosingClass)
        assertTrue(declaration.hasInnerClassMetadata)
    }

    @Test
    fun `member signature lookup scans annotations once without building a system annotation map`() {
        val signature = annotation(ImmutableAnnotationElement(
            "value",
            ImmutableArrayEncodedValue(listOf(ImmutableStringEncodedValue("TT;")))
        ))
        val base = ImmutableField(
            "Lfixture/Members;", "value", "Ljava/lang/Object;", AccessFlags.PUBLIC.value,
            null, listOf(signature), emptySet()
        )
        var annotationReads = 0
        val field = object : org.jf.dexlib2.iface.Field by base {
            override fun getAnnotations(): Set<Annotation> {
                annotationReads++
                return base.annotations
            }
        }

        val declaration = read(testDefinition(type = "Lfixture/Members;", fields = listOf(field)))

        assertEquals("TT;", declaration.fields.single().signature)
        assertEquals(1, annotationReads)
    }

    @Test
    fun `malformed member descriptors drop only their class declarations`() {
        val invalidField = ImmutableField(
            "Lfixture/InvalidField;",
            "field",
            "Lmissing-semicolon",
            AccessFlags.PUBLIC.value,
            null,
            emptyList(),
            emptySet()
        )
        val invalidMethod = ImmutableMethod(
            "Lfixture/InvalidMethod;",
            "method",
            emptyList(),
            "Lmissing-semicolon",
            AccessFlags.PUBLIC.value,
            emptySet(),
            emptySet(),
            null
        )
        val invalidFieldClass = testDefinition(type = "Lfixture/InvalidField;", fields = listOf(invalidField))
        val invalidMethodClass = testDefinition(type = "Lfixture/InvalidMethod;", methods = listOf(invalidMethod))
        val valid = testDefinition(type = "Lfixture/Valid;")

        val declarations = DexClassDeclarations.read(
            sequenceOf(invalidFieldClass, invalidMethodClass, valid),
            setOf("fixture.InvalidField", "fixture.InvalidMethod", "fixture.Valid")
        )

        assertEquals(setOf("fixture.Valid"), declarations.keys)
    }

    @Test
    fun `named member class retains enclosing instance without synthetic outer field`() {
        val annotations = listOf(
            annotation(
                ImmutableAnnotationElement("value", ImmutableTypeEncodedValue("Lfixture/Outer;")),
                type = "Ldalvik/annotation/EnclosingClass;"
            ),
            annotation(
                ImmutableAnnotationElement("accessFlags", ImmutableIntEncodedValue(0)),
                ImmutableAnnotationElement("name", ImmutableStringEncodedValue("Member")),
                type = "Ldalvik/annotation/InnerClass;"
            )
        )

        val declaration = read(testDefinition(type = "Lfixture/Outer\$Member;", annotations = annotations))

        assertEquals("fixture.Outer", declaration.enclosingClass)
        assertTrue(declaration.enclosingInstance == true)
        assertTrue(declaration.hasInnerClassMetadata)
    }

    @Test
    fun `named member with missing inner access flags retains its enclosing class`() {
        val annotations = listOf(
            annotation(
                ImmutableAnnotationElement("value", ImmutableTypeEncodedValue("Lfixture/Outer;")),
                type = "Ldalvik/annotation/EnclosingClass;"
            ),
            annotation(
                ImmutableAnnotationElement("name", ImmutableStringEncodedValue("Member")),
                type = "Ldalvik/annotation/InnerClass;"
            )
        )

        val declaration = read(testDefinition(type = "Lfixture/Outer\$Member;", annotations = annotations))

        assertEquals("fixture.Outer", declaration.enclosingClass)
        assertNull(declaration.enclosingInstance)
        assertTrue(declaration.hasInnerClassMetadata)
    }

    @Test
    fun `stripped inner metadata uses a synthetic outer field as enclosing-instance evidence`() {
        val annotations = listOf(annotation(
            ImmutableAnnotationElement("value", ImmutableTypeEncodedValue("Lfixture/Outer;")),
            type = "Ldalvik/annotation/EnclosingClass;"
        ))
        val outerField = ImmutableField(
            "Lfixture/Outer\$Local;", "this\$0", "Lfixture/Outer;", AccessFlags.SYNTHETIC.value,
            null, emptyList(), emptySet()
        )

        val declaration = read(testDefinition(
            type = "Lfixture/Outer\$Local;",
            annotations = annotations,
            fields = listOf(outerField)
        ))

        assertEquals("fixture.Outer", declaration.enclosingClass)
        assertTrue(declaration.enclosingInstance == true)
        assertFalse(declaration.hasInnerClassMetadata)
    }

    @Test
    fun `enclosing class survives stripped inner class metadata`() {
        val annotations = listOf(annotation(
            ImmutableAnnotationElement("value", ImmutableTypeEncodedValue("Lfixture/Outer;")),
            type = "Ldalvik/annotation/EnclosingClass;"
        ))

        val declaration = read(testDefinition(type = "Lfixture/Outer\$Local;", annotations = annotations))

        assertEquals("fixture.Outer", declaration.enclosingClass)
        assertNull(declaration.enclosingInstance)
        assertFalse(declaration.hasInnerClassMetadata)
    }

    @Test
    fun `named static member does not retain an enclosing instance`() {
        val annotations = listOf(
            annotation(
                ImmutableAnnotationElement("value", ImmutableTypeEncodedValue("Lfixture/Outer;")),
                type = "Ldalvik/annotation/EnclosingClass;"
            ),
            annotation(
                ImmutableAnnotationElement("accessFlags", ImmutableIntEncodedValue(AccessFlags.STATIC.value)),
                ImmutableAnnotationElement("name", ImmutableStringEncodedValue("Nested")),
                type = "Ldalvik/annotation/InnerClass;"
            )
        )

        val declaration = read(testDefinition(type = "Lfixture/Outer\$Nested;", annotations = annotations))

        assertNull(declaration.enclosingClass)
        assertTrue(declaration.enclosingInstance == false)
        assertTrue(declaration.hasInnerClassMetadata)
    }

    @Test
    fun `malformed requested class reports its declaration loss`() {
        val invalidField = ImmutableField(
            "Lfixture/Invalid;", "field", "Lmissing-semicolon", AccessFlags.PUBLIC.value,
            null, emptyList(), emptySet()
        )
        val warnings = mutableListOf<String>()

        val declarations = DexClassDeclarations.read(
            sequenceOf(testDefinition(type = "Lfixture/Invalid;", fields = listOf(invalidField))),
            setOf("fixture.Invalid"),
            warnings::add
        )

        assertEquals(emptyMap(), declarations)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("fixture.Invalid"))
    }

    private fun classDefinition(signature: String): ImmutableClassDef {
        val annotation = ImmutableAnnotation(
            AnnotationVisibility.SYSTEM,
            "Ldalvik/annotation/Signature;",
            listOf(ImmutableAnnotationElement(
                "value",
                ImmutableArrayEncodedValue(listOf(ImmutableStringEncodedValue(signature)))
            ))
        )
        return ImmutableClassDef(
            "Lfixture/Duplicate;",
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            listOf(annotation),
            emptyList(),
            emptyList()
        )
    }

    private fun dex(definition: ImmutableClassDef): ByteArray {
        val store = MemoryDataStore()
        DexPool.writeTo(store, ImmutableDexFile(Opcodes.getDefault(), listOf(definition)))
        return store.data
    }

    private fun dexResources(vararg entries: Pair<String, ByteArray>): ResourceAccessor {
        val content = entries.toMap()
        return object : ResourceAccessor {
            override fun list(pattern: String) = content.keys.asSequence()
                .filter { pattern == "**" || it.endsWith(".dex") }
                .map { ResourceEntry(it, "fixture") }
            override fun open(path: String) = ByteArrayInputStream(content.getValue(path))
        }
    }

    private fun annotation(
        vararg elements: AnnotationElement,
        visibility: Int = AnnotationVisibility.SYSTEM,
        type: String = "Ldalvik/annotation/Signature;"
    ): Annotation = object : BaseAnnotation() {
        override fun getVisibility() = visibility
        override fun getType() = type
        override fun getElements() = elements.toSet()
    }

    private fun readSignature(vararg fragments: String): String? = readSignatureAnnotation(annotation(
        ImmutableAnnotationElement(
            "value",
            ImmutableArrayEncodedValue(fragments.map(::ImmutableStringEncodedValue))
        )
    ))

    private fun readSignatureAnnotation(annotation: Annotation): String? =
        read(testDefinition(annotations = listOf(annotation))).signature

    private fun read(definition: ClassDef): ClassDeclarations =
        DexClassDeclarations.read(sequenceOf(definition), setOf(definition.type.classNameForTest()))
            .getValue(definition.type.classNameForTest())

    private fun testDefinition(
        type: String = "Lfixture/Test;",
        annotations: Collection<Annotation> = emptyList(),
        fields: Iterable<org.jf.dexlib2.iface.Field> = emptyList(),
        methods: Iterable<org.jf.dexlib2.iface.Method> = emptyList()
    ): ImmutableClassDef = ImmutableClassDef(
        type,
        AccessFlags.PUBLIC.value,
        "Ljava/lang/Object;",
        emptyList(),
        null,
        annotations,
        fields,
        methods
    )

    private fun String.classNameForTest(): String = substring(1, lastIndex).replace('/', '.')

    private fun annotationElement(encodedValue: EncodedValue): AnnotationElement = object : BaseAnnotationElement() {
        override fun getName() = "value"
        override fun getValue() = encodedValue
    }
}
