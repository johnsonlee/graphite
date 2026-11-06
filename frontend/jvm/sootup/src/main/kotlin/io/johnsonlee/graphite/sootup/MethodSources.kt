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
    private var methodCacheOwner
        get() = context.methodCacheOwner
        set(value) { context.methodCacheOwner = value }
    private val streamingSourcesCache = context.streamingSourcesCache
    private val conversionScratchFieldsByClass = context.conversionScratchFieldsByClass
    private val view = context.view
    private val config = context.config
    private val bytecodeMethods get() = context.bytecodeMethods
    private val completeness get() = context.completeness
    private val construction get() = context.construction
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

    fun bridgeBody(bridge: SootMethod): Body = try {
        bridge.body
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
            val selected = streamingStaticMethodsOrNull(sootClass, "main")
            if (selected != null) {
                selected.mapTo(entryPoints) { it.signature }
            } else {
                forEachMethod(sootClass) { method ->
                    if (method.name == "main" && method.isStatic) {
                        entryPoints.add(method.signature)
                    }
                }
            }
        }
        return entryPoints
    }

    fun forEachMethod(sootClass: SootClass, action: (SootMethod) -> Unit) {
        streamMethodsOrNull(sootClass)?.forEach(action)
            ?: bytecodeMethods.resolveMethodsOrEmpty(sootClass).sortedBy { it.signature.toString() }.forEach(action)
    }

    fun findStaticMethod(sootClass: SootClass, name: String): SootMethod? {
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
                if (source.name == name && (source.access and Opcodes.ACC_STATIC) != 0) {
                    source.asStreamingMethod(sootClass.type)
                } else {
                    null
                }
            } catch (oom: OutOfMemoryError) {
                declarations.log { "Skipping method ${sootClass.type}.${source.name}${source.desc}: OOM during streaming resolution" }
                completeness.recordResolutionFailure(sootClass.type, source.name, source.desc, oom.message ?: oom.javaClass.name)

                null
            } catch (e: sootup.core.frontend.ResolveException) {
                recordSourceFailure(sootClass, source, e)
                null
            } catch (expectedMalformedMetadata: IndexOutOfBoundsException) {
                recordSourceFailure(sootClass, source, expectedMalformedMetadata)
                null
            } catch (failure: IllegalArgumentException) {
                recordSourceFailure(sootClass, source, failure)
                null
            } catch (failure: IllegalStateException) {
                recordSourceFailure(sootClass, source, failure)
                null
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
                    yield(bytecodeMethods.detached(method))
                } catch (oom: OutOfMemoryError) {
                    declarations.log { "Skipping method ${method.signature}: OOM during streaming resolution" }
                    completeness.recordMethodEvidence(descriptors.toMethodDescriptor(method),
                        AnalysisStatus.RESOLUTION_FAILED, oom.message ?: oom.javaClass.name)

                } catch (e: sootup.core.frontend.ResolveException) {
                    declarations.log { "Skipping method ${method.signature}: ${e.message}" }
                    completeness.recordMethodEvidence(descriptors.toMethodDescriptor(method),
                        AnalysisStatus.RESOLUTION_FAILED, e.message ?: e.javaClass.name)
                } catch (expectedMalformedMetadata: IndexOutOfBoundsException) {
                    declarations.log { "Skipping method ${method.signature}: ${expectedMalformedMetadata.message}" }
                    completeness.recordMethodEvidence(descriptors.toMethodDescriptor(method),
                        AnalysisStatus.RESOLUTION_FAILED, expectedMalformedMetadata.message ?: expectedMalformedMetadata.javaClass.name)
                } catch (failure: IllegalArgumentException) {
                    declarations.log { "Skipping method ${method.signature}: ${failure.message}" }
                    completeness.recordMethodEvidence(descriptors.toMethodDescriptor(method),
                        AnalysisStatus.RESOLUTION_FAILED, failure.message ?: failure.javaClass.name)
                } catch (failure: IllegalStateException) {
                    declarations.log { "Skipping method ${method.signature}: ${failure.message}" }
                    completeness.recordMethodEvidence(descriptors.toMethodDescriptor(method),
                        AnalysisStatus.RESOLUTION_FAILED, failure.message ?: failure.javaClass.name)
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
                yield(source.asStreamingMethod(sootClass.type))
            } catch (oom: OutOfMemoryError) {
                declarations.log { "Skipping method ${sootClass.type}.${source.name}${source.desc}: OOM during streaming resolution" }
                completeness.recordResolutionFailure(sootClass.type, source.name, source.desc, oom.message ?: oom.javaClass.name)

            } catch (e: sootup.core.frontend.ResolveException) {
                recordSourceFailure(sootClass, source, e)
            } catch (expectedMalformedMetadata: IndexOutOfBoundsException) {
                recordSourceFailure(sootClass, source, expectedMalformedMetadata)
            } catch (failure: IllegalArgumentException) {
                recordSourceFailure(sootClass, source, failure)
            } catch (failure: IllegalStateException) {
                recordSourceFailure(sootClass, source, failure)
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
        declarations.log { "Skipping method ${sootClass.type}.${source.name}${source.desc}: ${failure.message}" }
        completeness.recordResolutionFailure(sootClass.type, source.name, source.desc, failure.message ?: failure.javaClass.name)
    }

    private fun recordCallGraphFailure(failure: Throwable) {
        declarations.log { "Call graph unavailable for the current classpath: ${failure.message}" }
    }

}
