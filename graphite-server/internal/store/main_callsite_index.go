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
	return s.tryMainCallSiteStringIndexWithWork(ctx, mapped, nil)
}

func (s *Store) tryMainCallSiteStringIndexWithWork(ctx context.Context, mapped bool, consumeWork func(int64) error) (*CallSiteStringIndex, bool, error) {
	if isMainReaderEntry(ctx) && mapped {
		prepared, err := s.MainPreparedProjectionFile()
		if err != nil || !prepared {
			return nil, false, err
		}
	}
	metadataCtx := mainEntryMetadataContext(ctx)
	if err := s.prepareProjectionOffsets(metadataCtx); err != nil {
		return nil, false, err
	}
	slot := 0
	if mapped {
		slot = 1
	}
	st := &s.callSiteIndex
	for {
		if err := metadataCtx.Err(); err != nil {
			return nil, false, err
		}
		st.mu.Lock()
		if st.closed {
			st.mu.Unlock()
			return nil, false, ErrStoreClosed
		}
		if err := metadataCtx.Err(); err != nil {
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
			var closing <-chan struct{}
			if isMainReaderEntry(ctx) {
				closing = st.closing
			}
			st.mu.Unlock()
			select {
			case <-metadataCtx.Done():
				return nil, false, metadataCtx.Err()
			case <-closing:
				return nil, false, ErrStoreClosed
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
		return func() (v *CallSiteStringIndex, available bool, err error) {
			cache, completed := false, false
			// Even an unexpected panic must release the preparation ticket. The
			// loader owns mapping cleanup until it returns a complete view.
			defer func() {
				st.mu.Lock()
				defer st.mu.Unlock()
				if err == nil {
					if st.closed {
						err = ErrStoreClosed
					} else if metadataCtx.Err() != nil {
						err = metadataCtx.Err()
					}
				}
				if completed && err == nil {
					if v != nil {
						state.view = v
					} else if mapped {
						state.unavailable = cache
					}
				} else if v != nil {
					_ = unmapNodeData(v.data)
					v = nil
				}
				available = v != nil
				state.loading = nil
				close(done)
			}()
			if mapped {
				v, cache, err = s.loadMainMappedIndex(ctx, closing, capacity, consumeWork)
			} else {
				v, err = s.loadMainRetainedIndex(ctx, closing, capacity, consumeWork)
			}
			completed = true
			return v, v != nil, err
		}()
	}
}
