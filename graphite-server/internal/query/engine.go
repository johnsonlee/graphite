// Package query executes the native Go read-only Cypher pipeline.
package query

import (
	"context"
	"errors"
	"fmt"
	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"sort"
	"strings"
	"unicode/utf8"
)

type Result struct {
	Columns    []string         `json:"columns"`
	Rows       []map[string]any `json:"rows"`
	rawColumns []string
	rawRowKeys [][]string
}

// Execute evaluates a single-store query. A negative limit means unlimited;
// limit zero returns no rows. It does not truncate before ORDER/aggregation.
func Execute(ctx context.Context, graph *store.Store, source string, parameters map[string]any, limit int) (result Result, err error) {
	return executeSources(ctx, graph, nil, false, source, parameters, limit)
}

func ExecuteCross(ctx context.Context, graphs []Graph, source string, parameters map[string]any, limit int) (Result, error) {
	seen := map[string]bool{}
	for _, g := range graphs {
		if g.ID == "" || g.Store == nil || seen[g.ID] {
			return Result{}, &Error{Message: "Cross-graph sources require unique nonempty ids and loaded stores"}
		}
		seen[g.ID] = true
	}
	return executeSources(ctx, nil, graphs, true, source, parameters, limit)
}

func executeSources(ctx context.Context, graph *store.Store, graphs []Graph, cross bool, source string, parameters map[string]any, limit int) (result Result, err error) {
	defer func() {
		if v := recover(); v != nil {
			switch x := v.(type) {
			case *Error:
				result = Result{}
				err = x
			case error:
				if errors.Is(x, context.Canceled) || errors.Is(x, context.DeadlineExceeded) {
					result = Result{}
					err = x
				} else {
					panic(v)
				}
			default:
				panic(v)
			}
		}
	}()
	ast, err := cypher.ParseContext(ctx, source)
	if err != nil {
		var literalError *cypher.ParseError
		if errors.As(err, &literalError) {
			if strings.HasPrefix(literalError.Message, "For input string:") {
				return Result{}, &Error{Class: "NumberFormatException", Message: literalError.Message}
			}
			if strings.HasPrefix(literalError.Message, "Syntax error at position ") {
				return Result{}, &Error{Class: "CypherParseException", Message: javaWireString(literalError.Message)}
			}
		}

		return Result{}, err
	}
	validate(ast)
	e := evaluator{ctx: ctx, parameters: parameters, graphs: graphs, cross: cross, regexes: &regexLRU{entries: map[string]compiledRegex{}}}
	javaSource := !utf8.ValidString(source) || (strings.Contains(source, `\u`) && needsJavaOutputOrder(ast))
	if needsRowOrder(ast) || javaSource {
		e.rowOrders = map[string]rowOrder{}
	}
	result = Result{Columns: []string{}, Rows: []map[string]any{}}
	for i, branch := range ast.Branches {
		e.check()
		r := e.branch(graph, branch)
		if i == 0 {
			result.Columns = r.Columns
		}
		result.Rows = append(result.Rows, r.Rows...)
		if i > 0 && !ast.UnionAll[i-1] {
			result.Rows = distinctRows(result.Rows, result.Columns)
		}
	}
	if limit >= 0 && len(result.Rows) > limit {
		result.Rows = result.Rows[:limit]
	}
	if hasNonUTF8Keys(result.Columns) {
		result.rawColumns = append([]string(nil), result.Columns...)
	}
	for index, row := range result.Rows {
		ids := provenance(row)
		delete(row, provenanceKey)
		var keys []string
		if javaSource {
			keys = e.rowKeys(row)
		}
		if wireKeyCollision(keys) {
			if result.rawRowKeys == nil {
				result.rawRowKeys = make([][]string, len(result.Rows))
			}
			result.rawRowKeys[index] = keys
			for k, v := range row {
				row[k] = e.materialize(v)
			}
		} else {
			// Write renamed keys into a fresh map rather than mutating a map while
			// iterating over it (new entries may otherwise be visited again).
			var renamed map[string]any
			if javaSource {
				for k := range row {
					if !utf8.ValidString(k) {
						renamed = make(map[string]any, len(row))
						break
					}
				}
			}
			for k, v := range row {
				if renamed != nil {
					renamed[javaWireString(k)] = e.materialize(v)
				} else {
					row[k] = e.materialize(v)
				}
			}
			if renamed != nil {
				row = renamed
				result.Rows[index] = row
			}
		}
		if cross {
			if _, present := row["$metadata"]; len(ids) > 0 || !present {
				row["$metadata"] = map[string]any{"graphIds": ids}
			}
		}
	}
	for i, column := range result.Columns {
		result.Columns[i] = javaWireString(column)
	}
	return result, nil
}
func (e evaluator) branch(graph *store.Store, branch cypher.SingleQuery) Result {
	e.check()
	if empty, ok := filteredLiteralEmpty(branch); ok {
		return empty
	}
	earlyLimit := e.computeEarlyLimit(branch)
	rows := []map[string]any{{}}
	columns := []string{}
	for ordinal, clause := range branch.Clauses {
		e.check()
		switch c := clause.(type) {
		case cypher.MatchClause:
			current := e
			current.indexFirst = ordinal == 0
			if earlyLimit > 0 && !c.Optional {
				rows = current.matchWithLimit(graph, rows, c, earlyLimit)
			} else {
				rows = current.match(graph, rows, c)
			}
		case cypher.UnwindClause:
			next := []map[string]any{}
			for _, r := range rows {
				value := e.eval(c.Expression, r)
				if value == nil {
					continue
				}
				list, ok := value.([]any)
				if !ok {
					continue
				}
				for _, v := range list {
					e.check()
					n := e.cloneRow(r)
					e.bind(n, c.Variable, v)
					next = append(next, n)
				}
			}
			rows = next
		case cypher.ProjectionClause:
			rows, columns = e.project(rows, c)
		}
	}
	return Result{Columns: columns, Rows: rows}
}
func (e evaluator) matches(value any, n cypher.NodePattern, row map[string]any) bool {
	var property func(string) any
	switch v := value.(type) {
	case *candidateSlot:
		for _, label := range n.Labels {
			if !v.matchesLabel(label) {
				return false
			}
		}
		property = v.property
	case qualifiedNode:
		for _, label := range n.Labels {
			if !matchesLabel(v.Node, label) {
				return false
			}
		}
		property = func(k string) any { return qualifiedProperty(v, k) }
	case qualifiedMethod:
		for _, label := range n.Labels {
			if !strings.EqualFold(label, "Method") {
				return false
			}
		}
		property = func(k string) any { return qualifiedProperty(v, k) }
	case store.Node:
		for _, label := range n.Labels {
			if !matchesLabel(v, label) {
				return false
			}
		}
		property = func(k string) any { return NodeProperty(v, k) }
	case store.MethodDescriptor:
		for _, label := range n.Labels {
			if !strings.EqualFold(label, "Method") {
				return false
			}
		}
		property = func(k string) any { return methodProperty(v, k) }
	default:
		return false
	}
	for _, k := range n.PropertyKeys {
		if equal(property(k), e.eval(n.Properties[k], row)) != true {
			return false
		}
	}
	return true
}
func methodProperty(m store.MethodDescriptor, key string) any {
	switch key {
	case "signature":
		return m.Signature()
	case "class":
		return m.DeclaringClass
	case "name":
		return m.Name
	case "return_type":
		return m.ReturnType
	case "parameter_types":
		r := make([]any, len(m.ParameterTypes))
		for i, v := range m.ParameterTypes {
			r[i] = v
		}
		return r
	}
	return nil
}
func (e evaluator) project(rows []map[string]any, c cypher.ProjectionClause) ([]map[string]any, []string) {
	columns := []string{}
	if c.All {
		if len(rows) > 0 {
			for _, key := range e.rowKeys(rows[0]) {
				if key != provenanceKey {
					columns = append(columns, key)
				}
			}
		}
	} else {
		for _, item := range c.Items {
			name := item.Alias
			if name == "" {
				name = columnName(item.Expression)
			}
			columns = append(columns, name)
		}
	}
	hasAggregate := false
	for _, item := range c.Items {
		hasAggregate = hasAggregate || containsAggregate(item.Expression)
	}
	type projected struct {
		row, source map[string]any
		sort        []any
	}
	out := []projected{}
	add := func(row, source map[string]any) {
		mergeProvenance(row, source)
		scope := e.cloneRow(source)
		for k, v := range row {
			e.bind(scope, k, v)
		}
		if c.Where != nil && e.eval(c.Where, scope) != true {
			return
		}
		var sortKeys []any
		precompute := false
		if !hasAggregate && !c.Distinct {
			for _, item := range c.OrderBy {
				variable, ok := item.Expression.(cypher.Variable)
				if _, present := row[variable.Name]; !ok || !present {
					precompute = true
					break
				}
			}
		}
		if precompute {
			sortKeys = make([]any, len(c.OrderBy))
			for i, item := range c.OrderBy {
				sortKeys[i] = e.eval(item.Expression, scope)
			}
		}

		out = append(out, projected{row, source, sortKeys})
	}
	if hasAggregate {
		groups := map[string][]map[string]any{}
		order := []string{}
		groupItems := []int{}
		for i, item := range c.Items {
			if !containsAggregate(item.Expression) {
				groupItems = append(groupItems, i)
			}
		}
		for _, row := range rows {
			values := make([]any, len(groupItems))
			for j, i := range groupItems {
				values[j] = e.eval(c.Items[i].Expression, row)
			}
			k := key(values)
			if _, ok := groups[k]; !ok {
				order = append(order, k)
			}
			groups[k] = append(groups[k], row)
		}
		if len(rows) == 0 && len(groupItems) == 0 {
			order = append(order, "")
			groups[""] = nil
		}
		for _, k := range order {
			group := groups[k]
			source := map[string]any{}
			if len(group) > 0 {
				source = e.cloneRow(group[0])
				for _, r := range group {
					mergeProvenance(source, r)
				}
			}
			row := map[string]any{}
			for i, item := range c.Items {
				if containsAggregate(item.Expression) {
					e.bind(row, columns[i], e.evaluateAggregate(item.Expression, group))
				} else {
					e.bind(row, columns[i], e.eval(item.Expression, source))
				}
			}
			add(row, source)
		}
	} else {
		for _, source := range rows {
			row := map[string]any{}
			if c.All {
				row = e.cloneRow(source)
			} else {
				for i, item := range c.Items {
					e.bind(row, columns[i], e.eval(item.Expression, source))
				}
			}
			add(row, source)
		}
	}
	if c.Distinct {
		seen := map[string]map[string]any{}
		unique := out[:0]
		for _, r := range out {
			k := rowKey(r.row, columns)
			if previous, ok := seen[k]; !ok {
				seen[k] = r.row
				unique = append(unique, r)
			} else {
				mergeProvenance(previous, r.row)
			}
		}
		out = unique
	}
	if len(c.OrderBy) > 0 {
		sort.SliceStable(out, func(i, j int) bool {
			e.check()
			for k, item := range c.OrderBy {
				left, right := any(nil), any(nil)
				if out[i].sort != nil {
					left = out[i].sort[k]
				} else {
					left = e.eval(item.Expression, out[i].row)
				}
				if out[j].sort != nil {
					right = out[j].sort[k]
				} else {
					right = e.eval(item.Expression, out[j].row)
				}
				cmp := compare(left, right)
				if cmp != 0 {
					if item.Descending {
						return cmp > 0
					}
					return cmp < 0
				}
			}
			return false
		})
	}
	bindingsAt := func(index int) map[string]any {
		if index < len(out) {
			return out[index].row
		}
		return map[string]any{}
	}
	start, end := 0, len(out)
	if c.Skip != nil {
		start = min(end, e.count(c.Skip, bindingsAt(0)))
	}
	if c.Limit != nil {
		end = start + min(end-start, e.count(c.Limit, bindingsAt(start)))
	}
	result := make([]map[string]any, 0, end-start)
	for _, r := range out[start:end] {
		result = append(result, r.row)
	}
	return result, columns
}
func (e evaluator) count(expr cypher.Expr, bindings map[string]any) int {
	n := cypherCountValue(e.eval(expr, bindings))
	if n < 0 {
		functionError("IllegalArgumentException", fmt.Sprintf("Requested element count %d is less than zero.", n))
	}
	return int(n)
}
func rowKey(row map[string]any, columns []string) string {
	v := make([]any, len(columns))
	for i, k := range columns {
		v[i] = row[k]
	}
	return key(v)
}
func distinctRows(rows []map[string]any, columns []string) []map[string]any {
	seen := map[string]map[string]any{}
	out := rows[:0]
	for _, r := range rows {
		visible := clone(r)
		delete(visible, provenanceKey)
		k := key(visible)
		if previous, ok := seen[k]; !ok {
			seen[k] = r
			out = append(out, r)
		} else {
			mergeProvenance(previous, r)
		}
	}
	return out
}
func validate(q *cypher.Query) {
	var expression func(cypher.Expr, bool)
	expression = func(expr cypher.Expr, allowAggregate bool) {
		switch x := expr.(type) {
		case nil, cypher.Literal, cypher.Variable, cypher.Parameter:
		case cypher.Property:
			expression(x.Object, false)
		case cypher.Unary:
			expression(x.Operand, false)
		case cypher.Binary:
			expression(x.Left, false)
			expression(x.Right, false)
		case cypher.Call:
			for _, a := range x.Arguments {
				expression(a, false)
			}
		case cypher.List:
			for _, a := range x.Elements {
				expression(a, false)
			}
		case cypher.Map:
			for _, a := range x.Entries {
				expression(a, false)
			}
		case cypher.Case:
			expression(x.Test, false)
			expression(x.Else, false)
			for _, w := range x.Whens {
				expression(w.Condition, false)
				expression(w.Result, false)
			}
		case cypher.ListComprehension:
			expression(x.List, false)
			expression(x.Where, false)
			expression(x.Projection, false)
		case cypher.Predicate:
			expression(x.List, false)
			expression(x.Where, false)
		case cypher.Index:
			expression(x.Object, false)
			expression(x.Index, false)
		case cypher.Slice:
			expression(x.Object, false)
			expression(x.From, false)
			expression(x.To, false)
		default:
			fail(fmt.Sprintf("unsupported expression %T", expr))
		}
	}
	for _, b := range q.Branches {
		for _, clause := range b.Clauses {
			switch c := clause.(type) {
			case cypher.MatchClause:
				for _, p := range c.Patterns {
					for _, rel := range p.Relationships {
						for _, value := range rel.Properties {
							expression(value, false)
						}
					}
					for _, n := range p.Nodes {
						for _, v := range n.Properties {
							expression(v, false)
						}
					}
				}
				expression(c.Where, false)
			case cypher.UnwindClause:
				expression(c.Expression, false)
			case cypher.ProjectionClause:
				for _, item := range c.Items {
					expression(item.Expression, true)
				}
				expression(c.Where, false)
				for _, s := range c.OrderBy {
					expression(s.Expression, false)
				}
				expression(c.Skip, false)
				expression(c.Limit, false)
			default:
				fail(fmt.Sprintf("unsupported Cypher clause %T", clause))
			}
		}
	}
}
