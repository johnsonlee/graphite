package query

import (
	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"strings"
)

// QueryPipeline.DirectStringConjunction precedes the generic necessary-candidate
// rank. Its residual uses direct string semantics (including short circuit),
// not the expression evaluator's eager AND.
func (e evaluator) lazyConjunction(expr cypher.Expr, variable string) ([]distinctStringAtom, func(evaluator, any) bool) {
	b, ok := expr.(cypher.Binary)
	if !ok || b.Op != "AND" {
		return nil, nil
	}
	left, lok := e.lazyDirectFilter(b.Left, variable)
	right, rok := e.lazyDirectFilter(b.Right, variable)
	makePlan := func(required distinctStringAtom, others []distinctStringAtom) ([]distinctStringAtom, func(evaluator, any) bool) {
		return []distinctStringAtom{required}, func(local evaluator, node any) bool {
			get := func(a distinctStringAtom) any {
				return local.eval(cypher.Property{Object: cypher.Variable{Name: variable}, Key: a.property}, map[string]any{variable: node})
			}
			if !local.distinctAtomMatches(required, get(required)) {
				return false
			}
			for _, a := range others {
				if local.distinctAtomMatches(a, get(a)) {
					return true
				}
			}
			return false
		}
	}
	if lok && rok {
		if right.op == "=" && left.op != "=" {
			return makePlan(right, []distinctStringAtom{left})
		}
		return makePlan(left, []distinctStringAtom{right})
	}
	if _, supported := e.compileDistinctAtom(b.Left, variable); lok && supported {
		if rest, ok := e.compileDistinctDisjunction(b.Right, variable); ok {
			return makePlan(left, lazyUniqueAtoms(rest))
		}
	}
	if _, supported := e.compileDistinctAtom(b.Right, variable); rok && supported {
		if rest, ok := e.compileDistinctDisjunction(b.Left, variable); ok {
			return makePlan(right, lazyUniqueAtoms(rest))
		}
	}
	return nil, nil
}

// QueryPipeline.DirectStringCandidatePlan: necessary filters only. Consumer
// always evaluates the original residual after the selected full Node decode.
func (e evaluator) lazyNecessaryCandidates(expr cypher.Expr, variable string) []distinctStringAtom {
	if atoms, ok := e.compileDistinctDisjunction(expr, variable); ok {
		return lazyUniqueAtoms(atoms)
	}
	b, ok := expr.(cypher.Binary)
	if !ok {
		return nil
	}
	if b.Op == "=~" {
		if term, ok := e.distinctStringConstant(b.Right); ok && strings.HasPrefix(term, ".*\\Q") && strings.HasSuffix(term, "\\E.*") {
			literal := term[4 : len(term)-4]
			if literal != "" && !strings.Contains(literal, "\\E") {
				if a, ok := e.compileDistinctAtom(cypher.Binary{Op: "CONTAINS", Left: b.Left, Right: cypher.Literal{Value: literal}}, variable); ok {
					return []distinctStringAtom{a}
				}
			}
		}
	}
	if b.Op != "AND" {
		return nil
	}
	left := e.lazyNecessaryCandidates(b.Left, variable)
	right := e.lazyNecessaryCandidates(b.Right, variable)
	if left == nil {
		return right
	}
	if right == nil || lazyCandidateRank(left) <= lazyCandidateRank(right) {
		return left
	}
	return right
}
func lazyCandidateRank(atoms []distinctStringAtom) int {
	rank := int32(0)
	for _, a := range atoms {
		switch a.op {
		case "STARTS WITH":
			rank += 100
		case "ENDS WITH":
			rank += 200
		case "CONTAINS":
			rank += 300
		}
		if a.property != "caller_name" && a.property != "callee_name" && a.property != "name" {
			rank += 20
		}
		rank -= int32(min(len(javaUTF16(a.term)), 64))
	}
	return int(rank)
}

// DirectStringFilter itself accepts arbitrary non-qualified properties. Only
// Disjunction/CandidatePlan requires the property/type capability table. This
// distinction is observable for a conjunction whose required property has no
// corresponding candidate type: its source is available-empty.
func (e evaluator) lazyDirectFilter(expr cypher.Expr, variable string) (distinctStringAtom, bool) {
	b, ok := expr.(cypher.Binary)
	if !ok {
		return distinctStringAtom{}, false
	}
	switch b.Op {
	case "=", "CONTAINS", "STARTS WITH", "ENDS WITH":
	default:
		return distinctStringAtom{}, false
	}
	operand := b.Left
	lower, coalesces := false, false
	if c, ok := operand.(cypher.Call); ok {
		if c.Distinct || c.Star || len(c.Arguments) != 1 || (!strings.EqualFold(c.Name, "toLower") && !strings.EqualFold(c.Name, "toLowercase")) {
			return distinctStringAtom{}, false
		}
		lower = true
		operand = c.Arguments[0]
		if c, ok := operand.(cypher.Call); ok {
			if c.Distinct || c.Star || len(c.Arguments) != 2 || !strings.EqualFold(c.Name, "coalesce") {
				return distinctStringAtom{}, false
			}
			literal, ok := c.Arguments[1].(cypher.Literal)
			if !ok || literal.Value != "" {
				return distinctStringAtom{}, false
			}
			coalesces = true
			operand = c.Arguments[0]
		}
	}
	property, ok := distinctProperty(operand, variable)
	if !ok || property == "graphId" || property == "elementId" || property == "qualifiedId" {
		return distinctStringAtom{}, false
	}
	term, ok := e.distinctStringConstant(b.Right)
	if !ok || coalesces && term == "" {
		return distinctStringAtom{}, false
	}
	return distinctStringAtom{property: property, op: b.Op, term: term, lower: lower}, true
}
func lazyUniqueAtoms(atoms []distinctStringAtom) []distinctStringAtom {
	seen := map[distinctStringAtom]bool{}
	out := []distinctStringAtom{}
	for _, atom := range atoms {
		identity := atom
		identity.term = javaFromUTF16(javaUTF16(atom.term))
		identity.property = javaFromUTF16(javaUTF16(atom.property))
		if !seen[identity] {
			seen[identity] = true
			out = append(out, atom)
		}
	}
	return out
}
