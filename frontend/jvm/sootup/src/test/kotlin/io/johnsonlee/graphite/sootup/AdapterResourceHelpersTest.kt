package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.ResourceEntry
import java.util.Locale
import java.util.ResourceBundle
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.NodeId
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.MethodNode
import sootup.core.jimple.common.constant.StringConstant as SootStringConstant
import sootup.core.jimple.common.ref.JStaticFieldRef
import sootup.core.model.SootClass
import sootup.java.core.JavaSootClass
import sootup.java.core.jimple.basic.JavaLocal

internal class AdapterResourceHelpersTest : AdapterTestFixtures() {
    @Test
    fun `literal helpers resolve locale and control constants`() {
        val adapter = createAdapter()

        assertEquals(listOf("java.class"), invokePrivate<Any?>(adapter, "resolveStaticLiteral", arrayOf(String::class.java,
            String::class.java), "java.util.ResourceBundle\$Control", "FORMAT_CLASS"))
        assertEquals(listOf("java.properties"), invokePrivate<Any?>(adapter, "resolveStaticLiteral",
            arrayOf(String::class.java, String::class.java), "java.util.ResourceBundle\$Control", "FORMAT_PROPERTIES"))
        assertEquals(listOf("java.class", "java.properties"), invokePrivate<Any?>(adapter, "resolveStaticLiteral",
            arrayOf(String::class.java, String::class.java), "java.util.ResourceBundle\$Control", "FORMAT_DEFAULT"))
        assertEquals("ko_KR", invokePrivate<String?>(adapter, "resolveStaticLiteral", arrayOf(String::class.java,
            String::class.java), "java.util.Locale", "KOREA"))
        assertEquals("", invokePrivate<String?>(adapter, "resolveStaticLiteral", arrayOf(String::class.java,
            String::class.java), "java.util.Locale", "ROOT"))

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
        assertEquals(setOf("java.properties"), invokePrivate<Set<String>>(adapter, "controlFormats",
            arrayOf(String::class.java), "java.properties"))
        assertEquals(setOf("java.properties", "java.class"), invokePrivate<Set<String>>(adapter, "controlFormats",
            arrayOf(String::class.java), null))

        assertEquals(Locale("ko", "KR"), invokePrivate<Locale>(adapter, "toLocale", arrayOf(String::class.java), "ko_KR"))
        assertEquals(Locale("ko"), invokePrivate<Locale>(adapter, "toLocale", arrayOf(String::class.java), "ko"))
        assertEquals(Locale.ROOT, invokePrivate<Locale>(adapter, "toLocale", arrayOf(String::class.java), ""))
        assertEquals("en_US_POSIX", invokePrivate<String>(adapter, "localeSpecOf", arrayOf(Locale::class.java), Locale("en",
            "US", "POSIX")))
        assertEquals("", invokePrivate<String>(adapter, "localeSpecOf", arrayOf(Locale::class.java), Locale.ROOT))

        assertTrue(invokePrivate<Boolean>(adapter, "matchesBundleClassPath", arrayOf(String::class.java, String::class.java),
            "sample.resources.MessagesListBundle_ko_KR", "sample.resources.MessagesListBundle"))
        assertFalse(invokePrivate<Boolean>(adapter, "matchesBundleClassPath", arrayOf(String::class.java,
            String::class.java), "other.Bundle", "sample.resources.MessagesListBundle"))

        val caller = MethodDescriptor(TypeDescriptor("sample.resources.ResourceConfig"), "lookup", emptyList(),
            TypeDescriptor("java.lang.String"))
        assertEquals("application.properties", invokePrivate<String>(adapter, "normalizeResourcePath",
            arrayOf(MethodDescriptor::class.java, String::class.java, String::class.java), caller, "java.lang.ClassLoader",
                "/application.properties"))
        assertEquals("application.properties", invokePrivate<String>(adapter, "normalizeResourcePath",
            arrayOf(MethodDescriptor::class.java, String::class.java, String::class.java), caller, "java.lang.Class",
                "/application.properties"))
        assertEquals("sample/resources/messages.properties", invokePrivate<String>(adapter, "normalizeResourcePath",
            arrayOf(MethodDescriptor::class.java, String::class.java, String::class.java), caller, "java.lang.Class",
                "messages.properties"))
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
    fun `artifact and class resource helpers normalize boundary cases`() {
        val adapter = createAdapter()

        assertEquals("dependency", invokePrivate<String?>(adapter, "artifactKey", arrayOf(String::class.java),
            "/opt/app/lib/dependency.jar"))
        assertNull(invokePrivate<String?>(adapter, "artifactKey", arrayOf(String::class.java), " / "))
        assertEquals("sample.resources.Messages", invokePrivate<String?>(adapter, "classResourcePathToName",
            arrayOf(String::class.java), "sample/resources/Messages.class"))
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
        assertEquals("local", invokePrivate<String?>(adapter, "resourceProfile", arrayOf(String::class.java),
            "config/application-local.json"))
        assertNull(invokePrivate<String?>(adapter, "resourceProfile", arrayOf(String::class.java), "config/feature.json"))
    }

    @Test
    fun `compiled resource bundle helpers inspect bytecode and control specs`() {
        val adapter = createAdapter()
        val resourceBackedAdapter = createResourceBackedAdapter()
        val classOnlyControl = resolveJavaSootClass(adapter, "sample.resources.ClassOnlyControl")
        val koreanOnlyControl = resolveJavaSootClass(adapter, "sample.resources.KoreanOnlyControl")
        val listBundle = resolveJavaSootClass(adapter, "sample.resources.MessagesListBundle_ko_KR")

        val directLoadedMethods = invokePrivate<List<MethodNode>?>(resourceBackedAdapter, "loadMethodNodesFromResource",
            arrayOf(JavaSootClass::class.java), resolveJavaSootClass(resourceBackedAdapter, "sample.resources.MessagesListBundle_ko_KR"))
        val controlMethods = invokePrivate<List<MethodNode>?>(adapter, "getAsmMethodNodes",
            arrayOf(JavaSootClass::class.java), classOnlyControl)
            ?: invokePrivate<List<MethodNode>?>(adapter, "loadMethodNodesFromResource", arrayOf(JavaSootClass::class.java),
                classOnlyControl)
        val candidateMethods = invokePrivate<List<MethodNode>?>(adapter, "getAsmMethodNodes",
            arrayOf(JavaSootClass::class.java), koreanOnlyControl)
            ?: invokePrivate<List<MethodNode>?>(adapter, "loadMethodNodesFromResource", arrayOf(JavaSootClass::class.java),
                koreanOnlyControl)
        assertNotNull(directLoadedMethods)
        assertTrue(directLoadedMethods.isNotEmpty())
        assertNotNull(controlMethods)
        assertNotNull(candidateMethods)
        val streamedMethods = invokePrivate<Sequence<*>>(resourceBackedAdapter, "streamMethodsOrNull",
            arrayOf(sootup.core.model.SootClass::class.java), resolveJavaSootClass(resourceBackedAdapter,
                "sample.resources.MessagesListBundle_ko_KR"))
        assertNotNull(streamedMethods)
        assertTrue(streamedMethods.any())

        val getFormats = controlMethods.first { it.name == "getFormats" }
        val getCandidateLocales = candidateMethods.first { it.name == "getCandidateLocales" }
        assertEquals(setOf("java.class"), invokePrivate<Set<String>?>(adapter, "extractControlFormatsFromMethod",
            arrayOf(MethodNode::class.java), getFormats))
        assertEquals(listOf("ko_KR"), invokePrivate<List<String>?>(adapter, "extractCandidateLocalesFromMethod",
            arrayOf(MethodNode::class.java), getCandidateLocales))
        assertTrue(invokePrivate<Boolean>(adapter, "returnsNullLiteral", arrayOf(MethodNode::class.java), nullReturnMethod()))

        val classOnlySpec = invokePrivate<Any?>(adapter, "resolveBundleControlSpec", arrayOf(String::class.java),
            "sample.resources.ClassOnlyControl")
        val koreanOnlySpec = invokePrivate<Any?>(adapter, "resolveBundleControlSpec", arrayOf(String::class.java),
            "sample.resources.KoreanOnlyControl")
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
        resourceFilesByPath["messages.properties"] = mutableListOf(ResourceFileNode(NodeId.next(), "messages.properties",
            "test", "properties", null))
        resourceFilesByPath["messages_ko.properties"] = mutableListOf(ResourceFileNode(NodeId.next(),
            "messages_ko.properties", "test", "properties", null))
        resourceFilesByPath["messages_ko_KR.properties"] = mutableListOf(ResourceFileNode(NodeId.next(),
            "messages_ko_KR.properties", "test", "properties", null))
        resourceFilesByPath["sample.resources.MessagesListBundle_ko_KR"] = mutableListOf(ResourceFileNode(NodeId.next(),
            "sample.resources.MessagesListBundle_ko_KR", "test", "listbundle", null))
        assertEquals(
            linkedSetOf("messages_ko_KR.properties", "messages_ko.properties", "messages.properties"),
            invokePrivate<LinkedHashSet<String>>(adapter, "collectMatchingResourceBundlePaths", arrayOf(String::class.java,
                String::class.java, classOnlySpec.javaClass), "messages", "messages", null)
        )
        assertEquals(
            linkedSetOf("sample.resources.MessagesListBundle_ko_KR"),
            invokePrivate<LinkedHashSet<String>>(adapter, "collectMatchingResourceBundlePaths", arrayOf(String::class.java,
                String::class.java, classOnlySpec.javaClass), "sample.resources.MessagesListBundle",
                    "sample/resources/MessagesListBundle", classOnlySpec)
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
        assertEquals(42, invokePrivate<Any?>(adapter, "evaluateLiteralMethod", arrayOf(MethodNode::class.java),
            intPushMethod(Opcodes.BIPUSH, 42)))
        assertEquals(1024, invokePrivate<Any?>(adapter, "evaluateLiteralMethod", arrayOf(MethodNode::class.java),
            intPushMethod(Opcodes.SIPUSH, 1024)))
        assertEquals(-1, invokePrivate<Any?>(adapter, "evaluateLiteralMethod", arrayOf(MethodNode::class.java),
            iconstReturnMethod(Opcodes.ICONST_M1)))
        assertEquals(5, invokePrivate<Any?>(adapter, "evaluateLiteralMethod", arrayOf(MethodNode::class.java),
            iconstReturnMethod(Opcodes.ICONST_5)))
        assertNull(invokePrivate<Any?>(adapter, "evaluateLiteralMethod", arrayOf(MethodNode::class.java), localeCtorMethod()))
        assertEquals(listOf("java.class", "java.properties"), invokePrivate<Any?>(adapter, "evaluateLiteralMethod",
            arrayOf(MethodNode::class.java), arraysAsListMethod()))
        assertEquals("stored", invokePrivate<Any?>(adapter, "evaluateLiteralMethod", arrayOf(MethodNode::class.java), storedStringMethod()))
        assertEquals(setOf("java.class"), invokePrivate<Set<String>?>(adapter, "extractControlFormatsFromMethod",
            arrayOf(MethodNode::class.java), singleFormatMethod("java.class")))
        assertEquals(setOf("java.properties"), invokePrivate<Set<String>?>(adapter, "extractControlFormatsFromMethod",
            arrayOf(MethodNode::class.java), singleFormatMethod("java.properties")))
        assertNull(invokePrivate<Set<String>?>(adapter, "extractControlFormatsFromMethod", arrayOf(MethodNode::class.java),
            singleIntListOfMethod(7)))
        assertNull(invokePrivate<Set<String>?>(adapter, "extractControlFormatsFromMethod", arrayOf(MethodNode::class.java),
            singleFormatMethod("ignored")))

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

        indexed["messages_ko_KR.properties"] = mutableListOf(ResourceFileNode(NodeId.next(), "messages_ko_KR.properties",
            "test", "properties", null))
        indexed["messages_ko.properties"] = mutableListOf(ResourceFileNode(NodeId.next(), "messages_ko.properties", "test",
            "properties", null))
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
        val caller = MethodDescriptor(TypeDescriptor("sample.resources.ResourceConfig"), "lookup", emptyList(),
            TypeDescriptor("java.lang.String"))
        val controlLocal = JavaLocal("control", identifierFactory.getType("java.lang.String"), emptyList())
        val textLocal = JavaLocal("text", identifierFactory.getType("java.lang.String"), emptyList())
        val wrongOwnerRef = JStaticFieldRef(identifierFactory.getFieldSignature("FORMAT_CLASS",
            identifierFactory.getClassType("java.lang.String"), identifierFactory.getType("java.lang.Object")))
        val classFormatRef = JStaticFieldRef(identifierFactory.getFieldSignature("FORMAT_CLASS",
            identifierFactory.getClassType("java.util.ResourceBundle\$Control"), identifierFactory.getType("java.lang.Object")))
        val propertiesFormatRef = JStaticFieldRef(identifierFactory.getFieldSignature("FORMAT_PROPERTIES",
            identifierFactory.getClassType("java.util.ResourceBundle\$Control"), identifierFactory.getType("java.lang.Object")))
        val defaultFormatRef = JStaticFieldRef(identifierFactory.getFieldSignature("FORMAT_DEFAULT",
            identifierFactory.getClassType("java.util.ResourceBundle\$Control"), identifierFactory.getType("java.lang.Object")))

        readField<MutableMap<Any, String>>(adapter, "bundleControlFormatsByLocal")[
            invokePrivate(adapter, "localKey", arrayOf(MethodDescriptor::class.java, String::class.java), caller, controlLocal.name)
        ] = "java.class"
        readField<MutableMap<Any, String>>(adapter, "stringValuesByLocal")[
            invokePrivate(adapter, "localKey", arrayOf(MethodDescriptor::class.java, String::class.java), caller, textLocal.name)
        ] = "hello"

        assertEquals("java.class", invokePrivate<String?>(adapter, "extractControlFormat",
            arrayOf(MethodDescriptor::class.java, sootup.core.jimple.common.Value::class.java), caller, controlLocal))
        assertEquals("java.class", invokePrivate<String?>(adapter, "extractControlFormat",
            arrayOf(MethodDescriptor::class.java, sootup.core.jimple.common.Value::class.java), caller, classFormatRef))
        assertEquals("java.properties", invokePrivate<String?>(adapter, "extractControlFormat",
            arrayOf(MethodDescriptor::class.java, sootup.core.jimple.common.Value::class.java), caller, propertiesFormatRef))
        assertNull(invokePrivate<String?>(adapter, "extractControlFormat", arrayOf(MethodDescriptor::class.java,
            sootup.core.jimple.common.Value::class.java), caller, defaultFormatRef))
        assertNull(invokePrivate<String?>(adapter, "extractControlFormat", arrayOf(MethodDescriptor::class.java,
            sootup.core.jimple.common.Value::class.java), caller, wrongOwnerRef))
        assertNull(invokePrivate<String?>(adapter, "extractControlFormat", arrayOf(MethodDescriptor::class.java,
            sootup.core.jimple.common.Value::class.java), null, controlLocal))

        assertEquals("direct", invokePrivate<String?>(adapter, "extractStringValue", arrayOf(MethodDescriptor::class.java,
            sootup.core.jimple.common.Value::class.java), caller, SootStringConstant("direct",
                identifierFactory.getType("java.lang.String"))))
        assertEquals("hello", invokePrivate<String?>(adapter, "extractStringValue", arrayOf(MethodDescriptor::class.java,
            sootup.core.jimple.common.Value::class.java), caller, textLocal))
        assertNull(invokePrivate<String?>(adapter, "extractStringValue", arrayOf(MethodDescriptor::class.java,
            sootup.core.jimple.common.Value::class.java), caller, null))

        assertTrue(invokePrivate(adapter, "isResourceBundleControlClass", arrayOf(sootup.core.model.SootClass::class.java),
            resolveJavaSootClass(adapter, "sample.resources.ClassOnlyControl")))
        assertFalse(invokePrivate(adapter, "isResourceBundleControlClass", arrayOf(sootup.core.model.SootClass::class.java),
            resolveJavaSootClass(adapter, "sample.resources.MessagesListBundle")))
    }

}
