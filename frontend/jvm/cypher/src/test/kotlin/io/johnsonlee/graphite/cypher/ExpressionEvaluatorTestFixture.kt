package io.johnsonlee.graphite.cypher

import java.util.AbstractSequentialList
import java.util.LinkedList
import org.junit.Before

abstract class ExpressionEvaluatorTestFixture {

    internal lateinit var evaluator: ExpressionEvaluator

    @Before
    fun setup() {
        evaluator = ExpressionEvaluator()
    }

    internal fun eval(expr: CypherExpr, bindings: Map<String, Any?> = emptyMap()): Any? = evaluator.evaluate(expr, bindings)

    internal fun lit(value: Any?) = CypherExpr.Literal(value)

    internal fun variable(name: String) = CypherExpr.Variable(name)
}

internal class CollectionOperationCancelled : RuntimeException()

internal class EvaluatorTrackingSequentialList<T>(values: Collection<T>) : AbstractSequentialList<T>() {
    private val delegate = LinkedList(values)
    var listIteratorCalls: Int = 0
        private set

    override val size: Int
        get() = delegate.size

    override fun listIterator(index: Int): MutableListIterator<T> {
        listIteratorCalls++
        return delegate.listIterator(index)
    }
}
