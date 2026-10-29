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

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    fun read(
        resources: ResourceAccessor,
        classNames: Set<String>,
        warning: (String) -> Unit = {}
    ): Map<String, ClassDeclarations> {
        if (resources is ArchiveResourceAccessor && resources.sourcePath != null && resources.dexApi != null) {
            val dexFiles = try {
                DexFileProvider.getInstance().getDexFromSource(resources.sourcePath.toFile(), resources.dexApi)
            } catch (failure: Exception) {
                warning(
                    "Failed to read DEX declarations with SootUp; scanning archive entries instead: " +
                        "${failure.message ?: failure.javaClass.simpleName}"
                )
                return read(resourceClasses(resources), classNames)
            }
            val classes = dexFiles.asSequence().flatMap { safeClasses(it.base.dexFile) }
            return read(classes, classNames)
        }
        // JAR and directory builds have no DEX classes and must not scan every resource entry.
        return if (resources is ArchiveResourceAccessor) emptyMap() else read(resourceClasses(resources), classNames)
    }

    internal fun read(classes: Sequence<ClassDef>, classNames: Set<String>): Map<String, ClassDeclarations> {
        val definitions = linkedMapOf<String, ClassDef>()
        classes.forEach { definition ->
            val name = try {
                definition.type.dexClassName()
            } catch (_: Exception) {
                return@forEach
            }
            if (name in classNames) {
                // Match SootUp's multidex index: the last definition for a requested type wins.
                definitions[name] = definition
            }
        }
        return definitions.mapNotNull { (name, definition) ->
            try {
                name to from(definition)
            } catch (_: Exception) {
                null
            }
        }.toMap(linkedMapOf())
    }

    private fun resourceClasses(resources: ResourceAccessor): Sequence<ClassDef> = sequence {
        resources.list("**")
            // DexFileFactory accepts every zip entry with DEX magic, including classes1.dex
            // and entries whose names do not end in .dex. Mirror SootUp's primary/classes-first
            // and reverse-lexical ordering so the same last duplicate wins.
            .sortedWith(compareBy<ResourceEntry> {
                it.path.substringAfterLast('/') != "classes.dex"
            }.thenBy {
                !it.path.substringAfterLast('/').startsWith("classes")
            }.thenByDescending { it.path.substringAfterLast('/') })
            .forEach { entry ->
                val classes = try {
                    resources.open(entry.path).use { input ->
                        val buffered = BufferedInputStream(input)
                        buffered.mark(DEX_MAGIC_SIZE)
                        val magic = ByteArray(DEX_MAGIC_SIZE)
                        val read = buffered.read(magic)
                        buffered.reset()
                        if (read != DEX_MAGIC_SIZE || !magic.isDexMagic()) emptySequence() else {
                            safeClasses(DexBackedDexFile.fromInputStream(null, buffered))
                        }
                    }
                } catch (_: Exception) {
                    emptySequence()
                }
                yieldAll(classes)
            }
    }

    private fun safeClasses(dexFile: DexFile): Sequence<ClassDef> = sequence {
        val iterator = try {
            dexFile.classes.iterator()
        } catch (_: Exception) {
            return@sequence
        }
        while (true) {
            val hasNext = try {
                iterator.hasNext()
            } catch (_: Exception) {
                return@sequence
            }
            if (!hasNext) return@sequence
            val definition = try {
                iterator.next()
            } catch (_: Exception) {
                return@sequence
            }
            yield(definition)
        }
    }

    private fun from(definition: ClassDef): ClassDeclarations {
        val name = definition.type.dexClassName()
        val superName = definition.superclass?.dexClassName()
        val interfaces = definition.interfaces.map { it.dexClassName() }
        val fields = definition.fields.map { field ->
            field.type.requireDexFieldDescriptor()
            MemberDeclaration(field.name, field.type, field.signature(), field.accessFlags)
        }
        val methods = definition.methods.map { method ->
            val descriptor = method.descriptor()
            descriptor.requireDexMethodDescriptor()
            MemberDeclaration(method.name, descriptor, method.signature(), method.accessFlags)
        }
        val enclosingMethod = definition.enclosingMethod()
        val innerAccess = definition.intAnnotation(INNER_CLASS, "accessFlags")
        val innerName = definition.stringAnnotation(INNER_CLASS, "name")
        val enclosingClass = enclosingMethod?.owner ?: definition.typeAnnotation(ENCLOSING_CLASS)
            ?.dexClassName()
            ?.takeIf { innerAccess != null && !AccessFlags.STATIC.isSet(innerAccess) }
        val hasEnclosingField = enclosingClass != null && fields.any { field ->
            AccessFlags.SYNTHETIC.isSet(field.access) && field.name.startsWith("this\$") &&
                field.descriptor == "L${enclosingClass.replace('.', '/')};"
        }
        return ClassDeclarations(
            name = name,
            signature = definition.signature(),
            superName = superName,
            interfaces = interfaces,
            fields = fields,
            methods = methods,
            enclosingClass = enclosingClass,
            enclosingMethod = enclosingMethod,
            isEnum = AccessFlags.ENUM.isSet(definition.accessFlags),
            enclosingInstance = when {
                enclosingClass == null -> false
                enclosingMethod == null && innerName != null -> true
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
            // dexlib decodes on getValue(). Bound its temporary String before materializing it.
            if (string is DexBackedStringEncodedValue && !string.rawLengthWithinBudget()) return null
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

    private fun Annotatable.stringAnnotation(type: String, name: String): String? =
        decodedAnnotationValue(type, name) { (it as? StringEncodedValue)?.value }

    private fun Annotatable.enclosingMethod(): MemberTypeKey? = decodedAnnotationValue(ENCLOSING_METHOD, VALUE) { value ->
        val reference = (value as? MethodEncodedValue)?.value ?: return@decodedAnnotationValue null
        MemberTypeKey(reference.definingClass.dexClassName(), reference.name, reference.descriptor())
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

    private fun MethodReference.descriptor(): String =
        parameterTypes.joinToString(separator = "", prefix = "(", postfix = ")$returnType")

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
    internal val dexStringIndexField by lazy(LazyThreadSafetyMode.PUBLICATION) {
        DexBackedStringEncodedValue::class.java.getDeclaredField("stringIndex").apply { isAccessible = true }
    }

    private fun dexStringUtf16Length(string: DexBackedStringEncodedValue): Int? = try {
        val stringIndex = dexStringIndexField.getInt(string)
        val stringIdOffset = string.dexFile.stringSection.getOffset(stringIndex)
        val stringDataOffset = string.dexFile.buffer.readSmallUint(stringIdOffset)
        string.dexFile.dataBuffer.readerAt(stringDataOffset).readSmallUleb128()
    } catch (_: Exception) {
        null
    }

    private fun DexBackedStringEncodedValue.rawLengthWithinBudget(): Boolean {
        // Fail closed if the pinned dexlib representation changes; never decode before bounding.
        val utf16Length = dexStringUtf16Length(this) ?: return false
        return try {
            // Every UTF-16 code unit contributes at least one byte to valid UTF-8.
            DeclaredTypeExpansionBudget.validate(0, utf16Length)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    @Suppress("MagicNumber") // Fixed offsets and byte values in the eight-byte DEX magic header.
    private fun ByteArray.isDexMagic(): Boolean =
        size == DEX_MAGIC_SIZE && this[0] == 'd'.code.toByte() && this[1] == 'e'.code.toByte() &&
            this[2] == 'x'.code.toByte() && this[3] == '\n'.code.toByte() &&
            (4..6).all { this[it] in '0'.code.toByte()..'9'.code.toByte() } && this[7] == 0.toByte()

    private const val DEX_MAGIC_SIZE = 8
}

private fun String.dexClassName(): String {
    if (dexTypeEnd(0, allowVoid = false) != length || firstOrNull() != 'L') invalidDexDescriptor("class")
    return substring(1, lastIndex).replace('/', '.')
}

private fun String.requireDexFieldDescriptor() {
    if (dexTypeEnd(0, allowVoid = false) != length) invalidDexDescriptor("field")
}

private fun String.requireDexMethodDescriptor() {
    if (firstOrNull() != '(') invalidDexDescriptor(METHOD_DESCRIPTOR_KIND)
    var index = 1
    while (index < length && this[index] != ')') {
        index = dexTypeEnd(index, allowVoid = false) ?: invalidDexDescriptor(METHOD_DESCRIPTOR_KIND)
    }
    if (index >= length || this[index] != ')') invalidDexDescriptor(METHOD_DESCRIPTOR_KIND)
    if (dexTypeEnd(index + 1, allowVoid = true) != length) invalidDexDescriptor(METHOD_DESCRIPTOR_KIND)
}

@Suppress("CyclomaticComplexMethod", "ReturnCount") // Mirrors the compact DEX descriptor grammar.
private fun String.dexTypeEnd(start: Int, allowVoid: Boolean): Int? {
    if (start !in indices) return null
    var valueStart = start
    while (valueStart < length && this[valueStart] == '[') {
        if (valueStart - start == MAX_DEX_ARRAY_DIMENSIONS) return null
        valueStart++
    }
    if (valueStart == length) return null
    return when (this[valueStart]) {
        'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z' -> valueStart + 1
        'V' -> if (allowVoid && valueStart == start) valueStart + 1 else null
        'L' -> indexOf(';', valueStart + 1).takeIf { end ->
            end >= 0 && substring(valueStart + 1, end).isDexObjectName()
        }?.plus(1)
        else -> null
    }
}

@Suppress("ComplexCondition") // DEX names allow broad Unicode but forbid descriptor separators and empty segments.
private fun String.isDexObjectName(): Boolean =
    isNotEmpty() && !startsWith('/') && !endsWith('/') && "//" !in this &&
        none { it == '.' || it == '[' || it == '(' || it == ')' || it == '\u0000' }

private fun String.invalidDexDescriptor(kind: String): Nothing =
    throw IllegalArgumentException("Invalid $kind descriptor: $this")

private const val METHOD_DESCRIPTOR_KIND = "method"
private const val MAX_DEX_ARRAY_DIMENSIONS = 255
