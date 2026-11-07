package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import java.util.AbstractSequentialList
import java.util.LinkedList
import org.junit.Before

abstract class CypherFunctionsTestFixture {

    internal val type = TypeDescriptor("com.example.Service")

    internal val intType = TypeDescriptor("int")

    internal val stringType = TypeDescriptor("java.lang.String")

    internal val method = MethodDescriptor(type, "process", listOf(intType), stringType)

    @Before
    fun setup() {
        NodeId.reset()
    }
}

internal class TrackingSequentialList<T>(values: List<T>) : AbstractSequentialList<T>() {
    private val delegate = LinkedList(values)
    var listIteratorCalls = 0
        private set

    override val size: Int
        get() = delegate.size

    override fun listIterator(index: Int): MutableListIterator<T> {
        listIteratorCalls++
        return delegate.listIterator(index)
    }
}

internal class CollectionFunctionCancelled : RuntimeException()

internal const val SORT_PHASE_CHECKPOINT = 15
