package query

import (
	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// computeEarlyLimit mirrors the general main pipeline's pre-evaluation. It
// evaluates in empty bindings before any clause, and keeps only positive counts.
// The same LIMIT expression is evaluated again in projected bindings later.
func (e evaluator) computeEarlyLimit(branch cypher.SingleQuery) int {
	matchIndex, limitIndex := -1, -1
	var match cypher.MatchClause
	var limit cypher.Expr
	for index, clause := range branch.Clauses {
		if c, ok := clause.(cypher.MatchClause); ok && !c.Optional && matchIndex < 0 {
			matchIndex, match = index, c
		}
		if c, ok := clause.(cypher.ProjectionClause); ok && c.Limit != nil && limitIndex < 0 {
			limitIndex, limit = index, c.Limit
		}
	}
	if matchIndex < 0 || limitIndex <= matchIndex || len(match.Patterns) != 1 || match.Where != nil {
		return 0
	}
	for _, clause := range branch.Clauses[matchIndex+1 : limitIndex+1] {
		projection, ok := clause.(cypher.ProjectionClause)
		if !ok || projection.With || projection.Distinct || projection.Where != nil || len(projection.OrderBy) != 0 || projection.Skip != nil {
			return 0
		}
		for _, item := range projection.Items {
			if containsAggregate(item.Expression) {
				return 0
			}
		}
	}
	count := cypherCountValue(e.eval(limit, map[string]any{}))
	if count > 0 {
		return int(count)
	}
	return 0
}

// matchWithLimit follows executeMatch: each input's pattern can produce up to
// limit matches, then the combined result is truncated. It intentionally does
// not pass a smaller remaining budget to later input rows.
func (e evaluator) matchWithLimit(graph *store.Store, rows []map[string]any, clause cypher.MatchClause, limit int) []map[string]any {
	states := make([]matchState, len(rows))
	for i, row := range rows {
		states[i].row = row
		if len(clause.Patterns) > 1 {
			states[i].used = map[edgeIdentity]bool{}
		}
	}
	for _, pattern := range clause.Patterns {
		next := []matchState{}
		for _, state := range states {
			e.check()
			produced := 0
			e.matchPatternUntil(graph, pattern, state, func(match matchState) bool {
				next = append(next, match)
				produced++
				return produced < limit
			})
			if len(next) >= limit {
				break
			}
		}
		if len(next) > limit {
			next = next[:limit]
		}
		states = next
	}
	result := make([]map[string]any, 0, len(states))
	for _, state := range states {
		if clause.Where == nil || e.eval(clause.Where, state.row) == true {
			result = append(result, state.row)
		}
	}
	return result
}

// matchPatternUntil composes candidate and relationship iterators depth first,
// as main's Sequence.flatMap does. No later node/edge target is decoded after
// the final consumer returns false.
func (e evaluator) matchPatternUntil(graph *store.Store, pattern cypher.Pattern, initial matchState, accept func(matchState) bool) bool {
	if len(pattern.Nodes) == 0 {
		return accept(initial)
	}
	tracked := initial.used != nil || len(pattern.Relationships) > 1
	if tracked && initial.used == nil {
		initial.used = map[edgeIdentity]bool{}
	}
	reserved := map[edgeIdentity]bool{}
	if tracked {
		for _, rel := range pattern.Relationships {
			for edge := range e.relationshipBindings(initial.row[rel.Variable]) {
				reserved[edge] = true
			}
		}
	}
	var descend func(matchState, int) bool
	descend = func(state matchState, index int) bool {
		e.check()
		if index < len(pattern.Relationships) {
			return e.matchRelationshipUntil(graph, state, pattern.Relationships[index], pattern.Nodes[index+1], reserved, func(next matchState) bool {
				return descend(next, index+1)
			})
		}
		if pattern.PathVariable != "" {
			path := e.makePath(state.nodes, state.edges)
			e.bind(state.row, pattern.PathVariable, path)
			if id, qualified := valueGraphID(path); qualified {
				addProvenance(state.row, id)
			}
		}
		return accept(state)
	}
	return e.walkNodeCandidatesUntil(graph, pattern.Nodes[0], initial.row, nil, func(value any) bool {
		e.check()
		if !e.matchesCandidate(value, pattern.Nodes[0], initial.row) {
			return true
		}
		bound := e.cloneRow(initial.row)
		if name := pattern.Nodes[0].Variable; name != "" {
			e.bind(bound, name, value)
			if id, qualified := valueGraphID(value); qualified {
				addProvenance(bound, id)
			}
		}
		return descend(matchState{row: bound, used: initial.used, current: value, nodes: []any{value}}, 0)
	})
}
