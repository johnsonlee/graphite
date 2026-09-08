package store

import (
	"context"
	"github.com/johnsonlee/graphite/graphite-server/internal/javastring"
)

// CertifyCallSiteTrigrams proves completeness of the derived index for the SIDs
// used by the four already-certified property directories. It is cold query work.
// Invalid proofs cache unavailable, so callers keep dictionary matching. Context
// cancellation and Close never publish a proof. Extra pairs are harmless because
// candidates still undergo exact matching. Signatures are deliberately unused.
func (s *Store) CertifyCallSiteTrigrams(ctx context.Context, view *CallSiteStringIndex) (bool, error) {
	if s.Mode != "MAPPED" || view == nil || view.owner != s {
		return false, nil
	}
	if valid, err := s.CertifyCallSiteCandidates(ctx, view); err != nil || !valid {
		return false, err
	}
	st := &s.trigramProof
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
		valid, err := s.certifyCallSiteTrigrams(ctx, view)
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

func (s *Store) certifyCallSiteTrigrams(ctx context.Context, view *CallSiteStringIndex) (bool, error) {
	seen := make(map[int32]bool)
	for property := CallerClass; property <= CalleeName; property++ {
		directory, err := view.Directory(ctx, property)
		if err != nil {
			return certificateFailure(err)
		}
		for _, entry := range directory {
			if err := ctx.Err(); err != nil {
				return false, err
			}
			if seen[entry.StringID] {
				continue
			}
			seen[entry.StringID] = true
			hashes, err := lowerTrigramHashes(ctx, s.Strings[entry.StringID])
			if err != nil {
				return false, err
			}
			valid, err := view.containsTrigrams(ctx, entry.StringID, hashes)
			if err != nil {
				return certificateFailure(err)
			}
			if !valid {
				return false, nil
			}
		}
	}
	return true, ctx.Err()
}

type trigramCancellation struct{ err error }

func lowerTrigramHashes(ctx context.Context, value string) (hashes []int32, err error) {
	defer func() {
		if r := recover(); r != nil {
			if canceled, ok := r.(trigramCancellation); ok {
				hashes = nil
				err = canceled.err
			} else {
				panic(r)
			}
		}
	}()
	check := func() {
		if err := ctx.Err(); err != nil {
			panic(trigramCancellation{err})
		}
	}
	check()
	lower := javastring.Case(value, false, check)
	units := javastring.UTF16(lower)
	check()
	seen := make(map[int32]bool)
	for i := 0; i+2 < len(units); i++ {
		check()
		hash := (int32(units[i])*31+int32(units[i+1]))*31 + int32(units[i+2])
		if !seen[hash] {
			seen[hash] = true
			hashes = append(hashes, hash)
		}
	}
	return hashes, nil
}
