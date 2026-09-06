package query

import (
	"fmt"
	"sort"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
)

type rowOrder struct {
	row  map[string]any
	keys []string
}

// Order is only retained when observable through RETURN * or an aggregate of
// entire bindings. Keeping it outside the binding map avoids reserving another
// user-visible variable name. The row reference prevents address reuse.
func needsRowOrder(q *cypher.Query) bool {
	var expression func(cypher.Expr) bool
	expression = func(expr cypher.Expr) bool {
		switch x := expr.(type) {
		case cypher.Call:
			if len(x.Arguments) == 0 && !x.Star && (strings.EqualFold(x.Name, "collect") || strings.EqualFold(x.Name, "min") || strings.EqualFold(x.Name, "max")) {
				return true
			}
			for _, a := range x.Arguments {
				if expression(a) {
					return true
				}
			}
		case cypher.Property:
			return expression(x.Object)
		case cypher.Binary:
			return expression(x.Left) || expression(x.Right)
		case cypher.Unary:
			return expression(x.Operand)
		case cypher.List:
			for _, v := range x.Elements {
				if expression(v) {
					return true
				}
			}
		case cypher.Map:
			for _, v := range x.Entries {
				if expression(v) {
					return true
				}
			}
		case cypher.Case:
			if expression(x.Test) || expression(x.Else) {
				return true
			}
			for _, w := range x.Whens {
				if expression(w.Condition) || expression(w.Result) {
					return true
				}
			}
		}
		return false
	}
	for _, branch := range q.Branches {
		for _, clause := range branch.Clauses {
			if p, ok := clause.(cypher.ProjectionClause); ok {
				if p.All {
					return true
				}
				for _, item := range p.Items {
					if expression(item.Expression) {
						return true
					}
				}
			}
		}
	}
	return false
}
func rowAddress(row map[string]any) string { return fmt.Sprintf("%p", row) }
func (e evaluator) rowKeys(row map[string]any) []string {
	keys := []string{}
	seen := map[string]bool{}
	if e.rowOrders != nil {
		if old, ok := e.rowOrders[rowAddress(row)]; ok {
			for _, key := range old.keys {
				if _, exists := row[key]; exists {
					keys = append(keys, key)
					seen[key] = true
				}
			}
		}
	}
	extra := []string{}
	for key := range row {
		if !seen[key] {
			extra = append(extra, key)
		}
	}
	sort.Strings(extra)
	return append(keys, extra...)
}
func (e evaluator) cloneRow(row map[string]any) map[string]any {
	result := clone(row)
	if e.rowOrders != nil {
		e.rowOrders[rowAddress(result)] = rowOrder{result, e.rowKeys(row)}
	}
	return result
}
func (e evaluator) bind(row map[string]any, name string, value any) {
	if e.rowOrders != nil {
		keys := e.rowKeys(row)
		if _, present := row[name]; !present {
			keys = append(keys, name)
		}
		e.rowOrders[rowAddress(row)] = rowOrder{row, keys}
	}
	row[name] = value
}
