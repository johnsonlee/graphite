package query

import (
	"math"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
)

type distinctStringAtom struct {
	property, op, term string
	lower              bool
}
type indexedDistinctPlan struct {
	sourceCount         int
	preferMappedView    bool
	variable            string
	generic             bool
	atoms               []distinctStringAtom
	properties, columns []string
	limit, skip         int
	graphIDs            map[string]bool
}

var distinctCallSiteProperties = map[string]int{"caller_class": 0, "caller_name": 1, "callee_class": 2, "callee_name": 3}

func (e evaluator) compileIndexedDistinct(branch cypher.SingleQuery) *indexedDistinctPlan {
	if len(branch.Clauses) != 2 {
		return nil
	}
	m, ok := branch.Clauses[0].(cypher.MatchClause)
	if !ok || m.Optional || m.Where == nil || len(m.Patterns) != 1 {
		return nil
	}
	r, ok := branch.Clauses[1].(cypher.ProjectionClause)
	if !ok || !r.Distinct || r.With || r.All || r.Where != nil || len(r.OrderBy) != 0 {
		return nil
	}
	pattern := m.Patterns[0]
	if pattern.PathVariable != "" || len(pattern.Relationships) != 0 || len(pattern.Nodes) != 1 {
		return nil
	}
	node := pattern.Nodes[0]
	if node.Variable == "" || len(node.Labels) > 1 || len(node.Properties) != 0 {
		return nil
	}
	generic := len(node.Labels) == 0
	if len(node.Labels) == 1 {
		switch strings.ToLower(node.Labels[0]) {
		case "node":
			generic = true
		case "callsite", "callsitenode":
		default:
			return nil
		}
	}
	limit, literal := plannerLiteralCount(r.Limit)
	if !literal || limit <= 0 {
		return nil
	}
	skip, _ := plannerLiteralCount(r.Skip)
	if skip < 0 || int64(skip)+int64(limit) > math.MaxInt32 {
		return nil
	}
	plan := &indexedDistinctPlan{preferMappedView: r.Skip == nil, variable: node.Variable, generic: generic, limit: int(limit) + int(skip), skip: int(skip)}
	for _, item := range r.Items {
		p, ok := item.Expression.(cypher.Property)
		if !ok {
			return nil
		}
		v, ok := p.Object.(cypher.Variable)
		if !ok || v.Name != node.Variable {
			return nil
		}
		if _, ok := distinctCallSiteProperties[p.Key]; !ok && p.Key != "graphId" && p.Key != "class" && p.Key != "name" {
			return nil
		}
		plan.properties = append(plan.properties, p.Key)
		column := item.Alias
		if column == "" {
			column = columnName(item.Expression)
		}
		plan.columns = append(plan.columns, column)
	}
	condition := m.Where
	if r.Skip == nil {
		condition, plan.graphIDs = e.distinctGraphRoute(condition, node.Variable)
	}
	atoms, ok := e.compileDistinctDisjunction(condition, node.Variable)
	if !ok {
		return nil
	}
	hasCallSite := false
	for _, atom := range atoms {
		if _, ok := distinctCallSiteProperties[atom.property]; ok {
			hasCallSite = true
		}
	}
	if !hasCallSite {
		return nil
	}
	plan.atoms = atoms
	return plan
}
func (e evaluator) distinctStringConstant(expression cypher.Expr) (string, bool) {
	switch x := expression.(type) {
	case cypher.Literal:
		s, ok := x.Value.(string)
		return s, ok
	case cypher.Parameter:
		s, ok := e.parameters[x.Name].(string)
		return s, ok
	}
	return "", false
}
func distinctProperty(expression cypher.Expr, variable string) (string, bool) {
	p, ok := expression.(cypher.Property)
	if !ok {
		return "", false
	}
	v, ok := p.Object.(cypher.Variable)
	return p.Key, ok && v.Name == variable
}
func (e evaluator) compileDistinctAtom(expression cypher.Expr, variable string) (distinctStringAtom, bool) {
	e.check()
	b, ok := expression.(cypher.Binary)
	if !ok {
		return distinctStringAtom{}, false
	}
	switch b.Op {
	case "=", "CONTAINS", "STARTS WITH", "ENDS WITH":
	default:
		return distinctStringAtom{}, false
	}
	property, ok := distinctProperty(b.Left, variable)
	lower, coalesces := false, false
	if !ok {
		call, good := b.Left.(cypher.Call)
		if !good || call.Distinct || call.Star || len(call.Arguments) != 1 || (!strings.EqualFold(call.Name, "toLower") && !strings.EqualFold(call.Name, "toLowercase")) {
			return distinctStringAtom{}, false
		}
		lower = true
		property, ok = distinctProperty(call.Arguments[0], variable)
		if !ok {
			coalesce, good := call.Arguments[0].(cypher.Call)
			if !good || coalesce.Distinct || coalesce.Star || !strings.EqualFold(coalesce.Name, "coalesce") || len(coalesce.Arguments) != 2 {
				return distinctStringAtom{}, false
			}
			empty, good := coalesce.Arguments[1].(cypher.Literal)
			if !good || empty.Value != "" {
				return distinctStringAtom{}, false
			}
			property, ok = distinctProperty(coalesce.Arguments[0], variable)
			coalesces = true
		}
	}
	if !ok {
		return distinctStringAtom{}, false
	}
	if _, ok := distinctCallSiteProperties[property]; !ok && property != "class" && property != "name" {
		return distinctStringAtom{}, false
	}
	term, ok := e.distinctStringConstant(b.Right)
	if !ok || (coalesces && term == "") {
		return distinctStringAtom{}, false
	}
	return distinctStringAtom{property: property, op: b.Op, term: term, lower: lower}, true
}
func distinctExistsGuard(expression cypher.Expr, variable, property string) bool {
	call, ok := expression.(cypher.Call)
	if !ok || call.Distinct || call.Star || !strings.EqualFold(call.Name, "exists") || len(call.Arguments) != 1 {
		return false
	}
	p, ok := distinctProperty(call.Arguments[0], variable)
	return ok && p == property
}
func (e evaluator) compileDistinctDisjunction(expression cypher.Expr, variable string) ([]distinctStringAtom, bool) {
	e.check()
	if atom, ok := e.compileDistinctAtom(expression, variable); ok {
		return []distinctStringAtom{atom}, true
	}
	b, ok := expression.(cypher.Binary)
	if !ok {
		return nil, false
	}
	switch b.Op {
	case "OR":
		a, ok := e.compileDistinctDisjunction(b.Left, variable)
		if !ok {
			return nil, false
		}
		z, ok := e.compileDistinctDisjunction(b.Right, variable)
		return append(a, z...), ok
	case "AND":
		if atom, ok := e.compileDistinctAtom(b.Left, variable); ok && distinctExistsGuard(b.Right, variable, atom.property) {
			return []distinctStringAtom{atom}, true
		}
		if atom, ok := e.compileDistinctAtom(b.Right, variable); ok && distinctExistsGuard(b.Left, variable, atom.property) {
			return []distinctStringAtom{atom}, true
		}
	case "IN":
		list, ok := b.Right.(cypher.List)
		if !ok {
			return nil, false
		}
		out := []distinctStringAtom{}
		for _, value := range list.Elements {
			atom, ok := e.compileDistinctAtom(cypher.Binary{Op: "=", Left: b.Left, Right: value}, variable)
			if !ok {
				return nil, false
			}
			out = append(out, atom)
		}
		return out, len(out) > 0
	}
	return nil, false
}
func distinctGraphReference(expression cypher.Expr, variable string) bool {
	if p, ok := distinctProperty(expression, variable); ok {
		return p == "graphId"
	}
	call, ok := expression.(cypher.Call)
	if !ok || call.Distinct || call.Star || !strings.EqualFold(call.Name, "graphId") || len(call.Arguments) != 1 {
		return false
	}
	v, ok := call.Arguments[0].(cypher.Variable)
	return ok && v.Name == variable
}
func (e evaluator) distinctPureGraphConstraint(expression cypher.Expr, variable string) map[string]bool {
	b, ok := expression.(cypher.Binary)
	if !ok {
		return nil
	}
	if b.Op == "AND" || b.Op == "OR" {
		left, right := e.distinctPureGraphConstraint(b.Left, variable), e.distinctPureGraphConstraint(b.Right, variable)
		if left == nil || right == nil {
			return nil
		}
		if b.Op == "AND" {
			for id := range left {
				if !right[id] {
					delete(left, id)
				}
			}
		} else {
			for id := range right {
				left[id] = true
			}
		}
		return left
	}
	if b.Op == "=" {
		if distinctGraphReference(b.Left, variable) {
			if id, ok := e.distinctStringConstant(b.Right); ok {
				return map[string]bool{id: true}
			}
		}
		if distinctGraphReference(b.Right, variable) {
			if id, ok := e.distinctStringConstant(b.Left); ok {
				return map[string]bool{id: true}
			}
		}
	}
	if b.Op == "IN" && distinctGraphReference(b.Left, variable) {
		out := map[string]bool{}
		switch x := b.Right.(type) {
		case cypher.List:
			for _, item := range x.Elements {
				id, ok := e.distinctStringConstant(item)
				if !ok {
					return nil
				}
				out[id] = true
			}
			return out
		case cypher.Parameter:
			values, ok := e.parameters[x.Name].([]any)
			if !ok {
				return nil
			}
			for _, v := range values {
				id, ok := v.(string)
				if !ok {
					return nil
				}
				out[id] = true
			}
			return out
		}
	}
	return nil
}
func (e evaluator) distinctGraphRoute(expression cypher.Expr, variable string) (cypher.Expr, map[string]bool) {
	var route map[string]bool
	var visit func(cypher.Expr) cypher.Expr
	visit = func(x cypher.Expr) cypher.Expr {
		if b, ok := x.(cypher.Binary); ok && b.Op == "AND" {
			left, right := visit(b.Left), visit(b.Right)
			if left == nil {
				return right
			}
			if right == nil {
				return left
			}
			return cypher.Binary{Op: "AND", Left: left, Right: right}
		}
		if ids := e.distinctPureGraphConstraint(x, variable); ids != nil {
			if route == nil {
				route = ids
			} else {
				for id := range route {
					if !ids[id] {
						delete(route, id)
					}
				}
			}
			return nil
		}
		return x
	}
	residual := visit(expression)
	return residual, route
}
