package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.CallGraphAlgorithm
import io.johnsonlee.graphite.input.LoaderConfig
import sootup.core.graph.StmtGraph
import sootup.core.jimple.basic.Local
import sootup.core.jimple.basic.Value
import sootup.core.jimple.common.constant.IntConstant as SootIntConstant
import sootup.core.jimple.common.constant.LongConstant as SootLongConstant
import sootup.core.jimple.common.constant.StringConstant as SootStringConstant
import sootup.core.jimple.common.constant.Constant as SootConstant
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr
import sootup.core.jimple.common.expr.AbstractInvokeExpr
import sootup.core.jimple.common.expr.JNewExpr
import sootup.core.jimple.common.ref.JFieldRef
import sootup.core.jimple.common.ref.JArrayRef
import sootup.core.jimple.common.ref.JParameterRef
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JIdentityStmt
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.core.jimple.common.stmt.JReturnStmt
import sootup.core.jimple.common.stmt.Stmt
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
    private val signatureReader: BytecodeSignatureReader? = null
) {
    // NodeId counter is now managed by NodeId.next() for memory efficiency
    private val graphBuilder = DefaultGraph.Builder()

    // Maps to track created nodes for cross-referencing
    private val localNodes = mutableMapOf<String, LocalVariable>()
    private val fieldNodes = mutableMapOf<String, FieldNode>()
    private val parameterNodes = mutableMapOf<String, ParameterNode>()
    private val constantNodes = mutableMapOf<Any, ConstantNode>()
    private val methodReturnNodes = mutableMapOf<String, ReturnNode>()
    private val allocationNodes = mutableMapOf<String, LocalVariable>()

    /**
     * Build the complete graph from the SootUp view
     */
    fun buildGraph(): Graph {
        // 1. Process all classes and build type hierarchy
        processTypeHierarchy()

        // 2. Extract enum constant values
        processEnumValues()

        // 3. Process all methods and build intraprocedural graphs
        processMethods()

        // 4. Extract HTTP endpoints from annotations
        processEndpoints()

        // 5. Extract Jackson annotation information
        processJacksonAnnotations()

        // 6. Build call graph if configured
        if (config.buildCallGraph) {
            processCallGraph()
        }

        return graphBuilder.build()
    }

    /**
     * Extract enum constant values from enum classes.
     * Parses the <clinit> method to find constructor arguments.
     */
    private fun processEnumValues() {
        val enumClasses = view.classes.filter { it.isEnum }.toList()
        log("Found ${enumClasses.size} enum classes to process")

        enumClasses.forEach { enumClass ->
            extractEnumValues(enumClass)
        }
    }

    private fun log(message: String) {
        config.verbose?.invoke(message)
    }

    /**
     * Extract HTTP endpoints from Spring MVC annotations.
     * Supports @RequestMapping, @GetMapping, @PostMapping, @PutMapping, @DeleteMapping, @PatchMapping
     */
    private fun processEndpoints() {
        view.classes.toList().forEach { sootClass ->
            if (sootClass !is JavaSootClass) return@forEach

            val className = sootClass.type.fullyQualifiedName

            // Get class-level @RequestMapping path prefix
            val classAnnotations = sootClass.annotations
            val classPath = extractRequestMappingPath(classAnnotations)

            sootClass.methods.toList().forEach { method ->
                if (method !is JavaSootMethod) return@forEach

                val methodAnnotations = method.annotations
                val httpMethod = findHttpMethod(methodAnnotations)

                if (httpMethod != null) {
                    val methodPath = extractMappingPath(methodAnnotations)
                    val fullPath = combinePaths(classPath, methodPath)
                    val produces = extractAnnotationArrayValue(methodAnnotations, "produces")
                    val consumes = extractAnnotationArrayValue(methodAnnotations, "consumes")

                    val methodDescriptor = toMethodDescriptor(method)

                    val endpoint = EndpointInfo(
                        method = methodDescriptor,
                        httpMethod = httpMethod,
                        path = fullPath,
                        produces = produces,
                        consumes = consumes
                    )
                    graphBuilder.addEndpoint(endpoint)
                    log("Found endpoint: ${httpMethod.name} $fullPath -> ${className}.${methodDescriptor.name}")
                }
            }
        }
    }

    /**
     * Extract Jackson annotation information from fields and getter methods.
     * Supports @JsonProperty, @JsonIgnore, and @JsonProperty(access = JsonProperty.Access.*)
     */
    private fun processJacksonAnnotations() {
        view.classes.toList().forEach { sootClass ->
            if (sootClass !is JavaSootClass) return@forEach

            val className = sootClass.type.fullyQualifiedName

            // Process field annotations
            sootClass.fields.forEach { field ->
                val fieldName = field.name
                val jacksonInfo = extractJacksonInfo(field.annotations)
                if (jacksonInfo != null) {
                    graphBuilder.addJacksonFieldInfo(className, fieldName, jacksonInfo)
                }
            }

            // Process getter method annotations
            sootClass.methods.forEach { method ->
                if (method !is JavaSootMethod) return@forEach

                val methodName = method.name
                // Only process getter methods (getXxx or isXxx)
                if ((methodName.startsWith("get") && methodName.length > 3) ||
                    (methodName.startsWith("is") && methodName.length > 2)) {
                    val jacksonInfo = extractJacksonInfo(method.annotations)
                    if (jacksonInfo != null) {
                        graphBuilder.addJacksonGetterInfo(className, methodName, jacksonInfo)
                    }
                }
            }
        }
    }

    /**
     * Extract Jackson annotation information from a list of annotations.
     */
    private fun extractJacksonInfo(annotations: Iterable<*>): io.johnsonlee.graphite.graph.JacksonFieldInfo? {
        var jsonName: String? = null
        var isIgnored = false

        for (annot in annotations) {
            val fullName = getAnnotationFullName(annot)

            when {
                fullName == "com.fasterxml.jackson.annotation.JsonIgnore" -> {
                    isIgnored = true
                }
                fullName == "com.fasterxml.jackson.annotation.JsonProperty" -> {
                    val values = getAnnotationValues(annot)
                    // Get the "value" property for the JSON name
                    val value = values["value"]
                    if (value != null && value is String && value.isNotEmpty()) {
                        jsonName = value
                    }
                    // Check for access = JsonProperty.Access.WRITE_ONLY (means ignore in serialization)
                    val access = values["access"]?.toString()
                    if (access?.contains("WRITE_ONLY") == true) {
                        isIgnored = true
                    }
                }
            }
        }

        // Only return if there's something to report
        return if (jsonName != null || isIgnored) {
            io.johnsonlee.graphite.graph.JacksonFieldInfo(jsonName, isIgnored)
        } else {
            null
        }
    }

    private fun findHttpMethod(annotations: Iterable<*>): HttpMethod? {
        for (annot in annotations) {
            val className = getAnnotationClassName(annot)
            val httpMethod = HttpMethod.fromAnnotation(className)
            if (httpMethod != null) return httpMethod
        }
        return null
    }

    private fun getAnnotationClassName(annot: Any?): String {
        if (annot == null) return ""
        // AnnotationUsage has annotation.className property
        return try {
            val annotationProp = annot::class.java.getMethod("getAnnotation").invoke(annot)
            annotationProp?.let {
                it::class.java.getMethod("getClassName").invoke(it)?.toString() ?: ""
            } ?: ""
        } catch (e: Exception) {
            ""
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

    private fun extractRequestMappingPath(annotations: Iterable<*>): String {
        for (annot in annotations) {
            val className = getAnnotationClassName(annot)
            val fullName = getAnnotationFullName(annot)
            if (className == "RequestMapping" || fullName == "org.springframework.web.bind.annotation.RequestMapping") {
                return extractPathFromAnnotation(annot)
            }
        }
        return ""
    }

    private fun extractMappingPath(annotations: Iterable<*>): String {
        for (annot in annotations) {
            val className = getAnnotationClassName(annot)
            if (className.endsWith("Mapping")) {
                return extractPathFromAnnotation(annot)
            }
        }
        return ""
    }

    private fun extractPathFromAnnotation(annot: Any?): String {
        val values = getAnnotationValues(annot)
        val valueElement = values["value"] ?: values["path"]
        return when (valueElement) {
            is String -> valueElement
            is List<*> -> valueElement.firstOrNull()?.toString()?.removeSurrounding("\"") ?: ""
            else -> valueElement?.toString()?.removeSurrounding("[", "]")?.removeSurrounding("\"") ?: ""
        }
    }

    private fun extractAnnotationArrayValue(annotations: Iterable<*>, key: String): List<String> {
        val result = mutableListOf<String>()
        for (annot in annotations) {
            val values = getAnnotationValues(annot)
            val value = values[key]
            when (value) {
                is String -> result.add(value)
                is List<*> -> result.addAll(value.filterIsInstance<String>())
                null -> {} // ignore
                else -> result.add(value.toString().removeSurrounding("[", "]").removeSurrounding("\""))
            }
        }
        return result
    }

    private fun combinePaths(classPath: String, methodPath: String): String {
        val normalizedClass = classPath.trimEnd('/')
        val normalizedMethod = methodPath.trimStart('/')
        return when {
            normalizedClass.isEmpty() && normalizedMethod.isEmpty() -> "/"
            normalizedClass.isEmpty() -> "/$normalizedMethod"
            normalizedMethod.isEmpty() -> normalizedClass
            else -> "$normalizedClass/$normalizedMethod"
        }
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
     * Extract a value from a method argument (either a constant or a local variable).
     */
    private fun extractValueFromArg(arg: Value, localValues: Map<String, Any?>): Any? {
        return when (arg) {
            is SootConstant -> extractConstantValue(arg)
            is Local -> localValues[arg.name]
            else -> null
        }
    }

    private fun processTypeHierarchy() {
        view.classes.forEach { sootClass ->
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
    }

    private fun processMethods() {
        view.classes
            .filter { shouldIncludeClass(it) }
            .forEach { sootClass ->
                sootClass.methods.forEach { method ->
                    processMethod(method)
                }
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

            // Process parameters
            processParameters(method, methodDescriptor)

            // Process each statement
            stmtGraph.stmts.forEach { stmt ->
                processStatement(stmt, methodDescriptor, stmtGraph)
            }
        }
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
            return
        }

        val targetNode = getOrCreateValueNode(leftOp, method)
        val sourceNode = getOrCreateValueNode(rightOp, method)

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

        // Handle method invocations in assignments (e.g., x = foo())
        if (rightOp is AbstractInvokeExpr) {
            processInvokeExpr(rightOp, method, targetNode)
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
        }
    }

    private fun processInvoke(stmt: JInvokeStmt, method: MethodDescriptor) {
        stmt.invokeExpr.ifPresent { invokeExpr ->
            processInvokeExpr(invokeExpr, method, null)
        }
    }

    private fun processInvokeExpr(
        invokeExpr: AbstractInvokeExpr,
        caller: MethodDescriptor,
        resultNode: ValueNode?
    ) {
        val calleeSignature = invokeExpr.methodSignature
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
                is SootLongConstant -> IntConstant(
                    id = nextNodeId("const"),
                    value = constant.value.toInt() // Simplified
                )
                is SootStringConstant -> StringConstant(
                    id = nextNodeId("const"),
                    value = constant.value
                )
                else -> IntConstant(
                    id = nextNodeId("const"),
                    value = 0 // Fallback
                )
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
                isStatic = false // Would need to check the actual field
            )
            graphBuilder.addNode(node)
            node
        }
    }

    private fun extractConstantValue(constant: SootConstant): Any? {
        return when (constant) {
            is SootIntConstant -> constant.value
            is SootLongConstant -> constant.value
            is SootStringConstant -> constant.value
            else -> null
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

    /**
     * Get method return type with generic arguments from bytecode signature.
     */
    private fun getMethodReturnTypeWithGenerics(
        declaringClass: String,
        methodKey: String,
        fallbackType: TypeDescriptor
    ): TypeDescriptor {
        return signatureReader?.getMethodReturnType(declaringClass, methodKey) ?: fallbackType
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
}
