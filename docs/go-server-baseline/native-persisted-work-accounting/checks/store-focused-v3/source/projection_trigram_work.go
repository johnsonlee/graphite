package store

import (
	"context"
	"fmt"
	"math"
	"sort"

	"github.com/johnsonlee/graphite/graphite-server/internal/javastring"
)

// PrepareProjectionTrigramsWithWork uses a request-local consumer. The owner
// ticket belongs to this index generation; neither it nor its caches retain a
// callback. Metadata publication survives a later postings failure.
func (i *DistinctStringIndex) PrepareProjectionTrigramsWithWork(ctx context.Context, consume func(int64) error) error {
	s := i.owner
	s.callSiteIndex.mu.RLock()
	closed := s.callSiteIndex.closed
	ready := i.view != nil || i.ordinary == nil || i.ordinary.trigramsReady
	s.callSiteIndex.mu.RUnlock()
	if closed {
		return ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	if ready {
		return nil
	}
	finish, err := s.beginProjectionWork(ctx, &i.ordinary.trigramLoading)
	if err != nil {
		return err
	}
	defer finish()
	s.callSiteIndex.mu.RLock()
	ready = i.ordinary.trigramsReady
	metadataReady := i.ordinary.trigramMetadataReady
	closing := s.callSiteIndex.closing
	s.callSiteIndex.mu.RUnlock()
	if ready {
		return nil
	}
	if !metadataReady {
		ids := i.usedProjectionTrigramStrings()
		signatures := make([]uint64, len(s.Strings))
		counts := make([]int32, len(s.Strings))
		var count int64
		err := i.forEachProjectionTrigramString(ctx, closing, consume, ids, func(sid int32, hashes []int32) error {
			counts[sid] = int32(len(hashes))
			count += int64(len(hashes))
			for _, hash := range hashes {
				mixed := uint32(hash) ^ (uint32(hash) >> 11) ^ (uint32(hash) << 7)
				signatures[sid] |= uint64(1)<<(uint32(hash)&63) | uint64(1)<<(mixed&63)
			}
			return nil
		})
		if err != nil {
			return err
		}
		s.callSiteIndex.mu.Lock()
		if s.callSiteIndex.closed {
			s.callSiteIndex.mu.Unlock()
			return ErrStoreClosed
		}
		i.ordinary.signatures = signatures
		i.ordinary.trigramStringIDs = ids
		i.ordinary.trigramPostingCounts = counts
		i.ordinary.trigramPostingCount = count
		i.ordinary.trigramMetadataReady = true
		s.callSiteIndex.mu.Unlock()
	}
	s.callSiteIndex.mu.RLock()
	ids := i.ordinary.trigramStringIDs
	counts := i.ordinary.trigramPostingCounts
	count := i.ordinary.trigramPostingCount
	s.callSiteIndex.mu.RUnlock()
	// Main completes the optional postings stage without a postings array for
	// zero/oversized counts. No second string pass is performed in that case.
	if count == 0 || count > math.MaxInt32 {
		return i.publishProjectionTrigrams(nil)
	}
	postings := make([]int64, 0, int(count))
	err = i.forEachProjectionTrigramString(ctx, closing, consume, ids, func(sid int32, hashes []int32) error {
		if int64(len(hashes)) != int64(counts[sid]) {
			return fmt.Errorf("trigram posting count changed during preparation")
		}
		for _, hash := range hashes {
			postings = append(postings, int64(hash)<<32|int64(uint32(sid)))
		}
		return nil
	})
	if err != nil {
		return err
	}
	if err = indexCheck(ctx, closing); err != nil {
		return err
	}
	sort.Slice(postings, func(a, b int) bool { return postings[a] < postings[b] })
	if err = indexCheck(ctx, closing); err != nil {
		return err
	}
	return i.publishProjectionTrigrams(postings)
}

func (i *DistinctStringIndex) publishProjectionTrigrams(postings []int64) error {
	s := i.owner
	s.callSiteIndex.mu.Lock()
	defer s.callSiteIndex.mu.Unlock()
	if s.callSiteIndex.closed {
		return ErrStoreClosed
	}
	i.ordinary.trigrams = postings
	i.ordinary.trigramsReady = true
	i.ordinary.trigramStringIDs = nil
	i.ordinary.trigramPostingCounts = nil
	return nil
}

func (i *DistinctStringIndex) usedProjectionTrigramStrings() []int32 {
	seen := make([]bool, len(i.owner.Strings))
	ids := []int32{}
	for _, entries := range i.entries {
		propertyIDs := make([]int32, 0, len(entries))
		for sid := range entries {
			propertyIDs = append(propertyIDs, sid)
		}
		sort.Slice(propertyIDs, func(a, b int) bool { return propertyIDs[a] < propertyIDs[b] })
		for _, sid := range propertyIDs {
			if !seen[sid] {
				seen[sid] = true
				ids = append(ids, sid)
			}
		}
	}
	return ids
}

func (i *DistinctStringIndex) forEachProjectionTrigramString(ctx context.Context, closing <-chan struct{}, consume func(int64) error, ids []int32, visit func(int32, []int32) error) (err error) {
	work := persistentReadWork{ctx: ctx, closing: closing, consumer: consume}
	defer func() {
		if rejected := work.flush(); rejected != nil {
			err = rejected
		}
	}()
	for _, sid := range ids {
		if err = work.consume(); err != nil {
			return
		}
		var text string
		text, err = i.owner.ProjectionString(ctx, sid)
		if err != nil {
			return
		}
		var canceled error
		text = javastring.Case(text, false, func() { canceled = ctx.Err() })
		if canceled != nil {
			return canceled
		}
		units := javastring.UTF16(text)
		seen := map[int32]bool{}
		hashes := []int32{}
		for at := 0; at+2 < len(units); at++ {
			if err = ctx.Err(); err != nil {
				return
			}
			hash := (int32(units[at])*31+int32(units[at+1]))*31 + int32(units[at+2])
			if !seen[hash] {
				seen[hash] = true
				hashes = append(hashes, hash)
			}
		}
		if err = visit(sid, hashes); err != nil {
			return
		}
	}
	return nil
}
