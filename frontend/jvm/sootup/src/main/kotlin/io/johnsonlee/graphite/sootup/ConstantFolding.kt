package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.CallSiteKey
import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.ConstantPattern
import io.johnsonlee.graphite.input.FoldOutcome
import io.johnsonlee.graphite.input.FoldReport
import io.johnsonlee.graphite.input.FoldRule
import io.johnsonlee.graphite.input.FoldSite
import io.johnsonlee.graphite.input.FoldSites
import io.johnsonlee.graphite.input.UnsupportedFoldSite
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
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
import sootup.core.jimple.common.expr.AbstractInvokeExpr
import sootup.core.jimple.common.ref.JStaticFieldRef
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
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.core.jimple.common.stmt.JNopStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.graph.MutableControlFlowGraph
import sootup.core.model.Body
import sootup.core.model.FieldModifier
import sootup.core.signatures.FieldSignature
import sootup.core.signatures.MethodSignature
import sootup.core.signatures.MethodSubSignature
import sootup.core.interceptor.BodyInterceptor
import sootup.core.types.ClassType
import sootup.core.types.PrimitiveType
import sootup.core.types.Type
import sootup.core.types.VoidType
import sootup.core.views.View
import sootup.interceptors.DeadAssignmentEliminator
import sootup.interceptors.NopEliminator

private const val STRING_CLASS = "java.lang.String"
private const val MAX_HINTS = 8
private const val CALLEE_CLASS = "callee_class"
private const val CALLEE_NAME = "callee_name"
private const val CALLEE_SIGNATURE = "callee_signature"
private const val CALLER_SIGNATURE = "caller_signature"
private const val ORDINAL = "ordinal"

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

    /** Per rule, per calling method, the keys of the calls its latest resolution folded. */
    private val folded = List(folds.size) { Collections.synchronizedMap(LinkedHashMap<String, List<CallSiteKey>>()) }

    /** Per rule, per calling method, the calls its latest resolution matched but could not fold. */
    private val unsupported = List(folds.size) { Collections.synchronizedMap(LinkedHashMap<String, List<UnsupportedFoldSite>>()) }

    /** Per rule, per calling method, the near misses its latest resolution saw with how many calls each. */
    private val misses = List(folds.size) { Collections.synchronizedMap(LinkedHashMap<String, Map<String, Int>>()) }

    private val declaringSignatures = ConcurrentHashMap<MethodSignature, MethodSignature>()

    /** Per method a rule folded in, the numbering of its invokes before the fold, by the statements that survive it. */
    private val preFoldOrdinals = ConcurrentHashMap<MethodSignature, Map<Stmt, Int>>()
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
        }
    )

    /** The near misses of a `match` rule with their call counts; the selected keys a `select` rule never met. */
    private fun hints(index: Int): List<String> {
        val fold = folds[index]
        if (fold is FoldSites) {
            val seen = synchronized(folded[index]) { folded[index].values.flatten().toSet() }
            return fold.selected.orEmpty().filter { it !in seen }.take(MAX_HINTS).map { "selected call site $it is not in this build" }
        }
        val hints = LinkedHashMap<String, Int>()
        synchronized(misses[index]) {
            misses[index].values.forEach { byHint ->
                byHint.forEach { (hint, calls) ->
                    if (hint in hints || hints.size < MAX_HINTS) hints[hint] = (hints[hint] ?: 0) + calls
                }
            }
        }
        return hints.map { (hint, calls) -> "$hint ($calls call(s))" }
    }

    /** Replace every matching call expression by its constant and mark the body for the clean-up passes. */
    private inner class Fold : BodyInterceptor {
        override fun interceptBody(builder: Body.BodyBuilder, view: View) {
            val run = BodyRun(folds.size)
            val statementsBefore = builder.controlFlowGraph.nodes.size
            val caller = render(builder.methodSignature)
            // The same numbering the adapter gives the graph's `CallSite.ordinal`, computed by the
            // one function from the unfiltered body (`callOrdinals`), so a key read off a graph
            // names the same invoke here even where the graph shows no call site for a call.
            val ordinals = callOrdinals(builder.stmts) { declaringSignature(view, it) }
            for (stmt in builder.stmts) {
                val invoke = invokeExprOf(stmt) ?: continue
                val callee = declaringSignature(view, invoke.methodSignature)
                val ordinal = ordinals.getValue(stmt)
                val index = select(run, builder, invoke, caller, view, callee, ordinal)
                if (index != null && foldCall(run, builder, stmt, invoke, index, view)) {
                    run.calls[index]++
                    run.keys[index].add(CallSiteKey(caller, render(callee), ordinal))
                }
            }
            for (index in folds.indices) {
                if (run.keys[index].isEmpty()) {
                    folded[index].remove(caller)
                } else {
                    folded[index][caller] = run.keys[index]
                }
                if (run.unsupported[index].isEmpty()) {
                    unsupported[index].remove(caller)
                } else {
                    unsupported[index][caller] = run.unsupported[index]
                }
                if (run.misses[index].isEmpty()) {
                    misses[index].remove(caller)
                } else {
                    misses[index][caller] = run.misses[index]
                }
                if (run.calls[index] == 0) sites[index].remove(caller)
            }
            if (run.calls.any { it > 0 }) {
                // A folded call throws nothing: a handler only it could reach has lost its last
                // predecessor and goes now, before SootUp validates the graph.
                removeUnreachable(builder.controlFlowGraph)
                marked[builder] = Marked(statementsBefore, run.calls)
                preFoldOrdinals[builder.methodSignature] = ordinals
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
            caller: String,
            view: View,
            callee: MethodSignature,
            ordinal: Int
        ): Int? {
            val properties = callSiteProperties(callee, builder.methodSignature, ordinal)
            var unresolved: Pair<Int, Int>? = null
            for ((index, fold) in folds.withIndex()) {
                when (val outcome = test(run, index, fold, builder, invoke, properties, view)) {
                    Outcome.Matched -> return index
                    is Outcome.Unresolved -> if (unresolved == null) unresolved = index to outcome.argument
                    Outcome.Skipped -> Unit
                }
            }
            unresolved?.let { (index, argument) ->
                val reason = "argument $argument of ${properties.getValue(CALLEE_SIGNATURE)} is not a constant"
                run.unsupported[index].add(UnsupportedFoldSite(caller, reason))
            }
            return null
        }

        /** [fold] against one call: matched, skipped (recording the near miss), or skipped because an argument is not a constant. */
        @Suppress("LongParameterList")
        private fun test(
            run: BodyRun,
            index: Int,
            fold: FoldRule,
            builder: Body.BodyBuilder,
            invoke: AbstractInvokeExpr,
            properties: Map<String, String>,
            view: View
        ): Outcome {
            if (fold is FoldSites) {
                val key = CallSiteKey(
                    properties.getValue(CALLER_SIGNATURE),
                    properties.getValue(CALLEE_SIGNATURE),
                    properties.getValue(ORDINAL).toInt()
                )
                return if (fold.selected?.contains(key) == true) Outcome.Matched else Outcome.Skipped
            }
            fold as ConstantFold
            val mismatch = fold.mismatch(properties)
            return when {
                mismatch != null -> Outcome.Skipped.also { recordSiteMiss(run, index, fold, properties, mismatch) }
                fold.arguments.keys.any { it >= invoke.args.size } -> Outcome.Skipped
                else -> testArguments(run, index, fold, builder, invoke, properties, view)
            }
        }

        @Suppress("LongParameterList")
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
            return if (argument == null) Outcome.Skipped else Outcome.Unresolved(argument)
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

        /** Replace [stmt]; `false`, with the site recorded as unsupported, when the return type cannot carry the value. */
        @Suppress("LongParameterList")
        private fun foldCall(
            run: BodyRun,
            builder: Body.BodyBuilder,
            stmt: Stmt,
            invoke: AbstractInvokeExpr,
            index: Int,
            view: View
        ): Boolean {
            val replacement = replacementFor(stmt, invoke, folds[index], view)
            if (replacement == null) {
                val reason = "return type ${invoke.methodSignature.type} cannot carry ${folds[index].value.description}"
                run.unsupported[index].add(UnsupportedFoldSite(render(builder.methodSignature), reason))
                return false
            }
            builder.controlFlowGraph.replaceNode(stmt, replacement)
            // A constant or a nop throws nothing: the handler the call could reach is reachable
            // only through the statements that can still throw.
            builder.controlFlowGraph.clearExceptionalEdges(replacement)
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
    private class FoldBranches(private val isEnumConstant: (View, JStaticFieldRef) -> Boolean) : BodyInterceptor {
        override fun interceptBody(builder: Body.BodyBuilder, view: View) {
            val graph = builder.controlFlowGraph
            do {
                val propagated = propagateConstants(builder) or foldComparisons(graph)
                val folded = foldConstantBranches(graph, enumReads(builder, view))
                // Pruned per round: a definition on the side just removed must not keep a local
                // from being read as its one remaining constant in the next round.
                if (folded) removeUnreachable(graph)
            } while (propagated || folded)
        }

        /**
         * The locals whose one definition reads an enum constant, with the field read. An enum
         * constant is not a Jimple constant and cannot stand inside an `if`, but two reads of enum
         * constants compare by identity: the same constant is `==`, different ones are not, and
         * neither is `null`.
         */
        private fun enumReads(builder: Body.BodyBuilder, view: View): Map<Local, JStaticFieldRef> {
            val statements = builder.controlFlowGraph.nodes.toList()
            return statements.filterIsInstance<JAssignStmt>()
                .filter { it.leftOp is Local && (it.rightOp as? JStaticFieldRef)?.let { field -> isEnumConstant(view, field) } == true }
                .groupBy { it.leftOp as Local }
                .filter { (local, stmts) -> stmts.size == 1 && statements.count { it.def.orElse(null) == local } == 1 }
                .mapValues { (_, stmts) -> stmts.single().rightOp as JStaticFieldRef }
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
                    if (stmt === definition || stmt.uses.none { it == local }) continue
                    builder.controlFlowGraph.replaceNode(stmt, stmt.withNewUse(local, definition.rightOp))
                    changed = true
                }
            }
            return changed
        }

        /**
         * A `long`, `float` or `double` comparison compiles to `cmp`/`cmpl`/`cmpg` into an int that
         * the `if` then tests; with both operands constant the `cmp` becomes its result.
         */
        private fun foldComparisons(graph: MutableControlFlowGraph): Boolean {
            val folded = graph.nodes.filterIsInstance<JAssignStmt>()
                .mapNotNull { assign -> compare(assign.rightOp)?.let { sign -> assign to sign } }
            folded.forEach { (assign, sign) -> graph.replaceNode(assign, assign.withRValue(IntConstant.getInstance(sign))) }
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
            val resolved = graph.nodes.filterIsInstance<JIfStmt>()
                .mapNotNull { stmt -> (evaluate(stmt.condition) ?: evaluateEnums(stmt.condition, enums))?.let { taken -> stmt to taken } }
            resolved.forEach { (stmt, taken) -> resolve(graph, stmt, taken) }
            return resolved.isNotEmpty()
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

        /**
         * Wire the predecessors of [stmt] to the side it takes and drop it. An `if` whose two
         * sides are the same statement (`if (gate) { }`) keeps that statement either way.
         */
        private fun resolve(graph: MutableControlFlowGraph, stmt: JIfStmt, taken: Boolean) {
            val target = graph.getBranchTargetsOf(stmt).single()
            val kept = if (taken) target else graph.successors(stmt).firstOrNull { it !== target } ?: target
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

    /** Records, per rule and calling method, what a marked body ended up as. */
    private inner class Accounting : BodyInterceptor {
        override fun interceptBody(builder: Body.BodyBuilder, view: View) {
            val mark = marked.remove(builder) ?: return
            val method = render(builder.methodSignature)
            val statementsAfter = builder.controlFlowGraph.nodes.size
            mark.calls.forEachIndexed { index, calls ->
                if (calls > 0) sites[index][method] = FoldSite(method, calls, mark.statementsBefore, statementsAfter)
            }
        }
    }

    private sealed interface Outcome {
        object Matched : Outcome
        object Skipped : Outcome
        class Unresolved(val argument: Int) : Outcome
    }

    private fun callSiteProperties(callee: MethodSignature, caller: MethodSignature, ordinal: Int): Map<String, String> = mapOf(
        CALLEE_CLASS to callee.declClassType.fullyQualifiedName,
        CALLEE_NAME to callee.name,
        CALLEE_SIGNATURE to render(callee),
        "caller_class" to caller.declClassType.fullyQualifiedName,
        "caller_name" to caller.name,
        CALLER_SIGNATURE to render(caller),
        ORDINAL to ordinal.toString()
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
                    is JStaticFieldRef -> describe(source, view)
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
    private fun replacementFor(stmt: Stmt, invoke: AbstractInvokeExpr, fold: FoldRule, view: View): Stmt? {
        val value = constantFor(invoke.methodSignature.type, fold.value, view) ?: return null
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
internal fun callOrdinals(statements: Iterable<Stmt>, declaring: (MethodSignature) -> MethodSignature): Map<Stmt, Int> {
    val counts = HashMap<String, Int>()
    val ordinals = IdentityHashMap<Stmt, Int>()
    for (stmt in statements) {
        val invoke = invokeExprOf(stmt) ?: continue
        ordinals[stmt] = counts.merge(render(declaring(invoke.methodSignature)), 1, Int::plus)!! - 1
    }
    return ordinals
}

/** `pkg.Cls.name(p1,p2)`, the signature as the graph's `callee_signature` and `caller_signature` spell it. */
private fun render(signature: MethodSignature): String =
    "${signature.declClassType.fullyQualifiedName}.${signature.name}(${signature.parameterTypes.joinToString(",")})"
