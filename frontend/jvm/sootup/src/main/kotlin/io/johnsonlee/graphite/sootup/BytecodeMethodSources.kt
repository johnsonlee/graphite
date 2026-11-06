package io.johnsonlee.graphite.sootup

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import sootup.core.model.SootClass
import sootup.core.model.SootMethod
import sootup.java.core.JavaSootClass
import sootup.java.bytecode.frontend.conversion.isBytecodeClassSource
import sootup.java.core.JavaSootMethod

/** Caches and detaches bytecode method declarations for bounded body lifetimes. */
internal class BytecodeMethodSources(private val context: SootUpAdapter) {
    private var activeClassResolutionFailed
        get() = context.activeClassResolutionFailed
        set(value) { context.activeClassResolutionFailed = value }
    private var methodCacheOwner
        get() = context.methodCacheOwner
        set(value) { context.methodCacheOwner = value }
    private val bytecodeMethodsCache = context.bytecodeMethodsCache
    private val view = context.view
    private val resourceAccessor = context.resourceAccessor
    private val declarations get() = context.declarations
    private val methodSources get() = context.methodSources

    /**
     * The methods of [sootClass] as the bytecode frontend read them. SootUp 3 converts a class
     * file into an `OverridingJavaClassSource` that already holds one [JavaSootMethod] per
     * method, each with its ASM `MethodNode` as body source, and releases the `ClassNode`. The
     * frontend collects them into a hash set keyed by identity, so they are sorted by signature
     * here for a deterministic walk; `null` for a class that did not come from bytecode.
     */
    fun bytecodeMethods(sootClass: JavaSootClass): List<JavaSootMethod>? =
        if (sootClass === methodCacheOwner) {
            bytecodeMethodsCache.getOrPut(sootClass) { resolveBytecodeMethods(sootClass) }
        } else {
            resolveBytecodeMethods(sootClass)
        }

    fun resolveBytecodeMethods(sootClass: JavaSootClass): List<JavaSootMethod>? {
        if (!sootClass.classSource.isBytecodeClassSource()) return null
        return try {
            // The class's own, memoised methods: a source's `resolveMethods()` converts every
            // descriptor again on each call. Sorted by the bytecode's own name and descriptor,
            // which the method node already holds: rendering a signature per method was a fifth
            // of this pass.
            sootClass.methods.filterIsInstance<JavaSootMethod>()
                .sortedWith(compareBy({ (it.bodySource as? MethodNode)?.name ?: it.name },
                    { (it.bodySource as? MethodNode)?.desc ?: it.signature.toString() }))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * A copy of [method] with a body cache of its own. SootUp 3 memoizes a method's body on the
     * [JavaSootMethod] the view holds; resolving the graph through that object would keep every
     * body of the corpus alive for the view's lifetime, which is what ran the Android SDK out
     * of heap. The copy is dropped once its graph nodes exist.
     */
    fun detached(method: JavaSootMethod): JavaSootMethod = JavaSootMethod(
        method.bodySource,
        method.signature,
        method.modifiers,
        method.exceptionSignatures,
        method.annotations,
        method.position
    )

    fun getAsmMethodNodes(sootClass: JavaSootClass): List<MethodNode>? =
        methodSources.streamingSources(sootClass) ?: bytecodeMethods(sootClass)?.mapNotNull { it.bodySource as? MethodNode }

    fun loadMethodNodesFromResource(sootClass: JavaSootClass): List<MethodNode>? {
        return try {
            val resourcePath = sootClass.type.fullyQualifiedName.replace('.', '/') + CLASS_FILE_SUFFIX
            resourceAccessor.open(resourcePath).use { input ->
                val classNode = ClassNode()
                ClassReader(input).accept(classNode, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                @Suppress("UNCHECKED_CAST")
                classNode.methods as? List<MethodNode>
            }
        } catch (_: java.io.IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun resolveMethodsOrEmpty(sootClass: SootClass): Set<SootMethod> = try {
        sootClass.methods
    } catch (failure: IllegalStateException) {
        recordClassFailure(sootClass, failure)
        emptySet()
    } catch (failure: OutOfMemoryError) {
        recordClassFailure(sootClass, failure)
        emptySet()
    }

    private fun recordClassFailure(sootClass: SootClass, failure: Throwable) {
        if (sootClass === methodCacheOwner) activeClassResolutionFailed = true
        declarations.log { "Skipping methods for ${sootClass.type}: ${failure.message ?: failure.javaClass.name}" }
    }
}
