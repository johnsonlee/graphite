package query

import (
	"context"
	"runtime"
	"sort"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

type ordinaryProjectionPlan struct {
	*indexedDistinctPlan
	projection         cypher.ProjectionClause
	direct, leading    bool
	scoped             bool
	forcePersisted     bool
	parallelProjection bool
}

func (e evaluator) compileOrdinaryProjection(branch cypher.SingleQuery) *ordinaryProjectionPlan {
	if len(branch.Clauses) != 2 {
		return nil
	}
	r, ok := branch.Clauses[1].(cypher.ProjectionClause)
	if !ok || r.Distinct || r.With || r.All || r.Skip != nil || len(r.OrderBy) != 0 {
		return nil
	}
	for _, item := range r.Items {
		if containsAggregate(item.Expression) {
			return nil
		}
	}
	m, ok := branch.Clauses[0].(cypher.MatchClause)
	if !ok || len(m.Patterns) != 1 || len(m.Patterns[0].Nodes) != 1 {
		return nil
	}
	variable := m.Patterns[0].Nodes[0].Variable
	// Reuse the proven WHERE/graph-route compiler while keeping the real RETURN
	// projection separate. The synthesized property is an AST-only capability probe.
	probe := r
	probe.Distinct = true
	probe.Items = []cypher.ReturnItem{{Expression: cypher.Property{Object: cypher.Variable{Name: variable}, Key: "caller_name"}}}
	compiled := e.compileIndexedDistinct(cypher.SingleQuery{Clauses: []cypher.Clause{m, probe}})
	if compiled == nil {
		return nil
	}
	for _, atom := range compiled.atoms {
		if _, ok := distinctCallSiteProperties[atom.property]; !ok {
			return nil
		}
	}
	p := &ordinaryProjectionPlan{indexedDistinctPlan: compiled, projection: r, direct: true, leading: true, scoped: compiled.graphIDs != nil}
	p.columns = nil
	p.properties = nil
	for _, item := range r.Items {
		name := item.Alias
		if name == "" {
			name = columnName(item.Expression)
		}
		p.columns = append(p.columns, name)
		property, ok := distinctProperty(item.Expression, variable)
		if !ok {
			p.direct = false
			p.leading = false
			p.properties = append(p.properties, "")
			continue
		}
		p.properties = append(p.properties, property)
		if _, raw := distinctCallSiteProperties[property]; !raw {
			p.direct = false
			if property != "graphId" {
				p.leading = false
			}
		}
	}
	return p
}
func (e evaluator) ordinaryProjection(graph *store.Store, branch cypher.SingleQuery) (Result, bool) {
	if !e.cross && graph == nil {
		return Result{}, false
	}
	plan := e.compileOrdinaryProjection(branch)
	if plan == nil {
		return Result{}, false
	}
	sources := e.graphs
	if !e.cross {
		sources = []Graph{{"single", graph}}
	}
	selected := []Graph{}
	for _, source := range sources {
		if plan.graphIDs != nil && !plan.graphIDs[source.ID] {
			continue
		}
		if source.Store == nil || source.Store.Mode != "MAPPED" {
			return Result{}, false
		}
		selected = append(selected, source)
	}
	sources = selected
	plan.sourceCount = len(sources)
	result := Result{Columns: plan.columns, Rows: []map[string]any{}}
	if len(sources) == 0 {
		return result, true
	}
	eligible := plan.direct && (len(sources) == 1 || plan.scoped)
	for _, source := range sources {
		if plan.generic && len(source.Store.NodesOfKind("AnnotationNode")) != 0 {
			eligible = false
		}
	}
	if eligible {
		indexes := make([]*store.DistinctStringIndex, len(sources))
		all := true
		for i, source := range sources {
			index, ok, err := source.Store.RetainedProjectionIndex(e.ctx)
			failProjectionRead(err)
			if !ok {
				all = false
				break
			}
			indexes[i] = index
		}
		if all {
			for i, source := range sources {
				result.Rows = append(result.Rows, e.ordinaryIndexedRows(source, indexes[i], plan, plan.limit-len(result.Rows))...)
				if len(result.Rows) >= plan.limit {
					break
				}
			}
			return e.ordinaryBindResult(result, plan), true
		}
	}
	parallel := e.cross && len(sources) > 1
	for _, item := range plan.projection.Items {
		switch x := item.Expression.(type) {
		case cypher.Literal:
		case cypher.Property:
			if v, ok := x.Object.(cypher.Variable); !ok || v.Name != plan.variable {
				parallel = false
			}
		default:
			parallel = false
		}
	}
	prefersSerial := true
	allRetained := true
	for _, source := range sources {
		index, ok, err := source.Store.RetainedProjectionIndex(e.ctx)
		failProjectionRead(err)
		if !ok {
			allRetained = false
			prefersSerial = false
			continue
		}
		yes, err := index.PrefersSerialProjectionScan(e.ctx)
		failProjectionRead(err)
		prefersSerial = prefersSerial && yes
		if plan.generic && len(source.Store.NodesOfKind("AnnotationNode")) != 0 {
			prefersSerial = false
		}
	}
	parallel = parallel && !prefersSerial
	balanced := len(sources) >= 40
	rawLeading := balanced && ordinaryRawLeading(plan)
	var leading []map[string]any
	projectedLeading := false
	if balanced {
		leading, projectedLeading = e.ordinaryLeadingRows(sources[0], plan, rawLeading)
		if projectedLeading && len(leading) >= plan.limit {
			result.Rows = leading[:plan.limit]
			return e.ordinaryBindResult(result, plan), true
		}
	}
	serial := !parallel
	if balanced {
		mayBatch := !plan.scoped || parallel
		serial = plan.scoped && allRetained || !rawLeading && !mayBatch && !parallel
	}
	if serial {
		for _, source := range sources {
			result.Rows = append(result.Rows, e.ordinarySourceRows(source, plan, plan.limit-len(result.Rows))...)
			if len(result.Rows) >= plan.limit {
				break
			}
		}
	} else if balanced {
		if projectedLeading {
			result.Rows = leading
		} else {
			firstPlan := *plan
			firstPlan.forcePersisted = rawLeading
			result.Rows = e.ordinarySourceRows(sources[0], &firstPlan, plan.limit)
		}
		if len(result.Rows) < plan.limit {
			runDistinctTasks(e.ctx, len(sources)-1, max(1, runtime.NumCPU()/2), true,
				func(ctx context.Context, i int) []map[string]any {
					local := e
					local.ctx = ctx
					local.rowOrders = nil
					taskPlan := *plan
					taskPlan.parallelProjection = true
					return local.ordinarySourceRows(sources[i+1], &taskPlan, plan.limit)
				},
				func(i int, rows []map[string]any) bool {
					remaining := plan.limit - len(result.Rows)
					result.Rows = append(result.Rows, rows[:min(remaining, len(rows))]...)
					return len(result.Rows) >= plan.limit
				})
		}
	} else {
		workers := min(len(sources), runtime.NumCPU(), 8)
		for start := 0; start < len(sources) && len(result.Rows) < plan.limit; start += workers {
			end := min(len(sources), start+workers)
			runDistinctTasks(e.ctx, end-start, workers, false, func(ctx context.Context, i int) []map[string]any {
				local := e
				local.ctx = ctx
				local.rowOrders = nil
				taskPlan := *plan
				taskPlan.parallelProjection = true
				return local.ordinarySourceRows(sources[start+i], &taskPlan, plan.limit)
			}, func(i int, rows []map[string]any) bool {
				remaining := plan.limit - len(result.Rows)
				result.Rows = append(result.Rows, rows[:min(remaining, len(rows))]...)
				return false
			})
		}
	}
	return e.ordinaryBindResult(result, plan), true
}
func (e evaluator) ordinaryBindResult(result Result, plan *ordinaryProjectionPlan) Result {
	for i, row := range result.Rows {
		ordered := map[string]any{}
		for _, column := range plan.columns {
			e.bind(ordered, column, row[column])
		}
		addProvenance(ordered, provenance(row)...)
		result.Rows[i] = ordered
	}
	return result
}
func (e evaluator) ordinaryIndexedRows(source Graph, index *store.DistinctStringIndex, plan *ordinaryProjectionPlan, limit int) []map[string]any {
	storagePlan := *plan
	storagePlan.indexedDistinctPlan = &indexedDistinctPlan{}
	*storagePlan.indexedDistinctPlan = *plan.indexedDistinctPlan
	storagePlan.properties = nil
	for _, property := range plan.properties {
		if property != "graphId" {
			storagePlan.properties = append(storagePlan.properties, property)
		}
	}
	cacheKey := ordinaryRowsKey(&storagePlan, limit)
	values, hit, err := index.ProjectionCachedRows(e.ctx, cacheKey)
	failProjectionRead(err)
	if !hit {
		ids := e.ordinaryLimitedIndexIDs(source, index, plan, limit)
		values = make([][]string, 0, len(ids))
		for _, id := range ids {
			row := []string{}
			for _, property := range storagePlan.properties {
				p := distinctCallSiteProperties[property]
				sid, err := source.Store.ProjectionStringID(e.ctx, id, store.CallSiteStringProperty(p))
				failProjectionRead(err)
				value, err := source.Store.ProjectionString(e.ctx, sid)
				failProjectionRead(err)
				row = append(row, value)
			}
			values = append(values, row)
		}
		e.ordinaryCacheNodes(index, plan, limit, ids)
		e.ordinaryCacheRows(index, &storagePlan, limit, values)
	}
	rows := make([]map[string]any, 0, len(values))
	for _, values := range values {
		row := map[string]any{}
		position := 0
		for i, property := range plan.properties {
			if property == "graphId" {
				row[plan.columns[i]] = source.ID
			} else {
				row[plan.columns[i]] = values[position]
				position++
			}
		}
		addProvenance(row, source.ID)
		rows = append(rows, row)
	}
	return rows
}

type ordinaryNodeIterator func() (store.Node, bool)

func (e evaluator) ordinarySourceRows(source Graph, plan *ordinaryProjectionPlan, limit int) []map[string]any {
	rows := []map[string]any{}
	next := e.ordinaryCandidateIterator(source, plan, limit)
	for len(rows) < limit {
		node, ok := next()
		if !ok {
			break
		}
		bindings := map[string]any{}
		e.bind(bindings, plan.variable, e.nodeValue(source.Store, source.ID, node))
		if e.cross {
			addProvenance(bindings, source.ID)
		}
		if plan.parallelProjection {
			row := map[string]any{}
			for i, item := range plan.projection.Items {
				var value any
				switch expression := item.Expression.(type) {
				case cypher.Literal:
					value = expression.Value
				case cypher.Property:
					expression.Object = cypher.Variable{Name: plan.variable}
					value = e.eval(expression, bindings)
				default:
					functionError("IllegalStateException", "Unsafe expression reached parallel string projection")
				}
				e.bind(row, plan.columns[i], value)
			}
			addProvenance(row, source.ID)
			rows = append(rows, row)
			continue
		}
		projection := plan.projection
		projection.Limit = nil
		projected, _ := e.project([]map[string]any{bindings}, projection)
		rows = append(rows, projected...)
	}
	return rows
}
func (e evaluator) ordinaryCandidateIterator(source Graph, plan *ordinaryProjectionPlan, limit int) ordinaryNodeIterator {
	index, retained, err := source.Store.RetainedProjectionIndex(e.ctx)
	failProjectionRead(err)
	raw := false
	var ids []int32
	candidatesPrepared := false
	if !retained {
		forcePersisted := plan.forcePersisted || plan.scoped && plan.sourceCount < 40
		if forcePersisted {
			prepared, err := source.Store.PreparedProjectionFile(e.ctx)
			failProjectionRead(err)
			if prepared {
				index, _, err = source.Store.PrepareDistinctStringIndex(e.ctx, store.DistinctProjectionOptions{SourceCount: 1, Limit: limit})
				failProjectionRead(err)
				raw = index.Raw
			} else {
				raw = true
			}
		} else if plan.sourceCount >= 40 {
			view, ok, err := source.Store.PrepareDistinctStringIndex(e.ctx, store.DistinctProjectionOptions{SourceCount: 40, Limit: limit, InitializeMappedView: true})
			failProjectionRead(err)
			var exact []map[int32]bool
			mappedExact := false
			if ok {
				exact, mappedExact = e.ordinaryExactMatches(source, view, plan)
			}
			if mappedExact && !ordinaryAnyExact(exact) {
				ids = []int32{}
				candidatesPrepared = true
			} else if mappedExact && ordinarySharedMatcher(plan) {
				ids = e.ordinaryMappedIDs(source, view, plan, exact)
				candidatesPrepared = true
			} else {
				waves := mappedExact && e.ordinaryExactCanFill(view, plan, exact, limit)
				ids, candidatesPrepared = e.ordinaryParallelCandidates(source, plan, limit, exact, waves)
				if !candidatesPrepared {
					index, _, err = source.Store.PrepareDistinctStringIndex(e.ctx, store.DistinctProjectionOptions{SourceCount: 40, Limit: limit, SkipPreparedPreference: true})
					failProjectionRead(err)
					raw = index.Raw
				}
			}
		} else if plan.sourceCount > 1 {
			raw = true
		} else {
			ids, candidatesPrepared = e.ordinaryParallelCandidates(source, plan, limit, nil, false)
			if !candidatesPrepared {
				index, _, err = source.Store.PrepareDistinctStringIndex(e.ctx, store.DistinctProjectionOptions{SourceCount: 1, Limit: limit, SkipPreparedPreference: true, CannotMatch: func() bool { return e.distinctCannotMatch(source, plan.indexedDistinctPlan) }})
				failProjectionRead(err)
				raw = index.Raw
			}
		}
	}
	if !candidatesPrepared {
		if raw {
			ids = source.Store.NodesOfKind("CallSiteNode")
		} else {
			ids = e.ordinaryLimitedIndexIDs(source, index, plan, limit)
		}
	}
	position := 0
	callsite := func() (store.Node, bool) {
		for position < len(ids) {
			e.check()
			id := ids[position]
			position++
			if raw {
				sids, err := source.Store.ProjectionStringIDs(e.ctx, id)
				failProjectionRead(err)
				matched := false
				for _, atom := range plan.atoms {
					p := distinctCallSiteProperties[atom.property]
					var text string
					var err error
					if len(source.Store.Strings) <= 1<<16 {
						text, err = source.Store.ProjectionArrayString(e.ctx, sids[p])
					} else {
						text, err = source.Store.ProjectionString(e.ctx, sids[p])
					}
					failProjectionRead(err)
					if e.distinctAtomMatches(atom, text) {
						matched = true
						break
					}
				}
				if !matched {
					continue
				}
			}
			node, present, err := source.Store.ProjectionCandidateNode(e.ctx, id)
			failProjectionRead(err)
			if !present {
				continue
			}
			if node.Kind != "CallSiteNode" && !raw {
				if candidatesPrepared {
					functionError("ClassCastException", "Cannot cast io.johnsonlee.graphite.core."+node.Kind+" to io.johnsonlee.graphite.core.CallSiteNode")
				}
				continue
			}
			if plan.generic {
				_, err := source.Store.ProjectionNodeOrder(e.ctx, node.ID)
				failProjectionRead(err)
			}
			return node, true
		}
		if index != nil && !raw && !candidatesPrepared {
			e.ordinaryCacheNodes(index, plan, limit, ids)
		}
		return store.Node{}, false
	}
	if !plan.generic || len(source.Store.NodesOfKind("AnnotationNode")) == 0 {
		return callsite
	}
	annotationIDs := source.Store.NodesOfKind("AnnotationNode")
	ai := 0
	annotation := func() (store.Node, bool) {
		for ai < len(annotationIDs) {
			id := annotationIDs[ai]
			ai++
			node, err := source.Store.CandidateNode(e.ctx, id)
			failProjectionRead(err)
			for _, atom := range plan.atoms {
				if e.distinctAtomMatches(atom, NodeProperty(node, atom.property)) {
					return node, true
				}
			}
		}
		return store.Node{}, false
	}
	// main's sequence merge obtains one node from each source before choosing the
	// earliest offset. A later-position first candidate can therefore throw first.
	iterators := []ordinaryNodeIterator{callsite, annotation}
	heads := make([]store.Node, len(iterators))
	present := make([]bool, len(iterators))
	orders := make([]int64, len(iterators))
	initialized := false
	advance := -1
	return func() (store.Node, bool) {
		if !initialized {
			for i, next := range iterators {
				heads[i], present[i] = next()
				if present[i] {
					order, err := source.Store.ProjectionNodeOrder(e.ctx, heads[i].ID)
					failProjectionRead(err)
					orders[i] = order
				}
			}
			initialized = true
		}
		if advance >= 0 {
			heads[advance], present[advance] = iterators[advance]()
			if present[advance] {
				order, err := source.Store.ProjectionNodeOrder(e.ctx, heads[advance].ID)
				failProjectionRead(err)
				orders[advance] = order
			}
			advance = -1
		}
		available := []int{}
		for i, yes := range present {
			if yes {
				available = append(available, i)
			}
		}
		if len(available) == 0 {
			return store.Node{}, false
		}
		sort.SliceStable(available, func(a, b int) bool { return orders[available[a]] < orders[available[b]] })
		advance = available[0]
		return heads[advance], true
	}
}
