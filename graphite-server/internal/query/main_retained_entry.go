package query

import "github.com/johnsonlee/graphite/graphite-server/internal/store"

// Unlike the mapped view, main's retained exact preflight also admits prefix
// and suffix predicates. It checks every predicate's capability before doing
// any lookup, then visits predicates in order. There is no request-local shared
// result map here: only the actual retained LRU can eliminate repeated work.
func (e evaluator) mainRetainedExactMatches(source Graph, index *store.DistinctStringIndex, plan *mainStringSourceSpec) ([]map[int32]bool, bool) {
	for _, atom := range plan.atoms {
		units := javaUTF16(atom.term)
		if atom.op == "=" || len(units) < 3 {
			return nil, false
		}
		if !atom.lower {
			for _, unit := range units {
				if unit > 127 {
					return nil, false
				}
			}
		}
	}
	sets := make([]map[int32]bool, 0, len(plan.atoms))
	for _, atom := range plan.atoms {
		ids, supported := e.mainStringIndexMatches(source, index, atom)
		if !supported {
			return nil, false
		}
		set := make(map[int32]bool, len(ids))
		for _, id := range ids {
			set[id] = true
		}
		sets = append(sets, set)
	}
	return sets, true
}
