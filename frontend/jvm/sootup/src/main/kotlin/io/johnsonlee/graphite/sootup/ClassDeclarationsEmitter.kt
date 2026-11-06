package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.EnumValueReference
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.TypeRelation
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.constant.Constant as SootConstant
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.jimple.common.ref.JFieldRef
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.model.SootClass
import sootup.core.model.SootField
import sootup.java.core.JavaSootClass
import sootup.java.core.JavaSootMethod
import sootup.core.types.ClassType

/** Emits hierarchy, enum and field declarations before method bodies are read. */
internal class ClassDeclarationsEmitter(private val context: SootUpAdapter) {
    private val trackCrossMethodFunctionalDispatch = context.trackCrossMethodFunctionalDispatch
    private val extractAnnotationsEnabled = context.extractAnnotationsEnabled
    private val fieldNodes = context.fieldNodes
    private val directSubtypes = context.directSubtypes
    private val syntheticIdentities = context.syntheticIdentities
    private var activeSyntheticClass
        get() = context.activeSyntheticClass
        set(value) { context.activeSyntheticClass = value }
    private var methodCacheOwner
        get() = context.methodCacheOwner
        set(value) { context.methodCacheOwner = value }
    private val streamingSourcesCache = context.streamingSourcesCache
    private val bytecodeMethodsCache = context.bytecodeMethodsCache
    private val config = context.config
    private val extensions = context.extensions
    private val graphBuilder = context.graphBuilder
    private val bodies get() = context.bodies
    private val callKinds get() = context.callKinds
    private val descriptors get() = context.descriptors
    private val metadata get() = context.metadata
    private val methodSources get() = context.methodSources
    private val values get() = context.values

    /**
     * Process a class of pass 2: its methods, their bodies and annotations, its fields, and, for
     * an [application] class, the extensions (endpoints and the like). A library class the
     * dependency body scope covers gets its bodies and nothing that would make it an entry point.
     */
    fun processClassWithMethodCache(sootClass: SootClass, extensionContext: GraphiteContext, application: Boolean) {
        methodCacheOwner = sootClass as? JavaSootClass
        try {
            if (extractAnnotationsEnabled && sootClass is JavaSootClass) {
                val className = sootClass.type.fullyQualifiedName
                metadata.extractAnnotations(sootClass.annotations, className, "<class>")
                sootClass.fields.inDeterministicOrder().forEach { field ->
                    metadata.extractAnnotations(field.annotations, className, field.name)
                }
            }

            activeSyntheticClass = SyntheticIdentity.isSyntheticClass(sootClass)
            if (activeSyntheticClass) {
                syntheticIdentities.addClass(sootClass)
            }
            methodSources.forEachMethod(sootClass) { method ->
                bodies.processMethod(method)
                if (extractAnnotationsEnabled && sootClass is JavaSootClass && method is JavaSootMethod) {
                    metadata.extractAnnotations(method.annotations, sootClass.type.fullyQualifiedName, method.name)
                }
            }

            visitFieldsForClass(sootClass)
            if (application) extensions.forEach { it.visit(sootClass, extensionContext) }
        } finally {
            bytecodeMethodsCache.clear()
            streamingSourcesCache.clear()
            methodCacheOwner = null
        }
    }

    fun log(message: String) {
        config.verbose?.invoke(message)
    }

    fun log(message: () -> String) {
        val verbose = config.verbose ?: return
        verbose(message())
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
    fun extractEnumValues(enumClass: SootClass) {
        val className = enumClass.type.fullyQualifiedName
        log { "Processing enum class: $className" }

        val clinit = methodSources.findStaticMethod(enumClass, "<clinit>")
        if (clinit == null) {
            log { "  No <clinit> found for $className" }
            return
        }

        if (!clinit.hasBody()) {
            log { "  <clinit> has no body for $className" }
            return
        }

        val body = try {
            clinit.body
        } finally {
            methodSources.releaseConversionState(clinit)
        }
        extractEnumValues(className, body.controlFlowGraph)
    }

    fun extractEnumValues(className: String, statements: Iterable<Stmt>) {
        val constructors = indexEnumConstructors(statements)

        // Track local variable assignments: localName -> value (for constants)
        val localValues = mutableMapOf<String, Any?>()
        // Track local variable aliases: localName -> original localName (for tracking new objects)
        val localAliases = mutableMapOf<String, String>()

        for (stmt in statements) {
            if (stmt !is JAssignStmt) continue
            val left = stmt.leftOp
            val right = stmt.rightOp

            if (left is Local) trackEnumLocalValue(left, right, localValues)

            // Track local-to-local assignments (aliases)
            if (left is Local && right is Local) {
                // left = right, so left is an alias for right
                // Follow the chain to find the original
                val original = localAliases[right.name] ?: right.name
                localAliases[left.name] = original
            }

            // Look for a field assigned from the local used by new + <init>.
            if (left is JFieldRef && left.fieldSignature.declClassType.fullyQualifiedName == className && right is Local) {
                val fieldName = left.fieldSignature.name
                val originalLocal = localAliases[right.name] ?: right.name
                log { "  Found field assignment: $fieldName = ${right.name} (resolved to $originalLocal)" }
                val initValues = findEnumInitValues(originalLocal, constructors, localValues)
                if (initValues.isNotEmpty()) {
                    graphBuilder.addEnumValues(className, fieldName, initValues)
                    log { "  Extracted enum value: $className.$fieldName = $initValues" }
                }
            }
        }
    }

    fun trackEnumLocalValue(left: Local, right: Value, localValues: MutableMap<String, Any?>) {
        // Track constant assignments to locals
        if (right is SootConstant) {
            localValues[left.name] = values.extractConstantValue(right)
        }

        // Track boxing method calls: Integer.valueOf(int), Long.valueOf(long), etc.
        // Pattern: $stackN = staticinvoke Integer.valueOf(1234)
        if (right is JStaticInvokeExpr) {
            val boxedValue = callKinds.extractBoxedValue(right)
            if (boxedValue != null) {
                localValues[left.name] = boxedValue
            }
        }

        // Track static field reads (enum constant references from other enums)
        // Pattern: $stackN = <sample.ab.Priority: Priority HIGH>
        // This handles the case where one enum's constructor takes another enum as argument
        if (right is JFieldRef) {
            val fieldSig = right.fieldSignature
            val fieldDeclClass = fieldSig.declClassType.fullyQualifiedName
            val fieldType = fieldSig.type
            // Check if the field type matches the declaring class (enum constant pattern)
            if (fieldType is ClassType && fieldType.fullyQualifiedName == fieldDeclClass) {
                localValues[left.name] = EnumValueReference(fieldDeclClass, fieldSig.name)
                log { "  Tracked enum reference: ${left.name} = $fieldDeclClass.${fieldSig.name}" }
            }
        }
    }

    /**
     * Keep the first eligible constructor in the same order as the old full-CFG search.
     * Only index expressions: argument locals must be read at each field assignment, not here.
     */
    fun indexEnumConstructors(statements: Iterable<Stmt>): Map<String, AbstractInstanceInvokeExpr> {
        val constructors = mutableMapOf<String, AbstractInstanceInvokeExpr>()
        for (stmt in statements) {
            val invoke = (stmt as? JInvokeStmt)?.invokeExpr?.orElse(null) ?: continue
            log { "    Checking invoke: ${invoke.javaClass.simpleName} - ${invoke.methodSignature}" }
            if (invoke is AbstractInstanceInvokeExpr && invoke.methodSignature.name == INIT_METHOD && invoke.args.size > 2) {
                constructors.putIfAbsent(invoke.base.name, invoke)
            }
        }
        return constructors
    }

    fun findEnumInitValues(
        localName: String,
        constructors: Map<String, AbstractInstanceInvokeExpr>,
        localValues: Map<String, Any?>
    ): List<Any?> {
        val invoke = constructors[localName]
        if (invoke == null) {
            log { "    No <init> call found for local $localName" }
            return emptyList()
        }
        val args = invoke.args
        log { "    Found <init> for $localName with ${args.size} args: ${args.map { it.toString() }}" }
        return args.drop(2).map { extractValueFromArg(it, localValues) }
    }

    /**
     * Extract a value from a method argument (either a constant or a local variable reference).
     * Enum constant references passed as constructor arguments are tracked via local variables
     * (see localValues map populated during JFieldRef processing above).
     */
    fun extractValueFromArg(arg: Value, localValues: Map<String, Any?>): Any? {
        return when (arg) {
            is SootConstant -> values.extractConstantValue(arg)
            is Local -> localValues[arg.name]
            else -> null
        }
    }

    fun processTypeHierarchyForClass(sootClass: SootClass) {
        val classType = descriptors.toTypeDescriptor(sootClass.type)

        // Process superclass
        sootClass.superclass.ifPresent { superType ->
            graphBuilder.addTypeRelation(
                classType,
                descriptors.toTypeDescriptor(superType),
                TypeRelation.EXTENDS
            )
            recordSubtype(superType.fullyQualifiedName, classType.className)
        }

        // Process interfaces
        sootClass.interfaces.forEach { interfaceType ->
            graphBuilder.addTypeRelation(
                classType,
                descriptors.toTypeDescriptor(interfaceType),
                TypeRelation.IMPLEMENTS
            )
            recordSubtype(interfaceType.fullyQualifiedName, classType.className)
        }
    }

    fun recordSubtype(superName: String, subName: String) {
        if (trackCrossMethodFunctionalDispatch && superName != JAVA_LANG_OBJECT) {
            directSubtypes.getOrPut(superName) { mutableListOf() }.add(subName)
        }
    }

    /**
     * A class's declared fields in a deterministic order. SootUp collects them into a `HashSet`
     * of `JavaSootField`, which has no `hashCode` of its own, so the set iterates in identity-hash
     * order: stable for one program, and shifted by any change in how many objects the build
     * hashes before the class is reached. Fields a body references are numbered when the body is
     * processed; the ones only this pass sees were numbered in that order, and a run of the same
     * input on another revision renumbered them (PR #174, `shape-label-scan-field`).
     */
    fun <F : SootField> Collection<F>.inDeterministicOrder(): List<F> =
        sortedWith(compareBy({ it.name }, { it.signature.toString() }))

    /**
     * Process all declared fields for a single class, ensuring FieldNodes are created.
     *
     * This is important for return type analysis to discover ALL fields in a class,
     * not just those that are referenced in methods. Fields may be:
     * - Public fields accessed directly without getter/setter
     * - Fields only used by frameworks (Jackson, Lombok, etc.)
     * - Fields initialized via reflection or deserialization
     */
    fun visitFieldsForClass(sootClass: SootClass) {
        val className = sootClass.type.fullyQualifiedName
        sootClass.fields.inDeterministicOrder().forEach { field ->
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

            val declaringClassType = descriptors.toTypeDescriptor(sootClass.type)
            val baseType = descriptors.toTypeDescriptor(field.type)

            // Try to get generic type from bytecode signature
            val fieldType = descriptors.getFieldTypeWithGenerics(className, fieldName, baseType)

            val node = FieldNode(
                id = metadata.nextNodeId("field"),
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
}
