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
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import sootup.core.jimple.basic.StmtPositionInfo
import sootup.core.jimple.common.constant.MethodHandle
import sootup.core.jimple.common.constant.StringConstant as SootStringConstant
import sootup.core.jimple.common.expr.JDynamicInvokeExpr
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.jimple.common.ref.JStaticFieldRef
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.inputlocation.AnalysisInputLocation
import sootup.core.model.SourceType
import sootup.core.signatures.MethodSignature
import sootup.core.types.VoidType
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation
import sootup.java.core.JavaIdentifierFactory
import sootup.java.core.JavaSootClass
import sootup.java.core.jimple.basic.JavaLocal
import sootup.java.core.views.JavaView

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
        assertNull(invokePrivate<Any?>(adapter, "normalizeAnnotationValue", arrayOf(Any::class.java), object {
            override fun toString(): String = "null"
        }))
        assertEquals("", invokePrivate<String>(adapter, "getAnnotationFullName", arrayOf(Any::class.java), null))
        assertTrue(invokePrivate<Map<String, Any?>>(adapter, "getAnnotationValues", arrayOf(Any::class.java), null).isEmpty())
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
        assertEquals(setOf("java.class"), invokePrivate<Set<String>?>(adapter, "extractControlFormatsFromMethod", arrayOf(MethodNode::class.java), singleFormatMethod("java.class")))
        assertEquals(setOf("java.properties"), invokePrivate<Set<String>?>(adapter, "extractControlFormatsFromMethod", arrayOf(MethodNode::class.java), singleFormatMethod("java.properties")))
        assertNull(invokePrivate<Set<String>?>(adapter, "extractControlFormatsFromMethod", arrayOf(MethodNode::class.java), singleIntListOfMethod(7)))
        assertNull(invokePrivate<Set<String>?>(adapter, "extractControlFormatsFromMethod", arrayOf(MethodNode::class.java), singleFormatMethod("ignored")))

        val bundle = ResourceBundle.getBundle("sample.resources.MessagesListBundle", Locale.KOREA)
        invokePrivate<Unit>(adapter, "indexRuntimeBundle", arrayOf(ResourceBundle::class.java), bundle)
        val indexed = readField<MutableMap<String, *>>(adapter, "resourceFilesByPath")
        assertTrue(indexed.containsKey("sample.resources.MessagesListBundle_ko_KR"))
        val parent = SimpleBundle("parent")
        val child = SimpleBundle("child").withParent(parent)
        assertNull(invokePrivate<ResourceBundle?>(adapter, "bundleParent", arrayOf(ResourceBundle::class.java), child))
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
    }

    private fun createAdapter(includePackages: List<String> = listOf("sample.resources"), buildCallGraph: Boolean = false): SootUpAdapter {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")
        val location = PathBasedAnalysisInputLocation.create(testClassesDir, SourceType.Application)
        val inputLocations: List<AnalysisInputLocation> = listOf(location)
        return SootUpAdapter(
            view = JavaView(inputLocations),
            config = LoaderConfig(includePackages = includePackages, buildCallGraph = buildCallGraph),
            signatureReader = BytecodeSignatureReader(),
            extensions = emptyList(),
            resourceAccessor = EmptyResourceAccessor,
            inputLocationSources = mapOf(location to testClassesDir.fileName.toString()),
            graphBuilder = DefaultGraph.Builder()
        )
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

    private fun singleFormatMethod(value: String): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "formats", "()Ljava/lang/Object;", null, null).apply {
            instructions.add(LdcInsnNode(value))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }

    private fun singletonLiteralListMethod(value: String): MethodNode =
        MethodNode(ASM_API_VERSION, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "formats", "()Ljava/util/List;", null, null).apply {
            instructions.add(LdcInsnNode(value))
            instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Collections", "singletonList", "(Ljava/lang/Object;)Ljava/util/List;", false))
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
}
