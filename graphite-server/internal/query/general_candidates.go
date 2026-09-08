package query

import (
	"math"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func hasUnknownNodeLabel(branch cypher.SingleQuery) bool {
	for _, clause := range branch.Clauses {
		match, ok := clause.(cypher.MatchClause)
		if !ok {
			continue
		}
		for _, pattern := range match.Patterns {
			for _, node := range pattern.Nodes {
				for _, label := range node.Labels {
					if !strings.EqualFold(label, "Method") && !lazyKnownLabel(label) {
						return true
					}
				}
			}
		}
	}
	return false
}

func candidateIsMethod(value any) bool {
	switch v := value.(type) {
	case store.MethodDescriptor, qualifiedMethod:
		return true
	case *candidateSlot:
		return v.isMethod
	}
	return false
}

func candidateMatchesClass(value any, label string) bool {
	switch v := value.(type) {
	case store.Node:
		return matchesLabel(v, label)
	case qualifiedNode:
		return matchesLabel(v.Node, label)
	case *candidateSlot:
		return !v.isMethod && matchesLabel(v.node, label)
	}
	return false
}

// Main's mapped typed iterator selects IDs from the type index. Its generic
// cast is erased to Node; only multiple labels are checked again after reading.
// Bound values and seeks have already undergone their concrete class check.
func (e evaluator) matchesCandidate(value any, pattern cypher.NodePattern, row map[string]any) bool {
	if len(pattern.Labels) == 1 && !candidateIsMethod(value) {
		pattern.Labels = nil
	}
	return e.matches(value, pattern, row)
}

func elementIDReference(expression cypher.Expr, variable string) bool {
	var object cypher.Expr
	switch value := expression.(type) {
	case cypher.Call:
		if !strings.EqualFold(value.Name, "elementId") || len(value.Arguments) != 1 {
			return false
		}
		object = value.Arguments[0]
	case cypher.Property:
		if value.Key != "elementId" && value.Key != "qualifiedId" {
			return false
		}
		object = value.Object
	default:
		return false
	}
	v, ok := object.(cypher.Variable)
	return ok && v.Name == variable
}

func (e evaluator) tryGeneralElementIDSeek(graph *store.Store, rows []map[string]any, clause cypher.MatchClause) ([]map[string]any, bool) {
	if clause.Optional || len(clause.Patterns) != 1 {
		return nil, false
	}
	pattern := clause.Patterns[0]
	if pattern.PathVariable != "" || len(pattern.Nodes) != 1 || len(pattern.Relationships) != 0 {
		return nil, false
	}
	nodePattern := pattern.Nodes[0]
	if nodePattern.Variable == "" {
		return nil, false
	}
	for _, row := range rows {
		if _, present := row[nodePattern.Variable]; present {
			return nil, false
		}
	}
	comparison, ok := clause.Where.(cypher.Binary)
	if !ok || comparison.Op != "=" {
		return nil, false
	}
	var literal cypher.Expr
	if elementIDReference(comparison.Left, nodePattern.Variable) {
		literal = comparison.Right
	} else if elementIDReference(comparison.Right, nodePattern.Variable) {
		literal = comparison.Left
	} else {
		return nil, false
	}
	value, ok := literal.(cypher.Literal)
	if !ok {
		return nil, false
	}
	id, ok := value.Value.(string)
	if !ok {
		return nil, false
	}
	result := []map[string]any{}
	source := Graph{Store: graph}
	if e.cross {
		separator := strings.LastIndexByte(id, ':')
		if separator < 0 || separator == len(id)-1 {
			return result, true
		}
		graphID := id[:separator]
		id = id[separator+1:]
		source = Graph{}
		for _, candidate := range e.graphs {
			if candidate.ID == graphID {
				source = candidate
				break
			}
		}
		if source.Store == nil {
			return result, true
		}
	}
	number, err := parseJavaLong(id)
	if err != nil || number < math.MinInt32 || number > math.MaxInt32 {
		return result, true
	}
	e.check()
	// Unlike a scan's decoded sequence, main's trackedNode seek charges before
	// lookup, including valid IDs whose persisted node is absent.
	e.consume(1)
	node, present, err := source.Store.GeneralCandidateNode(e.ctx, int32(number))
	if err != nil {
		failProjectionRead(err)
	}
	if !present {
		return result, true
	}
	label := "Node"
	if len(nodePattern.Labels) != 0 {
		label = nodePattern.Labels[0]
	}
	if !matchesLabel(node, label) {
		return result, true
	}
	candidate := e.nodeValue(source.Store, source.ID, node)
	for _, row := range rows {
		if !e.matchesCandidate(candidate, nodePattern, row) {
			continue
		}
		bound := e.cloneRow(row)
		e.bind(bound, nodePattern.Variable, candidate)
		if e.cross {
			addProvenance(bound, source.ID)
		}
		if e.eval(clause.Where, bound) == true {
			result = append(result, bound)
		}
	}
	return result, true
}
