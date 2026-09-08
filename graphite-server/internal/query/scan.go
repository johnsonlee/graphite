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
	slot := &candidateSlot{}
	var indexed func(func(any))
	if len(rows) == 1 && len(rows[0]) == 0 {
		indexed = e.indexedNodeWalker(graph, clause, slot)
	}
	for _, row := range rows {
		e.check()
		bound := e.cloneRow(row)
		accepted := false
		walk := indexed
		if walk == nil {
			walk = func(accept func(any)) { e.walkNodeCandidates(graph, pattern, row, slot, accept) }
		}
		walk(func(value any) {
			// Scratch bindings may be retained for key-order bookkeeping. Never
			// leave the borrowed view in them after this candidate finishes.
			// Retain the key position even on a miss, as the original scanner did.
			defer func() {
				if borrowed, ok := bound[pattern.Variable].(*candidateSlot); ok && borrowed == slot {
					bound[pattern.Variable] = nil
				}
			}()
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
				if id, qualified := valueGraphID(value); qualified {
					addProvenance(bound, id)
				}
			}
			if clause.Where != nil && e.eval(clause.Where, bound) != true {
				return
			}
			if pattern.Variable != "" {
				bound[pattern.Variable] = freezeCandidate(bound[pattern.Variable])
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
