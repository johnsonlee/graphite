package cypher

import "context"

// Cache trees stay private; callers retain ownership of every mutable AST
// container. Literal nodes emitted by the parser contain only scalars or nil.
type astCloner struct{ ctx context.Context }

func cloneQuery(ctx context.Context, q *Query) *Query {
	copy := &Query{UnionAll: copySlice(q.UnionAll)}
	copy.Branches = mapSlice(q.Branches, func(b SingleQuery) SingleQuery {
		return SingleQuery{Clauses: mapSlice(b.Clauses, func(c Clause) Clause {
			if err := ctx.Err(); err != nil {
				panic(err)
			}
			return astCloner{ctx: ctx}.clause(c)
		})}
	})
	if err := ctx.Err(); err != nil {
		panic(err)
	}
	return copy
}

func copySlice[T any](values []T) []T {
	if values == nil {
		return nil
	}
	result := make([]T, len(values))
	copy(result, values)
	return result
}

func mapSlice[T any](values []T, clone func(T) T) []T {
	if values == nil {
		return nil
	}
	result := make([]T, len(values))
	for i, value := range values {
		result[i] = clone(value)
	}
	return result
}

func (cloner astCloner) clause(clause Clause) Clause {
	switch c := clause.(type) {
	case MatchClause:
		c.Patterns = mapSlice(c.Patterns, cloner.pattern)
		c.Where = cloner.expr(c.Where)
		return c
	case ProjectionClause:
		c.Items = mapSlice(c.Items, func(i ReturnItem) ReturnItem { i.Expression = cloner.expr(i.Expression); return i })
		c.OrderBy = mapSlice(c.OrderBy, func(i SortItem) SortItem { i.Expression = cloner.expr(i.Expression); return i })
		c.Where, c.Skip, c.Limit = cloner.expr(c.Where), cloner.expr(c.Skip), cloner.expr(c.Limit)
		return c
	case UnwindClause:
		c.Expression = cloner.expr(c.Expression)
		return c
	case CreateClause:
		c.Patterns = mapSlice(c.Patterns, cloner.pattern)
		return c
	case DeleteClause:
		c.Expressions = mapSlice(c.Expressions, cloner.expr)
		return c
	case SetClause:
		c.Items = mapSlice(c.Items, func(i SetItem) SetItem {
			i.Expression, i.Labels = cloner.expr(i.Expression), copySlice(i.Labels)
			return i
		})
		return c
	case RemoveClause:
		c.Items = mapSlice(c.Items, func(i RemoveItem) RemoveItem { i.Labels = copySlice(i.Labels); return i })
		return c
	default:
		panic("unhandled parsed clause in AST clone")
	}
}

func (cloner astCloner) pattern(p Pattern) Pattern {
	p.Nodes = mapSlice(p.Nodes, func(n NodePattern) NodePattern {
		if err := cloner.ctx.Err(); err != nil {
			panic(err)
		}
		n.Labels, n.PropertyKeys, n.Properties = copySlice(n.Labels), copySlice(n.PropertyKeys), cloner.properties(n.Properties)
		return n
	})
	p.Relationships = mapSlice(p.Relationships, func(r RelationshipPattern) RelationshipPattern {
		if err := cloner.ctx.Err(); err != nil {
			panic(err)
		}
		r.Types, r.PropertyKeys, r.Properties = copySlice(r.Types), copySlice(r.PropertyKeys), cloner.properties(r.Properties)
		if r.MinHops != nil {
			value := *r.MinHops
			r.MinHops = &value
		}
		if r.MaxHops != nil {
			value := *r.MaxHops
			r.MaxHops = &value
		}
		return r
	})
	return p
}

func (cloner astCloner) properties(values map[string]Expr) map[string]Expr {
	if values == nil {
		return nil
	}
	result := make(map[string]Expr, len(values))
	for key, value := range values {
		result[key] = cloner.expr(value)
	}
	return result
}

func (cloner astCloner) expr(expr Expr) Expr {
	if err := cloner.ctx.Err(); err != nil {
		panic(err)
	}
	switch e := expr.(type) {
	case nil, Literal, Variable, Parameter:
		return e
	case Property:
		e.Object = cloner.expr(e.Object)
		return e
	case Binary:
		e.Left, e.Right = cloner.expr(e.Left), cloner.expr(e.Right)
		return e
	case Unary:
		e.Operand = cloner.expr(e.Operand)
		return e
	case Call:
		e.Arguments = mapSlice(e.Arguments, cloner.expr)
		return e
	case List:
		e.Elements = mapSlice(e.Elements, cloner.expr)
		return e
	case Map:
		e.Entries, e.Keys = cloner.properties(e.Entries), copySlice(e.Keys)
		return e
	case Index:
		e.Object, e.Index = cloner.expr(e.Object), cloner.expr(e.Index)
		return e
	case Slice:
		e.Object, e.From, e.To = cloner.expr(e.Object), cloner.expr(e.From), cloner.expr(e.To)
		return e
	case Case:
		e.Test, e.Else = cloner.expr(e.Test), cloner.expr(e.Else)
		e.Whens = mapSlice(e.Whens, func(w When) When { w.Condition, w.Result = cloner.expr(w.Condition), cloner.expr(w.Result); return w })
		return e
	case ListComprehension:
		e.List, e.Where, e.Projection = cloner.expr(e.List), cloner.expr(e.Where), cloner.expr(e.Projection)
		return e
	case Predicate:
		e.List, e.Where = cloner.expr(e.List), cloner.expr(e.Where)
		return e
	default:
		panic("unhandled parsed expression in AST clone")
	}
}
