package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.ConstantNode
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.ArtifactRole
import io.johnsonlee.graphite.graph.CallSiteGap
import io.johnsonlee.graphite.graph.ClassEvidence
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.MethodEvidence
import io.johnsonlee.graphite.graph.FullGraphBuilder
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.DependencyBodyScope
import io.johnsonlee.graphite.input.EmptyResourceAccessor
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.input.ResourceAccessor
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import java.util.BitSet
import java.util.Collections
import java.util.IdentityHashMap
import java.util.ServiceLoader
import sootup.core.graph.ControlFlowGraph
import sootup.core.inputlocation.AnalysisInputLocation
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.model.SootClass
import sootup.core.model.SootMethod
import sootup.core.signatures.FieldSignature
import sootup.core.signatures.MethodSignature
import sootup.java.core.JavaSootClass
import sootup.java.bytecode.frontend.conversion.AsmMethodSource
import sootup.java.core.JavaSootMethod
import sootup.core.types.Type
import sootup.core.views.View

internal const val RESOURCE_BUNDLE_FORMAT_PROPERTIES = "java.properties"
internal const val RESOURCE_BUNDLE_FORMAT_CLASS = "java.class"
internal const val PROPERTY_RESOURCE_BUNDLE_CLASS = "java.util.PropertyResourceBundle"
internal const val RESOURCE_BUNDLE_CLASS = "java.util.ResourceBundle"
internal const val LOCALE_CLASS = "java.util.Locale"
internal const val INIT_METHOD = "<init>"
internal const val KOTLIN_INVOKE = "invoke"
internal const val KOTLIN_PROPERTY_GET = "get"
internal const val JAVA_LANG_OBJECT = "java.lang.Object"
internal const val CALLABLE_REFERENCE_RECEIVER_FIELD = "receiver"
internal const val VALUE_OF_METHOD = "valueOf"
internal const val CLASS_FILE_SUFFIX = ".class"
internal const val MULTI_RELEASE_PREFIX = "META-INF/versions/"
internal const val PROPERTIES_FILE_SUFFIX = ".properties"
internal const val CONST_NODE_PREFIX = "const"
internal const val RESOURCE_SOURCE = "resource"
internal const val GET_KEYS_METHOD = "getKeys"
internal const val GET_BUNDLE_METHOD = "getBundle"
internal const val GET_PROPERTY_METHOD = "getProperty"
internal const val FROM_JSON_METHOD = "fromJson"
internal const val PARSE_METHOD = "parse"
internal const val OPEN_STREAM_METHOD = "openStream"
internal const val NEW_READER_METHOD = "newReader"
internal const val PASS1_PROGRESS_INTERVAL = 500
internal const val PASS2_PROGRESS_INTERVAL = 100

/**
 * The most function values a local or a [DispatchSlot] is tracked as holding. Resolution is
 * flow- and context-insensitive, so a value that many function values reach (an `Object`
 * parameter of a shared helper, a register a dex body reuses for hundreds of lambdas) holds
 * the union of all of them, and a call on it would resolve to every one: thousands of call
 * sites that mean nothing, and on a large Android app a fixpoint that outgrows the heap.
 * Past this many the holder is [SATURATED_TARGETS]: it resolves nothing and adds nothing to
 * what it flows to, which keeps the precise targets other paths bring there.
 */
internal const val MAX_TARGETS = 64

/** The targets of a holder past [MAX_TARGETS]: empty, told apart from any other set by identity. */
internal val SATURATED_TARGETS: Set<DispatchTarget> = java.util.Collections.unmodifiableSet(LinkedHashSet())
internal val CONVERSION_SCRATCH_FIELDS = listOf("insnIndexCache", "localVarTypeAnnotationIndex")

internal fun <K, V> identityMutableMap(): MutableMap<K, V> = IdentityHashMap()

internal class IntArrayBuilder(initialCapacity: Int = 8) {
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

internal class IntQueue(initialCapacity: Int = 16) {
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

internal class ControlFlowIndex(private val stmtGraph: ControlFlowGraph<*>, statementsInOrder: List<Stmt>) {
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
@Suppress("LongParameterList") // Adapter dependencies are explicit constructor seams for independent loaders/tests.
class SootUpAdapter(
    internal val view: View,
    internal val config: LoaderConfig,
    internal val signatureReader: BytecodeSignatureReader? = null,
    internal val extensions: List<GraphiteExtension> = ServiceLoader.load(GraphiteExtension::class.java).toList(),
    internal val resourceAccessor: ResourceAccessor = EmptyResourceAccessor,
    internal val declarationWarning: (String) -> Unit = {},
    internal val inputLocationSources: Map<AnalysisInputLocation, String>,
    /** The role of each source of [inputLocationSources], by name; a source without one is the application's. */
    internal val artifactRoles: Map<String, ArtifactRole> = emptyMap(),
    internal val singleArtifactSource: String? = null,
    /** The sources in the order the runtime loads them (`BOOT-INF/classpath.idx`), when the input says; the listing order otherwise. */
    internal val artifactOrder: List<String> = emptyList(),
    internal val graphBuilder: FullGraphBuilder = DefaultGraph.Builder(),
    /** A body's invoke numbering from before the fold pass changed it, when a rule folded there. */
    internal val preFoldOrdinals: (MethodSignature) -> Map<Stmt, Int>? = { null }
) {
    internal val trackCrossMethodFunctionalDispatch = config.trackCrossMethodFunctionalDispatch
    internal val extractAnnotationsEnabled = config.extractAnnotations
    /** The call-to-body edges of `LoaderConfig.interproceduralDataflow`, collected only when it is on. */
    internal val interprocedural = if (config.interproceduralDataflow) InterproceduralDataflow() else null

    internal class LocalKey(val method: MethodDescriptor, val name: String) {
        override fun equals(other: Any?): Boolean =
            other is LocalKey && method === other.method && name == other.name

        override fun hashCode(): Int = 31 * System.identityHashCode(method) + name.hashCode()
    }

    internal class ParameterBinding(val method: MethodDescriptor, val index: Int) {
        override fun equals(other: Any?): Boolean =
            other is ParameterBinding && method === other.method && index == other.index

        override fun hashCode(): Int = 31 * System.identityHashCode(method) + index
    }

    internal data class BundleControlSpec(
        val noFallback: Boolean = false,
        val formats: Set<String> = setOf(RESOURCE_BUNDLE_FORMAT_PROPERTIES, RESOURCE_BUNDLE_FORMAT_CLASS),
        val candidateLocales: List<String>? = null
    )
    internal data class LocaleBuilderSpec(
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
    internal data class IndexedClass(val sootClass: JavaSootClass, val source: String)

    // Maps to track created nodes for cross-referencing
    internal val localNodes = mutableMapOf<LocalKey, LocalVariable>()
    internal val fieldNodes = mutableMapOf<String, FieldNode>()
    internal val parameterNodes = mutableMapOf<ParameterBinding, ParameterNode>()
    internal val constantNodes = mutableMapOf<Any, ConstantNode>()
    internal val methodReturnNodes = mutableMapOf<MethodDescriptor, ReturnNode>()
    internal val allocationNodes = mutableMapOf<LocalKey, LocalVariable>()

    // Dispatch targets of locals holding a function value; keyed per method, and each method's
    // entries are removed by clearMethodState(). Every target set below is immutable once
    // stored and shared between the locals and slots that hold the same targets, see
    // mergeTargets() and addTargets(): a dex body keeps the compiler's registers as locals,
    // each reused for many values, and a copy between two such registers must neither append
    // every target again nor allocate.
    internal val dynamicTargets = mutableMapOf<LocalKey, Set<DispatchTarget>>()

    // Maps local key to parameter binding for locals assigned from parameters
    internal val localToParamIndex = mutableMapOf<LocalKey, ParameterBinding>()

    internal val arrayDynamicTargets = mutableMapOf<LocalKey, Set<DispatchTarget>>()

    // Cross-method functional dispatch, over slots (parameters, returns, fields, locals)
    // numbered on first use by slotId(): the targets seeded into each slot, the flows between
    // slots as parallel edge lists, the adapters and the calls waiting on a slot.
    // resolveFunctionalDispatch() propagates the targets to a fixpoint once every method has
    // been processed; the numbering makes that sweeps over arrays, where a map keyed by slot
    // cost a structural hash of a method descriptor per step.
    internal val slotIds = Object2IntOpenHashMap<DispatchSlot>().apply { defaultReturnValue(-1) }
    internal val slots = ArrayList<DispatchSlot>()
    internal val slotTargets = ArrayList<Set<DispatchTarget>?>()
    internal val flowFrom = IntArrayList()
    internal val flowTo = IntArrayList()
    internal val slotAdapters = Int2ObjectOpenHashMap<MutableList<SlotAdapter>>()
    internal val slotCalls = Int2ObjectLinkedOpenHashMap<MutableList<PendingDispatch>>()
    /** After the fixpoint, the slots a value with no known origin in the scope may reach, see [computeOpenSlots]. */
    internal var openSlots: BitSet? = null
    /** After the fixpoint, the slots a saturated slot flows into, see [computeOpenSlots]. */
    internal var lostSlots: BitSet? = null
    /** The slots seeded from a saturated holder: the targets never arrived, see [seedSlot]. */
    internal val lostSeeds = BitSet()
    /** The signature each static field reference resolved to, see [resolvedFieldSignature]. */
    internal val resolvedFields = HashMap<FieldSignature, FieldSignature>()
    internal val functionObjectClasses = mutableMapOf<String, Boolean>()
    internal val mayHoldFunctionByType = mutableMapOf<String, Boolean>()
    // Type hierarchy of the view, for flows across override boundaries: direct subtypes of every
    // superclass and interface named by a class in the view (built in pass 1), and lazily
    // cached transitive closures, method indexes and sorted method lists
    internal val directSubtypes = mutableMapOf<String, MutableList<String>>()
    internal val transitiveSubtypesByClass = mutableMapOf<String, List<String>>()
    internal val supertypesByClass = mutableMapOf<String, List<String>>()
    internal val declaredMethodIndexByClass = mutableMapOf<String, Map<String, SootMethod>>()
    internal val sortedMethodsByClass = mutableMapOf<String, List<SootMethod>>()
    internal val supertypeContractsByClass = mutableMapOf<String, Set<String>?>()
    internal val bridgesInvokingByClass = mutableMapOf<String, Map<String, List<String>>>()
    internal val implementedByFunctionValue = mutableMapOf<MethodDescriptor, Boolean>()
    // The flow graph while resolveFunctionalDispatch() runs (null before and after), and calls
    // that resolving another call produced (`Function::apply` on a function value), waiting
    // to join slotCalls between rounds
    internal var propagation: SlotPropagation? = null
    internal val nestedDispatches = mutableListOf<Pair<DispatchSlot, PendingDispatch>>()

    internal val localeSpecsByLocal = mutableMapOf<LocalKey, String>()
    internal val localeBuilderSpecsByLocal = mutableMapOf<LocalKey, LocaleBuilderSpec>()
    internal val stringValuesByLocal = mutableMapOf<LocalKey, String>()
    internal val bundleControlFormatsByLocal = mutableMapOf<LocalKey, String?>()
    internal val resourceHandlePathsByLocal = mutableMapOf<LocalKey, LinkedHashSet<String>>()
    internal val propertiesPathsByLocal = mutableMapOf<LocalKey, LinkedHashSet<String>>()
    internal val resourceBundlePaths = mutableMapOf<LocalKey, LinkedHashSet<String>>()
    internal val bundleControlSpecsByLocal = mutableMapOf<LocalKey, BundleControlSpec>()

    internal val resolvedMethodCache = mutableMapOf<MethodSignature, MethodSignature>()

    /** The rendered declaring signature of each callee, for the call-site ordinals of every body. */
    /**
     * The graph's rendering of each callee the ordinal pass resolved, by the resolved signature's
     * identity: `resolvedMethodCache` hands back one instance per callee, so no signature is
     * hashed or compared here.
     */
    internal val renderedDefiningClass = IdentityHashMap<MethodSignature, String>()

    /**
     * The callee each invoke of the current body resolves to, by the identity of the invoke's own
     * signature object: the ordinal pass resolves every invoke once, and the call-site pass reads
     * the same object off the same expression, so the body's callees are resolved once, not twice.
     */
    internal var bodyResolvedCallees: IdentityHashMap<MethodSignature, MethodSignature> = IdentityHashMap()
    internal val methodDescriptorCache = mutableMapOf<MethodSignature, MethodDescriptor>()
    internal val typeDescriptorCache = identityMutableMap<Type, TypeDescriptor>()
    internal val declaredMethodSubSignaturesByClass = mutableMapOf<String, Set<String>>()
    internal val fieldGenericTypesByClass = mutableMapOf<String, Map<String, TypeDescriptor>>()
    internal val classOriginsByName = mutableMapOf<String, String>()
    internal val classOriginSourceCounts = mutableMapOf<String, Int>()

    // The completeness evidence (Graph.analysisCompleteness): what the build did with every class
    // of the view and with every method it did not analyse completely, the duplicate class
    // definitions of a multi-artifact input, and the archives that carry versioned class copies.
    internal val classEvidence = HashMap<String, ClassEvidence>()
    internal val methodEvidence = HashMap<String, MethodEvidence>()
    internal val duplicateClasses = LinkedHashMap<String, MutableList<String>>()
    internal val multiReleaseArtifacts = HashSet<String>()
    internal var classEntrySources = HashMap<String, String>()
    internal var resolutionFailures = 0
    internal val callSiteGaps = HashMap<Int, CallSiteGap>()
    /** Under a reachable scope, the callees of every followed call site read so far, drained by the expansion. */
    internal val reachableExpansion = config.dependencyBodies as? DependencyBodyScope.Reachable
    internal val reachedCallees = ArrayList<MethodDescriptor>()
    internal val reachedVirtually = ArrayList<Boolean>()
    /** Under a reachable scope, the classes followed static field accesses initialise, which the JVM does on first access. */
    internal val reachedClasses = ArrayList<String>()
    internal val notedClasses = HashSet<String>()
    /** Every callee followed by the expansion, by identity (descriptors are canonical per signature), and whether virtually. */
    internal val notedCallees: MutableMap<MethodDescriptor, Boolean> = IdentityHashMap()
    internal val reachableFrontier = ReachableFrontier()
    internal var reachableBudget = reachableExpansion?.maxClasses ?: 0
    internal var reachableRead = 0
    internal var reachablePastBudget = 0
    /**
     * Under a reachable scope, the classes every call of which the expansion follows: the roots
     * (every application class without roots) and the library classes the expansion reads.
     */
    internal val seededClasses = HashSet<String>()
    /** The application methods a followed call, override or initialisation reaches, whose own calls are followed too. */
    internal val seededMethods: MutableSet<MethodDescriptor> = Collections.newSetFromMap(IdentityHashMap())
    /** The notes of the application methods nothing followed has reached yet, released when something does. */
    internal val heldNotes = HashMap<MethodDescriptor, HeldNotes>()

    /** The classes pass 2 read as the application's (by package, whatever artifact holds them). */
    internal val applicationClasses = HashSet<String>()
    /** Calls on function values that no slot tracks (cross-method tracking off): their gaps are read from here. */
    internal val untrackedDispatches = ArrayList<UntrackedDispatch>()
    /**
     * With cross-method tracking off, the locals (by [localKey]) holding a value their method
     * did not produce: a parameter, a field, a call's result, an element of an array one of
     * those holds, or a copy of such a local. A call on one dispatches to what the method
     * assigned it, and to a value the method cannot name.
     */
    internal val openLocals = HashSet<LocalKey>()
    /** Set while a pass-2 class's body is read: its branch scopes were left out for its size. */
    internal var activeControlFlowTruncated = false
    /** Set while a pass-2 class is processed: its methods could not be resolved at all. */
    internal var activeClassResolutionFailed = false
    internal val artifactDependenciesByArtifact = mutableMapOf<String, MutableMap<String, Int>>()
    internal val resourceFilesByPath = mutableMapOf<String, MutableList<ResourceFileNode>>()
    internal val configurationResourcePaths = linkedSetOf<String>()
    internal val runtimeIndexedBundles = mutableSetOf<String>()
    internal val bundleControlSpecsByClass = mutableMapOf<String, BundleControlSpec?>()
    internal var classesByNameCache: Map<String, SootClass>? = null
    internal val syntheticIdentities = SyntheticIdentity.Collector()
    /** Whether the class whose methods pass two is processing is a synthetic class, see [SyntheticIdentity]. */
    internal var activeSyntheticClass = false

    // Per-method: tracks which NodeIds were created from each statement
    // Reset per method in processMethod()
    internal var stmtNodeIds = identityMutableMap<Stmt, IntArrayBuilder>()

    /**
     * Per-method writes to locals, keyed by statement and holding
     * `[localNodeId, constantNodeId | NO_CONSTANT]`. Filled in pass one alongside the
     * edges, consumed in pass two to attach [io.johnsonlee.graphite.core.LocalDefinition]s
     * to each branch side and to the per-local tables. Same lifecycle as [stmtNodeIds].
     */
    internal var stmtLocalWrites = identityMutableMap<Stmt, IntArray>()

    /**
     * Identity statements (`r := @this`, `$e := @caughtexception`) that write a local whose
     * node may only be created by a later statement; resolved by name in pass two.
     */
    internal var stmtIdentityWrites = identityMutableMap<Stmt, String>()
    internal var activeMethod: MethodDescriptor? = null
    internal var activeMethodLocals = mutableListOf<LocalKey>()
    /**
     * `CallSite.ordinal` of every invoke of the active method's body, by statement: computed once
     * from the unfiltered body by [callOrdinals], the function the fold pass reads a rule's keys
     * with, so a boxing call this adapter turns into a dataflow edge rather than a call site
     * still takes its number and a key read off the graph names the same invoke in both.
     */
    internal var bodyCallOrdinals: Map<Stmt, Int> = emptyMap()
    /**
     * Derived calls of each callee per caller: these are also created after the caller's pass,
     * when cross-method dispatch resolves, so they are counted per caller for the whole build.
     */
    internal val derivedCallOrdinals = HashMap<String, Int>()
    internal var activeLocalKeysByName = mutableMapOf<String, LocalKey>()
    internal var activeMethodParameters = mutableListOf<ParameterBinding>()
    internal var activeParameterBindingsByIndex = mutableMapOf<Int, ParameterBinding>()

    /** The classes the expansion has reached and not yet read, each queued once. */
    internal class ReachableFrontier {
        val queue = ArrayDeque<String>()
        val reached = HashSet<String>()
        var drained = 0
        var drainedClasses = 0
    }

    /** Held by an application method nothing followed has reached: its callees (whether virtually) and the classes it initialises. */
    internal class HeldNotes {
        val callees: MutableMap<MethodDescriptor, Boolean> = IdentityHashMap()
        val classes = LinkedHashSet<String>()
    }

    companion object {
        /** The primitive each wrapper's boxing overload takes: `Integer.valueOf(int)`, `Boolean.valueOf(boolean)`, ... */
        internal val BOXED_PRIMITIVES = mapOf(
            "java.lang.Integer" to "int",
            "java.lang.Long" to "long",
            "java.lang.Short" to "short",
            "java.lang.Byte" to "byte",
            "java.lang.Float" to "float",
            "java.lang.Double" to "double",
            "java.lang.Boolean" to "boolean",
            "java.lang.Character" to "char"
        )
        internal val WRAPPER_CLASSES: Set<String> = BOXED_PRIMITIVES.keys
        internal val UNBOXING_METHODS = setOf(
            "intValue",
            "longValue",
            "shortValue",
            "byteValue",
            "floatValue",
            "doubleValue",
            "booleanValue",
            "charValue"
        )
        internal val RESOURCE_LOOKUP_METHODS = setOf(GET_PROPERTY_METHOD, "getString", "getObject")
        internal val RESOURCE_LOOKUP_CLASSES = setOf(
            PROPERTIES_CLASS,
            PROPERTY_RESOURCE_BUNDLE_CLASS,
            RESOURCE_BUNDLE_CLASS
        )
        internal val RESOURCE_PATH_METHODS = setOf("getResource", "getResourceAsStream")
        internal val RESOURCE_BUNDLE_READ_METHODS = setOf("getString", "getObject", GET_KEYS_METHOD)
        internal val PROPERTIES_LOAD_METHODS = setOf("load", "loadFromXML")
        internal val READER_BRIDGE_CLASSES = setOf(
            "java.io.InputStreamReader",
            "java.io.BufferedReader",
            "java.io.StringReader",
            "java.io.LineNumberReader"
        )
        internal const val LOCALE_BUILDER_CLASS = "java.util.Locale\$Builder"
        internal const val LOCALE_BUILDER_CLASS_ALT = "java.util.Locale.Builder"
        internal const val RESOURCE_BUNDLE_CONTROL_CLASS = "java.util.ResourceBundle\$Control"
        internal const val RESOURCE_BUNDLE_CONTROL_CLASS_ALT = "java.util.ResourceBundle.Control"
        internal const val PROPERTIES_CLASS = "java.util.Properties"
        internal const val CLASS_LOADER_CLASS = "java.lang.ClassLoader"
        internal const val CLASS_CLASS = "java.lang.Class"
        internal const val GSON_CLASS = "com.google.gson.Gson"
        internal const val DOCUMENT_BUILDER_CLASS = "javax.xml.parsers.DocumentBuilder"
        internal const val URL_CLASS = "java.net.URL"
        internal const val CHANNELS_CLASS = "java.nio.channels.Channels"
        internal const val MAX_CONTROL_FLOW_STATEMENTS = 2_000
    }

    /** A branch whose scope is emitted once the method's tracked locals are known. */
    internal class PendingBranchScope(
        val conditionNodeId: NodeId,
        val comparison: BranchComparison,
        val trueIds: IntArray,
        val falseIds: IntArray,
        /** The statements whose writes are this side's definitions: reached only through this side. */
        val trueOnlyStatements: BitSet,
        val falseOnlyStatements: BitSet
    )

    // Only the current pass-2 class may populate the sorted-method caches. Ancestor lookups,
    // enum discovery and post-pass call-graph cleanup must not retain a corpus-wide index.
    internal var methodCacheOwner: JavaSootClass? = null

    internal val streamingSourcesCache = IdentityHashMap<JavaSootClass, List<AsmMethodSource>?>()

    /**
     * The scratch fields of a body source class, looked up once: `getDeclaredField` per method
     * and per field was a reflective lookup for every body of the corpus, twice over.
     */
    internal val conversionScratchFieldsByClass = HashMap<Class<*>, List<java.lang.reflect.Field>>()

    /**
     * [bytecodeMethods] per class while the class is being processed: `resolveMethods()`
     * converts every method's descriptor into a signature on each call, and a class is asked
     * for its methods more than once in a pass (its graph, its declared sub-signatures). The
     * entry is dropped in finally once that class's pass is over. Other classes and phases
     * resolve without insertion, so the cache holds at most the active class.
     */
    internal val bytecodeMethodsCache = IdentityHashMap<JavaSootClass, List<JavaSootMethod>?>()

    internal val construction = GraphConstruction(this)
    internal val reachability = ReachableDependencies(this)
    internal val completeness = AnalysisEvidence(this)
    internal val declarations = ClassDeclarationsEmitter(this)
    internal val bodies = MethodBodyEmitter(this)
    internal val calls = CallSiteEmitter(this)
    internal val callKinds = CallClassification(this)
    internal val controlFlow = ControlFlowEmitter(this)
    internal val dispatchResolution = DispatchResolution(this)
    internal val dispatchHierarchy = DispatchHierarchy(this)
    internal val dispatchFlows = DispatchFlows(this)
    internal val methodSources = MethodSources(this)
    internal val values = ValueNodes(this)
    internal val resources = ResourceInventory(this)
    internal val literals = BytecodeLiterals(this)
    internal val resourceReads = ResourceReads(this)
    internal val bundlePaths = BundlePaths(this)
    internal val locales = LocaleTracking(this)
    internal val bundleControl = BundleControl(this)
    internal val localeValues = LocaleValues(this)
    internal val metadata = NodeMetadata(this)
    internal val descriptors = DescriptorResolution(this)
    internal val functionObjects = FunctionObjectMethods(this)
    internal val bytecodeMethods = BytecodeMethodSources(this)

    fun buildGraph(): Graph = construction.buildGraph()

}

/**
 * The slot flow graph in the form the fixpoint sweeps: the flows recorded while methods were
 * processed, each once, in compressed rows; the flows added during resolution (override flows,
 * found when a slot first holds targets, and those a resolved call creates); the sweep order,
 * a reverse postorder of the recorded flows so that most slots are swept after what flows into
 * them; and which slots changed since they were last swept. Slots numbered after construction
 * come last in sweep order.
 */
internal class SlotPropagation(private val count: Int, from: IntArrayList, to: IntArrayList) {
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
