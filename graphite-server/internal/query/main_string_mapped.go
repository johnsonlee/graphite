package query

import (
	"container/heap"
	"context"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

type mainMappedPostingHeap []*store.MainMappedCursor

func (h mainMappedPostingHeap) Len() int { return len(h) }
func (h mainMappedPostingHeap) Less(i, j int) bool {
	if h[i].Order() == h[j].Order() {
		return h[i].NodeID() < h[j].NodeID()
	}
	return h[i].Order() < h[j].Order()
}
func (h mainMappedPostingHeap) Swap(i, j int) { h[i], h[j] = h[j], h[i] }
func (h *mainMappedPostingHeap) Push(v any)   { *h = append(*h, v.(*store.MainMappedCursor)) }
func (h *mainMappedPostingHeap) Pop() any {
	old := *h
	v := old[len(old)-1]
	old[len(old)-1] = nil
	*h = old[:len(old)-1]
	return v
}

// Range validation happens before returning the sequence. A cursor owns cold
// validation orders; warm cursors read their next order only on advance.
func (e evaluator) mainSelectedMappedIDs(source Graph, index *store.DistinctStringIndex, plan *mainStringSourceSpec, exact []map[int32]bool, limit int) (func(context.Context) (int32, bool), bool) {
	if !index.MainMappedCapability() || len(plan.atoms) != len(exact) {
		return nil, false
	}
	pending := mainMappedPostingHeap{}
	for i, atom := range plan.atoms {
		for _, sid := range mainSortedStringIDs(exact[i]) {
			cursor, valid, err := index.MainMappedCursorWithWork(e.ctx, store.CallSiteStringProperty(distinctCallSiteProperties[atom.property]), sid, e.storeWorkConsumer())
			failMainMappedRead(err)
			if !valid {
				return nil, false
			}
			if cursor.HasCurrent() {
				pending = append(pending, cursor)
			}
		}
	}
	started := false
	var resume *store.MainMappedCursor
	previous := int32(-1)
	yielded, visited := 0, 0
	return func(ctx context.Context) (int32, bool) {
		if yielded >= limit {
			return 0, false
		}
		if !started {
			heap.Init(&pending)
			started = true
		}
		accounting := bufferedGraphWork{work: e.work}
		defer accounting.flush()
		for {
			if resume != nil {
				// Kotlin resumes after yield before updating previous and advancing.
				previous = resume.NodeID()
				current, err := resume.Advance()
				failMainStringRead(err)
				if current {
					heap.Push(&pending, resume)
				}
				resume = nil
			}
			if len(pending) == 0 {
				return 0, false
			}
			if visited&1023 == 0 {
				failMainMappedRead(ctx.Err())
			}
			visited++
			cursor := heap.Pop(&pending).(*store.MainMappedCursor)
			accounting.consume()
			id := cursor.NodeID()
			resume = cursor
			// Every popped posting consumes work, including duplicate node IDs.
			if id == previous {
				continue
			}
			accounting.flush()
			yielded++
			return id, true
		}
	}, true
}
