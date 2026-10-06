package sootup.java.bytecode.frontend.conversion

import java.nio.file.Path
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import sootup.core.frontend.SootClassSource
import sootup.core.inputlocation.AnalysisInputLocation
import sootup.core.types.ClassType
import sootup.core.views.View
import sootup.java.core.JavaSootClassSource
import sootup.java.core.OverridingJavaClassSource

/**
 * A class node whose method nodes are SootUp's [AsmMethodSource] body sources,
 * matching the node built by [AsmJavaClassProvider]. The parsed node is retained
 * behind a lazy class source. This class lives in SootUp's package to access the
 * body source's package-private constructor.
 */
internal class GraphiteClassNode(
    private val view: View,
    private val location: AnalysisInputLocation
) : ClassNode(AsmUtil.SUPPORTED_ASM_OPCODE) {

    override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<String>?): MethodVisitor {
        val source = AsmMethodSource(access, name, descriptor, signature, exceptions, view, location.bodyInterceptors)
        methods.add(source)
        return source
    }

    override fun visitEnd() {
        // An empty table cannot name locals, but SootUp still indexes every instruction for it.
        // Normalize after all visits; local type annotations and line information stay intact.
        methods.forEach { method ->
            if (method.localVariables?.isEmpty() == true) method.localVariables = null
        }
        super.visitEnd()
    }
}

/**
 * The class source SootUp's bytecode frontend keeps for [node]: its methods, fields,
 * annotations and hierarchy are resolved from the node when the view asks for them, as
 * SootUp 2 resolved them. `AsmJavaClassProvider.createClassSource` wraps this source in an
 * `OverridingJavaClassSource` that resolves all of that for every class as the input is
 * enumerated, which converts every method's descriptor and annotations before the graph pass
 * starts, under a lock of the identifier factory when the enumeration runs in parallel, and
 * on code the JIT has not compiled yet; a class the graph never visits pays the same.
 */
internal fun lazyClassSource(location: AnalysisInputLocation, path: Path, type: ClassType, node: ClassNode): JavaSootClassSource =
    if (node.access and Opcodes.ACC_ANNOTATION != 0) AsmAnnotationClassSource(location, path, type, node)
    else GraphiteAsmClassSource(location, path, type, node)

/** Whether [source] came from the bytecode frontend, with an ASM method node behind each method. */
internal fun SootClassSource.isBytecodeClassSource(): Boolean = this is AsmClassSource || this is OverridingJavaClassSource
