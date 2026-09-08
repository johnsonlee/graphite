package query

import "github.com/johnsonlee/graphite/graphite-server/internal/store"

func (e evaluator) prepareDistinctSplitSourceIndex(source Graph, plan *indexedDistinctPlan) (*store.DistinctStringIndex, bool, map[int]map[int32]bool) {
	index, representation, err := source.Store.PrepareMainDistinctSplitIndex(e.ctx, plan.preferMappedView, e.storeWorkConsumer())
	failProjectionRead(err)
	if index != nil {
		exact, supported := e.distinctSplitExactMatches(source, index, plan)
		if supported {
			anyMatch := false
			for _, ids := range exact {
				anyMatch = anyMatch || len(ids) > 0
			}
			if !anyMatch {
				return index, true, exact
			}
			if distinctSplitRawAdmitted(source, plan) {
				copy := *index
				copy.ParallelRaw = true
				return &copy, plan.limit <= 0, exact
			}
		}
		// A retained index owns the fallback even when exact matching declined.
		// A mapped view alone does not: main next tries preflight/raw, then build.
		if representation == store.MainDistinctIndexRetained {
			return index, false, nil
		}
	}
	if cannotMatch, _ := e.distinctSplitCannotMatch(source, plan.atoms); cannotMatch {
		raw, err := source.Store.MainDistinctRawIndex(true)
		failProjectionRead(err)
		return raw, true, nil
	}
	if distinctSplitRawAdmitted(source, plan) {
		raw, err := source.Store.MainDistinctRawIndex(true)
		failProjectionRead(err)
		return raw, plan.limit <= 0, nil
	}
	index, available, err := source.Store.PrepareMainDistinctFallbackIndex(e.ctx, e.storeWorkConsumer())
	failProjectionRead(err)
	if !available {
		return nil, false, nil
	}
	return index, false, nil
}

func distinctSplitRawAdmitted(source Graph, plan *indexedDistinctPlan) bool {
	if plan.limit <= 0 {
		return true
	}
	count := len(source.Store.NodesOfKind("CallSiteNode"))
	return count >= 4096 && int64(count) <= 2147483647 && plan.limit < count
}

// Nil means unsupported, never a proven miss. The exact sets are scoped to this
// provider invocation. Retained and mapped indexes use different cache policies.
func (e evaluator) distinctSplitExactMatches(source Graph, index *store.DistinctStringIndex, plan *indexedDistinctPlan) (map[int]map[int32]bool, bool) {
	atoms := make([]distinctStringAtom, 0, len(plan.atoms))
	for _, atom := range plan.atoms {
		if _, callsite := distinctCallSiteProperties[atom.property]; callsite {
			atoms = append(atoms, atom)
		}
	}
	if len(atoms) == 0 {
		return nil, false
	}
	spec := &mainStringSourceSpec{atoms: atoms, sourceCount: plan.sourceCount, lazyMain: true}
	var sets []map[int32]bool
	var supported bool
	if index.MainMappedCapability() {
		sets, supported = e.mainExactMatches(source, index, spec)
	} else {
		sets, supported = e.mainRetainedExactMatches(source, index, spec)
	}
	if !supported {
		return nil, false
	}
	return distinctExactByProperty(atoms, sets), true
}

// Preserve ordered matching calls above, then combine the OR sets by property.
// There is no property-independent union or extra retained predicate cache.
func distinctExactByProperty(atoms []distinctStringAtom, sets []map[int32]bool) map[int]map[int32]bool {
	exact := make(map[int]map[int32]bool)
	for i, atom := range atoms {
		property := distinctCallSiteProperties[atom.property]
		ids := exact[property]
		if ids == nil {
			ids = make(map[int32]bool)
			exact[property] = ids
		}
		for sid := range sets[i] {
			ids[sid] = true
		}
	}
	return exact
}

// Selection probes share string lookup and membership results only within this
// invocation. Property membership reads the directory, never its node postings.
func (e evaluator) distinctSplitSelectedIDs(source Graph, index *store.DistinctStringIndex, plan *indexedDistinctPlan, targets []map[string]any, filter bool) map[string]bool {
	selectedIDs := make(map[string]bool)
	stringIDs := make(map[string]int32)
	membership := make(map[uint64]bool)
	for _, target := range targets {
		values := make([]any, len(plan.properties))
		valid := true
		for i, property := range plan.properties {
			value := target[plan.columns[i]]
			p, raw := distinctCallSiteProperties[property]
			if !raw {
				values[i] = int32(-1)
				if property == "graphId" {
					valid = value == source.ID
				} else {
					valid = value == nil
				}
				if !valid {
					break
				}
				continue
			}
			text, ok := value.(string)
			if !ok {
				valid = false
				break
			}
			lookupKey := ordinaryJavaKeyString(text)
			sid, cached := stringIDs[lookupKey]
			if !cached {
				sid = e.distinctStringTableID(source.Store.Strings, text)
				stringIDs[lookupKey] = sid
			}
			if sid < 0 {
				valid = false
				break
			}
			if filter {
				membershipKey := uint64(p)<<32 | uint64(uint32(sid))
				present, cached := membership[membershipKey]
				if !cached {
					var err error
					present, err = index.MainDistinctContainsPropertyStringID(store.CallSiteStringProperty(p), sid, e.storeWorkConsumer())
					failProjectionRead(err)
					membership[membershipKey] = present
				}
				if !present {
					valid = false
					break
				}
			}
			values[i] = sid
		}
		if valid {
			selectedIDs[key(values)] = true
		}
	}
	return selectedIDs
}
