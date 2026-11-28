package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.GraphiteContext
import io.johnsonlee.graphite.sootup.GraphiteExtension
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import sootup.core.model.SootClass
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * An extension found through `ServiceLoader`, as a real one is: what it records must read the same from the graph's member
 * metadata and from its `Annotation` nodes, in the build's graph and after a save and a load (review of #229: a second write
 * of one name replaced the metadata entry and added a second node, so the two disagreed).
 */
class ExtensionAnnotationPersistenceTest {
    private val root = Files.createTempDirectory("extension-annotations")

    @AfterTest
    fun cleanup() {
        ScriptedExtension.script = { _, _ -> }
        root.toFile().deleteRecursively()
    }

    @Test
    fun `default builder, metadata and Annotation nodes agree after a save and a load`() = agreement(mmap = false)

    @Test
    fun `mmap builder, metadata and Annotation nodes agree after a save and a load`() = agreement(mmap = true)

    private fun agreement(mmap: Boolean) {
        val refusals = mutableListOf<String?>()
        ScriptedExtension.script = { sootClass, context ->
            if (sootClass.type.fullyQualifiedName == MIXING) {
                context.addMemberAnnotation(MIXING, "run", ROUTE, mapOf("value" to "/first"))
                // The reviewer's reproducer: the same key again.
                refusals += assertFailsWith<IllegalArgumentException> {
                    context.addMemberAnnotation(MIXING, "run", ROUTE, mapOf("value" to "/second"))
                }.message
                // Enriching an annotation the bytecode carries: refused too; the added fact gets a name of its own.
                refusals += assertFailsWith<IllegalArgumentException> {
                    context.addMemberAnnotation(MIXING, "carried", ROUTE, mapOf("value" to "/enriched"))
                }.message
                context.addMemberAnnotation(MIXING, "carried", "audit.ResolvedRoute", mapOf("value" to "/enriched"))
                context.addMemberAnnotation(MIXING, GraphiteContext.CLASS_MEMBER, "audit.Controller")
            }
        }
        val built = withExtension {
            JavaProjectLoader(LoaderConfig(buildCallGraph = false, includePackages = listOf("audit")), useMmapBuilder = mmap)
                .load(compile())
        }
        val expected = setOf(
            listOf(MIXING, "run", ROUTE, "/first"),
            listOf(MIXING, "carried", ROUTE, "/bytecode"),
            listOf(MIXING, "carried", "audit.ResolvedRoute", "/enriched"),
            listOf(MIXING, "<class>", "audit.Controller", null),
        )
        try {
            assertEquals(2, refusals.size)
            assertTrue(refusals[0]!!.startsWith("An extension already recorded annotation 'audit.Route' on '$MIXING#run'"), refusals[0])
            assertTrue(refusals[1]!!.contains("'$MIXING#carried', which the bytecode already carries"), refusals[1])
            assertAgreement(expected, built)
            val saved = root.resolve("graph")
            GraphStore.save(built, saved)
            val loaded = GraphStore.loadMapped(saved)
            try {
                assertAgreement(expected, loaded)
            } finally {
                (loaded as? AutoCloseable)?.close()
            }
        } finally {
            (built as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `an annotation recorded on another class whose bytecode carries it fails the build`() {
        // Whichever of the two classes the build reads first: the write is refused at the call when the carrier was read
        // already, and when the build commits the extensions' annotations when it had not been.
        ScriptedExtension.script = { sootClass, context ->
            if (sootClass.type.fullyQualifiedName == "audit.Other") {
                context.addMemberAnnotation(MIXING, "carried", ROUTE, mapOf("value" to "/elsewhere"))
            }
        }
        val failure = assertFailsWith<RuntimeException> {
            withExtension {
                JavaProjectLoader(LoaderConfig(buildCallGraph = false, includePackages = listOf("audit")), useMmapBuilder = false)
                    .load(compile())
            }
        }
        assertTrue(
            generateSequence<Throwable>(failure) { it.cause }
                .any { it.message.orEmpty().contains("'$MIXING#carried', which the bytecode already carries") },
            failure.stackTraceToString()
        )
    }

    /** The audit annotations of [graph], read from its member metadata and from its `Annotation` nodes: one fact, two views. */
    private fun assertAgreement(expected: Set<List<String?>>, graph: Graph) {
        val nodes = CypherExecutor(graph).execute(
            "MATCH (a:Annotation) WHERE a.name STARTS WITH 'audit.' RETURN a.class AS c, a.member AS m, a.name AS n, a.value AS v"
        ).rows.map { listOf(it["c"] as String?, it["m"] as String?, it["n"] as String?, it["v"] as String?) }
        val metadata = expected.map { it[0]!! to it[1]!! }.distinct().flatMap { (className, member) ->
            graph.memberAnnotations(className, member).filterKeys { it.startsWith("audit.") }
                .map { (name, values) -> listOf(className, member, name, values["value"] as String?) }
        }
        // As lists first: a second node for one key would be a duplicate row a set hides.
        assertEquals(expected.size, nodes.size, "Annotation nodes: $nodes")
        assertEquals(expected, nodes.toSet(), "Annotation nodes")
        assertEquals(expected, metadata.toSet(), "member metadata")
    }

    /** Runs [build] with [ScriptedExtension] registered for `ServiceLoader`, for this thread only. */
    private fun <T> withExtension(build: () -> T): T {
        val services = Files.createDirectories(root.resolve("services-${System.nanoTime()}/META-INF/services"))
        Files.writeString(services.resolve(GraphiteExtension::class.java.name), ScriptedExtension::class.java.name + "\n")
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        return URLClassLoader(arrayOf(services.parent.parent.toUri().toURL()), previous).use { loader ->
            thread.contextClassLoader = loader
            try {
                build()
            } finally {
                thread.contextClassLoader = previous
            }
        }
    }

    private fun compile(): Path {
        val sources = Files.createDirectories(root.resolve("src-${System.nanoTime()}/audit"))
        Files.writeString(sources.resolve("Route.java"), """
            package audit;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Route { String value(); }
        """.trimIndent())
        Files.writeString(sources.resolve("IdentityMixing.java"), """
            package audit;
            public class IdentityMixing {
                @Route("/bytecode") public void carried() { }
                public void run() { }
            }
        """.trimIndent())
        Files.writeString(sources.resolve("Other.java"), """
            package audit;
            public class Other { public void run() { } }
        """.trimIndent())
        val classes = Files.createDirectories(sources.parent.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-d", classes.toString(),
            *listOf("Route", "IdentityMixing", "Other").map { sources.resolve("$it.java").toString() }.toTypedArray()
        ))
        return classes
    }

    private companion object {
        const val MIXING = "audit.IdentityMixing"
        const val ROUTE = "audit.Route"
    }
}

/** The extension the tests register; each test scripts what it does on a visit. */
class ScriptedExtension : GraphiteExtension {
    override fun visit(sootClass: SootClass, context: GraphiteContext) = script(sootClass, context)

    companion object {
        @Volatile
        var script: (SootClass, GraphiteContext) -> Unit = { _, _ -> }
    }
}
