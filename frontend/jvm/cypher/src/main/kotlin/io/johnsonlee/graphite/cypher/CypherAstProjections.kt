package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.cypher.parser.CypherParser

internal object CypherAstProjections {
    fun visitReturnStClauses(ctx: CypherParser.ReturnStContext): List<CypherClause> {
        val clauses = mutableListOf<CypherClause>()
        val distinct = ctx.DISTINCT() != null
        val items = visitReturnItemsList(ctx.returnItems())
        clauses.add(CypherClause.Return(items, distinct))
        if (ctx.orderBySt() != null) clauses.add(visitOrderBySt(ctx.orderBySt()))
        if (ctx.skipSt() != null) clauses.add(visitSkipSt(ctx.skipSt()))
        if (ctx.limitSt() != null) clauses.add(visitLimitSt(ctx.limitSt()))
        return clauses
    }

    fun visitWithStClauses(ctx: CypherParser.WithStContext): List<CypherClause> {
        val clauses = mutableListOf<CypherClause>()
        val distinct = ctx.DISTINCT() != null
        val items = visitReturnItemsList(ctx.returnItems())
        val where = ctx.whereSt()?.let { CypherAstExpressions.visitExpr(it.expression()) }
        clauses.add(CypherClause.With(items, distinct, where))
        if (ctx.orderBySt() != null) clauses.add(visitOrderBySt(ctx.orderBySt()))
        if (ctx.skipSt() != null) clauses.add(visitSkipSt(ctx.skipSt()))
        if (ctx.limitSt() != null) clauses.add(visitLimitSt(ctx.limitSt()))
        return clauses
    }

    fun visitUnwindStClause(ctx: CypherParser.UnwindStContext): CypherClause.Unwind {
        val expr = CypherAstExpressions.visitExpr(ctx.expression())
        val variable = CypherAstNames.getSymbolicName(ctx.variable())
        return CypherClause.Unwind(expr, variable)
    }

    fun visitOrderBySt(ctx: CypherParser.OrderByStContext): CypherClause.OrderBy {
        val items = ctx.orderItem().map { item ->
            val expr = CypherAstExpressions.visitExpr(item.expression())
            val asc = when {
                item.DESC() != null || item.DESCENDING() != null -> false
                else -> true
            }
            SortItem(expr, asc)
        }
        return CypherClause.OrderBy(items)
    }

    fun visitSkipSt(ctx: CypherParser.SkipStContext): CypherClause.Skip {
        return CypherClause.Skip(CypherAstExpressions.visitExpr(ctx.expression()))
    }

    fun visitLimitSt(ctx: CypherParser.LimitStContext): CypherClause.Limit {
        return CypherClause.Limit(CypherAstExpressions.visitExpr(ctx.expression()))
    }

    fun visitReturnItemsList(ctx: CypherParser.ReturnItemsContext): List<ReturnItem> {
        if (ctx.MULT() != null) {
            return listOf(ReturnItem(CypherExpr.Variable("*")))
        }
        return ctx.returnItem().map { item ->
            val expr = CypherAstExpressions.visitExpr(item.expression())
            val alias = item.variable()?.let { CypherAstNames.getSymbolicName(it) }
            ReturnItem(expr, alias)
        }
    }
}
