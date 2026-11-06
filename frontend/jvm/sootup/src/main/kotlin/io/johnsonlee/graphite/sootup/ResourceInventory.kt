package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.input.ResourceEntry
import java.nio.file.Files
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Type as AsmType
import sootup.core.model.SootClass
import sootup.core.signatures.MethodSubSignature
import sootup.java.core.JavaSootClass
import sootup.java.bytecode.frontend.conversion.signatureFor
import sootup.core.types.ClassType
import io.johnsonlee.graphite.sootup.SootUpAdapter.IndexedClass

/** Inventories artifact origins, resource files and bundle declarations. */
internal class ResourceInventory(private val context: SootUpAdapter) {
    private val declaredMethodSubSignaturesByClass = context.declaredMethodSubSignaturesByClass
    private val classOriginsByName = context.classOriginsByName
    private val classOriginSourceCounts = context.classOriginSourceCounts
    private val duplicateClasses = context.duplicateClasses
    private val multiReleaseArtifacts = context.multiReleaseArtifacts
    private var classEntrySources
        get() = context.classEntrySources
        set(value) { context.classEntrySources = value }
    private val artifactDependenciesByArtifact = context.artifactDependenciesByArtifact
    private val resourceFilesByPath = context.resourceFilesByPath
    private val configurationResourcePaths = context.configurationResourcePaths
    private var classesByNameCache
        get() = context.classesByNameCache
        set(value) { context.classesByNameCache = value }
    private val view = context.view
    private val resourceAccessor = context.resourceAccessor
    private val inputLocationSources = context.inputLocationSources
    private val singleArtifactSource = context.singleArtifactSource
    private val graphBuilder = context.graphBuilder
    private val bytecodeMethods get() = context.bytecodeMethods
    private val declarations get() = context.declarations
    private val localeValues get() = context.localeValues
    private val metadata get() = context.metadata
    private val methodSources get() = context.methodSources

    fun indexSootClassOrigins(classes: Iterable<SootClass>): List<IndexedClass> {
        if (inputLocationSources.isEmpty()) return emptyList()
        val indexedClasses = mutableListOf<IndexedClass>()
        classes.forEach { sootClass ->
            if (sootClass is JavaSootClass) {
                val source = sourceForClass(sootClass) ?: return@forEach
                val className = sootClass.type.fullyQualifiedName
                if (className.endsWith(".package-info") || className.endsWith(".module-info")) {
                    return@forEach
                }
                classOriginsByName.putIfAbsent(className, source)
                classOriginSourceCounts[source] = (classOriginSourceCounts[source] ?: 0) + 1
                indexedClasses += IndexedClass(sootClass, source)
            }
        }
        return indexedClasses
    }

    fun sourceForClass(sootClass: JavaSootClass): String? =
        inputLocationSources[sootClass.classSource.analysisInputLocation]

    fun indexResourceValues(loadedClassSources: Set<String>): List<ResourceEntry> {
        val classEntries = mutableListOf<ResourceEntry>()
        classEntrySources = HashMap()
        resourceAccessor.list("**").forEach { entry ->
            if (entry.path.endsWith(CLASS_FILE_SUFFIX, ignoreCase = true)) {
                noteClassEntry(entry)
                if (entry.source in loadedClassSources) {
                    return@forEach
                }
                classEntries += entry
                classResourcePathToName(entry.path)?.let { className ->
                    classOriginsByName.putIfAbsent(className, entry.source)
                    classOriginSourceCounts[entry.source] = (classOriginSourceCounts[entry.source] ?: 0) + 1
                }
                return@forEach
            }
            val format = localeValues.resourceFormat(entry.path)
            val profile = localeValues.resourceProfile(entry.path)
            val fileNode = ResourceFileNode(
                id = metadata.nextNodeId(RESOURCE_SOURCE),
                path = entry.path,
                source = entry.source,
                format = format,
                profile = profile
            )
            graphBuilder.addNode(fileNode)
            resourceFilesByPath.getOrPut(entry.path) { mutableListOf() }.add(fileNode)
            if (localeValues.isResourceConfig(entry.path)) {
                configurationResourcePaths += entry.path
            }
        }
        classEntrySources = HashMap()
        return classEntries
    }

    fun indexArtifactDependency(sootClass: JavaSootClass, source: String) {
        val fromArtifact = artifactKey(source) ?: return
        val referencedClasses = runCatching {
            extractReferencedClasses(Files.readAllBytes(sootClass.classSource.sourcePath))
        }.getOrElse {
            declarations.log { "Failed to extract artifact dependencies from $source!/${sootClass.type.fullyQualifiedName}: ${it.message}" }
            return
        }
        recordArtifactDependencies(fromArtifact, referencedClasses)
    }

    fun indexArtifactDependency(entry: ResourceEntry) {
        val fromArtifact = artifactKey(entry.source) ?: return
        val referencedClasses = runCatching {
            resourceAccessor.open(entry.path).use { input ->
                extractReferencedClasses(input.readBytes())
            }
        }.getOrElse {
            declarations.log { "Failed to extract artifact dependencies from ${entry.source}!/${entry.path}: ${it.message}" }
            return
        }
        recordArtifactDependencies(fromArtifact, referencedClasses)
    }

    fun recordArtifactDependencies(fromArtifact: String, referencedClasses: Set<String>) {
        referencedClasses.forEach { referencedClass ->
            val targetArtifact = classOriginsByName[referencedClass]?.let(::artifactKey) ?: return@forEach
            if (targetArtifact == fromArtifact) return@forEach
            artifactDependenciesByArtifact
                .getOrPut(fromArtifact) { mutableMapOf() }
                .merge(targetArtifact, 1, Int::plus)
        }
    }

    fun extractReferencedClasses(bytecode: ByteArray): Set<String> {
        val references = linkedSetOf<String>()
        fun addType(type: AsmType?) {
            when (type?.sort) {
                AsmType.ARRAY -> addType(type.elementType)
                AsmType.OBJECT -> references += type.className
                AsmType.METHOD -> {
                    addType(type.returnType)
                    type.argumentTypes.forEach(::addType)
                }
            }
        }

        ClassReader(bytecode).accept(object : ClassVisitor(ASM_API_VERSION) {
            override fun visit(
                version: Int,
                access: Int,
                name: String?,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?
            ) {
                superName?.replace('/', '.')?.let { references += it }
                interfaces.orEmpty().forEach { references += it.replace('/', '.') }
            }

            override fun visitField(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                value: Any?
            ): FieldVisitor {
                addType(descriptor?.let(AsmType::getType))
                if (value is AsmType) addType(value)
                return object : FieldVisitor(ASM_API_VERSION) {}
            }

            override fun visitMethod(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor {
                addType(descriptor?.let(AsmType::getMethodType))
                exceptions.orEmpty().forEach { references += it.replace('/', '.') }
                return object : MethodVisitor(ASM_API_VERSION) {
                    override fun visitTypeInsn(opcode: Int, type: String) {
                        references += type.replace('/', '.')
                    }

                    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                        references += owner.replace('/', '.')
                        addType(AsmType.getType(descriptor))
                    }

                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        references += owner.replace('/', '.')
                        addType(AsmType.getMethodType(descriptor))
                    }

                    override fun visitLdcInsn(value: Any) {
                        if (value is AsmType) addType(value)
                    }

                    override fun visitInvokeDynamicInsn(
                        name: String,
                        descriptor: String,
                        bootstrapMethodHandle: Handle,
                        vararg bootstrapMethodArguments: Any
                    ) {
                        addType(AsmType.getMethodType(descriptor))
                        references += bootstrapMethodHandle.owner.replace('/', '.')
                        bootstrapMethodArguments.forEach { arg ->
                            when (arg) {
                                is AsmType -> addType(arg)
                                is Handle -> references += arg.owner.replace('/', '.')
                            }
                        }
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)

        return references
    }

    fun artifactKey(origin: String): String? =
        origin.trim().trimEnd('/').substringAfterLast('/').removeSuffix(".jar").takeIf { it.isNotBlank() }

    /**
     * What a class entry tells the evidence: a copy under `META-INF/versions/` marks its archive
     * multi-release (this frontend reads the base version, as SootUp does); any other entry of a
     * multi-artifact input is a definition of its class, duplicate or not.
     */
    fun noteClassEntry(entry: ResourceEntry) {
        if (entry.path.startsWith(MULTI_RELEASE_PREFIX)) {
            multiReleaseArtifacts += entry.source
        } else if (singleArtifactSource == null) {
            classResourcePathToName(entry.path)?.let { recordClassEntry(it, entry.source) }
        }
    }

    /**
     * Note that [source] defines [className]; the second and later sources that do are the class's
     * duplicate definitions, in the order the input lists them. The view holds the first one's.
     */
    fun recordClassEntry(className: String, source: String) {
        val first = classEntrySources.putIfAbsent(className, source) ?: return
        if (first == source) return
        val sources = duplicateClasses.getOrPut(className) { mutableListOf(first) }
        if (source !in sources) sources += source
    }

    fun classResourcePathToName(path: String): String? {
        if (!path.endsWith(CLASS_FILE_SUFFIX, ignoreCase = true)) return null
        val className = path.removeSuffix(CLASS_FILE_SUFFIX).replace('/', '.')
        return if (className.endsWith(".package-info") || className.endsWith(".module-info")) null else className
    }

    fun indexClassBundles(classes: Iterable<SootClass>) {
        classes.forEach { sootClass ->
            if (!isListResourceBundleClass(sootClass)) return@forEach
            indexListResourceBundle(sootClass)
        }
    }

    fun indexListResourceBundle(sootClass: SootClass) {
        val path = sootClass.type.fullyQualifiedName
        if (resourceFilesByPath.containsKey(path)) return
        val fileNode = ResourceFileNode(
            id = metadata.nextNodeId(RESOURCE_SOURCE),
            path = path,
            source = "class-bundle",
            format = "listbundle",
            profile = null
        )
        graphBuilder.addNode(fileNode)
        resourceFilesByPath.getOrPut(path) { mutableListOf() }.add(fileNode)
    }

    fun isListResourceBundleClass(sootClass: SootClass): Boolean {
        var current: SootClass? = sootClass
        var found = false
        while (current != null && !found) {
            val superName = current.superclass.orElse(null)?.fullyQualifiedName
            found = superName != null && superName == "java.util.ListResourceBundle"
            current = if (superName == null || found) null else resolveClassByName(superName)
        }
        return found
    }

    fun resolveClassByName(className: String): SootClass? {
        return classIndex()[className]
    }

    fun classIndex(): Map<String, SootClass> {
        classesByNameCache?.let { return it }
        return view.classes.toList()
            .associateBy { it.type.fullyQualifiedName }
            .also { classesByNameCache = it }
    }

    fun declaresMethod(classType: ClassType, subSignature: MethodSubSignature): Boolean {
        val className = classType.fullyQualifiedName
        val declaredSubSignatures = declaredMethodSubSignaturesByClass[className]
            ?: collectDeclaredMethodSubSignatures(className).also {
                declaredMethodSubSignaturesByClass[className] = it
            }
        return subSignature.toString() in declaredSubSignatures
    }

    fun collectDeclaredMethodSubSignatures(className: String): Set<String> {
        val sootClass = resolveClassByName(className) ?: return emptySet()
        val asmSubSignatures = if (sootClass is JavaSootClass) {
            methodSources.streamingSources(sootClass)?.mapTo(HashSet()) { it.signatureFor(sootClass.type).subSignature.toString() }
                ?: bytecodeMethods.bytecodeMethods(sootClass)?.mapTo(HashSet()) { it.signature.subSignature.toString() }
        } else {
            null
        }
        return asmSubSignatures ?: bytecodeMethods.resolveMethodsOrEmpty(sootClass)
            .mapTo(HashSet()) { it.signature.subSignature.toString() }
    }
}
