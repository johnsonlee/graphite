package query

import (
	"context"
	"errors"
	"runtime"
	"sort"

	"github.com/johnsonlee/graphite/graphite-server/internal/javastring"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// The mapped view supports the main CONTAINS trigram capability only. Its
// matching IDs are string-table IDs, not IDs restricted to a property directory.
func (e evaluator) mainExactMatches(source Graph, index *store.DistinctStringIndex, plan *mainStringSourceSpec) ([]map[int32]bool, bool) {
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
	shared := map[string]map[int32]bool{}
	for i, atom := range plan.atoms {
		key := ordinaryStringKey(atom)
		matches, present := shared[key]
		if !present {
			var supported bool
			matches, supported = e.mainMappedStringMatches(source, index, atom)
			if !supported {
				return nil, false
			}
			shared[key] = matches
		}
		sets[i] = matches
	}
	return sets, true
}

func (e evaluator) mainMappedStringMatches(source Graph, index *store.DistinctStringIndex, atom distinctStringAtom) (map[int32]bool, bool) {
	if !index.MainMappedCapability() {
		ids, supported := e.stringIndexMatches(source, index, atom, failMainStringRead)
		if !supported {
			return nil, false
		}
		matches := make(map[int32]bool, len(ids))
		for _, sid := range ids {
			matches[sid] = true
		}
		return matches, true
	}
	accounting := bufferedGraphWork{work: e.work}
	defer accounting.flush()
	term := atom.term
	if !atom.lower {
		term = javastring.Lower(term)
	}
	units := javaUTF16(term)
	seen := map[int32]bool{}
	anchorStart, anchorEnd := 0, 0
	matches := map[int32]bool{}
	for j := 0; j+2 < len(units); j++ {
		hash := (int32(units[j])*31+int32(units[j+1]))*31 + int32(units[j+2])
		if seen[hash] {
			continue
		}
		seen[hash] = true
		accounting.consume()
		start, end, found, err := index.MainMappedTrigramSpan(hash)
		failMainStringRead(err)
		if !found {
			return matches, true
		}
		if anchorEnd == 0 || end-start < anchorEnd-anchorStart {
			anchorStart, anchorEnd = start, end
		}
	}
	for position := anchorStart; position < anchorEnd; position++ {
		// main polls the absolute posting position, not every candidate or
		// binary-search read. Work callbacks retain their own cancellation.
		if position&1023 == 0 {
			failMainMappedRead(e.ctx.Err())
		}
		accounting.consume()
		sid, err := index.MainMappedTrigramStringIDAt(position)
		failMainStringRead(err)
		if sid < 0 || int64(sid) >= int64(len(source.Store.Strings)) {
			return nil, false
		}
		text, err := source.Store.MainMappedString(sid)
		failMainStringRead(err)
		if mainMappedContains(atom, text) {
			matches[sid] = true
		}
	}
	return matches, true
}

func ordinaryAnyExact(sets []map[int32]bool) bool {
	for _, s := range sets {
		if len(s) > 0 {
			return true
		}
	}
	return false
}
func (e evaluator) mainExactCanFill(index *store.DistinctStringIndex, plan *mainStringSourceSpec, sets []map[int32]bool, limit int) bool {
	if limit <= 0 {
		return true
	}
	if len(plan.atoms) != len(sets) {
		return false
	}
	if index.MainMappedCapability() {
		var occurrences int64
		for i, atom := range plan.atoms {
			for _, sid := range mainSortedStringIDs(sets[i]) {
				start, end, found, err := index.MainMappedPostingRangeWithWork(store.CallSiteStringProperty(distinctCallSiteProperties[atom.property]), sid, e.storeWorkConsumer())
				failMainStringRead(err)
				if !found {
					continue
				}
				if start < 0 || end < start || end > index.MainMappedCallSiteCount() {
					return false
				}
				occurrences += int64(end - start)
				if occurrences >= int64(limit) {
					return true
				}
			}
		}
		return false
	}
	count := 0
	for i, atom := range plan.atoms {
		entries, err := index.Directory(e.ctx, store.CallSiteStringProperty(distinctCallSiteProperties[atom.property]))
		failMainStringRead(err)
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

func mainSortedStringIDs(set map[int32]bool) []int32 {
	ids := make([]int32, 0, len(set))
	for sid := range set {
		ids = append(ids, sid)
	}
	sort.Slice(ids, func(i, j int) bool { return ids[i] < ids[j] })
	return ids
}

type ordinaryScanRange struct {
	ids      []int32
	records  []store.ProjectionRecord
	complete bool
	failure  any
}

func (e evaluator) mainParallelCandidates(source Graph, plan *mainStringSourceSpec, limit int, exact []map[int32]bool, orderedWaves bool) ([]int32, bool) {
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
		// Each actual scan task owns its own batch. Flush before the outer
		// recovery cancels sibling tasks, including on decoding failure.
		accounting := bufferedGraphWork{work: e.work}
		defer accounting.flush()
		start, end := i*chunk, min(len(ids), (i+1)*chunk)
		out.complete = true
		for at := start; at < end; at++ {
			local.check()
			accounting.consume()
			id := ids[at]
			sids, err := source.Store.ProjectionStringIDs(ctx, id)
			failMainStringRead(err)
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
					failMainStringRead(err)
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
				failMainStringRead(err)
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

func (e evaluator) ordinaryExactMatches(source Graph, index *store.DistinctStringIndex, plan *ordinaryProjectionPlan) ([]map[int32]bool, bool) {
	defer ordinarySourceFailure()
	return e.mainExactMatches(source, index, plan.mainSourceSpec())
}
func (e evaluator) ordinaryExactCanFill(index *store.DistinctStringIndex, plan *ordinaryProjectionPlan, sets []map[int32]bool, limit int) bool {
	defer ordinarySourceFailure()
	return e.mainExactCanFill(index, plan.mainSourceSpec(), sets, limit)
}
func (e evaluator) ordinaryParallelCandidates(source Graph, plan *ordinaryProjectionPlan, limit int, exact []map[int32]bool, orderedWaves bool) ([]int32, bool) {
	defer ordinarySourceFailure()
	return e.mainParallelCandidates(source, plan.mainSourceSpec(), limit, exact, orderedWaves)
}
