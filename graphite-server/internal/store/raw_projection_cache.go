package store

import "context"

// Raw projection matches belong to the graph, independently of retained string
// indexes. Keys encode ordered predicates and limit; values are node IDs so a
// later request can project different columns. Entries run from oldest to newest.
type rawProjectionMatch struct {
	key string
	ids []int32
}

func (s *Store) RawProjectionMatches(ctx context.Context, key string) ([]int32, bool, error) {
	st := &s.callSiteIndex
	st.mu.Lock()
	defer st.mu.Unlock()
	if st.closed {
		return nil, false, ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return nil, false, err
	}
	for i, entry := range st.rawProjection {
		if entry.key == key {
			copy(st.rawProjection[i:], st.rawProjection[i+1:])
			st.rawProjection[len(st.rawProjection)-1] = entry
			return append([]int32(nil), entry.ids...), true, nil
		}
	}
	return nil, false, nil
}

// CacheRawProjectionMatches publishes only completed probes. Like main, a
// duplicate put neither replaces the existing value nor changes its LRU order.
func (s *Store) CacheRawProjectionMatches(ctx context.Context, key string, ids []int32) error {
	st := &s.callSiteIndex
	st.mu.Lock()
	defer st.mu.Unlock()
	if st.closed {
		return ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	for _, entry := range st.rawProjection {
		if entry.key == key {
			return nil
		}
	}
	entry := rawProjectionMatch{key: key, ids: append([]int32(nil), ids...)}
	if len(st.rawProjection) == 16 {
		copy(st.rawProjection, st.rawProjection[1:])
		st.rawProjection[15] = entry
	} else {
		st.rawProjection = append(st.rawProjection, entry)
	}
	return nil
}
