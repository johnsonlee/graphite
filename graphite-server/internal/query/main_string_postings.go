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
		var ids []int32
		var hit bool
		var err error
		if plan.lazyMain {
			ids, hit, err = index.MainProjectionCachedIDs(store.ProjectionNodeMatches, mainNodeKey(plan, limit))
		} else {
			ids, hit, err = index.ProjectionCachedIDs(e.ctx, store.ProjectionNodeMatches, mainNodeKey(plan, limit))
		}
		failMainStringRead(err)
		if hit {
			e.consume(int64(max(len(ids), 1)))
			position := 0
			return func(ctx context.Context) (int32, bool) {
				if !plan.lazyMain {
					if err := ctx.Err(); err != nil {
						panic(err)
					}
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
	ranges := e.mainIndexMatchingRanges(source, index, plan.atoms)
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
	yielded, inspected := 0, 0
	consumed := []int32{}
	return func(ctx context.Context) (int32, bool) {
		e.ctx = ctx
		if !plan.lazyMain {
			e.check()
		}
		if complete {
			return 0, false
		}
		order := func(rangeIndex int) int64 {
			var order int64
			var err error
			id := ranges[rangeIndex][positions[rangeIndex]]
			if plan.lazyMain {
				order, err = source.Store.MainProjectionNodeOrder(id)
			} else {
				order, err = source.Store.ProjectionNodeOrder(ctx, id)
			}
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
		accounting := bufferedGraphWork{work: e.work}
		defer accounting.flush()
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
			if plan.lazyMain {
				// Main polls heap visits, including duplicate IDs, at 0/1024.
				// Heap construction and completed EOF do not add a worker poll.
				if inspected&1023 == 0 {
					failMainStringRead(ctx.Err())
				}
				inspected++
			} else {
				e.check()
			}
			id := ranges[heap[0]][positions[heap[0]]]
			advance = true
			if id == previous {
				continue
			}
			accounting.consume()
			previous = id
			yielded++
			// Main flushes before yield. The caller decodes/projects only
			// after this charge succeeds, and EOF alone publishes the cache.
			accounting.flush()
			if limit <= 200 {
				consumed = append(consumed, id)
			}
			return id, true
		}
	}
}

// Main PropertyCsr.collectMatchingRanges charges actual search probes, not every
// entry in a convenient Go directory scan. Each predicate has its own finally
// flush, following the matching-string lookup's separate batch boundary.
func (e evaluator) mainIndexMatchingRanges(source Graph, index *store.DistinctStringIndex, atoms []distinctStringAtom) [][]int32 {
	ranges := [][]int32{}
	type stringMatches struct {
		ids   []int32
		known bool
	}
	// Main shares null results as well as successful candidate arrays within
	// this request, independently of whether the retained LRU admits an entry.
	sharedMatches := map[string]stringMatches{}
	sharedStates := map[string]*boundedStringMatcher{}
	for _, atom := range atoms {
		property, ok := distinctCallSiteProperties[atom.property]
		if !ok {
			continue
		}
		predicateKey := ordinaryStringKey(atom)
		matchedStrings, shared := sharedMatches[predicateKey]
		if !shared {
			matchedStrings.ids, matchedStrings.known = e.stringIndexMatches(source, index, atom, failMainStringRead)
			sharedMatches[predicateKey] = matchedStrings
		}
		matches, known := matchedStrings.ids, matchedStrings.known
		var matcher *boundedStringMatcher
		if !known && !(atom.op == "=" && !atom.lower) && !mainRequiresTrigramSignature(atom) {
			matcher = sharedStates[predicateKey]
			if matcher == nil {
				matcher = &boundedStringMatcher{atom: atom, dense: make([]byte, len(source.Store.Strings))}
				sharedStates[predicateKey] = matcher
			}
		}
		directory, err := index.Directory(e.ctx, store.CallSiteStringProperty(property))
		failMainStringRead(err)
		func() {
			accounting := bufferedGraphWork{work: e.work}
			defer accounting.flush()
			appendRange := func(row int) {
				ids, err := index.Postings(e.ctx, store.CallSiteStringProperty(property), directory[row].StringID)
				failMainStringRead(err)
				if len(ids) > 0 {
					ranges = append(ranges, ids)
				}
			}
			if atom.op == "=" && !atom.lower {
				exact := e.distinctStringTableID(source.Store.Strings, atom.term)
				if exact < 0 {
					return
				}
				low, high := 0, len(directory)-1
				for low <= high {
					accounting.consume()
					middle := (low + high) / 2
					switch {
					case directory[middle].StringID < exact:
						low = middle + 1
					case directory[middle].StringID > exact:
						high = middle - 1
					default:
						appendRange(middle)
						return
					}
				}
				return
			}
			if known {
				rowIDs := make([]int32, len(directory))
				for i, entry := range directory {
					rowIDs[i] = entry.StringID
				}
				candidate, row := 0, 0
				for candidate < len(matches) && row < len(rowIDs) {
					e.check()
					switch {
					case matches[candidate] < rowIDs[row]:
						candidate = mainIndexGallop(matches, candidate, rowIDs[row], &accounting)
					case matches[candidate] > rowIDs[row]:
						row = mainIndexGallop(rowIDs, row, matches[candidate], &accounting)
					default:
						accounting.consume()
						appendRange(row)
						candidate++
						row++
					}
				}
				return
			}
			for row, entry := range directory {
				e.check()
				accounting.consume()
				if matcher != nil {
					if matcher.matches(e, entry.StringID, func(sid int32) (string, error) {
						value, err := source.Store.ProjectionString(e.ctx, sid)
						failMainStringRead(err)
						return value, nil
					}) {
						appendRange(row)
					}
					continue
				}
				value, err := source.Store.ProjectionString(e.ctx, entry.StringID)
				failMainStringRead(err)
				if e.distinctAtomMatches(atom, value) {
					appendRange(row)
				}
			}
		}()
	}
	return ranges
}

func mainRequiresTrigramSignature(atom distinctStringAtom) bool {
	units := javaUTF16(atom.term)
	if atom.op != "CONTAINS" || len(units) < 3 {
		return false
	}
	for _, unit := range units {
		if unit >= 128 {
			return false
		}
	}
	return true
}

func mainIndexGallop(values []int32, start int, target int32, accounting *bufferedGraphWork) int {
	bound := 1
	for bound < len(values)-start {
		accounting.consume()
		if values[start+bound] >= target {
			break
		}
		bound = min(bound*2, len(values)-start)
	}
	low, high := start+bound/2+1, min(len(values), start+bound+1)
	for low < high {
		accounting.consume()
		middle := (low + high) / 2
		if values[middle] < target {
			low = middle + 1
		} else {
			high = middle
		}
	}
	return low
}
