// Code generated from graphite-cypher/src/main/antlr/CypherParser.g4 by ANTLR 4.13.2. DO NOT EDIT.

package generated // CypherParser
import "github.com/antlr4-go/antlr/v4"

// A complete Visitor for a parse tree produced by CypherParser.
type CypherParserVisitor interface {
	antlr.ParseTreeVisitor

	// Visit a parse tree produced by CypherParser#script.
	VisitScript(ctx *ScriptContext) interface{}

	// Visit a parse tree produced by CypherParser#statement.
	VisitStatement(ctx *StatementContext) interface{}

	// Visit a parse tree produced by CypherParser#query.
	VisitQuery(ctx *QueryContext) interface{}

	// Visit a parse tree produced by CypherParser#regularQuery.
	VisitRegularQuery(ctx *RegularQueryContext) interface{}

	// Visit a parse tree produced by CypherParser#unionSt.
	VisitUnionSt(ctx *UnionStContext) interface{}

	// Visit a parse tree produced by CypherParser#singleQuery.
	VisitSingleQuery(ctx *SingleQueryContext) interface{}

	// Visit a parse tree produced by CypherParser#clause.
	VisitClause(ctx *ClauseContext) interface{}

	// Visit a parse tree produced by CypherParser#matchSt.
	VisitMatchSt(ctx *MatchStContext) interface{}

	// Visit a parse tree produced by CypherParser#unwindSt.
	VisitUnwindSt(ctx *UnwindStContext) interface{}

	// Visit a parse tree produced by CypherParser#withSt.
	VisitWithSt(ctx *WithStContext) interface{}

	// Visit a parse tree produced by CypherParser#returnSt.
	VisitReturnSt(ctx *ReturnStContext) interface{}

	// Visit a parse tree produced by CypherParser#whereSt.
	VisitWhereSt(ctx *WhereStContext) interface{}

	// Visit a parse tree produced by CypherParser#orderBySt.
	VisitOrderBySt(ctx *OrderByStContext) interface{}

	// Visit a parse tree produced by CypherParser#orderItem.
	VisitOrderItem(ctx *OrderItemContext) interface{}

	// Visit a parse tree produced by CypherParser#skipSt.
	VisitSkipSt(ctx *SkipStContext) interface{}

	// Visit a parse tree produced by CypherParser#limitSt.
	VisitLimitSt(ctx *LimitStContext) interface{}

	// Visit a parse tree produced by CypherParser#returnItems.
	VisitReturnItems(ctx *ReturnItemsContext) interface{}

	// Visit a parse tree produced by CypherParser#returnItem.
	VisitReturnItem(ctx *ReturnItemContext) interface{}

	// Visit a parse tree produced by CypherParser#createSt.
	VisitCreateSt(ctx *CreateStContext) interface{}

	// Visit a parse tree produced by CypherParser#deleteSt.
	VisitDeleteSt(ctx *DeleteStContext) interface{}

	// Visit a parse tree produced by CypherParser#setSt.
	VisitSetSt(ctx *SetStContext) interface{}

	// Visit a parse tree produced by CypherParser#SetProperty.
	VisitSetProperty(ctx *SetPropertyContext) interface{}

	// Visit a parse tree produced by CypherParser#SetMergeProperties.
	VisitSetMergeProperties(ctx *SetMergePropertiesContext) interface{}

	// Visit a parse tree produced by CypherParser#SetAllProperties.
	VisitSetAllProperties(ctx *SetAllPropertiesContext) interface{}

	// Visit a parse tree produced by CypherParser#SetLabels.
	VisitSetLabels(ctx *SetLabelsContext) interface{}

	// Visit a parse tree produced by CypherParser#removeSt.
	VisitRemoveSt(ctx *RemoveStContext) interface{}

	// Visit a parse tree produced by CypherParser#RemoveProperty.
	VisitRemoveProperty(ctx *RemovePropertyContext) interface{}

	// Visit a parse tree produced by CypherParser#RemoveLabels.
	VisitRemoveLabels(ctx *RemoveLabelsContext) interface{}

	// Visit a parse tree produced by CypherParser#mergeSt.
	VisitMergeSt(ctx *MergeStContext) interface{}

	// Visit a parse tree produced by CypherParser#patternList.
	VisitPatternList(ctx *PatternListContext) interface{}

	// Visit a parse tree produced by CypherParser#patternPart.
	VisitPatternPart(ctx *PatternPartContext) interface{}

	// Visit a parse tree produced by CypherParser#patternElement.
	VisitPatternElement(ctx *PatternElementContext) interface{}

	// Visit a parse tree produced by CypherParser#nodePattern.
	VisitNodePattern(ctx *NodePatternContext) interface{}

	// Visit a parse tree produced by CypherParser#nodeLabels.
	VisitNodeLabels(ctx *NodeLabelsContext) interface{}

	// Visit a parse tree produced by CypherParser#RelFullPattern.
	VisitRelFullPattern(ctx *RelFullPatternContext) interface{}

	// Visit a parse tree produced by CypherParser#RelLeftPattern.
	VisitRelLeftPattern(ctx *RelLeftPatternContext) interface{}

	// Visit a parse tree produced by CypherParser#RelRightPattern.
	VisitRelRightPattern(ctx *RelRightPatternContext) interface{}

	// Visit a parse tree produced by CypherParser#RelBothPattern.
	VisitRelBothPattern(ctx *RelBothPatternContext) interface{}

	// Visit a parse tree produced by CypherParser#leftArrow.
	VisitLeftArrow(ctx *LeftArrowContext) interface{}

	// Visit a parse tree produced by CypherParser#rightArrow.
	VisitRightArrow(ctx *RightArrowContext) interface{}

	// Visit a parse tree produced by CypherParser#dash.
	VisitDash(ctx *DashContext) interface{}

	// Visit a parse tree produced by CypherParser#relationDetail.
	VisitRelationDetail(ctx *RelationDetailContext) interface{}

	// Visit a parse tree produced by CypherParser#relationshipTypes.
	VisitRelationshipTypes(ctx *RelationshipTypesContext) interface{}

	// Visit a parse tree produced by CypherParser#rangeLiteral.
	VisitRangeLiteral(ctx *RangeLiteralContext) interface{}

	// Visit a parse tree produced by CypherParser#properties.
	VisitProperties(ctx *PropertiesContext) interface{}

	// Visit a parse tree produced by CypherParser#expression.
	VisitExpression(ctx *ExpressionContext) interface{}

	// Visit a parse tree produced by CypherParser#orExpression.
	VisitOrExpression(ctx *OrExpressionContext) interface{}

	// Visit a parse tree produced by CypherParser#xorExpression.
	VisitXorExpression(ctx *XorExpressionContext) interface{}

	// Visit a parse tree produced by CypherParser#andExpression.
	VisitAndExpression(ctx *AndExpressionContext) interface{}

	// Visit a parse tree produced by CypherParser#notExpression.
	VisitNotExpression(ctx *NotExpressionContext) interface{}

	// Visit a parse tree produced by CypherParser#comparisonExpression.
	VisitComparisonExpression(ctx *ComparisonExpressionContext) interface{}

	// Visit a parse tree produced by CypherParser#compOp.
	VisitCompOp(ctx *CompOpContext) interface{}

	// Visit a parse tree produced by CypherParser#stringPredicateExpression.
	VisitStringPredicateExpression(ctx *StringPredicateExpressionContext) interface{}

	// Visit a parse tree produced by CypherParser#StartsWithPredicate.
	VisitStartsWithPredicate(ctx *StartsWithPredicateContext) interface{}

	// Visit a parse tree produced by CypherParser#EndsWithPredicate.
	VisitEndsWithPredicate(ctx *EndsWithPredicateContext) interface{}

	// Visit a parse tree produced by CypherParser#ContainsPredicate.
	VisitContainsPredicate(ctx *ContainsPredicateContext) interface{}

	// Visit a parse tree produced by CypherParser#InPredicate.
	VisitInPredicate(ctx *InPredicateContext) interface{}

	// Visit a parse tree produced by CypherParser#RegexPredicate.
	VisitRegexPredicate(ctx *RegexPredicateContext) interface{}

	// Visit a parse tree produced by CypherParser#IsNotNullPredicate.
	VisitIsNotNullPredicate(ctx *IsNotNullPredicateContext) interface{}

	// Visit a parse tree produced by CypherParser#IsNullPredicate.
	VisitIsNullPredicate(ctx *IsNullPredicateContext) interface{}

	// Visit a parse tree produced by CypherParser#NotContainsPredicate.
	VisitNotContainsPredicate(ctx *NotContainsPredicateContext) interface{}

	// Visit a parse tree produced by CypherParser#NotStartsWithPredicate.
	VisitNotStartsWithPredicate(ctx *NotStartsWithPredicateContext) interface{}

	// Visit a parse tree produced by CypherParser#NotEndsWithPredicate.
	VisitNotEndsWithPredicate(ctx *NotEndsWithPredicateContext) interface{}

	// Visit a parse tree produced by CypherParser#addSubExpression.
	VisitAddSubExpression(ctx *AddSubExpressionContext) interface{}

	// Visit a parse tree produced by CypherParser#multDivExpression.
	VisitMultDivExpression(ctx *MultDivExpressionContext) interface{}

	// Visit a parse tree produced by CypherParser#powerExpression.
	VisitPowerExpression(ctx *PowerExpressionContext) interface{}

	// Visit a parse tree produced by CypherParser#unaryExpression.
	VisitUnaryExpression(ctx *UnaryExpressionContext) interface{}

	// Visit a parse tree produced by CypherParser#postfixExpression.
	VisitPostfixExpression(ctx *PostfixExpressionContext) interface{}

	// Visit a parse tree produced by CypherParser#SliceFromTo.
	VisitSliceFromTo(ctx *SliceFromToContext) interface{}

	// Visit a parse tree produced by CypherParser#SliceTo.
	VisitSliceTo(ctx *SliceToContext) interface{}

	// Visit a parse tree produced by CypherParser#SubscriptIndex.
	VisitSubscriptIndex(ctx *SubscriptIndexContext) interface{}

	// Visit a parse tree produced by CypherParser#ParameterAtom.
	VisitParameterAtom(ctx *ParameterAtomContext) interface{}

	// Visit a parse tree produced by CypherParser#CaseAtom.
	VisitCaseAtom(ctx *CaseAtomContext) interface{}

	// Visit a parse tree produced by CypherParser#CountStarAtom.
	VisitCountStarAtom(ctx *CountStarAtomContext) interface{}

	// Visit a parse tree produced by CypherParser#ListComprehensionAtom.
	VisitListComprehensionAtom(ctx *ListComprehensionAtomContext) interface{}

	// Visit a parse tree produced by CypherParser#PredicateFunctionAtom.
	VisitPredicateFunctionAtom(ctx *PredicateFunctionAtomContext) interface{}

	// Visit a parse tree produced by CypherParser#ExistsAtom.
	VisitExistsAtom(ctx *ExistsAtomContext) interface{}

	// Visit a parse tree produced by CypherParser#FunctionAtom.
	VisitFunctionAtom(ctx *FunctionAtomContext) interface{}

	// Visit a parse tree produced by CypherParser#ParenAtom.
	VisitParenAtom(ctx *ParenAtomContext) interface{}

	// Visit a parse tree produced by CypherParser#DistinctAtom.
	VisitDistinctAtom(ctx *DistinctAtomContext) interface{}

	// Visit a parse tree produced by CypherParser#LiteralAtom.
	VisitLiteralAtom(ctx *LiteralAtomContext) interface{}

	// Visit a parse tree produced by CypherParser#VariableAtom.
	VisitVariableAtom(ctx *VariableAtomContext) interface{}

	// Visit a parse tree produced by CypherParser#functionInvocation.
	VisitFunctionInvocation(ctx *FunctionInvocationContext) interface{}

	// Visit a parse tree produced by CypherParser#functionName.
	VisitFunctionName(ctx *FunctionNameContext) interface{}

	// Visit a parse tree produced by CypherParser#caseExpression.
	VisitCaseExpression(ctx *CaseExpressionContext) interface{}

	// Visit a parse tree produced by CypherParser#caseWhen.
	VisitCaseWhen(ctx *CaseWhenContext) interface{}

	// Visit a parse tree produced by CypherParser#caseElse.
	VisitCaseElse(ctx *CaseElseContext) interface{}

	// Visit a parse tree produced by CypherParser#listComprehension.
	VisitListComprehension(ctx *ListComprehensionContext) interface{}

	// Visit a parse tree produced by CypherParser#predicateFunction.
	VisitPredicateFunction(ctx *PredicateFunctionContext) interface{}

	// Visit a parse tree produced by CypherParser#parameter.
	VisitParameter(ctx *ParameterContext) interface{}

	// Visit a parse tree produced by CypherParser#TrueLiteral.
	VisitTrueLiteral(ctx *TrueLiteralContext) interface{}

	// Visit a parse tree produced by CypherParser#FalseLiteral.
	VisitFalseLiteral(ctx *FalseLiteralContext) interface{}

	// Visit a parse tree produced by CypherParser#NullLiteral.
	VisitNullLiteral(ctx *NullLiteralContext) interface{}

	// Visit a parse tree produced by CypherParser#IntLiteral.
	VisitIntLiteral(ctx *IntLiteralContext) interface{}

	// Visit a parse tree produced by CypherParser#FloatLit.
	VisitFloatLit(ctx *FloatLitContext) interface{}

	// Visit a parse tree produced by CypherParser#StringLit.
	VisitStringLit(ctx *StringLitContext) interface{}

	// Visit a parse tree produced by CypherParser#ListLit.
	VisitListLit(ctx *ListLitContext) interface{}

	// Visit a parse tree produced by CypherParser#MapLit.
	VisitMapLit(ctx *MapLitContext) interface{}

	// Visit a parse tree produced by CypherParser#integerLiteral.
	VisitIntegerLiteral(ctx *IntegerLiteralContext) interface{}

	// Visit a parse tree produced by CypherParser#floatLiteral.
	VisitFloatLiteral(ctx *FloatLiteralContext) interface{}

	// Visit a parse tree produced by CypherParser#stringLiteral.
	VisitStringLiteral(ctx *StringLiteralContext) interface{}

	// Visit a parse tree produced by CypherParser#listLiteral.
	VisitListLiteral(ctx *ListLiteralContext) interface{}

	// Visit a parse tree produced by CypherParser#mapLiteral.
	VisitMapLiteral(ctx *MapLiteralContext) interface{}

	// Visit a parse tree produced by CypherParser#mapPair.
	VisitMapPair(ctx *MapPairContext) interface{}

	// Visit a parse tree produced by CypherParser#expressionList.
	VisitExpressionList(ctx *ExpressionListContext) interface{}

	// Visit a parse tree produced by CypherParser#variable.
	VisitVariable(ctx *VariableContext) interface{}

	// Visit a parse tree produced by CypherParser#labelName.
	VisitLabelName(ctx *LabelNameContext) interface{}

	// Visit a parse tree produced by CypherParser#relTypeName.
	VisitRelTypeName(ctx *RelTypeNameContext) interface{}

	// Visit a parse tree produced by CypherParser#propertyKeyName.
	VisitPropertyKeyName(ctx *PropertyKeyNameContext) interface{}

	// Visit a parse tree produced by CypherParser#symbolicName.
	VisitSymbolicName(ctx *SymbolicNameContext) interface{}
}
