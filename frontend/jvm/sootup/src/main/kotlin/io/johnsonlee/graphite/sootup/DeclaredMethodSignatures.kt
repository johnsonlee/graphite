package io.johnsonlee.graphite.sootup

import sootup.apk.frontend.Util.DexUtil
import sootup.apk.frontend.dexpler.DexClassSource
import sootup.apk.frontend.dexpler.DexResolver
import sootup.core.IdentifierFactory
import sootup.core.model.SootClass
import sootup.core.signatures.MethodSignature

/** Declaration lookup must not resolve DEX bodies: their interceptors can be performing this lookup. */
internal fun declaredMethodSignatures(sootClass: SootClass, identifierFactory: IdentifierFactory): List<MethodSignature> {
    val source = sootClass.classSource
    if (source !is DexClassSource) return sootClass.methods.map { it.signature }
    // Use the same cached wrapper and class selection as DexClassSource, including multidex precedence.
    val definition = DexResolver.getInstance().initializeDexFile(source.sourcePath.toFile())
        .getClassInformation(sootClass.type)?.classDefinition
    return definition?.methods?.map { method ->
        identifierFactory.getMethodSignature(
            sootClass.type, method.name, DexUtil.toSootType(method.returnType, 0),
            method.parameterTypes.map { DexUtil.toSootType(it.toString(), 0) }
        )
    }.orEmpty()
}
