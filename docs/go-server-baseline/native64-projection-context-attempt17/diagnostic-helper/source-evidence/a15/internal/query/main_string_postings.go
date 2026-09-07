package query

import (
	"context"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// Matching ranges are prepared before returning the iterator. Posting positions
// and the merge heap are query-local, and order reads occur only at heap operations.
func (e evaluator) mainIndexNodeIDs(source Graph, index *store.DistinctStringIndex, plan *mainStringSourceSpec, limit int) func(context.Context) (int32, bool) {
	if limit <= 0 {
		return func(context.Context) (int32, bool) { return 0, false }
	}
	if limit <= 200 {
		ids, hit, err := index.ProjectionCachedIDs(e.ctx, store.ProjectionNodeMatches, mainNodeKey(plan, limit))
		failMainStringRead(err)
		if hit {
			position := 0
			return func(ctx context.Context) (int32, bool) {
				if err := ctx.Err(); err != nil {
					panic(err)
				}
				if position == len(ids) {
					return 0, false
				}
				id := ids[position]
				position++
				return id, true
			}
		}
	}
	ranges := [][]int32{}
	for _, atom := range plan.atoms {
		property, ok := distinctCallSiteProperties[atom.property]
		if !ok {
			continue
		}
		matches, known := e.stringIndexMatches(source, index, atom, failMainStringRead)
		directory, err := index.Directory(e.ctx, store.CallSiteStringProperty(property))
		failMainStringRead(err)
		exact := int32(-1)
		if atom.op == "=" && !atom.lower {
			exact = e.distinctStringTableID(source.Store.Strings, atom.term)
		}
		matchedSet := map[int32]bool{}
		for _, sid := range matches {
			matchedSet[sid] = true
		}
		for _, entry := range directory {
			e.check()
			matched := false
			if atom.op == "=" && !atom.lower {
				matched = entry.StringID == exact && exact >= 0
			} else if known {
				matched = matchedSet[entry.StringID]
			} else {
				value, err := source.Store.ProjectionString(e.ctx, entry.StringID)
				failMainStringRead(err)
				matched = e.distinctAtomMatches(atom, value)
			}
			if !matched {
				continue
			}
			ids, err := index.Postings(e.ctx, store.CallSiteStringProperty(property), entry.StringID)
			failMainStringRead(err)
			if len(ids) > 0 {
				ranges = append(ranges, ids)
			}
		}
	}
	if len(ranges) == 0 {
		e.mainCacheNodes(index, plan, limit, []int32{})
		return func(context.Context) (int32, bool) { return 0, false }
	}
	positions := make([]int, len(ranges))
	heap := make([]int, len(ranges))
	for i := range heap {
		heap[i] = i
	}
	size := len(heap)
	initialized, advance, complete := false, false, false
	previous := int32(-1)
	yielded := 0
	consumed := []int32{}
	return func(ctx context.Context) (int32, bool) {
		e.ctx = ctx
		e.check()
		if complete {
			return 0, false
		}
		order := func(rangeIndex int) int64 {
			order, err := source.Store.ProjectionNodeOrder(ctx, ranges[rangeIndex][positions[rangeIndex]])
			failMainStringRead(err)
			return order
		}
		sift := func(start int) {
			parent := start
			for {
				left := parent*2 + 1
				if left >= size {
					return
				}
				child := left
				right := left + 1
				if right < size && order(heap[right]) < order(heap[left]) {
					child = right
				}
				if order(heap[parent]) <= order(heap[child]) {
					return
				}
				heap[parent], heap[child] = heap[child], heap[parent]
				parent = child
			}
		}
		if !initialized {
			for i := size/2 - 1; i >= 0; i-- {
				sift(i)
			}
			initialized = true
		}
		for {
			if advance {
				if yielded >= limit {
					size = 0
				} else {
					at := heap[0]
					positions[at]++
					if positions[at] >= len(ranges[at]) {
						size--
						if size > 0 {
							heap[0] = heap[size]
						}
					}
					if size > 0 {
						sift(0)
					}
				}
				advance = false
			}
			if size == 0 {
				e.mainCacheNodes(index, plan, limit, consumed)
				complete = true
				return 0, false
			}
			e.check()
			id := ranges[heap[0]][positions[heap[0]]]
			advance = true
			if id == previous {
				continue
			}
			previous = id
			yielded++
			if limit <= 200 {
				consumed = append(consumed, id)
			}
			return id, true
		}
	}
}
