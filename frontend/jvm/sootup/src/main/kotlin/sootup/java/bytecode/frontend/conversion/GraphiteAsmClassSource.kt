package sootup.java.bytecode.frontend.conversion

import java.nio.file.Path
import org.objectweb.asm.tree.ClassNode
import sootup.core.frontend.SootClassSource
import sootup.core.inputlocation.AnalysisInputLocation
import sootup.core.jimple.basic.NoPositionInformation
import sootup.core.signatures.MethodSignature
import sootup.core.types.ClassType
import sootup.core.util.Modifiers
import sootup.java.core.AnnotationUsage
import sootup.java.core.JavaSootMethod

/** Exposes the parsed methods without forcing JavaSootClass's persistent method cache. */
internal class GraphiteAsmClassSource(
    location: AnalysisInputLocation,
    path: Path,
    type: ClassType,
    private val node: ClassNode
) : AsmClassSource(location, path, type, node) {
    fun methodSources(): List<AsmMethodSource> =
        node.methods.map { it as AsmMethodSource }.sortedWith(compareBy({ it.name }, { it.desc }))
}

/** Only our own lazy source has this path; overriding and annotation sources keep theirs. */
internal fun SootClassSource.streamingMethodSources(): List<AsmMethodSource>? =
    (this as? GraphiteAsmClassSource)?.methodSources()

internal fun AsmMethodSource.signatureFor(declaringClass: ClassType): MethodSignature {
    setDeclaringClass(declaringClass)
    return getSignature()
}

/**
 * The same method metadata AsmClassSource.resolveMethods builds, with the signature supplied
 * by the method source's memoized descriptor parse, also used when it resolves the body.
 * Each caller receives a fresh wrapper and therefore a private body cache.
 */
internal fun AsmMethodSource.asStreamingMethod(declaringClass: ClassType): JavaSootMethod {
    val methodSignature = signatureFor(declaringClass)
    val annotations = ArrayList<AnnotationUsage>()
    visibleAnnotations?.let { annotations.addAll(AsmUtil.createAnnotationUsage(it)) }
    invisibleAnnotations?.let { annotations.addAll(AsmUtil.createAnnotationUsage(it)) }
    collectReturnTypeAnnotations(annotations)
    return JavaSootMethod(
        this,
        methodSignature,
        Modifiers.getMethodModifiers(access),
        ArrayList(AsmUtil.asmIdToSignatures(exceptions)),
        annotations,
        NoPositionInformation.getInstance()
    )
}
