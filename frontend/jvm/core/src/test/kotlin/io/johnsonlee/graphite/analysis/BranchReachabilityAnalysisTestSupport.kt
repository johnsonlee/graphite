package io.johnsonlee.graphite.analysis

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import kotlin.test.BeforeTest

internal abstract class BranchReachabilityAnalysisTestSupport {
    @BeforeTest
    fun resetNodeId() {
        NodeId.reset()
    }

    protected fun makeMethod(className: String = "com.example.Test", name: String = "test"): MethodDescriptor {
        return MethodDescriptor(TypeDescriptor(className), name, emptyList(), TypeDescriptor("void"))
    }
}
