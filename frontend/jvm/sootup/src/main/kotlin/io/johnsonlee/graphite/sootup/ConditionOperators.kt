package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.ComparisonOp
import sootup.core.jimple.common.expr.JEqExpr
import sootup.core.jimple.common.expr.JGeExpr
import sootup.core.jimple.common.expr.JGtExpr
import sootup.core.jimple.common.expr.JLeExpr
import sootup.core.jimple.common.expr.JLtExpr
import sootup.core.jimple.common.expr.JNeExpr

/** Shared recognition for emitted branch constraints and constant branch folding; unsupported values stay unknown. */
internal fun comparisonOp(condition: Any): ComparisonOp? = when (condition) {
    is JEqExpr -> ComparisonOp.EQ
    is JNeExpr -> ComparisonOp.NE
    is JLtExpr -> ComparisonOp.LT
    is JGeExpr -> ComparisonOp.GE
    is JGtExpr -> ComparisonOp.GT
    is JLeExpr -> ComparisonOp.LE
    else -> null
}
