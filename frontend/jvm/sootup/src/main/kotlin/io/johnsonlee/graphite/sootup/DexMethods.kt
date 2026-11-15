package io.johnsonlee.graphite.sootup

import sootup.apk.frontend.dexpler.DexClassSource
import sootup.core.frontend.OverridingBodySource
import sootup.core.model.Body
import sootup.core.model.MethodModifier
import sootup.core.model.SootClass
import sootup.core.model.SootMethod
import sootup.core.model.SourceType
import sootup.java.core.JavaSootClass
import sootup.java.core.JavaSootMethod
import sootup.java.core.OverridingJavaClassSource

/**
 * The graph pass over DEX classes. SootUp's DEX frontend converts every body of a class to list
 * its methods and the class memoizes them, so the pass never lists them through a class: it reads
 * headers ([dexMethodHeaders]) and converts one body at a time, held no longer than its method.
 */
internal class DexMethods(private val context: SootUpAdapter) {
    private val view = context.view
    private val declarations get() = context.declarations
    private val functionObjects get() = context.functionObjects
    private val methodSources get() = context.methodSources

    /**
     * Each method in signature order, with the body converted once, or taken from what an earlier
     * reader was lent ([DexBodyLedger]), and dropped with the method.
     * A body that fails to convert is that method's failure, as on the bytecode path.
     */
    fun forEach(sootClass: SootClass, headers: List<SootMethod>, action: (SootMethod) -> Unit) {
        val ledger = view.dexBodies()
        // Dispatch reads a function object's bridges, possibly after this class.
        val functionObject = headers.any { MethodModifier.isBridge(it.modifiers) } &&
            functionObjects.isFunctionObjectClass(sootClass.type.fullyQualifiedName)
        for (header in headers.sortedBy { it.signature.toString() }) {
            val method = if (!header.hasBody()) header else catchingMethodFailure({
                val source = header.bodySource as DexHeaderBodySource
                withBody(header, ledger?.take(source) ?: source.convert(), functionObject)
            }) { failure ->
                // A bridge that cannot be read delegates to nothing dispatch can name.
                if (functionObject && MethodModifier.isBridge(header.modifiers)) context.dexBridgeCalls[header.signature] = emptyList()
                methodSources.recordMethodFailure(header, failure)
                null
            }
            method?.let(action)
        }
        ledger?.processed(sootClass.type)
    }

    /**
     * The static method [name] of a DEX class with its body converted, alone: listing the class's
     * methods would convert all of them. `null` when there is none or its body fails to convert;
     * the graph pass records that failure when it reaches the method.
     */
    fun staticMethod(headers: List<SootMethod>, name: String): SootMethod? {
        val method = headers.firstOrNull { it.name == name && it.isStatic } ?: return null
        return catchingMethodFailure({ method.also { if (it.hasBody()) it.body } }) { failure ->
            declarations.log { "Skipping method ${method.signature}: ${failure.message ?: failure.javaClass.name}" }
            null
        }
    }

    /**
     * [sootClass] as an extension sees it: a DEX class holding the [methods] the graph pass built
     * the class from, bodies included, so an extension that lists them converts nothing again,
     * and dropped with the class.
     */
    fun classWithMethods(sootClass: SootClass, methods: Collection<SootMethod>): JavaSootClass {
        val source = sootClass.classSource as DexClassSource
        val sourceType = if (sootClass.isLibraryClass) SourceType.Library else SourceType.Application
        return JavaSootClass(OverridingJavaClassSource(source).withMethods(methods.filterIsInstance<JavaSootMethod>()), sourceType)
    }

    private fun withBody(header: SootMethod, body: Body, functionObject: Boolean): SootMethod {
        if (functionObject && MethodModifier.isBridge(header.modifiers)) {
            context.dexBridgeCalls[header.signature] = functionObjects.invokedSignatures(body).toList()
        }
        return JavaSootMethod(
            OverridingBodySource(header.signature, body), header.signature, header.modifiers,
            header.exceptionSignatures, emptySet(), header.position
        )
    }
}
