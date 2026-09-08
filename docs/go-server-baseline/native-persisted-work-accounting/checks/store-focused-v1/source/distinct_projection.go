package store

import (
	"context"
	"encoding/binary"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"sort"
)

// ProjectionReadError is a Java-compatible failure of a selectively consumed
// mapped position/string, not a full-node decoder error. Nil Message represents
// ByteBuffer's IndexOutOfBoundsException without a detail message.
type ProjectionReadError struct {
	Message *string
	Class   string
}

func (e *ProjectionReadError) Error() string {
	if e.Message == nil {
		return "Query execution failed"
	}
	return *e.Message
}
func projectionStringError(id int32, size int) error {
	var message string
	if id < 0 {
		message = fmt.Sprintf("Index (%d) is negative", id)
	} else {
		message = fmt.Sprintf("Index (%d) is greater than or equal to list size (%d)", id, size)
	}
	return &ProjectionReadError{Message: &message}
}

// Protected by callSiteIndex.mu; Close shares this lifetime lock. The retained
// marker belongs to DISTINCT's initialization, not A6's full-node certificate.
type distinctProjectionState struct {
	offsetsLoaded   bool
	offsets         []byte
	index           *DistinctStringIndex
	mappedView      *DistinctStringIndex
	retainPersisted bool
}
type DistinctStringIndex struct {
	owner       *Store
	view        *CallSiteStringIndex
	entries     [4]map[int32][]int32
	Raw         bool
	ParallelRaw bool
	ordinary    *ordinaryIndexState
}

func (s *Store) prepareProjectionOffsets(ctx context.Context) error {
	s.callSiteIndex.mu.Lock()
	defer s.callSiteIndex.mu.Unlock()
	if s.callSiteIndex.closed {
		return ErrStoreClosed
	}
	select {
	case <-ctx.Done():
		if err := ctx.Err(); err != nil {
			return err
		}
	default:
	}
	if s.distinctProjection.offsetsLoaded {
		return nil
	}
	data, err := os.ReadFile(filepath.Join(s.dir, "graph.nodeoffsets"))
	if err != nil && !os.IsNotExist(err) {
		return err
	}
	if err == nil && len(data) < 8 {
		return &ProjectionReadError{}
	}
	select {
	case <-ctx.Done():
		if err := ctx.Err(); err != nil {
			return err
		}
	default:
	}
	s.distinctProjection.offsets = data
	s.distinctProjection.offsetsLoaded = true
	return nil
}
func (s *Store) projectionOffsetLocked(id int32) (int64, error) {
	if data := s.distinctProjection.offsets; data != nil {
		at := int64(8) + int64(id)*8
		if at < 0 || at > int64(len(data))-8 {
			return 0, &ProjectionReadError{}
		}
		stored := int64(binary.BigEndian.Uint64(data[at : at+8]))
		if stored == 0 {
			return -1, nil
		}
		return stored - 1, nil
	}
	loc, ok := s.locations[id]
	if !ok {
		return 0, &ProjectionReadError{}
	}
	return loc.offset, nil
}

// ProjectionNodeOrder is main's mapped offset encounter order. It does not read
// the node header or validate unrelated fields.
func (s *Store) ProjectionNodeOrder(ctx context.Context, id int32) (int64, error) {
	if err := s.prepareProjectionOffsets(ctx); err != nil {
		return 0, err
	}
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	if s.callSiteIndex.closed {
		return 0, ErrStoreClosed
	}
	select {
	case <-ctx.Done():
		if err := ctx.Err(); err != nil {
			return 0, err
		}
	default:
	}
	return s.projectionOffsetLocked(id)
}

// ProjectionStringID consumes precisely rawCallSiteStringPropertyId's fields.
// Preserve Java int32 arithmetic: negative/overflowing parameter counts are not
// errors until the resulting required ByteBuffer read is outside its bounds.
func (s *Store) ProjectionStringID(ctx context.Context, id int32, property CallSiteStringProperty) (int32, error) {
	if err := s.prepareProjectionOffsets(ctx); err != nil {
		return 0, err
	}
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	if s.callSiteIndex.closed {
		return 0, ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return 0, err
	}
	return s.projectionStringIDLocked(id, property)
}

// Caller holds the Store lifetime lock.
func (s *Store) projectionStringIDLocked(id int32, property CallSiteStringProperty) (int32, error) {
	offset, err := s.projectionOffsetLocked(id)
	if err != nil {
		return 0, err
	}
	read := func(at int32) (int32, error) {
		if at < 0 || int64(at) > int64(len(s.mappedData))-4 {
			return 0, &ProjectionReadError{}
		}
		return int32(binary.BigEndian.Uint32(s.mappedData[int(at) : int(at)+4])), nil
	}
	fields := int32(offset) + 5
	switch property {
	case CallerClass:
		return read(fields)
	case CallerName:
		return read(fields + 4)
	case CalleeClass, CalleeName:
		count, err := read(fields + 8)
		if err != nil {
			return 0, err
		}
		at := fields + (int32(4)+count)*4
		if property == CalleeName {
			at += 4
		}
		return read(at)
	default:
		return 0, fmt.Errorf("unknown CallSite string property %d", property)
	}
}

// ProjectionStringIDs mirrors withRawCallSiteStringIds: derive the callee
// address first, then read all four ints before invoking any SID consumer.
func (s *Store) ProjectionStringIDs(ctx context.Context, id int32) ([4]int32, error) {
	var out [4]int32
	if err := s.prepareProjectionOffsets(ctx); err != nil {
		return out, err
	}
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	if s.callSiteIndex.closed {
		return out, ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return out, err
	}
	offset, err := s.projectionOffsetLocked(id)
	if err != nil {
		return out, err
	}
	read := func(at int32) (int32, error) {
		if at < 0 || int64(at) > int64(len(s.mappedData))-4 {
			return 0, &ProjectionReadError{}
		}
		return int32(binary.BigEndian.Uint32(s.mappedData[int(at) : int(at)+4])), nil
	}
	fields := int32(offset) + 5
	count, err := read(fields + 8)
	if err != nil {
		return out, err
	}
	callee := fields + (4+count)*4
	for i, at := range []int32{fields, fields + 4, callee, callee + 4} {
		out[i], err = read(at)
		if err != nil {
			return out, err
		}
	}
	return out, nil
}

func (s *Store) ProjectionString(ctx context.Context, id int32) (string, error) {
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	if s.callSiteIndex.closed {
		return "", ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return "", err
	}
	if id < 0 || int64(id) >= int64(len(s.Strings)) {
		return "", projectionStringError(id, len(s.Strings))
	}
	return s.Strings[id], nil
}

// RetainedDistinctStringIndex reports a successful initialization only. No
// ordinary query path consumes this marker in this functional change.
func (s *Store) RetainedDistinctStringIndex(ctx context.Context) (bool, error) {
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	if s.callSiteIndex.closed {
		return false, ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return false, err
	}
	return s.distinctProjection.index != nil, nil
}

// DistinctProjectionOptions reflects main's storage work consumer, independently
// of query spelling. CannotMatch is invoked only after no retained index loads.
type DistinctProjectionOptions struct {
	SourceCount, Limit     int
	PreferMappedView       bool
	InitializeMappedView   bool
	SkipPreparedPreference bool
	MainSource             bool
	CannotMatch            func() bool
	// ConsumeWork charges the current retained-index preparation owner. It is
	// never retained on a Store/index or invoked while a lifetime lock is held.
	ConsumeWork func(int64) error
	// A preferred persisted lookup keeps its structural index across a
	// zero-hit DISTINCT release. Explicit benchmark clear resets this policy.
	RetainPersisted bool
}

// PrepareDistinctStringIndex validates only the representation consumed by
// main's index reader. It never invokes Node or CertifyCallSiteCandidates.
func (s *Store) PrepareDistinctStringIndex(ctx context.Context, options DistinctProjectionOptions) (*DistinctStringIndex, bool, error) {
	if s.Mode != "MAPPED" {
		return nil, false, nil
	}
	if err := s.prepareProjectionOffsets(ctx); err != nil {
		return nil, false, err
	}
	s.callSiteIndex.mu.Lock()
	existing := s.distinctProjection.index
	mappedExisting := s.distinctProjection.mappedView
	closed := s.callSiteIndex.closed
	if !closed && ctx.Err() == nil && options.RetainPersisted {
		s.distinctProjection.retainPersisted = true
	}
	s.callSiteIndex.mu.Unlock()
	if closed {
		return nil, false, ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return nil, false, err
	}
	parallelRaw := options.SourceCount >= 40 && len(s.byKind["CallSiteNode"]) >= 4096 && options.Limit < len(s.byKind["CallSiteNode"])
	adapt := func(index *DistinctStringIndex) *DistinctStringIndex {
		copy := *index
		copy.ParallelRaw = parallelRaw
		return &copy
	}
	rawFallback := options.SourceCount > 1 && options.SourceCount < 40 || parallelRaw
	if existing != nil {
		return adapt(existing), true, nil
	}
	if options.InitializeMappedView && mappedExisting != nil {
		return adapt(mappedExisting), true, nil
	}
	// Prepared is a file-presence capability in main, not a successful load.
	// A one-source query chooses serial storage even if this file is rejected.
	if info, err := os.Stat(filepath.Join(s.dir, callSiteIndexFile)); !options.SkipPreparedPreference && options.SourceCount == 1 && err == nil && info.Mode().IsRegular() {
		rawFallback = true
	}
	if len(s.byKind["CallSiteNode"]) == 0 {
		// Main cannot build or map a structural index without CallSites.
		// Its serial raw projection remains an available, empty scan.
		if rawFallback && !options.InitializeMappedView {
			return &DistinctStringIndex{owner: s, Raw: true}, true, nil
		}
		return nil, false, nil
	}
	var view *CallSiteStringIndex
	var available bool
	var err error
	if options.MainSource {
		view, available, err = s.tryMainCallSiteStringIndexWithWork(ctx, options.InitializeMappedView, options.ConsumeWork)
	} else {
		view, available, err = s.TryCallSiteStringIndex(ctx)
	}
	if err != nil {
		return nil, false, err
	}
	if available && !options.MainSource {
		// Main's persisted reader validates each posting against its mapped offset
		// accessor, independently of graph.nodeindex used by the general Go store.
		for property := CallerClass; property <= CalleeName && available; property++ {
			directory, err := view.Directory(ctx, property)
			if err != nil {
				return nil, false, err
			}
			for _, entry := range directory {
				ids, err := view.Postings(ctx, property, entry.StringID)
				if err != nil {
					return nil, false, err
				}
				previous := int64(math.MinInt64)
				for _, id := range ids {
					order, err := s.ProjectionNodeOrder(ctx, id)
					if err != nil {
						return nil, false, err
					}
					if order <= previous {
						available = false
						break
					}
					previous = order
				}
				if !available {
					break
				}
			}
		}
	}
	index := &DistinctStringIndex{owner: s, ordinary: &ordinaryIndexState{}}
	if available {
		index.view = view
	} else {
		if options.InitializeMappedView {
			return nil, false, nil
		}
		if options.CannotMatch != nil && options.CannotMatch() {
			return index, true, nil
		}
		if rawFallback {
			index.Raw = true
			return adapt(index), true, nil
		}
		for p := range index.entries {
			index.entries[p] = map[int32][]int32{}
		}
		for _, id := range s.byKind["CallSiteNode"] {
			sids, err := s.ProjectionStringIDs(ctx, id)
			if err != nil {
				return nil, false, err
			}
			for p := CallerClass; p <= CalleeName; p++ {
				sid := sids[p]
				if sid < 0 || int64(sid) >= int64(len(s.Strings)) {
					message := fmt.Sprintf("Index %d out of bounds for length %d", sid, len(s.Strings))
					return nil, false, &ProjectionReadError{Class: "ArrayIndexOutOfBoundsException", Message: &message}
				}
				index.entries[p][sid] = append(index.entries[p][sid], id)
			}
		}
	}
	if available && options.InitializeMappedView {
		s.callSiteIndex.mu.Lock()
		defer s.callSiteIndex.mu.Unlock()
		if s.callSiteIndex.closed {
			return nil, false, ErrStoreClosed
		}
		if err := ctx.Err(); err != nil {
			return nil, false, err
		}
		if s.distinctProjection.mappedView == nil {
			s.distinctProjection.mappedView = index
		}
		return adapt(s.distinctProjection.mappedView), true, nil
	}
	// Preferred mapped views support split projection without making the retained
	// index capability visible to subsequent ordinary projection requests.
	if available && options.PreferMappedView && options.SourceCount >= 40 {
		s.callSiteIndex.mu.Lock()
		if s.callSiteIndex.closed {
			s.callSiteIndex.mu.Unlock()
			return nil, false, ErrStoreClosed
		}
		if err := ctx.Err(); err != nil {
			s.callSiteIndex.mu.Unlock()
			return nil, false, err
		}
		// Query preparation may load a separate retained reader, but it must not
		// replace the initialized mapped view or erase its range certificates.
		if s.distinctProjection.mappedView == nil {
			s.distinctProjection.mappedView = index
		}
		s.callSiteIndex.mu.Unlock()
		if parallelRaw {
			return adapt(index), true, nil
		}
	}
	s.callSiteIndex.mu.Lock()
	defer s.callSiteIndex.mu.Unlock()
	if s.callSiteIndex.closed {
		return nil, false, ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return nil, false, err
	}
	if s.distinctProjection.index == nil {
		s.distinctProjection.index = index
	}
	return adapt(s.distinctProjection.index), true, nil
}
func (i *DistinctStringIndex) Directory(ctx context.Context, p CallSiteStringProperty) ([]CallSiteStringDirectoryEntry, error) {
	if i.view != nil {
		return i.view.Directory(ctx, p)
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
	if p > CalleeName {
		return nil, fmt.Errorf("unknown CallSite property %d", p)
	}
	out := make([]CallSiteStringDirectoryEntry, 0, len(i.entries[p]))
	for sid, ids := range i.entries[p] {
		out = append(out, CallSiteStringDirectoryEntry{sid, int32(len(ids))})
	}
	sort.Slice(out, func(a, b int) bool { return out[a].StringID < out[b].StringID })
	return out, nil
}
func (i *DistinctStringIndex) Postings(ctx context.Context, p CallSiteStringProperty, sid int32) ([]int32, error) {
	if i.view != nil {
		return i.view.Postings(ctx, p, sid)
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
	if p > CalleeName {
		return nil, fmt.Errorf("unknown CallSite property %d", p)
	}
	return append([]int32(nil), i.entries[p][sid]...), nil
}

// ReleaseDistinctStringIndex releases request caches after a zero-hit merge.
// Main retains preferred persisted indexes and newly built trigram indexes;
// other indexes lose their retained marker. Immutable handles remain valid
// until Store.Close or the explicit benchmark clear boundary.
func (s *Store) ReleaseDistinctStringIndex(ctx context.Context) error {
	s.callSiteIndex.mu.Lock()
	defer s.callSiteIndex.mu.Unlock()
	if s.callSiteIndex.closed {
		return ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	index := s.distinctProjection.index
	if index != nil && (index.view != nil && s.distinctProjection.retainPersisted ||
		index.view == nil && index.ordinary != nil && index.ordinary.trigramsReady && len(index.ordinary.trigrams) > 0) {
		if index.ordinary != nil {
			index.ordinary.caches = [3]projectionLRU{}
		}
		return nil
	}
	s.distinctProjection.index = nil
	s.distinctProjection.retainPersisted = false
	return nil
}
