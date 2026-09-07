package query

import (
	"context"
	"errors"
	"fmt"
	"runtime"
	"sort"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

type distinctProjectedRow struct {
	order int64
	row   map[string]any
}
type distinctSourceResult struct {
	rows  []map[string]any
	known map[string]bool
	index *store.DistinctStringIndex
}

func failProjectionRead(err error) {
	if err == nil {
		return
	}
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		panic(err)
	}
	var raw *store.ProjectionReadError
	if errors.As(err, &raw) {
		class := raw.Class
		if class == "" {
			class = "IndexOutOfBoundsException"
		}
		if raw.Message == nil {
			panic(&Error{Class: class, NullMessage: true})
		}
		functionError(class, *raw.Message)
	}
	failNodeRead(err)
}
func (e evaluator) distinctAtomMatches(atom distinctStringAtom, value any) bool {
	s, ok := value.(string)
	if !ok {
		return false
	}
	if atom.lower {
		s = e.javaCase(s, false)
	}
	return e.binary(cypher.Binary{Left: cypher.Literal{Value: s}, Op: atom.op, Right: cypher.Literal{Value: atom.term}}, nil) == true
}
func distinctVisibleKey(row map[string]any) string {
	visible := clone(row)
	delete(visible, provenanceKey)
	return key(visible)
}
func (e evaluator) indexedDistinct(graph *store.Store, branch cypher.SingleQuery) (Result, bool) {
	// An empty catalog has no scoped store; the generic executor owns its empty rows.
	if !e.cross && graph == nil {
		return Result{}, false
	}
	plan := e.compileIndexedDistinct(branch)
	if plan == nil {
		return Result{}, false
	}
	sources := e.graphs
	if !e.cross {
		sources = []Graph{{ID: "single", Store: graph}}
	}
	selectedSources := make([]Graph, 0, len(sources))
	for _, source := range sources {
		if plan.graphIDs != nil && !plan.graphIDs[source.ID] {
			continue
		}
		if source.Store.Mode != "MAPPED" {
			return Result{}, false
		}
		selectedSources = append(selectedSources, source)
	}
	sources = selectedSources
	plan.sourceCount = len(sources)
	result := Result{Columns: plan.columns, Rows: []map[string]any{}}
	if len(sources) == 0 {
		return result, true
	}
	retained := []map[string]any{}
	seen := map[string]map[string]any{}
	states := make([]*distinctSourceResult, len(sources))
	merge := func(sourceIndex int, state distinctSourceResult) {
		states[sourceIndex] = &state
		if len(state.rows) == 0 && len(sources) > 1 {
			failProjectionRead(sources[sourceIndex].Store.ReleaseDistinctStringIndex(e.ctx))
		}
		for _, row := range state.rows {
			k := distinctVisibleKey(row)
			if previous := seen[k]; previous != nil {
				mergeProvenance(previous, row)
			} else if len(retained) < plan.limit {
				seen[k] = row
				retained = append(retained, row)
			}
		}
	}
	parallelism := min(len(sources), runtime.NumCPU(), 8)
	if parallelism < 1 {
		parallelism = 1
	}
	start := 0
	if len(sources) >= 40 {
		parallelism = max(1, runtime.NumCPU()/2)
		merge(0, e.distinctSourcePrefix(sources[0], plan))
		if len(retained) < plan.limit {
			runDistinctTasks(e.ctx, len(sources)-1, parallelism, true,
				func(ctx context.Context, i int) distinctSourceResult {
					local := e
					local.ctx = ctx
					local.rowOrders = nil
					return local.distinctSourcePrefix(sources[i+1], plan)
				}, func(i int, state distinctSourceResult) bool { merge(i+1, state); return len(retained) >= plan.limit })
		}
		start = len(sources)
	}
	for start < len(sources) && len(retained) < plan.limit {
		end := min(len(sources), start+parallelism)
		batch := e.distinctSourceBatch(sources[start:end], plan)
		for i, state := range batch {
			merge(start+i, state)
		}
		start = end
	}
	// Once LIMIT selects visible tuples, later sources can only add provenance.
	// Do not project arbitrary later tuples or consume unrelated projected fields.
	if len(retained) >= plan.limit {
		probeSources := []int{}
		for i := range sources {
			e.check()
			state := states[i]
			if state != nil && len(state.rows) == 0 {
				continue
			}
			allKnown := state != nil
			if state != nil {
				for k := range seen {
					if !state.known[k] {
						allKnown = false
						break
					}
				}
			}
			if allKnown {
				continue
			}
			probeSources = append(probeSources, i)
		}
		runDistinctTasks(e.ctx, len(probeSources), parallelism, false,
			func(ctx context.Context, i int) map[string]bool {
				local := e
				local.ctx = ctx
				local.rowOrders = nil
				return local.distinctSourceHits(sources[probeSources[i]], plan, retained)
			}, func(i int, hits map[string]bool) bool {
				for k := range hits {
					if row := seen[k]; row != nil {
						addProvenance(row, sources[probeSources[i]].ID)
					}
				}
				return false
			})
	}
	if plan.skip < len(retained) {
		result.Rows = retained[plan.skip:]
	}
	// Bind final column keys on the parent evaluator to preserve WTF-8 alias order.
	for i, row := range result.Rows {
		ordered := map[string]any{}
		for _, column := range plan.columns {
			e.bind(ordered, column, row[column])
		}
		addProvenance(ordered, provenance(row)...)
		result.Rows[i] = ordered
	}
	return result, true
}
func (e evaluator) distinctSourceBatch(sources []Graph, plan *indexedDistinctPlan) []distinctSourceResult {
	results := make([]distinctSourceResult, len(sources))
	runDistinctTasks(e.ctx, len(sources), len(sources), false,
		func(ctx context.Context, i int) distinctSourceResult {
			local := e
			local.ctx = ctx
			local.rowOrders = nil
			return local.distinctSourcePrefix(sources[i], plan)
		},
		func(i int, result distinctSourceResult) bool { results[i] = result; return false })
	return results
}

// main's legacy waves fail on the first completed failure. Its balanced prefix
// checks failures in contiguous source order, stops at LIMIT, and cancels/joins
// the speculative suffix. Replenishment follows that same consumed prefix.
func runDistinctTasks[T any](ctx context.Context, count, parallel int, orderedStop bool, task func(context.Context, int) T, consume func(int, T) bool) {
	if count == 0 {
		return
	}
	if count == 1 {
		consume(0, task(ctx, 0))
		return
	}
	type outcome struct {
		index   int
		value   T
		failure any
	}
	local, cancel := context.WithCancel(ctx)
	done := make(chan outcome, count)
	active, nextTask := 0, 0
	launch := func(i int) {
		active++
		go func() {
			out := outcome{index: i}
			defer func() { out.failure = recover(); done <- out }()
			out.value = task(local, i)
		}()
	}
	defer func() {
		cancel()
		for active > 0 {
			<-done
			active--
		}
	}()
	for nextTask < min(count, max(1, parallel)) {
		launch(nextTask)
		nextTask++
	}
	pending := make([]*outcome, count)
	nextResult := 0
	for active > 0 {
		out := <-done
		active--
		pending[out.index] = &out
		if orderedStop {
			for nextResult < count && pending[nextResult] != nil {
				current := pending[nextResult]
				if current.failure != nil {
					panic(current.failure)
				}
				if consume(nextResult, current.value) {
					return
				}
				nextResult++
				if nextTask < count {
					launch(nextTask)
					nextTask++
				}
			}
		} else {
			if out.failure != nil {
				panic(out.failure)
			}
			if nextTask < count {
				launch(nextTask)
				nextTask++
			}
		}
	}
	if !orderedStop {
		for i, out := range pending {
			consume(i, out.value)
		}
	}
}
func (e evaluator) distinctMatchingIDs(source Graph, index *store.DistinctStringIndex, plan *indexedDistinctPlan) []int32 {
	selected := map[int32]bool{}
	for property := store.CallerClass; property <= store.CalleeName; property++ {
		atoms := []distinctStringAtom{}
		for _, atom := range plan.atoms {
			if p, ok := distinctCallSiteProperties[atom.property]; ok && p == int(property) {
				atoms = append(atoms, atom)
			}
		}
		if len(atoms) == 0 {
			continue
		}
		directory, err := index.Directory(e.ctx, property)
		failProjectionRead(err)
		for _, entry := range directory {
			e.check()
			value, err := source.Store.ProjectionString(e.ctx, entry.StringID)
			failProjectionRead(err)
			matched := false
			for _, atom := range atoms {
				if e.distinctAtomMatches(atom, value) {
					matched = true
					break
				}
			}
			if !matched {
				continue
			}
			ids, err := index.Postings(e.ctx, property, entry.StringID)
			failProjectionRead(err)
			for _, id := range ids {
				e.check()
				selected[id] = true
			}
		}
	}
	positions := make([]candidateNodePosition, 0, len(selected))
	for id := range selected {
		order, err := source.Store.ProjectionNodeOrder(e.ctx, id)
		failProjectionRead(err)
		positions = append(positions, candidateNodePosition{id, order})
	}
	sort.SliceStable(positions, func(a, b int) bool { return positions[a].offset < positions[b].offset })
	ids := make([]int32, len(positions))
	for i, p := range positions {
		ids[i] = p.id
	}
	return ids
}
func (e evaluator) distinctRawValues(source Graph, id int32, plan *indexedDistinctPlan) ([]int32, map[string]any) {
	sids := make([]int32, len(plan.properties))
	row := map[string]any{}
	for i, property := range plan.properties {
		e.check()
		sid := int32(-1)
		var value any
		if p, ok := distinctCallSiteProperties[property]; ok {
			var err error
			sid, err = source.Store.ProjectionStringID(e.ctx, id, store.CallSiteStringProperty(p))
			failProjectionRead(err)
			if sid >= 0 {
				value, err = source.Store.ProjectionString(e.ctx, sid)
				failProjectionRead(err)
			}
		} else if property == "graphId" {
			value = source.ID
		}
		sids[i] = sid
		row[plan.columns[i]] = value
	}
	addProvenance(row, source.ID)
	return sids, row
}
func (e evaluator) distinctSourcePrefix(source Graph, plan *indexedDistinctPlan) distinctSourceResult {
	e.check()
	index, available, err := source.Store.PrepareDistinctStringIndex(e.ctx, store.DistinctProjectionOptions{SourceCount: plan.sourceCount, Limit: plan.limit, PreferMappedView: plan.preferMappedView, CannotMatch: func() bool { return e.distinctCannotMatch(source, plan) }})
	failProjectionRead(err)
	if !available {
		fail("Distinct projection capability became unavailable")
	}
	rows := []distinctProjectedRow{}
	seenIDs := map[string]bool{}
	if index.Raw || index.ParallelRaw {
		rows = e.distinctRawRows(source, index, plan, nil, nil)
	} else {
		for _, id := range e.distinctMatchingIDs(source, index, plan) {
			sids, row := e.distinctRawValues(source, id, plan)
			values := make([]any, len(sids))
			for i, sid := range sids {
				values[i] = sid
			}
			k := key(values)
			if seenIDs[k] {
				continue
			}
			seenIDs[k] = true
			order, err := source.Store.ProjectionNodeOrder(e.ctx, id)
			failProjectionRead(err)
			rows = append(rows, distinctProjectedRow{order, row})
			if len(rows) >= plan.limit {
				break
			}
		}
	}
	rows = append(rows, e.distinctGenericRows(source, plan, nil)...)
	sort.SliceStable(rows, func(a, b int) bool { return rows[a].order < rows[b].order })
	state := distinctSourceResult{rows: []map[string]any{}, known: map[string]bool{}, index: index}
	byKey := map[string]map[string]any{}
	for _, item := range rows {
		k := distinctVisibleKey(item.row)
		if previous := byKey[k]; previous != nil {
			mergeProvenance(previous, item.row)
		} else if len(state.rows) < plan.limit {
			byKey[k] = item.row
			state.known[k] = true
			state.rows = append(state.rows, item.row)
		}
	}
	return state
}
func distinctGenericKindSupports(kind, property string) bool {
	switch kind {
	case "AnnotationNode":
		return property == "class" || property == "name" || property == "caller_class" || property == "caller_name" || property == "callee_class" || property == "callee_name"
	case "FieldNode":
		return property == "class" || property == "name"
	case "LocalVariable", "EnumConstant":
		return property == "name"
	}
	return false
}
func (e evaluator) distinctGenericRows(source Graph, plan *indexedDistinctPlan, selected map[string]bool) []distinctProjectedRow {
	if !plan.generic {
		return nil
	}
	positions := []candidateNodePosition{}
	for _, kind := range []string{"EnumConstant", "LocalVariable", "FieldNode", "AnnotationNode"} {
		needed := false
		for _, atom := range plan.atoms {
			if distinctGenericKindSupports(kind, atom.property) {
				needed = true
				break
			}
		}
		if !needed {
			continue
		}
		for _, id := range source.Store.NodesOfKind(kind) {
			order, err := source.Store.ProjectionNodeOrder(e.ctx, id)
			failProjectionRead(err)
			positions = append(positions, candidateNodePosition{id, order})
		}
	}
	sort.SliceStable(positions, func(a, b int) bool { return positions[a].offset < positions[b].offset })
	rows := []distinctProjectedRow{}
	seen := map[string]bool{}
	for _, position := range positions {
		e.check()
		node, err := source.Store.CandidateNode(e.ctx, position.id)
		failProjectionRead(err)
		matched := false
		for _, atom := range plan.atoms {
			if distinctGenericKindSupports(node.Kind, atom.property) && e.distinctAtomMatches(atom, NodeProperty(node, atom.property)) {
				matched = true
				break
			}
		}
		if !matched {
			continue
		}
		row := map[string]any{}
		for i, property := range plan.properties {
			var value any
			if property == "graphId" {
				value = source.ID
			} else {
				value = NodeProperty(node, property)
			}
			row[plan.columns[i]] = value
		}
		addProvenance(row, source.ID)
		k := distinctVisibleKey(row)
		if seen[k] || selected != nil && !selected[k] {
			continue
		}
		seen[k] = true
		rows = append(rows, distinctProjectedRow{position.offset, row})
		if len(rows) >= plan.limit {
			break
		}
	}
	return rows
}

// Selected-value provenance probes mirror main's exact-property anchor. They
// do not project all later hits before deciding whether those tuples matter.
func (e evaluator) distinctSourceHits(source Graph, plan *indexedDistinctPlan, selectedRows []map[string]any) map[string]bool {
	hits := map[string]bool{}
	selectedKeys := map[string]bool{}
	for _, row := range selectedRows {
		selectedKeys[distinctVisibleKey(row)] = true
	}
	// Pipeline storageSelectedValues removes tuples for other graph IDs and
	// non-string values before asking the storage projection capability. Generic
	// matches still run; an irrelevant raw source must not initialize an index.
	rawTargets := []map[string]any{}
	for _, target := range selectedRows {
		valid := true
		for i, property := range plan.properties {
			value := target[plan.columns[i]]
			if value != nil {
				if _, ok := value.(string); !ok {
					valid = false
					break
				}
			}
			if property == "graphId" && value != source.ID {
				valid = false
				break
			}
		}
		if valid {
			rawTargets = append(rawTargets, target)
		}
	}
	if len(rawTargets) == 0 {
		for _, generic := range e.distinctGenericRows(source, plan, selectedKeys) {
			hits[distinctVisibleKey(generic.row)] = true
		}
		return hits
	}
	selectedRows = rawTargets
	index, available, err := source.Store.PrepareDistinctStringIndex(e.ctx, store.DistinctProjectionOptions{SourceCount: plan.sourceCount, Limit: plan.limit, PreferMappedView: plan.preferMappedView, CannotMatch: func() bool { return e.distinctCannotMatch(source, plan) }})
	failProjectionRead(err)
	if !available {
		fail("Distinct projection capability became unavailable")
	}
	if index.Raw || index.ParallelRaw {
		for _, row := range e.distinctRawRows(source, index, plan, selectedKeys, selectedRows) {
			hits[distinctVisibleKey(row.row)] = true
		}
	} else {
		for _, target := range selectedRows {
			e.check()
			targetIDs := make([]int32, len(plan.properties))
			valid := true
			hasProperty := false
			var anchor []int32
			for i, property := range plan.properties {
				value := target[plan.columns[i]]
				p, raw := distinctCallSiteProperties[property]
				targetIDs[i] = -1
				if property == "graphId" {
					if value != source.ID {
						valid = false
					}
					continue
				}
				if !raw {
					if value != nil {
						valid = false
					}
					continue
				}
				hasProperty = true
				text, ok := value.(string)
				if !ok {
					valid = false
					break
				}
				sid := int32(-1)
				for j, s := range source.Store.Strings {
					if j&1023 == 0 {
						e.check()
					}
					if s == text {
						sid = int32(j)
						break
					}
				}
				if sid < 0 {
					valid = false
					break
				}
				targetIDs[i] = sid
				ids, err := index.Postings(e.ctx, store.CallSiteStringProperty(p), sid)
				failProjectionRead(err)
				if len(ids) > 0 && (anchor == nil || len(ids) < len(anchor)) {
					anchor = ids
				}
			}
			if !valid {
				continue
			}
			if hasProperty && anchor == nil {
				continue
			}
			if !hasProperty {
				anchor = e.distinctMatchingIDs(source, index, plan)
				sort.Slice(anchor, func(a, b int) bool { return anchor[a] < anchor[b] })
			}
			for _, id := range anchor {
				e.check()
				same := true
				for i, property := range plan.properties {
					if p, ok := distinctCallSiteProperties[property]; ok {
						sid, err := source.Store.ProjectionStringID(e.ctx, id, store.CallSiteStringProperty(p))
						failProjectionRead(err)
						if sid != targetIDs[i] {
							same = false
							break
						}
					}
				}
				if !same {
					continue
				}
				matched := !hasProperty
				if hasProperty {
					for _, atom := range plan.atoms {
						p, ok := distinctCallSiteProperties[atom.property]
						if !ok {
							continue
						}
						sid, err := source.Store.ProjectionStringID(e.ctx, id, store.CallSiteStringProperty(p))
						failProjectionRead(err)
						value, err := source.Store.ProjectionString(e.ctx, sid)
						failProjectionRead(err)
						if e.distinctAtomMatches(atom, value) {
							matched = true
							break
						}
					}
				}
				if matched {
					hits[distinctVisibleKey(target)] = true
					break
				}
			}
		}
	}
	for _, generic := range e.distinctGenericRows(source, plan, selectedKeys) {
		hits[distinctVisibleKey(generic.row)] = true
	}
	return hits
}

// Raw fallback is the storage serial/split path when no persisted or retained
// index is usable. It reads all four integer fields before evaluating predicates,
// but validates only SIDs actually used by a predicate or projected value.
func (e evaluator) distinctRawRange(source Graph, plan *indexedDistinctPlan, selected map[string]bool, nodeIDs []int32, exact map[int]map[int32]bool, selectedIDs map[string]bool) []distinctProjectedRow {
	rows := []distinctProjectedRow{}
	seen := map[string]bool{}
	for _, id := range nodeIDs {
		e.check()
		sids, err := source.Store.ProjectionStringIDs(e.ctx, id)
		failProjectionRead(err)
		matched := false
		for _, atom := range plan.atoms {
			p, ok := distinctCallSiteProperties[atom.property]
			if !ok {
				continue
			}
			sid := sids[p]
			if exact != nil {
				if exact[p][sid] {
					matched = true
					break
				}
				continue
			}
			if sid < 0 || int64(sid) >= int64(len(source.Store.Strings)) {
				functionError("ArrayIndexOutOfBoundsException", fmt.Sprintf("Index %d out of bounds for length %d", sid, len(source.Store.Strings)))
			}
			value, err := source.Store.ProjectionString(e.ctx, sid)
			failProjectionRead(err)
			if e.distinctAtomMatches(atom, value) {
				matched = true
				break
			}
		}
		if !matched {
			continue
		}
		if selectedIDs != nil {
			values := make([]any, len(plan.properties))
			for i, p := range plan.properties {
				if raw, ok := distinctCallSiteProperties[p]; ok {
					values[i] = sids[raw]
				} else {
					values[i] = int32(-1)
				}
			}
			if !selectedIDs[key(values)] {
				continue
			}
		}
		row := map[string]any{}
		for i, property := range plan.properties {
			var value any
			if p, ok := distinctCallSiteProperties[property]; ok {
				value, err = source.Store.ProjectionString(e.ctx, sids[p])
				failProjectionRead(err)
			} else if property == "graphId" {
				value = source.ID
			}
			row[plan.columns[i]] = value
		}
		addProvenance(row, source.ID)
		k := distinctVisibleKey(row)
		if seen[k] || selected != nil && !selected[k] {
			continue
		}
		seen[k] = true
		order, err := source.Store.ProjectionNodeOrder(e.ctx, id)
		failProjectionRead(err)
		rows = append(rows, distinctProjectedRow{order, row})
		target := plan.limit
		if selected != nil {
			target = min(target, len(selected))
		}
		if len(rows) >= target {
			break
		}
	}
	return rows
}

func (e evaluator) distinctCannotMatch(source Graph, plan *indexedDistinctPlan) bool {
	if len(source.Store.NodesOfKind("CallSiteNode")) < 4096 {
		return false
	}
	rawAtoms := []distinctStringAtom{}
	for _, atom := range plan.atoms {
		if _, ok := distinctCallSiteProperties[atom.property]; ok {
			if atom.op != "CONTAINS" || len(javaUTF16(atom.term)) < 16 {
				return false
			}
			rawAtoms = append(rawAtoms, atom)
		}
	}
	for _, atom := range rawAtoms {
		for _, value := range source.Store.Strings {
			e.check()
			if e.distinctAtomMatches(atom, value) {
				return false
			}
		}
	}
	return true
}

func (e evaluator) distinctRawRows(source Graph, index *store.DistinctStringIndex, plan *indexedDistinctPlan, selected map[string]bool, targets []map[string]any) []distinctProjectedRow {
	ids := source.Store.NodesOfKind("CallSiteNode")
	if !index.ParallelRaw {
		return e.distinctRawRange(source, plan, selected, ids, nil, nil)
	}
	var exact map[int]map[int32]bool
	if !index.Raw {
		exact = map[int]map[int32]bool{}
		for p := store.CallerClass; p <= store.CalleeName; p++ {
			set := map[int32]bool{}
			exact[int(p)] = set
			atoms := []distinctStringAtom{}
			for _, atom := range plan.atoms {
				if prop, ok := distinctCallSiteProperties[atom.property]; ok && prop == int(p) {
					atoms = append(atoms, atom)
				}
			}
			if len(atoms) == 0 {
				continue
			}
			directory, err := index.Directory(e.ctx, p)
			failProjectionRead(err)
			for _, entry := range directory {
				value, err := source.Store.ProjectionString(e.ctx, entry.StringID)
				failProjectionRead(err)
				for _, atom := range atoms {
					if e.distinctAtomMatches(atom, value) {
						set[entry.StringID] = true
						break
					}
				}
			}
		}
		anyMatch := false
		for _, set := range exact {
			anyMatch = anyMatch || len(set) > 0
		}
		if !anyMatch {
			return nil
		}
	}
	var selectedIDs map[string]bool
	if selected != nil {
		selectedIDs = map[string]bool{}
		for _, target := range targets {
			values := make([]any, len(plan.properties))
			valid := true
			for i, property := range plan.properties {
				value := target[plan.columns[i]]
				p, raw := distinctCallSiteProperties[property]
				if !raw {
					values[i] = int32(-1)
					if property == "graphId" {
						valid = valid && value == source.ID
					} else {
						valid = valid && value == nil
					}
					continue
				}
				text, ok := value.(string)
				if !ok {
					valid = false
					break
				}
				sid := int32(-1)
				for j, s := range source.Store.Strings {
					e.check()
					if s == text {
						sid = int32(j)
						break
					}
				}
				if sid < 0 {
					valid = false
					break
				}
				if !index.Raw {
					postings, err := index.Postings(e.ctx, store.CallSiteStringProperty(p), sid)
					failProjectionRead(err)
					if len(postings) == 0 {
						valid = false
						break
					}
				}
				values[i] = sid
			}
			if valid {
				selectedIDs[key(values)] = true
			}
		}
		if len(selectedIDs) == 0 {
			return nil
		}
	}
	workers := min(len(ids), max(0, runtime.NumCPU()-max(1, runtime.NumCPU()/2))+1)
	chunk := (len(ids) + workers - 1) / workers
	type outcome struct {
		index   int
		rows    []distinctProjectedRow
		failure any
	}
	done := make(chan outcome, workers)
	ctx, cancel := context.WithCancel(e.ctx)
	defer cancel()
	task := func(i int) (out outcome) {
		out.index = i
		defer func() {
			out.failure = recover()
			if out.failure != nil {
				cancel()
			}
		}()
		local := e
		local.ctx = ctx
		out.rows = local.distinctRawRange(source, plan, selected, ids[i*chunk:min(len(ids), (i+1)*chunk)], exact, selectedIDs)
		return
	}
	count := (len(ids) + chunk - 1) / chunk
	for i := 1; i < count; i++ {
		go func(i int) { done <- task(i) }(i)
	}
	first := task(0)
	results := make([][]distinctProjectedRow, count)
	results[0] = first.rows
	failure := first.failure
	isCancel := func(v any) bool {
		err, ok := v.(error)
		return ok && (errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded))
	}
	for i := 1; i < count; i++ {
		out := <-done
		results[out.index] = out.rows
		if out.failure != nil && (failure == nil || isCancel(failure) && !isCancel(out.failure)) {
			failure = out.failure
		}
	}
	if failure != nil {
		panic(failure)
	}
	rows := []distinctProjectedRow{}
	seen := map[string]bool{}
	limit := plan.limit
	if selected != nil {
		limit = min(limit, len(selectedIDs))
	}
	for _, part := range results {
		for _, row := range part {
			k := distinctVisibleKey(row.row)
			if seen[k] {
				continue
			}
			seen[k] = true
			rows = append(rows, row)
			if len(rows) >= limit {
				return rows
			}
		}
	}
	return rows
}
