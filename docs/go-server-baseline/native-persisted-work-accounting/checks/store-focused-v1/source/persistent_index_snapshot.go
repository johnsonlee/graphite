package store

import "context"

// PersistedIndexSnapshot observes retained-index state without preparing an
// index, accessing an LRU entry, or changing its retention preference.
type PersistedIndexSnapshot struct {
	RetainPreference        bool  `json:"retainPreference"`
	LoadedFromPersistence   bool  `json:"loadedFromPersistence"`
	PersistenceBudgetDenied *bool `json:"persistenceBudgetDenied"`
	StringIdentityCached    bool  `json:"stringIdentityCached"`
	MatchingStringIDsCount  *int  `json:"matchingStringIdsCount"`
	MatchingNodeIDsCount    *int  `json:"matchingNodeIdsCount"`
	ProjectedRowsCount      *int  `json:"projectedRowsCount"`
}

func (s *Store) PersistedIndexState(ctx context.Context) (PersistedIndexSnapshot, error) {
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	if s.callSiteIndex.closed {
		return PersistedIndexSnapshot{}, ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return PersistedIndexSnapshot{}, err
	}
	state := PersistedIndexSnapshot{
		RetainPreference:     s.distinctProjection.retainPersisted,
		StringIdentityCached: s.callSiteIndex.semanticStringIdentityReady,
	}
	// The native Store does not implement main's global reservation budget.
	// Its denial state is unavailable, represented by nil rather than false.
	if index := s.distinctProjection.index; index != nil {
		state.LoadedFromPersistence = index.view != nil
		counts := [3]int{}
		if index.ordinary != nil {
			for kind := range counts {
				counts[kind] = len(index.ordinary.caches[kind].entries)
			}
		}
		state.MatchingStringIDsCount = &counts[0]
		state.MatchingNodeIDsCount = &counts[1]
		state.ProjectedRowsCount = &counts[2]
	}
	return state, nil
}
