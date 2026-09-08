package store

import "context"

type MainDistinctIndexRepresentation uint8

const (
	MainDistinctIndexNone MainDistinctIndexRepresentation = iota
	MainDistinctIndexRetained
	MainDistinctIndexMapped
)

type mainDistinctEntryKey struct{}

// PrepareMainDistinctSplitIndex implements only main's initial Split index
// selection. A declined optional reader returns None; raw projection and the
// later load/build fallback belong to the caller. In particular, this method
// never builds an index or validates postings beyond the selected reader.
func (s *Store) PrepareMainDistinctSplitIndex(ctx context.Context, preferMapped bool, consumeWork func(int64) error) (*DistinctStringIndex, MainDistinctIndexRepresentation, error) {
	ctx = context.WithValue(ctx, mainDistinctEntryKey{}, true)
	s.callSiteIndex.mu.Lock()
	if s.callSiteIndex.closed {
		s.callSiteIndex.mu.Unlock()
		return nil, MainDistinctIndexNone, ErrStoreClosed
	}
	if !preferMapped {
		// Main sets this before examining the existing retained index.
		s.distinctProjection.retainPersisted = true
	}
	s.callSiteIndex.mu.Unlock()
	if index, ok, err := s.MainRetainedProjectionIndex(); err != nil || ok {
		if err != nil {
			return nil, MainDistinctIndexNone, err
		}
		return index, MainDistinctIndexRetained, nil
	}
	if s.Mode != "MAPPED" {
		return nil, MainDistinctIndexNone, nil
	}
	// The retained owner is shared with ordinary load/build preparation, so a
	// completed reader and its matching-cache wrapper are published together.
	if !preferMapped {
		finish, err := s.beginProjectionWork(mainEntryMetadataContext(ctx), &s.callSiteIndex.projectionPreparing)
		if err != nil {
			return nil, MainDistinctIndexNone, err
		}
		defer finish()
		if index, ok, err := s.MainRetainedProjectionIndex(); err != nil || ok {
			if err != nil {
				return nil, MainDistinctIndexNone, err
			}
			return index, MainDistinctIndexRetained, nil
		}
	}
	// Both optional entry points gate file presence before metadata/identity.
	// The mapped reader repeats its own gate before its cached-view lookup.
	present, err := s.MainPreparedProjectionFile()
	if err != nil || !present {
		return nil, MainDistinctIndexNone, err
	}
	s.callSiteIndex.mu.Lock()
	if s.callSiteIndex.closed {
		s.callSiteIndex.mu.Unlock()
		return nil, MainDistinctIndexNone, ErrStoreClosed
	}
	if preferMapped && s.distinctProjection.mappedView != nil {
		copy := *s.distinctProjection.mappedView
		copy.Raw, copy.ParallelRaw = false, false
		s.callSiteIndex.mu.Unlock()
		return &copy, MainDistinctIndexMapped, nil
	}
	if len(s.byKind["CallSiteNode"]) == 0 {
		if preferMapped {
			s.callSiteIndex.main[1].unavailable = true
		}
		s.callSiteIndex.mu.Unlock()
		return nil, MainDistinctIndexNone, nil
	}
	s.callSiteIndex.mu.Unlock()
	view, available, err := s.tryMainCallSiteStringIndexWithWork(ctx, preferMapped, consumeWork)
	if err != nil || !available {
		return nil, MainDistinctIndexNone, err
	}
	s.callSiteIndex.mu.Lock()
	defer s.callSiteIndex.mu.Unlock()
	if s.callSiteIndex.closed {
		return nil, MainDistinctIndexNone, ErrStoreClosed
	}
	slot := &s.distinctProjection.index
	representation := MainDistinctIndexRetained
	if preferMapped {
		slot = &s.distinctProjection.mappedView
		representation = MainDistinctIndexMapped
	}
	if *slot == nil {
		*slot = &DistinctStringIndex{owner: s, view: view, ordinary: &ordinaryIndexState{}}
	}
	copy := **slot
	copy.Raw, copy.ParallelRaw = false, false
	return &copy, representation, nil
}

// MainDistinctRawIndex creates a request handle only: no file read, index
// initialization, cache publication, or work consumption is performed.
func (s *Store) MainDistinctRawIndex(parallel bool) (*DistinctStringIndex, error) {
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	if s.callSiteIndex.closed {
		return nil, ErrStoreClosed
	}
	return &DistinctStringIndex{owner: s, Raw: true, ParallelRaw: parallel}, nil
}

// MainDistinctProjectionStringIDs keeps the raw four-field decoder's read
// order. The Split worker owns interruption checkpoints; this accessor checks
// Store lifetime only, including offset preparation.
func (s *Store) MainDistinctProjectionStringIDs(id int32) ([4]int32, error) {
	if err := s.prepareProjectionOffsets(nil); err != nil {
		return [4]int32{}, err
	}
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	if s.callSiteIndex.closed {
		return [4]int32{}, ErrStoreClosed
	}
	return s.projectionStringIDsLocked(id)
}
