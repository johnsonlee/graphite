package io.johnsonlee.graphite.sootup

import java.util.Locale
import java.util.ResourceBundle
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.objectweb.asm.tree.MethodNode
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.constant.StringConstant as SootStringConstant
import sootup.core.jimple.common.expr.JNewExpr
import sootup.core.jimple.common.expr.JSpecialInvokeExpr
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.jimple.common.expr.JVirtualInvokeExpr
import sootup.core.jimple.common.ref.JStaticFieldRef
import sootup.core.model.SootClass
import sootup.core.signatures.MethodSignature
import sootup.java.core.jimple.basic.JavaLocal

internal class AdapterFallbackHelpersTest : AdapterTestFixtures() {
    @Test
    fun `unresolved literals and unsupported factories retain empty results`() {
        val adapter = createAdapter(listOf("sample.resources", "sample.resolution"))
        val local = JavaLocal("value", identifierFactory.getType("java.lang.String"), emptyList())
        val stringValueOf = identifierFactory.getMethodSignature("java.lang.String", "valueOf", "java.lang.String",
            listOf("java.lang.Object"))
        assertNull(
            invokePrivate<Any?>(
                adapter,
                "extractValueFromArg",
                arrayOf(sootup.core.jimple.common.Value::class.java, Map::class.java),
                JStaticFieldRef(identifierFactory.getFieldSignature("KOREA",
                    identifierFactory.getClassType("java.util.Locale"), identifierFactory.getType("java.util.Locale"))),
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
        assertNull(invokePrivate<Any?>(adapter, "resolveStaticLiteral", arrayOf(String::class.java, String::class.java),
            "java.lang.String", "MISSING"))
    }

    @Test
    fun `bundle path lookups and resource call classification retain fallbacks`() {
        val adapter = createAdapter(listOf("sample.resources", "sample.resolution"))
        val caller = MethodDescriptor(TypeDescriptor("sample.resources.ResourceConfig"), "messageKo", emptyList(),
            TypeDescriptor("java.lang.String"))
        val local = JavaLocal("value", identifierFactory.getType("java.lang.String"), emptyList())
        val integerValueOf = identifierFactory.getMethodSignature("java.lang.Integer", "valueOf", "java.lang.Integer", listOf("int"))
        val resourceBundleGetBundle = identifierFactory.getMethodSignature("java.util.ResourceBundle", "getBundle",
            "java.util.ResourceBundle", listOf("java.lang.String", "java.util.Locale"))
        val gsonFromJson = identifierFactory.getMethodSignature("com.google.gson.Gson", "fromJson", "java.lang.Object",
            listOf("java.lang.String", "java.lang.Class"))
        val documentBuilderParse = identifierFactory.getMethodSignature("javax.xml.parsers.DocumentBuilder", "parse",
            "org.w3c.dom.Document", listOf("java.io.InputStream"))
        val urlOpenStream = identifierFactory.getMethodSignature("java.net.URL", "openStream", "java.io.InputStream", emptyList())
        val channelsNewReader = identifierFactory.getMethodSignature("java.nio.channels.Channels", "newReader",
            "java.io.Reader", listOf("java.nio.channels.ReadableByteChannel", "java.lang.String"))
        val irrelevantUrlMethod = identifierFactory.getMethodSignature("java.net.URL", "toString", "java.lang.String", emptyList())
        val listBundle = resolveJavaSootClass(adapter, "sample.resources.MessagesListBundle")

        assertNull(
            invokePrivate<LinkedHashSet<String>?>(
                adapter,
                "extractResourceBundlePaths",
                arrayOf(MethodDescriptor::class.java, MethodSignature::class.java,
                    sootup.core.jimple.common.expr.AbstractInvokeExpr::class.java),
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
                arrayOf(MethodDescriptor::class.java, MethodSignature::class.java,
                    sootup.core.jimple.common.expr.AbstractInvokeExpr::class.java),
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
                "findStaticMethod",
                arrayOf(sootup.core.model.SootClass::class.java, String::class.java),
                listBundle,
                "missingStaticMethod"
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

    }

    @Test
    fun `resource associations preserve constructed locales and bound streams`() {
        val adapter = createAdapter(listOf("sample.resources", "sample.resolution"))
        val caller = MethodDescriptor(TypeDescriptor("sample.resources.ResourceConfig"), "messageKo", emptyList(),
            TypeDescriptor("java.lang.String"))
        val urlOpenStream = identifierFactory.getMethodSignature("java.net.URL", "openStream", "java.io.InputStream", emptyList())
        val localeLocal = JavaLocal("locale", identifierFactory.getType("java.util.Locale"), emptyList())
        val localeCtor = identifierFactory.getMethodSignature("java.util.Locale", "<init>", "void",
            listOf("java.lang.String", "java.lang.String"))
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
            io.johnsonlee.graphite.core.CallSiteNode(NodeId.next(), caller,
                MethodDescriptor(TypeDescriptor("java.util.Locale"), "<init>", emptyList(), TypeDescriptor("void")), null,
                    null, emptyList()),
            localeCtor,
            localeCtorInvoke,
            caller,
            null
        )
        val localeKey = invokePrivate<Any>(adapter, "localKey", arrayOf(MethodDescriptor::class.java, String::class.java),
            caller, localeLocal.name)
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
            io.johnsonlee.graphite.core.CallSiteNode(NodeId.next(), caller, MethodDescriptor(TypeDescriptor("java.net.URL"),
                "openStream", emptyList(), TypeDescriptor("java.io.InputStream")), null, null, emptyList()),
            urlOpenStream,
            JVirtualInvokeExpr(urlLocal, urlOpenStream, emptyList()),
            caller,
            streamNode
        )
        val streamKey = invokePrivate<Any>(adapter, "localKey", arrayOf(MethodDescriptor::class.java, String::class.java), caller, "stream")
        assertEquals(linkedSetOf("config/service.xml"), readField<MutableMap<Any, LinkedHashSet<String>>>(adapter,
            "resourceHandlePathsByLocal")[streamKey])

    }

    @Test
    fun `unsupported controls and runtime bundles retain fallback behavior`() {
        val adapter = createAdapter(listOf("sample.resources", "sample.resolution"))
        val caller = MethodDescriptor(TypeDescriptor("sample.resources.ResourceConfig"), "messageKo", emptyList(),
            TypeDescriptor("java.lang.String"))
        assertNull(invokePrivate<Any?>(adapter, "evaluateLiteralMethod", arrayOf(MethodNode::class.java), emptyMethod()))
        assertNull(
            invokePrivate<Any?>(
                adapter,
                "extractBundleControlSpec",
                arrayOf(MethodDescriptor::class.java, sootup.core.jimple.common.Value::class.java, String::class.java, String::class.java),
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
                arrayOf(MethodDescriptor::class.java, sootup.core.jimple.common.Value::class.java, String::class.java, String::class.java),
                caller,
                SootStringConstant("not-control", identifierFactory.getType("java.lang.String")),
                "sample.resources.MessagesListBundle",
                "ko_KR"
            )
        )
        val unsupportedControl = identifierFactory.getMethodSignature("java.util.ResourceBundle\$Control", "unsupported",
            "java.util.ResourceBundle\$Control", emptyList())
        assertNull(
            invokePrivate<Any?>(
                adapter,
                "extractBundleControlSpec",
                arrayOf(MethodDescriptor::class.java, MethodSignature::class.java,
                    sootup.core.jimple.common.expr.AbstractInvokeExpr::class.java),
                caller,
                unsupportedControl,
                JStaticInvokeExpr(unsupportedControl, emptyList())
            )
        )
        val unknownFormatRef = JStaticFieldRef(identifierFactory.getFieldSignature("UNKNOWN",
            identifierFactory.getClassType("java.util.ResourceBundle\$Control"), identifierFactory.getType("java.lang.Object")))
        assertNull(invokePrivate<String?>(adapter, "extractControlFormat", arrayOf(MethodDescriptor::class.java,
            sootup.core.jimple.common.Value::class.java), caller, unknownFormatRef))
        assertNull(invokePrivate<String?>(adapter, "extractControlFormat", arrayOf(MethodDescriptor::class.java,
            sootup.core.jimple.common.Value::class.java), caller, sootup.core.jimple.common.constant.IntConstant.getInstance(1)))
        assertNull(invokePrivate<String?>(adapter, "extractLocaleSpec", arrayOf(MethodDescriptor::class.java,
            sootup.core.jimple.common.Value::class.java), caller, SootStringConstant("ko", identifierFactory.getType("java.lang.String"))))
        val plainBundle = SimpleBundle("plain")
        invokePrivate<Unit>(adapter, "indexRuntimeBundle", arrayOf(ResourceBundle::class.java), plainBundle)
        assertEquals("bundle", readField<MutableMap<String, MutableList<ResourceFileNode>>>(adapter,
            "resourceFilesByPath").getValue(plainBundle.javaClass.name).single().format)    }
}
