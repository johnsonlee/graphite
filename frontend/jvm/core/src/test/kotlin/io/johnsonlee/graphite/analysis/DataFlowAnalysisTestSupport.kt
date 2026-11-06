package io.johnsonlee.graphite.analysis

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import kotlin.test.BeforeTest

internal abstract class DataFlowAnalysisTestSupport {
    @BeforeTest
    fun resetNodeId() {
        NodeId.reset()
    }

    protected fun makeMethod(name: String = "test"): MethodDescriptor {
        return MethodDescriptor(TypeDescriptor("com.example.Test"), name, emptyList(), TypeDescriptor("void"))
    }
}
