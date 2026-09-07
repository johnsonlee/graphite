package store

import "reflect"

// ProfileProjectionState reads current retained state only. This diagnostic
// helper is injected into isolated profile binaries, never shipping builds.
// Reflection lets the exact same helper compile before and after the tuple
// feature; it neither creates a table nor changes query/cache state.
func (s *Store) ProfileProjectionState() map[string]any {
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	out := map[string]any{"retainedIndex": s.distinctProjection.index != nil, "mappedView": s.distinctProjection.mappedView != nil, "tupleCapacity": 0}
	i := s.distinctProjection.index
	if i == nil || i.ordinary == nil {
		return out
	}
	table := reflect.ValueOf(i.ordinary).Elem().FieldByName("exactTuple")
	if !table.IsValid() || table.IsNil() {
		return out
	}
	nodes := table.Elem().FieldByName("nodes")
	hashes := table.Elem().FieldByName("hashes")
	if !nodes.IsValid() || !hashes.IsValid() || nodes.Len() != hashes.Len() {
		panic("unexpected actual tuple representation")
	}
	out["tupleCapacity"] = nodes.Len()
	return out
}
