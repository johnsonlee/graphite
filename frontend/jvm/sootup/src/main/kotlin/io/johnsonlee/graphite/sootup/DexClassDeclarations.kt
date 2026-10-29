package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.graph.DeclaredTypeExpansionBudget
import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.ResourceEntry
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.AnnotationVisibility
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.dexbacked.value.DexBackedStringEncodedValue
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.iface.Annotatable
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.value.EncodedValue
import org.jf.dexlib2.iface.value.ArrayEncodedValue
import org.jf.dexlib2.iface.value.IntEncodedValue
import org.jf.dexlib2.iface.value.MethodEncodedValue
import org.jf.dexlib2.iface.value.StringEncodedValue
import org.jf.dexlib2.iface.value.TypeEncodedValue
import org.jf.util.ExceptionWithContext
import java.io.BufferedInputStream
import sootup.apk.frontend.dexpler.DexFileProvider

/** Reads the declaration metadata that Android stores in system annotations in every DEX file. */
internal object DexClassDeclarations {
    private const val SIGNATURE = "Ldalvik/annotation/Signature;"
    private const val ENCLOSING_CLASS = "Ldalvik/annotation/EnclosingClass;"
    private const val ENCLOSING_METHOD = "Ldalvik/annotation/EnclosingMethod;"
    private const val INNER_CLASS = "Ldalvik/annotation/InnerClass;"
    private const val VALUE = "value"
    private val dexName = Regex("classes(?:([2-9]|[1-9][0-9]+))?\\.dex")

    fun read(resources: ResourceAccessor): Map<String, ClassDeclarations> {
        if (resources is ArchiveResourceAccessor && resources.sourcePath != null && resources.dexApi != null) {
            val dexFiles = try {
                DexFileProvider.getInstance().getDexFromSource(resources.sourcePath.toFile(), resources.dexApi)
            } catch (_: Exception) {
                emptyList()
            }
            val classes = dexFiles.asSequence().flatMap { safeClasses(it.base.dexFile) }
            return read(classes)
        }
        return read(resourceClasses(resources))
    }

    internal fun readSignature(annotatable: Annotatable): String? = annotatable.signature()

    internal fun readEnclosingClass(annotatable: Annotatable): String? =
        annotatable.typeAnnotation(ENCLOSING_CLASS)

    internal fun readEnclosingMethod(annotatable: Annotatable): MemberTypeKey? =
        annotatable.enclosingMethod()

    private fun resourceClasses(resources: ResourceAccessor): Sequence<ClassDef> = sequence {
        resources.list("classes*.dex")
            .filter { dexName.matches(it.path) }
            // Match DexFileProvider's order: primary first, then reverse lexical. Its class
            // index is last-wins, so declarations must visit duplicate definitions identically.
            .sortedWith(compareBy<ResourceEntry> { it.path != "classes.dex" }.thenByDescending { it.path })
            .forEach { entry ->
                val classes = try {
                    resources.open(entry.path).use { input ->
                        safeClasses(DexBackedDexFile.fromInputStream(null, BufferedInputStream(input)))
                    }
                } catch (_: Exception) {
                    emptyList()
                }
                yieldAll(classes)
            }
    }

    private fun safeClasses(dexFile: DexFile): List<ClassDef> = try {
        dexFile.classes.mapNotNull { definition ->
            try {
                definition.type to definition
            } catch (_: Exception) {
                null
            }
        }.sortedBy { it.first }.map { it.second }
    } catch (_: Exception) {
        emptyList()
    }

    private fun read(classes: Sequence<ClassDef>): Map<String, ClassDeclarations> {
        val declarations = linkedMapOf<String, ClassDeclarations>()
        classes.forEach { definition ->
            val declaration = try {
                from(definition)
            } catch (_: Exception) {
                return@forEach
            }
            // Android rejects duplicate definitions, but SootUp's multidex index uses the last
            // entry when a malformed archive contains one.
            declarations[declaration.name] = declaration
        }
        return declarations
    }

    private fun from(definition: ClassDef): ClassDeclarations {
        val enclosingMethod = readEnclosingMethod(definition)
        val innerAccess = definition.intAnnotation(INNER_CLASS, "accessFlags")
        val enclosingClass = enclosingMethod?.owner ?: readEnclosingClass(definition)
            ?.className()
            ?.takeIf { innerAccess != null && !AccessFlags.STATIC.isSet(innerAccess) }
        val hasEnclosingField = enclosingClass != null && definition.fields.any { field ->
            AccessFlags.SYNTHETIC.isSet(field.accessFlags) && field.name.startsWith("this\$") &&
                field.type.className() == enclosingClass
        }
        return ClassDeclarations(
            name = definition.type.className(),
            signature = definition.signature(),
            superName = definition.superclass?.className(),
            interfaces = definition.interfaces.map { it.className() },
            fields = definition.fields.map { field ->
                MemberDeclaration(field.name, field.type, field.signature(), field.accessFlags)
            },
            methods = definition.methods.map { method ->
                MemberDeclaration(method.name, method.descriptor(), method.signature(), method.accessFlags)
            },
            enclosingClass = enclosingClass,
            enclosingMethod = enclosingMethod,
            isEnum = AccessFlags.ENUM.isSet(definition.accessFlags),
            enclosingInstance = when {
                enclosingClass == null -> false
                hasEnclosingField -> true
                else -> null
            }
        )
    }

    private fun Annotatable.signature(): String? = try {
        signatureValue()
    } catch (_: ExceptionWithContext) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    @Suppress("ReturnCount") // Every invalid system-annotation shape falls back independently.
    private fun Annotatable.signatureValue(): String? {
        val annotation = systemAnnotation(SIGNATURE) ?: return null
        if (annotation.elements.size != 1) return null
        val value = annotation.elements.single().takeIf { it.name == VALUE }?.value as? ArrayEncodedValue ?: return null
        if (value.value.size > MAX_SIGNATURE_FRAGMENTS) return null
        val signature = StringBuilder()
        var bytes = 0
        for (encoded in value.value) {
            val string = encoded as? StringEncodedValue ?: return null
            if (string is DexBackedStringEncodedValue) {
                // dexlib decodes on getValue(). Bound its temporary String before materializing it.
                val stringIndex = dexStringIndex.getInt(string)
                val stringIdOffset = string.dexFile.stringSection.getOffset(stringIndex)
                val stringDataOffset = string.dexFile.buffer.readSmallUint(stringIdOffset)
                val utf16Length = string.dexFile.dataBuffer.readerAt(stringDataOffset).readSmallUleb128()
                try {
                    // Every UTF-16 code unit contributes at least one byte to valid UTF-8.
                    DeclaredTypeExpansionBudget.validate(0, utf16Length)
                } catch (_: IllegalArgumentException) {
                    return null
                }
            }
            val fragment = string.value
            bytes = fragment.appendValidated(signature, bytes) ?: return null
        }
        return signature.toString()
    }

    private fun Annotatable.typeAnnotation(type: String): String? = decodedAnnotationValue(type, VALUE) {
        (it as? TypeEncodedValue)?.value
    }

    private fun Annotatable.intAnnotation(type: String, name: String): Int? = decodedAnnotationValue(type, name) {
        (it as? IntEncodedValue)?.value
    }

    private fun Annotatable.enclosingMethod(): MemberTypeKey? = decodedAnnotationValue(ENCLOSING_METHOD, VALUE) { value ->
        val reference = (value as? MethodEncodedValue)?.value ?: return@decodedAnnotationValue null
        MemberTypeKey(reference.definingClass.className(), reference.name, reference.descriptor())
    }

    private fun Annotatable.systemAnnotation(type: String) = annotations
        .filter { it.type == type && it.visibility == AnnotationVisibility.SYSTEM }
        .singleOrNull()

    private inline fun <T> Annotatable.decodedAnnotationValue(
        type: String,
        name: String,
        decode: (EncodedValue) -> T?
    ): T? = try {
        systemAnnotation(type)
            ?.elements
            ?.filter { it.name == name }
            ?.singleOrNull()
            ?.value
            ?.let(decode)
    } catch (_: ExceptionWithContext) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    private fun Method.descriptor(): String = (this as MethodReference).descriptor()

    private fun MethodReference.descriptor(): String =
        parameterTypes.joinToString(separator = "", prefix = "(", postfix = ")$returnType")

    private fun String.className(): String =
        if (length >= 2 && first() == 'L' && last() == ';') substring(1, lastIndex).replace('/', '.') else this

    @Suppress("ReturnCount", "MagicNumber") // UTF-8 byte widths and surrogate rejection are the algorithm.
    private fun String.appendValidated(target: StringBuilder, initialBytes: Int): Int? {
        var bytes = initialBytes
        var index = 0
        while (index < this.length) {
            val char = this[index++]
            val encoded = when {
                char.code <= 0x7f -> 1
                char.code <= 0x7ff -> 2
                char.isHighSurrogate() -> {
                    if (index >= this.length || !this[index++].isLowSurrogate()) return null
                    4
                }
                char.isLowSurrogate() -> return null
                else -> 3
            }
            bytes = DeclaredTypeExpansionBudget.addBytes(bytes, encoded)
        }
        try {
            // This is the standard UTF-8 size of the projected text, not DEX's MUTF-8 storage.
            DeclaredTypeExpansionBudget.validate(0, bytes)
        } catch (_: IllegalArgumentException) {
            return null
        }
        target.append(this)
        return bytes
    }

    private const val MAX_SIGNATURE_FRAGMENTS = 100_000
    // The pinned dexlib version exposes raw string-section APIs but not this encoded-value index.
    private val dexStringIndex = DexBackedStringEncodedValue::class.java
        .getDeclaredField("stringIndex")
        .apply { isAccessible = true }
}
