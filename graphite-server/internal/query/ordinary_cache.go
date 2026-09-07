package query

import (
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
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
func ordinaryNodeKey(plan *ordinaryProjectionPlan, limit int) string {
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
func ordinaryKeyCharacters(plan *ordinaryProjectionPlan) int64 {
	var n int64
	for _, atom := range plan.atoms {
		n += int64(len(javaUTF16(atom.property)) + len(javaUTF16(atom.term)))
	}
	return n
}
func (e evaluator) indexStringMatches(source Graph, index *store.DistinctStringIndex, atom distinctStringAtom) ([]int32, bool) {
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
	cached, ok, err := index.ProjectionCachedIDs(e.ctx, store.ProjectionStringMatches, cacheKey)
	failProjectionRead(err)
	if ok {
		return cached, true
	}
	failProjectionRead(index.PrepareProjectionTrigrams(e.ctx))
	ready, err := index.HasProjectionTrigrams(e.ctx)
	failProjectionRead(err)
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
	seen := map[int32]bool{}
	var anchor []int32
	for _, at := range positions {
		hash := (int32(term[at])*31+int32(term[at+1]))*31 + int32(term[at+2])
		if seen[hash] {
			continue
		}
		seen[hash] = true
		ids, err := index.ProjectionTrigramStrings(e.ctx, hash)
		failProjectionRead(err)
		if len(ids) == 0 {
			anchor = []int32{}
			break
		}
		if anchor == nil || len(ids) < len(anchor) {
			anchor = ids
		}
	}
	matches := []int32{}
	for _, sid := range anchor {
		value, err := source.Store.ProjectionString(e.ctx, sid)
		failProjectionRead(err)
		if e.distinctAtomMatches(atom, value) {
			matches = append(matches, sid)
		}
	}
	bytes := int64(104 + 2*len(units) + 4*len(matches))
	failProjectionRead(index.CacheProjectionIDs(e.ctx, store.ProjectionStringMatches, cacheKey, matches, bytes))
	return matches, true
}
func (e evaluator) ordinaryCacheNodes(index *store.DistinctStringIndex, plan *ordinaryProjectionPlan, limit int, ids []int32) {
	if limit > 200 {
		return
	}
	bytes := 144 + 2*ordinaryKeyCharacters(plan) + 4*int64(len(ids))
	failProjectionRead(index.CacheProjectionIDs(e.ctx, store.ProjectionNodeMatches, ordinaryNodeKey(plan, limit), ids, bytes))
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

func (e evaluator) ordinaryLimitedIndexIDs(source Graph, index *store.DistinctStringIndex, plan *ordinaryProjectionPlan, limit int) []int32 {
	if limit <= 200 {
		ids, hit, err := index.ProjectionCachedIDs(e.ctx, store.ProjectionNodeMatches, ordinaryNodeKey(plan, limit))
		failProjectionRead(err)
		if hit {
			return ids
		}
	}
	ids := e.ordinaryMatchingIDs(source, index, plan)
	if len(ids) > limit {
		ids = ids[:limit]
	}
	if len(ids) == 0 {
		e.ordinaryCacheNodes(index, plan, limit, ids)
	}
	return ids
}
