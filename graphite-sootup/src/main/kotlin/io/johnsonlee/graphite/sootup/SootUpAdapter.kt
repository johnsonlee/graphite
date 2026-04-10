package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.FullGraphBuilder
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.CallGraphAlgorithm
import io.johnsonlee.graphite.input.EmptyResourceAccessor
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.input.ResourceAccessor
import java.util.ServiceLoader
import sootup.core.graph.StmtGraph
import sootup.core.jimple.basic.Local
import sootup.core.jimple.basic.Value
import sootup.core.jimple.common.constant.IntConstant as SootIntConstant
import sootup.core.jimple.common.constant.LongConstant as SootLongConstant
import sootup.core.jimple.common.constant.FloatConstant as SootFloatConstant
import sootup.core.jimple.common.constant.DoubleConstant as SootDoubleConstant
import sootup.core.jimple.common.constant.NullConstant as SootNullConstant
import sootup.core.jimple.common.constant.StringConstant as SootStringConstant
import sootup.core.jimple.common.constant.Constant as SootConstant
import sootup.core.jimple.common.constant.MethodHandle
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr
import sootup.core.jimple.common.expr.AbstractInvokeExpr
import sootup.core.jimple.common.expr.JDynamicInvokeExpr
import sootup.core.jimple.common.expr.JNewExpr
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.jimple.common.ref.JFieldRef
import sootup.core.jimple.common.ref.JStaticFieldRef
import sootup.core.jimple.common.ref.JArrayRef
import sootup.core.jimple.common.ref.JParameterRef
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JIdentityStmt
import sootup.core.jimple.common.stmt.JIfStmt
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.core.jimple.common.stmt.JReturnStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.jimple.common.expr.AbstractConditionExpr
import sootup.core.jimple.common.expr.JEqExpr
import sootup.core.jimple.common.expr.JNeExpr
import sootup.core.jimple.common.expr.JLtExpr
import sootup.core.jimple.common.expr.JGeExpr
import sootup.core.jimple.common.expr.JGtExpr
import sootup.core.jimple.common.expr.JLeExpr
import sootup.core.model.SootClass
import sootup.core.model.SootMethod
import sootup.core.signatures.MethodSignature
import sootup.java.core.JavaSootClass
import sootup.java.core.JavaSootMethod
import sootup.core.types.ClassType
import sootup.core.types.ArrayType
import sootup.core.types.PrimitiveType
import sootup.core.types.Type
import sootup.core.views.View
import sootup.callgraph.CallGraph
import sootup.callgraph.ClassHierarchyAnalysisAlgorithm
import sootup.callgraph.RapidTypeAnalysisAlgorithm

/**
 * Adapter that converts SootUp's IR to Graphite's graph model.
 *
 * This is the bridge between SootUp's analysis infrastructure and
 * Graphite's unified graph representation.
 */
class SootUpAdapter(
    private val view: View,
    private val config: LoaderConfig,
    private val signatureReader: BytecodeSignatureReader? = null,
    private val extensions: List<GraphiteExtension> = ServiceLoader.load(GraphiteExtension::class.java).toList(),
    private val resourceAccessor: ResourceAccessor = EmptyResourceAccessor,
    private val graphBuilder: FullGraphBuilder = DefaultGraph.Builder()
) {

    // Maps to track created nodes for cross-referencing
    private val localNodes = mutableMapOf<String, LocalVariable>()
    private val fieldNodes = mutableMapOf<String, FieldNode>()
    private val parameterNodes = mutableMapOf<String, ParameterNode>()
    private val constantNodes = mutableMapOf<Any, ConstantNode>()
    private val methodReturnNodes = mutableMapOf<String, ReturnNode>()
    private val allocationNodes = mutableMapOf<String, LocalVariable>()

    // Maps local variable key to target methods resolved from invokedynamic
    // Key: "methodSignature#localName", same format as localNodes
    private val dynamicTargets = mutableMapOf<String, List<MethodDescriptor>>()

    // Maps method signature to dynamic targets returned by that method
    private val returnDynamicTargets = mutableMapOf<String, List<MethodDescriptor>>()

    // Maps local key to (methodSignature, paramIndex) for locals assigned from parameters
    private val localToParamIndex = mutableMapOf<String, Pair<String, Int>>()

    // Tracks call sites where an argument has dynamic targets
    // Key: callee method signature, Value: list of (argIndex, targets) pairs
    private val callSiteDynamicArgs = mutableMapOf<String, MutableList<Pair<Int, List<MethodDescriptor>>>>()

    // Tracks virtual calls on parameters within a method
    // Key: method signature, Value: list of (paramIndex, callSiteNode) pairs
    private val parameterVirtualCalls = mutableMapOf<String, MutableList<Pair<Int, CallSiteNode>>>()

    // Tracks call result locals: callee method signature -> list of caller local keys
    private val callResultLocals = mutableMapOf<String, MutableList<String>>()

    // Tracks virtual calls on locals that have no dynamic targets yet
    // (for resolution after return value propagation)
    // Key: local key ("methodSig#localName"), Value: list of CallSiteNodes
    private val unresolvedLocalVirtualCalls = mutableMapOf<String, MutableList<CallSiteNode>>()

    // Maps field key (field signature string) to dynamic targets assigned to that field
    // Enables resolution when a lambda/method reference is stored to a field and later invoked
    private val fieldDynamicTargets = mutableMapOf<String, MutableList<MethodDescriptor>>()

    // Maps array local key to dynamic targets stored in that array (via ARRAY_STORE)
    // Enables resolution when lambdas/method references are passed as varargs
    private val arrayDynamicTargets = mutableMapOf<String, MutableList<MethodDescriptor>>()

    // Tracks locals that were loaded from a field but had no dynamic targets at load time
    // Key: field key (field signature string), Value: list of local keys that loaded from this field
    // Resolved in resolveFunctionalDispatch after all methods have been processed
    private val fieldLoadLocals = mutableMapOf<String, MutableList<String>>()

    // Per-method: tracks which NodeIds were created from each statement
    // Reset per method in processMethod()
    private var stmtNodeIds = mutableMapOf<Stmt, MutableList<NodeId>>()

    /**
     * Build the complete graph from the SootUp view
     */
    fun buildGraph(): Graph {
        // Pass 1: All classes — type hierarchy + enum values
        // Enum values must be fully collected before processing methods
        // (a method in class A may reference an enum from class B)
        view.classes.forEach { sootClass ->
            processTypeHierarchyForClass(sootClass)
            if (sootClass.isEnum) {
                extractEnumValues(sootClass)
            }
        }

        // Pass 2: Filtered classes — methods + fields + extensions
        // Methods before fields (fields check fieldNodes map for duplicates)
        val extensionContext = GraphiteContext(
            methodDescriptorFactory = ::toMethodDescriptor,
            logger = ::log,
            resources = resourceAccessor
        )
        view.classes
            .filter { shouldIncludeClass(it) }
            .forEach { sootClass ->
                sootClass.methods.forEach { method ->
                    processMethod(method)
                }
                visitFieldsForClass(sootClass)
                // Extract annotations from class, fields, and methods
                if (sootClass is JavaSootClass) {
                    val className = sootClass.type.fullyQualifiedName
                    extractAnnotations(sootClass.annotations, className, "<class>")
                    sootClass.fields.forEach { field ->
                        extractAnnotations(field.annotations, className, field.name)
                    }
                    sootClass.methods.forEach { method ->
                        if (method is JavaSootMethod) {
                            extractAnnotations(method.annotations, className, method.name)
                        }
                    }
                }
                extensions.forEach { it.visit(sootClass, extensionContext) }
            }

        // Pass 2B: Resolve cross-method functional interface dispatch
        resolveFunctionalDispatch()

        // Build call graph if configured
        if (config.buildCallGraph) {
            processCallGraph()
        }

        graphBuilder.setResources(resourceAccessor)
        return graphBuilder.build()
    }

    private fun log(message: String) {
        config.verbose?.invoke(message)
    }

    /**
     * Extract enum constant values from a single enum class.
     *
     * In bytecode, enum constants are initialized in <clinit> like:
     *   CHECKOUT = new ExperimentId("CHECKOUT", 0, 1001, "checkout_exp");
     *
     * Where:
     * - First arg: enum name (String)
     * - Second arg: ordinal (int)
     * - Third+ args: user-defined constructor parameters
     */
    private fun extractEnumValues(enumClass: SootClass) {
        val className = enumClass.type.fullyQualifiedName
        log("Processing enum class: $className")

        val clinit = enumClass.methods.find { it.name == "<clinit>" && it.isStatic }
        if (clinit == null) {
            log("  No <clinit> found for $className")
            return
        }

        if (!clinit.hasBody()) {
            log("  <clinit> has no body for $className")
            return
        }

        val body = clinit.body
        val stmts = body.stmtGraph.stmts.toList()
        log("  <clinit> has ${stmts.size} statements")

        // Debug: print all statements
        stmts.forEachIndexed { idx, stmt ->
            log("    [$idx] ${stmt.javaClass.simpleName}: $stmt")
        }

        // Track local variable assignments: localName -> value (for constants)
        val localValues = mutableMapOf<String, Any?>()
        // Track local variable aliases: localName -> original localName (for tracking new objects)
        val localAliases = mutableMapOf<String, String>()

        stmts.forEach { stmt ->
            when (stmt) {
                is JAssignStmt -> {
                    val left = stmt.leftOp
                    val right = stmt.rightOp

                    // Track constant assignments to locals
                    if (left is Local && right is SootConstant) {
                        localValues[left.name] = extractConstantValue(right)
                    }

                    // Track boxing method calls: Integer.valueOf(int), Long.valueOf(long), etc.
                    // Pattern: $stackN = staticinvoke Integer.valueOf(1234)
                    if (left is Local && right is JStaticInvokeExpr) {
                        val boxedValue = extractBoxedValue(right)
                        if (boxedValue != null) {
                            localValues[left.name] = boxedValue
                        }
                    }

                    // Track static field reads (enum constant references from other enums)
                    // Pattern: $stackN = <sample.ab.Priority: Priority HIGH>
                    // This handles the case where one enum's constructor takes another enum as argument
                    if (left is Local && right is JFieldRef) {
                        val fieldSig = right.fieldSignature
                        val fieldDeclClass = fieldSig.declClassType.fullyQualifiedName
                        val fieldType = fieldSig.type
                        // Check if the field type matches the declaring class (enum constant pattern)
                        if (fieldType is ClassType && fieldType.fullyQualifiedName == fieldDeclClass) {
                            localValues[left.name] = EnumValueReference(fieldDeclClass, fieldSig.name)
                            log("  Tracked enum reference: ${left.name} = $fieldDeclClass.${fieldSig.name}")
                        }
                    }

                    // Track local-to-local assignments (aliases)
                    if (left is Local && right is Local) {
                        // left = right, so left is an alias for right
                        // Follow the chain to find the original
                        val original = localAliases[right.name] ?: right.name
                        localAliases[left.name] = original
                    }

                    // Look for: EnumField = new EnumClass(...)
                    if (left is JFieldRef && left.fieldSignature.declClassType.fullyQualifiedName == className) {
                        val fieldName = left.fieldSignature.name

                        // The right side should be a local that was assigned from new + <init>
                        // We need to find the <init> call to get the constructor arguments
                        if (right is Local) {
                            // Resolve alias to find the original local that was used with new/init
                            val originalLocal = localAliases[right.name] ?: right.name
                            log("  Found field assignment: $fieldName = ${right.name} (resolved to $originalLocal)")
                            val initValues = findEnumInitValues(originalLocal, stmts, localValues, className)
                            if (initValues.isNotEmpty()) {
                                graphBuilder.addEnumValues(className, fieldName, initValues)
                                log("  Extracted enum value: $className.$fieldName = $initValues")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Find the values passed to enum constructor for a given local variable.
     * Looks for the pattern: local.<init>("NAME", ordinal, value1, value2, ...)
     *
     * @return list of user-defined constructor arguments (excluding name and ordinal)
     */
    private fun findEnumInitValues(localName: String, stmts: List<Stmt>, localValues: Map<String, Any?>, className: String): List<Any?> {
        for (stmt in stmts) {
            if (stmt !is JInvokeStmt) continue

            val invokeExpr = stmt.invokeExpr.orElse(null) ?: continue
            log("    Checking invoke: ${invokeExpr.javaClass.simpleName} - ${invokeExpr.methodSignature}")

            if (invokeExpr !is AbstractInstanceInvokeExpr) {
                log("    Skipping: not AbstractInstanceInvokeExpr")
                continue
            }
            if (invokeExpr.methodSignature.name != "<init>") {
                log("    Skipping: method name is '${invokeExpr.methodSignature.name}', not '<init>'")
                continue
            }

            val base = invokeExpr.base
            log("    Base: ${base.javaClass.simpleName} - $base (looking for $localName)")
            if (base !is Local || base.name != localName) continue

            // Found the <init> call
            // Args: [name, ordinal, ...user args...]
            val args = invokeExpr.args
            log("    Found <init> for $localName with ${args.size} args: ${args.map { it.toString() }}")
            if (args.size > 2) {
                // Get all user-defined arguments (starting from index 2)
                return args.drop(2).map { arg ->
                    extractValueFromArg(arg, localValues)
                }
            } else {
                log("    Only ${args.size} args (need > 2 for user-defined values)")
            }
        }
        log("    No <init> call found for local $localName")
        return emptyList()
    }

    /**
     * Extract a value from a method argument (either a constant or a local variable reference).
     * Enum constant references passed as constructor arguments are tracked via local variables
     * (see localValues map populated during JFieldRef processing above).
     */
    private fun extractValueFromArg(arg: Value, localValues: Map<String, Any?>): Any? {
        return when (arg) {
            is SootConstant -> extractConstantValue(arg)
            is Local -> localValues[arg.name]
            else -> null
        }
    }

    private fun processTypeHierarchyForClass(sootClass: SootClass) {
        val classType = toTypeDescriptor(sootClass.type)

        // Process superclass
        sootClass.superclass.ifPresent { superType ->
            graphBuilder.addTypeRelation(
                classType,
                toTypeDescriptor(superType),
                TypeRelation.EXTENDS
            )
        }

        // Process interfaces
        sootClass.interfaces.forEach { interfaceType ->
            graphBuilder.addTypeRelation(
                classType,
                toTypeDescriptor(interfaceType),
                TypeRelation.IMPLEMENTS
            )
        }
    }

    /**
     * Process all declared fields for a single class, ensuring FieldNodes are created.
     *
     * This is important for return type analysis to discover ALL fields in a class,
     * not just those that are referenced in methods. Fields may be:
     * - Public fields accessed directly without getter/setter
     * - Fields only used by frameworks (Jackson, Lombok, etc.)
     * - Fields initialized via reflection or deserialization
     */
    private fun visitFieldsForClass(sootClass: SootClass) {
        val className = sootClass.type.fullyQualifiedName
        sootClass.fields.forEach { field ->
            val fieldName = field.name

            // Skip synthetic fields
            if (fieldName.startsWith("\$") || fieldName.startsWith("this\$")) {
                return@forEach
            }

            // Use field signature as key (same format as getOrCreateField)
            val fieldSig = field.signature.toString()
            if (fieldNodes.containsKey(fieldSig)) {
                return@forEach
            }

            val declaringClassType = toTypeDescriptor(sootClass.type)
            val baseType = toTypeDescriptor(field.type)

            // Try to get generic type from bytecode signature
            val fieldType = getFieldTypeWithGenerics(className, fieldName, baseType)

            val node = FieldNode(
                id = nextNodeId("field"),
                descriptor = FieldDescriptor(
                    declaringClass = declaringClassType,
                    name = fieldName,
                    type = fieldType
                ),
                isStatic = field.isStatic
            )
            fieldNodes[fieldSig] = node
            graphBuilder.addNode(node)
        }
    }

    private fun processMethod(method: SootMethod) {
        val methodDescriptor = toMethodDescriptor(method)
        graphBuilder.addMethod(methodDescriptor)

        // Create return node for this method
        val returnNode = ReturnNode(
            id = nextNodeId("return"),
            method = methodDescriptor
        )
        methodReturnNodes[methodDescriptor.signature] = returnNode
        graphBuilder.addNode(returnNode)

        // Process method body if available
        if (method.hasBody()) {
            val body = method.body
            val stmtGraph = body.stmtGraph

            // Reset per-method stmt tracking
            stmtNodeIds = mutableMapOf()

            // Process parameters
            processParameters(method, methodDescriptor)

            // Process each statement (pass 1: data flow)
            stmtGraph.stmts.forEach { stmt ->
                processStatement(stmt, methodDescriptor, stmtGraph)
            }

            // Process control flow (pass 2: branch structure)
            processControlFlow(stmtGraph, methodDescriptor)
        }
    }

    /**
     * Record that a node was created from a given statement.
     */
    private fun recordStmtNode(stmt: Stmt, nodeId: NodeId) {
        stmtNodeIds.getOrPut(stmt) { mutableListOf() }.add(nodeId)
    }

    private fun processParameters(method: SootMethod, methodDescriptor: MethodDescriptor) {
        method.parameterTypes.forEachIndexed { index, paramType ->
            val paramNode = ParameterNode(
                id = nextNodeId("param"),
                index = index,
                type = toTypeDescriptor(paramType),
                method = methodDescriptor
            )
            parameterNodes["${methodDescriptor.signature}#$index"] = paramNode
            graphBuilder.addNode(paramNode)
        }
    }

    private fun processStatement(stmt: Stmt, method: MethodDescriptor, stmtGraph: StmtGraph<*>) {
        when (stmt) {
            is JAssignStmt -> processAssignment(stmt, method)
            is JIdentityStmt -> processIdentity(stmt, method)
            is JInvokeStmt -> processInvoke(stmt, method)
            is JReturnStmt -> processReturn(stmt, method)
            // JIfStmt is handled in processControlFlow (pass 2)
        }
    }

    private fun processAssignment(stmt: JAssignStmt, method: MethodDescriptor) {
        val leftOp = stmt.leftOp
        val rightOp = stmt.rightOp

        // Handle new expressions: x = new Foo()
        // Create a LocalVariable with the correct type from the new expression
        if (rightOp is JNewExpr && leftOp is Local) {
            val allocType = toTypeDescriptor(rightOp.type)
            val allocNode = getOrCreateLocalWithType(leftOp, method, allocType)
            allocationNodes["${method.signature}#${leftOp.name}"] = allocNode
            recordStmtNode(stmt, allocNode.id)
            return
        }

        val targetNode = getOrCreateValueNode(leftOp, method)
        val sourceNode = getOrCreateValueNode(rightOp, method)

        if (targetNode != null) {
            recordStmtNode(stmt, targetNode.id)
        }

        if (targetNode != null && sourceNode != null) {
            val kind = when {
                leftOp is JFieldRef -> DataFlowKind.FIELD_STORE
                leftOp is JArrayRef -> DataFlowKind.ARRAY_STORE
                rightOp is JFieldRef -> DataFlowKind.FIELD_LOAD
                rightOp is JArrayRef -> DataFlowKind.ARRAY_LOAD
                else -> DataFlowKind.ASSIGN
            }

            graphBuilder.addEdge(
                DataFlowEdge(
                    from = sourceNode.id,
                    to = targetNode.id,
                    kind = kind
                )
            )
        }

        // Track dynamic targets flowing through field stores:
        // When a local with dynamic targets is stored to a field, remember the mapping
        if (leftOp is JFieldRef && rightOp is Local) {
            val localKey = "${method.signature}#${rightOp.name}"
            val targets = dynamicTargets[localKey]
            if (targets != null) {
                val fieldKey = leftOp.fieldSignature.toString()
                fieldDynamicTargets.getOrPut(fieldKey) { mutableListOf() }.addAll(targets)
            }
        }

        // Track dynamic targets flowing through array stores (varargs):
        // When a local with dynamic targets is stored into an array element, remember the mapping
        if (leftOp is JArrayRef && rightOp is Local) {
            val base = leftOp.base
            if (base is Local) {
                val rightKey = "${method.signature}#${rightOp.name}"
                val targets = dynamicTargets[rightKey]
                if (targets != null) {
                    val arrayKey = "${method.signature}#${base.name}"
                    arrayDynamicTargets.getOrPut(arrayKey) { mutableListOf() }.addAll(targets)
                }
            }
        }

        // Track dynamic targets flowing through array loads:
        // When an array element is loaded into a local, propagate the array's dynamic targets
        if (rightOp is JArrayRef && targetNode is LocalVariable) {
            val base = rightOp.base
            if (base is Local) {
                val arrayKey = "${method.signature}#${base.name}"
                val targets = arrayDynamicTargets[arrayKey]
                if (targets != null) {
                    val localKey = "${method.signature}#${targetNode.name}"
                    val existing = dynamicTargets[localKey]
                    dynamicTargets[localKey] = if (existing != null) existing + targets else targets.toList()
                }
            }
        }

        // Track dynamic targets flowing through field loads:
        // When a field with dynamic targets is loaded into a local, propagate the targets.
        // If the field has no dynamic targets yet (e.g., constructor not processed yet),
        // record for deferred resolution in resolveFunctionalDispatch.
        if (rightOp is JFieldRef && targetNode is LocalVariable) {
            val fieldKey = rightOp.fieldSignature.toString()
            val localKey = "${method.signature}#${targetNode.name}"
            val targets = fieldDynamicTargets[fieldKey]
            if (targets != null) {
                val existing = dynamicTargets[localKey]
                dynamicTargets[localKey] = if (existing != null) existing + targets else targets.toList()
            } else {
                // Record for deferred resolution
                fieldLoadLocals.getOrPut(fieldKey) { mutableListOf() }.add(localKey)
            }
        }

        // Handle method invocations in assignments (e.g., x = foo())
        if (rightOp is AbstractInvokeExpr) {
            processInvokeExpr(rightOp, method, targetNode, stmt)
        }
    }

    private fun processIdentity(stmt: JIdentityStmt, method: MethodDescriptor) {
        val leftOp = stmt.leftOp
        val rightOp = stmt.rightOp

        if (rightOp is JParameterRef) {
            val paramIndex = rightOp.index
            val paramKey = "${method.signature}#$paramIndex"
            val paramNode = parameterNodes[paramKey]
            val localNode = getOrCreateValueNode(leftOp, method)

            if (paramNode != null && localNode != null) {
                graphBuilder.addEdge(
                    DataFlowEdge(
                        from = paramNode.id,
                        to = localNode.id,
                        kind = DataFlowKind.ASSIGN
                    )
                )
            }

            // Track which locals correspond to parameters for cross-method dispatch
            if (leftOp is Local) {
                val localKey = "${method.signature}#${leftOp.name}"
                localToParamIndex[localKey] = method.signature to paramIndex
            }
        }
    }

    private fun processInvoke(stmt: JInvokeStmt, method: MethodDescriptor) {
        stmt.invokeExpr.ifPresent { invokeExpr ->
            processInvokeExpr(invokeExpr, method, null, stmt)
        }
    }

    private fun processInvokeExpr(
        invokeExpr: AbstractInvokeExpr,
        caller: MethodDescriptor,
        resultNode: ValueNode?,
        stmt: Stmt? = null
    ) {
        val calleeSignature = resolveMethodDefiningClass(invokeExpr.methodSignature)
        val callee = toMethodDescriptor(calleeSignature)

        // Create argument nodes and track dataflow
        val argNodeIds = invokeExpr.args.mapIndexed { index, arg ->
            val argNode = getOrCreateValueNode(arg, caller)
            argNode?.id ?: nextNodeId("unknown")
        }

        // Handle boxing methods (Integer.valueOf, Long.valueOf, etc.)
        // These should propagate the value directly without going through the call site
        if (isBoxingMethod(calleeSignature) && resultNode != null && argNodeIds.isNotEmpty()) {
            graphBuilder.addEdge(
                DataFlowEdge(
                    from = argNodeIds[0],
                    to = resultNode.id,
                    kind = DataFlowKind.ASSIGN
                )
            )
            return
        }

        // Handle unboxing methods (Integer.intValue, Long.longValue, etc.)
        // The receiver object's value flows to the result
        if (isUnboxingMethod(calleeSignature) && resultNode != null && invokeExpr is AbstractInstanceInvokeExpr) {
            val baseNode = getOrCreateValueNode(invokeExpr.base, caller)
            if (baseNode != null) {
                graphBuilder.addEdge(
                    DataFlowEdge(
                        from = baseNode.id,
                        to = resultNode.id,
                        kind = DataFlowKind.ASSIGN
                    )
                )
            }
            return
        }

        // Handle invokedynamic (lambdas and method references)
        // Extract the actual target method from bootstrap arguments
        if (invokeExpr is JDynamicInvokeExpr) {
            processDynamicInvoke(invokeExpr, caller, resultNode, stmt)
            return
        }

        // Extract receiver for instance method calls
        val receiverNode = if (invokeExpr is AbstractInstanceInvokeExpr) {
            getOrCreateValueNode(invokeExpr.base, caller)
        } else {
            null
        }

        // Create call site node
        val callSite = CallSiteNode(
            id = nextNodeId("call"),
            caller = caller,
            callee = callee,
            lineNumber = null, // SootUp may provide position info
            receiver = receiverNode?.id,
            arguments = argNodeIds
        )
        graphBuilder.addNode(callSite)
        if (stmt != null) {
            recordStmtNode(stmt, callSite.id)
        }

        // Resolve functional interface dispatch:
        // If the receiver was assigned from an invokedynamic, connect this
        // virtual call (e.g., Function.apply) to the actual target method
        if (receiverNode is LocalVariable) {
            val key = "${caller.signature}#${receiverNode.name}"
            val targets = dynamicTargets[key]

            // If this local has no direct dynamic targets, record for
            // cross-method resolution in resolveFunctionalDispatch()
            if (targets == null) {
                val paramInfo = localToParamIndex[key]
                if (paramInfo != null) {
                    // Local is a parameter — record for parameter-based dispatch
                    parameterVirtualCalls
                        .getOrPut(paramInfo.first) { mutableListOf() }
                        .add(paramInfo.second to callSite)
                } else {
                    // Local is not a parameter — may get targets from return propagation
                    unresolvedLocalVirtualCalls
                        .getOrPut(key) { mutableListOf() }
                        .add(callSite)
                }
            }

            if (targets != null) {
                for (target in targets) {
                    graphBuilder.addEdge(
                        CallEdge(
                            from = callSite.id,
                            to = callSite.id,
                            isVirtual = false,
                            isDynamic = true
                        )
                    )
                    // Create an additional call site that records the resolved target
                    val resolvedCallSite = CallSiteNode(
                        id = nextNodeId("call"),
                        caller = caller,
                        callee = target,
                        lineNumber = null,
                        receiver = receiverNode.id,
                        arguments = argNodeIds
                    )
                    graphBuilder.addNode(resolvedCallSite)
                    // Forward dataflow: arguments flow to resolved target too
                    argNodeIds.forEach { argNodeId ->
                        graphBuilder.addEdge(
                            DataFlowEdge(
                                from = argNodeId,
                                to = resolvedCallSite.id,
                                kind = DataFlowKind.PARAMETER_PASS
                            )
                        )
                    }
                    // If there's a result, dataflow from resolved call site too
                    if (resultNode != null) {
                        graphBuilder.addEdge(
                            DataFlowEdge(
                                from = resolvedCallSite.id,
                                to = resultNode.id,
                                kind = DataFlowKind.RETURN_VALUE
                            )
                        )
                    }
                }
            }
        }

        // Add dataflow edge from receiver to call site (for backward tracing)
        if (receiverNode != null) {
            graphBuilder.addEdge(
                DataFlowEdge(
                    from = receiverNode.id,
                    to = callSite.id,
                    kind = DataFlowKind.ASSIGN
                )
            )
        }

        // Add dataflow from arguments to parameters
        argNodeIds.forEachIndexed { index, argNodeId ->
            graphBuilder.addEdge(
                DataFlowEdge(
                    from = argNodeId,
                    to = callSite.id, // For now, flow to call site; will refine with call graph
                    kind = DataFlowKind.PARAMETER_PASS
                )
            )

            // Track dynamic targets flowing through arguments for cross-method dispatch
            // Check both direct dynamic targets and array dynamic targets (for varargs)
            if (index < invokeExpr.args.size) {
                val arg = invokeExpr.args[index]
                if (arg is Local) {
                    val argKey = "${caller.signature}#${arg.name}"
                    val targets = dynamicTargets[argKey] ?: arrayDynamicTargets[argKey]
                    if (targets != null) {
                        callSiteDynamicArgs
                            .getOrPut(callee.signature) { mutableListOf() }
                            .add(index to targets)
                    }
                }
            }
        }

        // If there's a result, add dataflow from call to result
        if (resultNode != null) {
            graphBuilder.addEdge(
                DataFlowEdge(
                    from = callSite.id,
                    to = resultNode.id,
                    kind = DataFlowKind.RETURN_VALUE
                )
            )
        }

        // Track call result locals for return value propagation
        if (resultNode is LocalVariable) {
            val resultKey = "${caller.signature}#${resultNode.name}"
            callResultLocals
                .getOrPut(callee.signature) { mutableListOf() }
                .add(resultKey)
        }
    }

    /**
     * Process an invokedynamic instruction (lambda or method reference).
     *
     * The bootstrap arguments contain MethodHandle objects that reference
     * the actual target method. For example:
     * - `handler::listUsers` -> MethodHandle pointing to `Handler.listUsers`
     * - `x -> x.getName()` -> MethodHandle pointing to a synthetic lambda method
     *
     * We create CallEdges to the actual target methods, enabling backward
     * tracing through lambdas and method references.
     */
    private fun processDynamicInvoke(
        invokeExpr: JDynamicInvokeExpr,
        caller: MethodDescriptor,
        resultNode: ValueNode?,
        stmt: Stmt?
    ) {
        // Extract actual target method(s) from bootstrap arguments
        val targetMethods = invokeExpr.bootstrapArgs
            .filterIsInstance<MethodHandle>()
            .filter { it.isMethodRef }
            .mapNotNull { handle ->
                val sig = handle.referenceSignature
                if (sig is MethodSignature) toMethodDescriptor(sig) else null
            }

        // Create argument nodes
        val argNodeIds = invokeExpr.args.mapIndexed { _, arg ->
            val argNode = getOrCreateValueNode(arg, caller)
            argNode?.id ?: nextNodeId("unknown")
        }

        // For each target method, create a call site
        for (target in targetMethods) {
            val callSite = CallSiteNode(
                id = nextNodeId("call"),
                caller = caller,
                callee = target,
                lineNumber = null,
                receiver = null,
                arguments = argNodeIds
            )
            graphBuilder.addNode(callSite)
            if (stmt != null) {
                recordStmtNode(stmt, callSite.id)
            }

            // Add call edge (marked as dynamic)
            graphBuilder.addEdge(
                CallEdge(
                    from = callSite.id,
                    to = callSite.id, // Self-referencing for now; will be resolved with method entry nodes
                    isVirtual = false,
                    isDynamic = true
                )
            )

            // Dataflow from arguments to call site
            argNodeIds.forEach { argNodeId ->
                graphBuilder.addEdge(
                    DataFlowEdge(
                        from = argNodeId,
                        to = callSite.id,
                        kind = DataFlowKind.PARAMETER_PASS
                    )
                )
            }

            // If there's a result, add dataflow from call to result
            if (resultNode != null) {
                graphBuilder.addEdge(
                    DataFlowEdge(
                        from = callSite.id,
                        to = resultNode.id,
                        kind = DataFlowKind.RETURN_VALUE
                    )
                )
            }
        }

        // Track dynamic targets for functional interface dispatch resolution
        // Merge with existing targets (supports conditional assignment where both branches
        // assign different lambdas/method references to the same local)
        if (resultNode is LocalVariable && targetMethods.isNotEmpty()) {
            val key = "${caller.signature}#${resultNode.name}"
            val existing = dynamicTargets[key]
            dynamicTargets[key] = if (existing != null) existing + targetMethods else targetMethods
        }

        // Track dynamic targets flowing directly to a field store:
        // e.g., this.mapper = invokedynamic(...) where resultNode is a FieldNode
        if (resultNode is FieldNode && targetMethods.isNotEmpty() && stmt is JAssignStmt) {
            val leftOp = stmt.leftOp
            if (leftOp is JFieldRef) {
                val fieldKey = leftOp.fieldSignature.toString()
                fieldDynamicTargets.getOrPut(fieldKey) { mutableListOf() }.addAll(targetMethods)
            }
        }

        // If no method handles found, fall back to creating a call site with the synthetic method
        if (targetMethods.isEmpty()) {
            val callee = toMethodDescriptor(invokeExpr.methodSignature)
            val callSite = CallSiteNode(
                id = nextNodeId("call"),
                caller = caller,
                callee = callee,
                lineNumber = null,
                receiver = null,
                arguments = argNodeIds
            )
            graphBuilder.addNode(callSite)
            if (stmt != null) {
                recordStmtNode(stmt, callSite.id)
            }

            argNodeIds.forEach { argNodeId ->
                graphBuilder.addEdge(
                    DataFlowEdge(
                        from = argNodeId,
                        to = callSite.id,
                        kind = DataFlowKind.PARAMETER_PASS
                    )
                )
            }

            if (resultNode != null) {
                graphBuilder.addEdge(
                    DataFlowEdge(
                        from = callSite.id,
                        to = resultNode.id,
                        kind = DataFlowKind.RETURN_VALUE
                    )
                )
            }
        }
    }

    /**
     * Check if this is a boxing method like Integer.valueOf(int)
     */
    private fun isBoxingMethod(signature: MethodSignature): Boolean {
        val className = signature.declClassType.fullyQualifiedName
        val methodName = signature.name
        return methodName == "valueOf" && className in WRAPPER_CLASSES
    }

    /**
     * Check if this is an unboxing method like Integer.intValue()
     */
    private fun isUnboxingMethod(signature: MethodSignature): Boolean {
        val className = signature.declClassType.fullyQualifiedName
        val methodName = signature.name
        return className in WRAPPER_CLASSES && methodName in setOf(
            "intValue", "longValue", "shortValue", "byteValue",
            "floatValue", "doubleValue", "booleanValue", "charValue"
        )
    }

    companion object {
        private val WRAPPER_CLASSES = setOf(
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Short",
            "java.lang.Byte",
            "java.lang.Float",
            "java.lang.Double",
            "java.lang.Boolean",
            "java.lang.Character"
        )
    }

    private fun processReturn(stmt: JReturnStmt, method: MethodDescriptor) {
        val returnValue = stmt.op
        val returnNode = methodReturnNodes[method.signature] ?: return

        val valueNode = getOrCreateValueNode(returnValue, method)
        if (valueNode != null) {
            graphBuilder.addEdge(
                DataFlowEdge(
                    from = valueNode.id,
                    to = returnNode.id,
                    kind = DataFlowKind.RETURN_VALUE
                )
            )

            // Track dynamic targets flowing through return values
            if (valueNode is LocalVariable) {
                val key = "${method.signature}#${valueNode.name}"
                val targets = dynamicTargets[key]
                if (targets != null) {
                    returnDynamicTargets[method.signature] = targets
                }
            }
        }
    }

    /**
     * Process control flow for a method (pass 2).
     *
     * For each JIfStmt, identifies:
     * 1. The condition operand's NodeId
     * 2. The JVM comparison operator and comparand
     * 3. Which nodes belong to the true branch vs false branch
     *
     * Creates ControlFlowEdge and BranchScope entries.
     */
    private fun processControlFlow(stmtGraph: StmtGraph<*>, method: MethodDescriptor) {
        val stmts = stmtGraph.stmts.toList()
        val stmtSet = stmts.toSet()

        for (stmt in stmts) {
            if (stmt !is JIfStmt) continue

            val condition = stmt.condition
            if (condition !is AbstractConditionExpr) continue

            val op1 = condition.op1
            val op2 = condition.op2

            // Resolve operands to NodeIds
            val op1Node = getOrCreateValueNode(op1, method)
            val op2Node = getOrCreateValueNode(op2, method)
            if (op1Node == null || op2Node == null) continue

            val comparisonOp = when (condition) {
                is JEqExpr -> ComparisonOp.EQ
                is JNeExpr -> ComparisonOp.NE
                is JLtExpr -> ComparisonOp.LT
                is JGeExpr -> ComparisonOp.GE
                is JGtExpr -> ComparisonOp.GT
                is JLeExpr -> ComparisonOp.LE
                else -> continue
            }

            // Determine which operand is the "condition" (variable) and which is the comparand (constant).
            // Convention: op1 is the variable being tested, op2 is the constant it's compared to.
            val conditionNodeId = op1Node.id
            val comparison = BranchComparison(
                operator = comparisonOp,
                comparandNodeId = op2Node.id
            )

            // In Jimple: "if <condition> goto target"
            // - successors[0] = fall-through (condition is FALSE)
            // - successors[1] = branch target (condition is TRUE)
            val successors = stmtGraph.successors(stmt).toList()
            if (successors.size != 2) continue

            val falseSuccessor = successors[0]  // fall-through
            val trueSuccessor = successors[1]   // goto target

            // Walk each branch collecting all reachable statements until merge point
            val trueBranchStmts = walkBranch(trueSuccessor, falseSuccessor, stmtGraph, stmtSet)
            val falseBranchStmts = walkBranch(falseSuccessor, trueSuccessor, stmtGraph, stmtSet)

            // Collect NodeIds from each branch's statements as IntArrays
            val trueIds = trueBranchStmts.flatMap { stmtNodeIds[it] ?: emptyList() }
                .map { it.value }.toIntArray()
            val falseIds = falseBranchStmts.flatMap { stmtNodeIds[it] ?: emptyList() }
                .map { it.value }.toIntArray()

            // Create ControlFlowEdges from condition to first node in each branch
            if (trueIds.isNotEmpty()) {
                graphBuilder.addEdge(
                    ControlFlowEdge(
                        from = conditionNodeId,
                        to = NodeId(trueIds[0]),
                        kind = ControlFlowKind.BRANCH_TRUE,
                        comparison = comparison
                    )
                )
            }
            if (falseIds.isNotEmpty()) {
                graphBuilder.addEdge(
                    ControlFlowEdge(
                        from = conditionNodeId,
                        to = NodeId(falseIds[0]),
                        kind = ControlFlowKind.BRANCH_FALSE,
                        comparison = comparison
                    )
                )
            }

            // Record branch data (BranchScope is materialised lazily by DefaultGraph)
            if (trueIds.isNotEmpty() || falseIds.isNotEmpty()) {
                graphBuilder.addBranchScope(
                    conditionNodeId = conditionNodeId,
                    method = method,
                    comparison = comparison,
                    trueBranchNodeIds = trueIds,
                    falseBranchNodeIds = falseIds
                )
            }
        }
    }

    /**
     * Walk a branch from [start] collecting statements that are exclusively in this branch.
     *
     * Stops when reaching:
     * - A statement also reachable from [otherBranchStart] (merge point)
     * - A return/throw statement
     * - A statement already visited
     *
     * Uses forward dominance: only includes statements that are reachable from [start]
     * but not directly reachable from [otherBranchStart] without going through the merge point.
     */
    private fun walkBranch(
        start: Stmt,
        otherBranchStart: Stmt,
        stmtGraph: StmtGraph<*>,
        allStmts: Set<Stmt>
    ): Set<Stmt> {
        // Collect statements reachable from the other branch (to find merge points)
        val otherReachable = collectReachable(otherBranchStart, stmtGraph)

        val result = mutableSetOf<Stmt>()
        val queue = ArrayDeque<Stmt>()
        queue.add(start)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current in result) continue
            if (current !in allStmts) continue

            // Stop at merge point: a statement reachable from both branches
            // But include the start itself even if it's reachable from the other branch
            if (current != start && current in otherReachable) continue

            result.add(current)

            for (succ in stmtGraph.successors(current)) {
                if (succ !in result) {
                    queue.add(succ)
                }
            }
        }

        return result
    }

    /**
     * Collect all statements reachable from [start] via forward traversal.
     */
    private fun collectReachable(start: Stmt, stmtGraph: StmtGraph<*>): Set<Stmt> {
        val reachable = mutableSetOf<Stmt>()
        val queue = ArrayDeque<Stmt>()
        queue.add(start)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current in reachable) continue
            reachable.add(current)
            for (succ in stmtGraph.successors(current)) {
                if (succ !in reachable) {
                    queue.add(succ)
                }
            }
        }

        return reachable
    }

    /**
     * Post-processing: resolve functional interface dispatch across method boundaries.
     *
     * When a lambda/method reference is passed as an argument to another method,
     * and that method calls the functional interface method on the parameter,
     * this creates resolved CallSiteNodes connecting the virtual call to the actual target.
     *
     * Also handles return values: when a method returns a lambda/method reference,
     * callers that invoke the functional interface on the result get resolved.
     */
    private fun resolveFunctionalDispatch() {
        // Phase 1: Propagate return dynamic targets to caller locals
        for ((methodSig, targets) in returnDynamicTargets) {
            val resultLocalKeys = callResultLocals[methodSig] ?: continue
            for (localKey in resultLocalKeys) {
                if (localKey !in dynamicTargets) {
                    dynamicTargets[localKey] = targets
                }
            }
        }

        // Phase 2: Resolve parameter virtual calls using dynamic args from callers
        for ((methodSig, virtualCalls) in parameterVirtualCalls) {
            val dynamicArgs = callSiteDynamicArgs[methodSig] ?: continue

            for ((paramIndex, callSiteNode) in virtualCalls) {
                val targets = dynamicArgs
                    .filter { it.first == paramIndex }
                    .flatMap { it.second }

                for (target in targets) {
                    val resolvedCallSite = CallSiteNode(
                        id = nextNodeId("call"),
                        caller = callSiteNode.caller,
                        callee = target,
                        lineNumber = callSiteNode.lineNumber,
                        receiver = callSiteNode.receiver,
                        arguments = callSiteNode.arguments
                    )
                    graphBuilder.addNode(resolvedCallSite)
                    graphBuilder.addEdge(
                        CallEdge(
                            from = resolvedCallSite.id,
                            to = resolvedCallSite.id,
                            isVirtual = false,
                            isDynamic = true
                        )
                    )
                    // Forward dataflow: arguments flow to resolved target
                    callSiteNode.arguments.forEach { argNodeId ->
                        graphBuilder.addEdge(
                            DataFlowEdge(
                                from = argNodeId,
                                to = resolvedCallSite.id,
                                kind = DataFlowKind.PARAMETER_PASS
                            )
                        )
                    }
                }
            }
        }

        // Phase 2B: Propagate field dynamic targets to locals that loaded from those fields
        // This handles the case where the field store (in a constructor or setter) was processed
        // after the field load (in a different method), so the targets weren't available at load time.
        for ((fieldKey, localKeys) in fieldLoadLocals) {
            val targets = fieldDynamicTargets[fieldKey] ?: continue
            for (localKey in localKeys) {
                val existing = dynamicTargets[localKey]
                dynamicTargets[localKey] = if (existing != null) existing + targets else targets.toList()
            }
        }

        // Phase 3: Re-resolve virtual calls on locals that gained dynamic targets
        // from return value propagation (Phase 1) or field propagation (Phase 2B).
        // These are non-parameter locals
        // whose dynamicTargets were empty during processInvokeExpr, but now have
        // targets after return propagation.
        for ((localKey, unresolvedCalls) in unresolvedLocalVirtualCalls) {
            val targets = dynamicTargets[localKey] ?: continue
            for (callSiteNode in unresolvedCalls) {
                for (target in targets) {
                    val resolvedCallSite = CallSiteNode(
                        id = nextNodeId("call"),
                        caller = callSiteNode.caller,
                        callee = target,
                        lineNumber = callSiteNode.lineNumber,
                        receiver = callSiteNode.receiver,
                        arguments = callSiteNode.arguments
                    )
                    graphBuilder.addNode(resolvedCallSite)
                    graphBuilder.addEdge(
                        CallEdge(
                            from = resolvedCallSite.id,
                            to = resolvedCallSite.id,
                            isVirtual = false,
                            isDynamic = true
                        )
                    )
                    callSiteNode.arguments.forEach { argNodeId ->
                        graphBuilder.addEdge(
                            DataFlowEdge(
                                from = argNodeId,
                                to = resolvedCallSite.id,
                                kind = DataFlowKind.PARAMETER_PASS
                            )
                        )
                    }
                    // If there's a result node for the original call, add return value edge
                }
            }
        }
    }

    private fun processCallGraph() {
        try {
            val callGraph = buildCallGraph()
            // Call graph edges are already processed via call site nodes
            // This method could be extended to add additional interprocedural edges
        } catch (e: Exception) {
            // Call graph construction may fail for incomplete classpaths
            // Continue without call graph
        }
    }

    private fun buildCallGraph(): CallGraph {
        val entryMethods = findEntryPoints()

        return when (config.callGraphAlgorithm) {
            CallGraphAlgorithm.CHA -> {
                ClassHierarchyAnalysisAlgorithm(view).initialize(entryMethods)
            }
            CallGraphAlgorithm.RTA -> {
                RapidTypeAnalysisAlgorithm(view).initialize(entryMethods)
            }
            else -> {
                // Default to CHA for now
                ClassHierarchyAnalysisAlgorithm(view).initialize(entryMethods)
            }
        }
    }

    private fun findEntryPoints(): List<MethodSignature> {
        // Find main methods and other entry points
        val entryPoints = mutableListOf<MethodSignature>()
        view.classes.forEach { sootClass ->
            sootClass.methods.forEach { method ->
                if (method.name == "main" && method.isStatic) {
                    entryPoints.add(method.signature)
                }
            }
        }
        return entryPoints
    }

    private fun getOrCreateValueNode(value: Value, method: MethodDescriptor): ValueNode? {
        return when (value) {
            is Local -> getOrCreateLocal(value, method)
            is SootConstant -> getOrCreateConstant(value)
            is JFieldRef -> getOrCreateField(value)
            is JArrayRef -> {
                // For array references like $r1[0], return the base array's node
                // This creates edges from array elements to the array itself,
                // enabling backward tracing from arrays to their elements
                val base = value.base
                if (base is Local) getOrCreateLocal(base, method) else null
            }
            is AbstractInvokeExpr -> null // Handled separately
            else -> null
        }
    }

    private fun getOrCreateLocal(local: Local, method: MethodDescriptor): LocalVariable {
        val key = "${method.signature}#${local.name}"
        // Check if we have a typed allocation for this local
        allocationNodes[key]?.let { return it }
        return localNodes.getOrPut(key) {
            val node = LocalVariable(
                id = nextNodeId("local"),
                name = local.name,
                type = toTypeDescriptor(local.type),
                method = method
            )
            graphBuilder.addNode(node)
            node
        }
    }

    private fun getOrCreateLocalWithType(local: Local, method: MethodDescriptor, type: TypeDescriptor): LocalVariable {
        val key = "${method.signature}#${local.name}"
        return localNodes.getOrPut(key) {
            val node = LocalVariable(
                id = nextNodeId("local"),
                name = local.name,
                type = type,
                method = method
            )
            graphBuilder.addNode(node)
            node
        }
    }

    private fun getOrCreateConstant(constant: SootConstant): ConstantNode {
        val value = extractConstantValue(constant)
        return constantNodes.getOrPut(value ?: constant) {
            val node = when (constant) {
                is SootIntConstant -> IntConstant(
                    id = nextNodeId("const"),
                    value = constant.value
                )
                is SootLongConstant -> LongConstant(
                    id = nextNodeId("const"),
                    value = constant.value
                )
                is SootFloatConstant -> FloatConstant(
                    id = nextNodeId("const"),
                    value = constant.value
                )
                is SootDoubleConstant -> DoubleConstant(
                    id = nextNodeId("const"),
                    value = constant.value
                )
                // Note: JVM bytecode represents boolean true/false as int constants (1/0),
                // so SootUp produces IntConstant, not BooleanConstant, for boolean values.
                is SootStringConstant -> StringConstant(
                    id = nextNodeId("const"),
                    value = constant.value
                )
                is SootNullConstant -> NullConstant(
                    id = nextNodeId("const")
                )
                else -> {
                    log("Unsupported constant type: ${constant.javaClass.simpleName} = $constant")
                    IntConstant(
                        id = nextNodeId("const"),
                        value = 0
                    )
                }
            }
            graphBuilder.addNode(node)
            node
        }
    }

    private fun getOrCreateField(fieldRef: JFieldRef): FieldNode {
        val fieldSig = fieldRef.fieldSignature
        val key = fieldSig.toString()
        return fieldNodes.getOrPut(key) {
            val declaringClassName = fieldSig.declClassType.fullyQualifiedName
            val fieldName = fieldSig.name
            val baseType = toTypeDescriptor(fieldSig.type)

            // Try to get generic type from bytecode signature
            val fieldType = getFieldTypeWithGenerics(declaringClassName, fieldName, baseType)

            val node = FieldNode(
                id = nextNodeId("field"),
                descriptor = FieldDescriptor(
                    declaringClass = toTypeDescriptor(fieldSig.declClassType),
                    name = fieldName,
                    type = fieldType
                ),
                isStatic = fieldRef is JStaticFieldRef
            )
            graphBuilder.addNode(node)
            node
        }
    }

    private fun extractConstantValue(constant: SootConstant): Any? {
        return when (constant) {
            is SootIntConstant -> constant.value
            is SootLongConstant -> constant.value
            is SootFloatConstant -> constant.value
            is SootDoubleConstant -> constant.value
            is SootStringConstant -> constant.value
            is SootNullConstant -> null
            else -> null
        }
    }

    /**
     * Extract value from boxing method calls like Integer.valueOf(int), Long.valueOf(long), etc.
     * These are used when enum constructor parameters are wrapper types (Integer, Long, etc.)
     * instead of primitive types (int, long, etc.).
     *
     * Pattern in bytecode: $stackN = staticinvoke Integer.valueOf(1234)
     */
    private fun extractBoxedValue(invokeExpr: JStaticInvokeExpr): Any? {
        val methodSig = invokeExpr.methodSignature
        val className = methodSig.declClassType.fullyQualifiedName
        val methodName = methodSig.name

        // Check for boxing methods: Integer.valueOf, Long.valueOf, etc.
        val isBoxingMethod = when (className) {
            "java.lang.Integer" -> methodName == "valueOf"
            "java.lang.Long" -> methodName == "valueOf"
            "java.lang.Short" -> methodName == "valueOf"
            "java.lang.Byte" -> methodName == "valueOf"
            "java.lang.Float" -> methodName == "valueOf"
            "java.lang.Double" -> methodName == "valueOf"
            "java.lang.Boolean" -> methodName == "valueOf"
            "java.lang.Character" -> methodName == "valueOf"
            else -> false
        }

        if (!isBoxingMethod) return null

        // Extract the primitive value from the first argument
        val args = invokeExpr.args
        if (args.isEmpty()) return null

        val arg = args[0]
        return when (arg) {
            is SootConstant -> extractConstantValue(arg)
            else -> null
        }
    }

    private fun extractAnnotations(annotations: Iterable<*>, className: String, memberName: String) {
        for (annot in annotations) {
            val fullName = getAnnotationFullName(annot)
            if (fullName.isEmpty()) continue

            val values = getAnnotationValues(annot)
            val cleanValues = mutableMapOf<String, Any?>()
            for ((key, value) in values) {
                cleanValues[key] = when (value) {
                    is String -> value.takeIf { it.isNotEmpty() }
                    is List<*> -> value.firstOrNull()?.toString()?.removeSurrounding("\"")
                    null -> null
                    else -> value.toString().removeSurrounding("\"").removeSurrounding("[", "]").removeSurrounding("\"").takeIf { it.isNotEmpty() && it != "null" }
                }
            }
            graphBuilder.addMemberAnnotation(className, memberName, fullName, cleanValues)
            graphBuilder.addNode(AnnotationNode(
                id = NodeId.next(),
                name = fullName,
                className = className,
                memberName = memberName,
                values = cleanValues
            ))
        }
    }

    private fun getAnnotationFullName(annot: Any?): String {
        if (annot == null) return ""
        return try {
            val annotationProp = annot::class.java.getMethod("getAnnotation").invoke(annot)
            annotationProp?.let {
                it::class.java.getMethod("getFullyQualifiedName").invoke(it)?.toString() ?: ""
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getAnnotationValues(annot: Any?): Map<String, Any?> {
        if (annot == null) return emptyMap()
        return try {
            annot::class.java.getMethod("getValues").invoke(annot) as? Map<String, Any?> ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun shouldIncludeClass(sootClass: SootClass): Boolean {
        val className = sootClass.type.toString()

        // Check exclude patterns
        if (config.excludePackages.any { className.startsWith(it) }) {
            return false
        }

        // Check include patterns (if specified)
        if (config.includePackages.isNotEmpty()) {
            return config.includePackages.any { className.startsWith(it) }
        }

        return true
    }

    @Suppress("UNUSED_PARAMETER")
    private fun nextNodeId(prefix: String): NodeId = NodeId.next()

    private fun toTypeDescriptor(type: Type): TypeDescriptor {
        return when (type) {
            is ClassType -> TypeDescriptor(
                className = type.fullyQualifiedName,
                typeArguments = emptyList() // Base type without generics
            )
            is ArrayType -> TypeDescriptor(
                className = "${toTypeDescriptor(type.baseType).className}[]"
            )
            is PrimitiveType -> TypeDescriptor(className = type.toString())
            else -> TypeDescriptor(className = type.toString())
        }
    }

    /**
     * Get field type with generic arguments from bytecode signature.
     */
    private fun getFieldTypeWithGenerics(
        declaringClass: String,
        fieldName: String,
        fallbackType: TypeDescriptor
    ): TypeDescriptor {
        return signatureReader?.getFieldType(declaringClass, fieldName) ?: fallbackType
    }

    private fun toMethodDescriptor(method: SootMethod): MethodDescriptor {
        return MethodDescriptor(
            declaringClass = toTypeDescriptor(method.declaringClassType),
            name = method.name,
            parameterTypes = method.parameterTypes.map { toTypeDescriptor(it) },
            returnType = toTypeDescriptor(method.returnType)
        )
    }

    private fun toMethodDescriptor(sig: MethodSignature): MethodDescriptor {
        return MethodDescriptor(
            declaringClass = toTypeDescriptor(sig.declClassType),
            name = sig.name,
            parameterTypes = sig.parameterTypes.map { toTypeDescriptor(it) },
            returnType = toTypeDescriptor(sig.type)
        )
    }

    /**
     * Resolve a method signature to the class that actually defines it.
     *
     * When bytecode says `invokevirtual ServiceImpl.doSomething()` but
     * `ServiceImpl` doesn't override `doSomething()` (it's defined in
     * `AbstractService` or `IService`), this method walks the type hierarchy
     * to find the actual defining class and returns a signature with that class.
     *
     * This ensures the callee in CallSiteNode matches the caller in call sites
     * inside the method body, enabling call chain traversal.
     */
    private fun resolveMethodDefiningClass(sig: MethodSignature): MethodSignature {
        // If the method exists directly in the declared class, use as-is
        if (view.getMethod(sig).isPresent) return sig

        val hierarchy = view.typeHierarchy
        val declClass = sig.declClassType

        // Walk superclasses
        try {
            for (superClass in hierarchy.superClassesOf(declClass)) {
                val resolved = MethodSignature(superClass, sig.subSignature)
                if (view.getMethod(resolved).isPresent) return resolved
            }
        } catch (_: Exception) { /* class not in hierarchy */ }

        // Walk implemented interfaces
        try {
            for (iface in hierarchy.implementedInterfacesOf(declClass)) {
                val resolved = MethodSignature(iface, sig.subSignature)
                if (view.getMethod(resolved).isPresent) return resolved
            }
        } catch (_: Exception) { /* class not in hierarchy */ }

        // Fallback: use original signature
        return sig
    }
}
