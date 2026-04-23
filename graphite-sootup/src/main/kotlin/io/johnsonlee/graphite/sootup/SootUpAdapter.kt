package io.johnsonlee.graphite.sootup

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.FullGraphBuilder
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.CallGraphAlgorithm
import io.johnsonlee.graphite.input.EmptyResourceAccessor
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.input.ResourceAccessor
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.Properties
import java.util.ResourceBundle
import java.util.ServiceLoader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type as AsmType
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FieldNode as AsmFieldNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import org.w3c.dom.Element
import org.w3c.dom.Node as DomNode
import sootup.core.frontend.BodySource
import sootup.core.graph.StmtGraph
import sootup.core.jimple.basic.NoPositionInformation
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
import sootup.core.model.MethodModifier
import sootup.core.model.SootMethod
import sootup.core.signatures.MethodSignature
import sootup.core.util.Modifiers
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
import sootup.java.bytecode.frontend.conversion.AsmMethodSource
import sootup.java.bytecode.frontend.conversion.AsmUtil

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
    private val trackCrossMethodFunctionalDispatch = config.trackCrossMethodFunctionalDispatch
    private val extractAnnotationsEnabled = config.extractAnnotations

    private data class LocalKey(val method: MethodDescriptor, val name: String)
    private data class ParameterBinding(val method: MethodDescriptor, val index: Int)
    private data class PlaceholderBinding(
        val key: String?,
        val defaultValue: Any?,
        val hasDefaultValue: Boolean
    )
    private data class BundleControlSpec(
        val noFallback: Boolean = false,
        val formats: Set<String> = setOf("java.properties", "java.class"),
        val candidateLocales: List<String>? = null
    )
    private data class LocaleBuilderSpec(
        val language: String? = null,
        val country: String? = null,
        val variant: String? = null,
        val languageTag: String? = null
    ) {
        fun toLocaleSpec(): String? {
            languageTag?.takeIf { it.isNotBlank() }?.let { return it }
            val raw = listOfNotNull(language, country, variant).joinToString("_")
            return raw.takeIf { it.isNotBlank() }
        }
    }

    // Maps to track created nodes for cross-referencing
    private val localNodes = mutableMapOf<LocalKey, LocalVariable>()
    private val fieldNodes = mutableMapOf<String, FieldNode>()
    private val parameterNodes = mutableMapOf<ParameterBinding, ParameterNode>()
    private val constantNodes = mutableMapOf<Any, ConstantNode>()
    private val methodReturnNodes = mutableMapOf<MethodDescriptor, ReturnNode>()
    private val allocationNodes = mutableMapOf<LocalKey, LocalVariable>()

    private val dynamicTargets = mutableMapOf<LocalKey, List<MethodDescriptor>>()

    private val returnDynamicTargets = mutableMapOf<MethodDescriptor, List<MethodDescriptor>>()

    // Maps local key to parameter binding for locals assigned from parameters
    private val localToParamIndex = mutableMapOf<LocalKey, ParameterBinding>()

    private val callSiteDynamicArgs = mutableMapOf<MethodDescriptor, MutableList<Pair<Int, List<MethodDescriptor>>>>()

    private val parameterVirtualCalls = mutableMapOf<MethodDescriptor, MutableList<Pair<Int, CallSiteNode>>>()

    private val callResultLocals = mutableMapOf<MethodDescriptor, MutableList<LocalKey>>()

    private val unresolvedLocalVirtualCalls = mutableMapOf<LocalKey, MutableList<CallSiteNode>>()

    // Maps field key (field signature string) to dynamic targets assigned to that field
    // Enables resolution when a lambda/method reference is stored to a field and later invoked
    private val fieldDynamicTargets = mutableMapOf<String, MutableList<MethodDescriptor>>()

    private val arrayDynamicTargets = mutableMapOf<LocalKey, MutableList<MethodDescriptor>>()
    private val localeSpecsByLocal = mutableMapOf<LocalKey, String>()
    private val localeBuilderSpecsByLocal = mutableMapOf<LocalKey, LocaleBuilderSpec>()
    private val stringValuesByLocal = mutableMapOf<LocalKey, String>()
    private val bundleControlFormatsByLocal = mutableMapOf<LocalKey, String?>()
    private val resourceHandlePathsByLocal = mutableMapOf<LocalKey, LinkedHashSet<String>>()
    private val propertiesPathsByLocal = mutableMapOf<LocalKey, LinkedHashSet<String>>()
    private val resourceBundlePaths = mutableMapOf<LocalKey, LinkedHashSet<String>>()
    private val bundleControlSpecsByLocal = mutableMapOf<LocalKey, BundleControlSpec>()

    private val fieldLoadLocals = mutableMapOf<String, MutableList<LocalKey>>()

    private val resolvedMethodCache = mutableMapOf<MethodSignature, MethodSignature>()
    private val methodDescriptorCache = mutableMapOf<MethodSignature, MethodDescriptor>()
    private val resourceFilesByPath = mutableMapOf<String, MutableList<ResourceFileNode>>()
    private val configurationResourcePaths = linkedSetOf<String>()
    private val runtimeIndexedBundles = mutableSetOf<String>()
    private val bundleControlSpecsByClass = mutableMapOf<String, BundleControlSpec?>()
    private val classesByName: Map<String, SootClass> by lazy {
        buildMap {
            view.classes.forEach { sootClass ->
                put(sootClass.type.fullyQualifiedName, sootClass)
            }
        }
    }

    // Per-method: tracks which NodeIds were created from each statement
    // Reset per method in processMethod()
    private var stmtNodeIds = mutableMapOf<Stmt, MutableList<NodeId>>()
    private var activeMethod: MethodDescriptor? = null
    private var activeMethodLocals = mutableSetOf<LocalKey>()
    private var activeMethodParameters = mutableSetOf<ParameterBinding>()

    /**
     * Build the complete graph from the SootUp view
     */
    fun buildGraph(): Graph {
        indexResourceValues()
        indexClassBundles()
        log("Starting buildGraph pass 1")
        var pass1Count = 0
        // Pass 1: All classes — type hierarchy + enum values
        // Enum values must be fully collected before processing methods
        // (a method in class A may reference an enum from class B)
        view.classes.forEach { sootClass ->
            pass1Count++
            if (pass1Count % 500 == 0) {
                log("Pass 1 processed $pass1Count classes; current=${sootClass.type}")
            }
            processTypeHierarchyForClass(sootClass)
            if (sootClass.isEnum) {
                extractEnumValues(sootClass)
            }
        }

        log("Starting buildGraph pass 2")
        var pass2Count = 0
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
                pass2Count++
                if (pass2Count % 100 == 0) {
                    log("Pass 2 processed $pass2Count classes; current=${sootClass.type}")
                }
                if (extractAnnotationsEnabled && sootClass is JavaSootClass) {
                    val className = sootClass.type.fullyQualifiedName
                    extractAnnotations(sootClass.annotations, className, "<class>")
                    sootClass.fields.forEach { field ->
                        extractAnnotations(field.annotations, className, field.name)
                    }
                }

                forEachMethod(sootClass) { method ->
                    processMethod(method)
                    if (extractAnnotationsEnabled && sootClass is JavaSootClass && method is JavaSootMethod) {
                        extractAnnotations(method.annotations, sootClass.type.fullyQualifiedName, method.name)
                    }
                }

                visitFieldsForClass(sootClass)
                extensions.forEach { it.visit(sootClass, extensionContext) }
            }

        // Pass 2B: Resolve cross-method functional interface dispatch
        if (trackCrossMethodFunctionalDispatch) {
            resolveFunctionalDispatch()
        }

        // Build call graph if configured
        if (config.buildCallGraph) {
            processCallGraph()
        }

        log("Starting graphBuilder.build()")
        graphBuilder.setResources(resourceAccessor)
        return graphBuilder.build().also {
            log("Finished graphBuilder.build()")
        }
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

        val clinit = firstMethod(enumClass) { it.name == "<clinit>" && it.isStatic }
        if (clinit == null) {
            log("  No <clinit> found for $className")
            return
        }

        if (!clinit.hasBody()) {
            log("  <clinit> has no body for $className")
            return
        }

        val body = clinit.body
        val stmtGraph = body.stmtGraph

        // Track local variable assignments: localName -> value (for constants)
        val localValues = mutableMapOf<String, Any?>()
        // Track local variable aliases: localName -> original localName (for tracking new objects)
        val localAliases = mutableMapOf<String, String>()

        for (stmt in stmtGraph) {
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
                            val initValues = findEnumInitValues(originalLocal, stmtGraph, localValues, className)
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
    private fun findEnumInitValues(localName: String, stmtGraph: StmtGraph<*>, localValues: Map<String, Any?>, className: String): List<Any?> {
        for (stmt in stmtGraph) {
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
        val configurationPropertiesPrefix = extractConfigurationPropertiesPrefix(sootClass)
        val valueAnnotationsByField = if (sootClass is JavaSootClass) {
            val fromSoot = sootClass.fields.associate { it.signature.toString() to extractValueAnnotationBinding(it.annotations) }
            val fromAsm = getAsmFieldNodes(sootClass)
                ?.associate { asmField -> asmField.name to extractValueAnnotationBinding(asmField) }
                ?: emptyMap()
            sootClass.fields.associate { field ->
                field.signature.toString() to (fromSoot[field.signature.toString()] ?: fromAsm[field.name])
            }
        } else {
            emptyMap()
        }
        sootClass.fields.forEach { field ->
            val fieldName = field.name

            // Skip synthetic fields
            if (fieldName.startsWith("\$") || fieldName.startsWith("this\$")) {
                return@forEach
            }

            // Use field signature as key (same format as getOrCreateField)
            val fieldSig = field.signature.toString()
            if (fieldNodes.containsKey(fieldSig)) {
                linkValueAnnotatedField(valueAnnotationsByField[fieldSig], fieldNodes[fieldSig]!!)
                linkConfigurationPropertiesField(configurationPropertiesPrefix, field.name, fieldNodes[fieldSig]!!)
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
            linkValueAnnotatedField(valueAnnotationsByField[fieldSig], node)
            linkConfigurationPropertiesField(configurationPropertiesPrefix, field.name, node)
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
        methodReturnNodes[methodDescriptor] = returnNode
        graphBuilder.addNode(returnNode)

        activeMethod = methodDescriptor
        activeMethodLocals = mutableSetOf()
        activeMethodParameters = mutableSetOf()

        try {
            // Process method body if available
            if (method.hasBody()) {
                val body = method.body
                val stmtGraph = body.stmtGraph

                // Reset per-method stmt tracking
                stmtNodeIds = mutableMapOf()

                // Process parameters
                processParameters(method, methodDescriptor)

                // Process each statement (pass 1: data flow)
                var stmtCount = 0
                for (stmt in stmtGraph) {
                    processStatement(stmt, methodDescriptor, stmtGraph)
                    stmtCount++
                }

                // Process control flow (pass 2: branch structure)
                if (stmtCount <= MAX_CONTROL_FLOW_STATEMENTS) {
                    processControlFlow(stmtGraph, methodDescriptor)
                } else {
                    log("Skipping control-flow extraction for ${methodDescriptor.signature}: $stmtCount statements exceed $MAX_CONTROL_FLOW_STATEMENTS")
                }
            }
        } catch (oom: OutOfMemoryError) {
            // Android/large corpus can contain a few pathological methods whose CFG
            // materialization explodes heap. Skip the offending method so graph build
            // can continue instead of failing the entire load.
            log("Skipping method due to OOM while building ${methodDescriptor.signature}: ${oom.message}")
            System.gc()
        } finally {
            clearMethodState(methodDescriptor)
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
            parameterNodes[parameterBinding(methodDescriptor, index)] = paramNode
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
            allocationNodes[localKey(method, leftOp.name)] = allocNode
            if (isLocaleBuilderClassName(allocType.className)) {
                localeBuilderSpecsByLocal[localKey(method, leftOp.name)] = LocaleBuilderSpec()
            }
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
            val localKey = localKey(method, rightOp.name)
            val targets = dynamicTargets[localKey]
            if (trackCrossMethodFunctionalDispatch && targets != null) {
                val fieldKey = leftOp.fieldSignature.toString()
                fieldDynamicTargets.getOrPut(fieldKey) { mutableListOf() }.addAll(targets)
            }
        }

        // Track dynamic targets flowing through array stores (varargs):
        // When a local with dynamic targets is stored into an array element, remember the mapping
        if (leftOp is JArrayRef && rightOp is Local) {
            val base = leftOp.base
            if (base is Local) {
                val rightKey = localKey(method, rightOp.name)
                val targets = dynamicTargets[rightKey]
                if (targets != null) {
                    val arrayKey = localKey(method, base.name)
                    arrayDynamicTargets.getOrPut(arrayKey) { mutableListOf() }.addAll(targets)
                }
            }
        }

        // Track dynamic targets flowing through array loads:
        // When an array element is loaded into a local, propagate the array's dynamic targets
        if (rightOp is JArrayRef && targetNode is LocalVariable) {
            val base = rightOp.base
            if (base is Local) {
                val arrayKey = localKey(method, base.name)
                val targets = arrayDynamicTargets[arrayKey]
                if (targets != null) {
                    val localKey = localKey(method, targetNode.name)
                    dynamicTargets[localKey] = mergeTargets(dynamicTargets[localKey], targets)
                }
            }
        }

        // Track dynamic targets flowing through field loads:
        // When a field with dynamic targets is loaded into a local, propagate the targets.
        // If the field has no dynamic targets yet (e.g., constructor not processed yet),
        // record for deferred resolution in resolveFunctionalDispatch.
        if (rightOp is JFieldRef && targetNode is LocalVariable) {
            val fieldKey = rightOp.fieldSignature.toString()
            val localKey = localKey(method, targetNode.name)
            val targets = fieldDynamicTargets[fieldKey]
            if (targets != null) {
                dynamicTargets[localKey] = mergeTargets(dynamicTargets[localKey], targets)
            } else if (trackCrossMethodFunctionalDispatch) {
                // Record for deferred resolution
                fieldLoadLocals.getOrPut(fieldKey) { mutableListOf() }.add(localKey)
            }
        }

        if (leftOp is Local) {
            val targetKey = localKey(method, leftOp.name)
            when (rightOp) {
                is SootStringConstant -> stringValuesByLocal[targetKey] = rightOp.value
                is Local -> stringValuesByLocal[localKey(method, rightOp.name)]?.let { stringValuesByLocal[targetKey] = it }
            }
            when (rightOp) {
                is Local -> {
                    localeSpecsByLocal[localKey(method, rightOp.name)]?.let { localeSpecsByLocal[targetKey] = it }
                    localeBuilderSpecsByLocal[localKey(method, rightOp.name)]?.let { localeBuilderSpecsByLocal[targetKey] = it }
                    bundleControlFormatsByLocal[localKey(method, rightOp.name)]?.let { bundleControlFormatsByLocal[targetKey] = it }
                    resourceHandlePathsByLocal[localKey(method, rightOp.name)]?.let { resourceHandlePathsByLocal[targetKey] = LinkedHashSet(it) }
                    propertiesPathsByLocal[localKey(method, rightOp.name)]?.let { propertiesPathsByLocal[targetKey] = LinkedHashSet(it) }
                    resourceBundlePaths[localKey(method, rightOp.name)]?.let { resourceBundlePaths[targetKey] = LinkedHashSet(it) }
                    bundleControlSpecsByLocal[localKey(method, rightOp.name)]?.let { bundleControlSpecsByLocal[targetKey] = it }
                }
                is JStaticFieldRef -> {
                    extractLocaleSpec(method, rightOp)?.let { localeSpecsByLocal[targetKey] = it }
                    extractControlFormat(method, rightOp)?.let { bundleControlFormatsByLocal[targetKey] = it }
                }
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
            val paramKey = parameterBinding(method, paramIndex)
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
                val localKey = localKey(method, leftOp.name)
                localToParamIndex[localKey] = parameterBinding(method, paramIndex)
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

        if (calleeSignature.declClassType.fullyQualifiedName == "java.util.Locale" && calleeSignature.name == "<init>") {
            val receiverLocal = (invokeExpr as? AbstractInstanceInvokeExpr)?.base as? Local
            val localeSpec = extractConstructedLocaleSpec(invokeExpr)
            if (receiverLocal != null && localeSpec != null) {
                localeSpecsByLocal[localKey(caller, receiverLocal.name)] = localeSpec
            }
        }
        updateLocaleBuilderState(caller, calleeSignature, invokeExpr, resultNode)

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
        linkResourceReads(callSite, calleeSignature, invokeExpr)
        linkResourceFileReads(callSite, calleeSignature, invokeExpr, caller)
        linkResourceBundleReads(callSite, calleeSignature, invokeExpr, caller)
        linkStructuredResourceLoads(callSite, calleeSignature, invokeExpr, caller)
        trackResourceAssociations(callSite, calleeSignature, invokeExpr, caller, resultNode)

        // Resolve functional interface dispatch:
        // If the receiver was assigned from an invokedynamic, connect this
        // virtual call (e.g., Function.apply) to the actual target method
        if (receiverNode is LocalVariable) {
            val key = localKey(caller, receiverNode.name)
            val targets = dynamicTargets[key]

            // If this local has no direct dynamic targets, record for
            // cross-method resolution in resolveFunctionalDispatch()
            if (targets == null) {
                if (trackCrossMethodFunctionalDispatch) {
                    val paramInfo = localToParamIndex[key]
                    if (paramInfo != null) {
                        parameterVirtualCalls
                            .getOrPut(paramInfo.method) { mutableListOf() }
                            .add(paramInfo.index to callSite)
                    } else {
                        unresolvedLocalVirtualCalls
                            .getOrPut(key) { mutableListOf() }
                            .add(callSite)
                    }
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
                    val argKey = localKey(caller, arg.name)
                    val targets = dynamicTargets[argKey] ?: arrayDynamicTargets[argKey]
                    if (trackCrossMethodFunctionalDispatch && targets != null) {
                        callSiteDynamicArgs
                            .getOrPut(callee) { mutableListOf() }
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
            val resultKey = localKey(caller, resultNode.name)
            extractResourceLookupPath(caller, calleeSignature, invokeExpr)?.let { resourceHandlePathsByLocal[resultKey] = linkedSetOf(it) }
            extractResourceBundlePaths(caller, calleeSignature, invokeExpr)?.let { resourceBundlePaths[resultKey] = LinkedHashSet(it) }
            extractLocaleFactorySpec(calleeSignature, invokeExpr)?.let { localeSpecsByLocal[resultKey] = it }
            extractBundleControlSpec(caller, calleeSignature, invokeExpr)?.let { bundleControlSpecsByLocal[resultKey] = it }
            if (trackCrossMethodFunctionalDispatch) {
                callResultLocals
                    .getOrPut(callee) { mutableListOf() }
                    .add(resultKey)
            }
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
            val key = localKey(caller, resultNode.name)
            dynamicTargets[key] = mergeTargets(dynamicTargets[key], targetMethods)
        }

        // Track dynamic targets flowing directly to a field store:
        // e.g., this.mapper = invokedynamic(...) where resultNode is a FieldNode
        if (resultNode is FieldNode && targetMethods.isNotEmpty() && stmt is JAssignStmt) {
            val leftOp = stmt.leftOp
            if (trackCrossMethodFunctionalDispatch && leftOp is JFieldRef) {
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
        private val NULL_CONSTANT_KEY = Any()
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
        private const val LOCALE_BUILDER_CLASS = "java.util.Locale\$Builder"
        private const val LOCALE_BUILDER_CLASS_ALT = "java.util.Locale.Builder"
        private const val RESOURCE_BUNDLE_CONTROL_CLASS = "java.util.ResourceBundle\$Control"
        private const val RESOURCE_BUNDLE_CONTROL_CLASS_ALT = "java.util.ResourceBundle.Control"
        private const val MAX_CONTROL_FLOW_STATEMENTS = 2_000
    }

    private fun isLocaleBuilderClassName(name: String): Boolean =
        name == LOCALE_BUILDER_CLASS || name == LOCALE_BUILDER_CLASS_ALT

    private fun isResourceBundleControlTypeName(name: String): Boolean =
        name == RESOURCE_BUNDLE_CONTROL_CLASS || name == RESOURCE_BUNDLE_CONTROL_CLASS_ALT

    private fun processReturn(stmt: JReturnStmt, method: MethodDescriptor) {
        val returnValue = stmt.op
        val returnNode = methodReturnNodes[method] ?: return

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
                val key = localKey(method, valueNode.name)
                val targets = dynamicTargets[key]
                if (trackCrossMethodFunctionalDispatch && targets != null) {
                    returnDynamicTargets[method] = mergeTargets(returnDynamicTargets[method], targets)
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
        for (stmt in stmtGraph) {
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
            val trueBranchStmts = walkBranch(trueSuccessor, falseSuccessor, stmtGraph)
            val falseBranchStmts = walkBranch(falseSuccessor, trueSuccessor, stmtGraph)

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
        stmtGraph: StmtGraph<*>
    ): Set<Stmt> {
        // Collect statements reachable from the other branch (to find merge points)
        val otherReachable = collectReachable(otherBranchStart, stmtGraph)

        val result = mutableSetOf<Stmt>()
        val queue = ArrayDeque<Stmt>()
        queue.add(start)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current in result) continue

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
                dynamicTargets[localKey] = mergeTargets(dynamicTargets[localKey], targets)
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
            forEachMethod(sootClass) { method ->
                if (method.name == "main" && method.isStatic) {
                    entryPoints.add(method.signature)
                }
            }
        }
        return entryPoints
    }

    private fun forEachMethod(sootClass: SootClass, action: (SootMethod) -> Unit) {
        streamMethodsOrNull(sootClass)?.forEach(action) ?: resolveMethodsOrEmpty(sootClass).forEach(action)
    }

    private fun firstMethod(sootClass: SootClass, predicate: (SootMethod) -> Boolean): SootMethod? {
        streamMethodsOrNull(sootClass)?.firstOrNull(predicate)?.let { return it }
        return resolveMethodsOrEmpty(sootClass).firstOrNull(predicate)
    }

    private fun streamMethodsOrNull(sootClass: SootClass): Sequence<SootMethod>? {
        if (sootClass !is JavaSootClass) return null
        val methodNodes = getAsmMethodNodes(sootClass) ?: return null
        return methodNodes.asSequence().mapNotNull { methodNode ->
            try {
                createStreamingMethod(sootClass, methodNode)
            } catch (oom: OutOfMemoryError) {
                log("Skipping method ${sootClass.type}.${methodNode.name}${methodNode.desc}: OOM during streaming resolution")
                System.gc()
                null
            } catch (e: Exception) {
                log("Skipping method ${sootClass.type}.${methodNode.name}${methodNode.desc}: ${e.message}")
                null
            }
        }
    }

    private fun getAsmMethodNodes(sootClass: JavaSootClass): List<MethodNode>? {
        val classNode = getAsmClassNode(sootClass) ?: return null
        @Suppress("UNCHECKED_CAST")
        return classNode.methods as? List<MethodNode>
    }

    private fun getAsmFieldNodes(sootClass: JavaSootClass): List<AsmFieldNode>? {
        return try {
            val resourcePath = sootClass.type.fullyQualifiedName.replace('.', '/') + ".class"
            resourceAccessor.open(resourcePath).use { input ->
                val classNode = ClassNode()
                ClassReader(input).accept(classNode, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                @Suppress("UNCHECKED_CAST")
                classNode.fields as? List<AsmFieldNode>
            }
        } catch (_: Exception) {
            val classNode = getAsmClassNode(sootClass) ?: return null
            @Suppress("UNCHECKED_CAST")
            classNode.fields as? List<AsmFieldNode>
        }
    }

    private fun loadMethodNodesFromResource(sootClass: JavaSootClass): List<MethodNode>? {
        return try {
            val resourcePath = sootClass.type.fullyQualifiedName.replace('.', '/') + ".class"
            resourceAccessor.open(resourcePath).use { input ->
                val classNode = ClassNode()
                ClassReader(input).accept(classNode, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                @Suppress("UNCHECKED_CAST")
                classNode.methods as? List<MethodNode>
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getAsmClassNode(sootClass: JavaSootClass): ClassNode? {
        val classSource = sootClass.classSource
        if (classSource.javaClass.name != "sootup.java.bytecode.frontend.conversion.AsmClassSource") {
            return null
        }

        return try {
            val classNodeField = classSource.javaClass.getDeclaredField("classNode")
            classNodeField.isAccessible = true
            classNodeField.get(classSource) as? ClassNode
        } catch (_: ReflectiveOperationException) {
            null
        }
    }

    private fun createStreamingMethod(sootClass: JavaSootClass, methodNode: MethodNode): JavaSootMethod? {
        val bodySource = methodNode as? BodySource ?: return null
        val asmMethodSource = methodNode as? AsmMethodSource ?: return null

        val setDeclaringClass = AsmMethodSource::class.java.getDeclaredMethod("setDeclaringClass", ClassType::class.java)
        setDeclaringClass.isAccessible = true
        setDeclaringClass.invoke(asmMethodSource, sootClass.type)

        val annotations = buildList {
            methodNode.visibleAnnotations?.let { addAll(AsmUtil.createAnnotationUsage(it).toList()) }
            methodNode.invisibleAnnotations?.let { addAll(AsmUtil.createAnnotationUsage(it).toList()) }
        }

        return JavaSootMethod(
            bodySource,
            asmMethodSource.getSignature(),
            Modifiers.getMethodModifiers(methodNode.access),
            AsmUtil.asmIdToSignature(methodNode.exceptions ?: emptyList()),
            annotations,
            NoPositionInformation.getInstance()
        )
    }

    private fun resolveMethodsOrEmpty(sootClass: SootClass): Set<out SootMethod> {
        return try {
            sootClass.methods
        } catch (oom: OutOfMemoryError) {
            log("Skipping methods for ${sootClass.type}: OOM during method resolution")
            System.gc()
            emptySet()
        } catch (e: IllegalStateException) {
            log("Skipping methods for ${sootClass.type}: ${e.message}")
            emptySet()
        }
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
        val key = localKey(method, local.name)
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
        val key = localKey(method, local.name)
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

    private fun getOrCreateConstantValue(value: Any?): ConstantNode {
        val cacheKey = value ?: NULL_CONSTANT_KEY
        return constantNodes.getOrPut(cacheKey) {
            val node = when (value) {
                null -> NullConstant(
                    id = nextNodeId("const")
                )
                is Int -> IntConstant(
                    id = nextNodeId("const"),
                    value = value
                )
                is Long -> LongConstant(
                    id = nextNodeId("const"),
                    value = value
                )
                is Float -> FloatConstant(
                    id = nextNodeId("const"),
                    value = value
                )
                is Double -> DoubleConstant(
                    id = nextNodeId("const"),
                    value = value
                )
                is Boolean -> BooleanConstant(
                    id = nextNodeId("const"),
                    value = value
                )
                else -> StringConstant(
                    id = nextNodeId("const"),
                    value = value.toString()
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

    private fun indexResourceValues() {
        resourceAccessor.list("**")
            .forEach { entry ->
                if (entry.path.endsWith(".class", ignoreCase = true)) return@forEach
                val format = resourceFormat(entry.path)
                val profile = resourceProfile(entry.path)
                val fileNode = ResourceFileNode(
                    id = nextNodeId("resource"),
                    path = entry.path,
                    source = entry.source,
                    format = format,
                    profile = profile
                )
                graphBuilder.addNode(fileNode)
                resourceFilesByPath.getOrPut(entry.path) { mutableListOf() }.add(fileNode)
                if (isResourceConfig(entry.path)) {
                    configurationResourcePaths += entry.path
                }
            }
    }

    private fun indexClassBundles() {
        view.classes.forEach { sootClass ->
            if (!isListResourceBundleClass(sootClass)) return@forEach
            indexListResourceBundle(sootClass)
        }
    }

    private fun indexListResourceBundle(sootClass: SootClass) {
        val path = sootClass.type.fullyQualifiedName
        if (resourceFilesByPath.containsKey(path)) return
        val fileNode = ResourceFileNode(
            id = nextNodeId("resource"),
            path = path,
            source = "class-bundle",
            format = "listbundle",
            profile = null
        )
        graphBuilder.addNode(fileNode)
        resourceFilesByPath.getOrPut(path) { mutableListOf() }.add(fileNode)
    }

    private fun isListResourceBundleClass(sootClass: SootClass): Boolean {
        var current: SootClass? = sootClass
        while (current != null) {
            val superType = current.superclass.orElse(null) ?: return false
            val superName = superType.fullyQualifiedName
            if (superName == "java.util.ListResourceBundle") return true
            current = resolveClassByName(superName)
        }
        return false
    }

    private fun resolveClassByName(className: String): SootClass? {
        return classesByName[className]
    }

    private fun extractListResourceBundleEntries(sootClass: SootClass): Map<String, Any?> {
        val javaSootClass = sootClass as? JavaSootClass ?: return emptyMap()
        val methodNode = (getAsmMethodNodes(javaSootClass) ?: loadMethodNodesFromResource(javaSootClass))
            ?.firstOrNull { it.name == "getContents" }
            ?: return emptyMap()
        val returned = evaluateArrayLiteral(methodNode) as? List<*> ?: return emptyMap()
        return buildMap {
            returned.forEach { entry ->
                val tuple = entry as? List<*> ?: return@forEach
                val key = tuple.getOrNull(0) as? String ?: return@forEach
                put(key, normalizeRuntimeBundleValue(tuple.getOrNull(1)))
            }
        }
    }

    private fun evaluateArrayLiteral(methodNode: MethodNode): Any? {
        val stack = ArrayDeque<Any?>()
        val locals = mutableMapOf<Int, Any?>()
        for (insn in methodNode.instructions) {
            when (insn) {
                is InsnNode -> when (insn.opcode) {
                    Opcodes.ACONST_NULL -> stack.addLast(null)
                    Opcodes.ICONST_M1 -> stack.addLast(-1)
                    Opcodes.ICONST_0 -> stack.addLast(0)
                    Opcodes.ICONST_1 -> stack.addLast(1)
                    Opcodes.ICONST_2 -> stack.addLast(2)
                    Opcodes.ICONST_3 -> stack.addLast(3)
                    Opcodes.ICONST_4 -> stack.addLast(4)
                    Opcodes.ICONST_5 -> stack.addLast(5)
                    Opcodes.DUP -> stack.lastOrNull()?.let(stack::addLast)
                    Opcodes.AASTORE -> {
                        val value = stack.removeLastOrNull()
                        val index = (stack.removeLastOrNull() as? Number)?.toInt() ?: continue
                        val array = stack.removeLastOrNull() as? MutableList<Any?> ?: continue
                        if (index in array.indices) array[index] = value
                    }
                    Opcodes.ARETURN -> return stack.removeLastOrNull()
                    Opcodes.ALOAD, Opcodes.ILOAD -> Unit
                    else -> Unit
                }
                is IntInsnNode -> when (insn.opcode) {
                    Opcodes.BIPUSH, Opcodes.SIPUSH -> stack.addLast(insn.operand)
                }
                is LdcInsnNode -> stack.addLast(insn.cst)
                is TypeInsnNode -> if (insn.opcode == Opcodes.ANEWARRAY) {
                    val size = (stack.removeLastOrNull() as? Number)?.toInt() ?: continue
                    stack.addLast(MutableList<Any?>(size) { null })
                }
                is VarInsnNode -> when (insn.opcode) {
                    Opcodes.ASTORE, Opcodes.ISTORE -> locals[insn.`var`] = stack.removeLastOrNull()
                    Opcodes.ALOAD, Opcodes.ILOAD -> stack.addLast(locals[insn.`var`])
                }
                else -> Unit
            }
        }
        return null
    }

    private fun extractControlFormatsFromMethod(methodNode: MethodNode): Set<String>? =
        when (val value = evaluateLiteralMethod(methodNode)) {
            is List<*> -> value.mapNotNull {
                when (it) {
                    "java.class", "java.properties" -> it as String
                    else -> null
                }
            }.toSet().takeIf { it.isNotEmpty() }
            "java.class" -> setOf("java.class")
            "java.properties" -> setOf("java.properties")
            else -> null
        }

    private fun extractCandidateLocalesFromMethod(methodNode: MethodNode): List<String>? =
        (evaluateLiteralMethod(methodNode) as? List<*>)
            ?.mapNotNull { value ->
                when (value) {
                    is String -> normalizeLocaleSpec(value)
                    else -> null
                }
            }
            ?.takeIf { it.isNotEmpty() }

    private fun returnsNullLiteral(methodNode: MethodNode): Boolean =
        evaluateLiteralMethod(methodNode) == null

    private fun evaluateLiteralMethod(methodNode: MethodNode): Any? {
        val stack = ArrayDeque<Any?>()
        val locals = mutableMapOf<Int, Any?>()
        val argTypes = AsmType.getArgumentTypes(methodNode.desc)
        var localIndex = if ((methodNode.access and Opcodes.ACC_STATIC) != 0) 0 else 1
        argTypes.forEach { argType ->
            locals[localIndex] = when (argType.className) {
                "java.util.Locale" -> "arg-locale"
                "java.lang.String" -> "arg-string"
                else -> null
            }
            localIndex += argType.size
        }
        for (insn in methodNode.instructions) {
            when (insn) {
                is InsnNode -> when (insn.opcode) {
                    Opcodes.ACONST_NULL -> stack.addLast(null)
                    Opcodes.ICONST_M1 -> stack.addLast(-1)
                    Opcodes.ICONST_0 -> stack.addLast(0)
                    Opcodes.ICONST_1 -> stack.addLast(1)
                    Opcodes.ICONST_2 -> stack.addLast(2)
                    Opcodes.ICONST_3 -> stack.addLast(3)
                    Opcodes.ICONST_4 -> stack.addLast(4)
                    Opcodes.ICONST_5 -> stack.addLast(5)
                    Opcodes.DUP -> stack.lastOrNull()?.let(stack::addLast)
                    Opcodes.ARETURN -> return stack.removeLastOrNull()
                }
                is IntInsnNode -> when (insn.opcode) {
                    Opcodes.BIPUSH, Opcodes.SIPUSH -> stack.addLast(insn.operand)
                }
                is LdcInsnNode -> stack.addLast(insn.cst)
                is VarInsnNode -> when (insn.opcode) {
                    Opcodes.ASTORE, Opcodes.ISTORE -> locals[insn.`var`] = stack.removeLastOrNull()
                    Opcodes.ALOAD, Opcodes.ILOAD -> stack.addLast(locals[insn.`var`])
                }
                is FieldInsnNode -> if (insn.opcode == Opcodes.GETSTATIC) {
                    stack.addLast(resolveStaticLiteral(insn.owner.replace('/', '.'), insn.name))
                }
                is MethodInsnNode -> {
                    val args = buildList {
                        repeat(AsmType.getArgumentTypes(insn.desc).size) {
                            add(0, stack.removeLastOrNull())
                        }
                    }
                    val owner = insn.owner.replace('/', '.')
                    val result = when {
                        insn.opcode == Opcodes.INVOKESTATIC && owner == "java.util.List" && insn.name == "of" -> args
                        insn.opcode == Opcodes.INVOKESTATIC && owner == "java.util.Arrays" && insn.name == "asList" -> {
                            val first = args.singleOrNull()
                            when (first) {
                                is List<*> -> first
                                else -> args
                            }
                        }
                        insn.opcode == Opcodes.INVOKESTATIC && owner == "java.util.Collections" && insn.name == "singletonList" -> args
                        insn.opcode == Opcodes.INVOKESTATIC && owner == "java.util.Locale" && insn.name == "forLanguageTag" -> {
                            (args.firstOrNull() as? String)?.let(::normalizeLocaleSpec)
                        }
                        insn.opcode == Opcodes.INVOKESPECIAL && owner == "java.util.Locale" && insn.name == "<init>" -> {
                            null
                        }
                        else -> null
                    }
                    if (AsmType.getReturnType(insn.desc).sort != AsmType.VOID) {
                        stack.addLast(result)
                    }
                }
                else -> Unit
            }
        }
        return null
    }

    private fun resolveStaticLiteral(owner: String, fieldName: String): Any? = when {
        fieldName == "FORMAT_CLASS" -> listOf("java.class")
        fieldName == "FORMAT_PROPERTIES" -> listOf("java.properties")
        fieldName == "FORMAT_DEFAULT" -> listOf("java.class", "java.properties")
        owner == "java.util.ResourceBundle.Control" || owner == "java.util.ResourceBundle\$Control" -> when (fieldName) {
            "FORMAT_CLASS" -> listOf("java.class")
            "FORMAT_PROPERTIES" -> listOf("java.properties")
            "FORMAT_DEFAULT" -> listOf("java.class", "java.properties")
            else -> null
        }
        owner == "java.util.Locale" -> extractLocaleSpec(fieldName)
        else -> null
    }

    private fun linkResourceReads(callSite: CallSiteNode, calleeSignature: MethodSignature, invokeExpr: AbstractInvokeExpr) {
        val configKey = extractResourceLookupKey(calleeSignature, invokeExpr)
        val resourcePaths = extractBoundResourcePaths(callSite.caller, calleeSignature, invokeExpr)
        if (configKey != null) {
            lookupResourceFiles(resourcePaths).forEach { resourceFile ->
                graphBuilder.addEdge(ResourceEdge(resourceFile.id, callSite.id, ResourceRelation.LOOKUP))
            }
            return
        }

        if (isResourceEnumerationCall(calleeSignature)) {
            resourcePaths?.forEach { path ->
                resourceFilesByPath[path]?.forEach { resourceFile ->
                    graphBuilder.addEdge(ResourceEdge(resourceFile.id, callSite.id, ResourceRelation.ENUMERATES))
                }
            }
        }
    }

    private fun linkResourceFileReads(
        callSite: CallSiteNode,
        calleeSignature: MethodSignature,
        invokeExpr: AbstractInvokeExpr,
        caller: MethodDescriptor
    ) {
        val resourcePaths = extractResourceBundlePaths(caller, calleeSignature, invokeExpr)
            ?: extractResourceLookupPath(caller, calleeSignature, invokeExpr)?.let(::listOf)
            ?: return
        resourcePaths.forEach { resourcePath ->
            resourceFilesByPath[resourcePath]?.forEach { resourceFile ->
                graphBuilder.addEdge(
                    ResourceEdge(
                        from = resourceFile.id,
                        to = callSite.id,
                        kind = if (isResourceBundleCall(calleeSignature)) ResourceRelation.BUNDLE_CANDIDATE else ResourceRelation.OPENS
                    )
                )
            }
        }
    }

    private fun extractResourceLookupKey(calleeSignature: MethodSignature, invokeExpr: AbstractInvokeExpr): String? {
        val declaringClass = calleeSignature.declClassType.fullyQualifiedName
        val methodName = calleeSignature.name
        if (methodName !in setOf("getProperty", "getString", "getObject")) return null
        val supported = declaringClass == "java.util.Properties" ||
            declaringClass == "java.util.PropertyResourceBundle" ||
            declaringClass == "java.util.ResourceBundle" ||
            declaringClass == "java.lang.System" ||
            declaringClass == "org.springframework.core.env.Environment" ||
            declaringClass == "org.springframework.core.env.PropertyResolver"
        if (!supported || invokeExpr.args.isEmpty()) return null
        val firstArg = invokeExpr.args[0]
        return if (firstArg is SootStringConstant) firstArg.value else null
    }

    private fun extractResourceLookupPath(
        caller: MethodDescriptor,
        calleeSignature: MethodSignature,
        invokeExpr: AbstractInvokeExpr
    ): String? {
        val declaringClass = calleeSignature.declClassType.fullyQualifiedName
        val methodName = calleeSignature.name
        val supported = (declaringClass == "java.lang.ClassLoader" && methodName in setOf("getResource", "getResourceAsStream")) ||
            (declaringClass == "java.lang.Class" && methodName in setOf("getResource", "getResourceAsStream"))
        if (!supported || invokeExpr.args.isEmpty()) return null
        val firstArg = invokeExpr.args[0] as? SootStringConstant ?: return null
        return normalizeResourcePath(caller, declaringClass, firstArg.value)
    }

    private fun extractResourceBundlePaths(
        caller: MethodDescriptor,
        calleeSignature: MethodSignature,
        invokeExpr: AbstractInvokeExpr
    ): LinkedHashSet<String>? {
        val declaringClass = calleeSignature.declClassType.fullyQualifiedName
        val methodName = calleeSignature.name
        if (declaringClass != "java.util.ResourceBundle" || methodName != "getBundle" || invokeExpr.args.isEmpty()) {
            return null
        }
        val firstArg = invokeExpr.args[0] as? SootStringConstant ?: return null
        val baseName = firstArg.value
        val basePath = baseName.replace('.', '/')
        val localeArg = calleeSignature.parameterTypes
            .indexOfFirst { it.toString() == "java.util.Locale" }
            .takeIf { it >= 0 }
            ?.let(invokeExpr.args::getOrNull)
        val controlArg = calleeSignature.parameterTypes
            .indexOfFirst { isResourceBundleControlTypeName(it.toString()) }
            .takeIf { it >= 0 }
            ?.let(invokeExpr.args::getOrNull)
        val localeSpec = localeArg?.let { extractLocaleSpec(caller, it) }
        val controlSpec = controlArg?.let { extractBundleControlSpec(caller, it, baseName, localeSpec) }
        ensureRuntimeBundleIndexed(baseName, localeSpec, controlSpec)
        return if (localeArg == null) {
            collectBundleCandidates(baseName, basePath, controlSpec)
        } else if (localeSpec != null) {
            buildResourceBundleCandidatePaths(baseName, basePath, localeSpec, controlSpec)
        } else {
            collectMatchingResourceBundlePaths(baseName, basePath, controlSpec)
        }
    }

    private fun linkResourceBundleReads(
        callSite: CallSiteNode,
        calleeSignature: MethodSignature,
        invokeExpr: AbstractInvokeExpr,
        caller: MethodDescriptor
    ) {
        val declaringClass = calleeSignature.declClassType.fullyQualifiedName
        val methodName = calleeSignature.name
        if (declaringClass != "java.util.ResourceBundle" || methodName !in setOf("getString", "getObject", "getKeys")) return
        val receiverLocal = (invokeExpr as? AbstractInstanceInvokeExpr)?.base as? Local ?: return
        val bundlePaths = resourceBundlePaths[localKey(caller, receiverLocal.name)] ?: return
        if (methodName == "getKeys") {
            bundlePaths.forEach { bundlePath ->
                resourceFilesByPath[bundlePath]?.forEach { resourceFile ->
                    graphBuilder.addEdge(ResourceEdge(resourceFile.id, callSite.id, ResourceRelation.ENUMERATES))
                }
            }
            return
        }
        bundlePaths.forEach { bundlePath ->
            resourceFilesByPath[bundlePath]
                ?.forEach { resourceFile ->
                    graphBuilder.addEdge(ResourceEdge(resourceFile.id, callSite.id, ResourceRelation.LOOKUP))
                }
        }
    }

    private fun linkStructuredResourceLoads(
        callSite: CallSiteNode,
        calleeSignature: MethodSignature,
        invokeExpr: AbstractInvokeExpr,
        caller: MethodDescriptor
    ) {
        if (!isStructuredResourceLoadCall(calleeSignature)) return
        extractBoundResourcePaths(caller, calleeSignature, invokeExpr)?.forEach { resourcePath ->
            resourceFilesByPath[resourcePath]?.forEach { resourceFile ->
                graphBuilder.addEdge(ResourceEdge(resourceFile.id, callSite.id, ResourceRelation.LOADS))
            }
        }
    }

    private fun trackResourceAssociations(
        callSite: CallSiteNode,
        calleeSignature: MethodSignature,
        invokeExpr: AbstractInvokeExpr,
        caller: MethodDescriptor,
        resultNode: ValueNode?
    ) {
        val receiverLocal = (invokeExpr as? AbstractInstanceInvokeExpr)?.base as? Local
        if (calleeSignature.declClassType.fullyQualifiedName == "java.util.Locale" && calleeSignature.name == "<init>") {
            val localeSpec = extractConstructedLocaleSpec(invokeExpr)
            if (receiverLocal != null && localeSpec != null) {
                localeSpecsByLocal[localKey(caller, receiverLocal.name)] = localeSpec
            }
        }

        val boundPaths = extractBoundResourcePaths(caller, calleeSignature, invokeExpr)
        if (boundPaths != null) {
            when {
                isPropertiesLoadCall(calleeSignature) && receiverLocal != null -> {
                    propertiesPathsByLocal[localKey(caller, receiverLocal.name)] = LinkedHashSet(boundPaths)
                    boundPaths.forEach { path ->
                        resourceFilesByPath[path]?.forEach { resourceFile ->
                            graphBuilder.addEdge(ResourceEdge(resourceFile.id, callSite.id, ResourceRelation.LOADS))
                        }
                    }
                }
                isPropertyResourceBundleConstructor(calleeSignature) && receiverLocal != null -> {
                    val receiverKey = localKey(caller, receiverLocal.name)
                    resourceBundlePaths[receiverKey] = LinkedHashSet(boundPaths)
                    boundPaths.forEach { path ->
                        resourceFilesByPath[path]?.forEach { resourceFile ->
                            graphBuilder.addEdge(ResourceEdge(resourceFile.id, callSite.id, ResourceRelation.LOADS))
                        }
                    }
                }
                isReaderBridgeConstructor(calleeSignature) && receiverLocal != null -> {
                    resourceHandlePathsByLocal[localKey(caller, receiverLocal.name)] = LinkedHashSet(boundPaths)
                }
            }
        }

        if (resultNode is LocalVariable) {
            val resultKey = localKey(caller, resultNode.name)
            extractResourceLookupPath(caller, calleeSignature, invokeExpr)?.let {
                resourceHandlePathsByLocal[resultKey] = linkedSetOf(it)
            }
            if (isReaderFactoryCall(calleeSignature)) {
                boundPaths?.let { resourceHandlePathsByLocal[resultKey] = LinkedHashSet(it) }
            }
        }
    }

    private fun extractBoundResourcePaths(
        caller: MethodDescriptor,
        calleeSignature: MethodSignature,
        invokeExpr: AbstractInvokeExpr
    ): LinkedHashSet<String>? {
        val directPath = extractResourceLookupPath(caller, calleeSignature, invokeExpr)
        if (directPath != null) return linkedSetOf(directPath)

        val boundPaths = LinkedHashSet<String>()
        (invokeExpr as? AbstractInstanceInvokeExpr)?.base
            ?.let { extractBoundResourcePaths(caller, it) }
            ?.let(boundPaths::addAll)
        invokeExpr.args.forEach { arg ->
            extractBoundResourcePaths(caller, arg)?.let(boundPaths::addAll)
        }
        return boundPaths.takeIf { it.isNotEmpty() }
    }

    private fun extractBoundResourcePaths(caller: MethodDescriptor, value: Value): LinkedHashSet<String>? = when (value) {
        is Local -> {
            val key = localKey(caller, value.name)
            resourceHandlePathsByLocal[key]
                ?: propertiesPathsByLocal[key]
                ?: resourceBundlePaths[key]
        }
        else -> null
    }?.let(::LinkedHashSet)

    private fun lookupResourceFiles(resourcePaths: Collection<String>?): Sequence<ResourceFileNode> {
        val scopedPaths = resourcePaths?.takeIf { it.isNotEmpty() } ?: configurationResourcePaths
        return scopedPaths.asSequence()
            .flatMap { path -> resourceFilesByPath[path].orEmpty().asSequence() }
            .distinctBy { it.id }
    }

    private fun isResourceBundleCall(calleeSignature: MethodSignature): Boolean =
        calleeSignature.declClassType.fullyQualifiedName == "java.util.ResourceBundle" &&
            calleeSignature.name == "getBundle"

    private fun isResourceEnumerationCall(calleeSignature: MethodSignature): Boolean {
        val declaringClass = calleeSignature.declClassType.fullyQualifiedName
        return (declaringClass == "java.util.ResourceBundle" || declaringClass == "java.util.PropertyResourceBundle") &&
            calleeSignature.name == "getKeys"
    }

    private fun isPropertiesLoadCall(calleeSignature: MethodSignature): Boolean =
        calleeSignature.declClassType.fullyQualifiedName == "java.util.Properties" &&
            calleeSignature.name in setOf("load", "loadFromXML")

    private fun isPropertyResourceBundleConstructor(calleeSignature: MethodSignature): Boolean =
        calleeSignature.declClassType.fullyQualifiedName == "java.util.PropertyResourceBundle" &&
            calleeSignature.name == "<init>"

    private fun isReaderBridgeConstructor(calleeSignature: MethodSignature): Boolean {
        val declaringClass = calleeSignature.declClassType.fullyQualifiedName
        return calleeSignature.name == "<init>" && declaringClass in setOf(
            "java.io.InputStreamReader",
            "java.io.BufferedReader",
            "java.io.StringReader",
            "java.io.LineNumberReader"
        )
    }

    private fun isReaderFactoryCall(calleeSignature: MethodSignature): Boolean {
        val declaringClass = calleeSignature.declClassType.fullyQualifiedName
        val methodName = calleeSignature.name
        return (declaringClass == "java.net.URL" && methodName == "openStream") ||
            (declaringClass == "java.nio.channels.Channels" && methodName == "newReader")
    }

    private fun isStructuredResourceLoadCall(calleeSignature: MethodSignature): Boolean {
        val declaringClass = calleeSignature.declClassType.fullyQualifiedName
        val methodName = calleeSignature.name
        if (isPropertiesLoadCall(calleeSignature) || isPropertyResourceBundleConstructor(calleeSignature)) return true
        return when (declaringClass) {
            "com.fasterxml.jackson.databind.ObjectMapper" -> methodName in setOf("readTree", "readValue", "readValues")
            "com.fasterxml.jackson.dataformat.xml.XmlMapper" -> methodName in setOf("readTree", "readValue", "readValues")
            "com.google.gson.Gson" -> methodName == "fromJson"
            "org.yaml.snakeyaml.Yaml" -> methodName in setOf("load", "loadAll", "loadAs")
            "javax.xml.parsers.DocumentBuilder" -> methodName == "parse"
            "org.dom4j.io.SAXReader" -> methodName == "read"
            "org.jdom2.input.SAXBuilder" -> methodName == "build"
            else -> false
        }
    }

    private fun collectMatchingResourceBundlePaths(
        baseName: String,
        basePath: String,
        controlSpec: BundleControlSpec?
    ): LinkedHashSet<String> {
        val candidates = resourceFilesByPath.keys
            .filter { it == "$basePath.properties" || (it.startsWith("${basePath}_") && it.endsWith(".properties")) || matchesBundleClassPath(it, baseName) }
            .sortedWith(compareByDescending<String> { it.count { ch -> ch == '_' } }.thenBy { it })
        return LinkedHashSet(
            candidates
                .asSequence()
                .filter { controlAllowsPath(it, controlSpec) }
                .toList()
                .ifEmpty { collectBundleCandidates(baseName, basePath, controlSpec) }
        )
    }

    private fun buildResourceBundleCandidatePaths(
        baseName: String,
        basePath: String,
        localeSpec: String,
        controlSpec: BundleControlSpec?
    ): LinkedHashSet<String> {
        val candidates = LinkedHashSet<String>()
        val localeCandidates = controlSpec?.candidateLocales ?: defaultBundleCandidateLocales(localeSpec)
        for (candidateLocale in localeCandidates) {
            if (candidateLocale.isBlank()) {
                candidates.add("$basePath.properties")
                candidates.add(baseName)
            } else {
                candidates.add("${basePath}_${candidateLocale}.properties")
                candidates.add("${baseName}_${candidateLocale}")
            }
        }
        return LinkedHashSet(
            candidates.filter { (it in resourceFilesByPath || it == "$basePath.properties" || it == baseName) && controlAllowsPath(it, controlSpec) }
        )
    }

    private fun defaultBundleCandidateLocales(localeSpec: String): List<String> {
        val parts = localeSpec.split('_').filter { it.isNotBlank() }
        return buildList {
            for (size in parts.size downTo 1) {
                add(parts.take(size).joinToString("_"))
            }
            add("")
        }
    }

    private fun collectBundleCandidates(baseName: String, basePath: String, controlSpec: BundleControlSpec?): LinkedHashSet<String> =
        LinkedHashSet(
            listOf("$basePath.properties", baseName)
                .filter { controlAllowsPath(it, controlSpec) }
        )

    private fun updateLocaleBuilderState(
        caller: MethodDescriptor,
        calleeSignature: MethodSignature,
        invokeExpr: AbstractInvokeExpr,
        resultNode: ValueNode?
    ) {
        if (!isLocaleBuilderClassName(calleeSignature.declClassType.fullyQualifiedName)) return
        val receiverLocal = (invokeExpr as? AbstractInstanceInvokeExpr)?.base as? Local ?: return
        val receiverKey = localKey(caller, receiverLocal.name)
        val current = localeBuilderSpecsByLocal[receiverKey] ?: LocaleBuilderSpec()
        val updated = when (calleeSignature.name) {
            "<init>" -> LocaleBuilderSpec()
            "setLanguage" -> current.copy(language = extractStringValue(caller, invokeExpr.args.getOrNull(0)))
            "setRegion" -> current.copy(country = extractStringValue(caller, invokeExpr.args.getOrNull(0)))
            "setVariant" -> current.copy(variant = extractStringValue(caller, invokeExpr.args.getOrNull(0)))
            "setLanguageTag" -> current.copy(languageTag = extractStringValue(caller, invokeExpr.args.getOrNull(0))?.let(::normalizeLocaleSpec))
            "setLocale" -> extractLocaleSpec(caller, invokeExpr.args.getOrNull(0) ?: return)
                ?.split('_')
                ?.let { parts ->
                    LocaleBuilderSpec(
                        language = parts.getOrNull(0),
                        country = parts.getOrNull(1),
                        variant = parts.drop(2).takeIf { it.isNotEmpty() }?.joinToString("_"),
                        languageTag = null
                    )
                } ?: current
            "clear", "clearExtensions" -> LocaleBuilderSpec()
            else -> current
        }
        localeBuilderSpecsByLocal[receiverKey] = updated

        if (resultNode is LocalVariable) {
            val resultKey = localKey(caller, resultNode.name)
            if (isLocaleBuilderClassName(toTypeDescriptor(calleeSignature.type).className)) {
                localeBuilderSpecsByLocal[resultKey] = updated
            }
            if (calleeSignature.name == "build") {
                updated.toLocaleSpec()?.let { localeSpecsByLocal[resultKey] = normalizeLocaleSpec(it) }
            }
        }
    }

    private fun extractBundleControlSpec(
        caller: MethodDescriptor,
        value: Value,
        baseName: String,
        localeSpec: String?
    ): BundleControlSpec? {
        val className = when (value) {
            is Local -> {
                val key = localKey(caller, value.name)
                bundleControlSpecsByLocal[key]?.let { return it }
                allocationNodes[key]?.type?.className ?: localNodes[key]?.type?.className
            }
            is JNewExpr -> toTypeDescriptor(value.type).className
            else -> null
        }
        className ?: return null
        return resolveBundleControlSpec(className)
            ?: reflectBundleControlSpec(className, baseName, localeSpec)
    }

    private fun extractBundleControlSpec(
        caller: MethodDescriptor,
        calleeSignature: MethodSignature,
        invokeExpr: AbstractInvokeExpr
    ): BundleControlSpec? {
        if (!isResourceBundleControlTypeName(calleeSignature.declClassType.fullyQualifiedName)) return null
        val formatArg = extractControlFormat(caller, invokeExpr.args.firstOrNull())
        return when (calleeSignature.name) {
            "getControl" -> BundleControlSpec(formats = controlFormats(formatArg))
            "getNoFallbackControl" -> BundleControlSpec(noFallback = true, formats = controlFormats(formatArg))
            else -> null
        }
    }

    private fun extractControlFormat(caller: MethodDescriptor?, value: Value?): String? {
        return when (value) {
            is SootStringConstant -> value.value
            is Local -> caller?.let { bundleControlFormatsByLocal[localKey(it, value.name)] }
            is JStaticFieldRef -> {
                if (!isResourceBundleControlTypeName(value.fieldSignature.declClassType.fullyQualifiedName)) {
                    null
                } else {
                    when (value.fieldSignature.name) {
                        "FORMAT_PROPERTIES" -> "java.properties"
                        "FORMAT_CLASS" -> "java.class"
                        "FORMAT_DEFAULT" -> null
                        else -> null
                    }
                }
            }
            else -> null
        }
    }

    private fun controlFormats(raw: String?): Set<String> = when (raw) {
        "java.class" -> setOf("java.class")
        "java.properties" -> setOf("java.properties")
        else -> setOf("java.properties", "java.class")
    }

    private fun resolveBundleControlSpec(className: String): BundleControlSpec? =
        bundleControlSpecsByClass.getOrPut(className) {
            val sootClass = resolveClassByName(className) ?: return@getOrPut null
            if (!isResourceBundleControlClass(sootClass)) return@getOrPut null
            val javaSootClass = sootClass as? JavaSootClass ?: return@getOrPut null
            val methods = getAsmMethodNodes(javaSootClass) ?: loadMethodNodesFromResource(javaSootClass) ?: return@getOrPut null
            val formats = methods.firstOrNull { it.name == "getFormats" }
                ?.let(::extractControlFormatsFromMethod)
                ?: setOf("java.properties", "java.class")
            val candidateLocales = methods.firstOrNull { it.name == "getCandidateLocales" }
                ?.let(::extractCandidateLocalesFromMethod)
            val noFallback = methods.firstOrNull { it.name == "getFallbackLocale" }
                ?.let(::returnsNullLiteral)
                ?: false
            BundleControlSpec(
                noFallback = noFallback,
                formats = formats,
                candidateLocales = candidateLocales
            )
        }

    private fun reflectBundleControlSpec(
        className: String,
        baseName: String,
        localeSpec: String?
    ): BundleControlSpec? = runCatching {
        val controlClass = Class.forName(className)
        if (!ResourceBundle.Control::class.java.isAssignableFrom(controlClass)) return null
        val instance = controlClass.getDeclaredConstructor().newInstance() as ResourceBundle.Control
        val locale = localeSpec?.toLocale() ?: Locale.ROOT
        BundleControlSpec(
            noFallback = instance.getFallbackLocale(baseName, locale) == null,
            formats = instance.getFormats(baseName).toSet(),
            candidateLocales = instance.getCandidateLocales(baseName, locale)
                .map(::localeSpecOf)
                .distinct()
        )
    }.getOrNull()

    private fun isResourceBundleControlClass(sootClass: SootClass): Boolean {
        var current: SootClass? = sootClass
        while (current != null) {
            val superType = current.superclass.orElse(null) ?: return false
            val superName = superType.fullyQualifiedName
            if (isResourceBundleControlTypeName(superName)) return true
            current = resolveClassByName(superName)
        }
        return false
    }

    private fun controlAllowsPath(path: String, controlSpec: BundleControlSpec?): Boolean {
        if (controlSpec == null) return true
        val isProperties = path.endsWith(".properties")
        val isClassBundle = !isProperties
        return (isProperties && "java.properties" in controlSpec.formats) ||
            (isClassBundle && "java.class" in controlSpec.formats)
    }

    private fun matchesBundleClassPath(path: String, baseName: String): Boolean =
        path == baseName || path.startsWith("${baseName}_")

    private fun ensureRuntimeBundleIndexed(baseName: String, localeSpec: String?, controlSpec: BundleControlSpec?) {
        val bundleKey = listOf(baseName, localeSpec.orEmpty(), controlSpec?.formats?.sorted()?.joinToString(","), controlSpec?.noFallback)
            .joinToString("|")
        if (!runtimeIndexedBundles.add(bundleKey)) return
        runCatching {
            val locale = localeSpec?.toLocale() ?: Locale.ROOT
            val classLoader = javaClass.classLoader
            val bundle = when (controlSpec) {
                null -> ResourceBundle.getBundle(baseName, locale, classLoader)
                else -> ResourceBundle.getBundle(baseName, locale, classLoader, toControl(controlSpec))
            }
            indexRuntimeBundle(bundle)
        }.onFailure {
            log("Skipping runtime bundle resolution for $baseName: ${it.message}")
        }
    }

    private fun indexRuntimeBundle(bundle: ResourceBundle) {
        val path = bundle.javaClass.name
        if (resourceFilesByPath.containsKey(path)) return
        val format = when (bundle) {
            is java.util.ListResourceBundle -> "listbundle"
            is java.util.PropertyResourceBundle -> "propertybundle"
            else -> "bundle"
        }
        val fileNode = ResourceFileNode(
            id = nextNodeId("resource"),
            path = path,
            source = "runtime-bundle",
            format = format,
            profile = null
        )
        graphBuilder.addNode(fileNode)
        resourceFilesByPath.getOrPut(path) { mutableListOf() }.add(fileNode)
        bundleParent(bundle)?.let(::indexRuntimeBundle)
    }

    private fun normalizeRuntimeBundleValue(value: Any?): Any? = when (value) {
        is String, is Number, is Boolean -> value
        is Array<*> -> value.map(::normalizeRuntimeBundleValue)
        is Iterable<*> -> value.map(::normalizeRuntimeBundleValue)
        else -> value?.toString()
    }

    private fun toControl(controlSpec: BundleControlSpec): ResourceBundle.Control =
        if (controlSpec.noFallback) {
            ResourceBundle.Control.getNoFallbackControl(controlFormatsList(controlSpec))
        } else {
            ResourceBundle.Control.getControl(controlFormatsList(controlSpec))
        }

    private fun controlFormatsList(controlSpec: BundleControlSpec): List<String> = buildList {
        if ("java.class" in controlSpec.formats) add("java.class")
        if ("java.properties" in controlSpec.formats) add("java.properties")
    }

    private fun String.toLocale(): Locale {
        if (isBlank()) return Locale.ROOT
        val parts = split('_').filter { it.isNotBlank() }
        return when (parts.size) {
            1 -> Locale(parts[0])
            2 -> Locale(parts[0], parts[1])
            else -> Locale(parts[0], parts[1], parts.drop(2).joinToString("_"))
        }
    }

    private fun localeSpecOf(locale: Locale): String =
        normalizeLocaleSpec(
            listOfNotNull(
                locale.language.takeIf { it.isNotBlank() },
                locale.country.takeIf { it.isNotBlank() },
                locale.variant.takeIf { it.isNotBlank() }
            ).joinToString("_")
        )

    private fun bundleParent(bundle: ResourceBundle): ResourceBundle? = runCatching {
        val field = ResourceBundle::class.java.getDeclaredField("parent")
        field.isAccessible = true
        field.get(bundle) as? ResourceBundle
    }.getOrNull()

    private fun extractLocaleSpec(caller: MethodDescriptor, value: Value): String? = when (value) {
        is Local -> localeSpecsByLocal[localKey(caller, value.name)]
        is JStaticFieldRef -> extractLocaleSpec(value)
        else -> null
    }

    private fun extractStringValue(caller: MethodDescriptor, value: Value?): String? = when (value) {
        is SootStringConstant -> value.value
        is Local -> stringValuesByLocal[localKey(caller, value.name)]
        else -> null
    }

    private fun extractLocaleSpec(fieldRef: JStaticFieldRef): String? {
        if (fieldRef.fieldSignature.declClassType.fullyQualifiedName != "java.util.Locale") return null
        return extractLocaleSpec(fieldRef.fieldSignature.name)
    }

    private fun extractLocaleSpec(fieldName: String): String? {
        return when (fieldName) {
            "ROOT" -> ""
            "ENGLISH" -> "en"
            "US" -> "en_US"
            "UK" -> "en_GB"
            "CANADA" -> "en_CA"
            "CANADA_FRENCH" -> "fr_CA"
            "FRENCH" -> "fr"
            "FRANCE" -> "fr_FR"
            "GERMAN" -> "de"
            "GERMANY" -> "de_DE"
            "ITALIAN" -> "it"
            "ITALY" -> "it_IT"
            "JAPANESE" -> "ja"
            "JAPAN" -> "ja_JP"
            "KOREAN" -> "ko"
            "KOREA" -> "ko_KR"
            "CHINESE" -> "zh"
            "CHINA", "SIMPLIFIED_CHINESE" -> "zh_CN"
            "TAIWAN", "TRADITIONAL_CHINESE" -> "zh_TW"
            else -> null
        }?.let(::normalizeLocaleSpec)
    }

    private fun extractLocaleFactorySpec(calleeSignature: MethodSignature, invokeExpr: AbstractInvokeExpr): String? {
        if (calleeSignature.declClassType.fullyQualifiedName != "java.util.Locale") return null
        return when (calleeSignature.name) {
            "forLanguageTag" -> (invokeExpr.args.firstOrNull() as? SootStringConstant)?.value
                ?.let(::normalizeLocaleSpec)
            else -> null
        }
    }

    private fun extractConstructedLocaleSpec(invokeExpr: AbstractInvokeExpr): String? {
        val language = (invokeExpr.args.getOrNull(0) as? SootStringConstant)?.value ?: return null
        val country = (invokeExpr.args.getOrNull(1) as? SootStringConstant)?.value
        val variant = (invokeExpr.args.getOrNull(2) as? SootStringConstant)?.value
        return normalizeLocaleSpec(listOfNotNull(language, country, variant).joinToString("_"))
    }

    private fun normalizeLocaleSpec(spec: String): String {
        val parts = spec.split('_', '-').filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""
        return buildList {
            add(parts[0].lowercase())
            if (parts.size > 1) add(parts[1].uppercase())
            if (parts.size > 2) addAll(parts.drop(2))
        }.joinToString("_")
    }

    private fun normalizeResourcePath(caller: MethodDescriptor, declaringClass: String, rawPath: String): String {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) return trimmed
        if (declaringClass == "java.lang.ClassLoader") {
            return trimmed.removePrefix("/")
        }
        if (trimmed.startsWith("/")) {
            return trimmed.removePrefix("/")
        }
        val packagePath = caller.declaringClass.className.substringBeforeLast('.', "")
            .replace('.', '/')
        return if (packagePath.isEmpty()) trimmed else "$packagePath/$trimmed"
    }

    private fun linkValueAnnotatedField(binding: PlaceholderBinding?, fieldNode: FieldNode) {
        if (binding == null) return
        binding.key?.let { configKey ->
            lookupResourceFiles(null).forEach { resourceFile ->
                graphBuilder.addEdge(ResourceEdge(resourceFile.id, fieldNode.id, ResourceRelation.LOOKUP))
            }
        }
        if (binding.hasDefaultValue) {
            val defaultNode = getOrCreateConstantValue(binding.defaultValue)
            graphBuilder.addEdge(
                DataFlowEdge(
                    from = defaultNode.id,
                    to = fieldNode.id,
                    kind = DataFlowKind.ASSIGN
                )
            )
        }
    }

    private fun extractValueAnnotationBinding(annotations: Iterable<*>): PlaceholderBinding? {
        val valueAnnotation = annotations.firstOrNull {
            getAnnotationFullName(it) == "org.springframework.beans.factory.annotation.Value"
        } ?: return null
        val expression = normalizeAnnotationValue(getAnnotationValues(valueAnnotation)["value"]) as? String ?: return null
        return extractPlaceholderBinding(expression)
    }

    private fun extractValueAnnotationBinding(fieldNode: AsmFieldNode): PlaceholderBinding? {
        val annotations = (fieldNode.visibleAnnotations ?: emptyList()) + (fieldNode.invisibleAnnotations ?: emptyList())
        val valueAnnotation = annotations.firstOrNull { it.desc == "Lorg/springframework/beans/factory/annotation/Value;" }
            ?: return null
        val rawValues = valueAnnotation.values ?: return null
        var expression: String? = null
        var index = 0
        while (index + 1 < rawValues.size) {
            val key = rawValues[index] as? String
            val value = rawValues[index + 1]
            if (key == "value") {
                expression = value?.toString()
                break
            }
            index += 2
        }
        return expression?.let { extractPlaceholderBinding(it) }
    }

    private fun extractPlaceholderBinding(expression: String): PlaceholderBinding {
        val trimmed = expression.trim()
        val match = Regex("""\$\{([^:}]+)(?::([^}]*))?}""").find(trimmed)
        if (match != null) {
            return PlaceholderBinding(
                key = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() },
                defaultValue = match.groupValues.getOrNull(2)?.let(::parseScalar),
                hasDefaultValue = match.groups[2] != null
            )
        }
        return PlaceholderBinding(
            key = null,
            defaultValue = parseScalar(trimmed),
            hasDefaultValue = true
        )
    }

    private fun extractConfigurationPropertiesPrefix(sootClass: SootClass): String? {
        if (sootClass is JavaSootClass) {
            val fromSoot = extractConfigurationPropertiesPrefix(sootClass.annotations)
            if (fromSoot != null) return fromSoot
            val fromAsm = getAsmClassNode(sootClass)?.let { classNode ->
                val annotations = buildList {
                    classNode.visibleAnnotations?.let { addAll(AsmUtil.createAnnotationUsage(it).toList()) }
                    classNode.invisibleAnnotations?.let { addAll(AsmUtil.createAnnotationUsage(it).toList()) }
                }
                extractConfigurationPropertiesPrefix(annotations)
            }
            if (fromAsm != null) return fromAsm
        }
        return null
    }

    private fun extractConfigurationPropertiesPrefix(annotations: Iterable<*>): String? {
        val configAnnotation = annotations.firstOrNull {
            getAnnotationFullName(it) == "org.springframework.boot.context.properties.ConfigurationProperties"
        } ?: return null
        val values = getAnnotationValues(configAnnotation)
        return normalizeAnnotationValue(values["prefix"]) as? String
            ?: normalizeAnnotationValue(values["value"]) as? String
    }

    private fun linkConfigurationPropertiesField(prefix: String?, fieldName: String, fieldNode: FieldNode) {
        if (prefix.isNullOrBlank()) return
        configurationPropertyKeys(prefix, fieldName).forEach { key ->
            lookupResourceFiles(null).forEach { resourceFile ->
                graphBuilder.addEdge(ResourceEdge(resourceFile.id, fieldNode.id, ResourceRelation.LOOKUP))
            }
        }
    }

    private fun configurationPropertyKeys(prefix: String, fieldName: String): Set<String> {
        val kebab = fieldName
            .replace(Regex("([a-z0-9])([A-Z])"), "$1-$2")
            .lowercase()
        val snake = fieldName
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase()
        return linkedSetOf(
            "$prefix.$fieldName",
            "$prefix.$kebab",
            "$prefix.$snake"
        )
    }

    private fun isResourceConfig(path: String): Boolean =
        path.endsWith(".properties") ||
            path.endsWith(".yml") ||
            path.endsWith(".yaml") ||
            path.endsWith(".json") ||
            path.endsWith(".xml")

    private fun resourceFormat(path: String): String = when {
        path.endsWith(".properties") -> "properties"
        path.endsWith(".yml") || path.endsWith(".yaml") -> "yaml"
        path.endsWith(".json") -> "json"
        path.endsWith(".xml") -> "xml"
        else -> "text"
    }

    private fun resourceProfile(path: String): String? {
        val fileName = path.substringAfterLast('/')
        val match = Regex("""application-([^.]+)\.(properties|json|xml|ya?ml)""").matchEntire(fileName)
        return match?.groupValues?.getOrNull(1)
    }

    private fun loadProperties(content: String): Map<String, Any?> {
        val properties = Properties()
        properties.load(content.reader())
        return properties.stringPropertyNames().associateWith { parseScalar(properties.getProperty(it)) }
    }

    private fun loadYaml(content: String): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        val pathStack = mutableListOf<String>()
        var previousIndent = 0

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').trimEnd()
            if (line.isBlank() || ':' !in line) return@forEach
            val indent = rawLine.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val level = indent / 2
            while (pathStack.size > level) {
                pathStack.removeAt(pathStack.lastIndex)
            }
            if (level < previousIndent / 2 && pathStack.size > level) {
                while (pathStack.size > level) {
                    pathStack.removeAt(pathStack.lastIndex)
                }
            }
            previousIndent = indent

            val key = line.substringBefore(':').trim()
            val valuePart = line.substringAfter(':').trim()
            if (valuePart.isEmpty()) {
                if (pathStack.size == level) {
                    pathStack.add(key)
                } else {
                    pathStack[level] = key
                }
            } else {
                val fullKey = (pathStack + key).joinToString(".")
                result[fullKey] = parseScalar(valuePart)
            }
        }

        return result
    }

    private fun loadJson(content: String): Map<String, Any?> {
        val element = JsonParser.parseString(content)
        val result = linkedMapOf<String, Any?>()
        flattenJson(element, emptyList(), result)
        return result
    }

    private fun flattenJson(element: JsonElement?, path: List<String>, dest: MutableMap<String, Any?>) {
        when {
            element == null || element.isJsonNull -> {
                if (path.isNotEmpty()) dest[path.joinToString(".")] = null
            }
            element.isJsonObject -> flattenJsonObject(element.asJsonObject, path, dest)
            element.isJsonArray -> flattenJsonArray(element.asJsonArray, path, dest)
            element.isJsonPrimitive -> {
                if (path.isNotEmpty()) dest[path.joinToString(".")] = parseJsonPrimitive(element)
            }
        }
    }

    private fun flattenJsonObject(obj: JsonObject, path: List<String>, dest: MutableMap<String, Any?>) {
        obj.entrySet().forEach { (key, value) ->
            flattenJson(value, path + key, dest)
        }
    }

    private fun flattenJsonArray(array: JsonArray, path: List<String>, dest: MutableMap<String, Any?>) {
        array.forEachIndexed { index, element ->
            val base = path.toMutableList()
            if (base.isNotEmpty()) {
                base[base.lastIndex] = "${base.last()}[$index]"
            } else {
                base += "[$index]"
            }
            flattenJson(element, base, dest)
        }
    }

    private fun parseJsonPrimitive(element: JsonElement): Any? {
        val primitive = element.asJsonPrimitive
        return when {
            primitive.isBoolean -> primitive.asBoolean
            primitive.isNumber -> parseScalar(primitive.asString)
            primitive.isString -> primitive.asString
            else -> primitive.toString()
        }
    }

    private fun loadXml(content: ByteArray): Map<String, Any?> {
        val text = content.toString(Charsets.UTF_8)
        val looksLikePropertiesXml = Regex("""<\s*properties(?:\s|>)""").containsMatchIn(text)
        return if (looksLikePropertiesXml) {
            runCatching { loadXmlProperties(content) }
                .getOrElse { loadGenericXml(content) }
        } else {
            loadGenericXml(content)
        }
    }

    private fun loadXmlProperties(content: ByteArray): Map<String, Any?> {
        val properties = Properties()
        ByteArrayInputStream(content).use(properties::loadFromXML)
        return properties.stringPropertyNames().associateWith { parseScalar(properties.getProperty(it)) }
    }

    private fun loadGenericXml(content: ByteArray): Map<String, Any?> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val document = ByteArrayInputStream(content).use { input ->
            factory.newDocumentBuilder().parse(input).apply { documentElement.normalize() }
        }
        val result = linkedMapOf<String, Any?>()
        val elements = document.getElementsByTagName("*")
        for (index in 0 until elements.length) {
            val element = elements.item(index) as? Element ?: continue
            val elementKey = xmlElementPath(element) ?: continue
            if (element.hasAttributes()) {
                val attributes = element.attributes
                for (attributeIndex in 0 until attributes.length) {
                    val attribute = attributes.item(attributeIndex)
                    if (attribute != null && attribute.nodeName.isNotBlank()) {
                        result["$elementKey.@${attribute.nodeName}"] = parseScalar(attribute.nodeValue ?: "")
                    }
                }
            }

            val childElements = xmlChildElements(element)
            val directTextContent = element.childNodes.toTextValue()
            if (childElements.isEmpty()) {
                element.textContent
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { result[elementKey] = parseScalar(it) }
            } else if (directTextContent != null) {
                result["$elementKey.#text"] = parseScalar(directTextContent)
            }
        }
        return result
    }

    private fun xmlChildElements(element: Element): List<Element> {
        val children = mutableListOf<Element>()
        for (index in 0 until element.childNodes.length) {
            val node = element.childNodes.item(index)
            if (node is Element) {
                children += node
            }
        }
        return children
    }

    private fun xmlElementPath(element: Element): String? {
        val parts = mutableListOf<String>()
        var current: Element? = element
        while (current != null) {
            val parent = current.parentNode as? Element
            if (parent == null) {
                if (parts.isEmpty() && xmlChildElements(current).isEmpty()) {
                    parts += current.tagName
                }
                break
            }
            val sameNamedSiblings = xmlChildElements(parent).filter { it.tagName == current.tagName }
            val name = if (sameNamedSiblings.size > 1) {
                "${current.tagName}[${sameNamedSiblings.indexOf(current)}]"
            } else {
                current.tagName
            }
            parts += name
            current = parent
        }
        return parts.asReversed().joinToString(".").takeIf { it.isNotBlank() }
    }

    private fun org.w3c.dom.NodeList.toTextValue(): String? {
        val text = buildString {
            for (index in 0 until length) {
            val node = item(index)
            if (node.nodeType == DomNode.TEXT_NODE || node.nodeType == DomNode.CDATA_SECTION_NODE) {
                append(node.nodeValue)
            }
        }
        }.trim()
        return text.takeIf { it.isNotEmpty() }
    }

    private fun parseScalar(value: String): Any? {
        val trimmed = value.trim()
        return when {
            trimmed.equals("true", ignoreCase = true) -> true
            trimmed.equals("false", ignoreCase = true) -> false
            trimmed.equals("null", ignoreCase = true) -> null
            trimmed.toIntOrNull() != null -> trimmed.toInt()
            trimmed.toLongOrNull() != null -> trimmed.toLong()
            trimmed.toDoubleOrNull() != null -> trimmed.toDouble()
            trimmed.startsWith("\"") && trimmed.endsWith("\"") -> trimmed.removeSurrounding("\"")
            trimmed.startsWith("'") && trimmed.endsWith("'") -> trimmed.removeSurrounding("'")
            else -> trimmed
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
                cleanValues[key] = normalizeAnnotationValue(value)
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

    private fun normalizeAnnotationValue(value: Any?): Any? = when (value) {
        null -> null
        is String -> value.removeSurrounding("\"").takeIf { it.isNotEmpty() }
        is Int, is Long, is Float, is Double, is Boolean -> value
        is List<*> -> value.mapNotNull { normalizeAnnotationValue(it) }.takeIf { it.isNotEmpty() }
        is Array<*> -> value.mapNotNull { normalizeAnnotationValue(it) }.takeIf { it.isNotEmpty() }
        else -> value.toString()
            .removeSurrounding("\"")
            .removeSurrounding("[", "]")
            .removeSurrounding("\"")
            .takeIf { it.isNotEmpty() && it != "null" }
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

    private fun localKey(method: MethodDescriptor, localName: String): LocalKey {
        val key = LocalKey(method, localName)
        if (activeMethod == method) {
            activeMethodLocals.add(key)
        }
        return key
    }

    private fun parameterBinding(method: MethodDescriptor, index: Int): ParameterBinding {
        val binding = ParameterBinding(method, index)
        if (activeMethod == method) {
            activeMethodParameters.add(binding)
        }
        return binding
    }

    private fun mergeTargets(
        existing: List<MethodDescriptor>?,
        newTargets: Iterable<MethodDescriptor>
    ): List<MethodDescriptor> {
        if (existing == null) return newTargets.toList()
        val merged = LinkedHashSet(existing)
        merged.addAll(newTargets)
        return merged.toList()
    }

    private fun clearMethodState(method: MethodDescriptor) {
        activeMethodLocals.forEach { key ->
            localNodes.remove(key)
            allocationNodes.remove(key)
            localToParamIndex.remove(key)
            dynamicTargets.remove(key)
            arrayDynamicTargets.remove(key)
            localeSpecsByLocal.remove(key)
            localeBuilderSpecsByLocal.remove(key)
            stringValuesByLocal.remove(key)
            bundleControlFormatsByLocal.remove(key)
            resourceHandlePathsByLocal.remove(key)
            propertiesPathsByLocal.remove(key)
            resourceBundlePaths.remove(key)
            bundleControlSpecsByLocal.remove(key)
        }
        activeMethodParameters.forEach { parameterNodes.remove(it) }
        methodReturnNodes.remove(method)
        stmtNodeIds.clear()
        activeMethod = null
        activeMethodLocals = mutableSetOf()
        activeMethodParameters = mutableSetOf()
    }

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
        return toMethodDescriptor(method.signature)
    }

    private fun toMethodDescriptor(sig: MethodSignature): MethodDescriptor {
        return methodDescriptorCache.getOrPut(sig) {
            MethodDescriptor(
                declaringClass = toTypeDescriptor(sig.declClassType),
                name = sig.name,
                parameterTypes = sig.parameterTypes.map { toTypeDescriptor(it) },
                returnType = toTypeDescriptor(sig.type)
            )
        }
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
        return resolvedMethodCache.getOrPut(sig) {
            if (view.getMethod(sig).isPresent) return@getOrPut sig

            val hierarchy = view.typeHierarchy
            val declClass = sig.declClassType

            try {
                for (superClass in hierarchy.superClassesOf(declClass)) {
                    val resolved = MethodSignature(superClass, sig.subSignature)
                    if (view.getMethod(resolved).isPresent) return@getOrPut resolved
                }
            } catch (_: Exception) {
                // class not in hierarchy
            }

            try {
                for (iface in hierarchy.implementedInterfacesOf(declClass)) {
                    val resolved = MethodSignature(iface, sig.subSignature)
                    if (view.getMethod(resolved).isPresent) return@getOrPut resolved
                }
            } catch (_: Exception) {
                // class not in hierarchy
            }

            sig
        }
    }
}
