package store

import (
	"context"
	"errors"
	"sync"
)

type candidateCertificateState struct {
	mu               sync.Mutex
	pending          chan struct{}
	completed, valid bool
}

// CandidateNode keeps mapped bytes alive through a candidate's complete decode.
func (s *Store) CandidateNode(ctx context.Context, id int32) (Node, error) {
	if err := ctx.Err(); err != nil {
		return Node{}, err
	}
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	if s.callSiteIndex.closed {
		return Node{}, ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return Node{}, err
	}
	return s.Node(id)
}

// CertifyCallSiteCandidates is counted cold query preparation, not an implicit
// load-time optimization. It proves that skipping records cannot hide a native
// full-node decode failure and that CSR membership agrees with each raw property.
// A failed proof is unavailable, allowing the original scan to choose its own
// failure/row timing. Cancellation and close propagate and never cache failure.
// Successful/failed proofs live only as long as this immutable Store instance.
func (s *Store) CertifyCallSiteCandidates(ctx context.Context, view *CallSiteStringIndex) (bool, error) {
	if s.Mode != "MAPPED" || view == nil || view.owner != s {
		return false, nil
	}
	st := &s.candidateProof
	for {
		// The fixed lock order is index lifetime, then certificate state. Cached
		// returns and publication both linearize before Store.Close or observe it.
		s.callSiteIndex.mu.RLock()
		if s.callSiteIndex.closed {
			s.callSiteIndex.mu.RUnlock()
			return false, ErrStoreClosed
		}
		if err := ctx.Err(); err != nil {
			s.callSiteIndex.mu.RUnlock()
			return false, err
		}
		st.mu.Lock()
		if st.completed {
			valid := st.valid
			st.mu.Unlock()
			s.callSiteIndex.mu.RUnlock()
			return valid, nil
		}
		if pending := st.pending; pending != nil {
			st.mu.Unlock()
			s.callSiteIndex.mu.RUnlock()
			select {
			case <-ctx.Done():
				return false, ctx.Err()
			case <-pending:
			}
			continue
		}
		st.pending = make(chan struct{})
		pending := st.pending
		st.mu.Unlock()
		s.callSiteIndex.mu.RUnlock()
		valid, err := s.certifyCallSiteCandidates(ctx, view)
		s.callSiteIndex.mu.RLock()
		st.mu.Lock()
		if s.callSiteIndex.closed {
			err = ErrStoreClosed
		}
		if canceled := ctx.Err(); canceled != nil {
			err = canceled
		}
		if err == nil {
			st.completed, st.valid = true, valid
		} else {
			valid = false
		}
		st.pending = nil
		close(pending)
		st.mu.Unlock()
		s.callSiteIndex.mu.RUnlock()
		return valid, err
	}
}

func (s *Store) certifyCallSiteCandidates(ctx context.Context, view *CallSiteStringIndex) (bool, error) {
	previousCallSiteOffset := int64(-1)
	for _, id := range s.ids {
		node, err := s.CandidateNode(ctx, id)
		if err != nil {
			return certificateFailure(err)
		}
		if node.Kind == "CallSiteNode" {
			offset := s.locations[id].offset
			if offset <= previousCallSiteOffset {
				return false, nil
			}
			previousCallSiteOffset = offset
		}
	}
	// Directory keys are unique, and each posting row has strictly increasing
	// offsets (reader validation). Correct SID association plus total membership
	// count therefore also proves no cross-row duplicates or omitted CallSites.
	for property := CallerClass; property <= CalleeName; property++ {
		directory, err := view.Directory(ctx, property)
		if err != nil {
			return certificateFailure(err)
		}
		total := 0
		for _, entry := range directory {
			ids, err := view.Postings(ctx, property, entry.StringID)
			if err != nil {
				return certificateFailure(err)
			}
			for _, id := range ids {
				raw, err := s.RawCallSiteStringIDs(ctx, id)
				if err != nil {
					return certificateFailure(err)
				}
				if raw.StringIDs[property] != entry.StringID {
					return false, nil
				}
				total++
			}
		}
		if total != len(s.byKind["CallSiteNode"]) {
			return false, nil
		}
	}
	if _, err := view.Info(ctx); err != nil {
		return false, err
	}
	return true, nil
}
func certificateFailure(err error) (bool, error) {
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) || errors.Is(err, ErrStoreClosed) {
		return false, err
	}
	return false, nil
}
