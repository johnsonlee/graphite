package query

import (
	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// orderedPropertyLimit mirrors main's OrderedPropertyLimitQuery admission and
// bounded heap. Keeping the actual route preserves both consumption and the
// diagnostic distinction between a completed fast path and a failed scan.
func (e evaluator) orderedPropertyLimit(graph *store.Store, branch cypher.SingleQuery) (Result, bool) {
	if len(branch.Clauses) != 2 {
		return Result{}, false
	}
	m, ok := branch.Clauses[0].(cypher.MatchClause)
	if !ok || m.Optional || m.Where != nil || len(m.Patterns) != 1 {
		return Result{}, false
	}
	r, ok := branch.Clauses[1].(cypher.ProjectionClause)
	if !ok || r.With || r.Distinct || r.All || r.Where != nil || r.Skip != nil || len(r.Items) == 0 || len(r.OrderBy) == 0 {
		return Result{}, false
	}
	pattern := m.Patterns[0]
	if pattern.PathVariable != "" || len(pattern.Nodes) != 1 || len(pattern.Relationships) != 0 {
		return Result{}, false
	}
	node := pattern.Nodes[0]
	if node.Variable == "" || len(node.Labels) > 1 || len(node.Properties) != 0 {
		return Result{}, false
	}
	if len(node.Labels) != 0 && !lazyKnownLabel(node.Labels[0]) {
		return Result{}, false
	}
	columns := make([]string, len(r.Items))
	properties := make([]string, len(r.Items))
	seen := map[string]bool{}
	for i, item := range r.Items {
		property, ok := item.Expression.(cypher.Property)
		if !ok {
			return Result{}, false
		}
		owner, ok := property.Object.(cypher.Variable)
		if !ok || owner.Name != node.Variable {
			return Result{}, false
		}
		column := item.Alias
		if column == "" {
			column = columnName(item.Expression)
		}
		if seen[column] {
			return Result{}, false
		}
		seen[column] = true
		columns[i], properties[i] = column, property.Key
	}
	sortColumns := make([]string, len(r.OrderBy))
	for i, item := range r.OrderBy {
		column, ok := item.Expression.(cypher.Variable)
		if !ok || !seen[column.Name] {
			return Result{}, false
		}
		sortColumns[i] = column.Name
	}
	literal, ok := r.Limit.(cypher.Literal)
	if !ok {
		return Result{}, false
	}
	if _, numeric := number(literal.Value); !numeric {
		return Result{}, false
	}
	limit := cypherCountValue(literal.Value)
	if limit > 10_000 {
		return Result{}, false
	}
	if limit <= 0 {
		return Result{Columns: columns, Rows: []map[string]any{}}, true
	}
	top := &streamingRowHeap{items: r.OrderBy}
	if e.workTrackingEnabled {
		top.check = e.check
	}
	var encounter int64
	e.nodeCandidates(graph, node, nil, func(candidate any) {
		row := map[string]any{}
		for i, property := range properties {
			var value any
			switch n := candidate.(type) {
			case store.Node:
				value = NodeProperty(n, property)
			case qualifiedNode:
				value = qualifiedProperty(n, property)
			}
			e.bind(row, columns[i], value)
		}
		if id, qualified := valueGraphID(candidate); qualified {
			addProvenance(row, id)
		}
		values := make([]any, len(sortColumns))
		for i, column := range sortColumns {
			values[i] = row[column]
		}
		top.add(&streamingRankedRow{row: row, values: values, encounter: encounter}, int(limit))
		encounter++
	})
	e.check()
	return top.result(columns, 0), true
}
