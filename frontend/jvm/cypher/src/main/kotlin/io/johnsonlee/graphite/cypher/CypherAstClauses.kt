package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.cypher.parser.CypherParser

internal fun visitScript(ctx: CypherParser.ScriptContext): List<CypherClause> {
    val clauses = mutableListOf<CypherClause>()
    for (stmt in ctx.statement()) {
        clauses.addAll(visitStatement(stmt))
    }
    return clauses
}

@Suppress("UNCHECKED_CAST")
internal fun visitStatement(ctx: CypherParser.StatementContext): List<CypherClause> {
    return visitQuery(ctx.query())
}

internal fun visitQuery(ctx: CypherParser.QueryContext): List<CypherClause> {
    return visitRegularQuery(ctx.regularQuery())
}

internal fun visitRegularQuery(ctx: CypherParser.RegularQueryContext): List<CypherClause> {
    val clauses = mutableListOf<CypherClause>()
    val queries = ctx.singleQuery()
    val unions = ctx.unionSt()

    clauses.addAll(visitSingleQuery(queries[0]))
    for (i in unions.indices) {
        clauses.add(visitUnionSt(unions[i]))
        if (i + 1 < queries.size) {
            clauses.addAll(visitSingleQuery(queries[i + 1]))
        }
    }
    return clauses
}

internal fun visitUnionSt(ctx: CypherParser.UnionStContext): CypherClause.Union {
    return CypherClause.Union(all = ctx.ALL() != null)
}

internal fun visitSingleQuery(ctx: CypherParser.SingleQueryContext): List<CypherClause> {
    val clauses = mutableListOf<CypherClause>()
    for (clause in ctx.clause()) {
        clauses.addAll(visitClause(clause))
    }
    return clauses
}

internal fun visitClause(ctx: CypherParser.ClauseContext): List<CypherClause> {
    val clauses = mutableListOf<CypherClause>()
    when {
        ctx.matchSt() != null -> {
            val matchCtx = ctx.matchSt()
            clauses.add(visitMatchSt(matchCtx))
            if (matchCtx.whereSt() != null && matchCtx.OPTIONAL() == null) {
                clauses.add(visitWhereSt(matchCtx.whereSt()))
            }
        }
        ctx.unwindSt() != null -> clauses.add(visitUnwindStClause(ctx.unwindSt()))
        ctx.withSt() != null -> clauses.addAll(visitWithStClauses(ctx.withSt()))
        ctx.returnSt() != null -> clauses.addAll(visitReturnStClauses(ctx.returnSt()))
        ctx.createSt() != null -> clauses.add(visitCreateStClause(ctx.createSt()))
        ctx.deleteSt() != null -> clauses.add(visitDeleteStClause(ctx.deleteSt()))
        ctx.setSt() != null -> clauses.add(visitSetStClause(ctx.setSt()))
        ctx.removeSt() != null -> clauses.add(visitRemoveStClause(ctx.removeSt()))
        ctx.mergeSt() != null -> clauses.add(visitMergeStClause(ctx.mergeSt()))
    }
    return clauses
}

internal fun visitMatchSt(ctx: CypherParser.MatchStContext): CypherClause.Match {
    val optional = ctx.OPTIONAL() != null
    val patterns = visitPatternList(ctx.patternList())
    val where = if (optional) ctx.whereSt()?.let { visitExpr(it.expression()) } else null
    return CypherClause.Match(patterns, optional, where)
}

internal fun visitWhereSt(ctx: CypherParser.WhereStContext): CypherClause.Where {
    return CypherClause.Where(visitExpr(ctx.expression()))
}

internal fun visitCreateStClause(ctx: CypherParser.CreateStContext): CypherClause.Create {
    return CypherClause.Create(visitPatternList(ctx.patternList()))
}

internal fun visitDeleteStClause(ctx: CypherParser.DeleteStContext): CypherClause.Delete {
    val detach = ctx.DETACH() != null
    val exprs = ctx.expressionList().expression().map { visitExpr(it) }
    return CypherClause.Delete(exprs, detach)
}

internal fun visitSetStClause(ctx: CypherParser.SetStContext): CypherClause.Set {
    val items = ctx.setItem().map { visitSetItem(it) }
    return CypherClause.Set(items)
}

internal fun visitSetItem(ctx: CypherParser.SetItemContext): SetItem {
    return when (ctx) {
        is CypherParser.SetPropertyContext -> {
            val variable = getSymbolicName(ctx.variable())
            val property = getSymbolicName(ctx.propertyKeyName())
            val expr = visitExpr(ctx.expression())
            SetItem.PropertySet(variable, property, expr)
        }
        is CypherParser.SetMergePropertiesContext -> {
            val variable = getSymbolicName(ctx.variable())
            val expr = visitExpr(ctx.expression())
            SetItem.MergePropertiesSet(variable, expr)
        }
        is CypherParser.SetAllPropertiesContext -> {
            val variable = getSymbolicName(ctx.variable())
            val expr = visitExpr(ctx.expression())
            SetItem.AllPropertiesSet(variable, expr)
        }
        is CypherParser.SetLabelsContext -> {
            val variable = getSymbolicName(ctx.variable())
            val labels = ctx.nodeLabels().labelName().map { getSymbolicName(it) }
            SetItem.LabelSet(variable, labels)
        }
        else -> throw CypherParseException("Unknown SET item type")
    }
}

internal fun visitRemoveStClause(ctx: CypherParser.RemoveStContext): CypherClause.Remove {
    val items = ctx.removeItem().map { visitRemoveItem(it) }
    return CypherClause.Remove(items)
}

internal fun visitRemoveItem(ctx: CypherParser.RemoveItemContext): RemoveItem {
    return when (ctx) {
        is CypherParser.RemovePropertyContext -> {
            val variable = getSymbolicName(ctx.variable())
            val property = getSymbolicName(ctx.propertyKeyName())
            RemoveItem.PropertyRemove(variable, property)
        }
        is CypherParser.RemoveLabelsContext -> {
            val variable = getSymbolicName(ctx.variable())
            val labels = ctx.nodeLabels().labelName().map { getSymbolicName(it) }
            RemoveItem.LabelRemove(variable, labels)
        }
        else -> throw CypherParseException("Unknown REMOVE item type")
    }
}

internal fun visitMergeStClause(ctx: CypherParser.MergeStContext): CypherClause.Create {
    return CypherClause.Create(listOf(visitPatternPart(ctx.patternPart())))
}
