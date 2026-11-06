package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.FloatConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import org.junit.Before

abstract class CypherCompatibilityTestFixture {

    internal lateinit var executor: CypherExecutor

    internal lateinit var graph: io.johnsonlee.graphite.graph.Graph

    internal var intConst42 = NodeId(0)

    internal var intConst7 = NodeId(0)

    internal var strConstHello = NodeId(0)

    internal var callSite1 = NodeId(0)

    internal var callSite2 = NodeId(0)

    internal var field1 = NodeId(0)

    internal var param1 = NodeId(0)

    internal var return1 = NodeId(0)

    internal var localVar1 = NodeId(0)

    internal var localVar2 = NodeId(0)

    internal var boolConst = NodeId(0)

    internal var enumConst = NodeId(0)

    internal var longConst = NodeId(0)

    internal var floatConst = NodeId(0)

    internal var doubleConst = NodeId(0)

    internal var nullConst = NodeId(0)

    @Before
    fun setup() {
        NodeId.reset()

        val builder = DefaultGraph.Builder()

        val type = TypeDescriptor("com.example.Service")
        val intType = TypeDescriptor("int")
        val stringType = TypeDescriptor("java.lang.String")
        val enumType = TypeDescriptor("com.example.Status")
        val method = MethodDescriptor(type, "process", listOf(intType), stringType)
        val callee = MethodDescriptor(TypeDescriptor("com.example.Repository"), "save", listOf(stringType), TypeDescriptor("void"))
        val callee2 = MethodDescriptor(TypeDescriptor("com.example.Logger"), "log", listOf(stringType), TypeDescriptor("void"))

        intConst42 = NodeId.next()
        builder.addNode(IntConstant(intConst42, 42))

        intConst7 = NodeId.next()
        builder.addNode(IntConstant(intConst7, 7))

        strConstHello = NodeId.next()
        builder.addNode(StringConstant(strConstHello, "hello"))

        callSite1 = NodeId.next()
        builder.addNode(CallSiteNode(callSite1, method, callee, 10, null, listOf(strConstHello)))

        callSite2 = NodeId.next()
        builder.addNode(CallSiteNode(callSite2, method, callee2, 20, null, listOf(strConstHello)))

        field1 = NodeId.next()
        builder.addNode(FieldNode(field1, FieldDescriptor(type, "name", stringType), false))

        param1 = NodeId.next()
        builder.addNode(ParameterNode(param1, 0, intType, method))

        return1 = NodeId.next()
        builder.addNode(ReturnNode(return1, method, stringType))

        localVar1 = NodeId.next()
        builder.addNode(LocalVariable(localVar1, "x", intType, method))

        localVar2 = NodeId.next()
        builder.addNode(LocalVariable(localVar2, "y", stringType, method))

        boolConst = NodeId.next()
        builder.addNode(BooleanConstant(boolConst, true))

        enumConst = NodeId.next()
        builder.addNode(EnumConstant(enumConst, enumType, "ACTIVE", listOf("active")))

        longConst = NodeId.next()
        builder.addNode(LongConstant(longConst, 999999999999L))

        floatConst = NodeId.next()
        builder.addNode(FloatConstant(floatConst, 3.14f))

        doubleConst = NodeId.next()
        builder.addNode(DoubleConstant(doubleConst, 2.718))

        nullConst = NodeId.next()
        builder.addNode(NullConstant(nullConst))

        // Edges
        builder.addEdge(DataFlowEdge(intConst42, param1, DataFlowKind.PARAMETER_PASS))
        builder.addEdge(DataFlowEdge(param1, localVar1, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(localVar1, localVar2, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(localVar2, callSite1, DataFlowKind.PARAMETER_PASS))
        builder.addEdge(DataFlowEdge(strConstHello, callSite1, DataFlowKind.PARAMETER_PASS))
        builder.addEdge(DataFlowEdge(strConstHello, callSite2, DataFlowKind.PARAMETER_PASS))
        builder.addEdge(CallEdge(callSite1, return1, false))
        builder.addEdge(DataFlowEdge(field1, localVar1, DataFlowKind.FIELD_LOAD))

        builder.addMethod(method)
        builder.addMethod(callee)
        builder.addMethod(callee2)

        graph = builder.build()
        executor = CypherExecutor(graph)
    }
}
