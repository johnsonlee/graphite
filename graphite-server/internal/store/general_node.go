package store

import (
	"context"
	"encoding/binary"
)

// GeneralCandidateNode is the general pipeline's Graph.node lookup. Missing
// IDs and offset sentinels are absent nodes, while present mapped records use
// the original deferred decoder without validating their ID or concrete type
// against graph.nodeindex. Candidate type selection belongs to the caller.
func (s *Store) GeneralCandidateNode(ctx context.Context, id int32) (Node, bool, error) {
	s.callSiteIndex.mu.RLock()
	if s.callSiteIndex.closed {
		s.callSiteIndex.mu.RUnlock()
		return Node{}, false, ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		s.callSiteIndex.mu.RUnlock()
		return Node{}, false, err
	}
	if s.Mode == "EAGER" {
		node, present := s.eagerNodes[id]
		s.callSiteIndex.mu.RUnlock()
		return node, present, nil
	}
	s.callSiteIndex.mu.RUnlock()
	if id < 0 {
		return Node{}, false, nil
	}
	if err := s.prepareProjectionOffsets(ctx); err != nil {
		return Node{}, false, err
	}

	s.callSiteIndex.mu.RLock()
	if s.callSiteIndex.closed {
		s.callSiteIndex.mu.RUnlock()
		return Node{}, false, ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		s.callSiteIndex.mu.RUnlock()
		return Node{}, false, err
	}
	present := true
	if offsets := s.distinctProjection.offsets; offsets != nil {
		// MappedNodeOffsetIndex.size is the persisted signed count, not the
		// adjacency span or the largest ID mentioned by a candidate index.
		present = id < int32(binary.BigEndian.Uint32(offsets[4:8]))
	} else {
		// When no offset sidecar exists, projectionOffsetLocked uses the
		// loaded node-index locations. Holes correspond to missing offsets.
		_, present = s.locations[id]
	}
	s.callSiteIndex.mu.RUnlock()
	if !present {
		return Node{}, false, nil
	}
	// This reader handles the stored-zero sentinel, narrows long offsets to
	// Java int positions, and preserves EOF on negative mapped positions.
	return s.ProjectionCandidateNode(ctx, id)
}
