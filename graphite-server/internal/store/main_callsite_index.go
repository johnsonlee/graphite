package store

import (
	"context"
	"encoding/binary"
)

type callSiteIndexPolicy uint8

const (
	strictCandidateIndex callSiteIndexPolicy = iota
	mainRetainedIndex
	mainMappedIndex
)

// Separate publication and rejection states: A6's strict source certificate
// must not decide whether main's retained or mapped representation can load.
// All three representations share the Store lifetime lock, never a query Close.
type mainCallSiteIndexState struct {
	loading     chan struct{}
	view        *CallSiteStringIndex
	unavailable bool
}

func (s *Store) tryMainCallSiteStringIndex(ctx context.Context, mapped bool) (*CallSiteStringIndex, bool, error) {
	if err := s.prepareProjectionOffsets(ctx); err != nil {
		return nil, false, err
	}
	slot, policy := 0, mainRetainedIndex
	if mapped {
		slot, policy = 1, mainMappedIndex
	}
	st := &s.callSiteIndex
	for {
		if err := ctx.Err(); err != nil {
			return nil, false, err
		}
		st.mu.Lock()
		if st.closed {
			st.mu.Unlock()
			return nil, false, ErrStoreClosed
		}
		if err := ctx.Err(); err != nil {
			st.mu.Unlock()
			return nil, false, err
		}
		state := &st.main[slot]
		if state.view != nil {
			v := state.view
			st.mu.Unlock()
			return v, true, nil
		}
		if state.unavailable {
			st.mu.Unlock()
			return nil, false, nil
		}
		if pending := state.loading; pending != nil {
			st.mu.Unlock()
			select {
			case <-ctx.Done():
				return nil, false, ctx.Err()
			case <-pending:
			}
			continue
		}
		if st.closing == nil {
			st.closing = make(chan struct{})
		}
		capacity := int32(0)
		if data := s.distinctProjection.offsets; data != nil {
			capacity = int32(binary.BigEndian.Uint32(data[4:8]))
		} else {
			for id := range s.locations {
				if id >= capacity {
					capacity = id + 1
				}
			}
		}
		done := make(chan struct{})
		state.loading = done
		closing := st.closing
		st.mu.Unlock()
		v, _, cache, err := s.loadCallSiteStringIndexWithPolicy(ctx, closing, policy, capacity)
		st.mu.Lock()
		if st.closed {
			err = ErrStoreClosed
		} else if ctx.Err() != nil {
			err = ctx.Err()
		}
		if err == nil {
			if v != nil {
				state.view = v
			} else if mapped {
				state.unavailable = cache
			}
		}
		if err != nil && v != nil {
			_ = unmapNodeData(v.data)
			v = nil
		}
		state.loading = nil
		close(done)
		st.mu.Unlock()
		return v, v != nil, err
	}
}
