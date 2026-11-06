package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.cypher.parser.CypherLexer
import io.johnsonlee.graphite.cypher.parser.CypherParser

private val ADD_SUB_OPERATORS = mapOf(CypherLexer.PLUS to "+", CypherLexer.SUB to "-")
private val MULT_DIV_OPERATORS = mapOf(CypherLexer.MULT to "*", CypherLexer.DIV to "/", CypherLexer.MOD to "%")

internal fun visitExpr(ctx: CypherParser.ExpressionContext): CypherExpr {
    return visitOrExpr(ctx.orExpression())
}

internal fun visitOrExpr(ctx: CypherParser.OrExpressionContext): CypherExpr {
    val parts = ctx.xorExpression()
    var result = visitXorExpr(parts[0])
    for (i in 1 until parts.size) {
        result = CypherExpr.Or(result, visitXorExpr(parts[i]))
    }
    return result
}

internal fun visitXorExpr(ctx: CypherParser.XorExpressionContext): CypherExpr {
    val parts = ctx.andExpression()
    var result = visitAndExpr(parts[0])
    for (i in 1 until parts.size) {
        result = CypherExpr.Xor(result, visitAndExpr(parts[i]))
    }
    return result
}

internal fun visitAndExpr(ctx: CypherParser.AndExpressionContext): CypherExpr {
    val parts = ctx.notExpression()
    var result = visitNotExpr(parts[0])
    for (i in 1 until parts.size) {
        result = CypherExpr.And(result, visitNotExpr(parts[i]))
    }
    return result
}

internal fun visitNotExpr(ctx: CypherParser.NotExpressionContext): CypherExpr {
    return if (ctx.NOT() != null) {
        CypherExpr.Not(visitNotExpr(ctx.notExpression()))
    } else {
        visitComparisonExpr(ctx.comparisonExpression())
    }
}

internal fun visitComparisonExpr(ctx: CypherParser.ComparisonExpressionContext): CypherExpr {
    val operands = ctx.stringPredicateExpression()
    val ops = ctx.compOp()
    var result = visitStringPredicateExpr(operands[0])
    for (i in ops.indices) {
        val op =
            when {
                ops[i].ASSIGN() != null -> "="
                ops[i].NOT_EQUAL() != null -> "<>"
                ops[i].LT() != null -> "<"
                ops[i].GT() != null -> ">"
                ops[i].LE() != null -> "<="
                ops[i].GE() != null -> ">="
                else -> throw CypherParseException("Unknown comparison operator")
            }
        result = CypherExpr.Comparison(op, result, visitStringPredicateExpr(operands[i + 1]))
    }
    return result
}

internal fun visitStringPredicateExpr(ctx: CypherParser.StringPredicateExpressionContext): CypherExpr {
    var result = visitAddSubExpr(ctx.addSubExpression())
    for (suffix in ctx.stringPredicateSuffix()) {
        result =
            when (suffix) {
                is CypherParser.StartsWithPredicateContext ->
                    CypherExpr.StringOp("STARTS WITH", result, visitAddSubExpr(suffix.addSubExpression()))
                is CypherParser.EndsWithPredicateContext ->
                    CypherExpr.StringOp("ENDS WITH", result, visitAddSubExpr(suffix.addSubExpression()))
                is CypherParser.ContainsPredicateContext ->
                    CypherExpr.StringOp("CONTAINS", result, visitAddSubExpr(suffix.addSubExpression()))
                is CypherParser.InPredicateContext -> CypherExpr.ListOp("IN", result, visitAddSubExpr(suffix.addSubExpression()))
                is CypherParser.RegexPredicateContext -> CypherExpr.RegexMatch(result, visitAddSubExpr(suffix.addSubExpression()))
                is CypherParser.IsNullPredicateContext -> CypherExpr.IsNull(result)
                is CypherParser.IsNotNullPredicateContext -> CypherExpr.IsNotNull(result)
                is CypherParser.NotContainsPredicateContext ->
                    CypherExpr.Not(CypherExpr.StringOp("CONTAINS", result, visitAddSubExpr(suffix.addSubExpression())))
                is CypherParser.NotStartsWithPredicateContext ->
                    CypherExpr.Not(CypherExpr.StringOp("STARTS WITH", result, visitAddSubExpr(suffix.addSubExpression())))
                is CypherParser.NotEndsWithPredicateContext ->
                    CypherExpr.Not(CypherExpr.StringOp("ENDS WITH", result, visitAddSubExpr(suffix.addSubExpression())))
                else -> throw CypherParseException("Unknown string predicate suffix")
            }
    }
    return result
}

internal fun visitAddSubExpr(ctx: CypherParser.AddSubExpressionContext): CypherExpr {
    val operands = ctx.multDivExpression()
    var result = visitMultDivExpr(operands[0])
    // The operators alternate with operands: operand (op operand)*
    // In the parse tree, we need to find the PLUS/SUB tokens
    var opIndex = 0
    for (i in 1 until operands.size) {
        // Find the operator between operands[i-1] and operands[i]
        val op = findAddSubOp(ctx, opIndex++)
        result = CypherExpr.BinaryOp(op, result, visitMultDivExpr(operands[i]))
    }
    return result
}

internal fun findAddSubOp(ctx: CypherParser.AddSubExpressionContext, index: Int): String =
    findOperator(ctx.children, index, ADD_SUB_OPERATORS, "add/sub")

internal fun visitMultDivExpr(ctx: CypherParser.MultDivExpressionContext): CypherExpr {
    val operands = ctx.powerExpression()
    var result = visitPowerExpr(operands[0])
    var opIndex = 0
    for (i in 1 until operands.size) {
        val op = findMultDivOp(ctx, opIndex++)
        result = CypherExpr.BinaryOp(op, result, visitPowerExpr(operands[i]))
    }
    return result
}

internal fun findMultDivOp(ctx: CypherParser.MultDivExpressionContext, index: Int): String =
    findOperator(ctx.children, index, MULT_DIV_OPERATORS, "mult/div/mod")

internal fun visitPowerExpr(ctx: CypherParser.PowerExpressionContext): CypherExpr {
    val left = visitUnaryExpr(ctx.unaryExpression())
    // Right-associative via grammar: powerExpression : unaryExpression (CARET powerExpression)?
    return if (ctx.CARET() != null && ctx.powerExpression() != null) {
        CypherExpr.BinaryOp("^", left, visitPowerExpr(ctx.powerExpression()))
    } else {
        left
    }
}

internal fun visitUnaryExpr(ctx: CypherParser.UnaryExpressionContext): CypherExpr {
    return when {
        ctx.SUB() != null -> CypherExpr.UnaryOp("-", visitUnaryExpr(ctx.unaryExpression()))
        ctx.PLUS() != null -> visitUnaryExpr(ctx.unaryExpression())
        else -> visitPostfixExpr(ctx.postfixExpression())
    }
}

internal fun visitPostfixExpr(ctx: CypherParser.PostfixExpressionContext): CypherExpr {
    var result = visitAtomExpr(ctx.atomExpression())

    // Process postfix chains: property access and subscript/slice
    val propertyKeys = ctx.propertyKeyName()
    val subscripts = ctx.subscriptOrSlice()

    // We need to iterate through the children to get the right order
    var propIdx = 0
    var subIdx = 0
    for (child in ctx.children) {
        when {
            child is org.antlr.v4.runtime.tree.TerminalNode && child.symbol.type == CypherLexer.DOT -> {
                if (propIdx < propertyKeys.size) {
                    result = CypherExpr.Property(result, getSymbolicName(propertyKeys[propIdx]))
                    propIdx++
                }
            }
            child is CypherParser.SubscriptOrSliceContext ||
                child is CypherParser.SliceFromToContext ||
                child is CypherParser.SliceToContext ||
                child is CypherParser.SubscriptIndexContext -> {
                if (subIdx < subscripts.size) {
                    result = visitSubscriptOrSlice(result, subscripts[subIdx])
                    subIdx++
                }
            }
        }
    }
    return result
}

internal fun visitSubscriptOrSlice(target: CypherExpr, ctx: CypherParser.SubscriptOrSliceContext): CypherExpr {
    return when (ctx) {
        is CypherParser.SliceFromToContext -> {
            val from = visitExpr(ctx.expression(0))
            val to = ctx.expression(1)?.let { visitExpr(it) }
            CypherExpr.Slice(target, from, to)
        }
        is CypherParser.SliceToContext -> {
            val to = visitExpr(ctx.expression())
            CypherExpr.Slice(target, null, to)
        }
        is CypherParser.SubscriptIndexContext -> {
            val index = visitExpr(ctx.expression())
            CypherExpr.Subscript(target, index)
        }
        else -> throw CypherParseException("Unknown subscript/slice type")
    }
}

private fun findOperator(
    children: List<org.antlr.v4.runtime.tree.ParseTree>,
    index: Int,
    operators: Map<Int, String>,
    description: String
): String =
    children
        .asSequence()
        .filterIsInstance<org.antlr.v4.runtime.tree.TerminalNode>()
        .mapNotNull { operators[it.symbol.type] }
        .elementAtOrNull(index) ?: throw CypherParseException("Could not find $description operator at index $index")
