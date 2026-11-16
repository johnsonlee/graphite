package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.analysis.DataFlowAnalysis
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodEvidence
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.FoldPlan
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.testfixture.minimalAndroidSdk
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import sootup.apk.frontend.ApkAnalysisInputLocation
import sootup.apk.frontend.DexBodyInterceptors
import sootup.apk.frontend.main.AndroidVersionInfo
import sootup.core.interceptor.BodyInterceptor
import sootup.core.model.SootClass
import sootup.core.model.MethodModifier
import sootup.java.core.views.JavaView

/**
 * SootUp's DEX frontend converts and intercepts every body of a class to list its methods, and
 * the class the view caches keeps them. Declaration lookups read headers instead, the graph pass
 * converts each body once and drops it, and no lookup re-requests a body being intercepted.
 */
class DexBodyLifetimeTest {
    private val apk = Path.of(requireNotNull(System.getProperty("frontend.correctness.android")))
    private val sdk = minimalAndroidSdk("dex-body-lifetime", apiLevel = 21)
    private val prefix = "fixture.frontend.android.DeclarationLookups"

    @AfterTest
    fun cleanup() {
        sdk.toFile().deleteRecursively()
    }

    @Test
    fun `declaration lookups on DEX classes convert no body`() {
        val location = ApkAnalysisInputLocation(apk, AndroidVersionInfo(apk, sdk.resolve("platforms").toString()),
            listOf(BodyInterceptor { body, _ -> error("declaration lookup converted ${body.methodSignature}") }))
        val view = JavaView(location)
        val adapter = SootUpAdapter(view, LoaderConfig(), extensions = emptyList(),
            inputLocationSources = mapOf(location to apk.fileName.toString()))
        fun type(name: String) = view.identifierFactory.getClassType(name)
        val function = view.getClass(type("$prefix\$1")).get()

        val signatures = declaredMethodSignatures(function).map { it.toString() }.sorted()
        assertEquals(listOf(
            "<$prefix\$1: java.lang.Object apply(java.lang.Object)>",
            "<$prefix\$1: java.lang.String apply(java.lang.String)>",
            "<$prefix\$1: void <init>()>"
        ), signatures)
        val dispatch = adapter.dispatchHierarchy.methodsInSignatureOrder(function)
        assertEquals(signatures, dispatch.map { it.signature.toString() })
        val bridge = dispatch.single { MethodModifier.isBridge(it.modifiers) }
        assertEquals("java.lang.Object", bridge.returnType.toString())
        assertTrue(MethodModifier.SYNTHETIC in bridge.modifiers && !bridge.isStatic && !bridge.isAbstract)
        assertSame(dispatch, adapter.dispatchHierarchy.methodsInSignatureOrder(function), "one object per method for identity checks")
        val apply = MethodDescriptor(TypeDescriptor("$prefix\$Transformer"), "apply",
            listOf(TypeDescriptor("java.lang.Object")), TypeDescriptor("java.lang.Object"))
        assertTrue(adapter.dispatchHierarchy.declaredMethod("$prefix\$Transformer", apply)!!.isAbstract)
        assertSame(bridge, adapter.dispatchHierarchy.effectiveMethod("$prefix\$1", apply))
        assertTrue(adapter.resources.declaresMethod(type("$prefix\$Mode"), view.identifierFactory.parseMethodSubSignature(
            "$prefix\$Mode[] values()")))
        val defaults = linkedSetOf<String>()
        adapter.reachability.collectDefaultBearingInterfaces("$prefix\$Transformer", hashSetOf(), defaults)
        assertEquals(emptySet(), defaults, "an interface of abstract methods only")
        assertNull(view.dexBodies(), "no build is running")
    }

    @Test
    fun `a folded APK build converts each DEX body once without reentry and the view keeps none`() {
        val active = linkedSetOf<String>()
        val reentered = mutableListOf<String>()
        val conversions = sortedMapOf<String, Int>()
        val begin = BodyInterceptor { body, _ ->
            val signature = body.methodSignature.toString()
            conversions.merge(signature, 1, Int::plus)
            if (!active.add(signature)) {
                reentered += signature
                error("A declaration lookup recursively requested the active DEX body $signature")
            }
        }
        val end = BodyInterceptor { body, _ -> active.remove(body.methodSignature.toString()) }
        val gate = ConstantFold.parse(mapOf("match" to mapOf("CallSite" to mapOf("callee_name" to "gate")), "value" to true))
        val loader = JavaProjectLoader(LoaderConfig(androidSdk = sdk, buildCallGraph = false,
            folding = FoldPlan(listOf(gate))), useMmapBuilder = false)
        loader.apkInterceptorFactory = { chain -> listOf(begin) + chain + end }
        val graph = loader.load(apk)

        assertEquals(emptyList(), reentered)
        assertEquals(emptySet(), active, "every interception finished")
        assertEquals(0, loader.bodyInterceptionFallbackCount, "no body needed a recovery chain")
        assertEquals(emptyMap(), conversions.filterValues { it != 1 }, "each DEX body converted exactly once")
        // The two enums whose constructors read each other, and the bridge dispatch reads, are among them.
        listOf("$prefix\$Left: void <init>(java.lang.String,int)>", "$prefix\$Right: void <init>(java.lang.String,int)>",
            "$prefix\$Left: void <clinit>()>", "$prefix\$1: java.lang.Object apply(java.lang.Object)>").forEach { name ->
            assertEquals(1, conversions["<$name"], name)
        }

        // gate() folds to true, and Mode.ON == Mode.ON folds on an enum proved safe through DEX headers:
        // select() returns 1, never the 2 of the comparison's other side nor the 0 of gate's.
        assertEquals(listOf(1), returnedConstants(graph, "select"))
        assertTrue(graph.nodes<CallSiteNode>().none { it.callee.name == "gate" && it.caller.declaringClass.className.startsWith(prefix) })
        // A constructor folded while the other enum's safety was being proved keeps its call ordinals.
        val superCalls = graph.nodes<CallSiteNode>().filter {
            it.caller.name == "<init>" && it.caller.declaringClass.className in setOf("$prefix\$Left", "$prefix\$Right") &&
                it.callee.declaringClass.className == "java.lang.Enum"
        }.toList()
        assertEquals(listOf(0, 0), superCalls.map { it.ordinal })
    }

    @Test
    fun `the graph pass leaves no DEX body on the class the view caches`() {
        val conversions = mutableListOf<String>()
        val location = ApkAnalysisInputLocation(apk, AndroidVersionInfo(apk, sdk.resolve("platforms").toString()),
            DexBodyInterceptors.Default.bodyInterceptors() + BodyInterceptor { body, _ -> conversions += body.methodSignature.toString() })
        val view = JavaView(location)
        val adapter = SootUpAdapter(view, LoaderConfig(), extensions = emptyList(),
            inputLocationSources = mapOf(location to apk.fileName.toString()))
        adapter.buildGraph()
        assertEquals(conversions.size, conversions.toSet().size, "each body converted once")
        assertNull(view.dexBodies(), "nothing lent outlives the build")

        // Had the build listed the cached class's methods, they would be memoized and convert nothing now.
        val owner = view.getClass(view.identifierFactory.getClassType("fixture.frontend.android.AndroidCases")).get()
        val before = conversions.size
        val methods = owner.methods
        assertTrue(methods.isNotEmpty())
        assertEquals(methods.size, conversions.size - before)
    }

    @Test
    fun `a DEX body that fails to convert fails its method alone`() {
        val failures = mapOf<String, () -> Throwable>(
            "branch" to { IllegalStateException("state") },
            "relationalBranch" to { IllegalArgumentException("argument") },
            "denseSwitch" to { IndexOutOfBoundsException("index") },
            "sparseSwitch" to { sootup.core.frontend.ResolveException("resolve", apk) },
            "nullReturn" to { OutOfMemoryError("memory") }
        )
        val location = ApkAnalysisInputLocation(apk, AndroidVersionInfo(apk, sdk.resolve("platforms").toString()),
            listOf(BodyInterceptor { body, _ ->
                val signature = body.methodSignature
                if (signature.declClassType.fullyQualifiedName == "fixture.frontend.android.AndroidCases") {
                    failures[signature.name]?.let { throw it() }
                }
            }))
        val view = JavaView(location)
        val adapter = SootUpAdapter(view, LoaderConfig(includePackages = listOf("fixture.frontend.android")), extensions = emptyList(),
            inputLocationSources = mapOf(location to apk.fileName.toString()))
        val completeness = requireNotNull(adapter.buildGraph().analysisCompleteness())
        val failed = completeness.methods.filterKeys { it.startsWith("fixture.frontend.android.AndroidCases.") }.entries
            .associate { (identity, evidence) -> identity.substringAfter("AndroidCases.").substringBefore('(') to evidence }
        assertEquals(mapOf(
            "branch" to MethodEvidence(AnalysisStatus.RESOLUTION_FAILED, "state"),
            "relationalBranch" to MethodEvidence(AnalysisStatus.RESOLUTION_FAILED, "argument"),
            "denseSwitch" to MethodEvidence(AnalysisStatus.RESOLUTION_FAILED, "index"),
            "sparseSwitch" to MethodEvidence(AnalysisStatus.RESOLUTION_FAILED, failed["sparseSwitch"]?.message),
            "nullReturn" to MethodEvidence(AnalysisStatus.RESOLUTION_FAILED, "memory")
        ), failed)
        assertTrue(failed.getValue("sparseSwitch").message!!.startsWith("resolve"))
        assertEquals(AnalysisStatus.ANALYZED, completeness.classStatus("fixture.frontend.android.AndroidCases"))
    }

    @Test
    fun `an enum initializer that fails to convert yields no static method`() {
        var failure: Throwable? = null
        val location = ApkAnalysisInputLocation(apk, AndroidVersionInfo(apk, sdk.resolve("platforms").toString()),
            listOf(BodyInterceptor { _, _ -> failure?.let { throw it } }))
        val view = JavaView(location)
        val adapter = SootUpAdapter(view, LoaderConfig(), extensions = emptyList(),
            inputLocationSources = mapOf(location to apk.fileName.toString()))
        val mode = view.getClass(view.identifierFactory.getClassType("$prefix\$Mode")).get()
        failure = IllegalStateException("state")
        assertNull(adapter.methodSources.findStaticMethod(mode, "<clinit>"))
        failure = OutOfMemoryError("memory")
        assertNull(adapter.methodSources.findStaticMethod(mode, "<clinit>"))
        failure = null
        assertNull(adapter.methodSources.findStaticMethod(mode, "absent"))
        val initializer = assertNotNull(adapter.methodSources.findStaticMethod(mode, "<clinit>"))
        assertTrue(initializer.body.stmts.isNotEmpty())
        val source = initializer.bodySource as DexHeaderBodySource
        assertNull(source.resolveAnnotationsDefaultValue())
        assertEquals(initializer.signature, source.getSignature())
    }

    @Test
    fun `a body lent after its class was processed, or for a class the pass does not read, is not held`() {
        val location = ApkAnalysisInputLocation(apk, AndroidVersionInfo(apk, sdk.resolve("platforms").toString()), emptyList())
        val view = JavaView(location)
        val mode = view.getClass(view.identifierFactory.getClassType("$prefix\$Mode")).get()
        val headers = requireNotNull(dexMethodHeaders(mode))
        val initializer = headers.single { it.name == "<clinit>" }.bodySource as DexHeaderBodySource
        val ledger = view.openDexBodies { it == mode.type }
        try {
            assertSame(ledger, view.dexBodies())
            val lent = ledger.lend(initializer)
            assertSame(lent, ledger.lend(initializer), "lent once, read again from the ledger")
            assertSame(lent, ledger.take(initializer), "taken by the graph pass")
            assertEquals(0, ledger.size)
            ledger.processed(mode.type)
            ledger.lend(headers.single { it.name == "values" }.bodySource as DexHeaderBodySource)
            assertEquals(0, ledger.size, "a processed class's body is converted for its asker alone")
        } finally {
            view.closeDexBodies(ledger)
        }
        assertNull(view.dexBodies())
        val outside = view.openDexBodies { false }
        try {
            outside.lend(initializer)
            assertEquals(0, outside.size, "a class the graph pass never reads keeps nothing")
        } finally {
            view.closeDexBodies(outside)
        }
    }

    @Test
    fun `two builds on one view each convert every DEX body once`() {
        val conversions = sortedMapOf<String, Int>()
        val location = ApkAnalysisInputLocation(apk, AndroidVersionInfo(apk, sdk.resolve("platforms").toString()),
            listOf(BodyInterceptor { body, _ -> conversions.merge(body.methodSignature.toString(), 1, Int::plus) }))
        val view = JavaView(location)
        repeat(2) {
            SootUpAdapter(view, LoaderConfig(), extensions = emptyList(),
                inputLocationSources = mapOf(location to apk.fileName.toString())).buildGraph()
        }
        assertTrue(conversions.isNotEmpty())
        assertEquals(emptyMap(), conversions.filterValues { it != 2 }, "nothing one build lent or processed reaches the next")
        listOf("$prefix\$Mode: void <clinit>()>", "$prefix\$1: java.lang.Object apply(java.lang.Object)>").forEach { name ->
            assertEquals(2, conversions["<$name"], name)
        }
    }

    @Test
    fun `a DEX bridge that fails to convert delegates to nothing and fails its method alone`() {
        val bridge = "<$prefix\$1: java.lang.Object apply(java.lang.Object)>"
        val location = ApkAnalysisInputLocation(apk, AndroidVersionInfo(apk, sdk.resolve("platforms").toString()),
            listOf(BodyInterceptor { body, _ -> check(body.methodSignature.toString() != bridge) { "bridge" } }))
        val view = JavaView(location)
        val adapter = SootUpAdapter(view, LoaderConfig(includePackages = listOf("fixture.frontend.android")), extensions = emptyList(),
            inputLocationSources = mapOf(location to apk.fileName.toString()))
        // Dispatch asked before the graph pass, and the build that asks it again after the pass failed the bridge.
        val function = view.getClass(view.identifierFactory.getClassType("$prefix\$1")).get()
        assertEquals(emptyMap(), adapter.functionObjects.bridgesInvoking(function))
        adapter.bridgesInvokingByClass.clear()
        val completeness = requireNotNull(adapter.buildGraph().analysisCompleteness())
        assertEquals(MethodEvidence(AnalysisStatus.RESOLUTION_FAILED, "bridge"),
            completeness.methods["$prefix\$1.apply(Ljava/lang/Object;)Ljava/lang/Object;"])
        assertEquals(AnalysisStatus.ANALYZED, completeness.classStatus("$prefix\$1"))
    }

    @Test
    fun `an extension sees the methods the graph pass built and converts nothing again`() {
        val conversions = sortedMapOf<String, Int>()
        val location = ApkAnalysisInputLocation(apk, AndroidVersionInfo(apk, sdk.resolve("platforms").toString()),
            listOf(BodyInterceptor { body, _ -> conversions.merge(body.methodSignature.toString(), 1, Int::plus) }))
        val view = JavaView(location)
        val seen = mutableMapOf<String, Int>()
        val extension = object : GraphiteExtension {
            override fun visit(sootClass: SootClass, context: GraphiteContext) {
                seen[sootClass.type.fullyQualifiedName] = sootClass.methods.filter { it.hasBody() }.sumOf { it.body.stmts.size }
            }
        }
        SootUpAdapter(view, LoaderConfig(includePackages = listOf(prefix)), extensions = listOf(extension),
            inputLocationSources = mapOf(location to apk.fileName.toString())).buildGraph()
        assertTrue(seen.getValue(prefix) > 0, "bodies included")
        assertTrue("$prefix\$1" in seen)
        assertEquals(emptyMap(), conversions.filterValues { it != 1 }, "each body converted once, extensions included")
    }

    @Test
    fun `a failure that is not one method's propagates`() {
        assertEquals(1, catchingMethodFailure({ throw IllegalStateException("state") }) { 1 })
        assertFailsWith<UnsupportedOperationException> {
            catchingMethodFailure({ throw UnsupportedOperationException("unexpected") }) { 1 }
        }
    }

    private fun returnedConstants(graph: Graph, method: String): List<Any?> {
        val returned = graph.nodes<ReturnNode>().single { it.method.name == method && it.method.declaringClass.className == prefix }
        return DataFlowAnalysis(graph).backwardSlice(returned.id).constants().map { it.value }.distinct().sortedBy { it.toString() }
    }
}
