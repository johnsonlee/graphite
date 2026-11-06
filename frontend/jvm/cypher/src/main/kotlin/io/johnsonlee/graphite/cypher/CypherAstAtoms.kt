package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.cypher.parser.CypherParser

internal fun visitAtomExpr(ctx: CypherParser.AtomExpressionContext): CypherExpr {
    return when (ctx) {
        is CypherParser.LiteralAtomContext -> visitLiteral(ctx.literal())
        is CypherParser.ParameterAtomContext -> {
            val name = getSymbolicName(ctx.parameter().symbolicName())
            CypherExpr.Parameter(name)
        }
        is CypherParser.CaseAtomContext -> visitCaseExpr(ctx.caseExpression())
        is CypherParser.CountStarAtomContext -> CypherExpr.CountStar
        is CypherParser.ListComprehensionAtomContext -> visitListComprehensionExpr(ctx.listComprehension())
        is CypherParser.PredicateFunctionAtomContext -> visitPredicateFunctionExpr(ctx.predicateFunction())
        is CypherParser.ExistsAtomContext -> {
            val expr = visitExpr(ctx.expression())
            CypherExpr.FunctionCall("exists", listOf(expr))
        }
        is CypherParser.FunctionAtomContext -> visitFunctionInvocationExpr(ctx.functionInvocation())
        is CypherParser.ParenAtomContext -> visitExpr(ctx.expression())
        is CypherParser.DistinctAtomContext -> CypherExpr.Distinct(visitUnaryExpr(ctx.unaryExpression()))
        is CypherParser.VariableAtomContext -> CypherExpr.Variable(getSymbolicName(ctx.variable()))
        else -> throw CypherParseException("Unknown atom expression type")
    }
}

internal fun visitLiteral(ctx: CypherParser.LiteralContext): CypherExpr {
    return when (ctx) {
        is CypherParser.TrueLiteralContext -> CypherExpr.Literal(true)
        is CypherParser.FalseLiteralContext -> CypherExpr.Literal(false)
        is CypherParser.NullLiteralContext -> CypherExpr.Literal(null)
        is CypherParser.IntLiteralContext -> {
            CypherExpr.Literal(integralValue(ctx.integerLiteral().text))
        }
        is CypherParser.FloatLitContext -> CypherExpr.Literal(ctx.floatLiteral().text.toDouble())
        is CypherParser.StringLitContext -> CypherExpr.Literal(parseStringLiteral(ctx.stringLiteral().text))
        is CypherParser.ListLitContext -> {
            val elements = ctx.listLiteral().expressionList()?.expression()?.map { visitExpr(it) } ?: emptyList()
            CypherExpr.ListLiteral(elements)
        }
        is CypherParser.MapLitContext -> {
            val entries = visitMapLiteralAsExprMap(ctx.mapLiteral())
            CypherExpr.MapLiteral(entries)
        }
        else -> throw CypherParseException("Unknown literal type")
    }
}

internal fun visitFunctionInvocationExpr(ctx: CypherParser.FunctionInvocationContext): CypherExpr {
    val name = ctx.functionName().symbolicName().joinToString(".") { getSymbolicName(it) }
    if (name.equals("count", ignoreCase = true) && ctx.expressionList() == null) {
        // Could be count() with no args, but count(*) is a separate rule
        return CypherExpr.FunctionCall(name, emptyList())
    }
    val distinct = ctx.DISTINCT() != null
    val args = ctx.expressionList()?.expression()?.map { visitExpr(it) } ?: emptyList()
    return CypherExpr.FunctionCall(name, args, distinct)
}

internal fun visitPredicateFunctionExpr(ctx: CypherParser.PredicateFunctionContext): CypherExpr {
    val name =
        when {
            ctx.ANY() != null -> "any"
            ctx.ALL() != null -> "all"
            ctx.NONE() != null -> "none"
            ctx.SINGLE() != null -> "single"
            else -> throw CypherParseException("Unknown predicate function")
        }
    val expressions = ctx.expression()
    val listExpr = visitExpr(expressions[0])
    val predicate = expressions.getOrNull(1)?.let { visitExpr(it) }
    return CypherExpr.PredicateFunction(name, getSymbolicName(ctx.variable()), listExpr, predicate)
}

internal fun visitCaseExpr(ctx: CypherParser.CaseExpressionContext): CypherExpr {
    // CASE expr? (WHEN expr THEN expr)+ (ELSE expr)? END
    // ctx.expression() returns the optional test expression (simple CASE)
    val test = ctx.expression()?.let { visitExpr(it) }

    val whens =
        ctx.caseWhen().map { w ->
            val cond = visitExpr(w.expression(0))
            val result = visitExpr(w.expression(1))
            cond to result
        }

    val elseExpr = ctx.caseElse()?.let { visitExpr(it.expression()) }

    return CypherExpr.CaseExpr(test, whens, elseExpr)
}

internal fun visitListComprehensionExpr(ctx: CypherParser.ListComprehensionContext): CypherExpr {
    val varName = getSymbolicName(ctx.variable())
    val expressions = ctx.expression()

    // [x IN listExpr WHERE predicate | mapExpr]
    val listExpr = visitExpr(expressions[0])

    // Check for WHERE and PIPE
    val hasWhere = ctx.WHERE() != null
    val hasStick = ctx.STICK() != null

    var predicate: CypherExpr? = null
    var mapExpr: CypherExpr? = null

    when {
        hasWhere && hasStick && expressions.size >= LIST_COMPREHENSION_FULL_FORM_SIZE -> {
            predicate = visitExpr(expressions[LIST_COMPREHENSION_PREDICATE_INDEX])
            mapExpr = visitExpr(expressions[LIST_COMPREHENSION_MAP_INDEX])
        }
        hasWhere && !hasStick && expressions.size >= LIST_COMPREHENSION_PARTIAL_FORM_SIZE -> {
            predicate = visitExpr(expressions[LIST_COMPREHENSION_PREDICATE_INDEX])
        }
        !hasWhere && hasStick && expressions.size >= LIST_COMPREHENSION_PARTIAL_FORM_SIZE -> {
            mapExpr = visitExpr(expressions[LIST_COMPREHENSION_PREDICATE_INDEX])
        }
    }

    return CypherExpr.ListComprehension(varName, listExpr, predicate, mapExpr)
}

internal fun visitMapLiteralAsExprMap(ctx: CypherParser.MapLiteralContext): Map<String, CypherExpr> {
    val map = mutableMapOf<String, CypherExpr>()
    for (pair in ctx.mapPair()) {
        val key = getSymbolicName(pair.propertyKeyName())
        val value = visitExpr(pair.expression())
        map[key] = value
    }
    return map
}

private fun integralValue(text: String): Number =
    when {
        text.startsWith("0x", true) -> java.lang.Long.decode(text)
        text.startsWith("0o", true) -> text.substring(OCTAL_PREFIX_LENGTH).toLong(OCTAL_RADIX)
        else -> text.toLong().let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
    }
