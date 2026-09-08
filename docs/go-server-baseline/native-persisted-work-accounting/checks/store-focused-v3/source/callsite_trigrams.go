package store

import (
	"context"
	"encoding/binary"
)

// TrigramAnchor copies only the shortest posting span. An absent hash proves an
// empty intersection only after CertifyCallSiteTrigrams succeeds. Empty hashes
// are not a query plan and return nil. No mapped memory escapes the lifetime lock.
func (v *CallSiteStringIndex) TrigramAnchor(ctx context.Context, hashes []int32) ([]int32, error) {
	if err := v.readLock(ctx); err != nil {
		return nil, err
	}
	defer v.owner.callSiteIndex.mu.RUnlock()
	if len(hashes) == 0 {
		return nil, ctx.Err()
	}
	start, end := 0, 0
	for i, hash := range hashes {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		lo, hi := v.trigramBounds(hash)
		if lo == hi {
			if err := ctx.Err(); err != nil {
				return nil, err
			}
			return []int32{}, nil
		}
		if i == 0 || hi-lo < end-start {
			start, end = lo, hi
		}
	}
	out := make([]int32, end-start)
	for i := range out {
		if i&1023 == 0 {
			if err := ctx.Err(); err != nil {
				return nil, err
			}
		}
		out[i] = int32(v.trigramAt(start + i))
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	return out, nil
}

// These helpers require the reader lifetime lock. The persisted pairs are sorted
// by nonnegative UTF-16 hash then SID; the reader has checked that structure.
func (v *CallSiteStringIndex) trigramAt(i int) int64 {
	return int64(binary.BigEndian.Uint64(v.data[v.trigrams+i*8 : v.trigrams+i*8+8]))
}
func (v *CallSiteStringIndex) trigramBound(target int64) int {
	lo, hi := 0, int(v.info.TrigramPostingCount)
	for lo < hi {
		mid := lo + (hi-lo)/2
		if v.trigramAt(mid) < target {
			lo = mid + 1
		} else {
			hi = mid
		}
	}
	return lo
}
func (v *CallSiteStringIndex) trigramBounds(hash int32) (int, int) {
	start := v.trigramBound(int64(hash) << 32)
	lo, hi := start, int(v.info.TrigramPostingCount)
	for lo < hi {
		mid := lo + (hi-lo)/2
		if int32(v.trigramAt(mid)>>32) <= hash {
			lo = mid + 1
		} else {
			hi = mid
		}
	}
	return start, lo
}
func (v *CallSiteStringIndex) containsTrigrams(ctx context.Context, sid int32, hashes []int32) (bool, error) {
	if err := v.readLock(ctx); err != nil {
		return false, err
	}
	defer v.owner.callSiteIndex.mu.RUnlock()
	for _, hash := range hashes {
		if err := ctx.Err(); err != nil {
			return false, err
		}
		key := int64(hash)<<32 | int64(uint32(sid))
		at := v.trigramBound(key)
		if at == int(v.info.TrigramPostingCount) || v.trigramAt(at) != key {
			return false, ctx.Err()
		}
	}
	return true, ctx.Err()
}
