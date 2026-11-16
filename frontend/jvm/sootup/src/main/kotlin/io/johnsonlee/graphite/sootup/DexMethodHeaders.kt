package io.johnsonlee.graphite.sootup

import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.MultiDexContainer
import sootup.apk.frontend.Util.DexUtil
import sootup.apk.frontend.dexpler.DexClassSource
import sootup.apk.frontend.dexpler.DexMethod
import sootup.apk.frontend.dexpler.DexResolver
import sootup.core.frontend.BodySource
import sootup.core.jimple.basic.NoPositionInformation
import sootup.core.model.Body
import sootup.core.model.MethodModifier
import sootup.core.signatures.MethodSignature
import sootup.core.types.ClassType
import sootup.core.util.Modifiers
import sootup.core.views.View
import sootup.java.core.JavaSootMethod

/**
 * The methods this source declares, read from the dexlib definition it selected (multidex
 * precedence included) without converting any body. `resolveMethods()` jimplifies and runs the
 * body interceptors on every method to build the same list, so a declaration lookup must not
 * call it: the interceptors may be the caller. Each header carries the signature and modifiers
 * SootUp's own DEX method has (no exceptions, annotations or position, as there), and converts
 * its body, through the same interceptors, only when the body is asked for ([DexBodyLedger]).
 */
internal fun DexClassSource.methodHeaders(): List<JavaSootMethod> {
    // The definition the source itself was read from: the same cached wrapper and multidex selection.
    val information = DexResolver.getInstance().initializeDexFile(sourcePath.toFile()).getClassInformation(classType)
        ?: return emptyList()
    val factory = view.identifierFactory
    return information.classDefinition.methods.map { method ->
        val signature = factory.getMethodSignature(
            classType, method.name, DexUtil.toSootType(method.returnType, 0),
            method.parameters.map { DexUtil.toSootType(it.type, 0) }
        )
        JavaSootMethod(
            DexHeaderBodySource(signature, method, information.dexEntry, this), signature,
            Modifiers.getMethodModifiers(method.accessFlags), emptyList(), emptySet(), NoPositionInformation.getInstance()
        )
    }
}

/** Converts one DEX method's body exactly as [DexClassSource.resolveMethods] converts it, through the open build's [DexBodyLedger]. */
internal class DexHeaderBodySource(
    private val methodSignature: MethodSignature,
    private val method: Method,
    private val dexEntry: MultiDexContainer.DexEntry<out DexFile>,
    private val source: DexClassSource
) : BodySource {
    val declaringClass: ClassType get() = source.classType

    /**
     * A body lent ahead of the graph pass, kept until that pass takes it ([DexBodyLedger.lend]);
     * outside a build, converted for the asker.
     */
    override fun resolveBody(modifiers: Iterable<MethodModifier>): Body =
        source.view.dexBodies()?.lend(this) ?: convert()

    override fun resolveAnnotationsDefaultValue(): Any? = null

    override fun getSignature(): MethodSignature = methodSignature

    fun convert(): Body = DexMethod(dexEntry, source.classType)
        .makeSootMethod(method, source.analysisInputLocation.bodyInterceptors, source.view).body
}

/**
 * The DEX bodies one build converted before its graph pass reached their class: an enum
 * initializer read for its constants, a bridge whose target dispatch reads, the initialization
 * bodies the fold checks an enum's safety by. Each is converted once and held only until the
 * graph pass takes it, so the pass neither converts it again (running the interceptors twice,
 * and folding a second resolution that the ordinals no longer describe) nor keeps every body of
 * the corpus as the view's own classes do. Only a class the graph pass will read ([holds]) has
 * its bodies held, and only until that pass has read it; any other body is converted for its
 * asker alone.
 */
internal class DexBodyLedger(private val holds: (ClassType) -> Boolean) {
    private val lent = ConcurrentHashMap<MethodSignature, Body>()
    private val processed: MutableSet<ClassType> = ConcurrentHashMap.newKeySet()

    fun lend(source: DexHeaderBodySource): Body =
        lent[source.getSignature()] ?: source.convert().also { body ->
            val type = source.declaringClass
            if (type !in processed && holds(type)) lent.putIfAbsent(source.getSignature(), body)
        }

    /** The body of [source] for the graph pass: the one lent earlier, or a fresh conversion. */
    fun take(source: DexHeaderBodySource): Body = lent.remove(source.getSignature()) ?: source.convert()

    /** The graph pass has taken what it needs of [type]: later conversions of its bodies are not held. */
    fun processed(type: ClassType) {
        processed += type
    }

    val size: Int get() = lent.size
}

/**
 * The ledger of the build running on each view. It belongs to the build ([openDexBodies] at its
 * start, [closeDexBodies] at its end), so nothing one build lent or processed reaches the next
 * build on the same view; the map only lets the view's body interceptors (the fold, which has no
 * adapter) find it.
 */
private val ledgers: MutableMap<View, DexBodyLedger> = Collections.synchronizedMap(WeakHashMap())

/** The ledger of the build running on this view, or `null` outside a build. */
internal fun View.dexBodies(): DexBodyLedger? = ledgers[this]

internal fun View.openDexBodies(holds: (ClassType) -> Boolean): DexBodyLedger = DexBodyLedger(holds).also { ledgers[this] = it }

/** Bodies lent but never taken (a class the build did not read after all) are dropped with the build. */
internal fun View.closeDexBodies(ledger: DexBodyLedger) {
    ledgers.remove(this, ledger)
}
