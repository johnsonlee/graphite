package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.ResourceEntry
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.iface.ClassDef
import java.io.BufferedInputStream

/** Enumerates untrusted DEX resources while preserving primary/multidex ordering and failure diagnostics. */
@Suppress("TooGenericExceptionCaught") // Resource providers and dexlib surface implementation-specific failures.
internal fun dexResourceClasses(
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
                        safeDexClasses(DexBackedDexFile.fromInputStream(null, buffered), entry.path, warning)
                    }
                }
            } catch (failure: Exception) {
                warning("Failed to read DEX declarations from ${entry.path}: ${failure.dexDeclarationSummary()}")
                emptySequence()
            }
            yieldAll(classes)
        }
}

@Suppress("TooGenericExceptionCaught") // Class iterators from damaged DEX files may fail at any traversal step.
internal fun safeDexClasses(
    dexFile: DexFile,
    source: String,
    warning: (String) -> Unit
): Sequence<ClassDef> = sequence {
    val iterator = try {
        dexFile.classes.iterator()
    } catch (failure: Exception) {
        warning("Failed to enumerate classes in $source: ${failure.dexDeclarationSummary()}")
        return@sequence
    }
    while (true) {
        val hasNext = try {
            iterator.hasNext()
        } catch (failure: Exception) {
            warning("Stopped enumerating classes in $source: ${failure.dexDeclarationSummary()}")
            return@sequence
        }
        if (!hasNext) return@sequence
        val definition = try {
            iterator.next()
        } catch (failure: Exception) {
            warning("Stopped enumerating classes in $source: ${failure.dexDeclarationSummary()}")
            return@sequence
        }
        yield(definition)
    }
}

@Suppress("MagicNumber") // Fixed offsets and byte values in the eight-byte DEX magic header.
private fun ByteArray.isDexMagic(): Boolean =
    size == DEX_MAGIC_SIZE && this[0] == 'd'.code.toByte() && this[1] == 'e'.code.toByte() &&
        this[2] == 'x'.code.toByte() && this[3] == '\n'.code.toByte() &&
        (4..6).all { this[it] in '0'.code.toByte()..'9'.code.toByte() } && this[7] == 0.toByte()

private const val DEX_MAGIC_SIZE = 8

internal fun Exception.dexDeclarationSummary(): String = message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
