package query

import (
	"context"
	"math"
	"runtime"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// The generic direct-string path accepts arbitrary direct properties and literals.
// Its bounded row storage does not select a different set of node candidates.
// Internal differential-test switch; never inferred from query text.
type genericDistinctDisabledKey struct{}

type genericDistinctPlan struct {
	match                 cypher.MatchClause
	projection            cypher.ProjectionClause
	atoms                 []distinctStringAtom
	columns, unique       []string
	itemSlots, finalItems []int
	limit, skip           int
	graphIDs              map[string]bool
}

func (e evaluator) compileGenericDistinct(branch cypher.SingleQuery) *genericDistinctPlan {
	if e.ctx.Value(genericDistinctDisabledKey{}) == true {
		return nil
	}
	if len(branch.Clauses) != 2 {
		return nil
	}
	m, ok := branch.Clauses[0].(cypher.MatchClause)
	if !ok || m.Optional || m.Where == nil || len(m.Patterns) != 1 {
		return nil
	}
	r, ok := branch.Clauses[1].(cypher.ProjectionClause)
	if !ok || !r.Distinct || r.With || r.All || r.Where != nil || len(r.OrderBy) != 0 {
		return nil
	}
	pattern := m.Patterns[0]
	if pattern.PathVariable != "" || len(pattern.Relationships) != 0 || len(pattern.Nodes) != 1 {
		return nil
	}
	node := pattern.Nodes[0]
	if node.Variable == "" || len(node.Labels) > 1 || len(node.Properties) != 0 {
		return nil
	}
	limit, literal := plannerLiteralCount(r.Limit)
	if !literal || limit <= 0 {
		return nil
	}
	skip, _ := plannerLiteralCount(r.Skip)
	if skip < 0 || int64(skip)+int64(limit) > math.MaxInt32 {
		return nil
	}
	p := &genericDistinctPlan{match: m, projection: r, limit: int(limit) + int(skip), skip: int(skip)}
	positions := map[string]int{}
	for i, item := range r.Items {
		switch expr := item.Expression.(type) {
		case cypher.Literal:
		case cypher.Property:
			owner, ok := expr.Object.(cypher.Variable)
			if !ok || owner.Name != node.Variable {
				return nil
			}
		default:
			return nil
		}
		column := item.Alias
		if column == "" {
			column = columnName(item.Expression)
		}
		slot, found := positions[column]
		if !found {
			slot = len(p.unique)
			positions[column] = slot
			p.unique = append(p.unique, column)
			p.finalItems = append(p.finalItems, i)
		} else {
			p.finalItems[slot] = i
		}
		p.columns = append(p.columns, column)
		p.itemSlots = append(p.itemSlots, slot)
	}
	condition := m.Where
	if r.Skip == nil {
		condition, p.graphIDs = e.distinctGraphRoute(condition, node.Variable)
	}
	atoms, ok := e.compileDistinctDisjunction(condition, node.Variable)
	if !ok {
		return nil
	}
	p.atoms = atoms
	return p
}

// The producer advances only after next requests another value. In particular,
// retaining LIMIT cannot decode an unsolicited following node. Close cancels
// and joins the producer, including exceptional consumer exits.
type genericDistinctCursor struct {
	request chan struct{}
	values  chan genericDistinctValue
	done    chan struct{}
	cancel  context.CancelFunc
}
type genericDistinctValue struct {
	value   any
	failure any
	end     bool
}

func newGenericDistinctCursor(e evaluator, source Graph, p *genericDistinctPlan) *genericDistinctCursor {
	return newGenericValueCursor(e.ctx, func(ctx context.Context, yield func(any)) {
		e.ctx, e.graphs, e.rowOrders, e.indexFirst = ctx, []Graph{source}, nil, true
		slot := &candidateSlot{}
		walk := e.indexedNodeWalker(source.Store, p.match, slot)
		if walk == nil {
			walk = func(accept func(any)) {
				for _, id := range source.Store.QueryNodeIDs() {
					e.check()
					node, err := source.Store.CandidateNode(ctx, id)
					if err != nil {
						candidatePreparationError(err)
						failNodeRead(err)
					}
					slot.graph, slot.graphID, slot.qualified = source.Store, source.ID, e.cross
					slot.isMethod, slot.node = false, node
					accept(slot)
				}
			}
		}
		walk(func(value any) {
			e.check()
			if e.matches(value, p.match.Patterns[0].Nodes[0], nil) {
				yield(value)
			}
		})
	})
}

func newGenericValueCursor(parent context.Context, produce func(context.Context, func(any))) *genericDistinctCursor {
	ctx, cancel := context.WithCancel(parent)
	c := &genericDistinctCursor{make(chan struct{}), make(chan genericDistinctValue), make(chan struct{}), cancel}
	go func() {
		defer close(c.done)
		send := func(out genericDistinctValue) {
			select {
			case c.values <- out:
			case <-ctx.Done():
			}
		}
		defer func() {
			if failure := recover(); failure != nil {
				send(genericDistinctValue{failure: failure})
			}
		}()
		await := func() {
			select {
			case <-c.request:
			case <-ctx.Done():
				panic(ctx.Err())
			}
		}
		await()
		produce(ctx, func(value any) { send(genericDistinctValue{value: value}); await() })
		send(genericDistinctValue{end: true})
	}()
	return c
}
func (c *genericDistinctCursor) next(ctx context.Context) (any, bool) {
	if err := ctx.Err(); err != nil {
		panic(err)
	}
	select {
	case c.request <- struct{}{}:
	case <-ctx.Done():
		panic(ctx.Err())
	case <-c.done:
		if err := ctx.Err(); err != nil {
			panic(err)
		}
		return nil, false
	}
	select {
	case out := <-c.values:
		if err := ctx.Err(); err != nil {
			panic(err)
		}
		if out.failure != nil {
			panic(out.failure)
		}
		return out.value, !out.end
	case <-ctx.Done():
		panic(ctx.Err())
	}
}
func (c *genericDistinctCursor) close() { c.cancel(); <-c.done }

type genericDistinctRows struct {
	values [][]any
	rows   []map[string]any
	hashes map[uint64][]int
	cypher map[string]int
	typed  bool
}

func newGenericDistinctRows(typed bool) *genericDistinctRows {
	return &genericDistinctRows{hashes: map[uint64][]int{}, cypher: map[string]int{}, typed: typed}
}
func (r *genericDistinctRows) find(values []any) (int, bool) {
	if !r.typed {
		i, ok := r.cypher[key(values)]
		return i, ok
	}
	h := genericJavaHash(values)
	for _, i := range r.hashes[h] {
		if genericJavaEqual(r.values[i], values) {
			return i, true
		}
	}
	return 0, false
}
func (r *genericDistinctRows) add(values []any, row map[string]any) {
	i := len(r.values)
	r.values = append(r.values, append([]any(nil), values...))
	r.rows = append(r.rows, row)
	if r.typed {
		h := genericJavaHash(values)
		r.hashes[h] = append(r.hashes[h], i)
	} else {
		r.cypher[key(values)] = i
	}
}

type genericDistinctScanner struct {
	e         evaluator
	source    Graph
	plan      *genericDistinctPlan
	cursor    *genericDistinctCursor
	scratch   []any
	local     *genericDistinctRows
	exhausted bool
}

func (s *genericDistinctScanner) next(ctx context.Context, selected bool) ([]any, bool) {
	local := s.e
	local.ctx = ctx
	if s.cursor == nil {
		s.cursor = newGenericDistinctCursor(s.e, s.source, s.plan)
	}
	for !s.exhausted {
		value, ok := s.cursor.next(ctx)
		if !ok {
			s.exhausted = true
			return nil, false
		}
		matched := false
		for _, atom := range s.plan.atoms {
			if local.distinctAtomMatches(atom, value.(*candidateSlot).property(atom.property)) {
				matched = true
				break
			}
		}
		if !matched {
			continue
		}
		// The SKIP streaming path evaluates its complete WHERE after direct candidates.
		if s.plan.projection.Skip != nil && local.eval(s.plan.match.Where, map[string]any{s.plan.match.Patterns[0].Nodes[0].Variable: value}) != true {
			continue
		}
		get := func(item int) any {
			local.check()
			switch x := s.plan.projection.Items[item].Expression.(type) {
			case cypher.Literal:
				return x.Value
			case cypher.Property:
				return value.(*candidateSlot).property(x.Key)
			}
			panic("invalid direct projection")
		}
		if selected {
			for slot, item := range s.plan.finalItems {
				s.scratch[slot] = get(item)
			}
		} else {
			for i := range s.plan.projection.Items {
				s.scratch[s.plan.itemSlots[i]] = get(i)
			}
		}
		return s.scratch, true
	}
	return nil, false
}
func (s *genericDistinctScanner) row(values []any) map[string]any {
	row := map[string]any{}
	for i, column := range s.plan.unique {
		s.e.bind(row, column, values[i])
	}
	if s.e.cross {
		addProvenance(row, s.source.ID)
	}
	return row
}
func (s *genericDistinctScanner) batch(ctx context.Context) []map[string]any {
	rows := []map[string]any{}
	for len(rows) < s.plan.limit {
		values, ok := s.next(ctx, false)
		if !ok {
			break
		}
		if _, found := s.local.find(values); found {
			continue
		}
		row := s.row(values)
		s.local.add(values, row)
		rows = append(rows, row)
	}
	return rows
}
func (s *genericDistinctScanner) remaining(ctx context.Context, selected *genericDistinctRows) map[int]bool {
	hits := map[int]bool{}
	for {
		values, ok := s.next(ctx, true)
		if !ok {
			return hits
		}
		if i, found := selected.find(values); found {
			hits[i] = true
		}
	}
}
func (e evaluator) genericDistinct(graph *store.Store, branch cypher.SingleQuery) (Result, bool) {
	p := e.compileGenericDistinct(branch)
	if p == nil {
		return Result{}, false
	}
	sources := e.graphs
	if !e.cross {
		sources = nil
		if graph != nil {
			sources = []Graph{{Store: graph}}
		}
	}
	selectedSources := []Graph{}
	for _, source := range sources {
		if p.graphIDs == nil || p.graphIDs[source.ID] {
			selectedSources = append(selectedSources, source)
		}
	}
	sources = selectedSources
	result := Result{Columns: p.columns, Rows: []map[string]any{}}
	selected := newGenericDistinctRows(p.projection.Skip == nil)
	scanners := make([]*genericDistinctScanner, len(sources))
	for i, source := range sources {
		local := e
		local.rowOrders = nil
		scanners[i] = &genericDistinctScanner{e: local, source: source, plan: p, scratch: make([]any, len(p.unique)), local: newGenericDistinctRows(p.projection.Skip == nil)}
	}
	defer func() {
		for _, s := range scanners {
			if s.cursor != nil {
				s.cursor.close()
			}
		}
	}()
	merge := func(rows []map[string]any) {
		for _, row := range rows {
			values := make([]any, len(p.unique))
			for i, k := range p.unique {
				values[i] = row[k]
			}
			if i, found := selected.find(values); found {
				mergeProvenance(selected.rows[i], row)
			} else if len(selected.rows) < p.limit {
				selected.add(values, row)
			}
		}
	}
	parallel := min(len(sources), runtime.NumCPU(), 8)
	if len(sources) >= 40 {
		parallel = min(len(sources), max(1, runtime.NumCPU()/2))
	}
	parallel = max(1, parallel)
	if !e.cross || len(sources) < 2 || p.projection.Skip != nil {
		for _, s := range scanners {
			for {
				values, ok := s.next(e.ctx, false)
				if !ok {
					break
				}
				if i, found := selected.find(values); found {
					if e.cross {
						addProvenance(selected.rows[i], s.source.ID)
					}
				} else if len(selected.rows) < p.limit {
					selected.add(values, s.row(values))
				}
				if !e.cross && len(selected.rows) >= p.limit {
					break
				}
			}
			if !e.cross && len(selected.rows) >= p.limit {
				break
			}
		}
	} else {
		for start := 0; start < len(scanners) && len(selected.rows) < p.limit; start += parallel {
			end := min(len(scanners), start+parallel)
			batches := make([][]map[string]any, end-start)
			runDistinctTasks(e.ctx, end-start, parallel, false, func(ctx context.Context, i int) []map[string]any { return scanners[start+i].batch(ctx) }, func(i int, rows []map[string]any) bool { batches[i] = rows; return false })
			for i, batch := range batches {
				merge(batch)
				for len(selected.rows) < p.limit && !scanners[start+i].exhausted {
					merge(scanners[start+i].batch(e.ctx))
				}
			}
		}
		if len(selected.rows) >= p.limit {
			runDistinctTasks(e.ctx, len(scanners), parallel, false, func(ctx context.Context, i int) map[int]bool { return scanners[i].remaining(ctx, selected) }, func(i int, hits map[int]bool) bool {
				for row := range hits {
					addProvenance(selected.rows[row], sources[i].ID)
				}
				return false
			})
		}
	}
	if p.skip < len(selected.rows) {
		result.Rows = selected.rows[p.skip:]
	}
	for i, row := range result.Rows {
		ordered := map[string]any{}
		for _, column := range p.unique {
			e.bind(ordered, column, row[column])
		}
		addProvenance(ordered, provenance(row)...)
		result.Rows[i] = ordered
	}
	return result, true
}
