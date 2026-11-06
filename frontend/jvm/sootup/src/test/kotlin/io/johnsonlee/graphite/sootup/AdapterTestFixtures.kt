package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.input.EmptyResourceAccessor
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.ResourceEntry
import java.nio.file.Path
import java.util.ResourceBundle
import java.util.stream.Stream
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import sootup.core.IdentifierFactory
import sootup.core.frontend.BodySource
import sootup.core.jimple.basic.NoPositionInformation
import sootup.core.inputlocation.AnalysisInputLocation
import sootup.core.model.Body
import sootup.core.model.ClassModifier
import sootup.core.model.MethodModifier
import sootup.core.model.SootClass
import sootup.core.model.SootField
import sootup.core.model.SootMethod
import sootup.core.model.SourceType
import sootup.core.signatures.FieldSignature
import sootup.core.signatures.MethodSignature
import sootup.core.typehierarchy.TypeHierarchy
import sootup.core.types.ClassType
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation
import sootup.java.core.JavaIdentifierFactory
import sootup.java.core.AnnotationUsage
import sootup.java.core.JavaSootClass
import sootup.java.core.JavaSootClassSource
import sootup.java.core.JavaSootField
import sootup.java.core.JavaSootMethod
import sootup.java.core.views.JavaView
import sootup.core.views.View

internal abstract class AdapterTestFixtures {
    protected val identifierFactory = JavaIdentifierFactory.getInstance()

    protected fun fallbackSyntheticMethods(): List<SootMethod> {
        val className = "sample.fake.Owner\$\$ExternalSyntheticLambda0"
        val run = object : FakeSootMethod(identifierFactory.getMethodSignature(className, "run", "void", emptyList())) {
            override fun hasBody(): Boolean = false
        }
        // The self-typed parameter makes the constructor depend on the class fingerprint.
        val constructor = object : FakeSootMethod(
            identifierFactory.getMethodSignature(className, "<init>", "void", listOf(className)),
            listOf(MethodModifier.SYNTHETIC)
        ) {
            override fun hasBody(): Boolean = false
        }
        return listOf(run, constructor)
    }

    protected fun collectFallbackSyntheticIdentities(methods: List<SootMethod>): Pair<List<String>, Map<String, String>> {
        val adapter = createAdapter()
        val sootClass = FakeSootClass(methods.first().declaringClassType, methods = methods.toCollection(LinkedHashSet()))
        assertTrue(SyntheticIdentity.isSyntheticClass(sootClass))
        assertNull(invokePrivate<Sequence<SootMethod>?>(adapter, "streamMethodsOrNull", arrayOf(SootClass::class.java), sootClass))
        val collector = SyntheticIdentity.Collector()
        collector.addClass(sootClass)
        val visited = mutableListOf<String>()
        val action: (SootMethod) -> Unit = { method ->
            visited.add(method.signature.toString())
            val key = "${method.declaringClassType.fullyQualifiedName}.${method.name}(${method.parameterTypes.joinToString(",")})"
            collector.addMethod(method, key, SyntheticIdentity.isSyntheticMethod(method))
        }
        invokePrivate<Unit>(adapter, "forEachMethod", arrayOf(SootClass::class.java, Function1::class.java), sootClass, action)
        val identities = collector.resolve()
        assertEquals(0, collector.skipped)
        return visited to identities
    }

    protected fun createAdapter(
        includePackages: List<String> = listOf("sample.resources"),
        buildCallGraph: Boolean = false,
        resourceAccessor: ResourceAccessor = EmptyResourceAccessor
    ): SootUpAdapter {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")
        val location = PathBasedAnalysisInputLocation.create(testClassesDir, SourceType.Application)
        val inputLocations: List<AnalysisInputLocation> = listOf(location)
        return SootUpAdapter(
            view = JavaView(inputLocations),
            config = LoaderConfig(includePackages = includePackages, buildCallGraph = buildCallGraph),
            signatureReader = BytecodeSignatureReader(),
            extensions = emptyList(),
            resourceAccessor = resourceAccessor,
            inputLocationSources = mapOf(location to testClassesDir.fileName.toString()),
            graphBuilder = DefaultGraph.Builder()
        )
    }

    protected fun referencedClassBytes(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            "sample/refs/Owner",
            null,
            "java/lang/Object",
            arrayOf("java/io/Serializable")
        )
        writer.visitField(Opcodes.ACC_PRIVATE, "items", "[Ljava/util/List;", null, null).visitEnd()
        writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "read",
            "(Ljava/util/Map;[Ljava/lang/String;)Ljava/util/Optional;",
            null,
            arrayOf("java/io/IOException")
        ).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    protected fun createResourceBackedAdapter(): SootUpAdapter {
        val testClassesDir = findTestClassesDir()
        val resources = mapOf(
            "sample/resources/MessagesListBundle_ko_KR.class" to findCompiledClassBytes("sample/resources/MessagesListBundle_ko_KR.class"),
            "sample/resources/ClassOnlyControl.class" to findCompiledClassBytes("sample/resources/ClassOnlyControl.class"),
            "sample/resources/KoreanOnlyControl.class" to findCompiledClassBytes("sample/resources/KoreanOnlyControl.class")
        )
        val location = PathBasedAnalysisInputLocation.create(testClassesDir, SourceType.Application)
        val inputLocations: List<AnalysisInputLocation> = listOf(location)
        val resourceAccessor = object : ResourceAccessor {
            override fun list(pattern: String): Sequence<ResourceEntry> = emptySequence()
            override fun open(path: String) = resources[path]?.inputStream() ?: error("Missing resource $path")
        }
        return SootUpAdapter(
            view = JavaView(inputLocations),
            config = LoaderConfig(includePackages = listOf("sample.resources"), buildCallGraph = false),
            signatureReader = BytecodeSignatureReader(),
            extensions = emptyList(),
            resourceAccessor = resourceAccessor,
            inputLocationSources = mapOf(location to testClassesDir.fileName.toString()),
            graphBuilder = DefaultGraph.Builder()
        )
    }

    protected fun resolveJavaSootClass(adapter: SootUpAdapter, className: String): JavaSootClass =
        invokePrivate<JavaSootClass?>(adapter, "resolveClassByName", arrayOf(String::class.java), className)
            ?: error("Unable to resolve $className")

    protected fun nullReturnMethod(): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC, "getFallbackLocale",
            "(Ljava/lang/String;Ljava/util/Locale;)Ljava/util/Locale;", null, null).apply {
            instructions.add(InsnNode(Opcodes.ACONST_NULL))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }

    protected fun emptyMethod(): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "empty", "()Ljava/lang/Object;", null, null)

    protected fun listOfMethod(): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "formats", "()Ljava/util/List;", null, null).apply {
            instructions.add(LdcInsnNode("java.class"))
            instructions.add(LdcInsnNode("java.properties"))
            instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/List", "of",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;", true))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }

    protected fun singletonListMethod(): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "formats", "()Ljava/util/List;", null, null).apply {
            instructions.add(LdcInsnNode("java.properties"))
            instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Collections", "singletonList",
                "(Ljava/lang/Object;)Ljava/util/List;", false))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }

    protected fun languageTagMethod(): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "locale", "()Ljava/lang/Object;", null, null).apply {
            instructions.add(LdcInsnNode("ko-KR"))
            instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Locale", "forLanguageTag",
                "(Ljava/lang/String;)Ljava/util/Locale;", false))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }

    protected fun intPushMethod(opcode: Int, operand: Int): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "literal", "()Ljava/lang/Object;", null, null).apply {
            instructions.add(org.objectweb.asm.tree.IntInsnNode(opcode, operand))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }

    protected fun iconstReturnMethod(opcode: Int): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "literal", "()Ljava/lang/Object;", null, null).apply {
            instructions.add(InsnNode(opcode))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }

    protected fun localeCtorMethod(): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "locale", "()Ljava/lang/Object;", null, null).apply {
            instructions.add(LdcInsnNode("ko"))
            instructions.add(LdcInsnNode("KR"))
            instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/Locale", "<init>",
                "(Ljava/lang/String;Ljava/lang/String;)V", false))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }

    protected fun arraysAsListMethod(): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "formats", "()Ljava/util/List;", null, null).apply {
            instructions.add(LdcInsnNode("java.class"))
            instructions.add(LdcInsnNode("java.properties"))
            instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/List", "of",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;", true))
            instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Arrays", "asList",
                "(Ljava/lang/Object;)Ljava/util/List;", false))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }

    protected fun storedStringMethod(): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "stored", "()Ljava/lang/Object;", null, null).apply {
            instructions.add(LdcInsnNode("stored"))
            instructions.add(VarInsnNode(Opcodes.ASTORE, 0))
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.add(InsnNode(Opcodes.DUP))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }

    protected fun singleFormatMethod(value: String): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "formats", "()Ljava/lang/Object;", null, null).apply {
            instructions.add(LdcInsnNode(value))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }

    protected fun singletonIntListMethod(value: Int): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "formats", "()Ljava/util/List;", null, null).apply {
            instructions.add(org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, value))
            instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false))
            instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Collections", "singletonList",
                "(Ljava/lang/Object;)Ljava/util/List;", false))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }

    protected fun singleIntListOfMethod(value: Int): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "formats", "()Ljava/util/List;", null, null).apply {
            instructions.add(org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, value))
            instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/List", "of", "(Ljava/lang/Object;)Ljava/util/List;", true))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }

    @Suppress("UNCHECKED_CAST")
    protected fun <T> readField(target: Any, name: String): T {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target) as T
    }

    @Suppress("UNCHECKED_CAST")
    protected fun <T> readProperty(target: Any, name: String): T {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target) as T
    }

    protected fun writeField(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    @Suppress("UNCHECKED_CAST")
    protected fun <T> invokePrivate(target: Any, name: String, parameterTypes: Array<Class<*>>, vararg args: Any?): T {
        val method = operationFor(target, name, *parameterTypes)
        method.isAccessible = true
        return method.invoke(target, *args) as T
    }

    protected fun slotId(adapter: SootUpAdapter, slot: DispatchSlot): Int =
        invokePrivate(adapter, "slotId", arrayOf(DispatchSlot::class.java), slot)

    protected fun targetsOf(adapter: SootUpAdapter, slot: DispatchSlot): Set<DispatchTarget>? =
        readField<MutableList<Set<DispatchTarget>?>>(adapter, "slotTargets")[slotId(adapter, slot)]

    protected fun setTargets(adapter: SootUpAdapter, slot: DispatchSlot, targets: Set<DispatchTarget>) {
        readField<MutableList<Set<DispatchTarget>?>>(adapter, "slotTargets")[slotId(adapter, slot)] = targets
    }

    protected fun flow(adapter: SootUpAdapter, from: DispatchSlot, to: DispatchSlot) =
        invokePrivate<Unit>(adapter, "flowSlot", arrayOf(DispatchSlot::class.java, DispatchSlot::class.java), from, to)

    protected fun waitOn(adapter: SootUpAdapter, slot: DispatchSlot, pending: PendingDispatch) =
        invokePrivate<MutableList<PendingDispatch>>(adapter, "callsOn", arrayOf(Int::class.javaPrimitiveType!!),
            slotId(adapter, slot)).add(pending)

    protected fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("frontend/jvm/sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }

    protected fun findCompiledClassBytes(relativePath: String): ByteArray =
        findTestClassesDir().resolve(relativePath).toFile().readBytes()

    protected class SimpleBundle(private val value: String) : ResourceBundle() {
        override fun handleGetObject(key: String): Any = value
        override fun getKeys(): java.util.Enumeration<String> = java.util.Collections.enumeration(listOf("hello"))
        fun withParent(parent: ResourceBundle): SimpleBundle {
            setParent(parent)
            return this
        }
    }

    protected class FakeAnnotationUsage {
        fun getAnnotation(): FakeAnnotation = FakeAnnotation()
        fun getValues(): Map<String, Any?> = mapOf("value" to "\"ok\"")
    }

    protected class FakeAnnotation(private val name: String = "sample.Annotation") {
        fun getFullyQualifiedName(): String = name
    }

    protected open class FakeSootMethod(
        signature: MethodSignature,
        modifiers: Iterable<MethodModifier> = emptyList()
    ) : JavaSootMethod(
        FakeBodySource(signature),
        signature,
        modifiers,
        emptyList<ClassType>(),
        NoPositionInformation.getInstance()
    )

    protected class FakeBodySource(private val signature: MethodSignature) : BodySource {
        override fun resolveBody(modifiers: Iterable<MethodModifier>): Body =
            error("Fake method has no body")

        override fun resolveAnnotationsDefaultValue(): Any? = null

        override fun getSignature(): MethodSignature = signature
    }

    protected open class FakeSootClass(
        classType: ClassType,
        private val methods: Set<SootMethod> = emptySet(),
        private val failure: Throwable? = null,
        private val enumClass: Boolean = false
    ) : JavaSootClass(FakeClassSource(classType), SourceType.Application) {
        @Suppress("UNCHECKED_CAST")
        override fun getMethods(): Set<JavaSootMethod> {
            failure?.let { throw it }
            return methods as Set<JavaSootMethod>
        }

        override fun isEnum(): Boolean = enumClass
    }

    protected class FakeClassSource(classType: ClassType) : JavaSootClassSource(
        PathBasedAnalysisInputLocation.create(Path.of(System.getProperty("java.io.tmpdir")), SourceType.Application),
        classType,
        Path.of("fake.class")
    ) {
        override fun resolveMethods(): Collection<JavaSootMethod> = emptyList()
        override fun resolveFields(): Collection<JavaSootField> = emptyList()
        override fun resolveModifiers(): Set<ClassModifier> = emptySet()
        override fun resolveInterfaces(): Set<ClassType> = emptySet()
        override fun resolveSuperclass(): java.util.Optional<ClassType> = java.util.Optional.empty()
        override fun resolveOuterClass(): java.util.Optional<ClassType> = java.util.Optional.empty()
        override fun resolvePosition() = NoPositionInformation.getInstance()
        override fun resolveAnnotations(): Iterable<AnnotationUsage> = emptyList()
        override fun buildClass(sourceType: SourceType): JavaSootClass = FakeSootClass(classType)
    }

    protected class FakeView(
        private val classes: List<SootClass>,
        private val identifierFactory: IdentifierFactory
    ) : View {
        override fun getClasses(): Stream<out SootClass> = classes.stream()
        override fun getClass(type: ClassType) = classes.firstOrNull { it.type == type }.let { java.util.Optional.ofNullable(it) }
        override fun getField(signature: FieldSignature): java.util.Optional<out SootField> = java.util.Optional.empty()
        override fun getMethod(signature: MethodSignature): java.util.Optional<out SootMethod> = java.util.Optional.empty()
        override fun getTypeHierarchy(): TypeHierarchy = error("Fake view does not provide a type hierarchy")
        override fun getIdentifierFactory(): IdentifierFactory = identifierFactory
    }
}
