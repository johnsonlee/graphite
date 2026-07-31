package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }
}
