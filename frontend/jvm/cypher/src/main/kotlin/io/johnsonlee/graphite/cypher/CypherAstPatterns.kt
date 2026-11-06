package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.cypher.parser.CypherParser

internal object CypherAstPatterns {
    fun visitPatternList(ctx: CypherParser.PatternListContext): List<CypherPattern> {
        return ctx.patternPart().map { visitPatternPart(it) }
    }

    fun visitPatternPart(ctx: CypherParser.PatternPartContext): CypherPattern {
        val pathVariable = ctx.variable()?.let { CypherAstNames.getSymbolicName(it) }
        val elements = visitPatternElement(ctx.patternElement())
        return CypherPattern(elements, pathVariable)
    }

    fun visitPatternElement(ctx: CypherParser.PatternElementContext): List<PatternElement> {
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

    fun visitNodePattern(ctx: CypherParser.NodePatternContext): PatternElement.NodePattern {
        val variable = ctx.variable()?.let { CypherAstNames.getSymbolicName(it) }
        val labels = ctx.nodeLabels()?.labelName()?.map { CypherAstNames.getSymbolicName(it) } ?: emptyList()
        val props = ctx.properties()?.let { CypherAstAtoms.visitMapLiteralAsExprMap(it.mapLiteral()) } ?: emptyMap()
        return PatternElement.NodePattern(variable, labels, props)
    }

    fun visitRelationshipPattern(ctx: CypherParser.RelationshipPatternContext): PatternElement.RelationshipPattern {
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

        val variable = detail?.variable()?.let { CypherAstNames.getSymbolicName(it) }
        val types = detail?.relationshipTypes()?.relTypeName()?.map { CypherAstNames.getSymbolicName(it) } ?: emptyList()
        val props = detail?.properties()?.let { CypherAstAtoms.visitMapLiteralAsExprMap(it.mapLiteral()) } ?: emptyMap()
        val (variableLength, minimum, maximum) = relationshipLength(detail?.rangeLiteral())
        return PatternElement.RelationshipPattern(variable, types, props, direction, minimum, maximum, variableLength)
    }

    private fun relationshipLength(range: CypherParser.RangeLiteralContext?): Triple<Boolean, Int?, Int?> {
        if (range == null) return Triple(false, null, null)
        val integers = range.integerLiteral()
        val hasRange = range.RANGE() != null
        return when {
            integers.size == 2 && hasRange -> Triple(true,
                CypherAstNames.parseIntLiteral(integers[0]), CypherAstNames.parseIntLiteral(integers[1]))
            integers.size == 1 && hasRange -> singleSidedLength(range, integers[0])
            integers.size == 1 -> CypherAstNames.parseIntLiteral(integers[0]).let { Triple(true, it, it) }
            integers.isEmpty() && hasRange -> Triple(true, 1, null)
            else -> Triple(true, null, null)
        }
    }

    private fun singleSidedLength(
        range: CypherParser.RangeLiteralContext,
        integer: CypherParser.IntegerLiteralContext
    ): Triple<Boolean, Int?, Int?> =
        if (integer.start.startIndex < range.RANGE().symbol.startIndex) {
            Triple(true, CypherAstNames.parseIntLiteral(integer), null)
        } else Triple(true, 1, CypherAstNames.parseIntLiteral(integer))
}
