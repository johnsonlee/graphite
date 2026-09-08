package store

import (
	"context"
	"fmt"
)

type ProjectionCacheKind uint8

const (
	ProjectionStringMatches ProjectionCacheKind = iota
	ProjectionNodeMatches
	ProjectionRows
)

type projectionCacheEntry struct {
	key   string
	bytes int64
	ids   []int32
	rows  [][]string
}
type projectionLRU struct {
	entries []projectionCacheEntry
	bytes   int64
}

func (c *projectionLRU) get(key string) (projectionCacheEntry, bool) {
	for i, entry := range c.entries {
		if entry.key == key {
			copy(c.entries[i:], c.entries[i+1:])
			c.entries[len(c.entries)-1] = entry
			return entry, true
		}
	}
	return projectionCacheEntry{}, false
}
func (c *projectionLRU) put(entry projectionCacheEntry) {
	for _, existing := range c.entries {
		if existing.key == entry.key {
			return
		}
	}
	if entry.bytes < 0 || entry.bytes > 2*1024*1024 {
		return
	}
	for len(c.entries) > 0 && (len(c.entries) >= 32 || c.bytes > 2*1024*1024-entry.bytes) {
		c.bytes -= c.entries[0].bytes
		c.entries = c.entries[1:]
	}
	c.entries = append(c.entries, entry)
	c.bytes += entry.bytes
}
func copyProjectionRows(rows [][]string) [][]string {
	out := make([][]string, len(rows))
	for i, row := range rows {
		out[i] = append([]string(nil), row...)
	}
	return out
}
func (i *DistinctStringIndex) ProjectionCachedIDs(ctx context.Context, kind ProjectionCacheKind, key string) ([]int32, bool, error) {
	return i.projectionCachedIDs(ctx, kind, key)
}

// A nil worker context skips polling, while preserving locking and copying.
func (i *DistinctStringIndex) projectionCachedIDs(ctx context.Context, kind ProjectionCacheKind, key string) ([]int32, bool, error) {
	s := i.owner
	s.callSiteIndex.mu.Lock()
	defer s.callSiteIndex.mu.Unlock()
	if s.callSiteIndex.closed {
		return nil, false, ErrStoreClosed
	}
	if ctx != nil {
		if err := ctx.Err(); err != nil {
			return nil, false, err
		}
	}
	if kind > ProjectionNodeMatches {
		return nil, false, fmt.Errorf("not an ID cache")
	}
	if i.ordinary == nil {
		return nil, false, nil
	}
	entry, ok := i.ordinary.caches[kind].get(key)
	return append([]int32(nil), entry.ids...), ok, nil
}
func (i *DistinctStringIndex) CacheProjectionIDs(ctx context.Context, kind ProjectionCacheKind, key string, ids []int32, bytes int64) error {
	return i.cacheProjectionIDs(ctx, kind, key, ids, bytes)
}

// A nil worker context skips polling, while preserving locking and copying.
func (i *DistinctStringIndex) cacheProjectionIDs(ctx context.Context, kind ProjectionCacheKind, key string, ids []int32, bytes int64) error {
	s := i.owner
	s.callSiteIndex.mu.Lock()
	defer s.callSiteIndex.mu.Unlock()
	if s.callSiteIndex.closed {
		return ErrStoreClosed
	}
	if ctx != nil {
		if err := ctx.Err(); err != nil {
			return err
		}
	}
	if kind > ProjectionNodeMatches {
		return fmt.Errorf("not an ID cache")
	}
	if i.ordinary != nil {
		i.ordinary.caches[kind].put(projectionCacheEntry{key: key, ids: append([]int32(nil), ids...), bytes: bytes})
	}
	return nil
}
func (i *DistinctStringIndex) ProjectionCachedRows(ctx context.Context, key string) ([][]string, bool, error) {
	s := i.owner
	s.callSiteIndex.mu.Lock()
	defer s.callSiteIndex.mu.Unlock()
	if s.callSiteIndex.closed {
		return nil, false, ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return nil, false, err
	}
	if i.ordinary == nil {
		return nil, false, nil
	}
	entry, ok := i.ordinary.caches[ProjectionRows].get(key)
	return copyProjectionRows(entry.rows), ok, nil
}
func (i *DistinctStringIndex) CacheProjectionRows(ctx context.Context, key string, rows [][]string, bytes int64) error {
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
		cache := &i.ordinary.caches[ProjectionRows]
		// Unlike ID-cache duplicate publication, main's projection publication
		// performs a map get and refreshes access order when another task won.
		if _, exists := cache.get(key); !exists {
			cache.put(projectionCacheEntry{key: key, rows: copyProjectionRows(rows), bytes: bytes})
		}
	}
	return nil
}

// ProjectionPlannerBytes is main's reservation estimate, used only to select
// its execution strategy. It is not a Go allocation or memory metric.
func (i *DistinctStringIndex) ProjectionPlannerBytes(ctx context.Context) (int64, error) {
	s := i.owner
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	if s.callSiteIndex.closed {
		return 0, ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return 0, err
	}
	return i.projectionPlannerBytesLocked(), nil
}

// Caller holds the Store lifetime lock. Main's strategy metadata getters do
// not themselves poll request interruption or initialize any index state.
func (i *DistinctStringIndex) projectionPlannerBytesLocked() int64 {
	s := i.owner
	bytes := int64(464 + 16*len(s.byKind["CallSiteNode"]) + 8*len(s.Strings))
	if i.view != nil {
		bytes = i.view.info.RetainedBytes
	} else {
		for _, entries := range i.entries {
			bytes += 8 * int64(len(entries))
		}
		if i.ordinary != nil && i.ordinary.trigramsReady && len(i.ordinary.trigrams) > 0 {
			bytes += 16 + 8*int64(len(i.ordinary.trigrams))
		}
	}
	if i.ordinary != nil {
		if table := i.ordinary.exactTuple; table != nil {
			bytes += 160 + 12*int64(len(table.nodes))
		}
		for _, cache := range i.ordinary.caches {
			bytes += cache.bytes
		}
	}
	return bytes
}
