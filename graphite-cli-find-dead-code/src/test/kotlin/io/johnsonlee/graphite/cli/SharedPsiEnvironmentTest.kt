package io.johnsonlee.graphite.cli

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class SharedPsiEnvironmentTest {

    @Test
    fun `project is created successfully`() {
        val project = SharedPsiEnvironment.project
        assertNotNull(project)
    }

    @Test
    fun `project returns same instance on repeated access`() {
        val first = SharedPsiEnvironment.project
        val second = SharedPsiEnvironment.project
        assertSame(first, second)
    }
}
