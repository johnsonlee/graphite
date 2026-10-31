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
import org.jf.dexlib2.iface.Annotation
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
import java.lang.reflect.Field
import sootup.apk.frontend.dexpler.DexFileProvider
import kotlin.io.path.extension

/** Reads the declaration metadata that Android stores in system annotations in every DEX file. */
@Suppress("TooManyFunctions") // DEX decoding policies stay centralized at this untrusted-input boundary.
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
                return read(resourceClasses(resources, true, warning), classNames, warning)
            }
            val classes = dexFiles.asSequence().flatMapIndexed { index, dex ->
                safeClasses(dex.base.dexFile, "SootUp DEX container #${index + 1}", warning)
            }
            return read(classes, classNames, warning)
        }
        // JAR and directory builds have no DEX classes and must not scan every resource entry.
        if (resources is ArchiveResourceAccessor) {
            if (!resources.sourcePath?.extension.equals("apk", ignoreCase = true)) return emptyMap()
            warning("APK resource accessor has no DEX API; scanning named DEX entries instead")
        }
        return read(resourceClasses(resources, false, warning), classNames, warning)
    }

    @Suppress("TooGenericExceptionCaught") // Untrusted dexlib implementations may throw arbitrary runtime failures.
    internal fun read(
        classes: Sequence<ClassDef>,
        classNames: Set<String>,
        warning: (String) -> Unit = {},
        stringIndexField: Field? = dexStringIndexField
    ): Map<String, ClassDeclarations> {
        if (stringIndexField == null) {
            warning("DEX raw string allocation guard is unavailable; using the post-decode signature budget")
        }
        val definitions = linkedMapOf<String, ClassDef>()
        classes.forEach { definition ->
            val name = try {
                definition.type.dexClassName()
            } catch (failure: Exception) {
                warning("Skipping a DEX declaration with an invalid class type: ${failure.summary()}")
                return@forEach
            }
            if (name in classNames) {
                // Match SootUp's multidex index: the last definition for a requested type wins.
                definitions[name] = definition
            }
        }
        return definitions.mapNotNull { (name, definition) ->
            try {
                name to from(definition, stringIndexField)
            } catch (failure: Exception) {
                warning("Skipping DEX declarations for $name: ${failure.summary()}")
                null
            }
        }.toMap(linkedMapOf())
    }

    @Suppress("TooGenericExceptionCaught") // Resource providers and dexlib surface implementation-specific failures.
    internal fun resourceClasses(
        resources: ResourceAccessor,
        includeNonDexNames: Boolean,
        warning: (String) -> Unit
    ): Sequence<ClassDef> = sequence {
        val entries = if (includeNonDexNames) resources.list("**") else resources.list("**/*.dex")
        val selectedBasenames = hashSetOf<String>()
        entries
            // An exceptional SootUp-provider fallback accepts any entry with DEX magic.
            // Generic accessors are limited to .dex names. Preserve SootUp's primary/classes-first
            // and reverse-lexical ordering so the same last duplicate wins in both paths.
            .sortedWith(compareBy<ResourceEntry> {
                it.path.substringAfterLast('/') != "classes.dex"
            }.thenBy {
                !it.path.substringAfterLast('/').startsWith("classes")
            }.thenByDescending { it.path.substringAfterLast('/') })
            .forEach { entry ->
                val basename = entry.path.substringAfterLast('/')
                val classes = if (basename in selectedBasenames) emptySequence() else try {
                    resources.open(entry.path).use { input ->
                        val buffered = BufferedInputStream(input)
                        buffered.mark(DEX_MAGIC_SIZE)
                        val magic = ByteArray(DEX_MAGIC_SIZE)
                        val read = buffered.read(magic)
                        buffered.reset()
                        // DexFileProvider indexes only valid DEX entries by basename. Deduplicate
                        // after the magic check so an arbitrary resource cannot shadow a DEX body.
                        if (read != DEX_MAGIC_SIZE || !magic.isDexMagic() ||
                            !selectedBasenames.add(basename)
                        ) emptySequence() else {
                            safeClasses(DexBackedDexFile.fromInputStream(null, buffered), entry.path, warning)
                        }
                    }
                } catch (failure: Exception) {
                    warning("Failed to read DEX declarations from ${entry.path}: ${failure.summary()}")
                    emptySequence()
                }
                yieldAll(classes)
            }
    }

    @Suppress("TooGenericExceptionCaught") // Class iterators from damaged DEX files may fail at any traversal step.
    private fun safeClasses(
        dexFile: DexFile,
        source: String,
        warning: (String) -> Unit
    ): Sequence<ClassDef> = sequence {
        val iterator = try {
            dexFile.classes.iterator()
        } catch (failure: Exception) {
            warning("Failed to enumerate classes in $source: ${failure.summary()}")
            return@sequence
        }
        while (true) {
            val hasNext = try {
                iterator.hasNext()
            } catch (failure: Exception) {
                warning("Stopped enumerating classes in $source: ${failure.summary()}")
                return@sequence
            }
            if (!hasNext) return@sequence
            val definition = try {
                iterator.next()
            } catch (failure: Exception) {
                warning("Stopped enumerating classes in $source: ${failure.summary()}")
                return@sequence
            }
            yield(definition)
        }
    }

    private fun from(definition: ClassDef, stringIndexField: Field?): ClassDeclarations {
        val name = definition.type.dexClassName()
        val superName = definition.superclass?.dexClassName()
        val interfaces = definition.interfaces.map { it.dexClassName() }
        val annotations = definition.systemAnnotations()
        val fields = definition.fields.map { field ->
            field.type.requireDexFieldDescriptor()
            MemberDeclaration(field.name, field.type, field.signature(stringIndexField), field.accessFlags)
        }
        val methods = definition.methods.map { method ->
            val descriptor = method.descriptor()
            descriptor.requireDexMethodDescriptor()
            MemberDeclaration(method.name, descriptor, method.signature(stringIndexField), method.accessFlags)
        }
        val enclosingMethod = annotations.enclosingMethod()
        val hasInnerClassMetadata = INNER_CLASS in annotations
        val innerAccess = annotations.intAnnotation(INNER_CLASS, "accessFlags")
        val innerName = annotations.stringAnnotation(INNER_CLASS, "name")
        val encodedEnclosingClass = annotations.typeAnnotation(ENCLOSING_CLASS)?.dexClassName()
        val enclosingClass = enclosingMethod?.owner ?: encodedEnclosingClass?.takeIf {
            !hasInnerClassMetadata || innerName == null ||
                innerAccess == null || !AccessFlags.STATIC.isSet(innerAccess)
        }
        val hasEnclosingField = enclosingClass != null && fields.any { field ->
            AccessFlags.SYNTHETIC.isSet(field.access) && field.name.startsWith("this\$") &&
                field.descriptor == classDescriptor(enclosingClass)
        }
        return ClassDeclarations(
            name = name,
            signature = annotations.signature(stringIndexField),
            superName = superName,
            interfaces = interfaces,
            fields = fields,
            methods = methods,
            enclosingClass = enclosingClass,
            enclosingMethod = enclosingMethod,
            isEnum = AccessFlags.ENUM.isSet(definition.accessFlags),
            enclosingInstance = when {
                enclosingClass == null -> false
                hasEnclosingField -> true
                enclosingMethod == null && innerName != null && innerAccess != null -> true
                else -> null
            },
            hasInnerClassMetadata = hasInnerClassMetadata
        )
    }

    private fun Annotatable.signature(stringIndexField: Field?): String? = dexTolerant {
        val source = annotations
        if (source.isEmpty()) return@dexTolerant null
        var signature: Annotation? = null
        for (annotation in source) {
            if (annotation.visibility != AnnotationVisibility.SYSTEM || annotation.type != SIGNATURE) continue
            if (signature != null) return@dexTolerant null
            signature = annotation
        }
        signature?.signature(stringIndexField)
    }

    private fun Map<String, Annotation>.signature(stringIndexField: Field?): String? = dexTolerant {
        this[SIGNATURE]?.signature(stringIndexField)
    }

    @Suppress("ReturnCount") // Reject malformed or over-budget fragments before materializing the signature.
    private fun Annotation.signature(stringIndexField: Field?): String? {
        if (elements.size != 1) return null
        val value = elements.single().takeIf { it.name == VALUE }?.value as? ArrayEncodedValue ?: return null
        if (value.value.size > MAX_SIGNATURE_FRAGMENTS) return null
        val signature = StringBuilder()
        var bytes = 0
        for (encoded in value.value) {
            val string = encoded as? StringEncodedValue ?: return null
            // dexlib decodes on getValue(). Bound its temporary String before materializing it.
            if (string is DexBackedStringEncodedValue && stringIndexField != null &&
                !string.rawLengthWithinBudget(stringIndexField)
            ) return null
            val fragment = string.value
            bytes = fragment.appendValidated(signature, bytes) ?: return null
        }
        return signature.toString()
    }

    private fun Map<String, Annotation>.typeAnnotation(type: String): String? = decodedAnnotationValue(type, VALUE) {
        (it as? TypeEncodedValue)?.value
    }

    private fun Map<String, Annotation>.intAnnotation(type: String, name: String): Int? =
        decodedAnnotationValue(type, name) {
            (it as? IntEncodedValue)?.value
        }

    private fun Map<String, Annotation>.stringAnnotation(type: String, name: String): String? =
        decodedAnnotationValue(type, name) { (it as? StringEncodedValue)?.value }

    private fun Map<String, Annotation>.enclosingMethod(): MemberTypeKey? =
        decodedAnnotationValue(ENCLOSING_METHOD, VALUE) { value ->
            val reference = (value as? MethodEncodedValue)?.value ?: return@decodedAnnotationValue null
            MemberTypeKey(reference.definingClass.dexClassName(), reference.name, reference.descriptor())
        }

    private fun Annotatable.systemAnnotations(): Map<String, Annotation> = dexTolerant {
        annotations.asSequence()
            .filter { it.visibility == AnnotationVisibility.SYSTEM }
            .groupBy(Annotation::getType)
            .mapNotNull { (type, matches) -> matches.singleOrNull()?.let { type to it } }
            .toMap()
    }.orEmpty()

    private inline fun <T> Map<String, Annotation>.decodedAnnotationValue(
        type: String,
        name: String,
        decode: (EncodedValue) -> T?
    ): T? = dexTolerant {
        this[type]
            ?.elements
            ?.filter { it.name == name }
            ?.singleOrNull()
            ?.value
            ?.let(decode)
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
    internal val dexStringIndexField: Field? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        try {
            DexBackedStringEncodedValue::class.java.getDeclaredField("stringIndex").apply { isAccessible = true }
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun dexStringUtf16Length(string: DexBackedStringEncodedValue, stringIndexField: Field): Int? = try {
        val stringIndex = stringIndexField.getInt(string)
        val stringIdOffset = string.dexFile.stringSection.getOffset(stringIndex)
        val stringDataOffset = string.dexFile.buffer.readSmallUint(stringIdOffset)
        string.dexFile.dataBuffer.readerAt(stringDataOffset).readSmallUleb128()
    } catch (_: Exception) {
        null
    }

    private fun DexBackedStringEncodedValue.rawLengthWithinBudget(stringIndexField: Field): Boolean {
        // Fail closed if the pinned dexlib representation changes; never decode before bounding.
        val utf16Length = dexStringUtf16Length(this, stringIndexField) ?: return false
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

    private fun Exception.summary(): String = message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
}

private inline fun <T> dexTolerant(block: () -> T?): T? = try {
    block()
} catch (_: ExceptionWithContext) {
    null
} catch (_: IllegalArgumentException) {
    null
} catch (_: IndexOutOfBoundsException) {
    null
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
