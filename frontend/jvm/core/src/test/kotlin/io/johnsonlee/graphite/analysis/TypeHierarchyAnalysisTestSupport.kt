package io.johnsonlee.graphite.analysis

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.BeforeTest

internal abstract class TypeHierarchyAnalysisTestSupport {
    @BeforeTest
    fun resetNodeId() {
        NodeId.reset()
    }

    protected val userType = TypeDescriptor("com.example.User")

    protected val addressType = TypeDescriptor("com.example.Address")

    protected val responseType = TypeDescriptor("com.example.ApiResponse")

    protected val objectType = TypeDescriptor("java.lang.Object")

    protected val stringType = TypeDescriptor("java.lang.String")

    protected val intType = TypeDescriptor("int")

    protected val voidType = TypeDescriptor("void")

    protected fun method(className: String, name: String, returnType: TypeDescriptor = voidType): MethodDescriptor {
        return MethodDescriptor(TypeDescriptor(className), name, emptyList(), returnType)
    }

    /**
     * Build a graph that simulates:
     * ```
     * ApiResponse createResponse() {
     *     User user = new User();
     *     user.setName("test");
     *     ApiResponse response = new ApiResponse();
     *     response.setData(user);
     *     return response;
     * }
     * ```
     */
    protected fun buildResponseGraph(): DefaultGraph.Builder {
        val builder = DefaultGraph.Builder()

        val createMethod = method("com.example.Controller", "createResponse", responseType)
        builder.addMethod(createMethod)

        // User local variable
        val userVarId = NodeId.next()
        val userVar = LocalVariable(userVarId, "user", userType, createMethod)
        builder.addNode(userVar)

        // Response local variable
        val responseVarId = NodeId.next()
        val responseVar = LocalVariable(responseVarId, "response", responseType, createMethod)
        builder.addNode(responseVar)

        // Return node
        val returnId = NodeId.next()
        val returnNode = ReturnNode(returnId, createMethod)
        builder.addNode(returnNode)

        // DataFlow: responseVar -> returnNode
        builder.addEdge(DataFlowEdge(responseVarId, returnId, DataFlowKind.ASSIGN))

        // Constructor call for User: new User()
        val userCtorMethod = MethodDescriptor(userType, "<init>", emptyList(), voidType)
        val userCtorId = NodeId.next()
        val userCtor = CallSiteNode(userCtorId, createMethod, userCtorMethod, 10, null, emptyList())
        builder.addNode(userCtor)

        // Constructor call for ApiResponse: new ApiResponse()
        val responseCtorMethod = MethodDescriptor(responseType, "<init>", emptyList(), voidType)
        val responseCtorId = NodeId.next()
        val responseCtor = CallSiteNode(responseCtorId, createMethod, responseCtorMethod, 12, null, emptyList())
        builder.addNode(responseCtor)

        // Setter call: response.setData(user)
        val setDataMethod = MethodDescriptor(responseType, "setData", listOf(objectType), voidType)
        val setDataId = NodeId.next()
        val setData = CallSiteNode(setDataId, createMethod, setDataMethod, 13, responseVarId, listOf(userVarId))
        builder.addNode(setData)

        // User field: name (String)
        val userNameFieldDesc = FieldDescriptor(userType, "name", stringType)
        val userNameFieldId = NodeId.next()
        val userNameField = FieldNode(userNameFieldId, userNameFieldDesc, isStatic = false)
        builder.addNode(userNameField)

        // ApiResponse field: data (Object)
        val dataFieldDesc = FieldDescriptor(responseType, "data", objectType)
        val dataFieldId = NodeId.next()
        val dataField = FieldNode(dataFieldId, dataFieldDesc, isStatic = false)
        builder.addNode(dataField)

        // DataFlow: userVar -> dataField (field store)
        builder.addEdge(DataFlowEdge(userVarId, dataFieldId, DataFlowKind.FIELD_STORE))

        // Setter call for User.setName
        val setNameMethod = MethodDescriptor(userType, "setName", listOf(stringType), voidType)
        val nameArgId = NodeId.next()
        val nameArg = LocalVariable(nameArgId, "nameVal", stringType, createMethod)
        builder.addNode(nameArg)
        val setNameId = NodeId.next()
        val setName = CallSiteNode(setNameId, createMethod, setNameMethod, 11, userVarId, listOf(nameArgId))
        builder.addNode(setName)

        // Getter method: User.getName()
        val getNameMethod = method("com.example.User", "getName", stringType)
        builder.addMethod(getNameMethod)

        // Getter method: ApiResponse.getData()
        val getDataMethod = method("com.example.ApiResponse", "getData", objectType)
        builder.addMethod(getDataMethod)

        return builder
    }
}
