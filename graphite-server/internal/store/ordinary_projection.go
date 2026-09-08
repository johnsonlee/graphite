package store

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"sort"
)

// RetainedProjectionIndex observes completed initialization without performing
// any file or index load. An A6 reader view is not this retained capability.
func (s *Store) RetainedProjectionIndex(ctx context.Context) (*DistinctStringIndex, bool, error) {
	return s.retainedProjectionIndex(ctx)
}

// A nil worker context selects the main lifetime-only metadata getter.
func (s *Store) retainedProjectionIndex(ctx context.Context) (*DistinctStringIndex, bool, error) {
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	if s.callSiteIndex.closed {
		return nil, false, ErrStoreClosed
	}
	if ctx != nil {
		if err := ctx.Err(); err != nil {
			return nil, false, err
		}
	}
	index := s.distinctProjection.index
	if index == nil {
		return nil, false, nil
	}
	copy := *index
	copy.Raw = false
	copy.ParallelRaw = false
	return &copy, true, nil
}
func (s *Store) InitializedProjectionView(ctx context.Context) (*DistinctStringIndex, bool, error) {
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	if s.callSiteIndex.closed {
		return nil, false, ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return nil, false, err
	}
	index := s.distinctProjection.mappedView
	if index == nil {
		return nil, false, nil
	}
	copy := *index
	copy.Raw = false
	copy.ParallelRaw = false
	return &copy, true, nil
}
func (s *Store) PreparedProjectionFile(ctx context.Context) (bool, error) {
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	if s.callSiteIndex.closed {
		return false, ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return false, err
	}
	info, err := os.Stat(filepath.Join(s.dir, callSiteIndexFile))
	if os.IsNotExist(err) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return info.Mode().IsRegular(), nil
}
func (i *DistinctStringIndex) PrefersSerialProjectionScan(ctx context.Context) (bool, error) {
	bytes, err := i.ProjectionPlannerBytes(ctx)
	return bytes <= 1024*1024, err
}

// ProjectionRecord captures only the four SID integers consumed by the raw
// candidate scan. Publishing a complete scan never reopens a prepared sidecar.
type ProjectionRecord struct {
	NodeID    int32
	StringIDs [4]int32
}

// PublishProjectionScan performs main's optional in-memory handoff after every
// raw range was fully consumed. It deliberately does not persist a sidecar.
// A caller may ignore a build failure, but must propagate cancellation/close.
func (s *Store) PublishProjectionScan(ctx context.Context, records []ProjectionRecord) error {
	s.callSiteIndex.mu.Lock()
	defer s.callSiteIndex.mu.Unlock()
	if s.callSiteIndex.closed {
		return ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	if s.distinctProjection.index != nil {
		return nil
	}
	if len(records) != len(s.byKind["CallSiteNode"]) {
		return fmt.Errorf("incomplete raw projection scan")
	}
	index := &DistinctStringIndex{owner: s, ordinary: &ordinaryIndexState{}}
	for p := range index.entries {
		index.entries[p] = map[int32][]int32{}
	}
	for _, record := range records {
		if err := ctx.Err(); err != nil {
			return err
		}
		for p, sid := range record.StringIDs {
			if sid < 0 || int64(sid) >= int64(len(s.Strings)) {
				message := fmt.Sprintf("Index %d out of bounds for length %d", sid, len(s.Strings))
				return &ProjectionReadError{Class: "ArrayIndexOutOfBoundsException", Message: &message}
			}
			index.entries[p][sid] = append(index.entries[p][sid], record.NodeID)
		}
	}
	s.distinctProjection.index = index
	return nil
}

// ProjectionArrayString preserves the ByteArray lookup before StringTable.get
// in the parallel raw matcher. Serial matchers use ProjectionString instead.
func (s *Store) ProjectionArrayString(ctx context.Context, id int32) (string, error) {
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	if s.callSiteIndex.closed {
		return "", ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return "", err
	}
	if id < 0 || int64(id) >= int64(len(s.Strings)) {
		message := fmt.Sprintf("Index %d out of bounds for length %d", id, len(s.Strings))
		return "", &ProjectionReadError{Class: "ArrayIndexOutOfBoundsException", Message: &message}
	}
	return s.Strings[id], nil
}

func (i *DistinctStringIndex) ProjectionTrigramStrings(ctx context.Context, hash int32) ([]int32, error) {
	if i.view != nil {
		return i.view.TrigramStringIDs(ctx, hash)
	}
	s := i.owner
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	if s.callSiteIndex.closed {
		return nil, ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if i.ordinary == nil || !i.ordinary.trigramsReady {
		return nil, nil
	}
	postings := i.ordinary.trigrams
	start := sort.Search(len(postings), func(j int) bool { return int32(postings[j]>>32) >= hash })
	ids := []int32{}
	for at := start; at < len(postings) && int32(postings[at]>>32) == hash; at++ {
		ids = append(ids, int32(postings[at]))
	}
	return ids, nil
}
func (i *DistinctStringIndex) HasProjectionTrigrams(ctx context.Context) (bool, error) {
	s := i.owner
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	if s.callSiteIndex.closed {
		return false, ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return false, err
	}
	return i.view != nil || i.ordinary != nil && i.ordinary.trigramsReady && len(i.ordinary.trigrams) > 0, nil
}
