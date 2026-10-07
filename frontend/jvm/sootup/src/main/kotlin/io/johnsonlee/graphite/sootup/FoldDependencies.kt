package io.johnsonlee.graphite.sootup

import sootup.core.jimple.common.Local
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.constant.Constant
import sootup.core.jimple.common.expr.JCastExpr
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.Stmt

/** Calls whose operands originate in a rule replacement, including copies and erased-value casts. */
internal class FoldDependencies(statements: Collection<Stmt>, roots: Set<Local>) {
    private val dependent = HashSet<Stmt>()

    init {
        trace(statements, roots)
    }

    private fun trace(statements: Collection<Stmt>, roots: Set<Local>) {
        val locals = roots.toMutableSet()
        do {
            var changed = false
            for (stmt in statements.filterIsInstance<JAssignStmt>()) {
                val local = stmt.leftOp as? Local ?: continue
                if (sourceOf(stmt.rightOp) in locals && locals.add(local)) changed = true
            }
        } while (changed)
        statements.filterTo(dependent) { stmt -> stmt.uses.any { it in locals } }
    }

    private fun sourceOf(value: Value): Value = when {
        value is JCastExpr -> value.op
        value is JStaticInvokeExpr && isPrimitiveBoxing(value) -> value.args.single()
        else -> value
    }

    operator fun contains(stmt: Stmt): Boolean = stmt in dependent

    /** Constant propagation creates new statements; retain the origin after a local becomes a literal. */
    fun replace(old: Stmt, replacement: Stmt, statements: Collection<Stmt>) {
        if (dependent.remove(old)) {
            dependent.add(replacement)
            // An evaluated intrinsic produces another rule-derived constant. Its consumers
            // must participate in the next round too (equals -> boxing -> equals, for example).
            if (replacement is JAssignStmt && replacement.rightOp is Constant) {
                (replacement.leftOp as? Local)?.let { trace(statements, setOf(it)) }
            }
        }
    }
}
