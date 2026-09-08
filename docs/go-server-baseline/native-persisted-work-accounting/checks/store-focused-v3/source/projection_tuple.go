package store

// Port of main 4e328b0 MappedCallSiteStringIndex.kt:1702-1896. This table is
// transient retained-index state, never a query-result cache or persisted data.
import (
	"context"
	"fmt"
	"math"
	"slices"

	"github.com/johnsonlee/graphite/graphite-server/internal/javastring"
)

type exactProjectionTupleIndex struct {
	hashes []uint64
	nodes  []int32
}

func projectionTupleHash(values [4]int32) uint64 {
	h := uint64(14695981039346656037)
	for _, v := range values {
		h = (h ^ uint64(int64(v))) * 1099511628211
	}
	return h
}
func projectionTupleSlot(h uint64, capacity int) int {
	h = (h ^ (h >> 33)) * uint64(18397679294719823053)
	h = (h ^ (h >> 33)) * uint64(14181476777654086739)
	return int(uint32(h^(h>>33))) & (capacity - 1)
}
func projectionJavaHash(ctx context.Context, s string) (int32, error) {
	var h int32
	for n, u := range javastring.UTF16(s) {
		if n&1023 == 0 {
			if err := ctx.Err(); err != nil {
				return 0, err
			}
		}
		h = 31*h + int32(u)
	}
	return h, ctx.Err()
}

// PrepareExactProjectionTuples is intentionally called before checking whether
// projection columns form a four-property permutation. An inapplicable selected
// projection can still consume the whole CSR and retain the resulting table.
// The shared Store lock serializes construction/publication and Close. No public
// lock-taking reader is called recursively from this critical section.
func (i *DistinctStringIndex) PrepareExactProjectionTuples(ctx context.Context, selectedCount int) (bool, error) {
	s := i.owner
	if err := s.prepareProjectionOffsets(ctx); err != nil {
		return false, err
	}
	s.callSiteIndex.mu.Lock()
	defer s.callSiteIndex.mu.Unlock()
	if s.callSiteIndex.closed {
		return false, ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return false, err
	}
	if i.ordinary == nil || i.Raw || i.ParallelRaw {
		return false, nil
	}
	if i.ordinary.exactTuple != nil {
		return true, nil
	}
	// A mapped-view handle is not a retained persisted index.
	if selectedCount < 256 || i.view == nil || s.distinctProjection.index == nil || s.distinctProjection.index.ordinary != i.ordinary {
		return false, nil
	}
	v := i.view
	r := v.regions[CallerClass]
	rows := int(v.info.UniqueStringCounts[CallerClass])
	if rows == 0 {
		return false, nil
	}
	count := int(v.intAt(r.ends + (rows-1)*4))
	if count < 4096 {
		return false, nil
	}
	capacity := 1
	for int64(capacity) < 2*int64(count) {
		if capacity > math.MaxInt32/2 {
			return false, nil
		}
		capacity *= 2
	}
	table := &exactProjectionTupleIndex{hashes: make([]uint64, capacity), nodes: make([]int32, capacity)}
	for n := range table.nodes {
		if n&1023 == 0 {
			if err := ctx.Err(); err != nil {
				return false, err
			}
		}
		table.nodes[n] = -1
	}
	stringHashes := make([]int32, len(s.Strings))
	stringHash := func(sid int32) (int32, error) {
		if sid < 0 || int64(sid) >= int64(len(stringHashes)) {
			m := fmt.Sprintf("Index %d out of bounds for length %d", sid, len(stringHashes))
			return 0, &ProjectionReadError{Class: "ArrayIndexOutOfBoundsException", Message: &m}
		}
		if stringHashes[sid] != 0 {
			return stringHashes[sid], nil
		}
		h, err := projectionJavaHash(ctx, s.Strings[sid])
		if err == nil {
			stringHashes[sid] = h
		}
		return h, err
	}
	for at := 0; at < count; at++ {
		if err := ctx.Err(); err != nil {
			return false, err
		}
		id := v.intAt(r.nodes + at*4)
		var ids, hashes [4]int32
		// Read all four raw integers before indexing the temporary hash array.
		for p := CallerClass; p <= CalleeName; p++ {
			sid, err := s.projectionStringIDLocked(id, p)
			if err != nil {
				return false, err
			}
			ids[p] = sid
		}
		for p, sid := range ids {
			h, err := stringHash(sid)
			if err != nil {
				return false, err
			}
			hashes[p] = h
		}
		hash := projectionTupleHash(hashes)
		slot := projectionTupleSlot(hash, capacity)
		duplicate := false
		for table.nodes[slot] >= 0 {
			if err := ctx.Err(); err != nil {
				return false, err
			}
			existing := table.nodes[slot]
			same := table.hashes[slot] == hash
			if same {
				for p := CallerClass; p <= CalleeName; p++ {
					sid, err := s.projectionStringIDLocked(existing, p)
					if err != nil {
						return false, err
					}
					if sid != ids[p] {
						same = false
						break
					}
				}
			}
			if same {
				order, err := s.projectionOffsetLocked(id)
				if err != nil {
					return false, err
				}
				previous, err := s.projectionOffsetLocked(existing)
				if err != nil {
					return false, err
				}
				if order < previous {
					table.nodes[slot] = id
				}
				duplicate = true
				break
			}
			slot = (slot + 1) & (capacity - 1)
		}
		if !duplicate {
			table.hashes[slot] = hash
			table.nodes[slot] = id
		}
	}
	if err := ctx.Err(); err != nil {
		return false, err
	}
	i.ordinary.exactTuple = table
	return true, nil
}

// ExactProjectionProbe advances only until one tuple-equal node. The caller
// evaluates its predicates before advancing again, preserving short circuiting
// across hash collisions and avoiding callbacks under the Store lifetime lock.
type ExactProjectionProbe struct {
	owner    *Store
	table    *exactProjectionTupleIndex
	hash     uint64
	slot     int
	expected [4]string
	done     bool
}

func (i *DistinctStringIndex) ExactProjectionProbe(ctx context.Context, expected [4]string) (*ExactProjectionProbe, error) {
	s := i.owner
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	if s.callSiteIndex.closed {
		return nil, ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if i.ordinary == nil || i.ordinary.exactTuple == nil {
		return nil, fmt.Errorf("exact projection tuples are not prepared")
	}
	var hashes [4]int32
	for p, value := range expected {
		h, err := projectionJavaHash(ctx, value)
		if err != nil {
			return nil, err
		}
		hashes[p] = h
	}
	table := i.ordinary.exactTuple
	hash := projectionTupleHash(hashes)
	return &ExactProjectionProbe{owner: s, table: table, hash: hash, slot: projectionTupleSlot(hash, len(table.nodes)), expected: expected}, nil
}
func (p *ExactProjectionProbe) Next(ctx context.Context) (int32, bool, error) {
	s := p.owner
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	if s.callSiteIndex.closed {
		return 0, false, ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return 0, false, err
	}
	for !p.done {
		if err := ctx.Err(); err != nil {
			return 0, false, err
		}
		slot := p.slot
		id := p.table.nodes[slot]
		if id < 0 {
			p.done = true
			break
		}
		p.slot = (slot + 1) & (len(p.table.nodes) - 1)
		if p.table.hashes[slot] != p.hash {
			continue
		}
		same := true
		for property := CallerClass; property <= CalleeName; property++ {
			sid, err := s.projectionStringIDLocked(id, property)
			if err != nil {
				return 0, false, err
			}
			if sid < 0 || int64(sid) >= int64(len(s.Strings)) {
				return 0, false, projectionStringError(sid, len(s.Strings))
			}
			if !slices.Equal(javastring.UTF16(s.Strings[sid]), javastring.UTF16(p.expected[property])) {
				same = false
				break
			}
		}
		if same {
			if err := ctx.Err(); err != nil {
				return 0, false, err
			}
			return id, true, nil
		}
	}
	return 0, false, ctx.Err()
}

// ClearProjectionQueryCaches leaves structural indexes, including the tuple
// table, intact. Existing cache payloads are owned values.
func (i *DistinctStringIndex) ClearProjectionQueryCaches(ctx context.Context) error {
	s := i.owner
	s.callSiteIndex.mu.Lock()
	defer s.callSiteIndex.mu.Unlock()
	if s.callSiteIndex.closed {
		return ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	if i.ordinary != nil {
		i.ordinary.caches = [3]projectionLRU{}
	}
	return nil
}
