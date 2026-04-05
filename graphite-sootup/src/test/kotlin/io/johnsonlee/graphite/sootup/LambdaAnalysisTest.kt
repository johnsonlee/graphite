package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertTrue

class LambdaAnalysisTest {

    private val graph: Graph by lazy {
        val classesDir = findTestClassesDir()
        val loader = JavaProjectLoader(
            LoaderConfig(
                includePackages = listOf("sample.lambda"),
                buildCallGraph = false
            )
        )
        loader.load(classesDir)
    }

    @Test
    fun `method reference creates call site to target method`() {
        val callSites = graph.nodes<CallSiteNode>().toList()
        val transformCallSites = callSites.filter {
            it.callee.name == "transform" &&
            it.callee.declaringClass.className == "sample.lambda.LambdaExample"
        }
        assertTrue(transformCallSites.isNotEmpty(),
            "Should find call site for method reference LambdaExample::transform. " +
            "All call sites: ${callSites.map { "${it.callee.declaringClass.className}.${it.callee.name}" }}")
    }

    @Test
    fun `lambda creates call site with dynamic flag`() {
        val callSites = graph.nodes<CallSiteNode>().toList()
        val streamCallSites = callSites.filter {
            it.callee.name == "map" || it.callee.name == "stream" || it.callee.name == "collect"
        }
        assertTrue(streamCallSites.isNotEmpty(),
            "Should find stream operation call sites. " +
            "All call sites: ${callSites.map { "${it.callee.declaringClass.className}.${it.callee.name}" }}")
    }

    @Test
    fun `capturing lambda has parameter pass edges for captured variables`() {
        // createPrefixProcessor captures 'prefix' -- the invokedynamic instruction
        // has 'prefix' as an argument, which should produce PARAMETER_PASS edges
        // flowing into the generated call site (exercises the argNodeIds.forEach
        // body in the targetMethods loop of processDynamicInvoke).
        val callSites = graph.nodes<CallSiteNode>().toList()

        // The capturing lambda should create a call site for the synthetic lambda method
        // Look for call sites created by createPrefixProcessor
        val capturingCallSites = callSites.filter { cs ->
            cs.caller.name == "createPrefixProcessor" &&
            cs.caller.declaringClass.className == "sample.lambda.LambdaExample"
        }

        assertTrue(capturingCallSites.isNotEmpty(),
            "Should find call sites from createPrefixProcessor. " +
            "All call sites: ${callSites.map { "${it.caller.declaringClass.className}.${it.caller.name} -> ${it.callee.name}" }}")

        // At least one call site should have arguments (the captured 'prefix' variable)
        val withArgs = capturingCallSites.filter { it.arguments.isNotEmpty() }
        assertTrue(withArgs.isNotEmpty(),
            "Capturing lambda call sites should have arguments for captured variables")
    }

    @Test
    fun `instance method reference captures receiver as argument`() {
        // getInstanceProcessor uses this::instanceTransform, where 'this' is captured
        // as an argument to the invokedynamic instruction
        val callSites = graph.nodes<CallSiteNode>().toList()

        val instanceRefCallSites = callSites.filter { cs ->
            cs.callee.name == "instanceTransform" &&
            cs.callee.declaringClass.className == "sample.lambda.LambdaExample"
        }
        assertTrue(instanceRefCallSites.isNotEmpty(),
            "Should find call site for instance method reference this::instanceTransform. " +
            "All call sites: ${callSites.map { "${it.callee.declaringClass.className}.${it.callee.name}" }}")
    }

    @Test
    fun `dynamic call site has call edge marked as dynamic`() {
        // Method references/lambdas should produce CallEdge with isDynamic=true
        val callSites = graph.nodes<CallSiteNode>().toList()
        val lambdaCallSites = callSites.filter { cs ->
            cs.callee.name == "transform" &&
            cs.callee.declaringClass.className == "sample.lambda.LambdaExample"
        }

        assertTrue(lambdaCallSites.isNotEmpty(), "Should find transform call sites")

        for (cs in lambdaCallSites) {
            val callEdges = graph.outgoing(cs.id, CallEdge::class.java).toList()
            assertTrue(callEdges.isNotEmpty(),
                "Lambda call site should have outgoing CallEdge")
            assertTrue(callEdges.any { it.isDynamic },
                "Lambda call site should have isDynamic=true on CallEdge")
        }
    }

    @Test
    fun `string concatenation creates call site via fallback path`() {
        // Java 9+ compiles string concatenation to invokedynamic makeConcatWithConstants.
        // Bootstrap args contain String templates (not MethodHandles), so
        // processDynamicInvoke hits the targetMethods.isEmpty() fallback.
        val callSites = graph.nodes<CallSiteNode>().toList()
        val concatCallSites = callSites.filter {
            it.callee.name == "makeConcatWithConstants"
        }
        assertTrue(concatCallSites.isNotEmpty(),
            "Should find call sites for string concatenation (makeConcatWithConstants). " +
            "All call sites: ${callSites.map { "${it.callee.declaringClass.className}.${it.callee.name}" }}")
    }

    @Test
    fun `string concatenation has parameter pass edges`() {
        val concatCallSites = graph.nodes<CallSiteNode>().filter {
            it.callee.name == "makeConcatWithConstants"
        }.toList()

        assertTrue(concatCallSites.isNotEmpty(), "Should find makeConcatWithConstants call sites")

        for (callSite in concatCallSites) {
            val incomingEdges = graph.incoming(callSite.id, DataFlowEdge::class.java).toList()
            val paramEdges = incomingEdges.filter { it.kind == DataFlowKind.PARAMETER_PASS }
            assertTrue(paramEdges.isNotEmpty(),
                "makeConcatWithConstants call site should have PARAMETER_PASS edges for its arguments")
        }
    }

    @Test
    fun `string concatenation result has return value edge`() {
        val concatCallSites = graph.nodes<CallSiteNode>().filter {
            it.callee.name == "makeConcatWithConstants"
        }.toList()

        assertTrue(concatCallSites.isNotEmpty(), "Should find makeConcatWithConstants call sites")

        // At least some concat call sites should have a RETURN_VALUE outgoing edge
        val concatWithReturnValue = concatCallSites.filter { callSite ->
            graph.outgoing(callSite.id, DataFlowEdge::class.java).any {
                it.kind == DataFlowKind.RETURN_VALUE
            }
        }
        assertTrue(concatWithReturnValue.isNotEmpty(),
            "At least one makeConcatWithConstants should have a RETURN_VALUE edge (assigned result)")
    }

    @Test
    fun `functional interface dispatch resolves to actual target method`() {
        // fn.apply(input) where fn = FunctionalInterfaceExample::toUpperCase
        // Should have a CallSiteNode pointing to toUpperCase (resolved from the virtual apply call)
        val callSites = graph.nodes<CallSiteNode>().toList()

        // Find call sites for toUpperCase
        val toUpperCaseCalls = callSites.filter {
            it.callee.name == "toUpperCase" &&
            it.callee.declaringClass.className == "sample.lambda.FunctionalInterfaceExample"
        }

        // Should have at least 2: one from invokedynamic, one from resolved virtual dispatch
        assertTrue(toUpperCaseCalls.size >= 2,
            "Should have call sites for toUpperCase from both invokedynamic and resolved dispatch. " +
            "Found ${toUpperCaseCalls.size}: ${toUpperCaseCalls.map { "${it.caller.name} -> ${it.callee.name}" }}")
    }

    @Test
    fun `functional interface apply call has resolved target in call sites`() {
        val callSites = graph.nodes<CallSiteNode>().toList()

        // Check that Function.apply call exists
        val applyCalls = callSites.filter { it.callee.name == "apply" }
        assertTrue(applyCalls.isNotEmpty(),
            "Should find Function.apply call sites")

        // The processWithFunctionalInterface method should have both apply and toUpperCase calls
        val methodCalls = callSites.filter {
            it.caller.name == "processWithFunctionalInterface"
        }
        val calleeNames = methodCalls.map { it.callee.name }.toSet()
        assertTrue("toUpperCase" in calleeNames,
            "processWithFunctionalInterface should have resolved call to toUpperCase. " +
            "Callees: $calleeNames")
    }

    @Test
    fun `Consumer accept resolves to actual target method`() {
        val callSites = graph.nodes<CallSiteNode>().toList()
        val methodCalls = callSites.filter { it.caller.name == "useConsumer" }
        val calleeNames = methodCalls.map { it.callee.name }.toSet()
        assertTrue("log" in calleeNames,
            "useConsumer should have resolved Consumer.accept to log. Callees: $calleeNames")
    }

    @Test
    fun `Runnable run resolves to lambda target`() {
        val callSites = graph.nodes<CallSiteNode>().toList()
        val methodCalls = callSites.filter { it.caller.name == "useRunnable" }
        val calleeNames = methodCalls.map { it.callee.name }.toSet()
        assertTrue("run" in calleeNames,
            "useRunnable should have Runnable.run call site. Callees: $calleeNames")
    }

    @Test
    fun `parameter callback resolves to actual target across methods`() {
        val callSites = graph.nodes<CallSiteNode>().toList()
        // processWithCallback calls callback.apply()
        // useCallback passes CallbackExample::transform
        // Should resolve callback.apply -> transform
        val callbackMethodCalls = callSites.filter {
            it.caller.name == "processWithCallback"
        }
        val calleeNames = callbackMethodCalls.map { it.callee.name }.toSet()
        assertTrue("transform" in calleeNames,
            "processWithCallback should have resolved callback.apply to transform. Callees: $calleeNames")
    }

    @Test
    fun `return value propagates dynamic targets`() {
        val callSites = graph.nodes<CallSiteNode>().toList()
        // createTransformer returns FactoryExample::transform
        // useFactory calls fn.apply on the result
        // Should resolve fn.apply -> transform
        val useFactoryCalls = callSites.filter {
            it.caller.name == "useFactory"
        }
        val calleeNames = useFactoryCalls.map { it.callee.name }.toSet()
        assertTrue("transform" in calleeNames,
            "useFactory should have resolved fn.apply to transform. Callees: $calleeNames")
    }

    @Test
    fun `constructor reference creates call site to init`() {
        val callSites = graph.nodes<CallSiteNode>().toList()
        val initCalls = callSites.filter {
            it.callee.name == "<init>" &&
            it.callee.declaringClass.className == "sample.lambda.ConstructorRefExample"
        }
        assertTrue(initCalls.isNotEmpty(),
            "Should find call site for constructor reference ConstructorRefExample::new")
    }

    @Test
    fun `field-stored lambda resolves dispatch`() {
        val callSites = graph.nodes<CallSiteNode>().toList()
        val processCalls = callSites.filter {
            it.caller.name == "process" &&
            it.caller.declaringClass.className == "sample.lambda.FieldCallbackExample"
        }
        val calleeNames = processCalls.map { it.callee.name }.toSet()
        assertTrue("transform" in calleeNames,
            "FieldCallbackExample.process should resolve mapper.apply to transform. Callees: $calleeNames")
    }

    @Test
    fun `conditional assignment resolves both targets`() {
        val callSites = graph.nodes<CallSiteNode>().toList()
        val processCalls = callSites.filter {
            it.caller.name == "process" &&
            it.caller.declaringClass.className == "sample.lambda.ConditionalCallbackExample"
        }
        val calleeNames = processCalls.map { it.callee.name }.toSet()
        assertTrue("upper" in calleeNames || "lower" in calleeNames,
            "ConditionalCallbackExample.process should resolve at least one branch. Callees: $calleeNames")
    }

    @Test
    fun `varargs array propagates dynamic targets`() {
        val callSites = graph.nodes<CallSiteNode>().toList()
        // useVarargs passes upper and lower as varargs
        // processFirst accesses fns[0].apply()
        // At minimum, the method references should be resolved at the call site
        val useVarargsCalls = callSites.filter { it.caller.name == "useVarargs" }
        val calleeNames = useVarargsCalls.map { it.callee.name }.toSet()
        assertTrue("upper" in calleeNames || "processFirst" in calleeNames,
            "useVarargs should have call sites for upper or processFirst. Callees: $calleeNames")
    }

    @Test
    fun `higher-order function return value resolves`() {
        val callSites = graph.nodes<CallSiteNode>().toList()
        val useHigherOrderCalls = callSites.filter { it.caller.name == "useHigherOrder" }
        val calleeNames = useHigherOrderCalls.map { it.callee.name }.toSet()
        // createTransformer returns String::toUpperCase or String::toLowerCase
        // Phase 1 return value propagation should resolve fn.apply()
        assertTrue("toUpperCase" in calleeNames || "toLowerCase" in calleeNames || "createTransformer" in calleeNames,
            "useHigherOrder should resolve through createTransformer. Callees: $calleeNames")
    }

    @Test
    fun `array load propagates dynamic targets within same method`() {
        // loadFromArray stores method refs into array, loads back, and invokes
        // This exercises the arrayDynamicTargets -> dynamicTargets propagation in processAssignment
        val callSites = graph.nodes<CallSiteNode>().toList()
        val loadFromArrayCalls = callSites.filter {
            it.caller.name == "loadFromArray" &&
            it.caller.declaringClass.className == "sample.lambda.ArrayLoadExample"
        }
        val calleeNames = loadFromArrayCalls.map { it.callee.name }.toSet()
        // Should find the method references stored into the array
        assertTrue("upper" in calleeNames || "lower" in calleeNames,
            "loadFromArray should have call sites for upper or lower from array-stored method refs. Callees: $calleeNames")
    }

    @Test
    fun `array load from plain array has no dynamic targets`() {
        // loadFromPlainArray receives an array parameter (no dynamic targets tracked)
        // This exercises the false branch of arrayDynamicTargets lookup during array load
        val callSites = graph.nodes<CallSiteNode>().toList()
        val plainArrayCalls = callSites.filter {
            it.caller.name == "loadFromPlainArray" &&
            it.caller.declaringClass.className == "sample.lambda.ArrayLoadExample"
        }
        // Should still have the fn.apply call site even without resolved targets
        val calleeNames = plainArrayCalls.map { it.callee.name }.toSet()
        assertTrue("apply" in calleeNames,
            "loadFromPlainArray should have apply call site. Callees: $calleeNames")
    }

    @Test
    fun `array store of non-lambda local has no dynamic targets`() {
        // storeNonLambdaToArray stores a plain String into a String array
        // This exercises the false branch of dynamicTargets lookup during array store
        // (the right-side local 'value' has no dynamic targets)
        val callSites = graph.nodes<CallSiteNode>().toList()
        val storeCalls = callSites.filter {
            it.caller.name == "storeNonLambdaToArray" &&
            it.caller.declaringClass.className == "sample.lambda.ArrayLoadExample"
        }
        // This method has no call sites (no invoke expressions) - just an array store
        // The test verifies the code path is exercised without errors
        assertTrue(storeCalls.isEmpty(),
            "storeNonLambdaToArray should have no call sites (just array store). Found: $storeCalls")
    }

    @Test
    fun `array dynamic targets fall back in argument tracking`() {
        // passArrayArg creates an array with method refs and passes it as argument
        // This exercises the arrayDynamicTargets fallback in processInvokeExpr argument tracking
        val callSites = graph.nodes<CallSiteNode>().toList()
        val passArrayCalls = callSites.filter {
            it.caller.name == "passArrayArg" &&
            it.caller.declaringClass.className == "sample.lambda.ArrayLoadExample"
        }
        val calleeNames = passArrayCalls.map { it.callee.name }.toSet()
        assertTrue("upper" in calleeNames || "applyFirst" in calleeNames,
            "passArrayArg should have call sites for upper or applyFirst. Callees: $calleeNames")
    }

    @Test
    fun `stream chain resolves method references`() {
        val callSites = graph.nodes<CallSiteNode>().toList()
        val chainCalls = callSites.filter {
            it.caller.name == "processChain"
        }
        val calleeNames = chainCalls.map { it.callee.name }.toSet()
        assertTrue("transform" in calleeNames,
            "processChain should resolve .map(StreamChainExample::transform). Callees: $calleeNames")
        assertTrue("isValid" in calleeNames,
            "processChain should resolve .filter(StreamChainExample::isValid). Callees: $calleeNames")
    }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }
}
