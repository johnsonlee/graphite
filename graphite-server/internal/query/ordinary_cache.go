package query

import (
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"sort"
	"unicode/utf8"
)

// Keys use Java String identity: a WTF8-encoded surrogate pair and its UTF8
// code point represent the same UTF16 sequence. Keep lone surrogates intact.
func ordinaryJavaKeyString(value string) string {
	if utf8.ValidString(value) {
		return value
	}
	return javaFromUTF16(javaUTF16(value))
}

func ordinaryStringKey(atom distinctStringAtom) string {
	return key([]any{atom.lower, atom.op, ordinaryJavaKeyString(atom.term)})
}
func mainNodeKey(plan *mainStringSourceSpec, limit int) string {
	predicates := []any{}
	for _, atom := range plan.atoms {
		predicates = append(predicates, []any{atom.property, atom.lower, atom.op, ordinaryJavaKeyString(atom.term)})
	}
	return key([]any{predicates, limit})
}
func ordinaryRowsKey(plan *ordinaryProjectionPlan, limit int) string {
	properties := make([]any, len(plan.properties))
	for i, p := range plan.properties {
		properties[i] = p
	}
	return key([]any{ordinaryNodeKey(plan, limit), properties})
}
func mainKeyCharacters(plan *mainStringSourceSpec) int64 {
	var n int64
	for _, atom := range plan.atoms {
		n += int64(len(javaUTF16(atom.property)) + len(javaUTF16(atom.term)))
	}
	return n
}
func (e evaluator) indexStringMatches(source Graph, index *store.DistinctStringIndex, atom distinctStringAtom) ([]int32, bool) {
	return e.stringIndexMatches(source, index, atom, failProjectionRead)
}
func (e evaluator) stringIndexMatches(source Graph, index *store.DistinctStringIndex, atom distinctStringAtom, failRead func(error)) ([]int32, bool) {
	return e.stringIndexMatchesWithCache(source, index, atom, failRead, false)
}

// Retained main storage checks its synchronized matching-string cache before
// any interruption checkpoint. A miss continues through the actual preparation
// and candidate-scanning checkpoints; this does not detach the worker context.
func (e evaluator) mainStringIndexMatches(source Graph, index *store.DistinctStringIndex, atom distinctStringAtom) ([]int32, bool) {
	return e.stringIndexMatchesWithCache(source, index, atom, failMainStringRead, true)
}

func (e evaluator) stringIndexMatchesWithCache(source Graph, index *store.DistinctStringIndex, atom distinctStringAtom, failRead func(error), mainEntry bool) ([]int32, bool) {
	units := javaUTF16(atom.term)
	if atom.op == "=" || len(units) < 3 {
		return nil, false
	}
	if !atom.lower {
		for _, unit := range units {
			if unit > 127 {
				return nil, false
			}
		}
	}
	cacheKey := ordinaryStringKey(atom)
	var cached []int32
	var ok bool
	var err error
	if mainEntry {
		cached, ok, err = index.MainProjectionCachedIDs(store.ProjectionStringMatches, cacheKey)
	} else {
		cached, ok, err = index.ProjectionCachedIDs(e.ctx, store.ProjectionStringMatches, cacheKey)
	}
	failRead(err)
	if ok {
		return cached, true
	}
	failRead(index.PrepareProjectionTrigramsWithWork(e.ctx, e.storeWorkConsumer()))
	ready, err := index.HasProjectionTrigrams(e.ctx)
	failRead(err)
	if !ready {
		return nil, false
	}
	term := javaUTF16(e.javaCase(atom.term, false))
	positions := []int{}
	switch atom.op {
	case "STARTS WITH":
		positions = []int{0}
	case "ENDS WITH":
		positions = []int{len(term) - 3}
	default:
		for at := 0; at+2 < len(term); at++ {
			positions = append(positions, at)
		}
	}
	// Main completes and flushes candidate selection before matching strings.
	// Intersect the actual posting ranges, rather than charging a scan of only
	// the shortest range (which can contain strings absent from another range).
	anchor := func() []int32 {
		accounting := bufferedGraphWork{work: e.work}
		defer accounting.flush()
		seen := map[int32]bool{}
		ranges := [][]int32{}
		for _, at := range positions {
			hash := (int32(term[at])*31+int32(term[at+1]))*31 + int32(term[at+2])
			if seen[hash] {
				continue
			}
			seen[hash] = true
			// Each unique trigram has one lower-bound and one upper-bound
			// lookup. Their binary-search comparisons are not separate units.
			accounting.consume()
			accounting.consume()
			ids, err := index.ProjectionTrigramStrings(e.ctx, hash)
			failRead(err)
			if len(ids) == 0 {
				return []int32{}
			}
			ranges = append(ranges, ids)
		}
		if len(ranges) == 0 {
			return nil
		}
		sort.SliceStable(ranges, func(i, j int) bool { return len(ranges[i]) < len(ranges[j]) })
		candidates := append([]int32(nil), ranges[0]...)
		for _, ids := range ranges[1:] {
			retained := 0
			for _, sid := range candidates {
				e.check()
				accounting.consume()
				position := sort.Search(len(ids), func(i int) bool { return ids[i] >= sid })
				if position < len(ids) && ids[position] == sid {
					candidates[retained] = sid
					retained++
				}
			}
			candidates = candidates[:retained]
			if retained == 0 {
				break
			}
		}
		return candidates
	}()
	matches := []int32{}
	func() {
		accounting := bufferedGraphWork{work: e.work}
		defer accounting.flush()
		for _, sid := range anchor {
			e.check()
			accounting.consume()
			value, err := source.Store.ProjectionString(e.ctx, sid)
			failRead(err)
			if e.distinctAtomMatches(atom, value) {
				matches = append(matches, sid)
			}
		}
	}()
	bytes := int64(104 + 2*len(units) + 4*len(matches))
	if mainEntry {
		failRead(index.MainCacheProjectionIDs(store.ProjectionStringMatches, cacheKey, matches, bytes))
	} else {
		failRead(index.CacheProjectionIDs(e.ctx, store.ProjectionStringMatches, cacheKey, matches, bytes))
	}
	return matches, true
}
func (e evaluator) mainCacheNodes(index *store.DistinctStringIndex, plan *mainStringSourceSpec, limit int, ids []int32) {
	if limit > 200 {
		return
	}
	bytes := 144 + 2*mainKeyCharacters(plan) + 4*int64(len(ids))
	if plan.lazyMain {
		failMainStringRead(index.MainCacheProjectionIDs(store.ProjectionNodeMatches, mainNodeKey(plan, limit), ids, bytes))
	} else {
		failMainStringRead(index.CacheProjectionIDs(e.ctx, store.ProjectionNodeMatches, mainNodeKey(plan, limit), ids, bytes))
	}
}
func (e evaluator) ordinaryCacheRows(index *store.DistinctStringIndex, plan *ordinaryProjectionPlan, limit int, rows [][]string) {
	characters := ordinaryKeyCharacters(plan)
	for _, property := range plan.properties {
		characters += int64(len(javaUTF16(property)))
	}
	bytes := int64(128) + 2*characters + 64*int64(len(rows))
	for _, row := range rows {
		bytes += 8 * int64(len(row))
		for _, value := range row {
			bytes += 40 + 2*int64(len(javaUTF16(value)))
		}
	}
	failProjectionRead(index.CacheProjectionRows(e.ctx, ordinaryRowsKey(plan, limit), rows, bytes))
}

func ordinaryNodeKey(plan *ordinaryProjectionPlan, limit int) string {
	return mainNodeKey(plan.mainSourceSpec(), limit)
}
func ordinaryKeyCharacters(plan *ordinaryProjectionPlan) int64 {
	return mainKeyCharacters(plan.mainSourceSpec())
}
