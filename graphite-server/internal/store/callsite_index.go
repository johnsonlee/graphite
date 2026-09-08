package store

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"hash/crc32"
	"math"
	"os"
	"path/filepath"
	"sync"
)

var ErrStoreClosed = errors.New("store is closed")
var ErrInvalidGraphData = errors.New("invalid graph data")

const callSiteIndexFile = "graph.callsite-string-index"
const callSiteIndexHeaderBytes = 76

// CallSiteStringProperty is the fixed property order in main's v2 CSR index.
type CallSiteStringProperty uint8

const (
	CallerClass CallSiteStringProperty = iota
	CallerName
	CalleeClass
	CalleeName
)

type CallSiteStringIndexInfo struct {
	Version                    int32
	StringCount, CallSiteCount int32
	UniqueStringCounts         [4]int32
	TrigramPostingCount        int32
	RetainedBytes              int64
	ContentIdentity            [32]byte
}
type CallSiteStringDirectoryEntry struct{ StringID, PostingCount int32 }
type callSiteIndexRegion struct{ strings, ends, nodes int }
type callSiteIndexState struct {
	mu          sync.RWMutex
	closed      bool
	closing     chan struct{}
	loading     chan struct{}
	view        *CallSiteStringIndex
	unavailable bool
	reason      string
	main        [2]mainCallSiteIndexState
}

// CallSiteStringIndex is an immutable Store-owned view. Methods return copied
// values, never mapped slices. There is deliberately no per-query Close method.
// Store.Close waits for active reads and a first load before returning.
type CallSiteStringIndex struct {
	owner                *Store
	data                 []byte
	info                 CallSiteStringIndexInfo
	regions              [4]callSiteIndexRegion
	signatures, trigrams int
	mainRanges           *mainPostingRangeCache
}

// TryCallSiteStringIndex lazily validates an optional main v2 index. Missing,
// unsupported or corrupt sidecars return (nil,false,nil). Cancellation and
// failures of required graph data return errors, and never publish/cache a view.
func (s *Store) TryCallSiteStringIndex(ctx context.Context) (*CallSiteStringIndex, bool, error) {
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
		if st.view != nil {
			v := st.view
			st.mu.Unlock()
			return v, true, nil
		}
		if st.unavailable {
			st.mu.Unlock()
			return nil, false, nil
		}
		if pending := st.loading; pending != nil {
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
		done := make(chan struct{})
		st.loading = done
		closing := st.closing
		st.mu.Unlock()
		v, reason, cache, err := s.loadCallSiteStringIndex(ctx, closing)
		st.mu.Lock()
		if st.closed {
			err = ErrStoreClosed
		} else if ctx.Err() != nil {
			err = ctx.Err()
		}
		if err == nil {
			if v != nil {
				st.view = v
				st.reason = ""
			} else {
				st.reason = reason
				st.unavailable = cache
			}
		}
		if err != nil && v != nil {
			_ = unmapNodeData(v.data)
			v = nil
		}
		st.loading = nil
		close(done)
		st.mu.Unlock()
		return v, v != nil, err
	}
}

// CallSiteStringIndexUnavailableReason is diagnostic only; it never converts a
// cancellation into an unavailable result. It is empty until a failed attempt.
func (s *Store) CallSiteStringIndexUnavailableReason() string {
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	return s.callSiteIndex.reason
}
func indexCheck(ctx context.Context, closing <-chan struct{}) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	select {
	case <-closing:
		return ErrStoreClosed
	default:
		return nil
	}
}

func (s *Store) loadCallSiteStringIndex(ctx context.Context, closing <-chan struct{}) (view *CallSiteStringIndex, reason string, cache bool, err error) {
	return s.loadCallSiteStringIndexWithPolicy(ctx, closing, strictCandidateIndex, 0)
}

func (s *Store) loadCallSiteStringIndexWithPolicy(ctx context.Context, closing <-chan struct{}, policy callSiteIndexPolicy, capacity int32) (view *CallSiteStringIndex, reason string, cache bool, err error) {
	if err = indexCheck(ctx, closing); err != nil {
		return
	}
	f, e := os.Open(filepath.Join(s.dir, callSiteIndexFile))
	if e != nil {
		return nil, e.Error(), !errors.Is(e, os.ErrNotExist), nil
	}
	defer f.Close()
	stat, e := f.Stat()
	if e != nil {
		return nil, e.Error(), true, nil
	}
	if !stat.Mode().IsRegular() || stat.Size() < 84 || stat.Size() > math.MaxInt32 {
		return nil, "invalid index file size or type", true, nil
	}
	data, e := mapNodeData(f, stat.Size())
	if e != nil {
		return nil, e.Error(), true, nil
	}
	keep := false
	defer func() {
		if !keep {
			_ = unmapNodeData(data)
		}
	}()
	fail := func(message string) (*CallSiteStringIndex, string, bool, error) { return nil, message, true, nil }
	read32 := func(at int) int32 { return int32(binary.BigEndian.Uint32(data[at : at+4])) }
	if read32(0) != 0x47524353 || read32(4) != 2 {
		return fail("unsupported CallSite string index magic/version")
	}
	info := CallSiteStringIndexInfo{Version: 2, StringCount: read32(8), CallSiteCount: read32(12), TrigramPostingCount: read32(64), RetainedBytes: int64(binary.BigEndian.Uint64(data[68:76]))}
	copy(info.ContentIdentity[:], data[16:48])
	if info.StringCount < 0 || int64(info.StringCount) != int64(len(s.Strings)) || info.CallSiteCount <= 0 || int64(info.CallSiteCount) != int64(len(s.byKind["CallSiteNode"])) || info.TrigramPostingCount <= 0 {
		return fail("index string/CallSite/posting count mismatch")
	}
	var unique int64
	for p := range info.UniqueStringCounts {
		n := read32(48 + 4*p)
		if n < 0 || n > info.StringCount {
			return fail("invalid unique string count")
		}
		info.UniqueStringCounts[p] = n
		unique += int64(n)
	}
	// All counts are bounded int32. Widen before arithmetic; reject a computed
	// layout outside the mapped int32 limit before converting offsets to int.
	payload := 8*unique + 16*int64(info.CallSiteCount) + 8*int64(info.StringCount) + 8*int64(info.TrigramPostingCount)
	if payload+84 != int64(len(data)) || (policy != mainMappedIndex && info.RetainedBytes != payload+480) || (policy == mainMappedIndex && info.RetainedBytes <= 0) {
		return fail("index layout/retained byte count mismatch")
	}
	expected, e := s.callSiteContentIdentity(ctx, closing)
	if e != nil {
		return nil, "", false, e
	}
	if expected != info.ContentIdentity {
		return fail("index content identity mismatch")
	}
	checksum, e := callSiteIndexCRC(ctx, closing, data, info)
	if e != nil {
		return nil, "", false, e
	}
	if uint64(checksum) != binary.BigEndian.Uint64(data[len(data)-8:]) {
		return fail("index CRC32 mismatch")
	}
	v := &CallSiteStringIndex{owner: s, data: data, info: info}
	// DISTINCT can publish a strict reader as the initialized mapped view.
	// Ordinary consumers must still validate selected posting ranges and retain
	// those results on that same mapping, including after a DISTINCT warmup.
	// Allocate before publication so concurrent readers never race cache setup.
	if policy == mainMappedIndex || policy == strictCandidateIndex {
		v.mainRanges = &mainPostingRangeCache{}
	}
	offset := callSiteIndexHeaderBytes
	for p, n := range info.UniqueStringCounts {
		r := callSiteIndexRegion{strings: offset, ends: offset + 4*int(n), nodes: offset + 8*int(n)}
		v.regions[p] = r
		offset = r.nodes + 4*int(info.CallSiteCount)
		previousID, previousEnd := int32(-1), int32(0)
		for row := 0; row < int(n); row++ {
			if e = indexCheck(ctx, closing); e != nil {
				return nil, "", false, e
			}
			id, end := read32(r.strings+row*4), read32(r.ends+row*4)
			if id < 0 || id >= info.StringCount || id <= previousID || end <= previousEnd || end > info.CallSiteCount {
				return fail("invalid CSR string directory or posting ends")
			}
			previousOrder := int64(-1)
			if policy != strictCandidateIndex {
				previousOrder = math.MinInt64
			}
			for pos := previousEnd; pos < end; pos++ {
				if pos&1023 == 0 {
					if e = indexCheck(ctx, closing); e != nil {
						return nil, "", false, e
					}
				}
				nodeID := read32(r.nodes + int(pos)*4)
				if policy == strictCandidateIndex {
					loc, ok := s.locations[nodeID]
					if !ok || loc.offset < 0 || loc.offset <= previousOrder {
						return fail("invalid CSR node ID or non-increasing node offset")
					}
					previousOrder = loc.offset
				} else {
					if nodeID < 0 || nodeID >= capacity {
						return fail("invalid CSR node ID capacity")
					}
					if policy == mainRetainedIndex {
						order, e := s.ProjectionNodeOrder(ctx, nodeID)
						if e != nil {
							if ctx.Err() != nil || errors.Is(e, ErrStoreClosed) {
								return nil, "", false, e
							}
							return fail("invalid persisted node order")
						}
						// The retained reader accepts a first negative order; the mapped
						// view's selected-range validation deliberately does not.
						if order <= previousOrder {
							return fail("non-increasing persisted node order")
						}
						previousOrder = order
					}
				}
			}
			previousID = id
			previousEnd = end
		}
		if previousEnd != info.CallSiteCount {
			return fail("CSR posting ends do not cover CallSite count")
		}
	}
	v.signatures = offset
	offset += 8 * int(info.StringCount)
	v.trigrams = offset
	previous := int64(math.MinInt64)
	for i := 0; i < int(info.TrigramPostingCount); i++ {
		if i&1023 == 0 {
			if e = indexCheck(ctx, closing); e != nil {
				return nil, "", false, e
			}
		}
		posting := int64(binary.BigEndian.Uint64(data[offset+i*8 : offset+i*8+8]))
		id := int32(posting)
		if posting < previous || id < 0 || id >= info.StringCount {
			return fail("invalid trigram posting order/string ID")
		}
		previous = posting
	}
	if e = indexCheck(ctx, closing); e != nil {
		return nil, "", false, e
	}
	keep = true
	return v, "", false, nil
}

func (v *CallSiteStringIndex) readLock(ctx context.Context) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	v.owner.callSiteIndex.mu.RLock()
	if v.owner.callSiteIndex.closed {
		v.owner.callSiteIndex.mu.RUnlock()
		return ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		v.owner.callSiteIndex.mu.RUnlock()
		return err
	}
	return nil
}
func (v *CallSiteStringIndex) Info(ctx context.Context) (CallSiteStringIndexInfo, error) {
	if err := v.readLock(ctx); err != nil {
		return CallSiteStringIndexInfo{}, err
	}
	defer v.owner.callSiteIndex.mu.RUnlock()
	return v.info, nil
}
func (v *CallSiteStringIndex) Directory(ctx context.Context, p CallSiteStringProperty) ([]CallSiteStringDirectoryEntry, error) {
	if err := v.readLock(ctx); err != nil {
		return nil, err
	}
	defer v.owner.callSiteIndex.mu.RUnlock()
	if p > 3 {
		return nil, fmt.Errorf("unknown CallSite string property %d", p)
	}
	r := v.regions[p]
	out := make([]CallSiteStringDirectoryEntry, int(v.info.UniqueStringCounts[p]))
	previous := int32(0)
	for i := range out {
		if i&1023 == 0 {
			if err := ctx.Err(); err != nil {
				return nil, err
			}
		}
		end := v.intAt(r.ends + i*4)
		out[i] = CallSiteStringDirectoryEntry{v.intAt(r.strings + i*4), end - previous}
		previous = end
	}
	return out, nil
}

// Postings returns source-local node IDs in strictly increasing node-data
// offset order. Different directory rows may overlap; no cross-row union is
// implied by this reader API. The returned copy remains safe after Store.Close.
func (v *CallSiteStringIndex) Postings(ctx context.Context, p CallSiteStringProperty, stringID int32) ([]int32, error) {
	if err := v.readLock(ctx); err != nil {
		return nil, err
	}
	defer v.owner.callSiteIndex.mu.RUnlock()
	if p > 3 {
		return nil, fmt.Errorf("unknown CallSite string property %d", p)
	}
	r := v.regions[p]
	lo, hi := 0, int(v.info.UniqueStringCounts[p])
	for lo < hi {
		mid := (lo + hi) / 2
		if v.intAt(r.strings+mid*4) < stringID {
			lo = mid + 1
		} else {
			hi = mid
		}
	}
	if lo >= int(v.info.UniqueStringCounts[p]) || v.intAt(r.strings+lo*4) != stringID {
		return []int32{}, nil
	}
	start := int32(0)
	if lo > 0 {
		start = v.intAt(r.ends + (lo-1)*4)
	}
	end := v.intAt(r.ends + lo*4)
	out := make([]int32, int(end-start))
	for i := range out {
		if i&1023 == 0 {
			if err := ctx.Err(); err != nil {
				return nil, err
			}
		}
		out[i] = v.intAt(r.nodes + (int(start)+i)*4)
	}
	return out, nil
}
func (v *CallSiteStringIndex) TrigramStringIDs(ctx context.Context, hash int32) ([]int32, error) {
	if err := v.readLock(ctx); err != nil {
		return nil, err
	}
	defer v.owner.callSiteIndex.mu.RUnlock()
	at := func(i int) int64 { return int64(binary.BigEndian.Uint64(v.data[v.trigrams+i*8 : v.trigrams+i*8+8])) }
	lo, hi := 0, int(v.info.TrigramPostingCount)
	target := int64(hash) << 32
	for lo < hi {
		mid := (lo + hi) / 2
		if at(mid) < target {
			lo = mid + 1
		} else {
			hi = mid
		}
	}
	out := []int32{}
	for i := lo; i < int(v.info.TrigramPostingCount) && int32(at(i)>>32) == hash; i++ {
		if i&1023 == 0 {
			if err := ctx.Err(); err != nil {
				return nil, err
			}
		}
		out = append(out, int32(at(i)))
	}
	return out, nil
}
func (v *CallSiteStringIndex) Signature(ctx context.Context, stringID int32) (uint64, error) {
	if err := v.readLock(ctx); err != nil {
		return 0, err
	}
	defer v.owner.callSiteIndex.mu.RUnlock()
	if stringID < 0 || stringID >= v.info.StringCount {
		return 0, fmt.Errorf("string ID %d outside table", stringID)
	}
	at := v.signatures + int(stringID)*8
	return binary.BigEndian.Uint64(v.data[at : at+8]), nil
}
func (v *CallSiteStringIndex) intAt(at int) int32 {
	return int32(binary.BigEndian.Uint32(v.data[at : at+4]))
}

// The file is big-endian, but main feeds each numeric value to CRC32 least
// significant byte first. The identity bytes are not reversed. This checksum
// cannot be replaced by crc32.ChecksumIEEE(data[:len(data)-8]).
func callSiteIndexCRC(ctx context.Context, closing <-chan struct{}, data []byte, info CallSiteStringIndexInfo) (uint32, error) {
	digest := crc32.NewIEEE()
	var scratch [32768]byte
	words := func(start, end, width int) error {
		for at := start; at < end; {
			if err := indexCheck(ctx, closing); err != nil {
				return err
			}
			next := min(at+len(scratch), end)
			for pos := at; pos < next; pos += width {
				for b := 0; b < width; b++ {
					scratch[pos-at+b] = data[pos+width-1-b]
				}
			}
			_, _ = digest.Write(scratch[:next-at])
			at = next
		}
		return nil
	}
	if err := words(0, 16, 4); err != nil {
		return 0, err
	}
	_, _ = digest.Write(data[16:48])
	if err := words(48, 68, 4); err != nil {
		return 0, err
	}
	if err := words(68, 76, 8); err != nil {
		return 0, err
	}
	end := 76
	for _, n := range info.UniqueStringCounts {
		end += 8*int(n) + 4*int(info.CallSiteCount)
	}
	if err := words(76, end, 4); err != nil {
		return 0, err
	}
	if err := words(end, len(data)-8, 8); err != nil {
		return 0, err
	}
	return digest.Sum32(), nil
}
