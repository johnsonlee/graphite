package io.johnsonlee.graphite.sootup

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import sootup.core.model.SourceType
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation
import sootup.java.core.GraphiteIdentifierFactory
import sootup.java.core.JavaIdentifierFactory
import sootup.java.core.views.JavaView

class GraphiteJavaViewTest {
    private val root = Files.createTempDirectory("graphite-view-factory")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `parsed locations get independent view caches without changing canonical types`() {
        val firstLocation = ParsedClassLocation(root, SourceType.Application, emptyList())
        val secondLocation = ParsedClassLocation(root, SourceType.Library, emptyList())
        val first = createJavaView(listOf(firstLocation))
        val second = createJavaView(listOf(secondLocation))
        assertIs<GraphiteJavaView>(first)
        assertIs<GraphiteIdentifierFactory>(first.identifierFactory)
        assertIs<GraphiteIdentifierFactory>(second.identifierFactory)
        assertNotSame(first.identifierFactory, second.identifierFactory)
        val canonical = JavaIdentifierFactory.getInstance().getClassType(OWNER)
        assertSame(canonical, first.identifierFactory.getClassType(OWNER))
        assertSame(canonical, second.identifierFactory.getClassType(OWNER))
        assertEquals(emptyList(), first.classes.toList())
        assertEquals(emptyList(), second.classes.toList())
    }

    @Test
    fun `other and mixed input locations keep the original JavaView factory`() {
        val parsed = ParsedClassLocation(root, SourceType.Application, emptyList())
        val other = PathBasedAnalysisInputLocation.create(root, SourceType.Library)
        for (locations in listOf(listOf(other), listOf(parsed, other))) {
            val view = createJavaView(locations)
            assertEquals(JavaView::class.java, view.javaClass)
            assertSame(JavaIdentifierFactory.getInstance(), view.identifierFactory)
        }
    }

    private companion object {
        const val OWNER = "sample.cached.ViewOwner"
    }
}
