package query

import (
	"context"
	"runtime"

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
	p := &ordinaryProjectionPlan{indexedDistinctPlan: compiled, projection: r, direct: true, leading: true, scoped: e.sourceScopeApplied || compiled.graphIDs != nil}
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
	serial := !parallel || e.workTrackingEnabled && !balanced
	mayBatch := balanced && (!plan.scoped || parallel)
	if balanced {
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
			// Main observes every source's prepared capability only after the
			// leading source has proved that the query needs its suffix.
			prepared := mayBatch && mainPreparedWide(plan, sources)
			task := func(ctx context.Context, i int) []map[string]any {
				local := e
				local.ctx = ctx
				local.rowOrders = nil
				taskPlan := *plan
				taskPlan.parallelProjection = true
				return local.ordinarySourceRows(sources[i+1], &taskPlan, plan.limit)
			}
			consume := func(i int, rows []map[string]any) bool {
				remaining := plan.limit - len(result.Rows)
				result.Rows = append(result.Rows, rows[:min(remaining, len(rows))]...)
				return len(result.Rows) >= plan.limit
			}
			workers := max(1, runtime.NumCPU()/2)
			if prepared {
				runMainFixedWorkersInOrderUntil(e.ctx, len(sources)-1, workers, task, consume)
			} else {
				runDistinctTasks(e.ctx, len(sources)-1, workers, true, task, consume)
			}
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

func mainPreparedWide(plan *ordinaryProjectionPlan, sources []Graph) bool {
	if len(sources) < 40 || len(plan.atoms) == 0 {
		return false
	}
	for _, atom := range plan.atoms {
		if _, supported := distinctCallSiteProperties[atom.property]; !supported {
			return false
		}
	}
	for _, source := range sources {
		if source.Store == nil {
			return false
		}
		prepared, err := source.Store.MainPreparedStringScan(plan.generic)
		failMainStringRead(err)
		if !prepared {
			return false
		}
	}
	return true
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
	if limit <= 0 {
		return []map[string]any{}
	}
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
	if hit {
		e.consume(int64(max(len(values), 1)))
	} else {
		next := e.mainIndexNodeIDs(source, index, plan.mainSourceSpec(), limit)
		values = [][]string{}
		for {
			id, present := next(e.ctx)
			if !present {
				break
			}
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
		// Project each yielded ID before resuming the source. A projection
		// failure must not read/charge later IDs or complete the node cache.
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
	defer ordinarySourceFailure()
	next := e.mainCandidateIterator(source, plan.mainSourceSpec(), limit)
	return func() (store.Node, bool) { defer ordinarySourceFailure(); return next(e.ctx) }
}

func ordinarySourceFailure() {
	if value := recover(); value != nil {
		if err, ok := value.(error); ok && err == store.ErrStoreClosed {
			failProjectionRead(err)
		}
		panic(value)
	}
}
