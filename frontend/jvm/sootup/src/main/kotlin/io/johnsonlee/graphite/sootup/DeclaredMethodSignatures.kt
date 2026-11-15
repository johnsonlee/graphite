package io.johnsonlee.graphite.sootup

import sootup.apk.frontend.dexpler.DexClassSource
import sootup.core.model.SootClass
import sootup.core.model.SootMethod
import sootup.core.signatures.MethodSignature

/**
 * The methods a DEX class declares, as headers that hold no body, or `null` for a class that is
 * not read from DEX. A declaration lookup must not resolve DEX bodies: SootUp's DEX frontend
 * converts and intercepts every body of a class to list its methods, the class the view caches
 * then keeps them all for the whole build, and the body interceptors (constant folding) may be
 * the ones performing the lookup. A header converts its own body only when asked, so a caller
 * that needs one body (a bridge, an enum initializer) converts that one and holds it no longer
 * than it holds the header.
 */
internal fun dexMethodHeaders(sootClass: SootClass): List<SootMethod>? =
    (sootClass.classSource as? DexClassSource)?.methodHeaders()

/** [sootClass]'s methods for a lookup of their declarations: headers for DEX, the class's own methods otherwise. */
internal fun declaredMethods(sootClass: SootClass): Collection<SootMethod> = dexMethodHeaders(sootClass) ?: sootClass.methods

/** Declaration lookup must not resolve DEX bodies: their interceptors can be performing this lookup. */
internal fun declaredMethodSignatures(sootClass: SootClass): List<MethodSignature> =
    declaredMethods(sootClass).map { it.signature }
