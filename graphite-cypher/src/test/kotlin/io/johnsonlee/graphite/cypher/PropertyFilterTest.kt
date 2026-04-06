package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.*
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PropertyFilterTest {

    @Before
    fun setup() {
        NodeId.reset()
    }

    private fun filter(prop: String, op: FilterOperator, value: Any?) = PropertyFilter(
        property = prop, operator = op, value = value,
        ownerLabels = emptySet(), variable = "n"
    )

    @Test
    fun `EQUALS matches same value`() {
        val node = IntConstant(NodeId.next(), 42)
        assertTrue(filter("value", FilterOperator.EQUALS, 42).matches(node))
    }

    @Test
    fun `EQUALS handles numeric type coercion`() {
        val node = IntConstant(NodeId.next(), 42)
        // Number from parser might be Long or Double
        assertTrue(filter("value", FilterOperator.EQUALS, 42L).matches(node))
        assertTrue(filter("value", FilterOperator.EQUALS, 42.0).matches(node))
    }

    @Test
    fun `EQUALS does not match different value`() {
        val node = IntConstant(NodeId.next(), 42)
        assertFalse(filter("value", FilterOperator.EQUALS, 7).matches(node))
    }

    @Test
    fun `NOT_EQUALS`() {
        val node = IntConstant(NodeId.next(), 42)
        assertTrue(filter("value", FilterOperator.NOT_EQUALS, 7).matches(node))
        assertFalse(filter("value", FilterOperator.NOT_EQUALS, 42).matches(node))
    }

    @Test
    fun `LESS_THAN`() {
        val node = IntConstant(NodeId.next(), 42)
        assertTrue(filter("value", FilterOperator.LESS_THAN, 100).matches(node))
        assertFalse(filter("value", FilterOperator.LESS_THAN, 42).matches(node))
        assertFalse(filter("value", FilterOperator.LESS_THAN, 10).matches(node))
    }

    @Test
    fun `GREATER_THAN`() {
        val node = IntConstant(NodeId.next(), 42)
        assertTrue(filter("value", FilterOperator.GREATER_THAN, 10).matches(node))
        assertFalse(filter("value", FilterOperator.GREATER_THAN, 42).matches(node))
    }

    @Test
    fun `LESS_THAN_OR_EQUAL`() {
        val node = IntConstant(NodeId.next(), 42)
        assertTrue(filter("value", FilterOperator.LESS_THAN_OR_EQUAL, 42).matches(node))
        assertTrue(filter("value", FilterOperator.LESS_THAN_OR_EQUAL, 100).matches(node))
        assertFalse(filter("value", FilterOperator.LESS_THAN_OR_EQUAL, 10).matches(node))
    }

    @Test
    fun `GREATER_THAN_OR_EQUAL`() {
        val node = IntConstant(NodeId.next(), 42)
        assertTrue(filter("value", FilterOperator.GREATER_THAN_OR_EQUAL, 42).matches(node))
        assertTrue(filter("value", FilterOperator.GREATER_THAN_OR_EQUAL, 10).matches(node))
        assertFalse(filter("value", FilterOperator.GREATER_THAN_OR_EQUAL, 100).matches(node))
    }

    @Test
    fun `REGEX matches`() {
        val type = TypeDescriptor("com.example.Service")
        val stringType = TypeDescriptor("java.lang.String")
        val node = FieldNode(NodeId.next(), FieldDescriptor(type, "name", stringType), false)
        assertTrue(filter("class", FilterOperator.REGEX, "com\\.example\\..*").matches(node))
        assertFalse(filter("class", FilterOperator.REGEX, "org\\.other\\..*").matches(node))
    }

    @Test
    fun `STARTS_WITH`() {
        val node = StringConstant(NodeId.next(), "hello world")
        assertTrue(filter("value", FilterOperator.STARTS_WITH, "hello").matches(node))
        assertFalse(filter("value", FilterOperator.STARTS_WITH, "world").matches(node))
    }

    @Test
    fun `ENDS_WITH`() {
        val node = StringConstant(NodeId.next(), "hello world")
        assertTrue(filter("value", FilterOperator.ENDS_WITH, "world").matches(node))
        assertFalse(filter("value", FilterOperator.ENDS_WITH, "hello").matches(node))
    }

    @Test
    fun `CONTAINS`() {
        val node = StringConstant(NodeId.next(), "hello world")
        assertTrue(filter("value", FilterOperator.CONTAINS, "lo wo").matches(node))
        assertFalse(filter("value", FilterOperator.CONTAINS, "xyz").matches(node))
    }

    @Test
    fun `EQUALS with null`() {
        val node = NullConstant(NodeId.next())
        // null == null should be true
        assertTrue(filter("value", FilterOperator.EQUALS, null).matches(node))
    }

    @Test
    fun `comparison with non-numeric property returns false`() {
        val node = StringConstant(NodeId.next(), "hello")
        assertFalse(filter("value", FilterOperator.GREATER_THAN, 10).matches(node))
    }

    @Test
    fun `REGEX with null actual returns false`() {
        val node = NullConstant(NodeId.next())
        assertFalse(filter("value", FilterOperator.REGEX, ".*").matches(node))
    }

    @Test
    fun `REGEX with null pattern returns false`() {
        val node = StringConstant(NodeId.next(), "hello")
        assertFalse(filter("value", FilterOperator.REGEX, null).matches(node))
    }

    @Test
    fun `STARTS_WITH with null actual returns false`() {
        val node = NullConstant(NodeId.next())
        assertFalse(filter("value", FilterOperator.STARTS_WITH, "hello").matches(node))
    }

    @Test
    fun `ENDS_WITH with null actual returns false`() {
        val node = NullConstant(NodeId.next())
        assertFalse(filter("value", FilterOperator.ENDS_WITH, "hello").matches(node))
    }

    @Test
    fun `CONTAINS with null actual returns false`() {
        val node = NullConstant(NodeId.next())
        assertFalse(filter("value", FilterOperator.CONTAINS, "hello").matches(node))
    }

    @Test
    fun `STARTS_WITH with null pattern returns false`() {
        val node = StringConstant(NodeId.next(), "hello")
        assertFalse(filter("value", FilterOperator.STARTS_WITH, null).matches(node))
    }

    @Test
    fun `ENDS_WITH with null pattern returns false`() {
        val node = StringConstant(NodeId.next(), "hello")
        assertFalse(filter("value", FilterOperator.ENDS_WITH, null).matches(node))
    }

    @Test
    fun `CONTAINS with null pattern returns false`() {
        val node = StringConstant(NodeId.next(), "hello")
        assertFalse(filter("value", FilterOperator.CONTAINS, null).matches(node))
    }
}
