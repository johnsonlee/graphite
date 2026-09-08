package store

import "context"

// PersistedIndexSnapshot observes retained-index state without preparing an
// index, accessing an LRU entry, or changing its retention preference.
type PersistedIndexSnapshot struct {
	RetainPreference             bool  `json:"retainPreference"`
	LoadedFromPersistence        bool  `json:"loadedFromPersistence"`
	PersistenceBudgetDenied      *bool `json:"persistenceBudgetDenied"`
	StringIdentityCached         bool  `json:"stringIdentityCached"`
	MappedViewUnavailable        bool  `json:"mappedViewUnavailable"`
	MatchingStringIDsCount       *int  `json:"matchingStringIdsCount"`
	MatchingNodeIDsCount         *int  `json:"matchingNodeIdsCount"`
	ProjectedRowsCount           *int  `json:"projectedRowsCount"`
	TrigramMetadataReady         *bool `json:"trigramMetadataReady"`
	TrigramPostingsReady         *bool `json:"trigramPostingsReady"`
	TrigramMetadataArraysPresent *bool `json:"trigramMetadataArraysPresent"`
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
		RetainPreference:      s.distinctProjection.retainPersisted,
		StringIdentityCached:  s.callSiteIndex.semanticStringIdentityReady,
		MappedViewUnavailable: s.callSiteIndex.main[1].unavailable,
	}
	// The native Store does not implement main's global reservation budget.
	// Its denial state is unavailable, represented by nil rather than false.
	if index := s.distinctProjection.index; index != nil {
		state.LoadedFromPersistence = index.view != nil
		metadataReady, postingsReady, arraysPresent := index.view != nil, index.view != nil, false
		if index.ordinary != nil {
			metadataReady = metadataReady || index.ordinary.trigramMetadataReady
			postingsReady = postingsReady || index.ordinary.trigramsReady && index.ordinary.trigrams != nil
			arraysPresent = index.ordinary.trigramPostingCounts != nil && index.ordinary.trigramStringIDs != nil
		}
		state.TrigramMetadataReady = &metadataReady
		state.TrigramPostingsReady = &postingsReady
		state.TrigramMetadataArraysPresent = &arraysPresent
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
