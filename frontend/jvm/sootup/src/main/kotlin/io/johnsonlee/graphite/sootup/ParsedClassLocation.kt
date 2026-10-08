package io.johnsonlee.graphite.sootup

import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors
import org.objectweb.asm.ClassReader
import java.util.stream.Stream
import sootup.core.frontend.SootClassSource
import sootup.core.inputlocation.AnalysisInputLocation
import sootup.core.interceptor.BodyInterceptor
import sootup.core.model.SourceType
import sootup.core.types.ClassType
import sootup.core.views.View
import sootup.java.bytecode.frontend.conversion.GraphiteClassNode
import sootup.java.bytecode.frontend.conversion.lazyClassSource
import sootup.java.core.JavaSootClassSource

/**
 * The classes of a jar or a class directory, parsed by this frontend and handed to SootUp as
 * ready class sources, where `PathBasedAnalysisInputLocation` parses them itself.
 *
 * SootUp 3 parses each class once while it enumerates the input, checks its name against the
 * path, and wraps it in an `OverridingJavaClassSource` that resolves every member immediately.
 * Here the classes are parsed into the node SootUp's provider builds
 * ([GraphiteClassNode]) and handed to the view
 * behind the lazy `AsmClassSource` SootUp 2 used, so a class's members are resolved when the
 * graph pass reaches it; the generic signatures of its fields are kept from that parse
 * ([fieldSignatures]). The entries are listed in the order `Files.walk` gives them, which is the
 * order SootUp would enumerate them in, so the view sees the same classes in the same order.
 *
 * The rules are SootUp's: a `.class` file that is not `module-info.class`, named by its path
 * below the root with `/` as `.`, and kept only when the class it holds has that name (a copy
 * under `META-INF/versions/` names another class and is skipped, as SootUp skips it).
 */
internal class ParsedClassLocation(
    private val path: Path,
    private val sourceType: SourceType,
    private val interceptors: List<BodyInterceptor>
) : AnalysisInputLocation {

    private val fileSystem: FileSystem? = if (Files.isDirectory(path)) null else FileSystems.newFileSystem(path)
    private val root: Path = fileSystem?.getPath("/") ?: path

    override fun getClassSource(type: ClassType, view: View): Optional<out SootClassSource> {
        val file = root.resolve(type.fullyQualifiedName.replace('.', '/') + CLASS_SUFFIX)
        if (!Files.isRegularFile(file)) return Optional.empty()
        return Optional.ofNullable(parse(file, view))
    }

    override fun getClassSources(view: View): Stream<out SootClassSource> {
        val files = Files.walk(root).use { walk ->
            walk.filter { file -> Files.isRegularFile(file) && isClassFile(file.fileName.toString()) }
                .collect(Collectors.toList())
        }
        // Parse in encounter order on the calling thread.
        val sources: List<SootClassSource> = files.stream().map { parse(it, view) }.collect(Collectors.toList()).filterNotNull()
        return sources.stream()
    }

    override fun getSourceType(): SourceType = sourceType

    override fun getBodyInterceptors(): List<BodyInterceptor> = interceptors

    override fun close() {
        fileSystem?.close()
    }

    /**
     * The class source of [file], read, checked against the name its path spells and converted by
     * the frontend's lazy class source; `null` when it holds another class (a copy under `META-INF/versions/`)
     * or cannot be read.
     */
    private fun parse(file: Path, view: View): JavaSootClassSource? {
        val name = typeName(file)
        val type = view.identifierFactory.getClassType(name)
        return try {
            // Retain SootUp's method body sources, resolving members when the view asks.
            // Read the exact bytes: ClassReader(InputStream) may pad a short, truncated class.
            val node = GraphiteClassNode(view, this)
            // Size local-file buffers directly; avoid ZipFS's intermediate byte-channel copy.
            val bytes = if (fileSystem == null) {
                Files.readAllBytes(file)
            } else {
                Files.newInputStream(file).use { it.readAllBytes() }
            }
            ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES)
            if (node.name.replace('/', '.') != name) return null
            declarations[name] = ClassDeclarations.from(node)
            node.fields.filter { it.signature != null }.associate { it.name to it.signature }
                .takeIf { it.isNotEmpty() }?.let { fieldSignatures[name] = it }
            lazyClassSource(this, file, type, node).also { parsed.add(name) }
        } catch (_: Exception) {
            null
        }
    }

    /** Declaration signatures and descriptors retained from each class's existing parse. */
    private val declarations = ConcurrentHashMap<String, ClassDeclarations>()

    fun declarations(className: String): ClassDeclarations? = declarations[className]

    /** The generic field signatures, by class and field name, for the fields that have one. */
    private val fieldSignatures = ConcurrentHashMap<String, Map<String, String>>()

    /**
     * The generic signature of each field of [className] that has one (`Ljava/util/List<Ljava/lang/String;>;`),
     * kept from the class's one parse; empty for a class without generic fields, `null` for a class
     * this location did not parse.
     */
    fun fieldSignatures(className: String): Map<String, String>? =
        if (className in parsed) fieldSignatures[className].orEmpty() else null

    private val parsed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun isClassFile(name: String): Boolean = name.endsWith(CLASS_SUFFIX) && name != MODULE_INFO

    /** The class name a path below the root spells: `a/b/C.class` is `a.b.C`. */
    private fun typeName(file: Path): String {
        val relative = root.relativize(file).toString().replace(root.fileSystem.separator, "/")
        return relative.removeSuffix(CLASS_SUFFIX).replace('/', '.')
    }

    override fun equals(other: Any?): Boolean = other is ParsedClassLocation && other.path == path && other.sourceType == sourceType

    override fun hashCode(): Int = path.hashCode() * 31 + sourceType.hashCode()

    override fun toString(): String = "ParsedClassLocation($path, $sourceType)"

    private companion object {
        const val CLASS_SUFFIX = ".class"
        const val MODULE_INFO = "module-info.class"
    }
}
