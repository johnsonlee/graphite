package store

// QueryNodeIDs returns the node source order used by the query engine. EAGER
// GraphStore groups nodes by their concrete class in first-encounter order,
// retaining serialized order within each class. NodeIDs remains the persisted
// record order for callers that need that separate contract.
//
// MAPPED main uses a HashMap keyed by JVM Class identities for supertype scans;
// that order cannot be reconstructed from persisted graph bytes. Keep the
// existing persisted order in that mode rather than hardcoding one JVM run.
func (s *Store) QueryNodeIDs() []int32 {
	if s.Mode != "EAGER" {
		return s.NodeIDs()
	}
	ids := make([]int32, 0, len(s.ids))
	seen := make(map[string]bool, len(s.byKind))
	for _, id := range s.ids {
		kind := s.eagerNodes[id].Kind
		if seen[kind] {
			continue
		}
		seen[kind] = true
		ids = append(ids, s.byKind[kind]...)
	}
	return ids
}
