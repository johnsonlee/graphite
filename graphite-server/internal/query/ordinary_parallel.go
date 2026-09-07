package query

import (
	"context"
	"errors"
	"runtime"
	"sort"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// The mapped view supports the main CONTAINS trigram capability only. Its
// matching IDs are string-table IDs, not IDs restricted to a property directory.
func (e evaluator) ordinaryExactMatches(source Graph, index *store.DistinctStringIndex, plan *ordinaryProjectionPlan) ([]map[int32]bool, bool) {
	for _, atom := range plan.atoms {
		units := javaUTF16(atom.term)
		if atom.op != "CONTAINS" || len(units) < 3 {
			return nil, false
		}
		if !atom.lower {
			for _, unit := range units {
				if unit > 127 {
					return nil, false
				}
			}
		}
	}
	sets := make([]map[int32]bool, len(plan.atoms))
	for i, atom := range plan.atoms {
		term := atom.term
		if !atom.lower {
			term = e.javaCase(term, false)
		}
		units := javaUTF16(term)
		seen := map[int32]bool{}
		var anchor []int32
		for j := 0; j+2 < len(units); j++ {
			hash := (int32(units[j])*31+int32(units[j+1]))*31 + int32(units[j+2])
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
		sets[i] = map[int32]bool{}
		for _, sid := range anchor {
			text, err := source.Store.ProjectionString(e.ctx, sid)
			failProjectionRead(err)
			if e.distinctAtomMatches(atom, text) {
				sets[i][sid] = true
			}
		}
	}
	return sets, true
}
func ordinaryAnyExact(sets []map[int32]bool) bool {
	for _, s := range sets {
		if len(s) > 0 {
			return true
		}
	}
	return false
}
func (e evaluator) ordinaryExactCanFill(index *store.DistinctStringIndex, plan *ordinaryProjectionPlan, sets []map[int32]bool, limit int) bool {
	count := 0
	for i, atom := range plan.atoms {
		entries, err := index.Directory(e.ctx, store.CallSiteStringProperty(distinctCallSiteProperties[atom.property]))
		failProjectionRead(err)
		for _, entry := range entries {
			if sets[i][entry.StringID] {
				count += int(entry.PostingCount)
				if count >= limit {
					return true
				}
			}
		}
	}
	return false
}

type ordinaryScanRange struct {
	ids      []int32
	records  []store.ProjectionRecord
	complete bool
	failure  any
}

func (e evaluator) ordinaryParallelCandidates(source Graph, plan *ordinaryProjectionPlan, limit int, exact []map[int32]bool, orderedWaves bool) ([]int32, bool) {
	ids := source.Store.NodesOfKind("CallSiteNode")
	split := plan.sourceCount >= 40
	if len(ids) < 4096 || limit >= len(ids) || limit >= int(^uint32(0)>>1) || (!split && runtime.NumCPU() <= 1) {
		return nil, false
	}
	workers := min(runtime.NumCPU(), len(ids))
	if split {
		workers = min(len(ids), max(0, runtime.NumCPU()-max(1, runtime.NumCPU()/2))+1)
	}
	ranges := workers
	if orderedWaves {
		ranges *= 2
	}
	chunk := (len(ids) + ranges - 1) / ranges
	count := (len(ids) + chunk - 1) / chunk
	results := make([]ordinaryScanRange, count)
	completed := 0
	ctx, cancel := context.WithCancel(e.ctx)
	defer cancel()
	task := func(i int) (out ordinaryScanRange) {
		defer func() {
			out.failure = recover()
			if out.failure != nil {
				cancel()
			}
		}()
		local := e
		local.ctx = ctx
		start, end := i*chunk, min(len(ids), (i+1)*chunk)
		out.complete = true
		for at := start; at < end; at++ {
			local.check()
			id := ids[at]
			sids, err := source.Store.ProjectionStringIDs(ctx, id)
			failProjectionRead(err)
			if !split {
				out.records = append(out.records, store.ProjectionRecord{NodeID: id, StringIDs: sids})
			}
			matched := false
			for j, atom := range plan.atoms {
				sid := sids[distinctCallSiteProperties[atom.property]]
				if exact != nil {
					matched = exact[j][sid]
				} else {
					text, err := source.Store.ProjectionArrayString(ctx, sid)
					failProjectionRead(err)
					matched = local.distinctAtomMatches(atom, text)
				}
				if matched {
					break
				}
			}
			if matched {
				out.ids = append(out.ids, id)
			}
			if len(out.ids) >= limit {
				out.complete = at+1 == end
				break
			}
		}
		return
	}
	isCancel := func(value any) bool {
		err, ok := value.(error)
		return ok && (errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded))
	}
	for start := 0; start < count; start += workers {
		end := min(count, start+workers)
		type outcome struct {
			index  int
			result ordinaryScanRange
		}
		done := make(chan outcome, end-start)
		first := start
		if split {
			first++
		}
		for i := first; i < end; i++ {
			go func(i int) { done <- outcome{i, task(i)} }(i)
		}
		var failure any
		if split {
			results[start] = task(start)
			failure = results[start].failure
		}
		for i := first; i < end; i++ {
			out := <-done
			results[out.index] = out.result
			if out.result.failure != nil && (failure == nil || isCancel(failure) && !isCancel(out.result.failure)) {
				failure = out.result.failure
			}
		}
		if failure != nil {
			panic(failure)
		}
		completed = end
		matches := 0
		for _, r := range results[:end] {
			matches += len(r.ids)
		}
		if orderedWaves && matches >= limit {
			break
		}
	}
	if !split && completed == count {
		all := true
		records := []store.ProjectionRecord{}
		for _, r := range results {
			all = all && r.complete
			records = append(records, r.records...)
		}
		if all {
			err := source.Store.PublishProjectionScan(e.ctx, records)
			if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) || errors.Is(err, store.ErrStoreClosed) {
				failProjectionRead(err)
			}
			// Publication is optional after a successful complete scan. Other build
			// failures must not replace the already-established candidate result.
		}
	}
	matches := []int32{}
	for _, r := range results {
		remaining := limit - len(matches)
		matches = append(matches, r.ids[:min(remaining, len(r.ids))]...)
		if len(matches) == limit {
			break
		}
	}
	return matches, true
}

func (e evaluator) ordinaryMatchingIDs(source Graph, index *store.DistinctStringIndex, plan *ordinaryProjectionPlan) []int32 {
	for _, atom := range plan.atoms {
		units := javaUTF16(atom.term)
		use := atom.op != "=" && len(units) >= 3
		if !atom.lower {
			for _, unit := range units {
				if unit > 127 {
					use = false
					break
				}
			}
		}
		if use {
			failProjectionRead(index.PrepareProjectionTrigrams(e.ctx))
			break
		}
	}
	return e.distinctMatchingIDs(source, index, plan.indexedDistinctPlan)
}

func (e evaluator) ordinaryMappedIDs(source Graph, index *store.DistinctStringIndex, plan *ordinaryProjectionPlan, exact []map[int32]bool) []int32 {
	selected := map[int32]bool{}
	for i, atom := range plan.atoms {
		for sid := range exact[i] {
			ids, err := index.Postings(e.ctx, store.CallSiteStringProperty(distinctCallSiteProperties[atom.property]), sid)
			failProjectionRead(err)
			for _, id := range ids {
				e.check()
				selected[id] = true
			}
		}
	}
	positions := []candidateNodePosition{}
	for id := range selected {
		offset, err := source.Store.ProjectionNodeOrder(e.ctx, id)
		failProjectionRead(err)
		positions = append(positions, candidateNodePosition{id, offset})
	}
	sort.Slice(positions, func(i, j int) bool {
		if positions[i].offset == positions[j].offset {
			return positions[i].id < positions[j].id
		}
		return positions[i].offset < positions[j].offset
	})
	ids := make([]int32, len(positions))
	for i, p := range positions {
		ids[i] = p.id
	}
	return ids
}
