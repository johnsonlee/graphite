// Code generated from graphite-cypher/src/main/antlr/CypherParser.g4 by ANTLR 4.13.2. DO NOT EDIT.

package generated // CypherParser
import "github.com/antlr4-go/antlr/v4"

type BaseCypherParserVisitor struct {
	*antlr.BaseParseTreeVisitor
}

func (v *BaseCypherParserVisitor) VisitScript(ctx *ScriptContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitStatement(ctx *StatementContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitQuery(ctx *QueryContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitRegularQuery(ctx *RegularQueryContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitUnionSt(ctx *UnionStContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitSingleQuery(ctx *SingleQueryContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitClause(ctx *ClauseContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitMatchSt(ctx *MatchStContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitUnwindSt(ctx *UnwindStContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitWithSt(ctx *WithStContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitReturnSt(ctx *ReturnStContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitWhereSt(ctx *WhereStContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitOrderBySt(ctx *OrderByStContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitOrderItem(ctx *OrderItemContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitSkipSt(ctx *SkipStContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitLimitSt(ctx *LimitStContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitReturnItems(ctx *ReturnItemsContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitReturnItem(ctx *ReturnItemContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitCreateSt(ctx *CreateStContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitDeleteSt(ctx *DeleteStContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitSetSt(ctx *SetStContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitSetProperty(ctx *SetPropertyContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitSetMergeProperties(ctx *SetMergePropertiesContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitSetAllProperties(ctx *SetAllPropertiesContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitSetLabels(ctx *SetLabelsContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitRemoveSt(ctx *RemoveStContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitRemoveProperty(ctx *RemovePropertyContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitRemoveLabels(ctx *RemoveLabelsContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitMergeSt(ctx *MergeStContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitPatternList(ctx *PatternListContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitPatternPart(ctx *PatternPartContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitPatternElement(ctx *PatternElementContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitNodePattern(ctx *NodePatternContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitNodeLabels(ctx *NodeLabelsContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitRelFullPattern(ctx *RelFullPatternContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitRelLeftPattern(ctx *RelLeftPatternContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitRelRightPattern(ctx *RelRightPatternContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitRelBothPattern(ctx *RelBothPatternContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitLeftArrow(ctx *LeftArrowContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitRightArrow(ctx *RightArrowContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitDash(ctx *DashContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitRelationDetail(ctx *RelationDetailContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitRelationshipTypes(ctx *RelationshipTypesContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitRangeLiteral(ctx *RangeLiteralContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitProperties(ctx *PropertiesContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitExpression(ctx *ExpressionContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitOrExpression(ctx *OrExpressionContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitXorExpression(ctx *XorExpressionContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitAndExpression(ctx *AndExpressionContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitNotExpression(ctx *NotExpressionContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitComparisonExpression(ctx *ComparisonExpressionContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitCompOp(ctx *CompOpContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitStringPredicateExpression(ctx *StringPredicateExpressionContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitStartsWithPredicate(ctx *StartsWithPredicateContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitEndsWithPredicate(ctx *EndsWithPredicateContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitContainsPredicate(ctx *ContainsPredicateContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitInPredicate(ctx *InPredicateContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitRegexPredicate(ctx *RegexPredicateContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitIsNotNullPredicate(ctx *IsNotNullPredicateContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitIsNullPredicate(ctx *IsNullPredicateContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitNotContainsPredicate(ctx *NotContainsPredicateContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitNotStartsWithPredicate(ctx *NotStartsWithPredicateContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitNotEndsWithPredicate(ctx *NotEndsWithPredicateContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitAddSubExpression(ctx *AddSubExpressionContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitMultDivExpression(ctx *MultDivExpressionContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitPowerExpression(ctx *PowerExpressionContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitUnaryExpression(ctx *UnaryExpressionContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitPostfixExpression(ctx *PostfixExpressionContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitSliceFromTo(ctx *SliceFromToContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitSliceTo(ctx *SliceToContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitSubscriptIndex(ctx *SubscriptIndexContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitParameterAtom(ctx *ParameterAtomContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitCaseAtom(ctx *CaseAtomContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitCountStarAtom(ctx *CountStarAtomContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitListComprehensionAtom(ctx *ListComprehensionAtomContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitPredicateFunctionAtom(ctx *PredicateFunctionAtomContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitExistsAtom(ctx *ExistsAtomContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitFunctionAtom(ctx *FunctionAtomContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitParenAtom(ctx *ParenAtomContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitDistinctAtom(ctx *DistinctAtomContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitLiteralAtom(ctx *LiteralAtomContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitVariableAtom(ctx *VariableAtomContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitFunctionInvocation(ctx *FunctionInvocationContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitFunctionName(ctx *FunctionNameContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitCaseExpression(ctx *CaseExpressionContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitCaseWhen(ctx *CaseWhenContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitCaseElse(ctx *CaseElseContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitListComprehension(ctx *ListComprehensionContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitPredicateFunction(ctx *PredicateFunctionContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitParameter(ctx *ParameterContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitTrueLiteral(ctx *TrueLiteralContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitFalseLiteral(ctx *FalseLiteralContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitNullLiteral(ctx *NullLiteralContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitIntLiteral(ctx *IntLiteralContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitFloatLit(ctx *FloatLitContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitStringLit(ctx *StringLitContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitListLit(ctx *ListLitContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitMapLit(ctx *MapLitContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitIntegerLiteral(ctx *IntegerLiteralContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitFloatLiteral(ctx *FloatLiteralContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitStringLiteral(ctx *StringLiteralContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitListLiteral(ctx *ListLiteralContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitMapLiteral(ctx *MapLiteralContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitMapPair(ctx *MapPairContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitExpressionList(ctx *ExpressionListContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitVariable(ctx *VariableContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitLabelName(ctx *LabelNameContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitRelTypeName(ctx *RelTypeNameContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitPropertyKeyName(ctx *PropertyKeyNameContext) interface{} {
	return v.VisitChildren(ctx)
}

func (v *BaseCypherParserVisitor) VisitSymbolicName(ctx *SymbolicNameContext) interface{} {
	return v.VisitChildren(ctx)
}
