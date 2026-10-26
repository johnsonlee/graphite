package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class ArrayLocalPersistenceTest {
    @Test
    fun `bytecode allocation local rank and complete identity survive both load modes`() {
        val directory = Files.createTempDirectory("array-local-migration")
        try {
            val input = Files.createDirectories(directory.resolve("input/p"))
            Files.write(input.resolve("Arrays.class"), classBytes())
            val graph = JavaProjectLoader(LoaderConfig(
                buildCallGraph = false,
                extractAnnotations = false,
                trackCrossMethodFunctionalDispatch = false
            )).load(input.parent)
            val before = allocationLocals(graph)
            val output = directory.resolve("graph")
            GraphStore.save(graph, output)
            for (mode in listOf(GraphStore.LoadMode.EAGER, GraphStore.LoadMode.MAPPED)) {
                val restored = GraphStore.load(output, mode)
                try {
                    // Compare complete Local values, including ID, name and caller;
                    // expected array rank comes from hand-authored bytecode below.
                    assertEquals(before, allocationLocals(restored), mode.name)
                } finally {
                    (restored as? AutoCloseable)?.close()
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun allocationLocals(graph: Graph): Map<String, LocalVariable> {
        val sites = graph.nodes(CallSiteNode::class.java).filter { it.caller.name == "build" }.toList()
        assertEquals(EXPECTED_TYPES.keys, sites.map { it.callee.name }.toSet())
        assertEquals(EXPECTED_TYPES.size, sites.size)
        return sites.associate { site ->
            val expected = assertNotNull(EXPECTED_TYPES[site.callee.name])
            assertEquals("p.Arrays", site.caller.declaringClass.className)
            assertEquals("p.Arrays", site.callee.declaringClass.className)
            assertEquals("()V", site.caller.descriptor)
            assertEquals(listOf(expected), site.callee.parameterTypes.map { it.className })
            val local = assertIs<LocalVariable>(graph.node(site.arguments.single()))
            assertEquals(site.caller, local.method)
            assertEquals(expected, local.type.className)
            site.callee.name to local
        }
    }

    private fun classBytes(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "p/Arrays", null, "java/lang/Object", null)
        val build = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "build", "()V", null, null)
        build.visitCode()
        for ((method, descriptor) in ARRAY_DESCRIPTORS) {
            val dimensions = descriptor.takeWhile { it == '[' }.length
            repeat(dimensions) { build.visitInsn(Opcodes.ICONST_1) }
            if (dimensions > 1) {
                build.visitMultiANewArrayInsn(descriptor, dimensions)
            } else if (descriptor == "[B") {
                build.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE)
            } else {
                build.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
            }
            build.visitMethodInsn(Opcodes.INVOKESTATIC, "p/Arrays", method, "($descriptor)V", false)
            val sink = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, method, "($descriptor)V", null, null)
            sink.visitCode()
            sink.visitInsn(Opcodes.RETURN)
            sink.visitMaxs(0, 0)
            sink.visitEnd()
        }
        build.visitInsn(Opcodes.RETURN)
        build.visitMaxs(0, 0)
        build.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private companion object {
        val ARRAY_DESCRIPTORS = linkedMapOf(
            "byte1" to "[B", "byte2" to "[[B", "byte3" to "[[[B",
            "string1" to "[Ljava/lang/String;", "string2" to "[[Ljava/lang/String;", "string3" to "[[[Ljava/lang/String;"
        )
        val EXPECTED_TYPES = linkedMapOf(
            "byte1" to "byte[]", "byte2" to "byte[][]", "byte3" to "byte[][][]",
            "string1" to "java.lang.String[]", "string2" to "java.lang.String[][]", "string3" to "java.lang.String[][][]"
        )
    }
}
