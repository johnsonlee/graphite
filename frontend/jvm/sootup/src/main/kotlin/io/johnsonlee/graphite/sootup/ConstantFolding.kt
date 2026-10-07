package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.jvmMethodDescriptor
import io.johnsonlee.graphite.input.CallSiteKey
import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.ConstantPattern
import io.johnsonlee.graphite.input.FoldOutcome
import io.johnsonlee.graphite.input.FoldReport
import io.johnsonlee.graphite.input.FoldRule
import io.johnsonlee.graphite.input.FoldSite
import io.johnsonlee.graphite.input.FoldSites
import io.johnsonlee.graphite.input.FoldedMethod
import io.johnsonlee.graphite.input.UnsupportedFoldSite
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import sootup.core.jimple.common.Immediate
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.constant.DoubleConstant
import sootup.core.jimple.common.constant.FloatConstant
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.constant.LongConstant
import sootup.core.jimple.common.constant.NullConstant
import sootup.core.jimple.common.constant.StringConstant
import sootup.core.jimple.common.constant.Constant
import sootup.core.jimple.common.expr.AbstractBinopExpr
import sootup.core.jimple.common.expr.AbstractConditionExpr
import sootup.core.jimple.common.expr.JCmpExpr
import sootup.core.jimple.common.expr.JCmpgExpr
import sootup.core.jimple.common.expr.JCmplExpr
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr
import sootup.core.jimple.common.expr.AbstractInvokeExpr
import sootup.core.jimple.common.expr.JCastExpr
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.jimple.common.ref.JFieldRef
import sootup.core.jimple.common.ref.JStaticFieldRef
import sootup.core.jimple.common.ref.JThisRef
import sootup.core.jimple.common.expr.JNewExpr
import sootup.core.jimple.common.expr.JSpecialInvokeExpr
import sootup.core.jimple.common.expr.JEqExpr
import sootup.core.jimple.common.expr.JGeExpr
import sootup.core.jimple.common.expr.JGtExpr
import sootup.core.jimple.common.expr.JLeExpr
import sootup.core.jimple.common.expr.JLtExpr
import sootup.core.jimple.common.expr.JNeExpr
import sootup.core.jimple.common.stmt.BranchingStmt
import sootup.core.jimple.common.stmt.FallsThroughStmt
import sootup.core.jimple.common.stmt.JIfStmt
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JIdentityStmt
import sootup.core.jimple.common.ref.JParameterRef
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.core.jimple.common.stmt.JNopStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.graph.MutableControlFlowGraph
import sootup.core.jimple.javabytecode.stmt.JSwitchStmt
import sootup.core.model.Body
import sootup.core.model.FieldModifier
import sootup.core.signatures.FieldSignature
import sootup.core.signatures.MethodSignature
import sootup.core.signatures.MethodSubSignature
import sootup.core.interceptor.BodyInterceptor
import sootup.core.types.ArrayType
import sootup.core.types.ClassType
import sootup.core.types.PrimitiveType
import sootup.core.types.Type
import sootup.core.types.VoidType
import sootup.core.views.View
import sootup.interceptors.DeadAssignmentEliminator
import sootup.interceptors.NopEliminator

private const val STRING_CLASS = "java.lang.String"
private const val OBJECT_CLASS = "java.lang.Object"
private const val BOOLEAN_BOX = "java.lang.Boolean"
private const val VALUE_OF = "valueOf"
private const val CONSTRUCTOR = "<init>"
private const val COMPUTED_AT_RUN_TIME = "computed at run time"
private const val SELECT_THE_CALL = "select the call that passes the constant"
private const val MAX_CASTS = 8
private const val BOOLEAN_TYPE = "boolean"
private const val INT_TYPE = "int"
private const val LONG_TYPE = "long"
private const val FLOAT_TYPE = "float"
private const val DOUBLE_TYPE = "double"

/** Each primitive type's box and the method that unboxes it, as `javac` and `kotlinc` emit them. */
private val BOXES: Map<String, Pair<String, String>> = mapOf(
    BOOLEAN_TYPE to (BOOLEAN_BOX to "booleanValue"),
    "byte" to ("java.lang.Byte" to "byteValue"),
    "short" to ("java.lang.Short" to "shortValue"),
    "char" to ("java.lang.Character" to "charValue"),
    INT_TYPE to ("java.lang.Integer" to "intValue"),
    LONG_TYPE to ("java.lang.Long" to "longValue"),
    FLOAT_TYPE to ("java.lang.Float" to "floatValue"),
    DOUBLE_TYPE to ("java.lang.Double" to "doubleValue")
)
private const val MAX_HINTS = 8
private const val CALLEE_CLASS = "callee_class"
private const val CALLEE_NAME = "callee_name"
private const val CALLEE_SIGNATURE = "callee_signature"
private const val CALLER_SIGNATURE = "caller_signature"

/**
 * Folds the calls the [FoldRule]s name while SootUp builds each body, before any graph node
 * exists, and accounts for what every rule did. A [ConstantFold] is matched the way its Cypher
 * pattern would be: the call site's properties against the rule's, and the constant reaching
 * each listed argument against the constant pattern the rule names for it. A [FoldSites] names
 * its calls by key (`caller_signature`, `callee_signature`, `ordinal`), the ordinal being the
 * call's rank among the caller's calls of that callee in statement order, computed here over
 * the body before anything in it changes, exactly as the adapter numbers the `CallSite` nodes of
 * a body no rule touched. The callee is the class that declares the method, as the graph's
 * `CallSite` names it, not the receiver class the bytecode spells: `Sub.enabled()` for a method
 * `Base` declares is `callee_class: Base`.
 *
 * The chain this installs ([bodyInterceptors]) keeps whatever interceptors the input location
 * runs without folding (none, for a jar or a class directory; SootUp's `Default` chain for the
 * Android platform jar) and adds, after them, the fold pass and, for the bodies in which a rule
 * matched and only those, the passes that let the constant take effect: propagation into the
 * branch that tests it, folding of that branch, removal of the side it rules out, of the
 * assignments that only fed the call and of the `nop`s left where a result-less call stood. A
 * body no rule touches goes through exactly the chain it would without rules, so folding
 * leaves every other method as it was.
 *
 * A body may be resolved more than once in a build (the adapter streams detached copies, an
 * enum initialiser is read again for its constants, a call-graph algorithm resolves the view's
 * own copy), and the chain runs on each resolution. What a rule did is therefore kept per
 * calling method and replaced on every resolution, never added up, so the report counts every
 * call once however often its body was built.
 *
 * Branches are folded here rather than by SootUp's `ConditionalBranchFolder`: 2.0.0 kept the
 * side a constant condition rules out, and 3.0.1, which fixed that, still prunes the join
 * point behind the dropped side whenever the `if` was its only other predecessor, so
 * `if (gate) work(); tail();` loses `tail()` and fails SootUp's own validation (see
 * `ConditionalBranchFolderTest`).
 */
internal class ConstantFolding(private val folds: List<FoldRule>) {

    /** What the fold pass recorded for one body, consumed by the accounting pass at the chain's end. */
    private class Marked(val statementsBefore: Int, val calls: IntArray)

    /** The rendered declaring signature of each callee, shared by every body the pass numbers. */
    private val renderedDeclaring = ConcurrentHashMap<MethodSignature, String>()

    /** What one resolution of one body found for the rules, keyed by rule index; replaces the previous resolution's. */
    private class BodyRun(size: Int) {
        val calls = IntArray(size)
        val keys = List(size) { ArrayList<CallSiteKey>() }
        val unsupported = List(size) { ArrayList<UnsupportedFoldSite>() }
        val misses = List(size) { LinkedHashMap<String, Int>() }

        fun miss(index: Int, hint: String) {
            val recorded = misses[index]
            if (hint in recorded || recorded.size < MAX_HINTS) recorded[hint] = (recorded[hint] ?: 0) + 1
        }
    }

    private val marked: MutableMap<Body.BodyBuilder, Marked> = Collections.synchronizedMap(IdentityHashMap())

    /** Per rule, per calling method, what the method's latest resolution folded. */
    private val sites = List(folds.size) { Collections.synchronizedMap(LinkedHashMap<String, FoldSite>()) }

    /** Per calling method, its statements before and after its latest resolution's folds: once, whatever the number of rules. */
    private val methods = Collections.synchronizedMap(LinkedHashMap<String, FoldedMethod>())

    /** Per rule, per calling method, the keys of the calls its latest resolution folded. */
    private val folded = List(folds.size) { Collections.synchronizedMap(LinkedHashMap<String, List<CallSiteKey>>()) }

    /** Per rule, per calling method, the calls its latest resolution matched but could not fold. */
    private val unsupported = List(folds.size) { Collections.synchronizedMap(LinkedHashMap<String, List<UnsupportedFoldSite>>()) }

    /** Per rule, per calling method, the near misses its latest resolution saw with how many calls each. */
    private val misses = List(folds.size) { Collections.synchronizedMap(LinkedHashMap<String, Map<String, Int>>()) }

    private val declaringSignatures = ConcurrentHashMap<MethodSignature, MethodSignature>()

    /** Per method a rule folded in, the numbering of its invokes before the fold, by the statements that survive it. */
    private val preFoldOrdinals = ConcurrentHashMap<MethodSignature, MutableMap<Stmt, Int>>()

    /** Methods that share a rendered signature and descriptor with another of their class, by signature. */
    private val collidingOverloads = ConcurrentHashMap<MethodSignature, List<String>>()
    private val enumConstants = HashMap<Pair<ClassType, String>, Boolean>()

    /**
     * [defaults], the chain the input location runs without folding, followed by the fold pass
     * and the gated clean-up passes. The fold runs after that whole chain, so every other method
     * sees exactly it, and [FoldBranches] propagates what the fold created on its own instead of
     * relying on SootUp's `ConstantPropagatorAndFolder`, which that chain may not contain.
     */
    fun bodyInterceptors(defaults: List<BodyInterceptor>): List<BodyInterceptor> {
        val chain = ArrayList(defaults)
        chain.add(Fold())
        listOf(FoldBranches(::isEnumConstant), DeadAssignmentEliminator(), NopEliminator()).mapTo(chain, ::Gated)
        chain.add(Accounting())
        return chain
    }

    /**
     * The ordinals of [signature]'s invokes as they were before a rule folded in its body, keyed
     * by the statements of its latest resolution, or `null` when no rule folded there. The adapter
     * numbers a folded body with these, so a call that survives the fold keeps the ordinal it had
     * in the bytecode (a folded call leaves a gap) and a key read off the folded graph names the
     * same call as one read off the graph built without rules.
     */
    fun ordinalsBeforeFolding(signature: MethodSignature): Map<Stmt, Int>? = preFoldOrdinals[signature]

    fun report(): FoldReport = FoldReport(
        folds.indices.map { index ->
            FoldOutcome(
                folds[index],
                synchronized(sites[index]) { sites[index].values.toList() },
                synchronized(unsupported[index]) { unsupported[index].values.flatten() },
                hints(index)
            )
        },
        synchronized(methods) { methods.values.toList() }
    )

    /** The near misses of a `match` rule with their call counts; the selected keys a `select` rule never met. */
    private fun hints(index: Int): List<String> {
        val fold = folds[index]
        val hints = LinkedHashMap<String, Int>()
        if (fold is FoldSites) {
            val seen = synchronized(folded[index]) { folded[index].values.flatten().toSet() }
            fold.selected.orEmpty().filter { it !in seen }.take(MAX_HINTS).forEach { key ->
                hints["selected call site $key is not in this build"] = 0
            }
        }
        synchronized(misses[index]) {
            misses[index].values.forEach { byHint ->
                byHint.forEach { (hint, calls) ->
                    if (hint in hints || hints.size < MAX_HINTS) hints[hint] = (hints[hint] ?: 0) + calls
                }
            }
        }
        return hints.map { (hint, calls) -> if (calls == 0) hint else "$hint ($calls call(s))" }
    }

    /** Replace every matching call expression by its constant and mark the body for the clean-up passes. */
    private inner class Fold : BodyInterceptor {
        override fun interceptBody(builder: Body.BodyBuilder, view: View) {
            val run = BodyRun(folds.size)
            val statementsBefore = builder.controlFlowGraph.nodes.size
            val caller = render(builder.methodSignature)
            val callerDescriptor = descriptorOf(builder.methodSignature)
            // What a method's resolution recorded is kept under its full identity: a bridge and
            // the covariant method it forwards to share a signature, not a descriptor, and
            // overloads that differ in array dimensions share both, so the Jimple signature it is.
            val identity = builder.methodSignature.toString()
            // The same numbering the adapter gives the graph's `CallSite.ordinal`, computed by the
            // one function from the unfiltered body (`callOrdinals`), so a key read off a graph
            // names the same invoke here even where the graph shows no call site for a call.
            val ordinals = callOrdinals(builder.stmts) { signature ->
                renderedDeclaring.getOrPut(signature) { render(declaringSignature(view, signature)) }
            }
            for (stmt in builder.stmts) {
                val invoke = invokeExprOf(stmt) ?: continue
                val callee = declaringSignature(view, invoke.methodSignature)
                val key = CallSiteKey(caller, callerDescriptor, render(callee), descriptorOf(callee), ordinals.getValue(stmt))
                val index = select(run, builder, invoke, key, view, callee)
                val resultType = index?.let { (folds[it] as? FoldSites)?.resultTypes?.get(key) }
                if (index != null && foldCall(run, builder, stmt, invoke, index, view, resultType)) {
                    run.calls[index]++
                    run.keys[index].add(key)
                }
            }
            for (index in folds.indices) {
                if (run.keys[index].isEmpty()) {
                    folded[index].remove(identity)
                } else {
                    folded[index][identity] = run.keys[index]
                }
                if (run.unsupported[index].isEmpty()) {
                    unsupported[index].remove(identity)
                } else {
                    unsupported[index][identity] = run.unsupported[index]
                }
                if (run.misses[index].isEmpty()) {
                    misses[index].remove(identity)
                } else {
                    misses[index][identity] = run.misses[index]
                }
                if (run.calls[index] == 0) sites[index].remove(identity)
            }
            if (run.calls.all { it == 0 }) methods.remove(identity)
            if (run.calls.any { it > 0 }) {
                // A folded call throws nothing: a handler only it could reach has lost its last
                // predecessor and goes now, before SootUp validates the graph.
                removeUnreachable(builder.controlFlowGraph)
                marked[builder] = Marked(statementsBefore, run.calls)
                preFoldOrdinals[builder.methodSignature] = IdentityHashMap(ordinals)
            }
        }

        /**
         * The first rule whose call-site properties and argument constants both match [invoke],
         * or `null`. A rule naming an argument the call does not have never matches. A call no
         * rule folds although a rule's call site matched and an argument it names is not a
         * constant is recorded once as unsupported, under that rule.
         */
        @Suppress("LongParameterList")
        private fun select(
            run: BodyRun,
            builder: Body.BodyBuilder,
            invoke: AbstractInvokeExpr,
            key: CallSiteKey,
            view: View,
            callee: MethodSignature
        ): Int? {
            val properties = callSiteProperties(callee, builder.methodSignature, key)
            var unresolved: Pair<Int, String>? = null
            for ((index, fold) in folds.withIndex()) {
                when (val outcome = test(run, index, fold, builder, invoke, key, properties, view)) {
                    Outcome.Matched -> return index
                    is Outcome.Unresolved -> if (unresolved == null) unresolved = index to outcome.reason
                    Outcome.Skipped -> Unit
                }
            }
            unresolved?.let { (index, reason) -> run.unsupported[index].add(UnsupportedFoldSite(key.caller, reason)) }
            return null
        }

        /** [fold] against one call: matched, skipped (recording the near miss), or skipped because an argument is not a constant. */
        @Suppress("LongParameterList", "ReturnCount")
        private fun test(
            run: BodyRun,
            index: Int,
            fold: FoldRule,
            builder: Body.BodyBuilder,
            invoke: AbstractInvokeExpr,
            key: CallSiteKey,
            properties: Map<String, String>,
            view: View
        ): Outcome {
            if (fold is FoldSites) {
                if (fold.selected?.contains(key) != true) return Outcome.Skipped
                ambiguity(view, builder.methodSignature, "caller")?.let { return Outcome.Unresolved(it) }
                ambiguity(view, declaringSignature(view, invoke.methodSignature), "callee")?.let { return Outcome.Unresolved(it) }
                return testSelected(run, index, fold, builder, invoke, properties, view)
            }
            fold as ConstantFold
            val mismatch = fold.mismatch(properties)
            return when {
                mismatch != null -> Outcome.Skipped.also { recordSiteMiss(run, index, fold, properties, mismatch) }
                fold.arguments.keys.any { it >= invoke.args.size } -> Outcome.Skipped
                else -> testArguments(run, index, fold, builder, invoke, properties, view)
            }
        }

        @Suppress("LongParameterList", "ReturnCount")
        private fun testArguments(
            run: BodyRun,
            index: Int,
            fold: ConstantFold,
            builder: Body.BodyBuilder,
            invoke: AbstractInvokeExpr,
            properties: Map<String, String>,
            view: View
        ): Outcome {
            val resolved = fold.arguments.keys.associateWith { position -> resolveArgument(builder, invoke, position, view) }
            val argument = resolved.entries.firstOrNull { it.value == null }?.key
            val differing = fold.arguments.entries.firstOrNull { (position, pattern) ->
                resolved[position]?.let { !pattern.matches(it.first, it.second) } ?: false
            }?.key
            if (argument == null && differing == null) return Outcome.Matched
            val position = differing ?: argument!!
            val seen = resolved[position]?.let { (label, props) -> ConstantPattern(label, props).description } ?: "not a constant"
            run.miss(index, "${properties[CALLEE_SIGNATURE]} is called with argument $position = $seen")
            if (argument == null) return Outcome.Skipped
            return Outcome.Unresolved("argument $argument of ${properties[CALLEE_SIGNATURE]} is not a constant")
        }

        /**
         * A selected call is the rule's to fold where it stands: a bare `select` is an assumption
         * about the call site, whoever calls the method around it. `args` and `receiver_args`
         * are extra match conditions: each named argument (or argument of the call that produced
         * the receiver) must be that constant here, and one that cannot be shown to be is
         * reported and left alone. A bare selection whose argument or receiver is a parameter of
         * the caller folds for every caller of that method, which the report says.
         */
        @Suppress("ReturnCount")
        private fun testSelected(
            run: BodyRun,
            index: Int,
            fold: FoldSites,
            builder: Body.BodyBuilder,
            invoke: AbstractInvokeExpr,
            properties: Map<String, String>,
            view: View
        ): Outcome {
            val callee = properties.getValue(CALLEE_SIGNATURE)
            if (fold.arguments.isEmpty() && fold.receiverArguments.isEmpty()) {
                sharedThrough(builder, invoke, callee)?.let { run.miss(index, it) }
            }
            for (position in fold.arguments.keys.sorted()) {
                if (position >= invoke.args.size) {
                    return Outcome.Unresolved("argument $position of $callee: the call has ${invoke.args.size} argument(s)")
                }
                val resolved = resolveArgument(builder, invoke, position, view)
                    ?: return Outcome.Unresolved(
                        "argument $position of $callee is not a constant here: it is ${argumentOrigin(builder, invoke, position)}; " +
                            SELECT_THE_CALL
                    )
                val pattern = fold.arguments[position] ?: continue
                if (!pattern.matches(resolved.first, resolved.second)) {
                    return Outcome.Unresolved("argument $position of $callee is ${shorthand(resolved)} here, not ${pattern.description}")
                }
            }
            if (fold.receiverArguments.isNotEmpty()) {
                val instance = invoke as? AbstractInstanceInvokeExpr
                    ?: return Outcome.Unresolved("$callee is a static call: it has no receiver for 'receiver_args' to match")
                return testReceiver(fold, builder, instance, callee, view)
            }
            return Outcome.Matched
        }

        /**
         * Why a bare selection of this call reaches beyond its caller, or `null`: an argument, or
         * the receiver, that is a parameter of the caller makes the fold apply to every caller of
         * the method, whatever key they pass.
         */
        private fun sharedThrough(builder: Body.BodyBuilder, invoke: AbstractInvokeExpr, callee: String): String? {
            val caller = render(builder.methodSignature)
            val parameters = builder.controlFlowGraph.nodes.filterIsInstance<JIdentityStmt>()
                .filter { it.rightOp is JParameterRef }.associate { it.leftOp to (it.rightOp as JParameterRef).index }
            invoke.args.forEachIndexed { position, argument ->
                parameters[argument]?.let {
                    return "$callee folds for every caller of $caller: argument $position is parameter $it of $caller"
                }
            }
            val receiver = (invoke as? AbstractInstanceInvokeExpr)?.base
            return parameters[receiver]?.let { "$callee folds for every caller of $caller: the receiver is parameter $it of $caller" }
        }

        /**
         * A key can travel in the receiver (`Box a = boxed(1234); if (a.isOn())`): the call that
         * produced the receiver (`boxed(1234)`, or the constructor call of a `new`) is held to
         * the rule's `receiver_args`. A receiver that is `this`, a parameter, a field or the
         * result of a call whose named argument is not that constant is reported.
         */
        @Suppress("ReturnCount")
        private fun testReceiver(
            fold: FoldSites,
            builder: Body.BodyBuilder,
            invoke: AbstractInstanceInvokeExpr,
            callee: String,
            view: View
        ): Outcome {
            val named = fold.receiverArguments
            val receiver = invoke.base as? Local ?: return Outcome.Unresolved("the receiver of $callee is not a local")
            val source = when (val origin = receiverSource(builder, receiver)) {
                is ReceiverSource.This -> return Outcome.Unresolved("the receiver of $callee is this, not a call's result")
                is ReceiverSource.Other ->
                    return Outcome.Unresolved("the receiver of $callee is ${origin.what}; $SELECT_THE_CALL")
                is ReceiverSource.Call -> origin.invoke
            }
            val producer = "the receiver of $callee comes from ${render(declaringSignature(view, source.methodSignature))}"
            for (position in named.keys.sorted()) {
                if (position >= source.args.size) return Outcome.Unresolved("$producer, which has ${source.args.size} argument(s)")
                val resolved = resolveArgument(builder, source, position, view)
                    ?: return Outcome.Unresolved(
                        "$producer, whose argument $position is not a constant here: " +
                            "it is ${argumentOrigin(builder, source, position)}; $SELECT_THE_CALL"
                    )
                val pattern = named.getValue(position)
                if (!pattern.matches(resolved.first, resolved.second)) {
                    return Outcome.Unresolved(
                        "$producer, whose argument $position is ${shorthand(resolved)} here, not ${pattern.description}"
                    )
                }
            }
            return Outcome.Matched
        }

        @Suppress("ReturnCount")
        private fun receiverSource(builder: Body.BodyBuilder, receiver: Local): ReceiverSource {
            val statements = builder.controlFlowGraph.nodes
            val definitions = statements.filter { it.def.orElse(null) == receiver }
            val single = definitions.singleOrNull() ?: return ReceiverSource.Other("a local with ${definitions.size} definitions")
            val value = (single as? JAssignStmt)?.rightOp ?: (single as? JIdentityStmt)?.rightOp
            return when (value) {
                is JThisRef -> ReceiverSource.This
                is JParameterRef -> ReceiverSource.Other("parameter ${value.index} of ${render(builder.methodSignature)}")
                is AbstractInvokeExpr -> ReceiverSource.Call(value)
                is JNewExpr -> statements.asSequence().mapNotNull { invokeExprOf(it) as? JSpecialInvokeExpr }
                    .firstOrNull { it.base == receiver && it.methodSignature.name == CONSTRUCTOR }
                    ?.let { ReceiverSource.Call(it) } ?: ReceiverSource.Other("an allocation without a constructor call")
                is JFieldRef -> ReceiverSource.Other("a read of field ${value.fieldSignature}")
                else -> ReceiverSource.Other(COMPUTED_AT_RUN_TIME)
            }
        }

        /** A constant as a rule would spell it: the bare value where the label adds nothing. */
        private fun shorthand(constant: Pair<String, Map<String, Any?>>): String {
            val (label, properties) = constant
            val bare = properties.keys == setOf(ConstantPattern.VALUE) && label != ConstantPattern.ENUM
            return ConstantPattern(if (bare) ConstantPattern.CONSTANT else label, properties).description
        }

        /**
         * Why a key naming [signature] as its [role] names more than one method, or `null`: the
         * graph writes an array as its base type and one `[]`, so overloads that differ only in
         * array dimensions (`run(int[])`, `run(int[][])`) share a signature and a descriptor, and
         * a key cannot tell their calls apart. Such a key folds nothing rather than both.
         */
        private fun ambiguity(view: View, signature: MethodSignature, role: String): String? {
            val others = collidingOverloads.getOrPut(signature) {
                val rendered = render(signature) to descriptorOf(signature)
                view.getClass(signature.declClassType).map { sootClass ->
                    sootClass.methods.map { it.signature }.filter { it != signature && (render(it) to descriptorOf(it)) == rendered }
                }.orElse(emptyList()).map { it.toString() }.sorted()
            }
            if (others.isEmpty()) return null
            return "the key's $role ${render(signature)} names ${others.size + 1} methods that differ only in array " +
                "dimensions, which the graph does not record: $signature and ${others.joinToString(" and ")}; not folded"
        }

        /** What an argument that is no constant is, for the report: a parameter, a call's result, or something computed. */
        @Suppress("ReturnCount")
        private fun argumentOrigin(builder: Body.BodyBuilder, invoke: AbstractInvokeExpr, position: Int): String {
            val argument = invoke.args[position] as? Local ?: return COMPUTED_AT_RUN_TIME
            val definitions = builder.controlFlowGraph.nodes.filter { it.def.orElse(null) == argument }
            val single = definitions.singleOrNull() ?: return "a local with ${definitions.size} definitions"
            return when {
                single is JIdentityStmt && single.rightOp is JParameterRef ->
                    "parameter ${(single.rightOp as JParameterRef).index} of ${render(builder.methodSignature)}"
                single is JAssignStmt && single.rightOp is AbstractInvokeExpr ->
                    "the result of a call to ${render((single.rightOp as AbstractInvokeExpr).methodSignature)}"
                else -> COMPUTED_AT_RUN_TIME
            }
        }

        /**
         * A call of the callee the rule names whose other properties do not match is a near miss
         * worth telling: the callee is named by `callee_name`, or by `callee_class` when the rule
         * has no name.
         */
        private fun recordSiteMiss(run: BodyRun, index: Int, fold: ConstantFold, properties: Map<String, String>, mismatch: String) {
            val named = fold.callSite[CALLEE_NAME]?.let { it == properties[CALLEE_NAME] }
                ?: (fold.mismatch(mapOf(CALLEE_CLASS to properties.getValue(CALLEE_CLASS))) == null)
            if (!named) return
            val callee = properties.getValue(CALLEE_SIGNATURE)
            val caller = properties.getValue(CALLER_SIGNATURE)
            run.miss(index, "$callee is called from $caller, where $mismatch is ${properties[mismatch]}")
        }

        /**
         * Replace [stmt]; `false`, with the site recorded as unsupported, when the return type
         * cannot carry the value. [resultType] is the type the value really has where the
         * return type is erased, see [FoldSites.resultTypes].
         */
        @Suppress("LongParameterList")
        private fun foldCall(
            run: BodyRun,
            builder: Body.BodyBuilder,
            stmt: Stmt,
            invoke: AbstractInvokeExpr,
            index: Int,
            view: View,
            resultType: String?
        ): Boolean {
            val replacement = replacementFor(stmt, invoke, folds[index], view, resultType)
            if (replacement == null) {
                val type = invoke.methodSignature.type.toString() + (resultType?.let { " (result type $it)" } ?: "")
                val reason = "return type $type cannot carry ${folds[index].value.description}"
                run.unsupported[index].add(UnsupportedFoldSite(render(builder.methodSignature), reason))
                return false
            }
            // A constant or a nop throws nothing: the handler the call could reach is reachable
            // only through the statements that can still throw.
            replaceWithNonThrowingStmt(builder.controlFlowGraph, stmt, replacement)
            return true
        }
    }

    /**
     * Let the folded constant take effect: a local with a single constant definition is read as
     * that constant, an `if` whose condition then compares two constants is resolved (its
     * predecessors are wired to the side the condition takes and the `if` goes), and the two
     * repeat until nothing changes, so `x = a && gate()` folds through `x` as well. The statements
     * that end up unreachable from the method's entry are removed in the same pass, because SootUp
     * validates the graph after every interceptor and a statement without a predecessor fails it.
     */
    /**
     * Replace [old] by [new] in the body, and let [new] inherit the ordinal [old] had before the
     * fold: a surviving invoke that reads a folded local is a new statement object, and the key
     * that names it must still name it.
     */
    private fun replace(builder: Body.BodyBuilder, old: Stmt, new: Stmt, throwsNothing: Boolean = false) {
        if (throwsNothing) replaceWithNonThrowingStmt(builder.controlFlowGraph, old, new)
        else builder.controlFlowGraph.replaceNode(old, new)
        preFoldOrdinals[builder.methodSignature]?.let { ordinals -> ordinals.remove(old)?.let { ordinals[new] = it } }
    }

    private inner class FoldBranches(private val isEnumConstant: (View, JStaticFieldRef) -> Boolean) : BodyInterceptor {
        override fun interceptBody(builder: Body.BodyBuilder, view: View) {
            val graph = builder.controlFlowGraph
            do {
                var callsFolded = foldUnboxing(builder)
                val propagated = propagateConstants(builder) or foldComparisons(builder)
                val enums = enumReads(builder, view)
                callsFolded = foldEnumEquals(builder, enums) || callsFolded
                val folded = foldConstantBranches(graph, enums)
                // Pruned per round: a definition on the side just removed must not keep a local
                // from being read as its one remaining constant in the next round.
                if (folded || callsFolded) removeUnreachable(graph)
            } while (callsFolded || propagated || folded)
        }

        /** The assignment that defines each local with exactly one definition, when that definition is an assignment. */
        private fun singleDefinitions(statements: List<Stmt>): Map<Local, JAssignStmt> {
            val counts = HashMap<Local, Int>()
            statements.forEach { stmt -> (stmt.def.orElse(null) as? Local)?.let { counts.merge(it, 1, Int::plus) } }
            return statements.filterIsInstance<JAssignStmt>()
                .filter { (it.leftOp as? Local)?.let { local -> counts[local] == 1 } == true }
                .associateBy { it.leftOp as Local }
        }

        /**
         * An erased call folded to a box (`Boolean.FALSE`, `Integer.valueOf(3)`) reaches its test
         * through a cast and an unboxing call: `$r = Boolean.FALSE; $z = (Boolean) $r;
         * $b = $z.booleanValue()`. The unboxing of a local whose one definition is such a box,
         * directly or through casts, becomes the constant the box holds, so the branch on `$b`
         * folds as one on a primitive call would. The unboxing call throws nothing on a box, so
         * its exceptional edges go with it.
         */
        private fun foldUnboxing(builder: Body.BodyBuilder): Boolean {
            val graph = builder.controlFlowGraph
            val statements = graph.nodes.toList()
            val definitions = singleDefinitions(statements)
            val unboxed = statements.filterIsInstance<JAssignStmt>().mapNotNull { assign ->
                val invoke = assign.rightOp as? AbstractInstanceInvokeExpr ?: return@mapNotNull null
                val box = (invoke.base as? Local)?.let { boxOf(it, definitions) } ?: return@mapNotNull null
                val signature = invoke.methodSignature
                val unboxes = invoke.args.isEmpty() && signature.declClassType.fullyQualifiedName == box.first &&
                    (box.first to signature.name) in BOXES.values
                if (unboxes) assign to box.second else null
            }
            unboxed.forEach { (assign, constant) ->
                val replacement = assign.withRValue(constant)
                replace(builder, assign, replacement, throwsNothing = true)
            }
            return unboxed.isNotEmpty()
        }

        /** The box class and the constant it holds, when the one definition of [local], through casts, is a constant box. */
        private fun boxOf(local: Local, definitions: Map<Local, JAssignStmt>): Pair<String, Constant>? {
            var value = definitions[local]?.rightOp
            var casts = 0
            while (value is JCastExpr && casts++ < MAX_CASTS) value = (value.op as? Local)?.let { definitions[it]?.rightOp }
            return when (value) {
                is JStaticFieldRef -> booleanBoxOf(value)
                is JStaticInvokeExpr -> valueOfBoxOf(value)
                else -> null
            }
        }

        /**
         * The locals whose one definition reads an enum constant, with the field read. An enum
         * constant is not a Jimple constant and cannot stand inside an `if`, but two reads of enum
         * constants compare by identity: the same constant is `==`, different ones are not, and
         * neither is `null`.
         */
        private fun enumReads(builder: Body.BodyBuilder, view: View): Map<Local, JStaticFieldRef> {
            val definitions = singleDefinitions(builder.controlFlowGraph.nodes.toList())
            val reads = HashMap<Local, JStaticFieldRef>()
            definitions.forEach { (local, assign) ->
                (assign.rightOp as? JStaticFieldRef)?.takeIf { isEnumConstant(view, it) }?.let { reads[local] = it }
            }
            // An erased call folded to an enum constant reaches its test through a cast:
            // `$r = E.A; $e = (E) $r; if $e == E.B`.
            do {
                val casts = definitions.filter { (local, assign) ->
                    local !in reads && ((assign.rightOp as? JCastExpr)?.op as? Local)?.let { it in reads } == true
                }
                casts.forEach { (local, assign) -> reads[local] = reads.getValue((assign.rightOp as JCastExpr).op as Local) }
            } while (casts.isNotEmpty())
            return reads
        }

        /**
         * Enum.equals(Object) is final and compares identity. Fold only with a known non-null
         * enum receiver and an enum constant or null argument; an unknown receiver may throw
         * or dispatch to user code. Overloads named equals have no such guarantee.
         */
        private fun foldEnumEquals(builder: Body.BodyBuilder, enums: Map<Local, JStaticFieldRef>): Boolean {
            val graph = builder.controlFlowGraph
            val folded = graph.nodes.toList().mapNotNull { stmt ->
                val invoke = invokeExprOf(stmt) as? AbstractInstanceInvokeExpr ?: return@mapNotNull null
                val signature = invoke.methodSignature
                if (signature.name != "equals" || signature.type.toString() != BOOLEAN_TYPE ||
                    signature.parameterTypes.singleOrNull()?.toString() != OBJECT_CLASS
                ) return@mapNotNull null
                val receiver = enums[invoke.base] ?: return@mapNotNull null
                val argument = invoke.args.singleOrNull() ?: return@mapNotNull null
                val other = enums[argument]
                val equal = when {
                    argument is NullConstant -> false
                    other != null -> receiver.fieldSignature == other.fieldSignature
                    else -> return@mapNotNull null
                }
                val replacement = if (stmt is JInvokeStmt) JNopStmt(stmt.positionInfo)
                else (stmt as JAssignStmt).withRValue(IntConstant.getInstance(if (equal) 1 else 0))
                stmt to replacement
            }
            folded.forEach { (stmt, replacement) ->
                replace(builder, stmt, replacement, throwsNothing = true)
            }
            return folded.isNotEmpty()
        }

        /**
         * Replace the reads of every local that has exactly one definition and that definition is
         * a constant. Statements come from the graph, not from the builder's statement list: that
         * list is a cache that does not learn about replaced nodes.
         */
        private fun propagateConstants(builder: Body.BodyBuilder): Boolean {
            val statements = builder.controlFlowGraph.nodes.toList()
            val definitions = statements.filterIsInstance<JAssignStmt>()
                .filter { it.leftOp is Local && it.rightOp is Constant }
                .groupBy { it.leftOp as Local }
                .filter { (local, stmts) -> stmts.size == 1 && statements.count { it.def.orElse(null) == local } == 1 }
            var changed = false
            for ((local, stmts) in definitions) {
                val definition = stmts.single()
                // Re-read per local: a statement that reads two folded locals was replaced by the
                // first substitution, and the graph no longer knows the object the snapshot holds.
                for (stmt in builder.controlFlowGraph.nodes.toList()) {
                    val before = if (stmt === definition) 0 else stmt.uses.count { it == local }
                    // A use the constant cannot take (the receiver of a call must stay a local)
                    // leaves the statement as it was: no replacement, no progress, or the
                    // fixpoint would revisit the same definition for ever.
                    val replaced = if (before == 0) stmt else stmt.withNewUse(local, definition.rightOp)
                    if (replaced !== stmt && replaced.uses.count { it == local } < before) {
                        replace(builder, stmt, replaced)
                        changed = true
                    }
                }
            }
            return changed
        }

        /**
         * A `long`, `float` or `double` comparison compiles to `cmp`/`cmpl`/`cmpg` into an int that
         * the `if` then tests; with both operands constant the `cmp` becomes its result.
         */
        private fun foldComparisons(builder: Body.BodyBuilder): Boolean {
            val graph = builder.controlFlowGraph
            val folded = graph.nodes.filterIsInstance<JAssignStmt>()
                .mapNotNull { assign -> compare(assign.rightOp)?.let { sign -> assign to sign } }
            folded.forEach { (assign, sign) -> replace(builder, assign, assign.withRValue(IntConstant.getInstance(sign))) }
            return folded.isNotEmpty()
        }

        /**
         * What the JVM's comparison instruction yields on two constants, or `null` when [value] is
         * not one on constants. `lcmp` compares the two longs exactly; `fcmpl`/`dcmpl` and
         * `fcmpg`/`dcmpg` compare as IEEE 754 does, so `-0.0` equals `0.0`, and yield `-1` and `1`
         * respectively when either side is `NaN`.
         */
        private fun compare(value: Value): Int? = when (value) {
            is JCmpExpr -> {
                val left = integral(value.op1)
                val right = integral(value.op2)
                if (left != null && right != null) left.compareTo(right) else null
            }
            is JCmplExpr -> compareFloating(value, nan = -1)
            is JCmpgExpr -> compareFloating(value, nan = 1)
            else -> null
        }

        private fun compareFloating(comparison: AbstractBinopExpr, nan: Int): Int? {
            val left = floating(comparison.op1)
            val right = floating(comparison.op2)
            return when {
                left == null || right == null -> null
                left.isNaN() || right.isNaN() -> nan
                left < right -> -1
                left > right -> 1
                else -> 0
            }
        }

        private fun floating(value: Value): Double? = when (value) {
            is IntConstant -> value.value.toDouble()
            is LongConstant -> value.value.toDouble()
            is FloatConstant -> value.value.toDouble()
            is DoubleConstant -> value.value
            else -> null
        }

        private fun integral(value: Value): Long? = when (value) {
            is IntConstant -> value.value.toLong()
            is LongConstant -> value.value
            else -> null
        }

        private fun foldConstantBranches(graph: MutableControlFlowGraph, enums: Map<Local, JStaticFieldRef>): Boolean {
            val ifs = graph.nodes.filterIsInstance<JIfStmt>().mapNotNull { stmt ->
                (evaluate(stmt.condition) ?: evaluateEnums(stmt.condition, enums))?.let { taken ->
                    stmt to { sideOf(graph, stmt, taken) }
                }
            }
            val switches = graph.nodes.filterIsInstance<JSwitchStmt>().mapNotNull { stmt ->
                (stmt.key as? IntConstant)?.let { key -> stmt to { caseOf(graph, stmt, key.value) } }
            }
            // The side is chosen as each branch is resolved: one may jump straight to another
            // that is resolved before it, and must then go where that one goes.
            (ifs + switches).forEach { (stmt, kept) -> resolve(graph, stmt, kept()) }
            return ifs.isNotEmpty() || switches.isNotEmpty()
        }

        /**
         * `==` and `!=` between two enum constants, or between an enum constant and `null`, by
         * the identity of the constants: `null` otherwise, including for an `if` on which only
         * one side is known.
         */
        private fun evaluateEnums(condition: AbstractConditionExpr, enums: Map<Local, JStaticFieldRef>): Boolean? {
            val left = enums[condition.op1]
            val right = enums[condition.op2]
            val equal = when {
                condition !is JEqExpr && condition !is JNeExpr -> null
                left != null && right != null -> left.fieldSignature == right.fieldSignature
                left != null && condition.op2 is NullConstant -> false
                right != null && condition.op1 is NullConstant -> false
                else -> null
            }
            return if (condition is JEqExpr) equal else equal?.not()
        }

        /** Wire the predecessors of [stmt] to [kept], the side it takes, and drop it. */
        private fun resolve(graph: MutableControlFlowGraph, stmt: BranchingStmt, kept: Stmt) {
            for (predecessor in graph.predecessors(stmt).toList()) {
                val indices = graph.removeEdge(predecessor, stmt)
                when (predecessor) {
                    is BranchingStmt -> indices.forEach { graph.putEdge(predecessor, it, kept) }
                    is FallsThroughStmt -> graph.putEdge(predecessor, kept)
                }
            }
            if (graph.startingStmt === stmt) graph.startingStmt = kept
            graph.removeNode(stmt, false)
        }

        /** The truth value of a condition on two constants, `null` when either side is not a constant. */
        private fun evaluate(condition: AbstractConditionExpr): Boolean? {
            val left = condition.op1 as? Constant
            val right = condition.op2 as? Constant
            return when {
                left == null || right == null -> null
                left is NullConstant || right is NullConstant -> compareNulls(condition, left is NullConstant && right is NullConstant)
                else -> {
                    val a = integral(left)
                    val b = integral(right)
                    if (a == null || b == null) null else compareIntegrals(condition, a, b)
                }
            }
        }

        /** `null == null` holds, `null == <other constant>` does not; order comparisons on references are meaningless. */
        private fun compareNulls(condition: AbstractConditionExpr, bothNull: Boolean): Boolean? = when (condition) {
            is JEqExpr -> bothNull
            is JNeExpr -> !bothNull
            else -> null
        }

        private fun compareIntegrals(condition: AbstractConditionExpr, a: Long, b: Long): Boolean? = when (condition) {
            is JEqExpr -> a == b
            is JNeExpr -> a != b
            is JLtExpr -> a < b
            is JLeExpr -> a <= b
            is JGtExpr -> a > b
            is JGeExpr -> a >= b
            else -> null
        }
    }

    /** Runs [delegate] only on a body the fold pass marked. */
    private inner class Gated(private val delegate: BodyInterceptor) : BodyInterceptor {
        override fun interceptBody(builder: Body.BodyBuilder, view: View) {
            if (builder in marked) delegate.interceptBody(builder, view)
        }
    }

    /**
     * Records what a marked body ended up as: per rule, the calls it folded there, and once for
     * the method, its statements before and after, since the clean-up after every rule's folds is
     * one pass whose removals no single rule owns.
     */
    private inner class Accounting : BodyInterceptor {
        override fun interceptBody(builder: Body.BodyBuilder, view: View) {
            val mark = marked.remove(builder) ?: return
            preFoldOrdinals[builder.methodSignature]?.let { ordinals -> reconcileOrdinals(builder, ordinals) }
            val method = render(builder.methodSignature)
            val identity = builder.methodSignature.toString()
            methods[identity] = FoldedMethod(method, mark.statementsBefore, builder.controlFlowGraph.nodes.size)
            mark.calls.forEachIndexed { index, calls ->
                if (calls > 0) sites[index][identity] = FoldSite(method, calls)
            }
        }
    }

    private sealed interface Outcome {
        object Matched : Outcome
        object Skipped : Outcome
        class Unresolved(val reason: String) : Outcome
    }

    private fun callSiteProperties(callee: MethodSignature, caller: MethodSignature, key: CallSiteKey): Map<String, String> = mapOf(
        CALLEE_CLASS to callee.declClassType.fullyQualifiedName,
        CALLEE_NAME to callee.name,
        CALLEE_SIGNATURE to key.callee,
        CallSiteKey.CALLEE_DESCRIPTOR to key.calleeDescriptor,
        "caller_class" to caller.declClassType.fullyQualifiedName,
        "caller_name" to caller.name,
        CALLER_SIGNATURE to key.caller,
        CallSiteKey.CALLER_DESCRIPTOR to key.callerDescriptor,
        CallSiteKey.ORDINAL to key.ordinal.toString()
    )

    /**
     * [signature] on the class that declares the method, which is how the graph names a
     * callee: bytecode spells the receiver's static type (`Sub.enabled()`), and when that class
     * does not declare the method its superclasses are searched, then its interfaces, as the
     * adapter does for the `CallSite` node. A class outside the view leaves the signature as is.
     */
    private fun declaringSignature(view: View, signature: MethodSignature): MethodSignature =
        declaringSignatures.getOrPut(signature) {
            val subSignature = signature.subSignature
            if (declares(view, signature.declClassType, subSignature)) return@getOrPut signature
            val hierarchy = runCatching { view.typeHierarchy }.getOrNull() ?: return@getOrPut signature
            val superClasses = runCatching { hierarchy.superClassesOf(signature.declClassType).toList() }.getOrDefault(emptyList())
            val interfaces = runCatching { hierarchy.implementedInterfacesOf(signature.declClassType).toList() }.getOrDefault(emptyList())
            (superClasses + interfaces).firstOrNull { declares(view, it, subSignature) }
                ?.let { MethodSignature(it, subSignature) }
                ?: signature
        }

    private fun declares(view: View, type: ClassType, subSignature: MethodSubSignature): Boolean =
        runCatching { view.getClass(type).map { it.getMethod(subSignature).isPresent }.orElse(false) }.getOrDefault(false)


    /**
     * The constant reaching argument [index] of [invoke] as the graph would label it, or `null`
     * when it is not a constant: the argument itself when it is one, else the right-hand side
     * of the one definition of the local it is, a constant or a static field read.
     */
    private fun resolveArgument(
        builder: Body.BodyBuilder,
        invoke: AbstractInvokeExpr,
        index: Int,
        view: View
    ): Pair<String, Map<String, Any?>>? {
        val parameterType = invoke.methodSignature.parameterTypes[index]
        return when (val argument = invoke.args[index]) {
            is Constant -> describe(argument, parameterType)
            is Local -> {
                val definitions = builder.controlFlowGraph.nodes.filter { it.def.orElse(null) == argument }
                when (val source = (definitions.singleOrNull() as? JAssignStmt)?.rightOp) {
                    is Constant -> describe(source, parameterType)
                    // A boxed constant (`fn.apply(3)` passes `Integer.valueOf(3)`) is the constant it holds.
                    is JStaticFieldRef -> booleanBoxOf(source)?.let { describe(it.second, PrimitiveType.getBoolean()) }
                        ?: describe(source, view)
                    is JStaticInvokeExpr -> valueOfBoxOf(source)?.let { (_, constant) ->
                        describe(constant, source.methodSignature.parameterTypes.single())
                    }
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun describe(constant: Constant, parameterType: Type): Pair<String, Map<String, Any?>>? = when (constant) {
        is IntConstant ->
            if (parameterType is PrimitiveType.BooleanType) ConstantPattern.BOOLEAN to mapOf(ConstantPattern.VALUE to (constant.value != 0))
            else ConstantPattern.INT to mapOf(ConstantPattern.VALUE to constant.value)
        is LongConstant -> ConstantPattern.LONG to mapOf(ConstantPattern.VALUE to constant.value)
        is FloatConstant -> ConstantPattern.FLOAT to mapOf(ConstantPattern.VALUE to constant.value.toDouble())
        is DoubleConstant -> ConstantPattern.DOUBLE to mapOf(ConstantPattern.VALUE to constant.value)
        is StringConstant -> ConstantPattern.STRING to mapOf(ConstantPattern.VALUE to constant.value)
        is NullConstant -> ConstantPattern.NULL to emptyMap()
        else -> null
    }

    /** A static field read: an enum constant when the field is one (`ACC_ENUM`), a plain field otherwise. */
    private fun describe(field: JStaticFieldRef, view: View): Pair<String, Map<String, Any?>> {
        val signature = field.fieldSignature
        val owner = signature.declClassType
        return if (isEnumConstant(view, field)) {
            ConstantPattern.ENUM to mapOf("enum_type" to owner.fullyQualifiedName, "name" to signature.name)
        } else {
            ConstantPattern.FIELD to mapOf("class" to owner.fullyQualifiedName, "name" to signature.name)
        }
    }

    /**
     * The statement that stands in for [stmt], or `null` when the return type cannot carry the
     * value. A discarded call is checked the same way before it becomes a `nop`: a `void` call,
     * or an overload returning a type the value does not fit, is not the call the rule means,
     * and deleting it would delete its effects.
     */
    private fun replacementFor(stmt: Stmt, invoke: AbstractInvokeExpr, fold: FoldRule, view: View, resultType: String?): Stmt? {
        val returnType = invoke.methodSignature.type
        val value = if (isObject(returnType)) erasedConstantFor(fold.value, view, resultType) else constantFor(returnType, fold.value, view)
        if (value == null) return null
        return if (stmt is JInvokeStmt) JNopStmt(stmt.positionInfo) else (stmt as JAssignStmt).withRValue(value)
    }

    /**
     * The Jimple value [pattern] names for a call returning [type], `null` when the type cannot
     * carry it: a constant, or for an `EnumConstant` the read of the enum's static field, which
     * is what the constant is in bytecode. The enum must be the return type itself.
     */
    @Suppress("ReturnCount")
    private fun constantFor(type: Type, pattern: ConstantPattern, view: View): Value? {
        if (pattern.label == ConstantPattern.NULL) return nullFor(type)
        if (pattern.label == ConstantPattern.ENUM) return enumConstantFor(type, pattern, view)
        val value = pattern.scalar
        if (type is VoidType || value == null) return null
        return when (pattern.label) {
            ConstantPattern.CONSTANT -> inferredConstantFor(type, value, view)
            ConstantPattern.BOOLEAN -> (value as? Boolean)?.takeIf { type is PrimitiveType.BooleanType }?.let(::booleanConstant)
            ConstantPattern.INT -> integral(value)?.let { intLikeConstant(type, it) }
            ConstantPattern.LONG -> integral(value)?.takeIf { type is PrimitiveType.LongType }?.let(LongConstant::getInstance)
            ConstantPattern.FLOAT -> numeric(value)?.takeIf { type is PrimitiveType.FloatType }?.let(::floatConstant)
            ConstantPattern.DOUBLE -> numeric(value)?.takeIf { type is PrimitiveType.DoubleType }?.let(DoubleConstant::getInstance)
            else -> (value as? String)?.takeIf { isString(type) }?.let { stringConstant(it, view) }
        }
    }

    /**
     * The value a call whose return type is erased to `java.lang.Object` becomes, `null` when
     * none fits: what the call really returns there is [resultType], when the CLI read it off the
     * function value's body, else the type the constant names (`false` a `boolean`, `3` an `int`,
     * an `EnumConstant` its enum). A primitive is boxed the way the compiler boxes it
     * (`Boolean.TRUE`/`FALSE`, `Integer.valueOf(3)`), so the cast and unboxing after the call
     * fold; a string is its constant; an enum constant is the read of its field.
     */
    private fun erasedConstantFor(pattern: ConstantPattern, view: View, resultType: String?): Value? {
        val factory = view.identifierFactory
        val effective = resultType ?: naturalType(pattern)
        val primitive = BOXES.entries.firstOrNull { (name, box) -> effective == name || effective == box.first }?.key
        val enumType = pattern.properties[ConstantPattern.ENUM_TYPE] as? String
        return when {
            pattern.label == ConstantPattern.NULL -> NullConstant.getInstance()
            pattern.label == ConstantPattern.ENUM -> enumType?.takeIf { resultType == null || resultType == it }
                ?.let { enumConstantFor(factory.getClassType(it), pattern, view) }
            primitive != null -> constantFor(factory.getType(primitive), pattern, view)?.let { boxed(primitive, it as Immediate, view) }
            effective == STRING_CLASS -> constantFor(factory.getClassType(STRING_CLASS), pattern, view)
            else -> null
        }
    }

    /** The shorthand: the scalar is carried by whatever the return type is, when it can be. */
    private fun inferredConstantFor(type: Type, value: Any, view: View): Value? = when {
        type is PrimitiveType.BooleanType -> (value as? Boolean)?.let(::booleanConstant)
        isIntLike(type) -> (value as? Int)?.let { intLikeConstant(type, it.toLong()) }
        type is PrimitiveType.LongType -> integral(value)?.let(LongConstant::getInstance)
        type is PrimitiveType.DoubleType -> numeric(value)?.let(DoubleConstant::getInstance)
        type is PrimitiveType.FloatType -> numeric(value)?.let(::floatConstant)
        isString(type) -> (value as? String)?.let { stringConstant(it, view) }
        else -> null
    }

    /** The read of the named constant, when the return type is its enum and the enum declares a constant of that name. */
    private fun enumConstantFor(type: Type, pattern: ConstantPattern, view: View): Value? {
        val enumType = pattern.properties[ConstantPattern.ENUM_TYPE] as? String
        val name = pattern.properties[ConstantPattern.NAME] as? String
        val fits = type is ClassType && type.fullyQualifiedName == enumType && name != null && isEnumConstant(view, type, name)
        return if (fits) JStaticFieldRef(FieldSignature(type as ClassType, name!!, type)) else null
    }

    /** Whether [field] reads an enum constant, as `describe` labels it and `FoldBranches` compares it. */
    private fun isEnumConstant(view: View, field: JStaticFieldRef): Boolean =
        isEnumConstant(view, field.fieldSignature.declClassType, field.fieldSignature.name)

    /**
     * Whether [owner] declares an enum constant [name]: the field carries `ACC_ENUM`, by the class
     * in the view, else by the analysis JVM's own copy of it, loaded without initialisation. An
     * enum's other static fields (`static E alias = A`) are fields, whatever their type.
     */
    private fun isEnumConstant(view: View, owner: ClassType, name: String): Boolean = enumConstants.getOrPut(owner to name) {
        runCatching {
            view.getClass(owner).map { declared -> declared.getField(name).map { FieldModifier.ENUM in it.modifiers }.orElse(false) }
                .orElse(null)
        }.getOrNull()
            ?: runCatching {
                val loader = ConstantFolding::class.java.classLoader
                Class.forName(owner.fullyQualifiedName, false, loader).getDeclaredField(name).isEnumConstant
            }.getOrDefault(false)
    }

    private fun stringConstant(value: String, view: View): Value =
        StringConstant(value, view.identifierFactory.getClassType(STRING_CLASS))
}

private fun booleanConstant(value: Boolean): Value = IntConstant.getInstance(if (value) 1 else 0)

/** The type a scalar names on its own: `false` is a `boolean`, `3` an `int`, `"x"` a `java.lang.String`. */
private fun naturalType(pattern: ConstantPattern): String? = when (pattern.label) {
    ConstantPattern.BOOLEAN -> BOOLEAN_TYPE
    ConstantPattern.INT -> INT_TYPE
    ConstantPattern.LONG -> LONG_TYPE
    ConstantPattern.FLOAT -> FLOAT_TYPE
    ConstantPattern.DOUBLE -> DOUBLE_TYPE
    ConstantPattern.STRING -> STRING_CLASS
    else -> when (pattern.scalar) {
        is Boolean -> BOOLEAN_TYPE
        is Int -> INT_TYPE
        is Long -> LONG_TYPE
        is Double -> DOUBLE_TYPE
        is String -> STRING_CLASS
        else -> null
    }
}

/** [constant], a value of [primitive], boxed as the compiler boxes it. */
private fun boxed(primitive: String, constant: Immediate, view: View): Value {
    val factory = view.identifierFactory
    val box = factory.getClassType(BOXES.getValue(primitive).first)
    return if (primitive == BOOLEAN_TYPE) {
        JStaticFieldRef(FieldSignature(box, if ((constant as IntConstant).value != 0) "TRUE" else "FALSE", box))
    } else {
        JStaticInvokeExpr(factory.getMethodSignature(box, VALUE_OF, box, listOf(factory.getType(primitive))), listOf(constant))
    }
}

/**
 * [value] as the `int` constant Jimple uses for every int-like [type], or `null` when the
 * declared type cannot hold it: a `byte` method does not return 128, a `short` 32768 or a
 * `char` -1, whatever the carrier, and a folded call must keep the method's own contract.
 */
private fun intLikeConstant(type: Type, value: Long): Value? =
    intLikeRange(type)?.takeIf { value in it }?.let { IntConstant.getInstance(value.toInt()) }

/**
 * [value] at `float` precision, or `null` when a `float` cannot carry it: a finite double
 * past `Float.MAX_VALUE` (`1e308`) narrows to an infinity, a value that was not written.
 */
private fun floatConstant(value: Double): Value? = value.toFloat().takeIf(Float::isFinite)?.let(FloatConstant::getInstance)

private fun nullFor(type: Type): Value? =
    if (type is ClassType || type.toString().endsWith("[]")) NullConstant.getInstance() else null

private fun isIntLike(type: Type): Boolean = intLikeRange(type) != null

/**
 * The values a type Jimple carries as an `int` can hold, `null` for any other type. SootUp
 * derives the narrow types (and `boolean`) from `IntType`, so they are matched first.
 */
private fun intLikeRange(type: Type): LongRange? = when (type) {
    is PrimitiveType.BooleanType -> null
    is PrimitiveType.ByteType -> Byte.MIN_VALUE.toLong()..Byte.MAX_VALUE.toLong()
    is PrimitiveType.ShortType -> Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong()
    is PrimitiveType.CharType -> Char.MIN_VALUE.code.toLong()..Char.MAX_VALUE.code.toLong()
    is PrimitiveType.IntType -> Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
    else -> null
}

private fun isString(type: Type): Boolean = type is ClassType && type.fullyQualifiedName == STRING_CLASS

private fun integral(value: Any): Long? = when (value) {
    is Int -> value.toLong()
    is Long -> value
    else -> null
}

private fun numeric(value: Any): Double? = when (value) {
    is Int -> value.toDouble()
    is Long -> value.toDouble()
    is Double -> value
    else -> null
}

/**
 * Remove every statement the method's entry no longer reaches, by normal or exceptional flow:
 * SootUp validates the graph after each interceptor and a statement without a predecessor fails it.
 */
/** The statement an `if` continues with. An `if` whose two sides are the same statement (`if (gate) { }`) keeps it either way. */
private fun sideOf(graph: MutableControlFlowGraph, stmt: JIfStmt, taken: Boolean): Stmt {
    val target = graph.getBranchTargetsOf(stmt).single()
    return if (taken) target else graph.successors(stmt).firstOrNull { it !== target } ?: target
}

/**
 * The statement a `switch` on [key] jumps to: the case listing it, else the default. A switch's
 * targets follow its values, the default last; a `tableswitch` lists every value of its range.
 */
private fun caseOf(graph: MutableControlFlowGraph, stmt: JSwitchStmt, key: Int): Stmt {
    val targets = graph.getBranchTargetsOf(stmt)
    val case = stmt.values.indexOfFirst { it.value == key }
    return targets[if (case >= 0) case else stmt.values.size]
}

private fun removeUnreachable(graph: MutableControlFlowGraph) {
    val reachable = Collections.newSetFromMap(IdentityHashMap<Stmt, Boolean>())
    val pending = ArrayDeque<Stmt>()
    graph.startingStmt?.let { pending.add(it); reachable.add(it) }
    while (pending.isNotEmpty()) {
        val stmt = pending.removeFirst()
        (graph.successors(stmt) + graph.exceptionalSuccessors(stmt).values).forEach { next ->
            if (reachable.add(next)) pending.add(next)
        }
    }
    for (stmt in graph.nodes.toList()) {
        if (stmt !in reachable) graph.removeNode(stmt, false)
    }
}

/** Where the receiver of a call came from: the call that produced it, `this`, or something else, described. */
private sealed interface ReceiverSource {
    class Call(val invoke: AbstractInvokeExpr) : ReceiverSource
    object This : ReceiverSource
    class Other(val what: String) : ReceiverSource
}

/**
 * The clean-up passes rebuild statements of their own: `DeadAssignmentEliminator` turns
 * `v = f()` into `f()` once `v` is unused, around the same invoke expression. A surviving
 * statement without a pre-fold ordinal takes the ordinal of the statement that held its
 * invoke expression before, so a call that survives the fold keeps the key it had.
 */
private fun reconcileOrdinals(builder: Body.BodyBuilder, ordinals: MutableMap<Stmt, Int>) {
    val live = builder.stmts
    val missing = live.filter { it !in ordinals && invokeExprOf(it) != null }
    if (missing.isEmpty()) return
    val liveSet = Collections.newSetFromMap(IdentityHashMap<Stmt, Boolean>()).apply { addAll(live) }
    val byExpr = IdentityHashMap<AbstractInvokeExpr, Int>()
    for ((stmt, ordinal) in ordinals) {
        if (stmt !in liveSet) invokeExprOf(stmt)?.let { byExpr[it] = ordinal }
    }
    for (stmt in missing) invokeExprOf(stmt)?.let(byExpr::get)?.let { ordinals[stmt] = it }
}

private fun invokeExprOf(stmt: Stmt): AbstractInvokeExpr? = when (stmt) {
    is JAssignStmt -> stmt.invokeExpr.orElse(null)
    is JInvokeStmt -> stmt.invokeExpr.orElse(null)
    else -> null
}

/**
 * `CallSite.ordinal` for every invoke of [statements], by statement: the rank of the call among
 * the invokes of the same callee in statement order, from `0`, the callee resolved to its
 * declaring class by [declaring]. The one place the identity of a call is computed: the adapter
 * gives the graph's call sites these numbers and the fold pass reads a rule's keys against them,
 * over the unfiltered body both, so a call the graph shows no call site for (a boxing call that
 * becomes a dataflow edge) still counts and a key names the same invoke in either.
 */
internal fun callOrdinals(statements: Iterable<Stmt>, declaring: (MethodSignature) -> String): Map<Stmt, Int> {
    val counts = HashMap<String, Int>()
    val ordinals = IdentityHashMap<Stmt, Int>()
    for (stmt in statements) {
        val invoke = invokeExprOf(stmt) ?: continue
        ordinals[stmt] = counts.merge(declaring(invoke.methodSignature), 1, Int::plus)!! - 1
    }
    return ordinals
}

/** The graph's rendering of a method signature (`pkg.Cls.name(p1,p2)`), as [callOrdinals] keys it. */
internal fun renderSignature(signature: MethodSignature): String = render(signature)

/** The box and the constant behind `Boolean.TRUE` / `Boolean.FALSE`, or `null`. */
private fun booleanBoxOf(field: JStaticFieldRef): Pair<String, Constant>? {
    val signature = field.fieldSignature
    return when {
        signature.declClassType.fullyQualifiedName != BOOLEAN_BOX -> null
        signature.name == "TRUE" -> BOOLEAN_BOX to IntConstant.getInstance(1)
        signature.name == "FALSE" -> BOOLEAN_BOX to IntConstant.getInstance(0)
        else -> null
    }
}

/** The box and the constant behind `Integer.valueOf(3)` and its kin, or `null`. */
private fun valueOfBoxOf(invoke: JStaticInvokeExpr): Pair<String, Constant>? {
    val signature = invoke.methodSignature
    val box = signature.declClassType.fullyQualifiedName
    val primitive = signature.parameterTypes.singleOrNull()?.toString()
    val constant = invoke.args.singleOrNull() as? Constant
    return if (signature.name == VALUE_OF && constant != null && BOXES[primitive]?.first == box) box to constant else null
}

/** `pkg.Cls.name(p1,p2)`, the signature as the graph's `callee_signature` and `caller_signature` spell it. */
private fun render(signature: MethodSignature): String {
    val parameters = signature.parameterTypes.joinToString(",", transform = ::graphTypeName)
    return "${signature.declClassType.fullyQualifiedName}.${signature.name}($parameters)"
}

/** The JVM descriptor as the graph's `callee_descriptor` and `caller_descriptor` spell it, from the same type names. */
private fun descriptorOf(signature: MethodSignature): String =
    jvmMethodDescriptor(signature.parameterTypes.map(::graphTypeName), graphTypeName(signature.type))

/**
 * A type's name as the graph's `TypeDescriptor` holds it: the one rule the adapter and the fold
 * pass both name types by, so a key read off the graph and one computed from a body agree. An
 * array is its base type and one `[]` whatever its dimensions, as the adapter has always written it.
 */
internal fun graphTypeName(type: Type): String = when (type) {
    is ClassType -> type.fullyQualifiedName
    is ArrayType -> "${graphTypeName(type.baseType)}[]"
    else -> type.toString()
}

private fun isObject(type: Type): Boolean = type is ClassType && type.fullyQualifiedName == OBJECT_CLASS
