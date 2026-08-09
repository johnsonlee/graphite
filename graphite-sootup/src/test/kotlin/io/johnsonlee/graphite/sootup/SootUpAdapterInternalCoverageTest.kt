@file:Suppress("FunctionOnlyReturningConstant", "LargeClass", "LongMethod", "MaxLineLength")

package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.input.EmptyResourceAccessor
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.ResourceEntry
import java.nio.file.Path
import java.util.Locale
import java.util.ResourceBundle
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.NodeId
import java.io.ByteArrayInputStream
import java.util.stream.Stream
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
import sootup.core.frontend.SootClassSource
import sootup.core.jimple.basic.NoPositionInformation
import sootup.core.jimple.basic.StmtPositionInfo
import sootup.core.jimple.common.constant.MethodHandle
import sootup.core.jimple.common.constant.StringConstant as SootStringConstant
import sootup.core.jimple.common.expr.JDynamicInvokeExpr
import sootup.core.jimple.common.expr.JNewExpr
import sootup.core.jimple.common.expr.JSpecialInvokeExpr
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.jimple.common.expr.JVirtualInvokeExpr
import sootup.core.jimple.common.ref.JStaticFieldRef
import sootup.core.jimple.common.stmt.JAssignStmt
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
import sootup.core.types.VoidType
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation
import sootup.java.core.JavaIdentifierFactory
import sootup.java.core.JavaSootClass
import sootup.java.core.jimple.basic.JavaLocal
import sootup.java.core.views.JavaView
import sootup.core.views.View

class SootUpAdapterInternalCoverageTest {
    private val identifierFactory = JavaIdentifierFactory.getInstance()

    @Test
    fun `literal helpers resolve locale and control constants`() {
        val adapter = createAdapter()

        assertEquals(listOf("java.class"), invokePrivate<Any?>(adapter, "resolveStaticLiteral", arrayOf(String::class.java, String::class.java), "java.util.ResourceBundle\$Control", "FORMAT_CLASS"))
        assertEquals(listOf("java.properties"), invokePrivate<Any?>(adapter, "resolveStaticLiteral", arrayOf(String::class.java, String::class.java), "java.util.ResourceBundle\$Control", "FORMAT_PROPERTIES"))
        assertEquals(listOf("java.class", "java.properties"), invokePrivate<Any?>(adapter, "resolveStaticLiteral", arrayOf(String::class.java, String::class.java), "java.util.ResourceBundle\$Control", "FORMAT_DEFAULT"))
        assertEquals("ko_KR", invokePrivate<String?>(adapter, "resolveStaticLiteral", arrayOf(String::class.java, String::class.java), "java.util.Locale", "KOREA"))
        assertEquals("", invokePrivate<String?>(adapter, "resolveStaticLiteral", arrayOf(String::class.java, String::class.java), "java.util.Locale", "ROOT"))

        assertEquals("zh_CN", invokePrivate<String?>(adapter, "extractLocaleSpec", arrayOf(String::class.java), "SIMPLIFIED_CHINESE"))
        assertEquals("zh_TW", invokePrivate<String?>(adapter, "extractLocaleSpec", arrayOf(String::class.java), "TRADITIONAL_CHINESE"))
        assertEquals("fr_CA", invokePrivate<String?>(adapter, "extractLocaleSpec", arrayOf(String::class.java), "CANADA_FRENCH"))
        assertEquals("en", invokePrivate<String?>(adapter, "extractLocaleSpec", arrayOf(String::class.java), "ENGLISH"))
        assertEquals("en_US", invokePrivate<String?>(adapter, "extractLocaleSpec", arrayOf(String::class.java), "US"))
        assertEquals("en_GB", invokePrivate<String?>(adapter, "extractLocaleSpec", arrayOf(String::class.java), "UK"))
        assertEquals("en_CA", invokePrivate<String?>(adapter, "extractLocaleSpec", arrayOf(String::class.java), "CANADA"))
        assertEquals("fr", invokePrivate<String?>(adapter, "extractLocaleSpec", arrayOf(String::class.java), "FRENCH"))
        assertEquals("fr_FR", invokePrivate<String?>(adapter, "extractLocaleSpec", arrayOf(String::class.java), "FRANCE"))
        assertEquals("de", invokePrivate<String?>(adapter, "extractLocaleSpec", arrayOf(String::class.java), "GERMAN"))
        assertEquals("de_DE", invokePrivate<String?>(adapter, "extractLocaleSpec", arrayOf(String::class.java), "GERMANY"))
        assertEquals("it", invokePrivate<String?>(adapter, "extractLocaleSpec", arrayOf(String::class.java), "ITALIAN"))
        assertEquals("it_IT", invokePrivate<String?>(adapter, "extractLocaleSpec", arrayOf(String::class.java), "ITALY"))
        assertEquals("ja", invokePrivate<String?>(adapter, "extractLocaleSpec", arrayOf(String::class.java), "JAPANESE"))
        assertEquals("ja_JP", invokePrivate<String?>(adapter, "extractLocaleSpec", arrayOf(String::class.java), "JAPAN"))
        assertEquals("ko", invokePrivate<String?>(adapter, "extractLocaleSpec", arrayOf(String::class.java), "KOREAN"))
        assertEquals("zh", invokePrivate<String?>(adapter, "extractLocaleSpec", arrayOf(String::class.java), "CHINESE"))
        assertNull(invokePrivate<String?>(adapter, "extractLocaleSpec", arrayOf(String::class.java), "UNKNOWN"))

        assertEquals(setOf("java.class"), invokePrivate<Set<String>>(adapter, "controlFormats", arrayOf(String::class.java), "java.class"))
        assertEquals(setOf("java.properties"), invokePrivate<Set<String>>(adapter, "controlFormats", arrayOf(String::class.java), "java.properties"))
        assertEquals(setOf("java.properties", "java.class"), invokePrivate<Set<String>>(adapter, "controlFormats", arrayOf(String::class.java), null))

        assertEquals(Locale("ko", "KR"), invokePrivate<Locale>(adapter, "toLocale", arrayOf(String::class.java), "ko_KR"))
        assertEquals(Locale("ko"), invokePrivate<Locale>(adapter, "toLocale", arrayOf(String::class.java), "ko"))
        assertEquals(Locale.ROOT, invokePrivate<Locale>(adapter, "toLocale", arrayOf(String::class.java), ""))
        assertEquals("en_US_POSIX", invokePrivate<String>(adapter, "localeSpecOf", arrayOf(Locale::class.java), Locale("en", "US", "POSIX")))
        assertEquals("", invokePrivate<String>(adapter, "localeSpecOf", arrayOf(Locale::class.java), Locale.ROOT))

        assertTrue(invokePrivate<Boolean>(adapter, "matchesBundleClassPath", arrayOf(String::class.java, String::class.java), "sample.resources.MessagesListBundle_ko_KR", "sample.resources.MessagesListBundle"))
        assertFalse(invokePrivate<Boolean>(adapter, "matchesBundleClassPath", arrayOf(String::class.java, String::class.java), "other.Bundle", "sample.resources.MessagesListBundle"))

        val caller = MethodDescriptor(TypeDescriptor("sample.resources.ResourceConfig"), "lookup", emptyList(), TypeDescriptor("java.lang.String"))
        assertEquals("application.properties", invokePrivate<String>(adapter, "normalizeResourcePath", arrayOf(MethodDescriptor::class.java, String::class.java, String::class.java), caller, "java.lang.ClassLoader", "/application.properties"))
        assertEquals("application.properties", invokePrivate<String>(adapter, "normalizeResourcePath", arrayOf(MethodDescriptor::class.java, String::class.java, String::class.java), caller, "java.lang.Class", "/application.properties"))
        assertEquals("sample/resources/messages.properties", invokePrivate<String>(adapter, "normalizeResourcePath", arrayOf(MethodDescriptor::class.java, String::class.java, String::class.java), caller, "java.lang.Class", "messages.properties"))
    }

    @Test
    fun `locale builder spec and enum fallback branches are covered`() {
        val adapter = createAdapter(includePackages = listOf("sample.simple"))
        val specClass = Class.forName("io.johnsonlee.graphite.sootup.SootUpAdapter\$LocaleBuilderSpec")
        val constructor = specClass.getDeclaredConstructor(
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java
        ).also { it.isAccessible = true }
        val toLocaleSpec = specClass.getDeclaredMethod("toLocaleSpec").also { it.isAccessible = true }

        assertEquals("en-US", toLocaleSpec.invoke(constructor.newInstance(null, null, null, "en-US")))
        assertEquals("en_US_POSIX", toLocaleSpec.invoke(constructor.newInstance("en", "US", "POSIX", "")))
        assertNull(toLocaleSpec.invoke(constructor.newInstance(null, null, null, "")))

        invokePrivate<Unit>(
            adapter,
            "extractEnumValues",
            arrayOf(sootup.core.model.SootClass::class.java),
            resolveJavaSootClass(adapter, "sample.simple.SimpleService")
        )
    }

    @Test
    fun `annotation normalization helpers cover empty and collection values`() {
        val adapter = createAdapter()

        assertNull(invokePrivate<Any?>(adapter, "normalizeAnnotationValue", arrayOf(Any::class.java), null))
        assertNull(invokePrivate<Any?>(adapter, "normalizeAnnotationValue", arrayOf(Any::class.java), "\"\""))
        assertEquals("alpha", invokePrivate<Any?>(adapter, "normalizeAnnotationValue", arrayOf(Any::class.java), "\"alpha\""))
        assertEquals(listOf("a"), invokePrivate<Any?>(adapter, "normalizeAnnotationValue", arrayOf(Any::class.java), listOf("a", "")))
        assertEquals(listOf("b"), invokePrivate<Any?>(adapter, "normalizeAnnotationValue", arrayOf(Any::class.java), arrayOf("b", "")))
        assertEquals(1, invokePrivate<Any?>(adapter, "normalizeAnnotationValue", arrayOf(Any::class.java), 1))
        assertNull(invokePrivate<Any?>(adapter, "normalizeAnnotationValue", arrayOf(Any::class.java), emptyList<String>()))
        assertNull(invokePrivate<Any?>(adapter, "normalizeAnnotationValue", arrayOf(Any::class.java), emptyArray<String>()))
        assertNull(invokePrivate<Any?>(adapter, "normalizeAnnotationValue", arrayOf(Any::class.java), object {
            override fun toString(): String = "null"
        }))
        assertEquals("", invokePrivate<String>(adapter, "getAnnotationFullName", arrayOf(Any::class.java), null))
        assertTrue(invokePrivate<Map<String, Any?>>(adapter, "getAnnotationValues", arrayOf(Any::class.java), null).isEmpty())
        assertEquals("sample.Annotation", invokePrivate<String>(adapter, "getAnnotationFullName", arrayOf(Any::class.java), FakeAnnotationUsage()))
        assertEquals(mapOf("value" to "\"ok\""), invokePrivate<Map<String, Any?>>(adapter, "getAnnotationValues", arrayOf(Any::class.java), FakeAnnotationUsage()))
    }

    @Test
    fun `artifact and class resource helpers normalize boundary cases`() {
        val adapter = createAdapter()

        assertEquals("dependency", invokePrivate<String?>(adapter, "artifactKey", arrayOf(String::class.java), "/opt/app/lib/dependency.jar"))
        assertNull(invokePrivate<String?>(adapter, "artifactKey", arrayOf(String::class.java), " / "))
        assertEquals("sample.resources.Messages", invokePrivate<String?>(adapter, "classResourcePathToName", arrayOf(String::class.java), "sample/resources/Messages.class"))
        assertNull(invokePrivate<String?>(adapter, "classResourcePathToName", arrayOf(String::class.java), "sample/resources/Messages.txt"))
        assertNull(invokePrivate<String?>(adapter, "classResourcePathToName", arrayOf(String::class.java), "sample/module-info.class"))
        assertEquals(
            TypeDescriptor("fallback.Type"),
            invokePrivate<TypeDescriptor>(
                adapter,
                "getFieldTypeWithGenerics",
                arrayOf(String::class.java, String::class.java, TypeDescriptor::class.java),
                "missing.Owner",
                "field",
                TypeDescriptor("fallback.Type")
            )
        )

        val method = MethodDescriptor(TypeDescriptor("sample.Owner"), "read", emptyList(), TypeDescriptor("void"))
        val otherMethod = MethodDescriptor(TypeDescriptor("sample.Owner"), "read", emptyList(), TypeDescriptor("void"))
        val bindingClass = Class.forName("io.johnsonlee.graphite.sootup.SootUpAdapter\$ParameterBinding")
        val bindingConstructor = bindingClass.getDeclaredConstructor(MethodDescriptor::class.java, Int::class.javaPrimitiveType!!)
            .also { it.isAccessible = true }
        val firstBinding = bindingConstructor.newInstance(method, 0)

        assertEquals(firstBinding, bindingConstructor.newInstance(method, 0))
        assertFalse(firstBinding == bindingConstructor.newInstance(otherMethod, 0))
        assertFalse(firstBinding == bindingConstructor.newInstance(method, 1))
        assertFalse(firstBinding == "not-a-binding")

        assertNotNull(
            invokePrivate<Any>(
                adapter,
                "parameterBinding",
                arrayOf(MethodDescriptor::class.java, Int::class.javaPrimitiveType!!),
                method,
                1
            )
        )
        writeField(adapter, "activeMethod", method)
        val activeBinding = invokePrivate<Any>(
            adapter,
            "parameterBinding",
            arrayOf(MethodDescriptor::class.java, Int::class.javaPrimitiveType!!),
            method,
            0
        )
        val activeBindingAgain = invokePrivate<Any>(
            adapter,
            "parameterBinding",
            arrayOf(MethodDescriptor::class.java, Int::class.javaPrimitiveType!!),
            method,
            0
        )
        assertEquals(activeBinding, activeBindingAgain)
        assertEquals(1, readField<MutableList<Any>>(adapter, "activeMethodParameters").size)
    }

    @Test
    fun `bytecode reference extraction includes invokedynamic bootstrap references`() {
        val adapter = createAdapter(includePackages = listOf("sample.lambda"))
        val references = invokePrivate<Set<String>>(
            adapter,
            "extractReferencedClasses",
            arrayOf(ByteArray::class.java),
            findCompiledClassBytes("sample/lambda/LambdaExample.class")
        )

        assertTrue(references.any { it == "java.lang.invoke.LambdaMetafactory" || it == "sample.lambda.LambdaExample" })

        val descriptorReferences = invokePrivate<Set<String>>(
            adapter,
            "extractReferencedClasses",
            arrayOf(ByteArray::class.java),
            referencedClassBytes()
        )
        assertTrue(descriptorReferences.contains("java.io.Serializable"))
        assertTrue(descriptorReferences.contains("java.util.List"))
        assertTrue(descriptorReferences.contains("java.util.Map"))
        assertTrue(descriptorReferences.contains("java.util.Optional"))
        assertTrue(descriptorReferences.contains("java.io.IOException"))
    }

    @Test
    fun `resource indexing helpers record class resources profiles and formats`() {
        val entries = listOf(
            ResourceEntry("com/example/Helper.class", "lib/helper.jar"),
            ResourceEntry("config/application-dev.yaml", "app.jar"),
            ResourceEntry("config/feature.json", "app.jar"),
            ResourceEntry("config/service.xml", "app.jar"),
            ResourceEntry("notes.txt", "app.jar")
        )
        val adapter = createAdapter(resourceAccessor = object : ResourceAccessor {
            override fun list(pattern: String): Sequence<ResourceEntry> = entries.asSequence()
            override fun open(path: String) = ByteArrayInputStream(ByteArray(0))
        })

        val unloadedClasses = invokePrivate<List<ResourceEntry>>(
            adapter,
            "indexResourceValues",
            arrayOf(Set::class.java),
            setOf("app.jar")
        )
        val resourceFilesByPath = readField<MutableMap<String, MutableList<ResourceFileNode>>>(adapter, "resourceFilesByPath")
        val configurationResourcePaths = readField<LinkedHashSet<String>>(adapter, "configurationResourcePaths")
        val classOrigins = readField<MutableMap<String, String>>(adapter, "classOriginsByName")

        assertEquals(listOf(ResourceEntry("com/example/Helper.class", "lib/helper.jar")), unloadedClasses)
        assertEquals("lib/helper.jar", classOrigins["com.example.Helper"])
        assertEquals("yaml", resourceFilesByPath.getValue("config/application-dev.yaml").single().format)
        assertEquals("dev", resourceFilesByPath.getValue("config/application-dev.yaml").single().profile)
        assertEquals("json", resourceFilesByPath.getValue("config/feature.json").single().format)
        assertEquals("xml", resourceFilesByPath.getValue("config/service.xml").single().format)
        assertEquals("text", resourceFilesByPath.getValue("notes.txt").single().format)
        assertTrue(configurationResourcePaths.contains("config/application-dev.yaml"))
        assertTrue(configurationResourcePaths.contains("config/feature.json"))
        assertTrue(configurationResourcePaths.contains("config/service.xml"))
        assertFalse(configurationResourcePaths.contains("notes.txt"))
        assertTrue(invokePrivate<Boolean>(adapter, "isResourceConfig", arrayOf(String::class.java), "application.yml"))
        assertTrue(invokePrivate<Boolean>(adapter, "isResourceConfig", arrayOf(String::class.java), "application.yaml"))
        assertEquals("local", invokePrivate<String?>(adapter, "resourceProfile", arrayOf(String::class.java), "config/application-local.json"))
        assertNull(invokePrivate<String?>(adapter, "resourceProfile", arrayOf(String::class.java), "config/feature.json"))
    }

    @Test
    fun `method resolution fallbacks handle missing bodies and failing classes`() {
        val adapter = createAdapter()
        val enumType = identifierFactory.getClassType("sample.fake.EmptyEnum")
        val clinitSignature = identifierFactory.getMethodSignature("sample.fake.EmptyEnum", "<clinit>", "void", emptyList())
        val clinit = object : FakeSootMethod(clinitSignature, listOf(MethodModifier.STATIC)) {
            override fun hasBody(): Boolean = false
        }
        val enumClass = FakeSootClass(enumType, methods = setOf(clinit), enumClass = true)

        invokePrivate<Unit>(adapter, "extractEnumValues", arrayOf(SootClass::class.java), enumClass)

        assertTrue(
            invokePrivate<Set<SootMethod>>(
                adapter,
                "resolveMethodsOrEmpty",
                arrayOf(SootClass::class.java),
                FakeSootClass(identifierFactory.getClassType("sample.fake.Oom"), failure = OutOfMemoryError("boom"))
            ).isEmpty()
        )
        assertTrue(
            invokePrivate<Set<SootMethod>>(
                adapter,
                "resolveMethodsOrEmpty",
                arrayOf(SootClass::class.java),
                FakeSootClass(identifierFactory.getClassType("sample.fake.Illegal"), failure = IllegalStateException("missing"))
            ).isEmpty()
        )

        writeField(adapter, "classesByNameCache", mapOf("sample.fake.EmptyEnum" to enumClass))
        assertTrue(
            invokePrivate<Set<String>>(
                adapter,
                "collectDeclaredMethodSubSignatures",
                arrayOf(String::class.java),
                "sample.fake.EmptyEnum"
            ).any { it.contains("<clinit>") }
        )

        val throwingMethod = object : FakeSootMethod(
            identifierFactory.getMethodSignature("sample.fake.Owner", "boom", "void", emptyList())
        ) {
            override fun hasBody(): Boolean = throw OutOfMemoryError("boom")
        }
        invokePrivate<Unit>(adapter, "processMethod", arrayOf(SootMethod::class.java), throwingMethod)
    }

    @Test
    fun `build graph reports progress for large class sets`() {
        val logs = mutableListOf<String>()
        val classes = (1..500).map {
            FakeSootClass(identifierFactory.getClassType("sample.fake.Progress$it"))
        }
        val adapter = SootUpAdapter(
            view = FakeView(classes, identifierFactory),
            config = LoaderConfig(includePackages = listOf("sample.fake"), buildCallGraph = false, verbose = logs::add),
            extensions = emptyList(),
            resourceAccessor = EmptyResourceAccessor,
            inputLocationSources = emptyMap(),
            graphBuilder = DefaultGraph.Builder()
        )

        adapter.buildGraph()

        assertTrue(logs.any { it.contains("Pass 1 processed 500 classes") })
        assertTrue(logs.any { it.contains("Pass 2 processed 500 classes") })
    }

    @Test
    fun `compiled resource bundle helpers inspect bytecode and control specs`() {
        val adapter = createAdapter()
        val resourceBackedAdapter = createResourceBackedAdapter()
        val classOnlyControl = resolveJavaSootClass(adapter, "sample.resources.ClassOnlyControl")
        val koreanOnlyControl = resolveJavaSootClass(adapter, "sample.resources.KoreanOnlyControl")
        val listBundle = resolveJavaSootClass(adapter, "sample.resources.MessagesListBundle_ko_KR")

        val directLoadedMethods = invokePrivate<List<MethodNode>?>(resourceBackedAdapter, "loadMethodNodesFromResource", arrayOf(JavaSootClass::class.java), resolveJavaSootClass(resourceBackedAdapter, "sample.resources.MessagesListBundle_ko_KR"))
        val controlMethods = invokePrivate<List<MethodNode>?>(adapter, "getAsmMethodNodes", arrayOf(JavaSootClass::class.java), classOnlyControl)
            ?: invokePrivate<List<MethodNode>?>(adapter, "loadMethodNodesFromResource", arrayOf(JavaSootClass::class.java), classOnlyControl)
        val candidateMethods = invokePrivate<List<MethodNode>?>(adapter, "getAsmMethodNodes", arrayOf(JavaSootClass::class.java), koreanOnlyControl)
            ?: invokePrivate<List<MethodNode>?>(adapter, "loadMethodNodesFromResource", arrayOf(JavaSootClass::class.java), koreanOnlyControl)
        assertNotNull(directLoadedMethods)
        assertTrue(directLoadedMethods.isNotEmpty())
        assertNotNull(controlMethods)
        assertNotNull(candidateMethods)
        val streamedMethods = invokePrivate<Sequence<*>>(resourceBackedAdapter, "streamMethodsOrNull", arrayOf(sootup.core.model.SootClass::class.java), resolveJavaSootClass(resourceBackedAdapter, "sample.resources.MessagesListBundle_ko_KR"))
        assertNotNull(streamedMethods)
        assertTrue(streamedMethods.any())

        val getFormats = controlMethods.first { it.name == "getFormats" }
        val getCandidateLocales = candidateMethods.first { it.name == "getCandidateLocales" }
        assertEquals(setOf("java.class"), invokePrivate<Set<String>?>(adapter, "extractControlFormatsFromMethod", arrayOf(MethodNode::class.java), getFormats))
        assertEquals(listOf("ko_KR"), invokePrivate<List<String>?>(adapter, "extractCandidateLocalesFromMethod", arrayOf(MethodNode::class.java), getCandidateLocales))
        assertTrue(invokePrivate<Boolean>(adapter, "returnsNullLiteral", arrayOf(MethodNode::class.java), nullReturnMethod()))

        val classOnlySpec = invokePrivate<Any?>(adapter, "resolveBundleControlSpec", arrayOf(String::class.java), "sample.resources.ClassOnlyControl")
        val koreanOnlySpec = invokePrivate<Any?>(adapter, "resolveBundleControlSpec", arrayOf(String::class.java), "sample.resources.KoreanOnlyControl")
        assertNotNull(classOnlySpec)
        assertNotNull(koreanOnlySpec)
        assertEquals(setOf("java.class"), readProperty<Set<String>>(classOnlySpec, "formats"))
        assertEquals(listOf("ko_KR"), readProperty<List<String>?>(koreanOnlySpec, "candidateLocales"))
        val reflectedControl = invokePrivate<Any?>(
            adapter,
            "reflectBundleControlSpec",
            arrayOf(String::class.java, String::class.java, String::class.java),
            "sample.resources.KoreanOnlyControl",
            "sample.resources.MessagesListBundle",
            "ko_KR"
        )
        assertNotNull(reflectedControl)
        assertEquals(listOf("ko_KR"), readProperty<List<String>?>(reflectedControl, "candidateLocales"))
        assertNull(
            invokePrivate<Any?>(
                adapter,
                "reflectBundleControlSpec",
                arrayOf(String::class.java, String::class.java, String::class.java),
                "java.lang.String",
                "sample.resources.MessagesListBundle",
                null
            )
        )
        assertNull(
            invokePrivate<Any?>(
                adapter,
                "reflectBundleControlSpec",
                arrayOf(String::class.java, String::class.java, String::class.java),
                "missing.Control",
                "sample.resources.MessagesListBundle",
                null
            )
        )
        val resourceFilesByPath = readField<MutableMap<String, MutableList<ResourceFileNode>>>(adapter, "resourceFilesByPath")
        resourceFilesByPath["messages.properties"] = mutableListOf(ResourceFileNode(NodeId.next(), "messages.properties", "test", "properties", null))
        resourceFilesByPath["messages_ko.properties"] = mutableListOf(ResourceFileNode(NodeId.next(), "messages_ko.properties", "test", "properties", null))
        resourceFilesByPath["messages_ko_KR.properties"] = mutableListOf(ResourceFileNode(NodeId.next(), "messages_ko_KR.properties", "test", "properties", null))
        resourceFilesByPath["sample.resources.MessagesListBundle_ko_KR"] = mutableListOf(ResourceFileNode(NodeId.next(), "sample.resources.MessagesListBundle_ko_KR", "test", "listbundle", null))
        assertEquals(
            linkedSetOf("messages_ko_KR.properties", "messages_ko.properties", "messages.properties"),
            invokePrivate<LinkedHashSet<String>>(adapter, "collectMatchingResourceBundlePaths", arrayOf(String::class.java, String::class.java, classOnlySpec.javaClass), "messages", "messages", null)
        )
        assertEquals(
            linkedSetOf("sample.resources.MessagesListBundle_ko_KR"),
            invokePrivate<LinkedHashSet<String>>(adapter, "collectMatchingResourceBundlePaths", arrayOf(String::class.java, String::class.java, classOnlySpec.javaClass), "sample.resources.MessagesListBundle", "sample/resources/MessagesListBundle", classOnlySpec)
        )

        val methods = invokePrivate<Set<*>>(adapter, "resolveMethodsOrEmpty", arrayOf(sootup.core.model.SootClass::class.java), listBundle)
        assertTrue(methods.isNotEmpty())
    }

    @Test
    fun `runtime bundle indexing records localized parent chain and literal evaluation`() {
        val adapter = createAdapter()

        assertEquals(
            listOf("java.class", "java.properties"),
            invokePrivate<Any?>(adapter, "evaluateLiteralMethod", arrayOf(MethodNode::class.java), listOfMethod())
        )
        assertEquals(
            listOf("java.properties"),
            invokePrivate<Any?>(adapter, "evaluateLiteralMethod", arrayOf(MethodNode::class.java), singletonListMethod())
        )
        assertEquals(
            "ko_KR",
            invokePrivate<Any?>(adapter, "evaluateLiteralMethod", arrayOf(MethodNode::class.java), languageTagMethod())
        )
        assertEquals(42, invokePrivate<Any?>(adapter, "evaluateLiteralMethod", arrayOf(MethodNode::class.java), intPushMethod(Opcodes.BIPUSH, 42)))
        assertEquals(1024, invokePrivate<Any?>(adapter, "evaluateLiteralMethod", arrayOf(MethodNode::class.java), intPushMethod(Opcodes.SIPUSH, 1024)))
        assertEquals(-1, invokePrivate<Any?>(adapter, "evaluateLiteralMethod", arrayOf(MethodNode::class.java), iconstReturnMethod(Opcodes.ICONST_M1)))
        assertEquals(5, invokePrivate<Any?>(adapter, "evaluateLiteralMethod", arrayOf(MethodNode::class.java), iconstReturnMethod(Opcodes.ICONST_5)))
        assertNull(invokePrivate<Any?>(adapter, "evaluateLiteralMethod", arrayOf(MethodNode::class.java), localeCtorMethod()))
        assertEquals(listOf("java.class", "java.properties"), invokePrivate<Any?>(adapter, "evaluateLiteralMethod", arrayOf(MethodNode::class.java), arraysAsListMethod()))
        assertEquals("stored", invokePrivate<Any?>(adapter, "evaluateLiteralMethod", arrayOf(MethodNode::class.java), storedStringMethod()))
        assertEquals(setOf("java.class"), invokePrivate<Set<String>?>(adapter, "extractControlFormatsFromMethod", arrayOf(MethodNode::class.java), singleFormatMethod("java.class")))
        assertEquals(setOf("java.properties"), invokePrivate<Set<String>?>(adapter, "extractControlFormatsFromMethod", arrayOf(MethodNode::class.java), singleFormatMethod("java.properties")))
        assertNull(invokePrivate<Set<String>?>(adapter, "extractControlFormatsFromMethod", arrayOf(MethodNode::class.java), singleIntListOfMethod(7)))
        assertNull(invokePrivate<Set<String>?>(adapter, "extractControlFormatsFromMethod", arrayOf(MethodNode::class.java), singleFormatMethod("ignored")))

        val bundle = ResourceBundle.getBundle("sample.resources.MessagesListBundle", Locale.KOREA)
        invokePrivate<Unit>(adapter, "indexRuntimeBundle", arrayOf(ResourceBundle::class.java), bundle)
        invokePrivate<Unit>(
            adapter,
            "indexRuntimeBundle",
            arrayOf(ResourceBundle::class.java),
            java.util.PropertyResourceBundle(ByteArrayInputStream("hello=world\n".toByteArray()))
        )
        val indexed = readField<MutableMap<String, MutableList<ResourceFileNode>>>(adapter, "resourceFilesByPath")
        assertTrue(indexed.containsKey("sample.resources.MessagesListBundle_ko_KR"))
        assertEquals("propertybundle", indexed.getValue("java.util.PropertyResourceBundle").single().format)
        val parent = SimpleBundle("parent")
        val child = SimpleBundle("child").withParent(parent)
        assertNull(invokePrivate<ResourceBundle?>(adapter, "bundleParent", arrayOf(ResourceBundle::class.java), child))

        indexed["messages_ko_KR.properties"] = mutableListOf(ResourceFileNode(NodeId.next(), "messages_ko_KR.properties", "test", "properties", null))
        indexed["messages_ko.properties"] = mutableListOf(ResourceFileNode(NodeId.next(), "messages_ko.properties", "test", "properties", null))
        val controlSpecClass = Class.forName("io.johnsonlee.graphite.sootup.SootUpAdapter\$BundleControlSpec")
        assertEquals(
            linkedSetOf("messages_ko_KR.properties", "messages_ko.properties", "messages.properties", "messages"),
            invokePrivate<LinkedHashSet<String>>(
                adapter,
                "buildResourceBundleCandidatePaths",
                arrayOf(String::class.java, String::class.java, String::class.java, controlSpecClass),
                "messages",
                "messages",
                "ko_KR",
                null
            )
        )
    }

    @Test
    fun `control and string extraction resolve locals static fields and bundle classes`() {
        val adapter = createAdapter(listOf("sample.resources", "sample.resolution"))
        val caller = MethodDescriptor(TypeDescriptor("sample.resources.ResourceConfig"), "lookup", emptyList(), TypeDescriptor("java.lang.String"))
        val controlLocal = JavaLocal("control", identifierFactory.getType("java.lang.String"), emptyList())
        val textLocal = JavaLocal("text", identifierFactory.getType("java.lang.String"), emptyList())
        val wrongOwnerRef = JStaticFieldRef(identifierFactory.getFieldSignature("FORMAT_CLASS", identifierFactory.getClassType("java.lang.String"), identifierFactory.getType("java.lang.Object")))
        val classFormatRef = JStaticFieldRef(identifierFactory.getFieldSignature("FORMAT_CLASS", identifierFactory.getClassType("java.util.ResourceBundle\$Control"), identifierFactory.getType("java.lang.Object")))
        val propertiesFormatRef = JStaticFieldRef(identifierFactory.getFieldSignature("FORMAT_PROPERTIES", identifierFactory.getClassType("java.util.ResourceBundle\$Control"), identifierFactory.getType("java.lang.Object")))
        val defaultFormatRef = JStaticFieldRef(identifierFactory.getFieldSignature("FORMAT_DEFAULT", identifierFactory.getClassType("java.util.ResourceBundle\$Control"), identifierFactory.getType("java.lang.Object")))

        readField<MutableMap<Any, String>>(adapter, "bundleControlFormatsByLocal")[
            invokePrivate(adapter, "localKey", arrayOf(MethodDescriptor::class.java, String::class.java), caller, controlLocal.name)
        ] = "java.class"
        readField<MutableMap<Any, String>>(adapter, "stringValuesByLocal")[
            invokePrivate(adapter, "localKey", arrayOf(MethodDescriptor::class.java, String::class.java), caller, textLocal.name)
        ] = "hello"

        assertEquals("java.class", invokePrivate<String?>(adapter, "extractControlFormat", arrayOf(MethodDescriptor::class.java, sootup.core.jimple.basic.Value::class.java), caller, controlLocal))
        assertEquals("java.class", invokePrivate<String?>(adapter, "extractControlFormat", arrayOf(MethodDescriptor::class.java, sootup.core.jimple.basic.Value::class.java), caller, classFormatRef))
        assertEquals("java.properties", invokePrivate<String?>(adapter, "extractControlFormat", arrayOf(MethodDescriptor::class.java, sootup.core.jimple.basic.Value::class.java), caller, propertiesFormatRef))
        assertNull(invokePrivate<String?>(adapter, "extractControlFormat", arrayOf(MethodDescriptor::class.java, sootup.core.jimple.basic.Value::class.java), caller, defaultFormatRef))
        assertNull(invokePrivate<String?>(adapter, "extractControlFormat", arrayOf(MethodDescriptor::class.java, sootup.core.jimple.basic.Value::class.java), caller, wrongOwnerRef))
        assertNull(invokePrivate<String?>(adapter, "extractControlFormat", arrayOf(MethodDescriptor::class.java, sootup.core.jimple.basic.Value::class.java), null, controlLocal))

        assertEquals("direct", invokePrivate<String?>(adapter, "extractStringValue", arrayOf(MethodDescriptor::class.java, sootup.core.jimple.basic.Value::class.java), caller, SootStringConstant("direct", identifierFactory.getType("java.lang.String"))))
        assertEquals("hello", invokePrivate<String?>(adapter, "extractStringValue", arrayOf(MethodDescriptor::class.java, sootup.core.jimple.basic.Value::class.java), caller, textLocal))
        assertNull(invokePrivate<String?>(adapter, "extractStringValue", arrayOf(MethodDescriptor::class.java, sootup.core.jimple.basic.Value::class.java), caller, null))

        assertTrue(invokePrivate(adapter, "isResourceBundleControlClass", arrayOf(sootup.core.model.SootClass::class.java), resolveJavaSootClass(adapter, "sample.resources.ClassOnlyControl")))
        assertFalse(invokePrivate(adapter, "isResourceBundleControlClass", arrayOf(sootup.core.model.SootClass::class.java), resolveJavaSootClass(adapter, "sample.resources.MessagesListBundle")))
    }

    @Test
    fun `dynamic invoke helpers resolve locals fields fallback and defining classes`() {
        val adapter = createAdapter(listOf("sample.resources", "sample.resolution", "sample.lambda"), buildCallGraph = true)
        val caller = MethodDescriptor(TypeDescriptor("sample.lambda.LambdaExample"), "useFactory", emptyList(), TypeDescriptor("java.lang.String"))
        val argLocal = JavaLocal("input", identifierFactory.getType("java.lang.String"), emptyList())
        val targetSig = identifierFactory.getMethodSignature("sample.lambda.LambdaExample", "transform", "java.lang.String", listOf("java.lang.String"))
        val bootstrapSig = identifierFactory.getMethodSignature("java.lang.invoke.LambdaMetafactory", "metafactory", "java.lang.Object", emptyList())
        val applySig = identifierFactory.getMethodSignature(JDynamicInvokeExpr.INVOKEDYNAMIC_DUMMY_CLASS_NAME, "apply", "java.lang.Object", listOf("java.lang.Object"))
        val methodHandle = MethodHandle(targetSig, MethodHandle.Kind.REF_INVOKE_STATIC, identifierFactory.getType("java.lang.String"))

        val localResult = LocalVariable(NodeId.next(), "fn", TypeDescriptor("java.util.function.Function"), caller)
        invokePrivate<Unit>(
            adapter,
            "processDynamicInvoke",
            arrayOf(JDynamicInvokeExpr::class.java, MethodDescriptor::class.java, io.johnsonlee.graphite.core.ValueNode::class.java, sootup.core.jimple.common.stmt.Stmt::class.java),
            JDynamicInvokeExpr(bootstrapSig, listOf(methodHandle), applySig, listOf(argLocal)),
            caller,
            localResult,
            null
        )
        val dynamicTargets = readField<MutableMap<Any, List<MethodDescriptor>>>(adapter, "dynamicTargets")
        val resultKey = invokePrivate<Any>(adapter, "localKey", arrayOf(MethodDescriptor::class.java, String::class.java), caller, "fn")
        assertEquals(listOf(invokePrivate<MethodDescriptor>(adapter, "toMethodDescriptor", arrayOf(MethodSignature::class.java), targetSig)), dynamicTargets[resultKey])

        val fieldSig = identifierFactory.getFieldSignature("callback", identifierFactory.getClassType("sample.lambda.FieldCallbackExample"), identifierFactory.getType("java.lang.Object"))
        val fieldNode = FieldNode(NodeId.next(), FieldDescriptor(TypeDescriptor("sample.lambda.FieldCallbackExample"), "callback", TypeDescriptor("java.lang.Object")), true)
        invokePrivate<Unit>(
            adapter,
            "processDynamicInvoke",
            arrayOf(JDynamicInvokeExpr::class.java, MethodDescriptor::class.java, io.johnsonlee.graphite.core.ValueNode::class.java, sootup.core.jimple.common.stmt.Stmt::class.java),
            JDynamicInvokeExpr(bootstrapSig, listOf(methodHandle), applySig, listOf(argLocal)),
            caller,
            fieldNode,
            JAssignStmt(JStaticFieldRef(fieldSig), JDynamicInvokeExpr(bootstrapSig, listOf(methodHandle), applySig, listOf(argLocal)), StmtPositionInfo.getNoStmtPositionInfo())
        )
        val fieldTargets = readField<MutableMap<String, MutableList<MethodDescriptor>>>(adapter, "fieldDynamicTargets")
        assertTrue(fieldTargets[fieldSig.toString()].orEmpty().isNotEmpty())

        invokePrivate<Unit>(
            adapter,
            "processDynamicInvoke",
            arrayOf(JDynamicInvokeExpr::class.java, MethodDescriptor::class.java, io.johnsonlee.graphite.core.ValueNode::class.java, sootup.core.jimple.common.stmt.Stmt::class.java),
            JDynamicInvokeExpr(bootstrapSig, listOf(SootStringConstant("template", identifierFactory.getType("java.lang.String"))), applySig, emptyList()),
            caller,
            null,
            null
        )
        val builtGraph = readField<DefaultGraph.Builder>(adapter, "graphBuilder").build()
        assertTrue(builtGraph.nodes(io.johnsonlee.graphite.core.CallSiteNode::class.java).any { it.callee.name == "apply" })

        val concreteProcess = identifierFactory.getMethodSignature("sample.resolution.ConcreteService", "process", "int", listOf("int"))
        val defaultGreet = identifierFactory.getMethodSignature("sample.resolution.FormalGreeter", "greet", "java.lang.String", listOf("java.lang.String"))
        assertEquals("sample.resolution.ConcreteService", invokePrivate<MethodSignature>(adapter, "resolveMethodDefiningClass", arrayOf(MethodSignature::class.java), concreteProcess).declClassType.fullyQualifiedName)
        assertEquals("sample.resolution.Greeter", invokePrivate<MethodSignature>(adapter, "resolveMethodDefiningClass", arrayOf(MethodSignature::class.java), defaultGreet).declClassType.fullyQualifiedName)
    }

    @Test
    fun `functional dispatch propagation resolves return parameter and unresolved locals`() {
        val adapter = createAdapter(listOf("sample.lambda"), buildCallGraph = true)
        val caller = MethodDescriptor(TypeDescriptor("sample.lambda.CallbackExample"), "useCallback", emptyList(), TypeDescriptor("java.lang.String"))
        val callee = MethodDescriptor(TypeDescriptor("sample.lambda.CallbackExample"), "processWithCallback", emptyList(), TypeDescriptor("java.lang.String"))
        val target = MethodDescriptor(TypeDescriptor("sample.lambda.CallbackExample"), "transform", listOf(TypeDescriptor("java.lang.String")), TypeDescriptor("java.lang.String"))
        val resultKey = invokePrivate<Any>(adapter, "localKey", arrayOf(MethodDescriptor::class.java, String::class.java), caller, "result")
        val fieldLocalKey = invokePrivate<Any>(adapter, "localKey", arrayOf(MethodDescriptor::class.java, String::class.java), caller, "fieldLocal")
        val callSite = io.johnsonlee.graphite.core.CallSiteNode(NodeId.next(), caller, callee, 10, null, listOf(NodeId.next()))
        val unresolved = io.johnsonlee.graphite.core.CallSiteNode(NodeId.next(), caller, callee, 11, null, emptyList())

        readField<MutableMap<MethodDescriptor, List<MethodDescriptor>>>(adapter, "returnDynamicTargets")[callee] = listOf(target)
        readField<MutableMap<MethodDescriptor, MutableList<Any>>>(adapter, "callResultLocals")[callee] = mutableListOf(resultKey)
        readField<MutableMap<MethodDescriptor, MutableList<Pair<Int, io.johnsonlee.graphite.core.CallSiteNode>>>>(adapter, "parameterVirtualCalls")[callee] = mutableListOf(0 to callSite)
        readField<MutableMap<MethodDescriptor, MutableList<Pair<Int, List<MethodDescriptor>>>>>(adapter, "callSiteDynamicArgs")[callee] = mutableListOf(0 to listOf(target))
        readField<MutableMap<String, MutableList<MethodDescriptor>>>(adapter, "fieldDynamicTargets")["sample.lambda.CallbackExample.callback"] = mutableListOf(target)
        readField<MutableMap<String, MutableList<Any>>>(adapter, "fieldLoadLocals")["sample.lambda.CallbackExample.callback"] = mutableListOf(fieldLocalKey)
        readField<MutableMap<Any, MutableList<io.johnsonlee.graphite.core.CallSiteNode>>>(adapter, "unresolvedLocalVirtualCalls")[fieldLocalKey] = mutableListOf(unresolved)

        invokePrivate<Unit>(adapter, "resolveFunctionalDispatch", emptyArray())

        val dynamicTargets = readField<MutableMap<Any, List<MethodDescriptor>>>(adapter, "dynamicTargets")
        assertEquals(listOf(target), dynamicTargets[resultKey])
        assertEquals(listOf(target), dynamicTargets[fieldLocalKey])
        val builtGraph = readField<DefaultGraph.Builder>(adapter, "graphBuilder").build()
        assertTrue(builtGraph.nodes(io.johnsonlee.graphite.core.CallSiteNode::class.java).count() >= 2)
    }

    @Test
    fun `helper fallbacks cover unresolved values and bundle paths`() {
        val adapter = createAdapter(listOf("sample.resources", "sample.resolution"))
        val caller = MethodDescriptor(TypeDescriptor("sample.resources.ResourceConfig"), "messageKo", emptyList(), TypeDescriptor("java.lang.String"))
        val local = JavaLocal("value", identifierFactory.getType("java.lang.String"), emptyList())
        val integerValueOf = identifierFactory.getMethodSignature("java.lang.Integer", "valueOf", "java.lang.Integer", listOf("int"))
        val stringValueOf = identifierFactory.getMethodSignature("java.lang.String", "valueOf", "java.lang.String", listOf("java.lang.Object"))
        val resourceBundleGetBundle = identifierFactory.getMethodSignature("java.util.ResourceBundle", "getBundle", "java.util.ResourceBundle", listOf("java.lang.String", "java.util.Locale"))
        val gsonFromJson = identifierFactory.getMethodSignature("com.google.gson.Gson", "fromJson", "java.lang.Object", listOf("java.lang.String", "java.lang.Class"))
        val documentBuilderParse = identifierFactory.getMethodSignature("javax.xml.parsers.DocumentBuilder", "parse", "org.w3c.dom.Document", listOf("java.io.InputStream"))
        val urlOpenStream = identifierFactory.getMethodSignature("java.net.URL", "openStream", "java.io.InputStream", emptyList())
        val channelsNewReader = identifierFactory.getMethodSignature("java.nio.channels.Channels", "newReader", "java.io.Reader", listOf("java.nio.channels.ReadableByteChannel", "java.lang.String"))
        val irrelevantUrlMethod = identifierFactory.getMethodSignature("java.net.URL", "toString", "java.lang.String", emptyList())
        val listBundle = resolveJavaSootClass(adapter, "sample.resources.MessagesListBundle")

        assertNull(
            invokePrivate<Any?>(
                adapter,
                "extractValueFromArg",
                arrayOf(sootup.core.jimple.basic.Value::class.java, Map::class.java),
                JStaticFieldRef(identifierFactory.getFieldSignature("KOREA", identifierFactory.getClassType("java.util.Locale"), identifierFactory.getType("java.util.Locale"))),
                emptyMap<String, Any?>()
            )
        )
        assertNull(
            invokePrivate<Any?>(
                adapter,
                "extractBoxedValue",
                arrayOf(JStaticInvokeExpr::class.java),
                JStaticInvokeExpr(stringValueOf, listOf(local))
            )
        )
        assertNull(
            invokePrivate<List<String>?>(
                adapter,
                "extractCandidateLocalesFromMethod",
                arrayOf(MethodNode::class.java),
                singleFormatMethod("ignored")
            )
        )
        assertNull(
            invokePrivate<List<String>?>(
                adapter,
                "extractCandidateLocalesFromMethod",
                arrayOf(MethodNode::class.java),
                singletonIntListMethod(7)
            )
        )
        assertEquals(Locale("ko", "KR", "POSIX"), invokePrivate<Locale>(adapter, "toLocale", arrayOf(String::class.java), "ko_KR_POSIX"))
        assertNull(invokePrivate<Any?>(adapter, "resolveStaticLiteral", arrayOf(String::class.java, String::class.java), "java.lang.String", "MISSING"))
        assertNull(
            invokePrivate<LinkedHashSet<String>?>(
                adapter,
                "extractResourceBundlePaths",
                arrayOf(MethodDescriptor::class.java, MethodSignature::class.java, sootup.core.jimple.common.expr.AbstractInvokeExpr::class.java),
                caller,
                resourceBundleGetBundle,
                JStaticInvokeExpr(resourceBundleGetBundle, listOf(local, local))
            )
        )
        readField<MutableMap<String, MutableList<ResourceFileNode>>>(adapter, "resourceFilesByPath")["messages.properties"] =
            mutableListOf(ResourceFileNode(NodeId.next(), "messages.properties", "test", "properties", null))
        assertEquals(
            linkedSetOf("messages.properties"),
            invokePrivate<LinkedHashSet<String>?>(
                adapter,
                "extractResourceBundlePaths",
                arrayOf(MethodDescriptor::class.java, MethodSignature::class.java, sootup.core.jimple.common.expr.AbstractInvokeExpr::class.java),
                caller,
                resourceBundleGetBundle,
                JStaticInvokeExpr(
                    resourceBundleGetBundle,
                    listOf(SootStringConstant("messages", identifierFactory.getType("java.lang.String")), local)
                )
            )
        )
        assertNull(
            invokePrivate<Any?>(
                adapter,
                "firstMethod",
                arrayOf(sootup.core.model.SootClass::class.java, kotlin.jvm.functions.Function1::class.java),
                listBundle,
                { _: Any? -> false }
            )
        )
        assertNull(
            invokePrivate<Any?>(
                adapter,
                "bundleParent",
                arrayOf(ResourceBundle::class.java),
                SimpleBundle("orphan")
            )
        )
        assertEquals(
            7,
            invokePrivate<Any?>(
                adapter,
                "extractBoxedValue",
                arrayOf(JStaticInvokeExpr::class.java),
                JStaticInvokeExpr(integerValueOf, listOf(sootup.core.jimple.common.constant.IntConstant.getInstance(7)))
            )
        )
        assertNull(
            invokePrivate<Any?>(
                adapter,
                "extractBoxedValue",
                arrayOf(JStaticInvokeExpr::class.java),
                JStaticInvokeExpr(integerValueOf, emptyList())
            )
        )
        assertTrue(invokePrivate<Boolean>(adapter, "isResourceRelevantCall", arrayOf(MethodSignature::class.java), gsonFromJson))
        assertTrue(invokePrivate<Boolean>(adapter, "isResourceRelevantCall", arrayOf(MethodSignature::class.java), documentBuilderParse))
        assertTrue(invokePrivate<Boolean>(adapter, "isResourceRelevantCall", arrayOf(MethodSignature::class.java), urlOpenStream))
        assertTrue(invokePrivate<Boolean>(adapter, "isResourceRelevantCall", arrayOf(MethodSignature::class.java), channelsNewReader))
        assertFalse(invokePrivate<Boolean>(adapter, "isResourceRelevantCall", arrayOf(MethodSignature::class.java), irrelevantUrlMethod))

        val localeLocal = JavaLocal("locale", identifierFactory.getType("java.util.Locale"), emptyList())
        val localeCtor = identifierFactory.getMethodSignature("java.util.Locale", "<init>", "void", listOf("java.lang.String", "java.lang.String"))
        val localeCtorInvoke = JSpecialInvokeExpr(
            localeLocal,
            localeCtor,
            listOf(
                SootStringConstant("ko", identifierFactory.getType("java.lang.String")),
                SootStringConstant("KR", identifierFactory.getType("java.lang.String"))
            )
        )
        invokePrivate<Unit>(
            adapter,
            "trackResourceAssociations",
            arrayOf(
                io.johnsonlee.graphite.core.CallSiteNode::class.java,
                MethodSignature::class.java,
                sootup.core.jimple.common.expr.AbstractInvokeExpr::class.java,
                MethodDescriptor::class.java,
                io.johnsonlee.graphite.core.ValueNode::class.java
            ),
            io.johnsonlee.graphite.core.CallSiteNode(NodeId.next(), caller, MethodDescriptor(TypeDescriptor("java.util.Locale"), "<init>", emptyList(), TypeDescriptor("void")), null, null, emptyList()),
            localeCtor,
            localeCtorInvoke,
            caller,
            null
        )
        val localeKey = invokePrivate<Any>(adapter, "localKey", arrayOf(MethodDescriptor::class.java, String::class.java), caller, localeLocal.name)
        assertEquals("ko_KR", readField<MutableMap<Any, String>>(adapter, "localeSpecsByLocal")[localeKey])

        val urlLocal = JavaLocal("url", identifierFactory.getType("java.net.URL"), emptyList())
        val streamNode = LocalVariable(NodeId.next(), "stream", TypeDescriptor("java.io.InputStream"), caller)
        readField<MutableMap<Any, LinkedHashSet<String>>>(adapter, "resourceHandlePathsByLocal")[
            invokePrivate(adapter, "localKey", arrayOf(MethodDescriptor::class.java, String::class.java), caller, urlLocal.name)
        ] = linkedSetOf("config/service.xml")
        invokePrivate<Unit>(
            adapter,
            "trackResourceAssociations",
            arrayOf(
                io.johnsonlee.graphite.core.CallSiteNode::class.java,
                MethodSignature::class.java,
                sootup.core.jimple.common.expr.AbstractInvokeExpr::class.java,
                MethodDescriptor::class.java,
                io.johnsonlee.graphite.core.ValueNode::class.java
            ),
            io.johnsonlee.graphite.core.CallSiteNode(NodeId.next(), caller, MethodDescriptor(TypeDescriptor("java.net.URL"), "openStream", emptyList(), TypeDescriptor("java.io.InputStream")), null, null, emptyList()),
            urlOpenStream,
            JVirtualInvokeExpr(urlLocal, urlOpenStream, emptyList()),
            caller,
            streamNode
        )
        val streamKey = invokePrivate<Any>(adapter, "localKey", arrayOf(MethodDescriptor::class.java, String::class.java), caller, "stream")
        assertEquals(linkedSetOf("config/service.xml"), readField<MutableMap<Any, LinkedHashSet<String>>>(adapter, "resourceHandlePathsByLocal")[streamKey])

        assertNull(invokePrivate<Any?>(adapter, "evaluateLiteralMethod", arrayOf(MethodNode::class.java), emptyMethod()))
        assertNull(
            invokePrivate<Any?>(
                adapter,
                "extractBundleControlSpec",
                arrayOf(MethodDescriptor::class.java, sootup.core.jimple.basic.Value::class.java, String::class.java, String::class.java),
                caller,
                JNewExpr(identifierFactory.getClassType("missing.Control")),
                "sample.resources.MessagesListBundle",
                "ko_KR"
            )
        )
        assertNull(
            invokePrivate<Any?>(
                adapter,
                "extractBundleControlSpec",
                arrayOf(MethodDescriptor::class.java, sootup.core.jimple.basic.Value::class.java, String::class.java, String::class.java),
                caller,
                SootStringConstant("not-control", identifierFactory.getType("java.lang.String")),
                "sample.resources.MessagesListBundle",
                "ko_KR"
            )
        )
        val unsupportedControl = identifierFactory.getMethodSignature("java.util.ResourceBundle\$Control", "unsupported", "java.util.ResourceBundle\$Control", emptyList())
        assertNull(
            invokePrivate<Any?>(
                adapter,
                "extractBundleControlSpec",
                arrayOf(MethodDescriptor::class.java, MethodSignature::class.java, sootup.core.jimple.common.expr.AbstractInvokeExpr::class.java),
                caller,
                unsupportedControl,
                JStaticInvokeExpr(unsupportedControl, emptyList())
            )
        )
        val unknownFormatRef = JStaticFieldRef(identifierFactory.getFieldSignature("UNKNOWN", identifierFactory.getClassType("java.util.ResourceBundle\$Control"), identifierFactory.getType("java.lang.Object")))
        assertNull(invokePrivate<String?>(adapter, "extractControlFormat", arrayOf(MethodDescriptor::class.java, sootup.core.jimple.basic.Value::class.java), caller, unknownFormatRef))
        assertNull(invokePrivate<String?>(adapter, "extractControlFormat", arrayOf(MethodDescriptor::class.java, sootup.core.jimple.basic.Value::class.java), caller, sootup.core.jimple.common.constant.IntConstant.getInstance(1)))
        assertNull(invokePrivate<String?>(adapter, "extractLocaleSpec", arrayOf(MethodDescriptor::class.java, sootup.core.jimple.basic.Value::class.java), caller, SootStringConstant("ko", identifierFactory.getType("java.lang.String"))))
        val plainBundle = SimpleBundle("plain")
        invokePrivate<Unit>(adapter, "indexRuntimeBundle", arrayOf(ResourceBundle::class.java), plainBundle)
        assertEquals("bundle", readField<MutableMap<String, MutableList<ResourceFileNode>>>(adapter, "resourceFilesByPath").getValue(plainBundle.javaClass.name).single().format)
    }

    private fun createAdapter(
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

    private fun referencedClassBytes(): ByteArray {
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

    private fun createResourceBackedAdapter(): SootUpAdapter {
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

    private fun resolveJavaSootClass(adapter: SootUpAdapter, className: String): JavaSootClass =
        invokePrivate<JavaSootClass?>(adapter, "resolveClassByName", arrayOf(String::class.java), className)
            ?: error("Unable to resolve $className")

    private fun nullReturnMethod(): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC, "getFallbackLocale", "(Ljava/lang/String;Ljava/util/Locale;)Ljava/util/Locale;", null, null).apply {
            instructions.add(InsnNode(Opcodes.ACONST_NULL))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }

    private fun emptyMethod(): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "empty", "()Ljava/lang/Object;", null, null)

    private fun listOfMethod(): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "formats", "()Ljava/util/List;", null, null).apply {
            instructions.add(LdcInsnNode("java.class"))
            instructions.add(LdcInsnNode("java.properties"))
            instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/List", "of", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;", true))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }

    private fun singletonListMethod(): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "formats", "()Ljava/util/List;", null, null).apply {
            instructions.add(LdcInsnNode("java.properties"))
            instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Collections", "singletonList", "(Ljava/lang/Object;)Ljava/util/List;", false))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }

    private fun languageTagMethod(): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "locale", "()Ljava/lang/Object;", null, null).apply {
            instructions.add(LdcInsnNode("ko-KR"))
            instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Locale", "forLanguageTag", "(Ljava/lang/String;)Ljava/util/Locale;", false))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }

    private fun intPushMethod(opcode: Int, operand: Int): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "literal", "()Ljava/lang/Object;", null, null).apply {
            instructions.add(org.objectweb.asm.tree.IntInsnNode(opcode, operand))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }

    private fun iconstReturnMethod(opcode: Int): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "literal", "()Ljava/lang/Object;", null, null).apply {
            instructions.add(InsnNode(opcode))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }

    private fun localeCtorMethod(): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "locale", "()Ljava/lang/Object;", null, null).apply {
            instructions.add(LdcInsnNode("ko"))
            instructions.add(LdcInsnNode("KR"))
            instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/Locale", "<init>", "(Ljava/lang/String;Ljava/lang/String;)V", false))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }

    private fun arraysAsListMethod(): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "formats", "()Ljava/util/List;", null, null).apply {
            instructions.add(LdcInsnNode("java.class"))
            instructions.add(LdcInsnNode("java.properties"))
            instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/List", "of", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;", true))
            instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Arrays", "asList", "(Ljava/lang/Object;)Ljava/util/List;", false))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }

    private fun storedStringMethod(): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "stored", "()Ljava/lang/Object;", null, null).apply {
            instructions.add(LdcInsnNode("stored"))
            instructions.add(VarInsnNode(Opcodes.ASTORE, 0))
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.add(InsnNode(Opcodes.DUP))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }

    private fun singleFormatMethod(value: String): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "formats", "()Ljava/lang/Object;", null, null).apply {
            instructions.add(LdcInsnNode(value))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }

    private fun singletonIntListMethod(value: Int): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "formats", "()Ljava/util/List;", null, null).apply {
            instructions.add(org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, value))
            instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false))
            instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Collections", "singletonList", "(Ljava/lang/Object;)Ljava/util/List;", false))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }

    private fun singleIntListOfMethod(value: Int): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "formats", "()Ljava/util/List;", null, null).apply {
            instructions.add(org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, value))
            instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/List", "of", "(Ljava/lang/Object;)Ljava/util/List;", true))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> readField(target: Any, name: String): T {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target) as T
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> readProperty(target: Any, name: String): T {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target) as T
    }

    private fun writeField(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> invokePrivate(target: Any, name: String, parameterTypes: Array<Class<*>>, vararg args: Any?): T {
        val method = target.javaClass.getDeclaredMethod(name, *parameterTypes)
        method.isAccessible = true
        return method.invoke(target, *args) as T
    }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }

    private fun findCompiledClassBytes(relativePath: String): ByteArray =
        findTestClassesDir().resolve(relativePath).toFile().readBytes()

    private class SimpleBundle(private val value: String) : ResourceBundle() {
        override fun handleGetObject(key: String): Any = value
        override fun getKeys(): java.util.Enumeration<String> = java.util.Collections.enumeration(listOf("hello"))
        fun withParent(parent: ResourceBundle): SimpleBundle {
            setParent(parent)
            return this
        }
    }

    private class FakeAnnotationUsage {
        fun getAnnotation(): FakeAnnotation = FakeAnnotation()
        fun getValues(): Map<String, Any?> = mapOf("value" to "\"ok\"")
    }

    private class FakeAnnotation {
        fun getFullyQualifiedName(): String = "sample.Annotation"
    }

    private open class FakeSootMethod(
        signature: MethodSignature,
        modifiers: Iterable<MethodModifier> = emptyList()
    ) : SootMethod(
        FakeBodySource(signature),
        signature,
        modifiers,
        emptyList<ClassType>(),
        NoPositionInformation.getInstance()
    )

    private class FakeBodySource(private val signature: MethodSignature) : BodySource {
        override fun resolveBody(modifiers: Iterable<MethodModifier>): Body =
            error("Fake method has no body")

        override fun resolveAnnotationsDefaultValue(): Any? = null

        override fun getSignature(): MethodSignature = signature
    }

    private open class FakeSootClass(
        classType: ClassType,
        private val methods: Set<SootMethod> = emptySet(),
        private val failure: Throwable? = null,
        private val enumClass: Boolean = false
    ) : SootClass(FakeClassSource(classType), SourceType.Application) {
        override fun getMethods(): Set<SootMethod> {
            failure?.let { throw it }
            return methods
        }

        override fun isEnum(): Boolean = enumClass
    }

    private class FakeClassSource(classType: ClassType) : SootClassSource(
        PathBasedAnalysisInputLocation.create(Path.of(System.getProperty("java.io.tmpdir")), SourceType.Application),
        classType,
        Path.of("fake.class")
    ) {
        override fun resolveMethods(): Collection<SootMethod> = emptyList()
        override fun resolveFields(): Collection<SootField> = emptyList()
        override fun resolveModifiers(): Set<ClassModifier> = emptySet()
        override fun resolveInterfaces(): Set<ClassType> = emptySet()
        override fun resolveSuperclass(): java.util.Optional<ClassType> = java.util.Optional.empty()
        override fun resolveOuterClass(): java.util.Optional<ClassType> = java.util.Optional.empty()
        override fun resolvePosition() = NoPositionInformation.getInstance()
        override fun buildClass(sourceType: SourceType): SootClass = FakeSootClass(classType)
    }

    private class FakeView(
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
