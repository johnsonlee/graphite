package query

import "github.com/johnsonlee/graphite/graphite-server/internal/store"

// Preparation intentionally precedes projection eligibility, as in main's
// selectedProjectionHitsByTuple. A single-column projection may build the table
// and then return to the property-posting anchor implementation.
func (e evaluator) distinctExactTupleHits(source Graph, index *store.DistinctStringIndex, plan *indexedDistinctPlan, targets []map[string]any) (map[string]bool, bool) {
	hasProperty := false
	for _, property := range plan.properties {
		if _, ok := distinctCallSiteProperties[property]; ok {
			hasProperty = true
			break
		}
	}
	if !hasProperty {
		return nil, false
	}
	available, err := index.PrepareExactProjectionTuples(e.ctx, len(targets))
	failProjectionRead(err)
	if !available || len(plan.properties) != 4 {
		return nil, false
	}
	seen := [4]bool{}
	for _, property := range plan.properties {
		p, ok := distinctCallSiteProperties[property]
		if !ok || seen[p] {
			return nil, false
		}
		seen[p] = true
	}
	hits := map[string]bool{}
	for _, target := range targets {
		e.check()
		var expected [4]string
		valid := true
		for position, property := range plan.properties {
			value, ok := target[plan.columns[position]].(string)
			if !ok {
				valid = false
				break
			}
			expected[distinctCallSiteProperties[property]] = value
		}
		if !valid {
			continue
		}
		probe, err := index.ExactProjectionProbe(e.ctx, expected)
		failProjectionRead(err)
		for {
			e.check()
			id, ok, err := probe.Next(e.ctx)
			failProjectionRead(err)
			if !ok {
				break
			}
			matched := false
			for _, atom := range plan.atoms {
				p, ok := distinctCallSiteProperties[atom.property]
				if !ok {
					continue
				}
				sid, err := source.Store.ProjectionStringID(e.ctx, id, store.CallSiteStringProperty(p))
				failProjectionRead(err)
				value, err := source.Store.ProjectionString(e.ctx, sid)
				failProjectionRead(err)
				if e.distinctAtomMatches(atom, value) {
					matched = true
					break
				}
			}
			if matched {
				_, err := source.Store.ProjectionNodeOrder(e.ctx, id)
				failProjectionRead(err)
				hits[distinctVisibleKey(target)] = true
				break
			}
		}
	}
	return hits, true
}
