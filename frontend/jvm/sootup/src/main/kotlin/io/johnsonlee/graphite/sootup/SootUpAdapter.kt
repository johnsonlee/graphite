package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.ConstantNode
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.EnumValueReference
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.FloatConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeRelation
import io.johnsonlee.graphite.core.ValueNode
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.FullGraphBuilder
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.CallGraphAlgorithm
import io.johnsonlee.graphite.input.EmptyResourceAccessor
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.ResourceEntry
import java.nio.file.Files
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import java.util.BitSet
import java.util.IdentityHashMap
import java.util.Locale
import java.util.ResourceBundle
import java.util.ServiceLoader
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type as AsmType
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode as AsmFieldNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import sootup.core.graph.ControlFlowGraph
import sootup.core.inputlocation.AnalysisInputLocation
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.Value
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
import sootup.core.jimple.common.expr.JCastExpr
import sootup.core.jimple.common.expr.JDynamicInvokeExpr
import sootup.core.jimple.common.expr.JNewExpr
import sootup.core.jimple.common.expr.JSpecialInvokeExpr
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
import sootup.core.jimple.common.expr.JEqExpr
import sootup.core.jimple.common.expr.JNeExpr
import sootup.core.jimple.common.expr.JLtExpr
import sootup.core.jimple.common.expr.JGeExpr
import sootup.core.jimple.common.expr.JGtExpr
import sootup.core.jimple.common.expr.JLeExpr
import sootup.core.model.Body
import sootup.core.model.SootClass
import java.lang.reflect.Modifier
import sootup.core.model.ClassModifier
import sootup.core.model.MethodModifier
import sootup.core.model.SootMethod
import sootup.core.signatures.MethodSubSignature
import sootup.core.signatures.MethodSignature
import sootup.core.util.Modifiers
import sootup.java.core.JavaSootClass
import sootup.java.bytecode.frontend.conversion.isBytecodeClassSource
import sootup.java.core.JavaSootMethod
import sootup.core.types.ClassType
import sootup.core.types.ArrayType
import sootup.core.types.PrimitiveType
import sootup.core.types.Type
import sootup.core.views.View
import sootup.callgraph.CallGraph
import sootup.callgraph.ClassHierarchyAnalysisAlgorithm
import sootup.callgraph.RapidTypeAnalysisAlgorithm

private const val RESOURCE_BUNDLE_FORMAT_PROPERTIES = "java.properties"
private const val RESOURCE_BUNDLE_FORMAT_CLASS = "java.class"
private const val PROPERTY_RESOURCE_BUNDLE_CLASS = "java.util.PropertyResourceBundle"
private const val RESOURCE_BUNDLE_CLASS = "java.util.ResourceBundle"
private const val LOCALE_CLASS = "java.util.Locale"
private const val INIT_METHOD = "<init>"
private const val KOTLIN_INVOKE = "invoke"
private const val KOTLIN_PROPERTY_GET = "get"
private const val JAVA_LANG_OBJECT = "java.lang.Object"
private const val CALLABLE_REFERENCE_RECEIVER_FIELD = "receiver"
private const val VALUE_OF_METHOD = "valueOf"
private const val CLASS_FILE_SUFFIX = ".class"
private const val PROPERTIES_FILE_SUFFIX = ".properties"
private const val CONST_NODE_PREFIX = "const"
private const val RESOURCE_SOURCE = "resource"
private const val GET_KEYS_METHOD = "getKeys"
private const val GET_BUNDLE_METHOD = "getBundle"
private const val GET_PROPERTY_METHOD = "getProperty"
private const val FROM_JSON_METHOD = "fromJson"
private const val PARSE_METHOD = "parse"
private const val OPEN_STREAM_METHOD = "openStream"
private const val NEW_READER_METHOD = "newReader"
private const val PASS1_PROGRESS_INTERVAL = 500
private const val PASS2_PROGRESS_INTERVAL = 100

/**
 * The most function values a local or a [DispatchSlot] is tracked as holding. Resolution is
 * flow- and context-insensitive, so a value that many function values reach (an `Object`
 * parameter of a shared helper, a register a dex body reuses for hundreds of lambdas) holds
 * the union of all of them, and a call on it would resolve to every one: thousands of call
 * sites that mean nothing, and on a large Android app a fixpoint that outgrows the heap.
 * Past this many the holder is [SATURATED_TARGETS]: it resolves nothing and adds nothing to
 * what it flows to, which keeps the precise targets other paths bring there.
 */
private const val MAX_TARGETS = 64

/** The targets of a holder past [MAX_TARGETS]: empty, told apart from any other set by identity. */
private val SATURATED_TARGETS: Set<DispatchTarget> = java.util.Collections.unmodifiableSet(LinkedHashSet())
private val CONVERSION_SCRATCH_FIELDS = listOf("insnIndexCache", "localVarTypeAnnotationIndex")

private fun <K, V> identityMutableMap(): MutableMap<K, V> = IdentityHashMap()

private class IntArrayBuilder(initialCapacity: Int = 8) {
    private var values = IntArray(initialCapacity)
    private var size = 0

    fun add(value: Int) {
        if (size == values.size) {
            values = values.copyOf(values.size * 2)
        }
        values[size++] = value
    }

    fun forEach(action: (Int) -> Unit) {
        for (index in 0 until size) {
            action(values[index])
        }
    }

    fun toIntArray(): IntArray = values.copyOf(size)
}

private class IntQueue(initialCapacity: Int = 16) {
    private var values = IntArray(initialCapacity)
    private var head = 0
    private var tail = 0

    fun add(value: Int) {
        if (tail == values.size) {
            values = values.copyOf(values.size * 2)
        }
        values[tail++] = value
    }

    fun isNotEmpty(): Boolean = head < tail

    fun removeFirst(): Int = values[head++]
}

private class ControlFlowIndex(private val stmtGraph: ControlFlowGraph<*>, statementsInOrder: List<Stmt>) {
    private val ids = IdentityHashMap<Stmt, Int>()
    private val statements = ArrayList<Stmt>()
    private val successorIds = ArrayList<IntArray?>()

    init {
        for (stmt in statementsInOrder) {
            register(stmt)
        }
    }

    private fun register(stmt: Stmt): Int {
        val existing = ids[stmt]
        if (existing != null) return existing
        val id = statements.size
        ids[stmt] = id
        statements += stmt
        successorIds += null
        return id
    }

    fun idOf(stmt: Stmt): Int = ids[stmt] ?: error("Statement is missing from control-flow index: $stmt")

    fun statement(id: Int): Stmt = statements[id]

    fun successors(id: Int): IntArray {
        successorIds[id]?.let { return it }
        val successors = stmtGraph.successors(statements[id])
        val encoded = IntArray(successors.size)
        for (successorIndex in successors.indices) {
            encoded[successorIndex] = register(successors[successorIndex])
        }
        successorIds[id] = encoded
        return encoded
    }
}

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
    private val inputLocationSources: Map<AnalysisInputLocation, String>,
    private val singleArtifactSource: String? = null,
    private val graphBuilder: FullGraphBuilder = DefaultGraph.Builder()
) {
    private val trackCrossMethodFunctionalDispatch = config.trackCrossMethodFunctionalDispatch
    private val extractAnnotationsEnabled = config.extractAnnotations

    private class LocalKey(val method: MethodDescriptor, val name: String) {
        override fun equals(other: Any?): Boolean =
            other is LocalKey && method === other.method && name == other.name

        override fun hashCode(): Int = 31 * System.identityHashCode(method) + name.hashCode()
    }

    private class ParameterBinding(val method: MethodDescriptor, val index: Int) {
        override fun equals(other: Any?): Boolean =
            other is ParameterBinding && method === other.method && index == other.index

        override fun hashCode(): Int = 31 * System.identityHashCode(method) + index
    }

    private data class BundleControlSpec(
        val noFallback: Boolean = false,
        val formats: Set<String> = setOf(RESOURCE_BUNDLE_FORMAT_PROPERTIES, RESOURCE_BUNDLE_FORMAT_CLASS),
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
    private data class IndexedClass(val sootClass: JavaSootClass, val source: String)

    // Maps to track created nodes for cross-referencing
    private val localNodes = mutableMapOf<LocalKey, LocalVariable>()
    private val fieldNodes = mutableMapOf<String, FieldNode>()
    private val parameterNodes = mutableMapOf<ParameterBinding, ParameterNode>()
    private val constantNodes = mutableMapOf<Any, ConstantNode>()
    private val methodReturnNodes = mutableMapOf<MethodDescriptor, ReturnNode>()
    private val allocationNodes = mutableMapOf<LocalKey, LocalVariable>()

    // Dispatch targets of locals holding a function value; keyed per method, and each method's
    // entries are removed by clearMethodState(). Every target set below is immutable once
    // stored and shared between the locals and slots that hold the same targets, see
    // mergeTargets() and addTargets(): a dex body keeps the compiler's registers as locals,
    // each reused for many values, and a copy between two such registers must neither append
    // every target again nor allocate.
    private val dynamicTargets = mutableMapOf<LocalKey, Set<DispatchTarget>>()

    // Maps local key to parameter binding for locals assigned from parameters
    private val localToParamIndex = mutableMapOf<LocalKey, ParameterBinding>()

    private val arrayDynamicTargets = mutableMapOf<LocalKey, Set<DispatchTarget>>()

    // Cross-method functional dispatch, over slots (parameters, returns, fields, locals)
    // numbered on first use by slotId(): the targets seeded into each slot, the flows between
    // slots as parallel edge lists, the adapters and the calls waiting on a slot.
    // resolveFunctionalDispatch() propagates the targets to a fixpoint once every method has
    // been processed; the numbering makes that sweeps over arrays, where a map keyed by slot
    // cost a structural hash of a method descriptor per step.
    private val slotIds = Object2IntOpenHashMap<DispatchSlot>().apply { defaultReturnValue(-1) }
    private val slots = ArrayList<DispatchSlot>()
    private val slotTargets = ArrayList<Set<DispatchTarget>?>()
    private val flowFrom = IntArrayList()
    private val flowTo = IntArrayList()
    private val slotAdapters = Int2ObjectOpenHashMap<MutableList<SlotAdapter>>()
    private val slotCalls = Int2ObjectLinkedOpenHashMap<MutableList<PendingDispatch>>()
    private val functionObjectClasses = mutableMapOf<String, Boolean>()
    private val mayHoldFunctionByType = mutableMapOf<String, Boolean>()
    // Type hierarchy of the view, for flows across override boundaries: direct subtypes of every
    // superclass and interface named by a class in the view (built in pass 1), and lazily
    // cached transitive closures, method indexes and sorted method lists
    private val directSubtypes = mutableMapOf<String, MutableList<String>>()
    private val transitiveSubtypesByClass = mutableMapOf<String, List<String>>()
    private val supertypesByClass = mutableMapOf<String, List<String>>()
    private val declaredMethodIndexByClass = mutableMapOf<String, Map<String, SootMethod>>()
    private val sortedMethodsByClass = mutableMapOf<String, List<SootMethod>>()
    private val supertypeContractsByClass = mutableMapOf<String, Set<String>?>()
    private val bridgesInvokingByClass = mutableMapOf<String, Map<String, List<String>>>()
    private val implementedByFunctionValue = mutableMapOf<MethodDescriptor, Boolean>()
    // The flow graph while resolveFunctionalDispatch() runs (null before and after), and calls
    // that resolving another call produced (`Function::apply` on a function value), waiting
    // to join slotCalls between rounds
    private var propagation: SlotPropagation? = null
    private val nestedDispatches = mutableListOf<Pair<DispatchSlot, PendingDispatch>>()

    private val localeSpecsByLocal = mutableMapOf<LocalKey, String>()
    private val localeBuilderSpecsByLocal = mutableMapOf<LocalKey, LocaleBuilderSpec>()
    private val stringValuesByLocal = mutableMapOf<LocalKey, String>()
    private val bundleControlFormatsByLocal = mutableMapOf<LocalKey, String?>()
    private val resourceHandlePathsByLocal = mutableMapOf<LocalKey, LinkedHashSet<String>>()
    private val propertiesPathsByLocal = mutableMapOf<LocalKey, LinkedHashSet<String>>()
    private val resourceBundlePaths = mutableMapOf<LocalKey, LinkedHashSet<String>>()
    private val bundleControlSpecsByLocal = mutableMapOf<LocalKey, BundleControlSpec>()

    private val resolvedMethodCache = mutableMapOf<MethodSignature, MethodSignature>()
    private val methodDescriptorCache = mutableMapOf<MethodSignature, MethodDescriptor>()
    private val typeDescriptorCache = identityMutableMap<Type, TypeDescriptor>()
    private val declaredMethodSubSignaturesByClass = mutableMapOf<String, Set<String>>()
    private val fieldGenericTypesByClass = mutableMapOf<String, Map<String, TypeDescriptor>>()
    private val classOriginsByName = mutableMapOf<String, String>()
    private val classOriginSourceCounts = mutableMapOf<String, Int>()
    private val artifactDependenciesByArtifact = mutableMapOf<String, MutableMap<String, Int>>()
    private val resourceFilesByPath = mutableMapOf<String, MutableList<ResourceFileNode>>()
    private val configurationResourcePaths = linkedSetOf<String>()
    private val runtimeIndexedBundles = mutableSetOf<String>()
    private val bundleControlSpecsByClass = mutableMapOf<String, BundleControlSpec?>()
    private var classesByNameCache: Map<String, SootClass>? = null
    private val syntheticIdentities = SyntheticIdentity.Collector()
    /** Whether the class whose methods pass two is processing is a synthetic class, see [SyntheticIdentity]. */
    private var activeSyntheticClass = false

    // Per-method: tracks which NodeIds were created from each statement
    // Reset per method in processMethod()
    private var stmtNodeIds = identityMutableMap<Stmt, IntArrayBuilder>()

    /**
     * Per-method writes to locals, keyed by statement and holding
     * `[localNodeId, constantNodeId | NO_CONSTANT]`. Filled in pass one alongside the
     * edges, consumed in pass two to attach [io.johnsonlee.graphite.core.LocalDefinition]s
     * to each branch side and to the per-local tables. Same lifecycle as [stmtNodeIds].
     */
    private var stmtLocalWrites = identityMutableMap<Stmt, IntArray>()

    /**
     * Identity statements (`r := @this`, `$e := @caughtexception`) that write a local whose
     * node may only be created by a later statement; resolved by name in pass two.
     */
    private var stmtIdentityWrites = identityMutableMap<Stmt, String>()
    private var activeMethod: MethodDescriptor? = null
    private var activeMethodLocals = mutableListOf<LocalKey>()
    private var activeLocalKeysByName = mutableMapOf<String, LocalKey>()
    private var activeMethodParameters = mutableListOf<ParameterBinding>()
    private var activeParameterBindingsByIndex = mutableMapOf<Int, ParameterBinding>()
    private fun persistedClassOrigins(): Map<String, String> {
        if (classOriginSourceCounts.size <= 1) {
            return emptyMap()
        }
        return classOriginsByName.toMap()
    }

    /**
     * Build the complete graph from the SootUp view
     */
    fun buildGraph(): Graph {
        val classes = view.classes.toList()
        classesByNameCache = classes.associateBy { it.type.fullyQualifiedName }
        val indexedClasses: List<IndexedClass>
        val loadedClassSources: Set<String>
        if (singleArtifactSource == null) {
            indexedClasses = indexSootClassOrigins(classes)
            loadedClassSources = indexedClasses.mapTo(HashSet()) { it.source }
        } else {
            indexedClasses = emptyList()
            loadedClassSources = setOf(singleArtifactSource)
        }
        val unloadedClassEntries = indexResourceValues(loadedClassSources)
        if (singleArtifactSource == null && classOriginSourceCounts.size > 1) {
            indexedClasses.forEach { indexArtifactDependency(it.sootClass, it.source) }
            unloadedClassEntries.forEach(::indexArtifactDependency)
        } else {
            log("Skipping artifact dependency extraction for single-artifact input")
        }
        indexClassBundles(classes)
        log("Starting buildGraph pass 1")
        var pass1Count = 0
        // Pass 1: All classes — type hierarchy + enum values
        // Enum values must be fully collected before processing methods
        // (a method in class A may reference an enum from class B)
        classes.forEach { sootClass ->
            pass1Count++
            if (pass1Count % PASS1_PROGRESS_INTERVAL == 0) {
                log { "Pass 1 processed $pass1Count classes; current=${sootClass.type}" }
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
        classes.asSequence()
            .filter { shouldIncludeClass(it) }
            .forEach { sootClass ->
                pass2Count++
                if (pass2Count % PASS2_PROGRESS_INTERVAL == 0) {
                    log { "Pass 2 processed $pass2Count classes; current=${sootClass.type}" }
                }
                if (extractAnnotationsEnabled && sootClass is JavaSootClass) {
                    val className = sootClass.type.fullyQualifiedName
                    extractAnnotations(sootClass.annotations, className, "<class>")
                    sootClass.fields.forEach { field ->
                        extractAnnotations(field.annotations, className, field.name)
                    }
                }

                activeSyntheticClass = SyntheticIdentity.isSyntheticClass(sootClass)
                if (activeSyntheticClass) {
                    syntheticIdentities.addClass(sootClass)
                }
                forEachMethod(sootClass) { method ->
                    processMethod(method)
                    if (extractAnnotationsEnabled && sootClass is JavaSootClass && method is JavaSootMethod) {
                        extractAnnotations(method.annotations, sootClass.type.fullyQualifiedName, method.name)
                    }
                }

                visitFieldsForClass(sootClass)
                extensions.forEach { it.visit(sootClass, extensionContext) }
                bytecodeMethodsCache.remove(sootClass)
            }

        syntheticIdentities.resolve().forEach { (member, fingerprint) ->
            graphBuilder.addSyntheticIdentity(member, fingerprint)
        }
        if (syntheticIdentities.skipped > 0) {
            log { "Skipped ${syntheticIdentities.skipped} synthetic member(s) whose identity could not be rendered" }
        }

        // Pass 2B: Resolve cross-method functional interface dispatch
        if (trackCrossMethodFunctionalDispatch) {
            resolveFunctionalDispatch()
        }

        // Build call graph if configured
        if (config.buildCallGraph) {
            processCallGraph()
        }

        persistedClassOrigins().forEach { (className, source) ->
            graphBuilder.addClassOrigin(className, source)
        }
        artifactDependenciesByArtifact.forEach { (fromArtifact, dependencies) ->
            dependencies.forEach { (toArtifact, weight) ->
                graphBuilder.addArtifactDependency(fromArtifact, toArtifact, weight)
            }
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

    private inline fun log(message: () -> String) {
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
    private fun extractEnumValues(enumClass: SootClass) {
        val className = enumClass.type.fullyQualifiedName
        log { "Processing enum class: $className" }

        val clinit = firstMethod(enumClass) { it.name == "<clinit>" && it.isStatic }
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
            releaseConversionState(clinit)
        }
        val stmtGraph = body.controlFlowGraph

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
                            log { "  Tracked enum reference: ${left.name} = $fieldDeclClass.${fieldSig.name}" }
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
                            log { "  Found field assignment: $fieldName = ${right.name} (resolved to $originalLocal)" }
                            val initValues = findEnumInitValues(originalLocal, stmtGraph, localValues)
                            if (initValues.isNotEmpty()) {
                                graphBuilder.addEnumValues(className, fieldName, initValues)
                                log { "  Extracted enum value: $className.$fieldName = $initValues" }
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
    private fun findEnumInitValues(localName: String, stmtGraph: ControlFlowGraph<*>, localValues: Map<String, Any?>): List<Any?> {
        for (stmt in stmtGraph) {
            if (stmt !is JInvokeStmt) continue

            val invokeExpr = stmt.invokeExpr.orElse(null) ?: continue
            log { "    Checking invoke: ${invokeExpr.javaClass.simpleName} - ${invokeExpr.methodSignature}" }

            if (invokeExpr !is AbstractInstanceInvokeExpr) {
                log { "    Skipping: not AbstractInstanceInvokeExpr" }
                continue
            }
            if (invokeExpr.methodSignature.name != INIT_METHOD) {
                log { "    Skipping: method name is '${invokeExpr.methodSignature.name}', not '<init>'" }
                continue
            }

            val base = invokeExpr.base
            log { "    Base: ${base.javaClass.simpleName} - $base (looking for $localName)" }
            if (base.name != localName) continue

            // Found the <init> call
            // Args: [name, ordinal, ...user args...]
            val args = invokeExpr.args
            log { "    Found <init> for $localName with ${args.size} args: ${args.map { it.toString() }}" }
            if (args.size > 2) {
                // Get all user-defined arguments (starting from index 2)
                return args.drop(2).map { arg ->
                    extractValueFromArg(arg, localValues)
                }
            } else {
                log { "    Only ${args.size} args (need > 2 for user-defined values)" }
            }
        }
        log { "    No <init> call found for local $localName" }
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
            recordSubtype(superType.fullyQualifiedName, classType.className)
        }

        // Process interfaces
        sootClass.interfaces.forEach { interfaceType ->
            graphBuilder.addTypeRelation(
                classType,
                toTypeDescriptor(interfaceType),
                TypeRelation.IMPLEMENTS
            )
            recordSubtype(interfaceType.fullyQualifiedName, classType.className)
        }
    }

    private fun recordSubtype(superName: String, subName: String) {
        if (trackCrossMethodFunctionalDispatch && superName != JAVA_LANG_OBJECT) {
            directSubtypes.getOrPut(superName) { mutableListOf() }.add(subName)
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
        methodReturnNodes[methodDescriptor] = returnNode
        graphBuilder.addNode(returnNode)

        activeMethod = methodDescriptor
        activeMethodLocals = mutableListOf()
        activeLocalKeysByName = mutableMapOf()
        activeMethodParameters = mutableListOf()
        activeParameterBindingsByIndex = mutableMapOf()

        try {
            // Process method body if available
            if (method.hasBody()) {
                processMethodBody(method, methodDescriptor)
            }
            // Rendered here, while the body is materialised, rather than in a pass of its own:
            // streamed methods are rebuilt on every enumeration.
            val syntheticMethod = SyntheticIdentity.isSyntheticMethod(method)
            if (activeSyntheticClass || syntheticMethod) {
                syntheticIdentities.addMethod(method, methodDescriptor.signature, syntheticMethod)
            }
        } catch (oom: OutOfMemoryError) {
            // Android/large corpus can contain a few pathological methods whose CFG
            // materialization explodes heap. Skip the offending method so graph build
            // can continue instead of failing the entire load.
            log { "Skipping method due to OOM while building ${methodDescriptor.signature}: ${oom.message}" }
            System.gc()
        } finally {
            clearMethodState(methodDescriptor)
            releaseConversionState(method)
        }
    }

    private fun processMethodBody(method: SootMethod, methodDescriptor: MethodDescriptor) {
        val stmtGraph = method.body.controlFlowGraph
        val statements = stmtGraph.stmts

        // Reset per-method stmt tracking
        stmtNodeIds = identityMutableMap()
        stmtLocalWrites = identityMutableMap()
        stmtIdentityWrites = identityMutableMap()

        // Process parameters
        processParameters(method, methodDescriptor)

        var stmtCount = 0
        var branchStatements: MutableList<JIfStmt>? = null
        var hasBranch = false
        for (stmt in statements) {
            processStatement(stmt, methodDescriptor)
            if (stmt is JIfStmt) {
                hasBranch = true
                if (stmtCount < MAX_CONTROL_FLOW_STATEMENTS) {
                    branchStatements = (branchStatements ?: mutableListOf()).also { it.add(stmt) }
                }
            }
            stmtCount++
        }

        if (!hasBranch) return
        if (stmtCount <= MAX_CONTROL_FLOW_STATEMENTS) {
            processControlFlow(branchStatements ?: emptyList(), stmtGraph, statements, methodDescriptor)
        } else {
            log {
                "Skipping control-flow extraction for ${methodDescriptor.signature}: " +
                    "$stmtCount statements exceed $MAX_CONTROL_FLOW_STATEMENTS"
            }
        }
    }

    /**
     * Record that a node was created from a given statement.
     */
    private fun recordStmtNode(stmt: Stmt, nodeId: NodeId) {
        stmtNodeIds.getOrPut(stmt) { IntArrayBuilder(1) }.add(nodeId.value)
    }

    /**
     * Record that [stmt] writes the local [localId]; [constantId] is the constant node
     * when the value is a constant (recorded at the same point as its ASSIGN edge, so
     * the two stay one-to-one) and [BranchScope.NO_CONSTANT] otherwise.
     */
    private fun recordLocalWrite(stmt: Stmt, localId: NodeId, constantId: Int) {
        stmtLocalWrites[stmt] = intArrayOf(localId.value, constantId)
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

    private fun processStatement(stmt: Stmt, method: MethodDescriptor) {
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
            trackFunctionObject(method, leftOp, allocType.className, allocNode.id, stmt)
            recordLocalWrite(stmt, allocNode.id, BranchScope.NO_CONSTANT)
            return
        }

        val targetNode = getOrCreateValueNode(leftOp, method)
        // A cast passes its operand through unchanged, e.g. the `(String) arg` a bridge method
        // applies before calling the typed implementation
        val sourceNode = getOrCreateValueNode((rightOp as? JCastExpr)?.op ?: rightOp, method)

        if (targetNode != null) {
            recordStmtNode(stmt, targetNode.id)
            if (leftOp is Local) {
                val constantId = if (sourceNode is ConstantNode) sourceNode.id.value else BranchScope.NO_CONSTANT
                recordLocalWrite(stmt, targetNode.id, constantId)
            }
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

        trackFunctionValueAssignment(stmt, method, targetNode)

        if (leftOp is Local) {
            val targetKey = localKey(method, leftOp.name)
            when (rightOp) {
                is SootStringConstant -> stringValuesByLocal[targetKey] = rightOp.value
            }
            when (rightOp) {
                is Local -> {
                    val sourceKey = localKey(method, rightOp.name)
                    stringValuesByLocal[sourceKey]?.let { stringValuesByLocal[targetKey] = it }
                    localeSpecsByLocal[sourceKey]?.let { localeSpecsByLocal[targetKey] = it }
                    localeBuilderSpecsByLocal[sourceKey]?.let { localeBuilderSpecsByLocal[targetKey] = it }
                    bundleControlFormatsByLocal[sourceKey]?.let { bundleControlFormatsByLocal[targetKey] = it }
                    resourceHandlePathsByLocal[sourceKey]?.let { resourceHandlePathsByLocal[targetKey] = LinkedHashSet(it) }
                    propertiesPathsByLocal[sourceKey]?.let { propertiesPathsByLocal[targetKey] = LinkedHashSet(it) }
                    resourceBundlePaths[sourceKey]?.let { resourceBundlePaths[targetKey] = LinkedHashSet(it) }
                    bundleControlSpecsByLocal[sourceKey]?.let { bundleControlSpecsByLocal[targetKey] = it }
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

    /** Follow function values through an assignment: field and array stores and loads, copies and casts. */
    private fun trackFunctionValueAssignment(stmt: JAssignStmt, method: MethodDescriptor, targetNode: ValueNode?) {
        val leftOp = stmt.leftOp
        val rightOp = stmt.rightOp
        // Track function values stored to fields: `this.callback = fn`
        if (leftOp is JFieldRef && rightOp is Local) {
            trackFlow(method, rightOp, DispatchSlot.Field(leftOp.fieldSignature.toString()))
        }

        // Track function values stored into array elements (varargs): `fns[0] = fn`
        if (leftOp is JArrayRef && rightOp is Local) {
            val base = leftOp.base
            dynamicTargets[localKey(method, rightOp.name)]?.let { targets ->
                val arrayKey = localKey(method, base.name)
                arrayDynamicTargets[arrayKey] = mergeTargets(arrayDynamicTargets[arrayKey], targets)
            }
            trackFlow(method, rightOp, slotOf(method, base))
        }

        if (leftOp is Local && targetNode is LocalVariable) {
            trackFunctionValueLoad(stmt, method, leftOp, targetNode)
        }
    }

    /** Follow function values into a local: array and field loads, `INSTANCE` singletons, copies and casts. */
    private fun trackFunctionValueLoad(stmt: JAssignStmt, method: MethodDescriptor, leftOp: Local, targetNode: LocalVariable) {
        when (val rightOp = stmt.rightOp) {
            // Array element loads: `fn = fns[0]`
            is JArrayRef -> {
                val arrayKey = localKey(method, rightOp.base.name)
                arrayDynamicTargets[arrayKey]?.let { targets -> mergeLocalTargets(method, leftOp, targets) }
                trackSlotFlow(slotOf(method, rightOp.base), method, leftOp)
            }
            // Field loads: `fn = this.callback`, including Kotlin's `Lambda.INSTANCE` singletons
            is JFieldRef -> {
                trackSlotFlow(DispatchSlot.Field(rightOp.fieldSignature.toString()), method, leftOp)
                val fieldClass = rightOp.fieldSignature.declClassType.fullyQualifiedName
                if (rightOp is JStaticFieldRef && toTypeDescriptor(rightOp.type).className == fieldClass) {
                    trackFunctionObject(method, leftOp, fieldClass, targetNode.id, stmt)
                }
            }
            // Copies: `a = b`
            is Local -> copyFunctionValue(method, rightOp, leftOp)
            // Casts: `fn = (Function1) lambda`, emitted after every Kotlin lambda allocation
            is JCastExpr -> (rightOp.op as? Local)?.let { copyFunctionValue(method, it, leftOp) }
            else -> Unit
        }
    }

    private fun processIdentity(stmt: JIdentityStmt, method: MethodDescriptor) {
        val leftOp = stmt.leftOp
        val rightOp = stmt.rightOp
        if (rightOp !is JParameterRef && leftOp is Local) {
            stmtIdentityWrites[stmt] = leftOp.name
        }

        if (rightOp is JParameterRef) {
            val paramIndex = rightOp.index
            val paramKey = parameterBinding(method, paramIndex)
            val paramNode = parameterNodes[paramKey]
            val localNode = getOrCreateValueNode(leftOp, method)
            if (localNode != null) {
                recordLocalWrite(stmt, localNode.id, BranchScope.NO_CONSTANT)
            }

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
            if (trackCrossMethodFunctionalDispatch) {
                val localKey = localKey(method, leftOp.name)
                localToParamIndex[localKey] = parameterBinding(method, paramIndex)
            }
        }
    }

    private fun processInvoke(stmt: JInvokeStmt, method: MethodDescriptor) {
        val invokeExpr = stmt.invokeExpr.orElse(null) ?: return
        processInvokeExpr(invokeExpr, method, null, stmt)
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
        val args = invokeExpr.args
        val argNodeIds = argumentNodeIds(args, caller)

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
            if (stmt != null && resultNode is LocalVariable && isPrimitiveBoxingCall(calleeSignature, args)) {
                recordLocalWrite(stmt, resultNode.id, argNodeIds[0].value)
            }
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

        if (calleeSignature.declClassType.fullyQualifiedName == LOCALE_CLASS && calleeSignature.name == INIT_METHOD) {
            val receiverLocal = (invokeExpr as? AbstractInstanceInvokeExpr)?.base as? Local
            val localeSpec = extractConstructedLocaleSpec(invokeExpr)
            if (receiverLocal != null && localeSpec != null) {
                localeSpecsByLocal[localKey(caller, receiverLocal.name)] = localeSpec
            }
        }
        updateLocaleBuilderState(caller, calleeSignature, invokeExpr, resultNode)

        // Extract receiver for instance method calls
        val receiverLocal = (invokeExpr as? AbstractInstanceInvokeExpr)?.base
        val receiverNode = if (receiverLocal != null) {
            getOrCreateValueNode(receiverLocal, caller)
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
        val resourceRelevantCall = isResourceRelevantCall(calleeSignature)
        if (resourceRelevantCall) {
            linkResourceReads(callSite, calleeSignature, invokeExpr)
            linkResourceFileReads(callSite, calleeSignature, invokeExpr, caller)
            linkResourceBundleReads(callSite, calleeSignature, invokeExpr, caller)
            linkStructuredResourceLoads(callSite, calleeSignature, invokeExpr, caller)
            trackResourceAssociations(callSite, calleeSignature, invokeExpr, caller, resultNode)
        }

        // Resolve functional interface dispatch: a call on a local holding a function value
        // (e.g. Function.apply, Function1.invoke) also calls the function value's implementation.
        // Targets known in this method resolve now; the call also waits on the receiver's slot for
        // targets that arrive from other methods (parameters, returns, fields).
        // Constructor and private calls (`invokespecial`) never dispatch.
        if (receiverLocal is Local && receiverNode is LocalVariable && invokeExpr !is JSpecialInvokeExpr) {
            val pending = PendingDispatch(
                callSite = callSite,
                result = resultNode?.id,
                argumentSlots = args.map { arg -> (arg as? Local)?.let { functionSlotOf(caller, it) } },
                resultSlot = (resultNode as? LocalVariable)?.let { DispatchSlot.Local(caller, it.name) },
                resolved = mutableSetOf()
            )
            dynamicTargets[localKey(caller, receiverLocal.name)].orEmpty()
                .filter(pending.resolved::add)
                .forEach { emitResolvedDispatch(pending, it) }
            if (trackCrossMethodFunctionalDispatch && mayHoldFunction(receiverNode.type)) {
                callsOn(slotId(slotOf(caller, receiverLocal))).add(pending)
            }
        }
        if (invokeExpr is JSpecialInvokeExpr && callee.name == INIT_METHOD && caller.name == INIT_METHOD) {
            trackCallableReferenceReceiver(caller, callee, args)
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

            // Track function values passed as arguments for cross-method dispatch
            (args.getOrNull(index) as? Local)?.let { arg ->
                trackFlow(caller, arg, DispatchSlot.Parameter(callee, index))
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
            if (resourceRelevantCall) {
                extractResourceLookupPath(caller, calleeSignature, invokeExpr)?.let {
                    resourceHandlePathsByLocal[resultKey] = linkedSetOf(it)
                }
                extractResourceBundlePaths(caller, calleeSignature, invokeExpr)?.let {
                    resourceBundlePaths[resultKey] = LinkedHashSet(it)
                }
            }
            extractLocaleFactorySpec(calleeSignature, invokeExpr)?.let { localeSpecsByLocal[resultKey] = it }
            extractBundleControlSpec(caller, calleeSignature, invokeExpr)?.let { bundleControlSpecsByLocal[resultKey] = it }
            if (trackCrossMethodFunctionalDispatch && mayHoldFunction(resultNode.type)) {
                flowSlot(DispatchSlot.Return(callee), DispatchSlot.Local(caller, resultNode.name))
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
        val handles = invokeExpr.bootstrapArgs
            .filterIsInstance<MethodHandle>()
            .filter { it.isMethodRef }
            .mapNotNull { handle ->
                val sig = handle.referenceSignature
                if (sig is MethodSignature) toMethodDescriptor(sig) to handleKind(handle) else null
            }

        // Create argument nodes: the values the lambda or method reference captures
        val args = invokeExpr.args
        val argNodeIds = argumentNodeIds(args, caller)
        val samName = invokeExpr.methodSignature.name
        val targets = mutableListOf<DispatchTarget>()
        val leftOp = (stmt as? JAssignStmt)?.leftOp
        val resultLocal = (leftOp as? Local)?.name ?: (resultNode as? LocalVariable)?.name
        val resultSlot = when {
            leftOp is JFieldRef -> DispatchSlot.Field(leftOp.fieldSignature.toString())
            resultLocal != null -> DispatchSlot.Local(caller, resultLocal)
            else -> null
        }

        // For each target method, create a call site
        for ((target, kind) in handles) {
            // An instance handle takes its first captured value as the receiver, so the remaining
            // captures line up with the implementation's parameters
            val callSite = CallSiteNode(
                id = nextNodeId("call"),
                caller = caller,
                callee = target,
                lineNumber = null,
                receiver = if (kind == HandleKind.INSTANCE) argNodeIds.firstOrNull() else null,
                arguments = if (kind == HandleKind.INSTANCE) argNodeIds.drop(1) else argNodeIds
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

            // Dataflow from arguments to call site, and from the call to its result
            argNodeIds.forEach { graphBuilder.addEdge(DataFlowEdge(from = it, to = callSite.id, kind = DataFlowKind.PARAMETER_PASS)) }
            if (resultNode != null) {
                graphBuilder.addEdge(DataFlowEdge(from = callSite.id, to = resultNode.id, kind = DataFlowKind.RETURN_VALUE))
            }

            // Captured function values flow into the implementation's parameters, e.g. a Kotlin
            // lambda capturing another lambda compiles to `outer$lambda$1(Function1 inner, ...)`
            args.forEachIndexed { index, arg ->
                val paramIndex = if (kind == HandleKind.INSTANCE) index - 1 else index
                if (arg is Local && paramIndex >= 0) {
                    trackFlow(caller, arg, DispatchSlot.Parameter(target, paramIndex))
                }
            }

            targets += DispatchTarget.Handle(target, kind, samName, argNodeIds)

            // A method reference to a function value's own method (`fn::apply`) dispatches on to
            // whatever that function value dispatches to: what is known in this method now, and
            // what reaches the receiver from other methods (a parameter, a field) in the fixpoint
            val boundReceiver = args.firstOrNull() as? Local
            if (kind == HandleKind.INSTANCE && boundReceiver != null) {
                dynamicTargets[localKey(caller, boundReceiver.name)]?.forEach { inner ->
                    targets += DispatchTarget.Adapted(samName, target, argNodeIds, inner)
                }
                if (trackCrossMethodFunctionalDispatch && resultSlot != null && mayHoldFunction(toTypeDescriptor(boundReceiver.type))) {
                    val receiverId = slotId(slotOf(caller, boundReceiver))
                    (slotAdapters.get(receiverId) ?: mutableListOf<SlotAdapter>().also { slotAdapters.put(receiverId, it) })
                        .add(SlotAdapter(resultSlot, samName, target, argNodeIds))
                }
            }
        }

        // Track dynamic targets for functional interface dispatch resolution
        // Merge with existing targets (supports conditional assignment where both branches
        // assign different lambdas/method references to the same local)
        if (resultLocal != null && targets.isNotEmpty()) {
            val key = localKey(caller, resultLocal)
            dynamicTargets[key] = mergeTargets(dynamicTargets[key], targets)
        }

        // Track dynamic targets flowing directly to a field store:
        // e.g., this.mapper = invokedynamic(...) where resultNode is a FieldNode
        if (trackCrossMethodFunctionalDispatch && leftOp is JFieldRef && targets.isNotEmpty()) {
            seedSlot(DispatchSlot.Field(leftOp.fieldSignature.toString()), targets)
        }

        // If no method handles found, fall back to creating a call site with the synthetic method
        if (handles.isEmpty()) {
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

    private fun argumentNodeIds(args: List<Value>, caller: MethodDescriptor): List<NodeId> {
        if (args.isEmpty()) return emptyList()
        val nodeIds = ArrayList<NodeId>(args.size)
        for (arg in args) {
            val argNode = getOrCreateValueNode(arg, caller)
            nodeIds += argNode?.id ?: nextNodeId("unknown")
        }
        return nodeIds
    }

    /**
     * Check if this is a boxing method like Integer.valueOf(int)
     */
    private fun isBoxingMethod(signature: MethodSignature): Boolean {
        val className = signature.declClassType.fullyQualifiedName
        val methodName = signature.name
        return methodName == VALUE_OF_METHOD && className in WRAPPER_CLASSES
    }

    /**
     * True only for the primitive boxing overload, `Wrapper.valueOf(<primitive>)`, called with a constant.
     * Parsing overloads such as `Integer.valueOf(String)` share the name but compute (and may throw), so
     * their String argument is never a constant definition of the result.
     */
    private fun isPrimitiveBoxingCall(signature: MethodSignature, args: List<Value>): Boolean {
        val primitive = BOXED_PRIMITIVES[signature.declClassType.fullyQualifiedName]
        return args.singleOrNull() is SootConstant &&
            signature.parameterTypes.singleOrNull()?.toString() == primitive
    }

    /**
     * Check if this is an unboxing method like Integer.intValue()
     */
    private fun isUnboxingMethod(signature: MethodSignature): Boolean {
        val className = signature.declClassType.fullyQualifiedName
        val methodName = signature.name
        return className in WRAPPER_CLASSES && methodName in UNBOXING_METHODS
    }

    companion object {
        /** The primitive each wrapper's boxing overload takes: `Integer.valueOf(int)`, `Boolean.valueOf(boolean)`, ... */
        private val BOXED_PRIMITIVES = mapOf(
            "java.lang.Integer" to "int",
            "java.lang.Long" to "long",
            "java.lang.Short" to "short",
            "java.lang.Byte" to "byte",
            "java.lang.Float" to "float",
            "java.lang.Double" to "double",
            "java.lang.Boolean" to "boolean",
            "java.lang.Character" to "char"
        )
        private val WRAPPER_CLASSES: Set<String> = BOXED_PRIMITIVES.keys
        private val UNBOXING_METHODS = setOf(
            "intValue",
            "longValue",
            "shortValue",
            "byteValue",
            "floatValue",
            "doubleValue",
            "booleanValue",
            "charValue"
        )
        private val RESOURCE_LOOKUP_METHODS = setOf(GET_PROPERTY_METHOD, "getString", "getObject")
        private val RESOURCE_LOOKUP_CLASSES = setOf(
            PROPERTIES_CLASS,
            PROPERTY_RESOURCE_BUNDLE_CLASS,
            RESOURCE_BUNDLE_CLASS
        )
        private val RESOURCE_PATH_METHODS = setOf("getResource", "getResourceAsStream")
        private val RESOURCE_BUNDLE_READ_METHODS = setOf("getString", "getObject", GET_KEYS_METHOD)
        private val PROPERTIES_LOAD_METHODS = setOf("load", "loadFromXML")
        private val READER_BRIDGE_CLASSES = setOf(
            "java.io.InputStreamReader",
            "java.io.BufferedReader",
            "java.io.StringReader",
            "java.io.LineNumberReader"
        )
        private const val LOCALE_BUILDER_CLASS = "java.util.Locale\$Builder"
        private const val LOCALE_BUILDER_CLASS_ALT = "java.util.Locale.Builder"
        private const val RESOURCE_BUNDLE_CONTROL_CLASS = "java.util.ResourceBundle\$Control"
        private const val RESOURCE_BUNDLE_CONTROL_CLASS_ALT = "java.util.ResourceBundle.Control"
        private const val PROPERTIES_CLASS = "java.util.Properties"
        private const val CLASS_LOADER_CLASS = "java.lang.ClassLoader"
        private const val CLASS_CLASS = "java.lang.Class"
        private const val GSON_CLASS = "com.google.gson.Gson"
        private const val DOCUMENT_BUILDER_CLASS = "javax.xml.parsers.DocumentBuilder"
        private const val URL_CLASS = "java.net.URL"
        private const val CHANNELS_CLASS = "java.nio.channels.Channels"
        private const val MAX_CONTROL_FLOW_STATEMENTS = 2_000
    }

    private fun isLocaleBuilderClassName(name: String): Boolean =
        name == LOCALE_BUILDER_CLASS || name == LOCALE_BUILDER_CLASS_ALT

    private fun isResourceBundleControlTypeName(name: String): Boolean =
        name == RESOURCE_BUNDLE_CONTROL_CLASS || name == RESOURCE_BUNDLE_CONTROL_CLASS_ALT

    @Suppress("CyclomaticComplexMethod")
    private fun isResourceRelevantCall(calleeSignature: MethodSignature): Boolean {
        val declaringClass = calleeSignature.declClassType.fullyQualifiedName
        val methodName = calleeSignature.name
        return when {
            declaringClass == PROPERTIES_CLASS ->
                methodName in RESOURCE_LOOKUP_METHODS || methodName in PROPERTIES_LOAD_METHODS
            declaringClass == PROPERTY_RESOURCE_BUNDLE_CLASS ->
                methodName in RESOURCE_LOOKUP_METHODS || methodName == INIT_METHOD || methodName == GET_KEYS_METHOD
            declaringClass == RESOURCE_BUNDLE_CLASS ->
                methodName == GET_BUNDLE_METHOD || methodName in RESOURCE_BUNDLE_READ_METHODS
            declaringClass == CLASS_LOADER_CLASS || declaringClass == CLASS_CLASS ->
                methodName in RESOURCE_PATH_METHODS
            declaringClass == GSON_CLASS ->
                methodName == FROM_JSON_METHOD
            declaringClass == DOCUMENT_BUILDER_CLASS ->
                methodName == PARSE_METHOD
            declaringClass == URL_CLASS ->
                methodName == OPEN_STREAM_METHOD
            declaringClass == CHANNELS_CLASS ->
                methodName == NEW_READER_METHOD
            methodName == INIT_METHOD && declaringClass in READER_BRIDGE_CLASSES ->
                true
            else -> false
        }
    }

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

            // Track function values flowing through return values
            if (returnValue is Local) {
                trackFlow(method, returnValue, DispatchSlot.Return(method))
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
    private fun processControlFlow(
        branchStatements: List<JIfStmt>,
        stmtGraph: ControlFlowGraph<*>,
        statements: List<Stmt>,
        method: MethodDescriptor
    ) {
        val controlFlowIndex = ControlFlowIndex(stmtGraph, statements)
        val reachableCache = HashMap<Int, BitSet>()
        val pending = ArrayList<PendingBranchScope>(branchStatements.size)
        for (stmt in branchStatements) {
            val condition = stmt.condition

            val op1 = condition.op1
            val op2 = condition.op2

            // Resolve operands to NodeIds
            val op1Node = getOrCreateValueNode(op1, method)
            val op2Node = getOrCreateValueNode(op2, method)
            if (op1Node == null || op2Node == null) continue

            val comparisonOp = comparisonOp(condition) ?: continue

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
            val successors = controlFlowIndex.successors(controlFlowIndex.idOf(stmt))
            if (successors.size != 2) continue

            val falseSuccessor = successors[0]  // fall-through
            val trueSuccessor = successors[1]   // goto target

            // Walk each branch collecting all reachable node ids until merge point.
            val trueStatements = branchStatements(trueSuccessor, falseSuccessor, controlFlowIndex, reachableCache)
            val falseStatements = branchStatements(falseSuccessor, trueSuccessor, controlFlowIndex, reachableCache)
            val trueIds = nodeIdsFor(trueStatements, controlFlowIndex)
            val falseIds = nodeIdsFor(falseStatements, controlFlowIndex)

            addControlFlowEdges(conditionNodeId, comparison, trueIds, falseIds)

            // Record branch data (BranchScope is materialised lazily by DefaultGraph)
            if (trueIds.isNotEmpty() || falseIds.isNotEmpty()) {
                pending += PendingBranchScope(
                    conditionNodeId,
                    comparison,
                    trueIds,
                    falseIds,
                    definitionStatements(trueStatements, trueSuccessor, reachableCache.getValue(falseSuccessor)),
                    definitionStatements(falseStatements, falseSuccessor, reachableCache.getValue(trueSuccessor))
                )
            }
        }
        if (pending.isEmpty()) return
        val writes = resolveLocalWrites(controlFlowIndex)
        val trackedLocals = trackedLocals(pending, writes)
        for (scope in pending) {
            graphBuilder.addBranchScope(
                conditionNodeId = scope.conditionNodeId,
                method = method,
                comparison = scope.comparison,
                trueBranchNodeIds = scope.trueIds,
                falseBranchNodeIds = scope.falseIds,
                trueDefinitions = definitionsFor(scope.trueOnlyStatements, writes, trackedLocals),
                falseDefinitions = definitionsFor(scope.falseOnlyStatements, writes, trackedLocals)
            )
        }
        recordLocalDefinitionTables(writes, trackedLocals)
    }

    /** A branch whose scope is emitted once the method's tracked locals are known. */
    private class PendingBranchScope(
        val conditionNodeId: NodeId,
        val comparison: BranchComparison,
        val trueIds: IntArray,
        val falseIds: IntArray,
        /** The statements whose writes are this side's definitions: reached only through this side. */
        val trueOnlyStatements: BitSet,
        val falseOnlyStatements: BitSet
    )

    /**
     * The statements whose writes belong to one side, derived from raw reachability: those the side's
     * successor reaches and the other successor does not. [branchStatements] forces a side's own
     * successor into its set even when the other side reaches it (a merge point, a loop exit, or the
     * shared target of a branch with an empty `then`); that keeps the node sets and control-flow edges
     * as they are, but a write there executes whichever way the branch goes, so it is a definition of
     * neither side. Subtracting it with a killed side would drop a write the other side still makes.
     */
    private fun definitionStatements(sideStatements: BitSet, successorId: Int, otherReachable: BitSet): BitSet =
        if (otherReachable.get(successorId)) (sideStatements.clone() as BitSet).also { it.clear(successorId) } else sideStatements

    /**
     * The method's local writes by statement ordinal: `[localNodeId, constantNodeId | NO_CONSTANT]`.
     * Identity writes are resolved to the local's node here, without creating one.
     */
    private fun resolveLocalWrites(controlFlowIndex: ControlFlowIndex): Int2ObjectOpenHashMap<IntArray> {
        val writes = Int2ObjectOpenHashMap<IntArray>(stmtLocalWrites.size + stmtIdentityWrites.size)
        for ((stmt, write) in stmtLocalWrites) {
            writes.put(controlFlowIndex.idOf(stmt), write)
        }
        val method = activeMethod ?: return writes
        for ((stmt, localName) in stmtIdentityWrites) {
            val key = localKey(method, localName)
            val node = allocationNodes[key] ?: localNodes[key] ?: continue
            writes.put(controlFlowIndex.idOf(stmt), intArrayOf(node.id.value, BranchScope.NO_CONSTANT))
        }
        return writes
    }

    /** Locals with a constant write on some branch side: the only ones a consumer can fold. */
    private fun trackedLocals(pending: List<PendingBranchScope>, writes: Int2ObjectOpenHashMap<IntArray>): IntOpenHashSet {
        val tracked = IntOpenHashSet()
        for (scope in pending) {
            collectConstantlyWrittenLocals(scope.trueOnlyStatements, writes, tracked)
            collectConstantlyWrittenLocals(scope.falseOnlyStatements, writes, tracked)
        }
        return tracked
    }

    private fun collectConstantlyWrittenLocals(statements: BitSet, writes: Int2ObjectOpenHashMap<IntArray>, into: IntOpenHashSet) {
        var statementId = statements.nextSetBit(0)
        while (statementId >= 0) {
            val write = writes.get(statementId)
            if (write != null && write[1] != BranchScope.NO_CONSTANT) into.add(write[0])
            statementId = statements.nextSetBit(statementId + 1)
        }
    }

    /**
     * The writes to tracked locals made by [statements], packed as
     * `[stmtOrdinal, localNodeId, constantNodeId | NO_CONSTANT]*` in ascending statement order.
     * The ordinal is the [ControlFlowIndex] id, which equals the statement's index in the
     * first-pass body traversal.
     */
    private fun definitionsFor(
        statements: BitSet,
        writes: Int2ObjectOpenHashMap<IntArray>,
        trackedLocals: IntOpenHashSet
    ): IntArray {
        if (trackedLocals.isEmpty()) return BranchScope.EMPTY_DEFINITIONS
        var packed: IntArrayBuilder? = null
        var statementId = statements.nextSetBit(0)
        while (statementId >= 0) {
            val write = writes.get(statementId)
            if (write != null && write[0] in trackedLocals) {
                val builder = packed ?: IntArrayBuilder(BranchScope.DEFINITION_STRIDE).also { packed = it }
                builder.add(statementId)
                builder.add(write[0])
                builder.add(write[1])
            }
            statementId = statements.nextSetBit(statementId + 1)
        }
        return packed?.toIntArray() ?: BranchScope.EMPTY_DEFINITIONS
    }

    /**
     * Record every write of each tracked local, so consumers can subtract killed branch-side
     * writes from a complete, persistence-safe list instead of from the local's ASSIGN edges
     * (persisted graphs collapse repeated arcs), and see any surviving non-constant write.
     */
    private fun recordLocalDefinitionTables(writes: Int2ObjectOpenHashMap<IntArray>, trackedLocals: IntOpenHashSet) {
        if (trackedLocals.isEmpty()) return
        val ordered = ArrayList<IntArray>()
        for (entry in writes.int2ObjectEntrySet()) {
            val write = entry.value
            if (write[0] in trackedLocals) ordered += intArrayOf(entry.intKey, write[0], write[1])
        }
        ordered.sortWith(compareBy({ it[1] }, { it[0] }))
        var start = 0
        while (start < ordered.size) {
            val localId = ordered[start][1]
            var end = start
            while (end < ordered.size && ordered[end][1] == localId) end++
            val packed = IntArray((end - start) * BranchScope.DEFINITION_STRIDE)
            for (position in start until end) {
                val base = (position - start) * BranchScope.DEFINITION_STRIDE
                val triple = ordered[position]
                packed[base] = triple[0]
                packed[base + 1] = triple[1]
                packed[base + 2] = triple[2]
            }
            graphBuilder.addLocalDefinitions(NodeId(localId), packed)
            start = end
        }
    }

    private fun comparisonOp(condition: Any): ComparisonOp? = when (condition) {
        is JEqExpr -> ComparisonOp.EQ
        is JNeExpr -> ComparisonOp.NE
        is JLtExpr -> ComparisonOp.LT
        is JGeExpr -> ComparisonOp.GE
        is JGtExpr -> ComparisonOp.GT
        is JLeExpr -> ComparisonOp.LE
        else -> null
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
    private fun branchStatements(
        startId: Int,
        otherStartId: Int,
        controlFlowIndex: ControlFlowIndex,
        reachableCache: MutableMap<Int, BitSet>
    ): BitSet {
        val startReachable = reachableCache.getOrPut(startId) {
            collectReachable(startId, controlFlowIndex)
        }
        val otherReachable = reachableCache.getOrPut(otherStartId) {
            collectReachable(otherStartId, controlFlowIndex)
        }

        val branchStatements = startReachable.clone() as BitSet
        branchStatements.andNot(otherReachable)
        branchStatements.set(startId)
        return branchStatements
    }

    /** Create ControlFlowEdges from the condition to the first node in each branch. */
    private fun addControlFlowEdges(
        conditionNodeId: NodeId,
        comparison: BranchComparison,
        trueIds: IntArray,
        falseIds: IntArray
    ) {
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
    }

    private fun nodeIdsFor(statements: BitSet, controlFlowIndex: ControlFlowIndex): IntArray {
        val nodeIds = IntArrayBuilder()
        var statementId = statements.nextSetBit(0)
        while (statementId >= 0) {
            stmtNodeIds[controlFlowIndex.statement(statementId)]?.forEach(nodeIds::add)
            statementId = statements.nextSetBit(statementId + 1)
        }
        return nodeIds.toIntArray()
    }

    /**
     * Collect all statements reachable from [start] via forward traversal.
     */
    private fun collectReachable(startId: Int, controlFlowIndex: ControlFlowIndex): BitSet {
        val reachable = BitSet()
        val queue = IntQueue()
        queue.add(startId)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (reachable.get(current)) continue
            reachable.set(current)
            for (successorId in controlFlowIndex.successors(current)) {
                if (!reachable.get(successorId)) {
                    queue.add(successorId)
                }
            }
        }

        return reachable
    }

    /**
     * Post-processing: resolve functional interface dispatch across method boundaries.
     *
     * Function values move between methods through parameters, return values, fields and
     * array elements, and a method may be processed before the one that creates the function
     * value it calls. Every such move was recorded as a flow between [DispatchSlot]s; this
     * propagates the dispatch targets along those flows to a fixpoint, then resolves every call
     * that was waiting on a slot, e.g. `callback.apply(x)` inside a method whose `callback`
     * parameter receives `Foo::transform`, or `this.fn.invoke(x)` whose field a constructor
     * assigned a Kotlin lambda.
     */
    private fun resolveFunctionalDispatch() {
        val propagation = SlotPropagation(slots.size, flowFrom, flowTo)
        flowFrom.clear()
        flowTo.clear()
        this.propagation = propagation
        for (id in slotTargets.indices) {
            if (slotTargets[id] != null) propagation.pending.set(id)
        }
        // Resolving a call connects its arguments and result to the implementation's parameter
        // and return slots, which may carry further function values: alternate until nothing
        // new is resolved
        do {
            propagateSlotTargets(propagation)
        } while (emitPendingDispatches())
        this.propagation = null
    }

    /**
     * Sweep the slots in flow order, pushing the targets of each pending one along its flows,
     * until a sweep leaves nothing pending behind it: a flow to a slot swept earlier (a cycle,
     * a flow found during the sweep) is picked up by the next sweep.
     */
    private fun propagateSlotTargets(propagation: SlotPropagation) {
        var again = true
        while (again) {
            again = sweepSlots(propagation)
        }
    }

    /** One sweep; true when a slot already passed changed and the next sweep is needed. */
    private fun sweepSlots(propagation: SlotPropagation): Boolean {
        var backward = false
        var index = 0
        // Slots numbered during the sweep (override flows, adapter sinks) are swept at its end
        while (index < slots.size) {
            val id = propagation.slotAt(index)
            if (propagation.pending.get(id)) {
                propagation.pending.clear(id)
                if (propagateSlot(id, propagation)) backward = true
            }
            index++
        }
        return backward
    }

    /**
     * Push [id]'s targets along its flows and adapters; true when that changed a slot the
     * current sweep has passed (or [id] itself, `fn = fn::apply`), which the next sweep handles.
     * A saturated slot (see [SATURATED_TARGETS]) passes nothing on.
     */
    private fun propagateSlot(id: Int, propagation: SlotPropagation): Boolean {
        val targets = slotTargets[id]?.takeIf { it.isNotEmpty() } ?: return false
        if (!propagation.expanded.get(id)) {
            propagation.expanded.set(id)
            overrideFlows(slots[id]).forEach { propagation.addFlow(id, slotId(it)) }
        }
        var backward = false
        fun deliver(next: Int, incoming: Set<DispatchTarget>) {
            if (addTargets(next, incoming)) {
                propagation.pending.set(next)
                if (propagation.position(next) <= propagation.position(id)) backward = true
            }
        }
        propagation.forEachFlow(id) { next -> deliver(next, targets) }
        slotAdapters.get(id)?.forEach { adapter ->
            val adapted = targets.mapNotNullTo(LinkedHashSet(), adapter::adapt)
            if (adapted.isNotEmpty()) deliver(slotId(adapter.sink), adapted)
        }
        return backward
    }

    /**
     * Resolve every waiting call against the targets its slot now holds, then register the
     * calls that resolution itself produced; true if anything changed.
     */
    private fun emitPendingDispatches(): Boolean {
        var emitted = false
        for (entry in slotCalls.int2ObjectEntrySet()) {
            val targets = slotTargets[entry.intKey] ?: continue
            for (pending in entry.value.toList()) {
                val fresh = targets.filter(pending.resolved::add)
                fresh.forEach { emitResolvedDispatch(pending, it) }
                emitted = emitted || fresh.isNotEmpty()
            }
        }
        val nested = nestedDispatches.toList()
        nestedDispatches.clear()
        nested.forEach { (slot, pending) -> callsOn(slotId(slot)).add(pending) }
        return emitted || nested.isNotEmpty()
    }

    private fun callsOn(id: Int): MutableList<PendingDispatch> =
        slotCalls.get(id) ?: mutableListOf<PendingDispatch>().also { slotCalls.put(id, it) }

    /** The number of [slot], assigned on first use; numbers index [slots] and [slotTargets]. */
    private fun slotId(slot: DispatchSlot): Int {
        val known = slotIds.getInt(slot)
        if (known >= 0) return known
        val id = slots.size
        slotIds.put(slot, id)
        slots.add(slot)
        slotTargets.add(null)
        return id
    }

    /**
     * Create the call site through which [callSite], a call on a function value, reaches
     * [target]'s implementation, with the receiver and arguments aligned to that
     * implementation's parameters. Does nothing when [target] does not implement the call.
     */
    private fun emitResolvedDispatch(pending: PendingDispatch, target: DispatchTarget) {
        val callSite = pending.callSite
        val result = pending.result
        val resolved = resolveDispatch(target, callSite.callee, callSite.receiver, callSite.arguments, pending.argumentSlots) ?: return
        val resolvedCallSite = CallSiteNode(
            id = nextNodeId("call"),
            caller = callSite.caller,
            callee = resolved.method,
            lineNumber = callSite.lineNumber,
            receiver = resolved.receiver,
            arguments = resolved.arguments
        )
        graphBuilder.addNode(resolvedCallSite)
        // Both the call on the function value and the resolved call are dynamic dispatch
        graphBuilder.addEdge(CallEdge(from = callSite.id, to = callSite.id, isVirtual = false, isDynamic = true))
        graphBuilder.addEdge(CallEdge(from = resolvedCallSite.id, to = resolvedCallSite.id, isVirtual = false, isDynamic = true))
        // Forward dataflow: arguments (and captured values) flow to the resolved target
        resolved.arguments.forEach { argNodeId ->
            graphBuilder.addEdge(DataFlowEdge(from = argNodeId, to = resolvedCallSite.id, kind = DataFlowKind.PARAMETER_PASS))
        }
        if (result != null) {
            graphBuilder.addEdge(DataFlowEdge(from = resolvedCallSite.id, to = result, kind = DataFlowKind.RETURN_VALUE))
        }
        // The call now has a callee: function values among its arguments reach the
        // implementation's parameters, and what the implementation returns reaches the result
        if (trackCrossMethodFunctionalDispatch) {
            resolved.argumentSlots.forEachIndexed { index, slot ->
                if (slot != null) flowSlotNow(slot, DispatchSlot.Parameter(resolved.method, index))
            }
            pending.resultSlot?.let { flowSlotNow(DispatchSlot.Return(resolved.method), it) }
        }
        // An unbound reference to a function value's own method (`Function::apply`) resolves
        // to a call whose receiver is itself a function value: that call dispatches in turn
        resolved.receiverSlot?.let { receiverSlot ->
            val nested = PendingDispatch(resolvedCallSite, result, resolved.argumentSlots, pending.resultSlot, mutableSetOf())
            val known = (receiverSlot as? DispatchSlot.Local)?.takeIf { it.method == callSite.caller }
                ?.let { dynamicTargets[localKey(it.method, it.name)] }.orEmpty()
            known.filter(nested.resolved::add).forEach { emitResolvedDispatch(nested, it) }
            if (trackCrossMethodFunctionalDispatch) {
                nestedDispatches += receiverSlot to nested
            }
        }
    }

    private fun resolveDispatch(
        target: DispatchTarget,
        invoked: MethodDescriptor,
        receiver: NodeId?,
        arguments: List<NodeId>,
        argumentSlots: List<DispatchSlot?>
    ): ResolvedDispatch? = when (target) {
        is DispatchTarget.Handle -> if (target.samName != invoked.name || !isImplementedByFunctionValue(invoked)) {
            null
        } else {
            // The function value's own receiver is not the implementation's: a static method or
            // constructor has none, and an instance method takes the first capture or argument.
            // Captured values already flowed to the implementation's parameters at creation.
            val all = target.captures + arguments
            val slots = List<DispatchSlot?>(target.captures.size) { null } + argumentSlots
            when (target.kind) {
                HandleKind.STATIC -> ResolvedDispatch(target.method, null, null, all, slots)
                HandleKind.INSTANCE ->
                    ResolvedDispatch(target.method, all.firstOrNull(), slots.firstOrNull(), all.drop(1), slots.drop(1))
            }
        }
        // The function object is the receiver, and it dispatched here: no further dispatch on it
        is DispatchTarget.FunctionObject -> findFunctionObjectMethod(target.className, invoked)
            ?.let { ResolvedDispatch(it, receiver, null, arguments, argumentSlots) }
        is DispatchTarget.Adapted -> if (target.samName != invoked.name || !isImplementedByFunctionValue(invoked)) {
            null
        } else {
            val all = target.captures + arguments
            val slots = List<DispatchSlot?>(target.captures.size) { null } + argumentSlots
            resolveDispatch(target.inner, target.invokedAs, all.firstOrNull(), all.drop(1), slots.drop(1))
        }
    }

    /**
     * The method of function-object class [className] (or an in-view superclass) that a call
     * to [invoked] runs: same name and erased parameter types, which the bridge method the
     * compiler emits for a generic interface always has. Kotlin property references implement
     * `invoke` in the stdlib by calling `get`, so `get` stands in for `invoke` there.
     */
    private fun findFunctionObjectMethod(className: String, invoked: MethodDescriptor): MethodDescriptor? {
        val start = resolveClassByName(className)
        val names = if (
            start != null && invoked.name == KOTLIN_INVOKE && superclassNames(start).any(::isKotlinPropertyReferenceClass)
        ) {
            setOf(KOTLIN_INVOKE, KOTLIN_PROPERTY_GET)
        } else {
            setOf(invoked.name)
        }
        val parameterTypes = invoked.parameterTypes.map { it.className }
        return generateSequence(start) { current ->
            current.superclass.orElse(null)?.let { resolveClassByName(it.fullyQualifiedName) }
        }.firstNotNullOfOrNull { current ->
            val candidates = methodsInSignatureOrder(current).filter {
                !it.isStatic && !it.isAbstract && it.name in names && it.parameterTypes.size == parameterTypes.size
            }
            // An exact override runs for any call; the erased bridge stands in only for an
            // abstract method with another erasure, never for a default method the class
            // does not override
            candidates.firstOrNull { method ->
                method.parameterTypes.map { toTypeDescriptor(it).className } == parameterTypes
            } ?: candidates.firstOrNull { MethodModifier.isBridge(it.modifiers) && isImplementedByFunctionValue(invoked) }
        }?.let(::toMethodDescriptor)
    }

    /**
     * [sootClass]'s methods ordered by signature. SootUp's method set has no stable iteration
     * order across JVM runs, and the order decides which node IDs the call sites get.
     */
    private fun methodsInSignatureOrder(sootClass: SootClass): List<SootMethod> =
        sortedMethodsByClass.getOrPut(sootClass.type.fullyQualifiedName) {
            (streamMethodsOrNull(sootClass)?.toList() ?: resolveMethodsOrEmpty(sootClass)).sortedBy { it.signature.toString() }
        }

    /**
     * Flows a call graph edge implies but no statement records: a function value passed to a
     * method's parameter reaches that parameter in every override (`invoker.invoke(fn)` calls
     * `Impl.invoke`), and one returned by an override is what a call on the overridden method
     * returns. Derived lazily, for slots that hold a target.
     */
    private fun overrideFlows(slot: DispatchSlot): List<DispatchSlot> = when (slot) {
        is DispatchSlot.Parameter -> overridesOf(slot.method).map { DispatchSlot.Parameter(it, slot.index) }
        is DispatchSlot.Return -> overriddenBy(slot.method).map { DispatchSlot.Return(it) }
        else -> emptyList()
    }

    /**
     * The concrete implementations of [method] in every subtype of its declaring class: the
     * one a subtype declares, or the one it inherits from a superclass that is not itself a
     * subtype of the declaring type (`class Child extends Base implements Invoker {}` runs
     * `Base.invoke` for `Invoker.invoke`).
     */
    private fun overridesOf(method: MethodDescriptor): List<MethodDescriptor> {
        if (!isOverridable(method)) return emptyList()
        val own = method.declaringClass.className
        return transitiveSubtypes(own).mapNotNull { subtype ->
            effectiveMethod(subtype, method)?.takeIf { it.declaringClassType.fullyQualifiedName != own }?.let(::toMethodDescriptor)
        }.distinct()
    }

    /**
     * The methods [method] implements or overrides: those declared by a supertype of its
     * declaring class, and those declared by a supertype of any subclass that inherits it
     * (`class FactoryChild extends FactoryBase implements Factory {}` makes `FactoryBase.make`
     * the implementation of `Factory.make`). A supertype outside the view (a JDK or Kotlin
     * stdlib interface) has no [SootMethod] to inspect, so its method is assumed to share
     * [method]'s sub-signature.
     */
    private fun overriddenBy(method: MethodDescriptor): List<MethodDescriptor> {
        val own = method.declaringClass.className
        val declared = declaredMethod(own, method)?.takeIf { isOverridable(method) } ?: return emptyList()
        val implementors = listOf(own) + transitiveSubtypes(own).filter { effectiveMethod(it, method) === declared }
        return implementors.flatMap(::supertypes).distinct().filter { it != own }.mapNotNull { supertype ->
            when (resolveClassByName(supertype)) {
                null -> toMethodDescriptor(MethodSignature(view.identifierFactory.getClassType(supertype), declared.subSignature))
                else -> declaredMethod(supertype, method)?.takeIf { !it.isStatic }?.let(::toMethodDescriptor)
            }
        }.distinct()
    }

    /** The concrete method a call to [descriptor] on an instance of [className] runs: declared by it or inherited. */
    private fun effectiveMethod(className: String, descriptor: MethodDescriptor): SootMethod? =
        generateSequence(className) { resolveClassByName(it)?.superclass?.orElse(null)?.fullyQualifiedName }
            .mapNotNull { declaredMethod(it, descriptor) }
            .firstOrNull()
            ?.takeIf { !it.isStatic && !it.isAbstract }

    private fun isOverridable(method: MethodDescriptor): Boolean {
        if (method.name == INIT_METHOD || method.declaringClass.className == JAVA_LANG_OBJECT) return false
        val declared = declaredMethod(method.declaringClass.className, method)
        return declared == null || (!declared.isStatic && !MethodModifier.isPrivate(declared.modifiers))
    }

    /**
     * The method [className] declares with [descriptor]'s erased sub-signature, if any. The
     * return type is part of it, so an overridden generic method finds the override's bridge,
     * whose body flows on to the typed implementation.
     */
    private fun declaredMethod(className: String, descriptor: MethodDescriptor): SootMethod? =
        declaredMethodIndexByClass.getOrPut(className) {
            resolveClassByName(className)?.let(::methodsInSignatureOrder).orEmpty().associateBy { method ->
                methodKey(
                    method.name,
                    method.parameterTypes.map { toTypeDescriptor(it).className },
                    toTypeDescriptor(method.returnType).className
                )
            }
        }[methodKey(descriptor.name, descriptor.parameterTypes.map { it.className }, descriptor.returnType.className)]

    private fun methodKey(name: String, parameterTypes: List<String>, returnType: String = ""): String =
        "$name(${parameterTypes.joinToString(",")})$returnType"

    /** Every class in the view that extends or implements [className], directly or not. */
    private fun transitiveSubtypes(className: String): List<String> = transitiveSubtypesByClass.getOrPut(className) {
        val visited = linkedSetOf<String>()
        val queue = ArrayDeque(directSubtypes[className].orEmpty())
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (visited.add(next)) queue.addAll(directSubtypes[next].orEmpty())
        }
        visited.toList()
    }

    /** Every superclass and interface of [className], directly or not, as far as the view resolves them. */
    private fun supertypes(className: String): List<String> = supertypesByClass.getOrPut(className) {
        val visited = linkedSetOf<String>()
        val queue = ArrayDeque(directSupertypes(className))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (visited.add(next)) queue.addAll(directSupertypes(next))
        }
        visited.toList()
    }

    private fun directSupertypes(className: String): List<String> {
        val sootClass = resolveClassByName(className) ?: return emptyList()
        return listOfNotNull(sootClass.superclass.orElse(null)?.fullyQualifiedName) +
            sootClass.interfaces.map { it.fullyQualifiedName }
    }

    /** Names of [sootClass]'s superclasses, as far as the view resolves them (plus the first one it does not). */
    private fun superclassNames(sootClass: SootClass): Sequence<String> =
        generateSequence(sootClass.superclass.orElse(null)?.fullyQualifiedName) { name ->
            resolveClassByName(name)?.superclass?.orElse(null)?.fullyQualifiedName
        }

    /**
     * Whether instances of [className] are function values whose class pins down the
     * implementation: a class without a source name, which only exists to be handed around as
     * a value (an anonymous class, `object :`, a Kotlin lambda, callable reference or SAM
     * wrapper class, or a synthetic class implementing an interface, which is how D8/R8
     * desugar lambdas, even after R8 renames them), or a class implementing a Kotlin function
     * type or extending a Kotlin function base class.
     */
    private fun isFunctionObjectClass(className: String): Boolean = functionObjectClasses.getOrPut(className) {
        val sootClass = resolveClassByName(className)
        sootClass != null && !sootClass.isInterface && !sootClass.isAbstract && (
            isAnonymousClassName(className) ||
                (ClassModifier.isSynthetic(sootClass.modifiers) && sootClass.interfaces.isNotEmpty()) ||
                sootClass.interfaces.any { isKotlinFunctionInterface(it.fullyQualifiedName) } ||
                superclassNames(sootClass).any(::isKotlinFunctionBaseClass)
            )
    }

    /**
     * A function object of class [className] was created (`new Foo$bar$1(...)`) or loaded from
     * its singleton (`Foo$bar$1.INSTANCE`) into [local]: calls on [local] dispatch to the class's
     * methods, and, as for an `invokedynamic`, the creating method gets a dynamic call site to
     * each method the class implements for a supertype, so the lambda body stays reachable when
     * the call that runs it happens in code outside the graph (`lazy {}`, `Executor.execute`,
     * ...). Methods the class adds on its own (`new Object() { void helper() {} }`) are only
     * reachable through calls the graph already records.
     */
    private fun trackFunctionObject(
        method: MethodDescriptor,
        local: Local,
        className: String,
        receiver: NodeId,
        stmt: Stmt
    ) {
        if (!isFunctionObjectClass(className)) return
        mergeLocalTargets(method, local, listOf(DispatchTarget.FunctionObject(className)))
        val sootClass = resolveClassByName(className) ?: return
        methodsInSignatureOrder(sootClass)
            .filter { !it.isStatic && !it.isAbstract && it.name != INIT_METHOD && !MethodModifier.isBridge(it.modifiers) }
            .filter { implementsSupertypeMethod(sootClass, it) }
            .forEach { body ->
                val callee = toMethodDescriptor(body)
                val callSite = CallSiteNode(
                    id = nextNodeId("call"),
                    caller = method,
                    callee = callee,
                    lineNumber = null,
                    receiver = receiver,
                    arguments = emptyList()
                )
                graphBuilder.addNode(callSite)
                recordStmtNode(stmt, callSite.id)
                graphBuilder.addEdge(CallEdge(from = callSite.id, to = callSite.id, isVirtual = false, isDynamic = true))
            }
    }

    /**
     * Whether [method] implements or overrides a method of one of [sootClass]'s supertypes, by
     * name and arity, so that the erased bridge is skipped but the typed implementation kept.
     * A supertype outside the view (`java.lang.Runnable`, `kotlin.jvm.functions.Function1`) is
     * inspected through the analysis JVM's own copy of it when it has one; only a supertype
     * that cannot be inspected at all lets any non-private method count.
     */
    private fun implementsSupertypeMethod(sootClass: SootClass, method: SootMethod): Boolean {
        if (MethodModifier.isPrivate(method.modifiers)) return false
        // The erased signatures under which this method can be called through a supertype:
        // its own, and that of a bridge the compiler emitted for it (`apply(Object)` for
        // `apply(String)`), which javac and kotlinc only generate for overriding methods
        val signatures = listOf(erasedSignature(method)) + bridgesInvoking(sootClass)[method.subSignature.toString()].orEmpty()
        return supertypes(sootClass.type.fullyQualifiedName).any { supertype ->
            supertypeContracts(supertype)?.any(signatures::contains) ?: true
        }
    }

    /**
     * For each method of [sootClass] that a bridge delegates to, keyed by sub-signature, the
     * erased signatures of those bridges. A bridge's body is a cast and one call to its
     * target, so the call names it; an overload sharing the name and arity (`apply(Integer)`
     * next to `apply(String)`) is not the bridge's target and gets nothing.
     */
    private fun bridgesInvoking(sootClass: SootClass): Map<String, List<String>> =
        bridgesInvokingByClass.getOrPut(sootClass.type.fullyQualifiedName) {
            methodsInSignatureOrder(sootClass)
                .filter { MethodModifier.isBridge(it.modifiers) && it.hasBody() }
                .flatMap { bridge ->
                    bridgeBody(bridge).controlFlowGraph.stmts.asSequence()
                        .mapNotNull { stmt ->
                            when (stmt) {
                                is JInvokeStmt -> stmt.invokeExpr.orElse(null)
                                is JAssignStmt -> stmt.rightOp as? AbstractInvokeExpr
                                else -> null
                            }?.methodSignature
                        }
                        .filter { it.declClassType == sootClass.type && it.name == bridge.name }
                        .map { it.subSignature.toString() to erasedSignature(bridge) }
                        .toList()
                }
                .groupBy({ it.first }, { it.second })
        }

    private fun erasedSignature(method: SootMethod): String =
        methodKey(method.name, method.parameterTypes.map { toTypeDescriptor(it).className })

    /**
     * The erased signatures a subclass of [className] can implement or override: instance
     * methods that are neither private, static nor final (`Function.identity()` is static, so
     * an instance `identity()` helper implements nothing). A class in the view is read from it;
     * one outside it (JDK, Kotlin stdlib) through the analysis JVM's own copy, loaded without
     * initialization, public methods inherited included. Null when neither has the class.
     */
    private fun supertypeContracts(className: String): Set<String>? = supertypeContractsByClass.getOrPut(className) {
        resolveClassByName(className)?.let { sootClass ->
            methodsInSignatureOrder(sootClass)
                .filter { !it.isStatic && !MethodModifier.isPrivate(it.modifiers) && !MethodModifier.isFinal(it.modifiers) }
                .map(::erasedSignature)
                .toSet()
        } ?: runCatching { Class.forName(className, false, SootUpAdapter::class.java.classLoader) }.getOrNull()?.let { clazz ->
            (clazz.methods.asSequence() + clazz.declaredMethods.asSequence())
                .filter { isOverridable(it.modifiers) }
                .map { methodKey(it.name, it.parameterTypes.map { type -> type.typeName }) }
                .toSet()
        }
    }

    private fun isOverridable(modifiers: Int): Boolean =
        !Modifier.isStatic(modifiers) && !Modifier.isPrivate(modifiers) && !Modifier.isFinal(modifiers)

    /**
     * Whether [invoked] is a method a function value implements, so that a call to it runs the
     * implementation: an abstract method of its declaring type. A default method with the same
     * name (`Extra.apply(Integer)` next to `Function.apply(Object)`, `Function.andThen`) runs
     * its own body. A method that neither the view nor the analysis JVM can inspect is assumed
     * abstract.
     */
    private fun isImplementedByFunctionValue(invoked: MethodDescriptor): Boolean = implementedByFunctionValue.getOrPut(invoked) {
        val className = invoked.declaringClass.className
        resolveClassByName(className)?.let { declaredMethod(className, invoked)?.isAbstract ?: true }
            ?: runCatching { Class.forName(className, false, SootUpAdapter::class.java.classLoader) }.getOrNull()?.let { clazz ->
                val signature = methodKey(invoked.name, invoked.parameterTypes.map { it.className })
                (clazz.methods.asSequence() + clazz.declaredMethods.asSequence())
                    .firstOrNull { methodKey(it.name, it.parameterTypes.map { type -> type.typeName }) == signature }
                    ?.let { Modifier.isAbstract(it.modifiers) } ?: true
            } ?: true
    }

    /**
     * A Kotlin bound callable reference class (`fn::invoke`, `obj::method`) hands its receiver
     * to the stdlib base constructor (`FunctionReferenceImpl(arity, receiver, owner, name,
     * signature, flags)`), which stores it in `CallableReference.receiver`; the reference's
     * `invoke` reads it back through the subclass's own field reference. The stdlib is outside
     * the view, so the store is recorded here: every argument that may hold a function value
     * flows to the subclass's `receiver` field.
     */
    private fun trackCallableReferenceReceiver(caller: MethodDescriptor, superInit: MethodDescriptor, args: List<Value>) {
        if (!trackCrossMethodFunctionalDispatch || !isKotlinCallableReferenceBaseClass(superInit.declaringClass.className)) return
        val receiverField = view.identifierFactory.getFieldSignature(
            CALLABLE_REFERENCE_RECEIVER_FIELD,
            view.identifierFactory.getClassType(caller.declaringClass.className),
            JAVA_LANG_OBJECT
        ).toString()
        args.filterIsInstance<Local>().forEach { trackFlow(caller, it, DispatchSlot.Field(receiverField)) }
    }

    private fun handleKind(handle: MethodHandle): HandleKind = when (handle.kind) {
        MethodHandle.Kind.REF_INVOKE_STATIC, MethodHandle.Kind.REF_INVOKE_CONSTRUCTOR -> HandleKind.STATIC
        else -> HandleKind.INSTANCE
    }

    private fun mergeLocalTargets(method: MethodDescriptor, local: Local, targets: Collection<DispatchTarget>) {
        val key = localKey(method, local.name)
        dynamicTargets[key] = mergeTargets(dynamicTargets[key], targets)
    }

    /** `target = source` (or a cast of it): [target] holds whatever function value [source] holds. */
    private fun copyFunctionValue(method: MethodDescriptor, source: Local, target: Local) {
        val sourceKey = localKey(method, source.name)
        val targetKey = localKey(method, target.name)
        dynamicTargets[sourceKey]?.let { mergeLocalTargets(method, target, it) }
        arrayDynamicTargets[sourceKey]?.let { arrayDynamicTargets[targetKey] = mergeTargets(arrayDynamicTargets[targetKey], it) }
        if (trackCrossMethodFunctionalDispatch) {
            localToParamIndex[sourceKey]?.let { localToParamIndex[targetKey] = it }
            trackSlotFlow(slotOf(method, source), method, target)
        }
    }

    /** The slot the value of [local] lives in across methods: its parameter, or the local itself. */
    private fun slotOf(method: MethodDescriptor, local: Local): DispatchSlot {
        val parameter = localToParamIndex[localKey(method, local.name)]
        return if (parameter != null) {
            DispatchSlot.Parameter(parameter.method, parameter.index)
        } else {
            DispatchSlot.Local(method, local.name)
        }
    }

    /**
     * The function value in [local] flows to [sink]: its targets known in this method seed
     * [sink] now, and targets that reach [local]'s slot later follow it there.
     */
    private fun trackFlow(method: MethodDescriptor, local: Local, sink: DispatchSlot) {
        if (!trackCrossMethodFunctionalDispatch) return
        val key = localKey(method, local.name)
        (dynamicTargets[key] ?: arrayDynamicTargets[key])?.let { seedSlot(sink, it) }
        if (mayHoldFunction(toTypeDescriptor(local.type))) {
            flowSlot(slotOf(method, local), sink)
        }
    }

    /** Whatever function value reaches [source] also reaches [target]. */
    private fun trackSlotFlow(source: DispatchSlot, method: MethodDescriptor, target: Local) {
        if (trackCrossMethodFunctionalDispatch && mayHoldFunction(toTypeDescriptor(target.type))) {
            flowSlot(source, DispatchSlot.Local(method, target.name))
        }
    }

    private fun seedSlot(slot: DispatchSlot, targets: Collection<DispatchTarget>) {
        if (targets.isNotEmpty()) {
            addTargets(slotId(slot), targets as? Set<DispatchTarget> ?: LinkedHashSet(targets))
        }
    }

    /**
     * Slot [id] holds [incoming] as well; true when that added a target. A slot with nothing
     * of its own takes [incoming] itself, and so does one whose targets [incoming] already
     * includes, so a slot that fans out to many others (an interface method's parameter
     * reaching the same parameter of every implementation, a result local reached by every
     * call on the interface) shares one set with them rather than filling a copy per slot;
     * only a slot where distinct sets meet gets a set of its own. Sets are never mutated.
     * A slot past [MAX_TARGETS] saturates, see [SATURATED_TARGETS], and nothing changes it after.
     */
    private fun addTargets(id: Int, incoming: Set<DispatchTarget>): Boolean {
        val current = slotTargets[id]
        val merged = mergeTargets(current, incoming)
        if (merged === current) return false
        slotTargets[id] = merged
        return true
    }

    private fun flowSlot(from: DispatchSlot, to: DispatchSlot) {
        if (from == to) return
        val fromId = slotId(from)
        val toId = slotId(to)
        val propagation = propagation
        if (propagation == null) {
            flowFrom.add(fromId)
            flowTo.add(toId)
        } else {
            propagation.addFlow(fromId, toId)
        }
    }

    /** [flowSlot], and have the fixpoint revisit [from] so targets it already holds follow the new flow. */
    private fun flowSlotNow(from: DispatchSlot, to: DispatchSlot) {
        flowSlot(from, to)
        propagation?.pending?.set(slotId(from))
    }

    /** The slot [local]'s function value lives in, or null when its type cannot hold one. */
    private fun functionSlotOf(method: MethodDescriptor, local: Local): DispatchSlot? =
        if (trackCrossMethodFunctionalDispatch && mayHoldFunction(toTypeDescriptor(local.type))) slotOf(method, local) else null

    /**
     * Whether a value of [type] can hold a function value, which bounds what cross-method
     * dispatch tracking records: an interface, an abstract class, `Object` (erased generics), a
     * function-object class, an array of those, or a type outside the view (the JDK, the Kotlin
     * stdlib), but not a primitive, a common concrete JDK type or a concrete class in the view.
     */
    private fun mayHoldFunction(type: TypeDescriptor): Boolean = mayHoldFunctionByType.getOrPut(type.className) {
        val className = type.className.substringBefore('[')
        if (isNonFunctionType(className)) return@getOrPut false
        val sootClass = resolveClassByName(className) ?: return@getOrPut true
        sootClass.isInterface || sootClass.isAbstract || isFunctionObjectClass(className)
    }

    private fun processCallGraph() {
        try {
            buildCallGraph()
            // Call graph edges are already processed via call site nodes
            // This method could be extended to add additional interprocedural edges
        } catch (e: Exception) {
            // Call graph construction may fail for incomplete classpaths
            // Continue without call graph
        } finally {
            // The algorithm resolves bodies through the methods the view holds, which memoize
            // them; their conversion scratch state is released here, as the adapter's own
            // detached copies release theirs after each method.
            view.classes.forEach { sootClass ->
                if (sootClass is JavaSootClass) bytecodeMethods(sootClass)?.forEach(::releaseConversionState)
            }
        }
    }

    private fun bridgeBody(bridge: SootMethod): Body = try {
        bridge.body
    } finally {
        releaseConversionState(bridge)
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
        val methods = bytecodeMethods(sootClass) ?: return null
        return sequence {
            for (method in methods) {
                try {
                    yield(detached(method))
                } catch (oom: OutOfMemoryError) {
                    log { "Skipping method ${method.signature}: OOM during streaming resolution" }
                    System.gc()
                } catch (e: Exception) {
                    log { "Skipping method ${method.signature}: ${e.message}" }
                } finally {
                    releaseConversionState(method.bodySource)
                }
            }
        }
    }

    /**
     * Drop the per-instruction scratch maps SootUp 3's `AsmMethodSource` keeps after it resolved
     * a body (`insnIndexCache`, one entry per bytecode instruction, and
     * `localVarTypeAnnotationIndex`). The source stays reachable through the view's class for
     * the whole build, and on the Android SDK those maps alone exceed the 4 GB test heap; both
     * are rebuilt on demand, so a later resolution of the same body is unaffected. A source
     * without the fields (another frontend, a future SootUp) is left alone.
     */
    private fun releaseConversionState(method: SootMethod) {
        if (method is JavaSootMethod) releaseConversionState(method.bodySource)
    }

    private fun releaseConversionState(bodySource: Any) {
        for (field in conversionScratchFields(bodySource.javaClass)) {
            try {
                field.set(bodySource, null)
            } catch (_: ReflectiveOperationException) {
                // Not an AsmMethodSource of this SootUp: nothing to release.
            }
        }
    }

    /**
     * The scratch fields of a body source class, looked up once: `getDeclaredField` per method
     * and per field was a reflective lookup for every body of the corpus, twice over.
     */
    private val conversionScratchFieldsByClass = HashMap<Class<*>, List<java.lang.reflect.Field>>()

    private fun conversionScratchFields(type: Class<*>): List<java.lang.reflect.Field> =
        conversionScratchFieldsByClass.getOrPut(type) {
            CONVERSION_SCRATCH_FIELDS.mapNotNull { name ->
                try {
                    type.getDeclaredField(name).apply { isAccessible = true }
                } catch (_: ReflectiveOperationException) {
                    null
                }
            }
        }

    /**
     * The methods of [sootClass] as the bytecode frontend read them. SootUp 3 converts a class
     * file into an `OverridingJavaClassSource` that already holds one [JavaSootMethod] per
     * method, each with its ASM `MethodNode` as body source, and releases the `ClassNode`. The
     * frontend collects them into a hash set keyed by identity, so they are sorted by signature
     * here for a deterministic walk; `null` for a class that did not come from bytecode.
     */
    private fun bytecodeMethods(sootClass: JavaSootClass): List<JavaSootMethod>? =
        bytecodeMethodsCache.getOrPut(sootClass) { resolveBytecodeMethods(sootClass) }

    /**
     * [bytecodeMethods] per class while the class is being processed: `resolveMethods()`
     * converts every method's descriptor into a signature on each call, and a class is asked
     * for its methods more than once in a pass (its graph, its declared sub-signatures). The
     * entry is dropped once the class's pass is over, so the cache holds one class at a time.
     */
    private val bytecodeMethodsCache = IdentityHashMap<JavaSootClass, List<JavaSootMethod>?>()

    private fun resolveBytecodeMethods(sootClass: JavaSootClass): List<JavaSootMethod>? {
        if (!sootClass.classSource.isBytecodeClassSource()) return null
        return try {
            // The class's own, memoised methods: a source's `resolveMethods()` converts every
            // descriptor again on each call. Sorted by the bytecode's own name and descriptor,
            // which the method node already holds: rendering a signature per method was a fifth
            // of this pass.
            sootClass.methods.filterIsInstance<JavaSootMethod>()
                .sortedWith(compareBy({ (it.bodySource as? MethodNode)?.name ?: it.name }, { (it.bodySource as? MethodNode)?.desc ?: it.signature.toString() }))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * A copy of [method] with a body cache of its own. SootUp 3 memoizes a method's body on the
     * [JavaSootMethod] the view holds; resolving the graph through that object would keep every
     * body of the corpus alive for the view's lifetime, which is what ran the Android SDK out
     * of heap. The copy is dropped once its graph nodes exist.
     */
    private fun detached(method: JavaSootMethod): JavaSootMethod = JavaSootMethod(
        method.bodySource,
        method.signature,
        method.modifiers,
        method.exceptionSignatures,
        method.annotations,
        method.position
    )

    private fun getAsmMethodNodes(sootClass: JavaSootClass): List<MethodNode>? =
        bytecodeMethods(sootClass)?.mapNotNull { it.bodySource as? MethodNode }

    private fun loadMethodNodesFromResource(sootClass: JavaSootClass): List<MethodNode>? {
        return try {
            val resourcePath = sootClass.type.fullyQualifiedName.replace('.', '/') + CLASS_FILE_SUFFIX
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

    private fun resolveMethodsOrEmpty(sootClass: SootClass): Set<SootMethod> = try {
        sootClass.methods
    } catch (failure: Throwable) {
        when (failure) {
            is OutOfMemoryError -> {
                log { "Skipping methods for ${sootClass.type}: OOM during method resolution" }
                System.gc()
            }
            is IllegalStateException -> log { "Skipping methods for ${sootClass.type}: ${failure.message}" }
            else -> throw failure
        }
        emptySet()
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
                getOrCreateLocal(value.base, method)
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
                    id = nextNodeId(CONST_NODE_PREFIX),
                    value = constant.value
                )
                is SootLongConstant -> LongConstant(
                    id = nextNodeId(CONST_NODE_PREFIX),
                    value = constant.value
                )
                is SootFloatConstant -> FloatConstant(
                    id = nextNodeId(CONST_NODE_PREFIX),
                    value = constant.value
                )
                is SootDoubleConstant -> DoubleConstant(
                    id = nextNodeId(CONST_NODE_PREFIX),
                    value = constant.value
                )
                // Note: JVM bytecode represents boolean true/false as int constants (1/0),
                // so SootUp produces IntConstant, not BooleanConstant, for boolean values.
                is SootStringConstant -> StringConstant(
                    id = nextNodeId(CONST_NODE_PREFIX),
                    value = constant.value
                )
                is SootNullConstant -> NullConstant(
                    id = nextNodeId(CONST_NODE_PREFIX)
                )
                else -> {
                    log { "Unsupported constant type: ${constant.javaClass.simpleName} = $constant" }
                    IntConstant(
                        id = nextNodeId(CONST_NODE_PREFIX),
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

    private fun indexSootClassOrigins(classes: Iterable<SootClass>): List<IndexedClass> {
        if (inputLocationSources.isEmpty()) return emptyList()
        val indexedClasses = mutableListOf<IndexedClass>()
        classes.forEach { sootClass ->
            if (sootClass is JavaSootClass) {
                val source = sourceForClass(sootClass) ?: return@forEach
                val className = sootClass.type.fullyQualifiedName
                if (className.endsWith(".package-info") || className.endsWith(".module-info")) {
                    return@forEach
                }
                classOriginsByName.putIfAbsent(className, source)
                classOriginSourceCounts[source] = (classOriginSourceCounts[source] ?: 0) + 1
                indexedClasses += IndexedClass(sootClass, source)
            }
        }
        return indexedClasses
    }

    private fun sourceForClass(sootClass: JavaSootClass): String? =
        inputLocationSources[sootClass.classSource.analysisInputLocation]

    private fun indexResourceValues(loadedClassSources: Set<String>): List<ResourceEntry> {
        val classEntries = mutableListOf<ResourceEntry>()
        resourceAccessor.list("**").forEach { entry ->
            if (entry.path.endsWith(CLASS_FILE_SUFFIX, ignoreCase = true)) {
                if (entry.source in loadedClassSources) {
                    return@forEach
                }
                classEntries += entry
                classResourcePathToName(entry.path)?.let { className ->
                    classOriginsByName.putIfAbsent(className, entry.source)
                    classOriginSourceCounts[entry.source] = (classOriginSourceCounts[entry.source] ?: 0) + 1
                }
                return@forEach
            }
            val format = resourceFormat(entry.path)
            val profile = resourceProfile(entry.path)
            val fileNode = ResourceFileNode(
                id = nextNodeId(RESOURCE_SOURCE),
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
        return classEntries
    }

    private fun indexArtifactDependency(sootClass: JavaSootClass, source: String) {
        val fromArtifact = artifactKey(source) ?: return
        val referencedClasses = runCatching {
            extractReferencedClasses(Files.readAllBytes(sootClass.classSource.sourcePath))
        }.getOrElse {
            log { "Failed to extract artifact dependencies from $source!/${sootClass.type.fullyQualifiedName}: ${it.message}" }
            return
        }
        recordArtifactDependencies(fromArtifact, referencedClasses)
    }

    private fun indexArtifactDependency(entry: ResourceEntry) {
        val fromArtifact = artifactKey(entry.source) ?: return
        val referencedClasses = runCatching {
            resourceAccessor.open(entry.path).use { input ->
                extractReferencedClasses(input.readBytes())
            }
        }.getOrElse {
            log { "Failed to extract artifact dependencies from ${entry.source}!/${entry.path}: ${it.message}" }
            return
        }
        recordArtifactDependencies(fromArtifact, referencedClasses)
    }

    private fun recordArtifactDependencies(fromArtifact: String, referencedClasses: Set<String>) {
        referencedClasses.forEach { referencedClass ->
            val targetArtifact = classOriginsByName[referencedClass]?.let(::artifactKey) ?: return@forEach
            if (targetArtifact == fromArtifact) return@forEach
            artifactDependenciesByArtifact
                .getOrPut(fromArtifact) { mutableMapOf() }
                .merge(targetArtifact, 1, Int::plus)
        }
    }

    private fun extractReferencedClasses(bytecode: ByteArray): Set<String> {
        val references = linkedSetOf<String>()
        fun addType(type: AsmType?) {
            when (type?.sort) {
                AsmType.ARRAY -> addType(type.elementType)
                AsmType.OBJECT -> references += type.className
                AsmType.METHOD -> {
                    addType(type.returnType)
                    type.argumentTypes.forEach(::addType)
                }
            }
        }

        ClassReader(bytecode).accept(object : ClassVisitor(ASM_API_VERSION) {
            override fun visit(
                version: Int,
                access: Int,
                name: String?,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?
            ) {
                superName?.replace('/', '.')?.let { references += it }
                interfaces.orEmpty().forEach { references += it.replace('/', '.') }
            }

            override fun visitField(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                value: Any?
            ): FieldVisitor {
                addType(descriptor?.let(AsmType::getType))
                if (value is AsmType) addType(value)
                return object : FieldVisitor(ASM_API_VERSION) {}
            }

            override fun visitMethod(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor {
                addType(descriptor?.let(AsmType::getMethodType))
                exceptions.orEmpty().forEach { references += it.replace('/', '.') }
                return object : MethodVisitor(ASM_API_VERSION) {
                    override fun visitTypeInsn(opcode: Int, type: String) {
                        references += type.replace('/', '.')
                    }

                    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                        references += owner.replace('/', '.')
                        addType(AsmType.getType(descriptor))
                    }

                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        references += owner.replace('/', '.')
                        addType(AsmType.getMethodType(descriptor))
                    }

                    override fun visitLdcInsn(value: Any) {
                        if (value is AsmType) addType(value)
                    }

                    override fun visitInvokeDynamicInsn(
                        name: String,
                        descriptor: String,
                        bootstrapMethodHandle: Handle,
                        vararg bootstrapMethodArguments: Any
                    ) {
                        addType(AsmType.getMethodType(descriptor))
                        references += bootstrapMethodHandle.owner.replace('/', '.')
                        bootstrapMethodArguments.forEach { arg ->
                            when (arg) {
                                is AsmType -> addType(arg)
                                is Handle -> references += arg.owner.replace('/', '.')
                            }
                        }
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)

        return references
    }

    private fun artifactKey(origin: String): String? =
        origin.trim().trimEnd('/').substringAfterLast('/').removeSuffix(".jar").takeIf { it.isNotBlank() }

    private fun classResourcePathToName(path: String): String? {
        if (!path.endsWith(CLASS_FILE_SUFFIX, ignoreCase = true)) return null
        val className = path.removeSuffix(CLASS_FILE_SUFFIX).replace('/', '.')
        return if (className.endsWith(".package-info") || className.endsWith(".module-info")) null else className
    }

    private fun indexClassBundles(classes: Iterable<SootClass>) {
        classes.forEach { sootClass ->
            if (!isListResourceBundleClass(sootClass)) return@forEach
            indexListResourceBundle(sootClass)
        }
    }

    private fun indexListResourceBundle(sootClass: SootClass) {
        val path = sootClass.type.fullyQualifiedName
        if (resourceFilesByPath.containsKey(path)) return
        val fileNode = ResourceFileNode(
            id = nextNodeId(RESOURCE_SOURCE),
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
        return classIndex()[className]
    }

    private fun classIndex(): Map<String, SootClass> {
        classesByNameCache?.let { return it }
        return view.classes.toList()
            .associateBy { it.type.fullyQualifiedName }
            .also { classesByNameCache = it }
    }

    private fun declaresMethod(classType: ClassType, subSignature: MethodSubSignature): Boolean {
        val className = classType.fullyQualifiedName
        val declaredSubSignatures = declaredMethodSubSignaturesByClass[className]
            ?: collectDeclaredMethodSubSignatures(className).also {
                declaredMethodSubSignaturesByClass[className] = it
            }
        return subSignature.toString() in declaredSubSignatures
    }

    private fun collectDeclaredMethodSubSignatures(className: String): Set<String> {
        val sootClass = resolveClassByName(className) ?: return emptySet()
        val asmSubSignatures = if (sootClass is JavaSootClass) {
            bytecodeMethods(sootClass)?.mapTo(HashSet()) { it.signature.subSignature.toString() }
        } else {
            null
        }
        return asmSubSignatures ?: resolveMethodsOrEmpty(sootClass)
            .mapTo(HashSet()) { it.signature.subSignature.toString() }
    }

    private fun extractControlFormatsFromMethod(methodNode: MethodNode): Set<String>? =
        when (val value = evaluateLiteralMethod(methodNode)) {
            is List<*> -> value.mapNotNull {
                when (it) {
                    RESOURCE_BUNDLE_FORMAT_CLASS, RESOURCE_BUNDLE_FORMAT_PROPERTIES -> it as String
                    else -> null
                }
            }.toSet().takeIf { it.isNotEmpty() }
            RESOURCE_BUNDLE_FORMAT_CLASS -> setOf(RESOURCE_BUNDLE_FORMAT_CLASS)
            RESOURCE_BUNDLE_FORMAT_PROPERTIES -> setOf(RESOURCE_BUNDLE_FORMAT_PROPERTIES)
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
                LOCALE_CLASS -> "arg-locale"
                "java.lang.String" -> "arg-string"
                else -> null
            }
            localIndex += argType.size
        }
        for (insn in methodNode.instructions) {
            when (insn) {
                is InsnNode -> when {
                    insn.opcode == Opcodes.ACONST_NULL -> stack.addLast(null)
                    insn.opcode in Opcodes.ICONST_M1..Opcodes.ICONST_5 -> stack.addLast(insn.opcode - Opcodes.ICONST_0)
                    insn.opcode == Opcodes.DUP -> stack.lastOrNull()?.let(stack::addLast)
                    insn.opcode == Opcodes.ARETURN -> return stack.removeLastOrNull()
                }
                is IntInsnNode -> if (insn.opcode == Opcodes.BIPUSH || insn.opcode == Opcodes.SIPUSH) {
                    stack.addLast(insn.operand)
                }
                is LdcInsnNode -> stack.addLast(insn.cst)
                is VarInsnNode -> when {
                    insn.opcode == Opcodes.ASTORE || insn.opcode == Opcodes.ISTORE -> locals[insn.`var`] = stack.removeLastOrNull()
                    insn.opcode == Opcodes.ALOAD || insn.opcode == Opcodes.ILOAD -> stack.addLast(locals[insn.`var`])
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
                        insn.opcode == Opcodes.INVOKESTATIC && owner == "java.util.Arrays" && insn.name == "asList" ->
                            (args.singleOrNull() as? List<*>) ?: args
                        insn.opcode == Opcodes.INVOKESTATIC && owner == "java.util.Collections" && insn.name == "singletonList" -> args
                        insn.opcode == Opcodes.INVOKESTATIC && owner == LOCALE_CLASS && insn.name == "forLanguageTag" ->
                            (args.firstOrNull() as? String)?.let(::normalizeLocaleSpec)
                        insn.opcode == Opcodes.INVOKESPECIAL && owner == LOCALE_CLASS && insn.name == INIT_METHOD -> null
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
        owner == LOCALE_CLASS -> extractLocaleSpec(fieldName)
        fieldName == "FORMAT_CLASS" -> listOf(RESOURCE_BUNDLE_FORMAT_CLASS)
        fieldName == "FORMAT_PROPERTIES" -> listOf(RESOURCE_BUNDLE_FORMAT_PROPERTIES)
        fieldName == "FORMAT_DEFAULT" -> listOf(RESOURCE_BUNDLE_FORMAT_CLASS, RESOURCE_BUNDLE_FORMAT_PROPERTIES)
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
        if (methodName !in RESOURCE_LOOKUP_METHODS || declaringClass !in RESOURCE_LOOKUP_CLASSES || invokeExpr.args.isEmpty()) {
            return null
        }
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
        val supported = (declaringClass == "java.lang.ClassLoader" && methodName in RESOURCE_PATH_METHODS) ||
            (declaringClass == "java.lang.Class" && methodName in RESOURCE_PATH_METHODS)
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
        if (declaringClass != RESOURCE_BUNDLE_CLASS || methodName != GET_BUNDLE_METHOD || invokeExpr.args.isEmpty()) {
            return null
        }
        val firstArg = invokeExpr.args[0] as? SootStringConstant ?: return null
        val baseName = firstArg.value
        val basePath = baseName.replace('.', '/')
        val localeArg = calleeSignature.parameterTypes
            .indexOfFirst { it.toString() == LOCALE_CLASS }
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
        if (declaringClass != RESOURCE_BUNDLE_CLASS || methodName !in RESOURCE_BUNDLE_READ_METHODS) return
        val receiverLocal = (invokeExpr as? AbstractInstanceInvokeExpr)?.base as? Local ?: return
        val bundlePaths = resourceBundlePaths[localKey(caller, receiverLocal.name)] ?: return
        if (methodName == GET_KEYS_METHOD) {
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
        if (calleeSignature.declClassType.fullyQualifiedName == LOCALE_CLASS && calleeSignature.name == INIT_METHOD) {
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
        calleeSignature.declClassType.fullyQualifiedName == RESOURCE_BUNDLE_CLASS &&
            calleeSignature.name == GET_BUNDLE_METHOD

    private fun isResourceEnumerationCall(calleeSignature: MethodSignature): Boolean {
        val declaringClass = calleeSignature.declClassType.fullyQualifiedName
        return (declaringClass == RESOURCE_BUNDLE_CLASS || declaringClass == PROPERTY_RESOURCE_BUNDLE_CLASS) &&
            calleeSignature.name == GET_KEYS_METHOD
    }

    private fun isPropertiesLoadCall(calleeSignature: MethodSignature): Boolean =
        calleeSignature.declClassType.fullyQualifiedName == PROPERTIES_CLASS &&
            calleeSignature.name in PROPERTIES_LOAD_METHODS

    private fun isPropertyResourceBundleConstructor(calleeSignature: MethodSignature): Boolean =
        calleeSignature.declClassType.fullyQualifiedName == PROPERTY_RESOURCE_BUNDLE_CLASS &&
            calleeSignature.name == INIT_METHOD

    private fun isReaderBridgeConstructor(calleeSignature: MethodSignature): Boolean {
        val declaringClass = calleeSignature.declClassType.fullyQualifiedName
        return calleeSignature.name == INIT_METHOD && declaringClass in READER_BRIDGE_CLASSES
    }

    private fun isReaderFactoryCall(calleeSignature: MethodSignature): Boolean {
        val declaringClass = calleeSignature.declClassType.fullyQualifiedName
        val methodName = calleeSignature.name
        return (declaringClass == URL_CLASS && methodName == OPEN_STREAM_METHOD) ||
            (declaringClass == CHANNELS_CLASS && methodName == NEW_READER_METHOD)
    }

    private fun isStructuredResourceLoadCall(calleeSignature: MethodSignature): Boolean {
        val declaringClass = calleeSignature.declClassType.fullyQualifiedName
        val methodName = calleeSignature.name
        return isPropertiesLoadCall(calleeSignature) ||
            isPropertyResourceBundleConstructor(calleeSignature) ||
            (declaringClass == GSON_CLASS && methodName == FROM_JSON_METHOD) ||
            (declaringClass == DOCUMENT_BUILDER_CLASS && methodName == PARSE_METHOD)
    }

    private fun collectMatchingResourceBundlePaths(
        baseName: String,
        basePath: String,
        controlSpec: BundleControlSpec?
    ): LinkedHashSet<String> {
        val candidates = resourceFilesByPath.keys
            .filter {
                it == defaultBundlePropertiesPath(basePath) ||
                    (it.startsWith("${basePath}_") && it.endsWith(PROPERTIES_FILE_SUFFIX)) ||
                    matchesBundleClassPath(it, baseName)
            }
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
                candidates.add(defaultBundlePropertiesPath(basePath))
                candidates.add(baseName)
            } else {
                candidates.add("${basePath}_$candidateLocale$PROPERTIES_FILE_SUFFIX")
                candidates.add("${baseName}_${candidateLocale}")
            }
        }
        return LinkedHashSet(
            candidates.filter {
                (it in resourceFilesByPath || it == defaultBundlePropertiesPath(basePath) || it == baseName) &&
                    controlAllowsPath(it, controlSpec)
            }
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
            listOf(defaultBundlePropertiesPath(basePath), baseName)
                .filter { controlAllowsPath(it, controlSpec) }
        )

    private fun defaultBundlePropertiesPath(basePath: String): String = "$basePath$PROPERTIES_FILE_SUFFIX"

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
            INIT_METHOD -> LocaleBuilderSpec()
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
                        "FORMAT_PROPERTIES" -> RESOURCE_BUNDLE_FORMAT_PROPERTIES
                        "FORMAT_CLASS" -> RESOURCE_BUNDLE_FORMAT_CLASS
                        "FORMAT_DEFAULT" -> null
                        else -> null
                    }
                }
            }
            else -> null
        }
    }

    private fun controlFormats(raw: String?): Set<String> = when (raw) {
        RESOURCE_BUNDLE_FORMAT_CLASS -> setOf(RESOURCE_BUNDLE_FORMAT_CLASS)
        RESOURCE_BUNDLE_FORMAT_PROPERTIES -> setOf(RESOURCE_BUNDLE_FORMAT_PROPERTIES)
        else -> setOf(RESOURCE_BUNDLE_FORMAT_PROPERTIES, RESOURCE_BUNDLE_FORMAT_CLASS)
    }

    private fun resolveBundleControlSpec(className: String): BundleControlSpec? =
        bundleControlSpecsByClass.getOrPut(className) {
            val sootClass = resolveClassByName(className) ?: return@getOrPut null
            if (!isResourceBundleControlClass(sootClass)) return@getOrPut null
            val javaSootClass = sootClass as? JavaSootClass ?: return@getOrPut null
            val methods = getAsmMethodNodes(javaSootClass) ?: loadMethodNodesFromResource(javaSootClass) ?: return@getOrPut null
            val formats = methods.firstOrNull { it.name == "getFormats" }
                ?.let(::extractControlFormatsFromMethod)
                ?: setOf(RESOURCE_BUNDLE_FORMAT_PROPERTIES, RESOURCE_BUNDLE_FORMAT_CLASS)
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
        val isProperties = path.endsWith(PROPERTIES_FILE_SUFFIX)
        val isClassBundle = !isProperties
        return (isProperties && RESOURCE_BUNDLE_FORMAT_PROPERTIES in controlSpec.formats) ||
            (isClassBundle && RESOURCE_BUNDLE_FORMAT_CLASS in controlSpec.formats)
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
            log { "Skipping runtime bundle resolution for $baseName: ${it.message}" }
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
            id = nextNodeId(RESOURCE_SOURCE),
            path = path,
            source = "runtime-bundle",
            format = format,
            profile = null
        )
        graphBuilder.addNode(fileNode)
        resourceFilesByPath.getOrPut(path) { mutableListOf() }.add(fileNode)
        bundleParent(bundle)?.let(::indexRuntimeBundle)
    }

    private fun toControl(controlSpec: BundleControlSpec): ResourceBundle.Control =
        if (controlSpec.noFallback) {
            ResourceBundle.Control.getNoFallbackControl(controlFormatsList(controlSpec))
        } else {
            ResourceBundle.Control.getControl(controlFormatsList(controlSpec))
        }

    private fun controlFormatsList(controlSpec: BundleControlSpec): List<String> = buildList {
        if (RESOURCE_BUNDLE_FORMAT_CLASS in controlSpec.formats) add(RESOURCE_BUNDLE_FORMAT_CLASS)
        if (RESOURCE_BUNDLE_FORMAT_PROPERTIES in controlSpec.formats) add(RESOURCE_BUNDLE_FORMAT_PROPERTIES)
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
        if (fieldRef.fieldSignature.declClassType.fullyQualifiedName != LOCALE_CLASS) return null
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
        if (calleeSignature.declClassType.fullyQualifiedName != LOCALE_CLASS) return null
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
        if (trimmed.isEmpty() || declaringClass == CLASS_LOADER_CLASS || trimmed.startsWith("/")) {
            return trimmed.removePrefix("/")
        }
        val packagePath = caller.declaringClass.className.substringBeforeLast('.', "").replace('.', '/')
        return if (packagePath.isEmpty()) trimmed else "$packagePath/$trimmed"
    }

    private fun isResourceConfig(path: String): Boolean =
        path.endsWith(PROPERTIES_FILE_SUFFIX) ||
            path.endsWith(".yml") ||
            path.endsWith(".yaml") ||
            path.endsWith(".json") ||
            path.endsWith(".xml")

    private fun resourceFormat(path: String): String = when {
        path.endsWith(PROPERTIES_FILE_SUFFIX) -> "properties"
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
            "java.lang.Integer" -> methodName == VALUE_OF_METHOD
            "java.lang.Long" -> methodName == VALUE_OF_METHOD
            "java.lang.Short" -> methodName == VALUE_OF_METHOD
            "java.lang.Byte" -> methodName == VALUE_OF_METHOD
            "java.lang.Float" -> methodName == VALUE_OF_METHOD
            "java.lang.Double" -> methodName == VALUE_OF_METHOD
            "java.lang.Boolean" -> methodName == VALUE_OF_METHOD
            "java.lang.Character" -> methodName == VALUE_OF_METHOD
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
        if (activeMethod === method) {
            return activeLocalKeysByName.getOrPut(localName) {
                LocalKey(method, localName).also(activeMethodLocals::add)
            }
        }
        return LocalKey(method, localName)
    }

    private fun parameterBinding(method: MethodDescriptor, index: Int): ParameterBinding {
        if (activeMethod === method) {
            return activeParameterBindingsByIndex.getOrPut(index) {
                ParameterBinding(method, index).also(activeMethodParameters::add)
            }
        }
        return ParameterBinding(method, index)
    }

    /**
     * [existing] with [newTargets] appended: [existing] itself when it already holds them all
     * (or is saturated), [newTargets] itself when there is nothing yet or it holds everything
     * in [existing], so a copy between locals or slots that hold the same function values
     * (every copy in a dex body, whose registers are reused; an interface method's parameter
     * reaching every implementation's) allocates nothing. A union past [MAX_TARGETS] is
     * [SATURATED_TARGETS], which nothing changes after; a saturated (so empty) [newTargets]
     * adds nothing. The result is never mutated.
     */
    private fun mergeTargets(
        existing: Set<DispatchTarget>?,
        newTargets: Collection<DispatchTarget>
    ): Set<DispatchTarget> = when {
        existing === SATURATED_TARGETS || newTargets.isEmpty() -> existing ?: emptySet()
        existing === newTargets || (existing != null && existing.size >= newTargets.size && existing.containsAll(newTargets)) -> existing
        newTargets.size > MAX_TARGETS -> SATURATED_TARGETS
        existing == null || (newTargets is Set<DispatchTarget> && newTargets.containsAll(existing)) ->
            newTargets as? Set<DispatchTarget> ?: LinkedHashSet(newTargets)
        else -> LinkedHashSet(existing).apply { addAll(newTargets) }.takeIf { it.size <= MAX_TARGETS } ?: SATURATED_TARGETS
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
        stmtLocalWrites.clear()
        stmtIdentityWrites.clear()
        activeMethod = null
        activeMethodLocals = mutableListOf()
        activeLocalKeysByName = mutableMapOf()
        activeMethodParameters = mutableListOf()
        activeParameterBindingsByIndex = mutableMapOf()
    }

    private fun toTypeDescriptor(type: Type): TypeDescriptor {
        return typeDescriptorCache.getOrPut(type) {
            when (type) {
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
    }

    /**
     * Get field type with generic arguments from bytecode signature.
     */
    private fun getFieldTypeWithGenerics(
        declaringClass: String,
        fieldName: String,
        fallbackType: TypeDescriptor
    ): TypeDescriptor {
        return signatureReader?.getFieldType(declaringClass, fieldName)
            ?: fieldGenericTypes(declaringClass)[fieldName]
            ?: fallbackType
    }

    private fun fieldGenericTypes(className: String): Map<String, TypeDescriptor> =
        fieldGenericTypesByClass.getOrPut(className) {
            val sootClass = resolveClassByName(className) as? JavaSootClass ?: return@getOrPut emptyMap()
            val signatures = fieldSignatures(sootClass, className) ?: return@getOrPut emptyMap()
            val genericTypes = HashMap<String, TypeDescriptor>(signatures.size)
            for ((field, signature) in signatures) {
                val type = GenericSignatureParser.parseFieldSignature(signature) ?: continue
                genericTypes[field] = type
            }
            genericTypes
        }

    /**
     * The generic signatures of the fields of [className]: kept by the location that parsed the
     * class ([ParsedClassLocation]), else read again from the class file for a location that
     * did not (an APK, a platform jar).
     */
    @Suppress("ReturnCount")
    private fun fieldSignatures(sootClass: JavaSootClass, className: String): Map<String, String>? {
        (sootClass.classSource.analysisInputLocation as? ParsedClassLocation)?.fieldSignatures(className)?.let { return it }
        val classNode = loadClassNodeFromResource(className) ?: return null
        @Suppress("UNCHECKED_CAST")
        val fields = classNode.fields as? List<AsmFieldNode> ?: return null
        return fields.filter { it.signature != null }.associate { it.name to it.signature }
    }

    private fun loadClassNodeFromResource(className: String): ClassNode? =
        try {
            val resourcePath = className.replace('.', '/') + CLASS_FILE_SUFFIX
            resourceAccessor.open(resourcePath).use { input ->
                ClassNode().also { classNode ->
                    ClassReader(input).accept(classNode, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                }
            }
        } catch (_: Exception) {
            null
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
            if (declaresMethod(sig.declClassType, sig.subSignature)) return@getOrPut sig

            val hierarchy = view.typeHierarchy
            val declClass = sig.declClassType

            try {
                for (superClass in hierarchy.superClassesOf(declClass)) {
                    if (declaresMethod(superClass, sig.subSignature)) {
                        return@getOrPut MethodSignature(superClass, sig.subSignature)
                    }
                }
            } catch (_: Exception) {
                // class not in hierarchy
            }

            try {
                for (iface in hierarchy.implementedInterfacesOf(declClass)) {
                    if (declaresMethod(iface, sig.subSignature)) {
                        return@getOrPut MethodSignature(iface, sig.subSignature)
                    }
                }
            } catch (_: Exception) {
                // class not in hierarchy
            }

            sig
        }
    }
}

/**
 * The slot flow graph in the form the fixpoint sweeps: the flows recorded while methods were
 * processed, each once, in compressed rows; the flows added during resolution (override flows,
 * found when a slot first holds targets, and those a resolved call creates); the sweep order,
 * a reverse postorder of the recorded flows so that most slots are swept after what flows into
 * them; and which slots changed since they were last swept. Slots numbered after construction
 * come last in sweep order.
 */
private class SlotPropagation(private val count: Int, from: IntArrayList, to: IntArrayList) {
    private val rowStart = IntArray(count + 1)
    private val rowFlows: IntArray
    private val order = IntArray(count)
    private val positions = IntArray(count)
    private val added = Int2ObjectOpenHashMap<IntArrayList>()
    val pending = BitSet(count)
    val expanded = BitSet(count)

    init {
        val edges = from.size
        for (edge in 0 until edges) rowStart[from.getInt(edge) + 1]++
        for (id in 0 until count) rowStart[id + 1] += rowStart[id]
        val flows = IntArray(edges)
        val fill = rowStart.copyOf()
        for (edge in 0 until edges) flows[fill[from.getInt(edge)]++] = to.getInt(edge)
        // Sort each row and drop the repeats; a row is compacted below where it was read from
        var written = 0
        for (id in 0 until count) {
            val start = rowStart[id]
            val end = rowStart[id + 1]
            java.util.Arrays.sort(flows, start, end)
            rowStart[id] = written
            var previous = -1
            for (index in start until end) {
                val flow = flows[index]
                if (flow != previous) {
                    flows[written++] = flow
                    previous = flow
                }
            }
        }
        rowStart[count] = written
        rowFlows = flows.copyOf(written)
        computeOrder()
    }

    /** Reverse postorder of an iterative depth-first search over the recorded flows. */
    private fun computeOrder() {
        val search = DepthFirstSearch()
        for (root in 0 until count) {
            if (search.enter(root)) search.run()
        }
        for (index in 0 until count) positions[order[index]] = index
    }

    /** Explicit-stack depth-first search filling [order] from the back as slots are left. */
    private inner class DepthFirstSearch {
        private val visited = BitSet(count)
        private val stack = IntArrayList()
        private val cursor = IntArrayList()
        private var next = count

        /** Push [id] unless it was visited; true when pushed. */
        fun enter(id: Int): Boolean {
            if (visited.get(id)) return false
            visited.set(id)
            stack.push(id)
            cursor.push(rowStart[id])
            return true
        }

        fun run() {
            while (!stack.isEmpty) {
                val id = stack.topInt()
                val index = cursor.topInt()
                if (index < rowStart[id + 1]) {
                    cursor.set(cursor.size - 1, index + 1)
                    enter(rowFlows[index])
                } else {
                    stack.popInt()
                    cursor.popInt()
                    order[--next] = id
                }
            }
        }
    }

    /** The slot swept at [index]; slots numbered after construction follow in number order. */
    fun slotAt(index: Int): Int = if (index < count) order[index] else index

    /** Where slot [id] comes in sweep order. */
    fun position(id: Int): Int = if (id < count) positions[id] else id

    fun addFlow(from: Int, to: Int) {
        val flows = added.get(from) ?: IntArrayList().also { added.put(from, it) }
        if (!flows.contains(to)) flows.add(to)
    }

    inline fun forEachFlow(id: Int, action: (Int) -> Unit) {
        if (id < rowStart.size - 1) {
            for (index in rowStart[id] until rowStart[id + 1]) action(rowFlows[index])
        }
        added.get(id)?.let { flows -> for (index in 0 until flows.size) action(flows.getInt(index)) }
    }
}
