package query

import (
	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// A single-node pattern has no path/relationship state to retain. Evaluate its
// WHERE clause while visiting candidates and publish a fresh row only on a hit.
// ORDER BY, aggregation and LIMIT still run in their ordinary pipeline phases.
func (e evaluator) matchSingleNode(graph *store.Store, rows []map[string]any, clause cypher.MatchClause) []map[string]any {
	pattern := clause.Patterns[0].Nodes[0]
	result := []map[string]any{}
	for _, row := range rows {
		e.check()
		bound := e.cloneRow(row)
		accepted := false
		e.nodeCandidates(graph, pattern, row, func(value any) {
			e.check()
			if !e.matches(value, pattern, row) {
				return
			}
			if pattern.Variable != "" {
				e.bind(bound, pattern.Variable, value)
				if ids, present := row[provenanceKey]; present {
					bound[provenanceKey] = ids
				} else {
					delete(bound, provenanceKey)
				}
				if id := valueGraphID(value); id != "" {
					addProvenance(bound, id)
				}
			}
			if clause.Where != nil && e.eval(clause.Where, bound) != true {
				return
			}
			result = append(result, e.cloneRow(bound))
			accepted = true
		})
		if clause.Optional && !accepted {
			missing := e.cloneRow(row)
			if pattern.Variable != "" {
				if _, present := missing[pattern.Variable]; !present {
					e.bind(missing, pattern.Variable, nil)
				}
			}
			result = append(result, missing)
		}
	}
	return result
}
