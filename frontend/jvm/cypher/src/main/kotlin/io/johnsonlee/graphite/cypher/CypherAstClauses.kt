package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.cypher.parser.CypherParser

internal object CypherAstClauses {
    fun visitScript(ctx: CypherParser.ScriptContext): List<CypherClause> {
        val clauses = mutableListOf<CypherClause>()
        for (stmt in ctx.statement()) {
            clauses.addAll(visitStatement(stmt))
        }
        return clauses
    }

    @Suppress("UNCHECKED_CAST")
    fun visitStatement(ctx: CypherParser.StatementContext): List<CypherClause> {
        return visitQuery(ctx.query())
    }

    fun visitQuery(ctx: CypherParser.QueryContext): List<CypherClause> {
        return visitRegularQuery(ctx.regularQuery())
    }

    fun visitRegularQuery(ctx: CypherParser.RegularQueryContext): List<CypherClause> {
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

    fun visitUnionSt(ctx: CypherParser.UnionStContext): CypherClause.Union {
        return CypherClause.Union(all = ctx.ALL() != null)
    }

    fun visitSingleQuery(ctx: CypherParser.SingleQueryContext): List<CypherClause> {
        val clauses = mutableListOf<CypherClause>()
        for (clause in ctx.clause()) {
            clauses.addAll(visitClause(clause))
        }
        return clauses
    }

    fun visitClause(ctx: CypherParser.ClauseContext): List<CypherClause> {
        val clauses = mutableListOf<CypherClause>()
        when {
            ctx.matchSt() != null -> {
                val matchCtx = ctx.matchSt()
                clauses.add(visitMatchSt(matchCtx))
                if (matchCtx.whereSt() != null && matchCtx.OPTIONAL() == null) {
                    clauses.add(visitWhereSt(matchCtx.whereSt()))
                }
            }
            ctx.unwindSt() != null -> clauses.add(CypherAstProjections.visitUnwindStClause(ctx.unwindSt()))
            ctx.withSt() != null -> clauses.addAll(CypherAstProjections.visitWithStClauses(ctx.withSt()))
            ctx.returnSt() != null -> clauses.addAll(CypherAstProjections.visitReturnStClauses(ctx.returnSt()))
            ctx.createSt() != null -> clauses.add(visitCreateStClause(ctx.createSt()))
            ctx.deleteSt() != null -> clauses.add(visitDeleteStClause(ctx.deleteSt()))
            ctx.setSt() != null -> clauses.add(visitSetStClause(ctx.setSt()))
            ctx.removeSt() != null -> clauses.add(visitRemoveStClause(ctx.removeSt()))
            ctx.mergeSt() != null -> clauses.add(visitMergeStClause(ctx.mergeSt()))
        }
        return clauses
    }

    fun visitMatchSt(ctx: CypherParser.MatchStContext): CypherClause.Match {
        val optional = ctx.OPTIONAL() != null
        val patterns = CypherAstPatterns.visitPatternList(ctx.patternList())
        val where = if (optional) ctx.whereSt()?.let { CypherAstExpressions.visitExpr(it.expression()) } else null
        return CypherClause.Match(patterns, optional, where)
    }

    fun visitWhereSt(ctx: CypherParser.WhereStContext): CypherClause.Where {
        return CypherClause.Where(CypherAstExpressions.visitExpr(ctx.expression()))
    }

    fun visitCreateStClause(ctx: CypherParser.CreateStContext): CypherClause.Create {
        return CypherClause.Create(CypherAstPatterns.visitPatternList(ctx.patternList()))
    }

    fun visitDeleteStClause(ctx: CypherParser.DeleteStContext): CypherClause.Delete {
        val detach = ctx.DETACH() != null
        val exprs = ctx.expressionList().expression().map { CypherAstExpressions.visitExpr(it) }
        return CypherClause.Delete(exprs, detach)
    }

    fun visitSetStClause(ctx: CypherParser.SetStContext): CypherClause.Set {
        val items = ctx.setItem().map { visitSetItem(it) }
        return CypherClause.Set(items)
    }

    fun visitSetItem(ctx: CypherParser.SetItemContext): SetItem {
        return when (ctx) {
            is CypherParser.SetPropertyContext -> {
                val variable = CypherAstNames.getSymbolicName(ctx.variable())
                val property = CypherAstNames.getSymbolicName(ctx.propertyKeyName())
                val expr = CypherAstExpressions.visitExpr(ctx.expression())
                SetItem.PropertySet(variable, property, expr)
            }
            is CypherParser.SetMergePropertiesContext -> {
                val variable = CypherAstNames.getSymbolicName(ctx.variable())
                val expr = CypherAstExpressions.visitExpr(ctx.expression())
                SetItem.MergePropertiesSet(variable, expr)
            }
            is CypherParser.SetAllPropertiesContext -> {
                val variable = CypherAstNames.getSymbolicName(ctx.variable())
                val expr = CypherAstExpressions.visitExpr(ctx.expression())
                SetItem.AllPropertiesSet(variable, expr)
            }
            is CypherParser.SetLabelsContext -> {
                val variable = CypherAstNames.getSymbolicName(ctx.variable())
                val labels = ctx.nodeLabels().labelName().map { CypherAstNames.getSymbolicName(it) }
                SetItem.LabelSet(variable, labels)
            }
            else -> throw CypherParseException("Unknown SET item type")
        }
    }

    fun visitRemoveStClause(ctx: CypherParser.RemoveStContext): CypherClause.Remove {
        val items = ctx.removeItem().map { visitRemoveItem(it) }
        return CypherClause.Remove(items)
    }

    fun visitRemoveItem(ctx: CypherParser.RemoveItemContext): RemoveItem {
        return when (ctx) {
            is CypherParser.RemovePropertyContext -> {
                val variable = CypherAstNames.getSymbolicName(ctx.variable())
                val property = CypherAstNames.getSymbolicName(ctx.propertyKeyName())
                RemoveItem.PropertyRemove(variable, property)
            }
            is CypherParser.RemoveLabelsContext -> {
                val variable = CypherAstNames.getSymbolicName(ctx.variable())
                val labels = ctx.nodeLabels().labelName().map { CypherAstNames.getSymbolicName(it) }
                RemoveItem.LabelRemove(variable, labels)
            }
            else -> throw CypherParseException("Unknown REMOVE item type")
        }
    }

    fun visitMergeStClause(ctx: CypherParser.MergeStContext): CypherClause.Create {
        return CypherClause.Create(listOf(CypherAstPatterns.visitPatternPart(ctx.patternPart())))
    }
}
