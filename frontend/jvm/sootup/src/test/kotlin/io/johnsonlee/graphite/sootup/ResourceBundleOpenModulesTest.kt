package io.johnsonlee.graphite.sootup

import java.util.Collections
import java.util.ResourceBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Runs only in resourceBundleOpenModulesTest; the ordinary suite keeps java.util closed. */
internal class ResourceBundleOpenModulesTest : AdapterTestFixtures() {
    @Test
    fun `opened module reveals the actual bundle parent and preserves inherited lookups`() {
        assertTrue(ResourceBundle::class.java.module.isOpen("java.util", javaClass.module))
        val parent = Bundle(mapOf("inherited" to "parent-value"))
        val child = Bundle(mapOf("local" to "child-value"), parent)
        val control = createAdapter().bundleControl
        assertSame(parent, control.bundleParent(child))
        assertNull(control.bundleParent(parent))
        assertEquals("parent-value", child.getString("inherited"))
        assertEquals("child-value", child.getString("local"))
    }

    private class Bundle(private val values: Map<String, String>, inherited: ResourceBundle? = null) : ResourceBundle() {
        init { setParent(inherited) }
        override fun handleGetObject(key: String): Any? = values[key]
        override fun getKeys() = Collections.enumeration(values.keys)
    }
}
