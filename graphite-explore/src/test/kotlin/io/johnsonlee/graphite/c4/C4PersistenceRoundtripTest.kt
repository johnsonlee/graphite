package io.johnsonlee.graphite.c4

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.webgraph.GraphStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies that synthetic C4 tags injected by [C4TaggedGraph] survive a
 * [GraphStore.save] / [GraphStore.load] round-trip — i.e. the build-time
 * decision is faithfully persisted into saved-graph metadata.
 */
class C4PersistenceRoundtripTest {

    @Test
    fun `synthetic stereotype and external-system tags survive save and load`() {
        val service = TypeDescriptor("com.example.svc.UserService")
        val jdbc = TypeDescriptor("java.sql.Connection")
        val serviceMethod = MethodDescriptor(service, "find", emptyList(), TypeDescriptor("void"))
        val jdbcMethod = MethodDescriptor(jdbc, "prepareStatement", listOf(TypeDescriptor("java.lang.String")), TypeDescriptor("java.sql.PreparedStatement"))

        val builder = DefaultGraph.Builder()
        builder.addNode(CallSiteNode(NodeId.next(), serviceMethod, jdbcMethod, 1, null, emptyList()))
        builder.addMemberAnnotation(
            service.className, "<class>",
            "org.springframework.stereotype.Service", emptyMap()
        )
        val tagged = C4TaggedGraph(builder.build())

        val dir = Files.createTempDirectory("c4-roundtrip")
        try {
            GraphStore.save(tagged, dir)
            val loaded = GraphStore.load(dir)

            val serviceAnnotations = loaded.memberAnnotations(service.className, "<class>")
            assertEquals(C4Tags.Stereotype.SERVICE, serviceAnnotations[C4Tags.STEREOTYPE_ANNOTATION]?.get(C4Tags.VALUE_ATTRIBUTE))

            val jdbcAnnotations = loaded.memberAnnotations(jdbc.className, "<class>")
            assertEquals(C4Tags.ExternalSystem.DATABASE, jdbcAnnotations[C4Tags.EXTERNAL_SYSTEM_ANNOTATION]?.get(C4Tags.VALUE_ATTRIBUTE))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
