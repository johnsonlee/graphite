package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.input.CallGraphAlgorithm
import org.objectweb.asm.Opcodes
import sootup.core.model.Body
import sootup.core.model.SootClass
import sootup.core.model.SootMethod
import sootup.core.signatures.MethodSignature
import sootup.java.core.JavaSootClass
import sootup.java.bytecode.frontend.conversion.AsmMethodSource
import sootup.java.bytecode.frontend.conversion.asStreamingMethod
import sootup.java.bytecode.frontend.conversion.streamingMethodSources
import sootup.java.core.JavaSootMethod
import sootup.callgraph.CallGraph
import sootup.callgraph.ClassHierarchyAnalysisAlgorithm
import sootup.callgraph.RapidTypeAnalysisAlgorithm

/** Selects streaming method sources and releases transient frontend-conversion state. */
internal class MethodSources(private val context: SootUpAdapter) {
    private val interprocedural = context.interprocedural
    private val methodCacheOwner
        get() = context.methodCacheOwner
    private val streamingSourcesCache = context.streamingSourcesCache
    private val conversionScratchFieldsByClass = context.conversionScratchFieldsByClass
    private val view = context.view
    private val config = context.config
    private val bytecodeMethods get() = context.bytecodeMethods
    private val dexMethods get() = context.dexMethods
    private val completeness get() = context.completeness
    private val declarations get() = context.declarations
    private val descriptors get() = context.descriptors

    fun processCallGraph() {
        try {
            buildCallGraph()
            // Call graph edges are already processed via call site nodes
            // This method could be extended to add additional interprocedural edges
        } catch (e: sootup.core.frontend.ResolveException) {
            recordCallGraphFailure(e)
        } catch (expectedMalformedMetadata: IndexOutOfBoundsException) {
            recordCallGraphFailure(expectedMalformedMetadata)
        } catch (failure: IllegalArgumentException) {
            recordCallGraphFailure(failure)
        } catch (failure: IllegalStateException) {
            recordCallGraphFailure(failure)
        } finally {
            // The algorithm resolves bodies through the methods the view holds, which memoize
            // them; their conversion scratch state is released here, as the adapter's own
            // detached copies release theirs after each method.
            view.classes.forEach(::releaseClassConversionState)
        }
    }

    fun releaseClassConversionState(sootClass: SootClass) {
        if (sootClass !is JavaSootClass) return
        val sources = streamingSources(sootClass)
        if (sources != null) sources.forEach { releaseConversionState(it) }
        else bytecodeMethods.bytecodeMethods(sootClass)?.forEach(::releaseConversionState)
    }

    /**
     * The body of [bridge], selected because dispatch reads the call it delegates to. A DEX
     * header's body is lent to the graph pass rather than memoized on the header the dispatch
     * caches hold.
     */
    fun bridgeBody(bridge: SootMethod): Body = try {
        (bridge.bodySource as? DexHeaderBodySource)?.let { it.resolveBody(bridge.modifiers) } ?: bridge.body
    } finally {
        releaseConversionState(bridge)
    }

    fun buildCallGraph(): CallGraph {
        val entryMethods = findEntryPoints()

        return when (config.callGraphAlgorithm) {
            CallGraphAlgorithm.CHA -> {
                ClassHierarchyAnalysisAlgorithm(view).initialize(entryMethods)
            }
            CallGraphAlgorithm.RTA -> {
                RapidTypeAnalysisAlgorithm(view).initialize(entryMethods)
            }
            else -> {
                // Default to CHA for now
                ClassHierarchyAnalysisAlgorithm(view).initialize(entryMethods)
            }
        }
    }

    fun findEntryPoints(): List<MethodSignature> {
        // Find main methods and other entry points
        val entryPoints = mutableListOf<MethodSignature>()
        view.classes.forEach { sootClass ->
            // A DEX class's declarations answer the question without converting any body.
            val candidates = streamingStaticMethodsOrNull(sootClass, "main") ?: dexMethodHeaders(sootClass)
                ?: buildList { forEachMethod(sootClass, ::add) }
            candidates.filter { it.name == "main" && it.isStatic }.mapTo(entryPoints) { it.signature }
        }
        return entryPoints
    }

    fun forEachMethod(sootClass: SootClass, action: (SootMethod) -> Unit) {
        dexMethodHeaders(sootClass)?.let { headers -> return dexMethods.forEach(sootClass, headers, action) }
        streamMethodsOrNull(sootClass)?.forEach(action)
            ?: bytecodeMethods.resolveMethodsOrEmpty(sootClass).sortedBy { it.signature.toString() }.forEach(action)
    }

    fun findStaticMethod(sootClass: SootClass, name: String): SootMethod? {
        // A DEX class converts only the method asked for, under the failures its method list has.
        dexMethodHeaders(sootClass)?.let { headers -> return dexMethods.staticMethod(headers, name) }
        val selected = streamingStaticMethodsOrNull(sootClass, name)
        val streamed = if (selected != null) {
            selected.firstOrNull()
        } else {
            streamMethodsOrNull(sootClass)?.firstOrNull { it.name == name && it.isStatic }
        }
        // Preserve the existing fallback, including resolution failures, when no wrapper survived.
        return streamed ?: bytecodeMethods.resolveMethodsOrEmpty(sootClass).firstOrNull { it.name == name && it.isStatic }
    }

    /**
     * Only our parsed ASM sources keep name/access unchanged from method construction. Select
     * those fields before wrapping, but still convert all metadata for a selected method so a
     * failing annotation cannot introduce an entry point previously skipped by streaming.
     */
    fun streamingStaticMethodsOrNull(sootClass: SootClass, name: String): List<SootMethod>? {
        if (sootClass !is JavaSootClass) return null
        return streamingSources(sootClass)?.mapNotNull { source ->
            try {
                catchingMethodFailure({
                    if (source.name == name && (source.access and Opcodes.ACC_STATIC) != 0) {
                        source.asStreamingMethod(sootClass.type)
                    } else {
                        null
                    }
                }) { failure ->
                    recordSourceFailure(sootClass, source, failure)
                    null
                }
            } finally {
                // Run even for non-matches, and before a caller can stop after its first match.
                releaseConversionState(source)
            }
        }
    }

    fun streamMethodsOrNull(sootClass: SootClass): Sequence<SootMethod>? =
        (sootClass as? JavaSootClass)?.let { javaClass ->
            val sources = streamingSources(javaClass)
            if (sources != null) streamMethods(javaClass, sources)
            else bytecodeMethods.bytecodeMethods(javaClass)?.let(::streamDetachedMethods)
        }

    private fun streamDetachedMethods(methods: List<JavaSootMethod>): Sequence<SootMethod> = sequence {
            for (method in methods) {
                try {
                    catchingMethodFailure({ yield(bytecodeMethods.detached(method)) }) { recordMethodFailure(method, it) }
                } finally {
                    releaseConversionState(method.bodySource)
                }
            }
        }

    fun streamingSources(sootClass: JavaSootClass): List<AsmMethodSource>? =
        if (sootClass === methodCacheOwner) {
            streamingSourcesCache.getOrPut(sootClass) { sootClass.classSource.streamingMethodSources() }
        } else {
            sootClass.classSource.streamingMethodSources()
        }

    fun streamMethods(sootClass: JavaSootClass, sources: List<AsmMethodSource>): Sequence<SootMethod> = sequence {
        for (source in sources) {
            try {
                catchingMethodFailure({ yield(source.asStreamingMethod(sootClass.type)) }) { recordSourceFailure(sootClass, source, it) }
            } finally {
                releaseConversionState(source)
            }
        }
    }

    /**
     * Drop the per-instruction scratch maps SootUp 3's `AsmMethodSource` keeps after it resolved
     * a body (`insnIndexCache`, one entry per bytecode instruction, and
     * `localVarTypeAnnotationIndex`). The source stays reachable through the view's class for
     * the whole build, and on the Android SDK those maps alone exceed the 4 GB test heap; both
     * are rebuilt on demand, so a later resolution of the same body is unaffected. A source
     * without the fields (another frontend, a future SootUp) is left alone.
     */
    fun releaseConversionState(method: SootMethod) {
        if (method is JavaSootMethod) releaseConversionState(method.bodySource)
    }

    fun releaseConversionState(bodySource: Any) {
        for (field in conversionScratchFields(bodySource.javaClass)) {
            try {
                field.set(bodySource, null)
            } catch (_: ReflectiveOperationException) {
                // Not an AsmMethodSource of this SootUp: nothing to release.
            }
        }
    }

    fun conversionScratchFields(type: Class<*>): List<java.lang.reflect.Field> =
        conversionScratchFieldsByClass.getOrPut(type) {
            CONVERSION_SCRATCH_FIELDS.mapNotNull { name ->
                try {
                    type.getDeclaredField(name).apply { isAccessible = true }
                } catch (_: ReflectiveOperationException) {
                    null
                }
            }
        }
    private fun recordSourceFailure(sootClass: JavaSootClass, source: AsmMethodSource, failure: Throwable) {
        declarations.log { "Skipping method ${sootClass.type}.${source.name}${source.desc}: ${diagnostic(failure)}" }
        completeness.recordResolutionFailure(sootClass.type, source.name, source.desc, failure.message ?: failure.javaClass.name)
    }

    /** [method] failed to resolve and is left out; the rest of its class is read. */
    fun recordMethodFailure(method: SootMethod, failure: Throwable) {
        declarations.log { "Skipping method ${method.signature}: ${diagnostic(failure)}" }
        completeness.recordMethodEvidence(descriptors.toMethodDescriptor(method),
            AnalysisStatus.RESOLUTION_FAILED, failure.message ?: failure.javaClass.name)
    }

    private fun diagnostic(failure: Throwable): String? =
        if (failure is OutOfMemoryError) "OOM during streaming resolution" else failure.message

    private fun recordCallGraphFailure(failure: Throwable) {
        declarations.log { "Call graph unavailable for the current classpath: ${failure.message}" }
    }

}

/**
 * [block]'s value, or [onFailure]'s when resolving one method fails in a way that fails that
 * method alone (its body or metadata could not be converted, or converting it ran out of
 * memory). Every path that reads one method at a time, bytecode or DEX, fails the same way.
 */
internal inline fun <T> catchingMethodFailure(block: () -> T, onFailure: (Throwable) -> T): T = try {
    block()
} catch (failure: OutOfMemoryError) {
    onFailure(failure)
} catch (failure: sootup.core.frontend.ResolveException) {
    onFailure(failure)
} catch (expectedMalformedMetadata: IndexOutOfBoundsException) {
    onFailure(expectedMalformedMetadata)
} catch (failure: IllegalArgumentException) {
    onFailure(failure)
} catch (failure: IllegalStateException) {
    onFailure(failure)
}
