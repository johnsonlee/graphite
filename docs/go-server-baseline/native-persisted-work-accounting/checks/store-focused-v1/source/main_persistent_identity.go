package store

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"math"
	"path/filepath"

	"github.com/johnsonlee/graphite/graphite-server/internal/javastring"
)

func (s *Store) mainPersistedContentIdentity(ctx context.Context, closing <-chan struct{}, consumeWork func(int64) error) ([32]byte, error) {
	if err := indexCheck(ctx, closing); err != nil {
		return [32]byte{}, err
	}
	if identity, ok := readIdentity(filepath.Join(s.dir, "graph.callsite-string-content.identity")); ok {
		return identity, submitPersistentWork(consumeWork, 1)
	}
	stringsIdentity, err := s.mainSemanticStringIdentity(ctx, closing, consumeWork)
	if err != nil {
		return [32]byte{}, err
	}
	return s.mainRawContentIdentity(ctx, closing, stringsIdentity, consumeWork)
}

func (s *Store) mainSemanticStringIdentity(ctx context.Context, closing <-chan struct{}, consumeWork func(int64) error) ([32]byte, error) {
	st := &s.callSiteIndex
	st.mu.RLock()
	identity, ready, closed := st.semanticStringIdentity, st.semanticStringIdentityReady, st.closed
	st.mu.RUnlock()
	if closed {
		return [32]byte{}, ErrStoreClosed
	}
	if ready {
		return identity, nil
	}
	// The retained preparation ticket serializes this helper. Strict/mapped
	// readers deliberately retain their previous independent identity path.
	identity, err := s.computeMainStringIdentity(ctx, closing, consumeWork)
	if err != nil {
		return [32]byte{}, err
	}
	st.mu.Lock()
	defer st.mu.Unlock()
	if st.closed {
		return [32]byte{}, ErrStoreClosed
	}
	st.semanticStringIdentity, st.semanticStringIdentityReady = identity, true
	return identity, nil
}

func (s *Store) computeMainStringIdentity(ctx context.Context, closing <-chan struct{}, consumeWork func(int64) error) (identity [32]byte, err error) {
	work := persistentReadWork{ctx: ctx, closing: closing, consumer: consumeWork}
	defer func() {
		if rejected := work.flush(); rejected != nil {
			err = rejected
		}
	}()
	digest := sha256.New()
	var word [4]byte
	binary.BigEndian.PutUint32(word[:], uint32(len(s.Strings)))
	digest.Write(word[:])
	for _, text := range s.Strings {
		if err = work.consume(); err != nil {
			return
		}
		bytes := []byte(javastring.WireString(text))
		if len(bytes) > math.MaxInt32 {
			return identity, fmt.Errorf("%w: string exceeds Java byte length", ErrInvalidGraphData)
		}
		binary.BigEndian.PutUint32(word[:], uint32(len(bytes)))
		digest.Write(word[:])
		digest.Write(bytes)
	}
	copy(identity[:], digest.Sum(nil))
	return
}

func (s *Store) mainRawContentIdentity(ctx context.Context, closing <-chan struct{}, stringsIdentity [32]byte, consumeWork func(int64) error) (identity [32]byte, err error) {
	work := persistentReadWork{ctx: ctx, closing: closing, consumer: consumeWork}
	defer func() {
		if rejected := work.flush(); rejected != nil {
			err = rejected
		}
	}()
	digest := sha256.New()
	digest.Write(stringsIdentity[:])
	var word [8]byte
	ids := s.byKind["CallSiteNode"]
	binary.BigEndian.PutUint32(word[:4], uint32(len(ids)))
	digest.Write(word[:4])
	for _, nodeID := range ids {
		if err = work.consume(); err != nil {
			return
		}
		var sids [4]int32
		sids, err = s.ProjectionStringIDs(ctx, nodeID)
		if err != nil {
			return
		}
		var order int64
		order, err = s.ProjectionNodeOrder(ctx, nodeID)
		if err != nil {
			return
		}
		binary.BigEndian.PutUint32(word[:4], uint32(nodeID))
		digest.Write(word[:4])
		binary.BigEndian.PutUint64(word[:], uint64(order))
		digest.Write(word[:])
		for _, sid := range sids {
			binary.BigEndian.PutUint32(word[:4], uint32(sid))
			digest.Write(word[:4])
		}
	}
	copy(identity[:], digest.Sum(nil))
	return
}
