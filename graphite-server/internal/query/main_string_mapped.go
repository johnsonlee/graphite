package query

import (
	"container/heap"
	"context"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"sort"
)

type mainMappedPostingCursor struct {
	ids      []int32
	orders   []int64
	position int
	order    int64
}
type mainMappedPostingHeap []*mainMappedPostingCursor

func (h mainMappedPostingHeap) Len() int { return len(h) }
func (h mainMappedPostingHeap) Less(i, j int) bool {
	if h[i].order == h[j].order {
		return h[i].ids[h[i].position] < h[j].ids[h[j].position]
	}
	return h[i].order < h[j].order
}
func (h mainMappedPostingHeap) Swap(i, j int) { h[i], h[j] = h[j], h[i] }
func (h *mainMappedPostingHeap) Push(v any)   { *h = append(*h, v.(*mainMappedPostingCursor)) }
func (h *mainMappedPostingHeap) Pop() any {
	old := *h
	v := old[len(old)-1]
	old[len(old)-1] = nil
	*h = old[:len(old)-1]
	return v
}

// Cold rows validate their complete canonical order before returning a sequence.
// Warm rows retain only the validation result; their orders are read on advance.
// This preserves the mapped-view boundary without pre-consuming unrelated rows.
func (e evaluator) mainSelectedMappedIDs(source Graph, index *store.DistinctStringIndex, plan *mainStringSourceSpec, exact []map[int32]bool, limit int) (func(context.Context) (int32, bool), bool) {
	pending := mainMappedPostingHeap{}
	readOrder := func(ctx context.Context, c *mainMappedPostingCursor) {
		if c.orders != nil {
			c.order = c.orders[c.position]
			return
		}
		order, err := source.Store.ProjectionNodeOrder(ctx, c.ids[c.position])
		failMainStringRead(err)
		c.order = order
	}
	for i, atom := range plan.atoms {
		sids := make([]int32, 0, len(exact[i]))
		for sid := range exact[i] {
			sids = append(sids, sid)
		}
		sort.Slice(sids, func(i, j int) bool { return sids[i] < sids[j] })
		for _, sid := range sids {
			ids, orders, valid, err := index.MainMappedProjectionRange(e.ctx, store.CallSiteStringProperty(distinctCallSiteProperties[atom.property]), sid)
			failMainStringRead(err)
			if !valid {
				return nil, false
			}
			if len(ids) == 0 {
				continue
			}
			cursor := &mainMappedPostingCursor{ids: ids, orders: orders}
			readOrder(e.ctx, cursor)
			pending = append(pending, cursor)
		}
	}
	started := false
	var resume *mainMappedPostingCursor
	previous := int32(-1)
	yielded := 0
	return func(ctx context.Context) (int32, bool) {
		if yielded >= limit {
			return 0, false
		}
		if !started {
			heap.Init(&pending)
			started = true
		}
		for {
			if resume != nil {
				resume.position++
				if resume.position < len(resume.ids) {
					readOrder(ctx, resume)
					heap.Push(&pending, resume)
				}
				resume = nil
			}
			if len(pending) == 0 {
				return 0, false
			}
			local := e
			local.ctx = ctx
			local.check()
			cursor := heap.Pop(&pending).(*mainMappedPostingCursor)
			resume = cursor
			id := cursor.ids[cursor.position]
			if id == previous {
				continue
			}
			previous = id
			yielded++
			return id, true
		}
	}, true
}
