package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.ResourceEntry
import java.io.IOException
import java.io.InputStream
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import sootup.core.model.MethodModifier
import sootup.core.model.SootClass
import sootup.java.core.types.JavaClassType

internal class AdapterFallbackContractTest : AdapterTestFixtures() {
    @Test
    fun `foreign control sources use resource bytes and missing bytes do not invent policy`() {
        val names = listOf("sample.ForeignControl", "sample.MissingControl")
        val owners = names.map { name ->
            object : FakeSootClass(identifierFactory.getClassType(name)) {
                override fun getSuperclass(): Optional<JavaClassType> =
                    Optional.of(identifierFactory.getClassType("java.util.ResourceBundle\$Control"))
            }
        }
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/ForeignControl", null, "java/util/ResourceBundle\$Control", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC, "getFallbackLocale", "(Ljava/lang/String;Ljava/util/Locale;)Ljava/util/Locale;", null, null)
            .apply { visitCode(); visitInsn(Opcodes.ACONST_NULL); visitInsn(Opcodes.ARETURN); visitMaxs(1, 3); visitEnd() }
        writer.visitEnd()
        val opened = mutableListOf<String>()
        val resources = object : ResourceAccessor {
            override fun list(pattern: String): Sequence<ResourceEntry> = emptySequence()
            override fun open(path: String): InputStream {
                opened += path
                if (path == "sample/ForeignControl.class") return writer.toByteArray().inputStream()
                throw IOException("control bytecode unavailable")
            }
        }
        val adapter = SootUpAdapter(FakeView(owners, identifierFactory), LoaderConfig(buildCallGraph = false),
            extensions = emptyList(), inputLocationSources = emptyMap(), resourceAccessor = resources)
        val policy = assertNotNull(adapter.bundleControl.resolveBundleControlSpec(names[0]))
        assertTrue(policy.noFallback)
        assertEquals(setOf(RESOURCE_BUNDLE_FORMAT_CLASS, RESOURCE_BUNDLE_FORMAT_PROPERTIES), policy.formats)
        assertNull(adapter.bundleControl.resolveBundleControlSpec(names[1]), "unknown policy must remain unknown")
        assertEquals(listOf("sample/ForeignControl.class", "sample/MissingControl.class"), opened)
    }

    @Test
    fun `foreign declarations retain method identity and unreadable dependency bytes remain unindexed`() {
        val type = identifierFactory.getClassType("sample.Foreign")
        val signature = identifierFactory.getMethodSignature(type, "lookup", "java.lang.String", listOf("int"))
        val owner = FakeSootClass(type, methods = setOf(FakeSootMethod(signature)))
        val foreign = object : SootClass by owner {}
        val logs = mutableListOf<String>()
        val adapter = SootUpAdapter(FakeView(listOf(foreign), identifierFactory),
            LoaderConfig(buildCallGraph = false, verbose = logs::add), extensions = emptyList(), inputLocationSources = emptyMap())
        assertEquals(setOf(signature.subSignature.toString()),
            adapter.resources.collectDeclaredMethodSubSignatures(type.fullyQualifiedName))
        adapter.resources.indexArtifactDependency(owner, "foreign.jar")
        assertTrue(adapter.artifactDependenciesByArtifact.isEmpty(), "failed bytecode reads cannot fabricate dependency edges")
        assertTrue(logs.single().contains("Failed to extract artifact dependencies from foreign.jar!/sample.Foreign"))
    }

    @Test
    fun `failed synthetic rendering does not kill the worker or discard later valid fingerprints`() {
        val logs = mutableListOf<String>()
        val adapter = SootUpAdapter(FakeView(emptyList(), identifierFactory),
            LoaderConfig(buildCallGraph = false, verbose = logs::add), extensions = emptyList(), inputLocationSources = emptyMap())
        val type = identifierFactory.getClassType("sample.Owner")
        val broken = object : FakeSootMethod(identifierFactory.getMethodSignature(type, "lambda\$broken\$0", "void", emptyList())) {
            override fun getModifiers(): Set<MethodModifier> = error("foreign method modifiers unavailable")
        }
        val good = object : FakeSootMethod(identifierFactory.getMethodSignature(type, "lambda\$good\$1", "void", emptyList())) {
            override fun hasBody(): Boolean = false
        }
        adapter.syntheticIdentities.addMethod(broken, "broken", true)
        adapter.syntheticIdentities.addMethod(good, "survivor", true)
        val graph = adapter.buildGraph()
        assertEquals(1, adapter.syntheticIdentities.skipped)
        assertEquals(setOf("survivor"), graph.syntheticIdentities().keys)
        assertTrue(graph.syntheticIdentities().getValue("survivor").isNotBlank())
        assertTrue(logs.contains("Skipped 1 synthetic member(s) whose identity could not be rendered"))
    }
}
