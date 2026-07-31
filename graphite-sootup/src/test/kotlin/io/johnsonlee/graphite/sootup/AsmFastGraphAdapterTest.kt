package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.JavaArchiveLayout
import io.johnsonlee.graphite.input.LoaderConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

@Suppress("LargeClass")
class AsmFastGraphAdapterTest {

    @Test
    fun `fast build creates core graph evidence from bytecode`() {
        val graph = JavaProjectLoader(
            LoaderConfig(
                includePackages = listOf("sample.simple"),
                buildCallGraph = false,
                extractAnnotations = false,
                trackCrossMethodFunctionalDispatch = false,
                fastBuild = true
            )
        ).load(findTestClassesDir())

        val simpleFields = graph.nodes<FieldNode>()
            .filter { it.descriptor.declaringClass.className == "sample.simple.SimpleService" }
            .map { it.descriptor.name }
            .toSet()
        assertEquals(setOf("items", "scores"), simpleFields)

        val processMethods = graph.methods(
            MethodPattern(
                declaringClass = "sample.simple.SimpleService",
                name = "processItem"
            )
        ).toList()
        assertEquals(1, processMethods.size)

        val callSites = graph.nodes<CallSiteNode>().toList()
        assertTrue(
            callSites.any {
                it.caller.declaringClass.className == "sample.simple.SimpleService" &&
                    it.callee.name == "processItem"
            },
            "fast build should create call site nodes for bytecode invokes"
        )
        assertTrue(
            graph.nodes<LocalVariable>().any { it.method.name == "processItem" },
            "fast build should create local value nodes"
        )
        assertTrue(
            graph.nodes<Node>().any { node -> graph.outgoing(node.id, DataFlowEdge::class.java).any() },
            "fast build should create basic dataflow edges"
        )
    }

    @Test
    fun `fast build handles broad bytecode corpus`() {
        val graph = JavaProjectLoader(fastConfig(includePackages = listOf("sample"))).load(findTestClassesDir())

        assertTrue(
            graph.methods(
                MethodPattern(
                    declaringClass = "sample.lambda.LambdaExample",
                    name = "processWithLambda"
                )
            ).any(),
            "fast build should discover lambda fixture methods"
        )
        assertTrue(
            graph.nodes<CallSiteNode>().count() > 100,
            "fast build should create call sites across the bytecode fixture corpus"
        )
        assertTrue(
            graph.nodes<LocalVariable>().count() > 100,
            "fast build should create locals across the bytecode fixture corpus"
        )
        assertTrue(
            graph.nodes<StringConstant>().any { it.value == "one" },
            "fast build should capture string constants from switch fixtures"
        )
        assertTrue(
            graph.nodes<IntConstant>().any { it.value == 42 },
            "fast build should capture integer constants"
        )
        assertTrue(
            graph.nodes<Node>().any { node ->
                graph.outgoing(node.id, DataFlowEdge::class.java)
                    .any { it.kind == DataFlowKind.PARAMETER_PASS }
            },
            "fast build should connect invocation arguments to call sites"
        )
        assertTrue(
            graph.nodes<Node>().any { node ->
                graph.outgoing(node.id, DataFlowEdge::class.java)
                    .any { it.kind == DataFlowKind.RETURN_VALUE }
            },
            "fast build should connect return values to return nodes"
        )
    }

    @Test
    fun `fast build indexes non class resources`() {
        val root = Files.createTempDirectory("graphite-fast-resources")
        try {
            Files.createDirectories(root.resolve("config"))
            Files.createDirectories(root.resolve("sample/fast"))
            Files.writeString(root.resolve("application-prod.yaml"), "feature: true\n")
            Files.writeString(root.resolve("config/settings.json"), """{"enabled":true}""")
            Files.writeString(root.resolve("application-dev.properties"), "feature=true\n")
            Files.writeString(root.resolve("config/logback.xml"), "<configuration/>\n")
            Files.write(root.resolve("sample/fast/Broken.class"), byteArrayOf(0, 1, 2, 3))

            val logs = mutableListOf<String>()
            val graph = JavaProjectLoader(fastConfig(verbose = logs::add)).load(root)

            val resources = graph.nodes<ResourceFileNode>().toList()
            assertTrue(
                resources.any {
                    it.path == "application-prod.yaml" &&
                        it.format == "yaml" &&
                        it.profile == "prod"
                },
                "fast build should index profile-specific YAML resources"
            )
            assertTrue(
                resources.any { it.path == "config/settings.json" && it.format == "json" },
                "fast build should index nested JSON resources"
            )
            assertTrue(
                resources.any { it.path == "application-dev.properties" && it.format == "properties" },
                "fast build should index properties resources"
            )
            assertTrue(
                resources.any { it.path == "config/logback.xml" && it.format == "xml" },
                "fast build should index XML resources"
            )
            assertTrue(
                logs.any { it.contains("Skipping class") && it.contains("Broken.class") },
                "fast build should skip malformed class resources without failing the build"
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `fast build handles generated opcode fixture`() {
        val root = Files.createTempDirectory("graphite-fast-opcodes")
        try {
            val classFile = root.resolve("sample/fast/GeneratedFastOpcodes.class")
            Files.createDirectories(classFile.parent)
            Files.write(classFile, generatedOpcodeClass())

            val graph = JavaProjectLoader(fastConfig(includePackages = listOf("sample.fast"))).load(root)

            assertTrue(
                graph.methods(
                    MethodPattern(
                        declaringClass = "sample.fast.GeneratedFastOpcodes",
                        name = "dupOps"
                    )
                ).any(),
                "fast build should discover methods from generated bytecode"
            )
            assertTrue(
                graph.nodes<FieldNode>().any {
                    it.descriptor.declaringClass.className == "sample.fast.GeneratedFastOpcodes" &&
                        it.descriptor.name == "staticField"
                },
                "fast build should discover generated fields"
            )
            assertTrue(
                graph.nodes<Node>().any { node ->
                    graph.outgoing(node.id, DataFlowEdge::class.java)
                        .any { it.kind == DataFlowKind.ARRAY_STORE }
                },
                "fast build should record array store dataflow from generated bytecode"
            )
            assertTrue(
                graph.nodes<Node>().any { node ->
                    graph.outgoing(node.id, DataFlowEdge::class.java)
                        .any { it.kind == DataFlowKind.FIELD_STORE }
                },
                "fast build should record field store dataflow from generated bytecode"
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `fast build records cross artifact dependencies from descriptors`() {
        val root = Files.createTempDirectory("graphite-fast-artifacts")
        try {
            writeJar(
                root.resolve("support.jar"),
                mapOf("sample/fast/support/Support.class" to generatedSupportClass())
            )
            writeJar(
                root.resolve("dep.jar"),
                mapOf("sample/fast/dep/Dep.class" to generatedDependencyClass())
            )

            val graph = JavaProjectLoader(
                fastConfig(includePackages = listOf("sample.fast.dep"))
            ).load(root)

            assertEquals(
                1,
                graph.artifactDependencies()["dep"]?.get("support"),
                "fast build should record descriptor-level dependencies between artifacts"
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `fast build selects nested Spring Boot and WAR libraries`() {
        val testClassesDir = findTestClassesDir()
        val genericsJar = buildJarFromClasses(testClassesDir, "sample/generics/", "generics-lib.jar")
        val regularJar = buildJarFromClasses(testClassesDir, "sample/simple/", "simple-app.jar")
        val bootJar = buildSpringBootJarWithLibs(testClassesDir, "sample/simple/", listOf(genericsJar))
        val warFile = buildWarFileWithLibs(testClassesDir, "sample/simple/", listOf(genericsJar))
        val distDir = Files.createTempDirectory("graphite-fast-dist")
        val config = fastConfig(
            includePackages = listOf("sample.simple", "sample.generics"),
            includeLibraries = true
        )

        try {
            val regularGraph = JavaProjectLoader(config).load(regularJar)
            assertTrue(
                regularGraph.nodes<FieldNode>().any {
                    it.descriptor.declaringClass.className == "sample.simple.SimpleService"
                },
                "fast build should load regular JAR inputs"
            )

            Files.copy(genericsJar, distDir.resolve(genericsJar.fileName.toString()))
            val directoryGraph = JavaProjectLoader(config).load(distDir)
            assertTrue(
                directoryGraph.nodes<FieldNode>().any {
                    it.descriptor.declaringClass.className == "sample.generics.GenericFieldService"
                },
                "fast build should select matching JARs from directory inputs"
            )

            val bootGraph = JavaProjectLoader(config).load(bootJar)
            assertTrue(
                bootGraph.nodes<FieldNode>().any {
                    it.descriptor.declaringClass.className == "sample.generics.GenericFieldService"
                },
                "fast build should load matching nested Spring Boot libraries"
            )
            assertTrue(
                bootGraph.classOrigin("sample.generics.GenericFieldService")?.startsWith("generics-lib") == true,
                "fast build should record the Spring Boot nested library origin"
            )

            val warGraph = JavaProjectLoader(config).load(warFile)
            assertTrue(
                warGraph.nodes<FieldNode>().any {
                    it.descriptor.declaringClass.className == "sample.generics.GenericFieldService"
                },
                "fast build should load matching nested WAR libraries"
            )
            assertTrue(
                warGraph.classOrigin("sample.generics.GenericFieldService")?.startsWith("generics-lib") == true,
                "fast build should record the WAR nested library origin"
            )
        } finally {
            Files.deleteIfExists(genericsJar)
            Files.deleteIfExists(regularJar)
            Files.deleteIfExists(bootJar)
            Files.deleteIfExists(warFile)
            distDir.toFile().deleteRecursively()
        }
    }

    private fun fastConfig(
        includePackages: List<String> = emptyList(),
        includeLibraries: Boolean = false,
        verbose: ((String) -> Unit)? = null
    ): LoaderConfig =
        LoaderConfig(
            includePackages = includePackages,
            includeLibraries = includeLibraries,
            buildCallGraph = false,
            extractAnnotations = false,
            trackCrossMethodFunctionalDispatch = false,
            fastBuild = true,
            verbose = verbose
        )

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }

    private fun buildJarFromClasses(
        classesDir: Path,
        classPathPrefix: String,
        jarName: String
    ): Path {
        val jarPath = Files.createTempFile(jarName.removeSuffix(".jar"), ".jar")

        JarOutputStream(
            Files.newOutputStream(jarPath),
            Manifest().apply { mainAttributes.putValue("Manifest-Version", "1.0") }
        ).use { output ->
            copyClassesToJar(output, classesDir, classPathPrefix) { it }
        }

        return jarPath
    }

    private fun buildSpringBootJarWithLibs(
        classesDir: Path,
        classPathPrefix: String,
        libJars: List<Path>
    ): Path {
        val jarPath = Files.createTempFile("fast-springboot", ".jar")

        JarOutputStream(
            Files.newOutputStream(jarPath),
            Manifest().apply {
                mainAttributes.putValue("Manifest-Version", "1.0")
                mainAttributes.putValue(JavaArchiveLayout.SPRING_BOOT_CLASSES_ATTRIBUTE, JavaArchiveLayout.BOOT_INF_CLASSES)
                mainAttributes.putValue(JavaArchiveLayout.SPRING_BOOT_LIB_ATTRIBUTE, JavaArchiveLayout.BOOT_INF_LIB)
            }
        ).use { output ->
            output.putNextEntry(JarEntry(JavaArchiveLayout.BOOT_INF_CLASSES))
            output.closeEntry()
            copyClassesToJar(output, classesDir, classPathPrefix, JavaArchiveLayout::bootInfClassEntry)

            output.putNextEntry(JarEntry(JavaArchiveLayout.BOOT_INF_LIB))
            output.closeEntry()
            libJars.forEach { libJar ->
                output.putNextEntry(JarEntry(JavaArchiveLayout.bootInfLibEntry(libJar.fileName.toString())))
                Files.newInputStream(libJar).use { it.copyTo(output) }
                output.closeEntry()
            }
        }

        return jarPath
    }

    private fun buildWarFileWithLibs(
        classesDir: Path,
        classPathPrefix: String,
        libJars: List<Path>
    ): Path {
        val warPath = Files.createTempFile("fast-war", ".war")

        ZipOutputStream(Files.newOutputStream(warPath)).use { output ->
            output.putNextEntry(ZipEntry(JavaArchiveLayout.WEB_INF_CLASSES))
            output.closeEntry()
            copyClassesToZip(output, classesDir, classPathPrefix, JavaArchiveLayout::webInfClassEntry)

            output.putNextEntry(ZipEntry(JavaArchiveLayout.WEB_INF_LIB))
            output.closeEntry()
            libJars.forEach { libJar ->
                output.putNextEntry(ZipEntry(JavaArchiveLayout.webInfLibEntry(libJar.fileName.toString())))
                Files.newInputStream(libJar).use { it.copyTo(output) }
                output.closeEntry()
            }
        }

        return warPath
    }

    private fun copyClassesToJar(
        output: JarOutputStream,
        classesDir: Path,
        classPathPrefix: String,
        entryName: (String) -> String
    ) {
        copyClasses(classesDir, classPathPrefix) { relativePath, classFile ->
            output.putNextEntry(JarEntry(entryName(relativePath)))
            classFile.inputStream().use { it.copyTo(output) }
            output.closeEntry()
        }
    }

    private fun copyClassesToZip(
        output: ZipOutputStream,
        classesDir: Path,
        classPathPrefix: String,
        entryName: (String) -> String
    ) {
        copyClasses(classesDir, classPathPrefix) { relativePath, classFile ->
            output.putNextEntry(ZipEntry(entryName(relativePath)))
            classFile.inputStream().use { it.copyTo(output) }
            output.closeEntry()
        }
    }

    private fun copyClasses(
        classesDir: Path,
        classPathPrefix: String,
        copy: (String, File) -> Unit
    ) {
        val baseDir = classesDir.toFile()
        val prefixDir = File(baseDir, classPathPrefix)
        if (prefixDir.exists()) {
            prefixDir.walk()
                .filter { it.isFile && it.name.endsWith(".class") }
                .forEach { classFile ->
                    val relativePath = classFile.relativeTo(baseDir).path.replace(File.separatorChar, '/')
                    copy(relativePath, classFile)
                }
        }
    }

    private fun writeJar(path: Path, entries: Map<String, ByteArray>) {
        JarOutputStream(
            Files.newOutputStream(path),
            Manifest().apply { mainAttributes.putValue("Manifest-Version", "1.0") }
        ).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(JarEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
    }

    private fun generatedSupportClass(): ByteArray =
        ClassWriter(0).apply {
            visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC,
                "sample/fast/support/Support",
                null,
                "java/lang/Object",
                null
            )
            addGeneratedConstructor(this)
            visitEnd()
        }.toByteArray()

    private fun generatedDependencyClass(): ByteArray =
        ClassWriter(0).apply {
            visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC,
                "sample/fast/dep/Dep",
                null,
                "java/lang/Object",
                null
            )
            addGeneratedConstructor(this)
            visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "support",
                "()Lsample/fast/support/Support;",
                null,
                null
            ).apply {
                visitCode()
                visitInsn(Opcodes.ACONST_NULL)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(1, 0)
                visitEnd()
            }
            visitEnd()
        }.toByteArray()

    @Suppress("LongMethod")
    private fun generatedOpcodeClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            "sample/fast/GeneratedFastOpcodes",
            null,
            "java/lang/Object",
            null
        )
        writer.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "staticField", "I", null, null).visitEnd()
        writer.visitField(Opcodes.ACC_PUBLIC, "instanceField", "I", null, null).visitEnd()
        addGeneratedConstructor(writer)
        addConstantAndLocalMethod(writer)
        addDupMethod(writer)
        addArrayMethod(writer)
        addFieldMethod(writer)
        addJumpMethod(writer)
        addTypeMethod(writer)
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun addGeneratedConstructor(writer: ClassWriter) {
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
    }

    private fun addConstantAndLocalMethod(writer: ClassWriter) {
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "constantsAndLocals", "()V", null, null).apply {
            visitCode()
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.LCONST_0)
            visitVarInsn(Opcodes.LSTORE, 0)
            visitInsn(Opcodes.LCONST_1)
            visitInsn(Opcodes.POP2)
            visitInsn(Opcodes.FCONST_0)
            visitVarInsn(Opcodes.FSTORE, 2)
            visitInsn(Opcodes.FCONST_1)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.FCONST_2)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.DCONST_0)
            visitVarInsn(Opcodes.DSTORE, 3)
            visitInsn(Opcodes.DCONST_1)
            visitInsn(Opcodes.POP2)
            visitVarInsn(Opcodes.ALOAD, 9)
            visitInsn(Opcodes.POP)
            visitIincInsn(8, 1)
            visitInsn(Opcodes.RETURN)
            visitMaxs(4, 10)
            visitEnd()
        }
    }

    private fun addDupMethod(writer: ClassWriter) {
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "dupOps", "()V", null, null).apply {
            visitCode()
            ints(1, 2)
            visitInsn(Opcodes.DUP_X1)
            popMany(3)
            ints(1, 2, 3)
            visitInsn(Opcodes.DUP_X2)
            popMany(4)
            ints(1, 2)
            visitInsn(Opcodes.DUP2)
            popMany(4)
            ints(1, 2, 3)
            visitInsn(Opcodes.DUP2_X1)
            popMany(5)
            ints(1, 2, 3, 4)
            visitInsn(Opcodes.DUP2_X2)
            popMany(6)
            ints(1, 2)
            visitInsn(Opcodes.SWAP)
            popMany(2)
            visitInsn(Opcodes.RETURN)
            visitMaxs(8, 0)
            visitEnd()
        }
    }

    private fun addArrayMethod(writer: ClassWriter) {
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "arrays", "([I)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitInsn(Opcodes.ICONST_0)
            visitIntInsn(Opcodes.BIPUSH, 7)
            visitInsn(Opcodes.IASTORE)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitInsn(Opcodes.ICONST_0)
            visitInsn(Opcodes.IALOAD)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.ICONST_2)
            visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT)
            visitInsn(Opcodes.DUP)
            visitInsn(Opcodes.ICONST_0)
            visitIntInsn(Opcodes.BIPUSH, 7)
            visitInsn(Opcodes.IASTORE)
            visitInsn(Opcodes.DUP)
            visitInsn(Opcodes.ICONST_0)
            visitInsn(Opcodes.IALOAD)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.ICONST_1)
            visitInsn(Opcodes.ICONST_1)
            visitMultiANewArrayInsn("[[Ljava/lang/String;", 2)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(5, 0)
            visitEnd()
        }
    }

    private fun addFieldMethod(writer: ClassWriter) {
        writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "fields",
            "(Lsample/fast/GeneratedFastOpcodes;)V",
            null,
            null
        ).apply {
            visitCode()
            visitIntInsn(Opcodes.BIPUSH, 9)
            visitFieldInsn(Opcodes.PUTSTATIC, "sample/fast/GeneratedFastOpcodes", "staticField", "I")
            visitFieldInsn(Opcodes.GETSTATIC, "sample/fast/GeneratedFastOpcodes", "staticField", "I")
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitIntInsn(Opcodes.SIPUSH, 300)
            visitFieldInsn(Opcodes.PUTFIELD, "sample/fast/GeneratedFastOpcodes", "instanceField", "I")
            visitVarInsn(Opcodes.ALOAD, 1)
            visitFieldInsn(Opcodes.GETFIELD, "sample/fast/GeneratedFastOpcodes", "instanceField", "I")
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(3, 2)
            visitEnd()
        }
    }

    private fun addJumpMethod(writer: ClassWriter) {
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "jumps", "(II)I", null, null).apply {
            visitCode()
            val zero = Label()
            val equal = Label()
            visitVarInsn(Opcodes.ILOAD, 0)
            visitJumpInsn(Opcodes.IFEQ, zero)
            visitVarInsn(Opcodes.ILOAD, 0)
            visitVarInsn(Opcodes.ILOAD, 1)
            visitJumpInsn(Opcodes.IF_ICMPEQ, equal)
            visitVarInsn(Opcodes.ILOAD, 0)
            val defaultLabel = Label()
            val tableOne = Label()
            val tableTwo = Label()
            visitTableSwitchInsn(1, 2, defaultLabel, tableOne, tableTwo)
            visitLabel(tableOne)
            visitInsn(Opcodes.ICONST_1)
            visitInsn(Opcodes.IRETURN)
            visitLabel(tableTwo)
            visitInsn(Opcodes.ICONST_2)
            visitInsn(Opcodes.IRETURN)
            visitLabel(defaultLabel)
            visitVarInsn(Opcodes.ILOAD, 1)
            val lookupSeven = Label()
            visitLookupSwitchInsn(defaultLabel, intArrayOf(7), arrayOf(lookupSeven))
            visitLabel(lookupSeven)
            visitIntInsn(Opcodes.BIPUSH, 7)
            visitInsn(Opcodes.IRETURN)
            visitLabel(equal)
            visitInsn(Opcodes.ICONST_3)
            visitInsn(Opcodes.IRETURN)
            visitLabel(zero)
            visitInsn(Opcodes.ICONST_0)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(2, 2)
            visitEnd()
        }
    }

    private fun addTypeMethod(writer: ClassWriter) {
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "types", "()V", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/lang/Object")
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Object")
            visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/Object")
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.ICONST_1)
            visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
            visitInsn(Opcodes.ARRAYLENGTH)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(3, 0)
            visitEnd()
        }
    }

    private fun MethodVisitor.ints(vararg values: Int) {
        values.forEach {
            visitInsn(Opcodes.ICONST_0 + it)
        }
    }

    private fun MethodVisitor.popMany(count: Int) {
        repeat(count) {
            visitInsn(Opcodes.POP)
        }
    }
}
