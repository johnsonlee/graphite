package store

import (
	"context"
	"fmt"
)

// Register work under the lifetime lock, but execute it outside that lock.
// The registry includes old index generations still held by a running query.
func (s *Store) beginProjectionWork(ctx context.Context, slot *chan struct{}) (func(), error) {
	st := &s.callSiteIndex
	for {
		st.mu.Lock()
		if st.closed {
			st.mu.Unlock()
			return nil, ErrStoreClosed
		}
		if err := ctx.Err(); err != nil {
			st.mu.Unlock()
			return nil, err
		}
		if pending := *slot; pending != nil {
			closing := st.closing
			st.mu.Unlock()
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-closing:
				return nil, ErrStoreClosed
			case <-pending:
			}
			continue
		}
		if st.closing == nil {
			st.closing = make(chan struct{})
		}
		done := make(chan struct{})
		*slot = done
		if st.projectionWorkOwners == nil {
			st.projectionWorkOwners = map[chan struct{}]struct{}{}
		}
		st.projectionWorkOwners[done] = struct{}{}
		st.mu.Unlock()
		return func() { st.mu.Lock(); *slot = nil; delete(st.projectionWorkOwners, done); close(done); st.mu.Unlock() }, nil
	}
}

// Caller holds the lifetime lock. Snapshot before unlocking for a join.
func (s *Store) projectionPendingLocked() []chan struct{} {
	st := &s.callSiteIndex
	pending := []chan struct{}{st.loading, st.main[0].loading, st.main[1].loading, st.semanticStringIdentityLoading}
	for done := range st.projectionWorkOwners {
		pending = append(pending, done)
	}
	return pending
}

func (s *Store) forEachMainProjectionNode(ctx context.Context, consume func(int64) error, visit func(int32, [4]int32) error) (err error) {
	s.callSiteIndex.mu.RLock()
	closing := s.callSiteIndex.closing
	s.callSiteIndex.mu.RUnlock()
	work := persistentReadWork{ctx: ctx, closing: closing, consumer: consume}
	defer func() {
		if rejected := work.flush(); rejected != nil {
			err = rejected
		}
	}()
	for _, id := range s.byKind["CallSiteNode"] {
		if err = work.consume(); err != nil {
			return
		}
		var ids [4]int32
		ids, err = s.ProjectionStringIDs(ctx, id)
		if err != nil {
			return
		}
		if err = visit(id, ids); err != nil {
			return
		}
	}
	return nil
}

func (s *Store) buildMainProjectionIndex(ctx context.Context, index *DistinctStringIndex, consume func(int64) error) error {
	var counts [4][]int32
	for p := range counts {
		counts[p] = make([]int32, len(s.Strings))
	}
	if err := s.forEachMainProjectionNode(ctx, consume, func(_ int32, ids [4]int32) error {
		for p, sid := range ids {
			if sid < 0 || int64(sid) >= int64(len(s.Strings)) {
				message := fmt.Sprintf("Index %d out of bounds for length %d", sid, len(s.Strings))
				return &ProjectionReadError{Class: "ArrayIndexOutOfBoundsException", Message: &message}
			}
			counts[p][sid]++
		}
		return nil
	}); err != nil {
		return err
	}
	for p := range counts {
		index.entries[p] = map[int32][]int32{}
		for sid, n := range counts[p] {
			if n > 0 {
				index.entries[p][int32(sid)] = make([]int32, 0, int(n))
			}
		}
	}
	// Re-read raw fields in a second pass. This is separate work and a separate
	// finally boundary; a rejected first pass never starts filling postings.
	return s.forEachMainProjectionNode(ctx, consume, func(id int32, ids [4]int32) error {
		for p, sid := range ids {
			if sid < 0 || int64(sid) >= int64(len(s.Strings)) {
				message := fmt.Sprintf("Index %d out of bounds for length %d", sid, len(s.Strings))
				return &ProjectionReadError{Class: "ArrayIndexOutOfBoundsException", Message: &message}
			}
			index.entries[p][sid] = append(index.entries[p][sid], id)
		}
		return nil
	})
}
