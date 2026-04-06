package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.cypher.parser.CypherLexer
import io.johnsonlee.graphite.cypher.parser.CypherParser
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer

/**
 * Parses a Cypher query string into a list of [CypherClause] AST nodes.
 *
 * Uses ANTLR-generated [CypherParser] for parsing and a custom visitor
 * to build the internal AST.
 */
object CypherDslAdapter {

    /**
     * Parse a Cypher query string into an ordered list of clauses.
     *
     * @param cypher the Cypher query text
     * @return ordered list of [CypherClause] representing the query structure
     * @throws CypherParseException if the query cannot be parsed
     */
    fun parse(cypher: String): List<CypherClause> {
        if (cypher.isBlank()) return emptyList()

        // Handle semicolon-separated statements
        if (cypher.contains(';')) {
            val parts = cypher.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size > 1) {
                return parts.flatMap { parse(it) }
            }
        }

        return buildAst(cypher)
    }

    internal fun buildAst(cypher: String): List<CypherClause> {
        val lexer = CypherLexer(CharStreams.fromString(cypher))
        lexer.removeErrorListeners()
        lexer.addErrorListener(ThrowingErrorListener)

        val tokens = CommonTokenStream(lexer)
        val parser = CypherParser(tokens)
        parser.removeErrorListeners()
        parser.addErrorListener(ThrowingErrorListener)

        val tree = parser.script()
        val visitor = CypherAstVisitor()
        return visitor.visitScript(tree)
    }
}

/**
 * Exception thrown when the Cypher parser encounters invalid syntax.
 */
class CypherParseException(message: String) : RuntimeException(message)

/**
 * ANTLR error listener that throws [CypherParseException] on any syntax error.
 */
private object ThrowingErrorListener : BaseErrorListener() {
    override fun syntaxError(
        recognizer: Recognizer<*, *>?,
        offendingSymbol: Any?,
        line: Int,
        charPositionInLine: Int,
        msg: String?,
        e: RecognitionException?
    ) {
        throw CypherParseException("Syntax error at position $charPositionInLine: $msg")
    }
}

// ============================================================================
// ANTLR Visitor that builds internal AST
// ============================================================================

private class CypherAstVisitor {

    // -- Script / top-level ---------------------------------------------------

    fun visitScript(ctx: CypherParser.ScriptContext): List<CypherClause> {
        val clauses = mutableListOf<CypherClause>()
        for (stmt in ctx.statement()) {
            clauses.addAll(visitStatement(stmt))
        }
        return clauses
    }

    @Suppress("UNCHECKED_CAST")
    private fun visitStatement(ctx: CypherParser.StatementContext): List<CypherClause> {
        return visitQuery(ctx.query())
    }

    private fun visitQuery(ctx: CypherParser.QueryContext): List<CypherClause> {
        return visitRegularQuery(ctx.regularQuery())
    }

    private fun visitRegularQuery(ctx: CypherParser.RegularQueryContext): List<CypherClause> {
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

    private fun visitUnionSt(ctx: CypherParser.UnionStContext): CypherClause.Union {
        return CypherClause.Union(all = ctx.ALL() != null)
    }

    private fun visitSingleQuery(ctx: CypherParser.SingleQueryContext): List<CypherClause> {
        val clauses = mutableListOf<CypherClause>()
        for (clause in ctx.clause()) {
            clauses.addAll(visitClause(clause))
        }
        return clauses
    }

    private fun visitClause(ctx: CypherParser.ClauseContext): List<CypherClause> {
        val clauses = mutableListOf<CypherClause>()
        when {
            ctx.matchSt() != null -> {
                val matchCtx = ctx.matchSt()
                clauses.add(visitMatchSt(matchCtx))
                if (matchCtx.whereSt() != null) {
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

    // -- Clause visitors ------------------------------------------------------

    private fun visitMatchSt(ctx: CypherParser.MatchStContext): CypherClause.Match {
        val optional = ctx.OPTIONAL() != null
        val patterns = visitPatternList(ctx.patternList())
        return CypherClause.Match(patterns, optional)
    }

    private fun visitWhereSt(ctx: CypherParser.WhereStContext): CypherClause.Where {
        return CypherClause.Where(visitExpr(ctx.expression()))
    }

    private fun visitReturnStClauses(ctx: CypherParser.ReturnStContext): List<CypherClause> {
        val clauses = mutableListOf<CypherClause>()
        val distinct = ctx.DISTINCT() != null
        val items = visitReturnItemsList(ctx.returnItems())
        clauses.add(CypherClause.Return(items, distinct))
        if (ctx.orderBySt() != null) clauses.add(visitOrderBySt(ctx.orderBySt()))
        if (ctx.skipSt() != null) clauses.add(visitSkipSt(ctx.skipSt()))
        if (ctx.limitSt() != null) clauses.add(visitLimitSt(ctx.limitSt()))
        return clauses
    }

    private fun visitWithStClauses(ctx: CypherParser.WithStContext): List<CypherClause> {
        val clauses = mutableListOf<CypherClause>()
        val distinct = ctx.DISTINCT() != null
        val items = visitReturnItemsList(ctx.returnItems())
        val where = ctx.whereSt()?.let { visitExpr(it.expression()) }
        clauses.add(CypherClause.With(items, distinct, where))
        if (ctx.orderBySt() != null) clauses.add(visitOrderBySt(ctx.orderBySt()))
        if (ctx.skipSt() != null) clauses.add(visitSkipSt(ctx.skipSt()))
        if (ctx.limitSt() != null) clauses.add(visitLimitSt(ctx.limitSt()))
        return clauses
    }

    private fun visitUnwindStClause(ctx: CypherParser.UnwindStContext): CypherClause.Unwind {
        val expr = visitExpr(ctx.expression())
        val variable = getSymbolicName(ctx.variable())
        return CypherClause.Unwind(expr, variable)
    }

    private fun visitOrderBySt(ctx: CypherParser.OrderByStContext): CypherClause.OrderBy {
        val items = ctx.orderItem().map { item ->
            val expr = visitExpr(item.expression())
            val asc = when {
                item.DESC() != null || item.DESCENDING() != null -> false
                else -> true
            }
            SortItem(expr, asc)
        }
        return CypherClause.OrderBy(items)
    }

    private fun visitSkipSt(ctx: CypherParser.SkipStContext): CypherClause.Skip {
        return CypherClause.Skip(visitExpr(ctx.expression()))
    }

    private fun visitLimitSt(ctx: CypherParser.LimitStContext): CypherClause.Limit {
        return CypherClause.Limit(visitExpr(ctx.expression()))
    }

    private fun visitCreateStClause(ctx: CypherParser.CreateStContext): CypherClause.Create {
        return CypherClause.Create(visitPatternList(ctx.patternList()))
    }

    private fun visitDeleteStClause(ctx: CypherParser.DeleteStContext): CypherClause.Delete {
        val detach = ctx.DETACH() != null
        val exprs = ctx.expressionList().expression().map { visitExpr(it) }
        return CypherClause.Delete(exprs, detach)
    }

    private fun visitSetStClause(ctx: CypherParser.SetStContext): CypherClause.Set {
        val items = ctx.setItem().map { visitSetItem(it) }
        return CypherClause.Set(items)
    }

    private fun visitSetItem(ctx: CypherParser.SetItemContext): SetItem {
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

    private fun visitRemoveStClause(ctx: CypherParser.RemoveStContext): CypherClause.Remove {
        val items = ctx.removeItem().map { visitRemoveItem(it) }
        return CypherClause.Remove(items)
    }

    private fun visitRemoveItem(ctx: CypherParser.RemoveItemContext): RemoveItem {
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

    private fun visitMergeStClause(ctx: CypherParser.MergeStContext): CypherClause.Create {
        return CypherClause.Create(listOf(visitPatternPart(ctx.patternPart())))
    }

    // -- Return items ---------------------------------------------------------

    private fun visitReturnItemsList(ctx: CypherParser.ReturnItemsContext): List<ReturnItem> {
        if (ctx.MULT() != null) {
            return listOf(ReturnItem(CypherExpr.Variable("*")))
        }
        return ctx.returnItem().map { item ->
            val expr = visitExpr(item.expression())
            val alias = item.variable()?.let { getSymbolicName(it) }
            ReturnItem(expr, alias)
        }
    }

    // -- Pattern parsing ------------------------------------------------------

    private fun visitPatternList(ctx: CypherParser.PatternListContext): List<CypherPattern> {
        return ctx.patternPart().map { visitPatternPart(it) }
    }

    private fun visitPatternPart(ctx: CypherParser.PatternPartContext): CypherPattern {
        val pathVariable = ctx.variable()?.let { getSymbolicName(it) }
        val elements = visitPatternElement(ctx.patternElement())
        return CypherPattern(elements, pathVariable)
    }

    private fun visitPatternElement(ctx: CypherParser.PatternElementContext): List<PatternElement> {
        val elements = mutableListOf<PatternElement>()
        val nodePatterns = ctx.nodePattern()
        val relPatterns = ctx.relationshipPattern()

        elements.add(visitNodePattern(nodePatterns[0]))
        for (i in relPatterns.indices) {
            elements.add(visitRelationshipPattern(relPatterns[i]))
            if (i + 1 < nodePatterns.size) {
                elements.add(visitNodePattern(nodePatterns[i + 1]))
            }
        }
        return elements
    }

    private fun visitNodePattern(ctx: CypherParser.NodePatternContext): PatternElement.NodePattern {
        val variable = ctx.variable()?.let { getSymbolicName(it) }
        val labels = ctx.nodeLabels()?.labelName()?.map { getSymbolicName(it) } ?: emptyList()
        val props = ctx.properties()?.let { visitMapLiteralAsExprMap(it.mapLiteral()) } ?: emptyMap()
        return PatternElement.NodePattern(variable, labels, props)
    }

    private fun visitRelationshipPattern(ctx: CypherParser.RelationshipPatternContext): PatternElement.RelationshipPattern {
        val detail = when (ctx) {
            is CypherParser.RelFullPatternContext -> ctx.relationDetail()
            is CypherParser.RelLeftPatternContext -> ctx.relationDetail()
            is CypherParser.RelRightPatternContext -> ctx.relationDetail()
            is CypherParser.RelBothPatternContext -> ctx.relationDetail()
            else -> null
        }

        val direction = when (ctx) {
            is CypherParser.RelLeftPatternContext -> Direction.INCOMING
            is CypherParser.RelRightPatternContext -> Direction.OUTGOING
            is CypherParser.RelFullPatternContext -> Direction.BOTH
            is CypherParser.RelBothPatternContext -> Direction.BOTH
            else -> Direction.BOTH
        }

        var variable: String? = null
        var types = emptyList<String>()
        var props = emptyMap<String, CypherExpr>()
        var varLen = false
        var minH: Int? = null
        var maxH: Int? = null

        if (detail != null) {
            variable = detail.variable()?.let { getSymbolicName(it) }
            types = detail.relationshipTypes()?.relTypeName()?.map { getSymbolicName(it) } ?: emptyList()
            props = detail.properties()?.let { visitMapLiteralAsExprMap(it.mapLiteral()) } ?: emptyMap()

            val range = detail.rangeLiteral()
            if (range != null) {
                varLen = true
                val intLiterals = range.integerLiteral()
                val hasRange = range.RANGE() != null

                when {
                    intLiterals.size == 2 && hasRange -> {
                        minH = parseIntLiteral(intLiterals[0])
                        maxH = parseIntLiteral(intLiterals[1])
                    }
                    intLiterals.size == 1 && hasRange -> {
                        // Could be min.. or ..max
                        // Need to check if the integer comes before or after RANGE
                        val intToken = intLiterals[0].start
                        val rangeToken = range.RANGE().symbol
                        if (intToken.startIndex < rangeToken.startIndex) {
                            // n..  (min only)
                            minH = parseIntLiteral(intLiterals[0])
                        } else {
                            // ..n  (max only)
                            minH = 1
                            maxH = parseIntLiteral(intLiterals[0])
                        }
                    }
                    intLiterals.size == 1 && !hasRange -> {
                        // Exact: *3
                        val v = parseIntLiteral(intLiterals[0])
                        minH = v
                        maxH = v
                    }
                    intLiterals.isEmpty() && hasRange -> {
                        // *..  (unbounded with range - treat as *1..)
                        minH = 1
                    }
                    // intLiterals.isEmpty() && !hasRange -> just *, unbounded
                }
            }
        }

        return PatternElement.RelationshipPattern(variable, types, props, direction, minH, maxH, varLen)
    }

    private fun parseIntLiteral(ctx: CypherParser.IntegerLiteralContext): Int {
        val text = ctx.text
        return when {
            text.startsWith("0x", true) -> java.lang.Long.decode(text).toInt()
            text.startsWith("0o", true) -> text.substring(2).toInt(8)
            else -> text.toInt()
        }
    }

    // -- Expression visitors --------------------------------------------------

    private fun visitExpr(ctx: CypherParser.ExpressionContext): CypherExpr {
        return visitOrExpr(ctx.orExpression())
    }

    private fun visitOrExpr(ctx: CypherParser.OrExpressionContext): CypherExpr {
        val parts = ctx.xorExpression()
        var result = visitXorExpr(parts[0])
        for (i in 1 until parts.size) {
            result = CypherExpr.Or(result, visitXorExpr(parts[i]))
        }
        return result
    }

    private fun visitXorExpr(ctx: CypherParser.XorExpressionContext): CypherExpr {
        val parts = ctx.andExpression()
        var result = visitAndExpr(parts[0])
        for (i in 1 until parts.size) {
            result = CypherExpr.Xor(result, visitAndExpr(parts[i]))
        }
        return result
    }

    private fun visitAndExpr(ctx: CypherParser.AndExpressionContext): CypherExpr {
        val parts = ctx.notExpression()
        var result = visitNotExpr(parts[0])
        for (i in 1 until parts.size) {
            result = CypherExpr.And(result, visitNotExpr(parts[i]))
        }
        return result
    }

    private fun visitNotExpr(ctx: CypherParser.NotExpressionContext): CypherExpr {
        return if (ctx.NOT() != null) {
            CypherExpr.Not(visitNotExpr(ctx.notExpression()))
        } else {
            visitComparisonExpr(ctx.comparisonExpression())
        }
    }

    private fun visitComparisonExpr(ctx: CypherParser.ComparisonExpressionContext): CypherExpr {
        val operands = ctx.stringPredicateExpression()
        val ops = ctx.compOp()
        var result = visitStringPredicateExpr(operands[0])
        for (i in ops.indices) {
            val op = when {
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

    private fun visitStringPredicateExpr(ctx: CypherParser.StringPredicateExpressionContext): CypherExpr {
        var result = visitAddSubExpr(ctx.addSubExpression())
        for (suffix in ctx.stringPredicateSuffix()) {
            result = when (suffix) {
                is CypherParser.StartsWithPredicateContext ->
                    CypherExpr.StringOp("STARTS WITH", result, visitAddSubExpr(suffix.addSubExpression()))
                is CypherParser.EndsWithPredicateContext ->
                    CypherExpr.StringOp("ENDS WITH", result, visitAddSubExpr(suffix.addSubExpression()))
                is CypherParser.ContainsPredicateContext ->
                    CypherExpr.StringOp("CONTAINS", result, visitAddSubExpr(suffix.addSubExpression()))
                is CypherParser.InPredicateContext ->
                    CypherExpr.ListOp("IN", result, visitAddSubExpr(suffix.addSubExpression()))
                is CypherParser.RegexPredicateContext ->
                    CypherExpr.RegexMatch(result, visitAddSubExpr(suffix.addSubExpression()))
                is CypherParser.IsNullPredicateContext ->
                    CypherExpr.IsNull(result)
                is CypherParser.IsNotNullPredicateContext ->
                    CypherExpr.IsNotNull(result)
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

    private fun visitAddSubExpr(ctx: CypherParser.AddSubExpressionContext): CypherExpr {
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

    private fun findAddSubOp(ctx: CypherParser.AddSubExpressionContext, index: Int): String {
        var count = 0
        for (child in ctx.children) {
            if (child is org.antlr.v4.runtime.tree.TerminalNode) {
                when (child.symbol.type) {
                    CypherLexer.PLUS -> {
                        if (count == index) return "+"
                        count++
                    }
                    CypherLexer.SUB -> {
                        if (count == index) return "-"
                        count++
                    }
                }
            }
        }
        throw CypherParseException("Could not find add/sub operator at index $index")
    }

    private fun visitMultDivExpr(ctx: CypherParser.MultDivExpressionContext): CypherExpr {
        val operands = ctx.powerExpression()
        var result = visitPowerExpr(operands[0])
        var opIndex = 0
        for (i in 1 until operands.size) {
            val op = findMultDivOp(ctx, opIndex++)
            result = CypherExpr.BinaryOp(op, result, visitPowerExpr(operands[i]))
        }
        return result
    }

    private fun findMultDivOp(ctx: CypherParser.MultDivExpressionContext, index: Int): String {
        var count = 0
        for (child in ctx.children) {
            if (child is org.antlr.v4.runtime.tree.TerminalNode) {
                when (child.symbol.type) {
                    CypherLexer.MULT -> {
                        if (count == index) return "*"
                        count++
                    }
                    CypherLexer.DIV -> {
                        if (count == index) return "/"
                        count++
                    }
                    CypherLexer.MOD -> {
                        if (count == index) return "%"
                        count++
                    }
                }
            }
        }
        throw CypherParseException("Could not find mult/div/mod operator at index $index")
    }

    private fun visitPowerExpr(ctx: CypherParser.PowerExpressionContext): CypherExpr {
        val left = visitUnaryExpr(ctx.unaryExpression())
        // Right-associative via grammar: powerExpression : unaryExpression (CARET powerExpression)?
        return if (ctx.CARET() != null && ctx.powerExpression() != null) {
            CypherExpr.BinaryOp("^", left, visitPowerExpr(ctx.powerExpression()))
        } else {
            left
        }
    }

    private fun visitUnaryExpr(ctx: CypherParser.UnaryExpressionContext): CypherExpr {
        return when {
            ctx.SUB() != null -> CypherExpr.UnaryOp("-", visitUnaryExpr(ctx.unaryExpression()))
            ctx.PLUS() != null -> visitUnaryExpr(ctx.unaryExpression())
            else -> visitPostfixExpr(ctx.postfixExpression())
        }
    }

    private fun visitPostfixExpr(ctx: CypherParser.PostfixExpressionContext): CypherExpr {
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

    private fun visitSubscriptOrSlice(target: CypherExpr, ctx: CypherParser.SubscriptOrSliceContext): CypherExpr {
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

    private fun visitAtomExpr(ctx: CypherParser.AtomExpressionContext): CypherExpr {
        return when (ctx) {
            is CypherParser.LiteralAtomContext -> visitLiteral(ctx.literal())
            is CypherParser.ParameterAtomContext -> {
                val name = getSymbolicName(ctx.parameter().symbolicName())
                CypherExpr.Parameter(name)
            }
            is CypherParser.CaseAtomContext -> visitCaseExpr(ctx.caseExpression())
            is CypherParser.CountStarAtomContext -> CypherExpr.CountStar
            is CypherParser.ListComprehensionAtomContext -> visitListComprehensionExpr(ctx.listComprehension())
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

    private fun visitLiteral(ctx: CypherParser.LiteralContext): CypherExpr {
        return when (ctx) {
            is CypherParser.TrueLiteralContext -> CypherExpr.Literal(true)
            is CypherParser.FalseLiteralContext -> CypherExpr.Literal(false)
            is CypherParser.NullLiteralContext -> CypherExpr.Literal(null)
            is CypherParser.IntLiteralContext -> {
                val text = ctx.integerLiteral().text
                val v: Number = when {
                    text.startsWith("0x", true) -> java.lang.Long.decode(text)
                    text.startsWith("0o", true) -> text.substring(2).toLong(8)
                    else -> text.toLong().let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
                }
                CypherExpr.Literal(v)
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

    private fun visitFunctionInvocationExpr(ctx: CypherParser.FunctionInvocationContext): CypherExpr {
        val name = ctx.functionName().symbolicName().joinToString(".") { getSymbolicName(it) }
        if (name.equals("count", ignoreCase = true) && ctx.expressionList() == null) {
            // Could be count() with no args, but count(*) is a separate rule
            return CypherExpr.FunctionCall(name, emptyList())
        }
        val distinct = ctx.DISTINCT() != null
        val args = ctx.expressionList()?.expression()?.map { visitExpr(it) } ?: emptyList()
        return CypherExpr.FunctionCall(name, args, distinct)
    }

    private fun visitCaseExpr(ctx: CypherParser.CaseExpressionContext): CypherExpr {
        // CASE expr? (WHEN expr THEN expr)+ (ELSE expr)? END
        // ctx.expression() returns the optional test expression (simple CASE)
        val test = ctx.expression()?.let { visitExpr(it) }

        val whens = ctx.caseWhen().map { w ->
            val cond = visitExpr(w.expression(0))
            val result = visitExpr(w.expression(1))
            cond to result
        }

        val elseExpr = ctx.caseElse()?.let { visitExpr(it.expression()) }

        return CypherExpr.CaseExpr(test, whens, elseExpr)
    }

    private fun visitListComprehensionExpr(ctx: CypherParser.ListComprehensionContext): CypherExpr {
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
            hasWhere && hasStick && expressions.size >= 3 -> {
                predicate = visitExpr(expressions[1])
                mapExpr = visitExpr(expressions[2])
            }
            hasWhere && !hasStick && expressions.size >= 2 -> {
                predicate = visitExpr(expressions[1])
            }
            !hasWhere && hasStick && expressions.size >= 2 -> {
                mapExpr = visitExpr(expressions[1])
            }
        }

        return CypherExpr.ListComprehension(varName, listExpr, predicate, mapExpr)
    }

    // -- Map literal helper ---------------------------------------------------

    private fun visitMapLiteralAsExprMap(ctx: CypherParser.MapLiteralContext): Map<String, CypherExpr> {
        val map = mutableMapOf<String, CypherExpr>()
        for (pair in ctx.mapPair()) {
            val key = getSymbolicName(pair.propertyKeyName())
            val value = visitExpr(pair.expression())
            map[key] = value
        }
        return map
    }

    // -- String literal parsing -----------------------------------------------

    private fun parseStringLiteral(text: String): String {
        // Remove surrounding quotes
        val content = text.substring(1, text.length - 1)
        val quote = text[0]
        val sb = StringBuilder()
        var i = 0
        while (i < content.length) {
            val c = content[i]
            if (c == '\\' && i + 1 < content.length) {
                i++
                when (content[i]) {
                    '\\' -> sb.append('\\')
                    '\'' -> sb.append('\'')
                    '"' -> sb.append('"')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'u' -> {
                        if (i + 4 < content.length) {
                            sb.append(content.substring(i + 1, i + 5).toInt(16).toChar())
                            i += 4
                        }
                    }
                    else -> { sb.append('\\'); sb.append(content[i]) }
                }
            } else if (c == quote && i + 1 < content.length && content[i + 1] == quote) {
                // Escaped quote via doubling: '' or ""
                sb.append(quote)
                i++
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    // -- Name extraction helpers ----------------------------------------------

    private fun getSymbolicName(ctx: CypherParser.VariableContext): String {
        return getSymbolicName(ctx.symbolicName())
    }

    private fun getSymbolicName(ctx: CypherParser.LabelNameContext): String {
        return getSymbolicName(ctx.symbolicName())
    }

    private fun getSymbolicName(ctx: CypherParser.RelTypeNameContext): String {
        return getSymbolicName(ctx.symbolicName())
    }

    private fun getSymbolicName(ctx: CypherParser.PropertyKeyNameContext): String {
        return getSymbolicName(ctx.symbolicName())
    }

    private fun getSymbolicName(ctx: CypherParser.SymbolicNameContext): String {
        val esc = ctx.ESC_LITERAL()
        if (esc != null) {
            // Remove backtick delimiters
            val text = esc.text
            return text.substring(1, text.length - 1)
        }
        return ctx.text
    }
}
