package store

import (
	"context"
	"errors"
)

type OpenOptions struct {
	PrepareCallSiteStringIndex bool
}

// OpenModeWithOptions completes main's optional index preparation and best-effort
// persistence before publishing the graph. Existing OpenMode calls remain lazy.
func OpenModeWithOptions(ctx context.Context, dir, mode string, options OpenOptions) (*Store, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	s, err := OpenMode(dir, mode)
	if err != nil {
		return nil, err
	}
	if options.PrepareCallSiteStringIndex && s.Mode == "MAPPED" {
		if _, err = s.PrepareCallSiteStringIndex(ctx); err == nil {
			s.callSiteIndex.mu.Lock()
			s.persistProjectionOnCloseLocked()
			s.callSiteIndex.mu.Unlock()
		}
	}
	if err == nil {
		err = ctx.Err()
	}
	if err != nil {
		_ = s.Close()
		return nil, err
	}
	return s, nil
}

// PrepareCallSiteStringIndex prepares complete structural postings and trigrams.
// Like main's internal preparation method it does not itself persist the index.
func (s *Store) PrepareCallSiteStringIndex(ctx context.Context) (bool, error) {
	if err := ctx.Err(); err != nil {
		return false, err
	}
	index, ok, err := s.PrepareDistinctStringIndex(ctx, DistinctProjectionOptions{
		SourceCount: 1, Limit: len(s.byKind["CallSiteNode"]),
		MainSource: true, SkipPreparedPreference: true,
	})
	if err != nil || !ok {
		return false, err
	}
	if index.ordinary == nil {
		return false, nil
	}
	if err := index.PrepareProjectionTrigrams(ctx); err != nil {
		return false, err
	}
	return index.HasProjectionTrigrams(ctx)
}

// ClearStringPropertyIndexes is the benchmark invocation boundary corresponding
// to main's internal clear method. The owner must join all query tasks and stop
// using their index handles before calling it, as the main benchmark does.
// Graph/node ownership survives; a built trigram index may be persisted first.
func (s *Store) ClearStringPropertyIndexes(ctx context.Context) error {
	st := &s.callSiteIndex
	for {
		st.mu.Lock()
		if st.closed {
			st.mu.Unlock()
			return ErrStoreClosed
		}
		if err := ctx.Err(); err != nil {
			st.mu.Unlock()
			return err
		}
		// A loader started before this boundary must finish before its publication
		// can be reset. Keep its mutex and completion channel identities intact.
		var pending chan struct{}
		for _, done := range []chan struct{}{st.loading, st.main[0].loading, st.main[1].loading} {
			if done != nil {
				pending = done
				break
			}
		}
		if pending != nil {
			st.mu.Unlock()
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-pending:
			}
			continue
		}
		s.persistProjectionOnCloseLocked()
		if index := s.distinctProjection.index; index != nil && index.ordinary != nil {
			index.ordinary.exactTuple = nil
			index.ordinary.caches = [3]projectionLRU{}
		}
		var err error
		for _, view := range []*CallSiteStringIndex{st.view, st.main[0].view, st.main[1].view} {
			if view != nil {
				err = errors.Join(err, unmapNodeData(view.data))
				view.data = nil
			}
		}
		st.view, st.unavailable, st.reason = nil, false, ""
		st.main = [2]mainCallSiteIndexState{}
		// The mapped node-offset table is graph ownership, not a query index.
		s.distinctProjection.index = nil
		s.distinctProjection.mappedView = nil
		for _, proof := range []*candidateCertificateState{&s.candidateProof, &s.trigramProof} {
			proof.mu.Lock()
			proof.completed, proof.valid = false, false
			proof.mu.Unlock()
		}
		st.mu.Unlock()
		return err
	}
}

type StringPropertyIndexState struct {
	Retained              bool `json:"retained"`
	MappedView            bool `json:"mappedView"`
	Trigrams              bool `json:"trigrams"`
	LoadedFromPersistence bool `json:"loadedFromPersistence"`
	MappedRangeCount      int  `json:"mappedRangeCount"`
}

// StringPropertyIndexes observes initialized structures without loading them.
func (s *Store) StringPropertyIndexes(ctx context.Context) (StringPropertyIndexState, error) {
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	if s.callSiteIndex.closed {
		return StringPropertyIndexState{}, ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return StringPropertyIndexState{}, err
	}
	i := s.distinctProjection.index
	v := s.distinctProjection.mappedView
	state := StringPropertyIndexState{Retained: i != nil, MappedView: v != nil}
	if i != nil {
		state.LoadedFromPersistence = i.view != nil
		state.Trigrams = i.view != nil || i.ordinary != nil && i.ordinary.trigramsReady && len(i.ordinary.trigrams) > 0
	}
	if v != nil && v.view != nil && v.view.mainRanges != nil {
		for _, entry := range v.view.mainRanges.states {
			if entry != 0 {
				state.MappedRangeCount++
			}
		}
	}
	return state, nil
}
