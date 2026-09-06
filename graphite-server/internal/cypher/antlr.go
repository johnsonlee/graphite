package cypher

import (
	"context"
	"errors"
	"fmt"
	"github.com/antlr4-go/antlr/v4"
	g "github.com/johnsonlee/graphite/graphite-server/internal/cypher/generated"
	"strings"
)

//go:generate ./generate.sh

type errorsListener struct {
	*antlr.DefaultErrorListener
	first  *ParseError
	source []rune
}

func (l *errorsListener) SyntaxError(_ antlr.Recognizer, symbol interface{}, line, column int, message string, _ antlr.RecognitionException) {
	if l.first != nil {
		return
	}
	pos := 0
	if t, ok := symbol.(antlr.Token); ok {
		pos = t.GetStart()
	} else {
		ln := 1
		for pos < len(l.source) && ln < line {
			if l.source[pos] == '\n' {
				ln++
			}
			pos++
		}
		pos += column
	}
	if pos < 0 {
		pos = 0
	}
	if pos > len(l.source) {
		pos = len(l.source)
	}
	l.first = &ParseError{Position: len(inputString(l.source[:pos])), Message: fmt.Sprintf("Syntax error at position %d: %s", column, message)}
	panic(l.first)
}
func antlrParser(ctx context.Context, source string) (*g.CypherParser, *contextTokens, *errorsListener) {
	if err := ctx.Err(); err != nil {
		panic(err)
	}
	input := newJavaInput(source)
	listener := &errorsListener{DefaultErrorListener: antlr.NewDefaultErrorListener(), source: input.points}
	characters := &contextCharacters{CharStream: input, ctx: ctx}
	lexer := g.NewCypherLexer(characters)
	lexer.RemoveErrorListeners()
	lexer.AddErrorListener(listener)
	tokens := &contextTokens{CommonTokenStream: antlr.NewCommonTokenStream(lexer, antlr.TokenDefaultChannel), ctx: ctx}
	p := g.NewCypherParser(tokens)
	p.RemoveErrorListeners()
	p.AddErrorListener(listener)
	return p, tokens, listener
}
func recovered(err *error) {
	if v := recover(); v != nil {
		switch e := v.(type) {
		case *ParseError:
			*err = e
		case error:
			if errors.Is(e, context.Canceled) || errors.Is(e, context.DeadlineExceeded) {
				*err = e
			} else {
				panic(v)
			}
		default:
			panic(v)
		}
	}
}

// Parse uses the shared ANTLR grammar and the Kotlin adapter's statement
// preprocessing. Java is not involved in parsing or serving a query.
func Parse(source string) (*Query, error) { return ParseContext(context.Background(), source) }

// ParseContext provides cancellation during lexing, prediction, and AST
// conversion. It preserves Parse's source-compatible statement semantics.
func ParseContext(ctx context.Context, source string) (query *Query, err error) {
	defer recovered(&err)
	return parseContext(ctx, source), nil
}
func parseContext(ctx context.Context, source string) *Query {
	if err := ctx.Err(); err != nil {
		panic(err)
	}
	if strings.TrimFunc(source, kotlinWhitespace) == "" {
		return &Query{Branches: []SingleQuery{{}}}
	}
	// Kotlin deliberately splits the raw text before lexing, even within a
	// quoted literal/comment. Keep that observable behavior rather than applying
	// a syntactically aware statement split.
	if strings.ContainsRune(source, ';') {
		parts := []string{}
		for _, part := range strings.Split(source, ";") {
			if err := ctx.Err(); err != nil {
				panic(err)
			}
			part = strings.TrimFunc(part, kotlinWhitespace)
			if part != "" {
				parts = append(parts, part)
			}
		}
		if len(parts) > 1 {
			query := &Query{Branches: []SingleQuery{{}}}
			for _, part := range parts {
				appendQuery(query, parseContext(ctx, part))
			}
			return query
		}
	}
	p, _, listener := antlrParser(ctx, source)
	script := p.Script()
	if listener.first != nil {
		panic(listener.first)
	}
	a := adapter{source: listener.source, ctx: ctx}
	query := &Query{Branches: []SingleQuery{{}}}
	for _, statement := range script.AllStatement() {
		regular := statement.Query().RegularQuery()
		part := &Query{}
		for _, branch := range regular.AllSingleQuery() {
			single := SingleQuery{}
			for _, clause := range branch.AllClause() {
				single.Clauses = append(single.Clauses, a.clause(clause))
			}
			part.Branches = append(part.Branches, single)
		}
		for _, u := range regular.AllUnionSt() {
			part.UnionAll = append(part.UnionAll, u.ALL() != nil)
		}
		appendQuery(query, part)
	}
	return query
}

// Statement separators do not create execution branches in Kotlin's flattened
// clause list. Only UNION does, so adjacent statements share the same pipeline.
func appendQuery(target, part *Query) {
	if len(part.Branches) == 0 {
		return
	}
	last := len(target.Branches) - 1
	target.Branches[last].Clauses = append(target.Branches[last].Clauses, part.Branches[0].Clauses...)
	target.Branches = append(target.Branches, part.Branches[1:]...)
	target.UnionAll = append(target.UnionAll, part.UnionAll...)
}
func kotlinWhitespace(r rune) bool {
	return r >= '\t' && r <= '\r' || r >= 0x1c && r <= 0x20 || r == 0xa0 || r == 0x1680 || r >= 0x2000 && r <= 0x200a || r == 0x2028 || r == 0x2029 || r == 0x202f || r == 0x205f || r == 0x3000
}
func ParseExpression(source string) (expression Expr, err error) {
	defer recovered(&err)
	p, tokens, listener := antlrParser(context.Background(), source)
	if listener.first != nil {
		return nil, listener.first
	}
	ctx := p.Expression()
	if listener.first != nil {
		return nil, listener.first
	}
	if tokens.LA(1) != antlr.TokenEOF {
		return nil, &ParseError{Position: tokens.LT(1).GetStart(), Message: "unexpected trailing token"}
	}
	a := adapter{source: listener.source, ctx: context.Background()}
	return a.expr(ctx), nil
}

type adapter struct {
	source []rune
	ctx    context.Context
}

func (a adapter) check() {
	if err := a.ctx.Err(); err != nil {
		panic(err)
	}
}

func name(ctx antlr.ParserRuleContext) string {
	if ctx == nil {
		return ""
	}
	s := ctx.GetText()
	if len(s) >= 2 && s[0] == '`' {
		return s[1 : len(s)-1]
	}
	return s
}
func (a adapter) text(ctx antlr.ParserRuleContext) string {
	return inputString(a.source[ctx.GetStart().GetStart() : ctx.GetStop().GetStop()+1])
}
func (a adapter) unsupported(ctx antlr.ParserRuleContext, message string) {
	panic(&ParseError{Position: ctx.GetStart().GetStart(), Message: message})
}
func (a adapter) clause(ctx g.IClauseContext) Clause {
	a.check()
	if c := ctx.MatchSt(); c != nil {
		m := MatchClause{Optional: c.OPTIONAL() != nil}
		for _, p := range c.PatternList().AllPatternPart() {
			m.Patterns = append(m.Patterns, a.pattern(p))
		}
		if c.WhereSt() != nil {
			m.Where = a.expr(c.WhereSt().Expression())
		}
		return m
	}
	if c := ctx.UnwindSt(); c != nil {
		return UnwindClause{Expression: a.expr(c.Expression()), Variable: name(c.Variable())}
	}
	if c := ctx.WithSt(); c != nil {
		r := a.projection(c.ReturnItems(), c.OrderBySt(), c.SkipSt(), c.LimitSt())
		r.With = true
		r.Distinct = c.DISTINCT() != nil
		if c.WhereSt() != nil {
			r.Where = a.expr(c.WhereSt().Expression())
		}
		return r
	}
	if c := ctx.ReturnSt(); c != nil {
		r := a.projection(c.ReturnItems(), c.OrderBySt(), c.SkipSt(), c.LimitSt())
		r.Distinct = c.DISTINCT() != nil
		return r
	}
	if c := ctx.CreateSt(); c != nil {
		r := CreateClause{}
		for _, p := range c.PatternList().AllPatternPart() {
			r.Patterns = append(r.Patterns, a.pattern(p))
		}
		return r
	}
	if c := ctx.MergeSt(); c != nil {
		return CreateClause{Patterns: []Pattern{a.pattern(c.PatternPart())}}
	}
	if c := ctx.DeleteSt(); c != nil {
		return DeleteClause{Expressions: a.list(c.ExpressionList()), Detach: c.DETACH() != nil}
	}
	if c := ctx.SetSt(); c != nil {
		r := SetClause{}
		for _, item := range c.AllSetItem() {
			var result SetItem
			switch s := item.(type) {
			case *g.SetPropertyContext:
				result = SetItem{Kind: SetProperty, Variable: name(s.Variable()), Property: name(s.PropertyKeyName()), Expression: a.expr(s.Expression())}
			case *g.SetAllPropertiesContext:
				result = SetItem{Kind: SetAllProperties, Variable: name(s.Variable()), Expression: a.expr(s.Expression())}
			case *g.SetMergePropertiesContext:
				result = SetItem{Kind: SetMergeProperties, Variable: name(s.Variable()), Expression: a.expr(s.Expression())}
			case *g.SetLabelsContext:
				result = SetItem{Kind: SetLabels, Variable: name(s.Variable())}
				for _, l := range s.NodeLabels().AllLabelName() {
					result.Labels = append(result.Labels, name(l))
				}
			}
			r.Items = append(r.Items, result)
		}
		return r
	}
	if c := ctx.RemoveSt(); c != nil {
		r := RemoveClause{}
		for _, item := range c.AllRemoveItem() {
			var result RemoveItem
			switch s := item.(type) {
			case *g.RemovePropertyContext:
				result = RemoveItem{Kind: RemoveProperty, Variable: name(s.Variable()), Property: name(s.PropertyKeyName())}
			case *g.RemoveLabelsContext:
				result = RemoveItem{Kind: RemoveLabels, Variable: name(s.Variable())}
				for _, l := range s.NodeLabels().AllLabelName() {
					result.Labels = append(result.Labels, name(l))
				}
			}
			r.Items = append(r.Items, result)
		}
		return r
	}
	a.unsupported(ctx, "unsupported clause")
	return nil
}
func (a adapter) projection(items g.IReturnItemsContext, order g.IOrderByStContext, skip g.ISkipStContext, limit g.ILimitStContext) ProjectionClause {
	r := ProjectionClause{All: items.MULT() != nil}
	for _, item := range items.AllReturnItem() {
		r.Items = append(r.Items, ReturnItem{Expression: a.expr(item.Expression()), Alias: name(item.Variable()), Text: a.text(item.Expression())})
	}
	if order != nil {
		for _, s := range order.AllOrderItem() {
			r.OrderBy = append(r.OrderBy, SortItem{Expression: a.expr(s.Expression()), Descending: s.DESC() != nil || s.DESCENDING() != nil})
		}
	}
	if skip != nil {
		r.Skip = a.expr(skip.Expression())
	}
	if limit != nil {
		r.Limit = a.expr(limit.Expression())
	}
	return r
}
func (a adapter) pattern(ctx g.IPatternPartContext) Pattern {
	a.check()
	p := Pattern{PathVariable: name(ctx.Variable())}
	el := ctx.PatternElement()
	for _, n := range el.AllNodePattern() {
		node := NodePattern{Variable: name(n.Variable())}
		if n.NodeLabels() != nil {
			for _, l := range n.NodeLabels().AllLabelName() {
				node.Labels = append(node.Labels, name(l))
			}
		}
		if n.Properties() != nil {
			node.Properties = a.mapExpr(n.Properties().MapLiteral()).Entries
		}
		p.Nodes = append(p.Nodes, node)
	}
	for _, r := range el.AllRelationshipPattern() {
		rel := RelationshipPattern{Direction: Both}
		var detail g.IRelationDetailContext
		switch c := r.(type) {
		case *g.RelFullPatternContext:
			detail = c.RelationDetail()
		case *g.RelLeftPatternContext:
			rel.Direction = Incoming
			detail = c.RelationDetail()
		case *g.RelRightPatternContext:
			rel.Direction = Outgoing
			detail = c.RelationDetail()
		case *g.RelBothPatternContext:
			detail = c.RelationDetail()
		}
		if detail != nil {
			rel.Variable = name(detail.Variable())
			if t := detail.RelationshipTypes(); t != nil {
				for _, n := range t.AllRelTypeName() {
					rel.Types = append(rel.Types, name(n))
				}
			}
			if hops := detail.RangeLiteral(); hops != nil {
				rel.VariableLength = true
				ints := hops.AllIntegerLiteral()
				hop := func(ctx g.IIntegerLiteralContext) *int {
					t := token{text: ctx.GetText(), pos: ctx.GetStart().GetStart()}
					v, err := numberValue(t)
					if err != nil {
						panic(err)
					}
					var n int64
					switch v := v.(type) {
					case int32:
						n = int64(v)
					case int64:
						n = v
					}
					if n > 2147483647 {
						a.unsupported(ctx, "hop count exceeds 32-bit integer")
					}
					i := int(n)
					return &i
				}
				if hops.RANGE() == nil && len(ints) > 0 {
					rel.MinHops = hop(ints[0])
					rel.MaxHops = rel.MinHops
				} else if len(ints) == 2 {
					rel.MinHops = hop(ints[0])
					rel.MaxHops = hop(ints[1])
				} else if len(ints) == 1 {
					if ints[0].GetStart().GetTokenIndex() < hops.RANGE().GetSymbol().GetTokenIndex() {
						rel.MinHops = hop(ints[0])
					} else {
						rel.MaxHops = hop(ints[0])
					}
				}
			}
			if detail.Properties() != nil {
				rel.Properties = a.mapExpr(detail.Properties().MapLiteral()).Entries
			}
		}
		p.Relationships = append(p.Relationships, rel)
	}
	return p
}
func (a adapter) mapExpr(ctx g.IMapLiteralContext) Map {
	a.check()
	m := Map{Entries: map[string]Expr{}}
	for _, p := range ctx.AllMapPair() {
		key := name(p.PropertyKeyName())
		if _, exists := m.Entries[key]; !exists {
			m.Keys = append(m.Keys, key)
		}
		m.Entries[key] = a.expr(p.Expression())
	}
	return m
}
func (a adapter) list(ctx g.IExpressionListContext) []Expr {
	a.check()
	if ctx == nil {
		return nil
	}
	var expressions []Expr
	for _, e := range ctx.AllExpression() {
		expressions = append(expressions, a.expr(e))
	}
	return expressions
}
func (a adapter) fold(ctx antlr.ParserRuleContext) Expr {
	var left Expr
	op := ""
	for _, child := range ctx.GetChildren() {
		switch c := child.(type) {
		case antlr.TerminalNode:
			op = strings.ToUpper(c.GetText())
		case g.ICompOpContext:
			op = c.GetText()
		case antlr.ParserRuleContext:
			right := a.expr(c)
			if left == nil {
				left = right
			} else {
				left = Binary{Op: op, Left: left, Right: right}
			}
		}
	}
	return left
}
func (a adapter) expr(ctx antlr.ParserRuleContext) Expr {
	a.check()
	switch c := ctx.(type) {
	case g.IExpressionContext:
		return a.expr(c.OrExpression())
	case g.IOrExpressionContext, g.IXorExpressionContext, g.IAndExpressionContext, g.IComparisonExpressionContext, g.IAddSubExpressionContext, g.IMultDivExpressionContext:
		return a.fold(ctx)
	case g.INotExpressionContext:
		if c.NOT() != nil {
			return Unary{Op: "NOT", Operand: a.expr(c.NotExpression())}
		}
		return a.expr(c.ComparisonExpression())
	case g.IPowerExpressionContext:
		left := a.expr(c.UnaryExpression())
		if c.PowerExpression() != nil {
			return Binary{Op: "^", Left: left, Right: a.expr(c.PowerExpression())}
		}
		return left
	case g.IUnaryExpressionContext:
		if c.SUB() != nil {
			return Unary{Op: "-", Operand: a.expr(c.UnaryExpression())}
		}
		if c.PLUS() != nil {
			return Unary{Op: "+", Operand: a.expr(c.UnaryExpression())}
		}
		return a.expr(c.PostfixExpression())
	case g.IStringPredicateExpressionContext:
		left := a.expr(c.AddSubExpression())
		for _, suffix := range c.AllStringPredicateSuffix() {
			var op string
			var right antlr.ParserRuleContext
			switch s := suffix.(type) {
			case *g.StartsWithPredicateContext:
				op = "STARTS WITH"
				right = s.AddSubExpression()
			case *g.EndsWithPredicateContext:
				op = "ENDS WITH"
				right = s.AddSubExpression()
			case *g.ContainsPredicateContext:
				op = "CONTAINS"
				right = s.AddSubExpression()
			case *g.InPredicateContext:
				op = "IN"
				right = s.AddSubExpression()
			case *g.RegexPredicateContext:
				op = "=~"
				right = s.AddSubExpression()
			case *g.NotContainsPredicateContext:
				op = "NOT CONTAINS"
				right = s.AddSubExpression()
			case *g.NotStartsWithPredicateContext:
				op = "NOT STARTS WITH"
				right = s.AddSubExpression()
			case *g.NotEndsWithPredicateContext:
				op = "NOT ENDS WITH"
				right = s.AddSubExpression()
			case *g.IsNullPredicateContext:
				op = "IS NULL"
			case *g.IsNotNullPredicateContext:
				op = "IS NOT NULL"
			}
			if right == nil {
				left = Unary{Op: op, Operand: left}
			} else {
				left = Binary{Op: op, Left: left, Right: a.expr(right)}
			}
		}
		return left
	case g.IPostfixExpressionContext:
		left := a.expr(c.AtomExpression())
		for _, child := range c.GetChildren() {
			switch s := child.(type) {
			case g.IPropertyKeyNameContext:
				left = Property{Object: left, Key: name(s)}
			case *g.SubscriptIndexContext:
				left = Index{Object: left, Index: a.expr(s.Expression())}
			case *g.SliceFromToContext:
				slice := Slice{Object: left, From: a.expr(s.Expression(0))}
				if len(s.AllExpression()) > 1 {
					slice.To = a.expr(s.Expression(1))
				}
				left = slice
			case *g.SliceToContext:
				left = Slice{Object: left, To: a.expr(s.Expression())}
			}
		}
		return left
	case *g.ParameterAtomContext:
		return Parameter{Name: name(c.Parameter().SymbolicName())}
	case *g.CaseAtomContext:
		return a.expr(c.CaseExpression())
	case *g.CountStarAtomContext:
		return Call{Name: "count", Star: true}
	case *g.ListComprehensionAtomContext:
		return a.expr(c.ListComprehension())
	case *g.PredicateFunctionAtomContext:
		return a.expr(c.PredicateFunction())
	case *g.ExistsAtomContext:
		return Call{Name: "exists", Arguments: []Expr{a.expr(c.Expression())}}
	case *g.FunctionAtomContext:
		return a.expr(c.FunctionInvocation())
	case *g.ParenAtomContext:
		return a.expr(c.Expression())
	case *g.DistinctAtomContext:
		return Unary{Op: "DISTINCT", Operand: a.expr(c.UnaryExpression())}
	case *g.LiteralAtomContext:
		return a.expr(c.Literal())
	case *g.VariableAtomContext:
		return Variable{Name: name(c.Variable())}
	case g.IFunctionInvocationContext:
		names := []string{}
		for _, n := range c.FunctionName().AllSymbolicName() {
			names = append(names, name(n))
		}
		return Call{Name: strings.Join(names, "."), Arguments: a.list(c.ExpressionList()), Distinct: c.DISTINCT() != nil}
	case g.ICaseExpressionContext:
		result := Case{}
		if c.Expression() != nil {
			result.Test = a.expr(c.Expression())
		}
		for _, w := range c.AllCaseWhen() {
			result.Whens = append(result.Whens, When{Condition: a.expr(w.Expression(0)), Result: a.expr(w.Expression(1))})
		}
		if c.CaseElse() != nil {
			result.Else = a.expr(c.CaseElse().Expression())
		}
		return result
	case g.IListComprehensionContext:
		r := ListComprehension{Variable: name(c.Variable()), List: a.expr(c.Expression(0))}
		i := 1
		if c.WHERE() != nil {
			r.Where = a.expr(c.Expression(i))
			i++
		}
		if c.STICK() != nil {
			r.Projection = a.expr(c.Expression(i))
		}
		return r
	case g.IPredicateFunctionContext:
		r := Predicate{Name: strings.ToLower(c.GetStart().GetText()), Variable: name(c.Variable()), List: a.expr(c.Expression(0))}
		if c.WHERE() != nil {
			r.Where = a.expr(c.Expression(1))
		}
		return r
	case *g.TrueLiteralContext:
		return Literal{Value: true}
	case *g.FalseLiteralContext:
		return Literal{Value: false}
	case *g.NullLiteralContext:
		return Literal{}
	case *g.IntLiteralContext, *g.FloatLitContext:
		v, err := numberValue(token{text: ctx.GetText(), pos: ctx.GetStart().GetStart()})
		if err != nil {
			panic(err)
		}
		return Literal{Value: v}
	case *g.StringLitContext:
		v, err := stringValue(token{text: ctx.GetText(), pos: ctx.GetStart().GetStart()})
		if err != nil {
			panic(err)
		}
		return Literal{Value: v}
	case *g.ListLitContext:
		return List{Elements: a.list(c.ListLiteral().ExpressionList())}
	case *g.MapLitContext:
		return a.mapExpr(c.MapLiteral())
	}
	a.unsupported(ctx, fmt.Sprintf("unsupported expression context %T", ctx))
	return nil
}
