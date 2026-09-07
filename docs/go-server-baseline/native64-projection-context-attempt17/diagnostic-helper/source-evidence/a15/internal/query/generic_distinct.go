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
	match                    cypher.MatchClause
	projection               cypher.ProjectionClause
	atoms                    []distinctStringAtom
	columns, unique          []string
	itemSlots, finalItems    []int
	limit, skip              int
	mainSource, parallelSafe bool
	sourceCount              int
	graphIDs                 map[string]bool
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
	p := &genericDistinctPlan{match: m, projection: r, limit: int(limit) + int(skip), skip: int(skip), mainSource: r.Skip == nil, parallelSafe: true}
	positions := map[string]int{}
	for i, item := range r.Items {
		if containsAggregate(item.Expression) {
			return nil
		}
		switch expr := item.Expression.(type) {
		case cypher.Literal:
		case cypher.Property:
			owner, ok := expr.Object.(cypher.Variable)
			if !ok || owner.Name != node.Variable {
				p.parallelSafe = false
				if !p.mainSource {
					return nil
				}
			}
		default:
			p.parallelSafe = false
			if !p.mainSource {
				return nil
			}
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

// A scanner owns this synchronous cursor. A successful advance borrows one
// value until the next call. Source tasks never share a scanner concurrently;
// runDistinctTasks joins them before the query owner closes the cursors.
type genericDistinctCursor struct {
	ctx       context.Context
	cancel    context.CancelFunc
	advance   func(context.Context) (any, bool)
	exhausted bool
}
type genericDistinctValue struct {
	value   any
	failure any
	end     bool
}

func newGenericDistinctCursor(e evaluator, source Graph, p *genericDistinctPlan) *genericDistinctCursor {
	initialized := false
	var mainNext mainNodeNext
	var indexed *candidateNodePositions
	var ids []int32
	position := 0
	slot := &candidateSlot{}
	return newGenericValueCursor(e.ctx, func(ctx context.Context) (any, bool) {
		e.ctx = ctx
		if p.mainSource {
			if !initialized {
				mainNext = e.mainStringCandidates(source, p.match.Patterns[0].Nodes[0], p.atoms, p.sourceCount)
				initialized = true
			}
			node, ok := mainNext(ctx)
			if !ok {
				return nil, false
			}
			slot.graph, slot.graphID, slot.qualified = source.Store, source.ID, e.cross
			slot.isMethod, slot.node = false, node
			return slot, true
		}
		if !initialized {
			e.graphs, e.rowOrders, e.indexFirst = []Graph{source}, nil, true
			prepared, available := e.prepareIndexedNodePositions(source.Store, p.match)
			if available {
				indexed = &candidateNodePositions{groups: prepared}
			} else {
				ids = source.Store.QueryNodeIDs()
			}
			initialized = true
		}
		for {
			current, id, ok := source, int32(0), false
			if indexed != nil {
				current, id, ok = indexed.next()
			} else if position < len(ids) {
				id, ok = ids[position], true
				position++
			}
			if !ok {
				return nil, false
			}
			e.check()
			node, err := current.Store.CandidateNode(ctx, id)
			if err != nil {
				candidatePreparationError(err)
				failNodeRead(err)
			}
			slot.graph, slot.graphID, slot.qualified = current.Store, current.ID, e.cross
			slot.isMethod, slot.node = false, node
			e.check()
			if e.matches(slot, p.match.Patterns[0].Nodes[0], nil) {
				return slot, true
			}
		}
	})
}

func newGenericValueCursor(parent context.Context, advance func(context.Context) (any, bool)) *genericDistinctCursor {
	ctx, cancel := context.WithCancel(parent)
	return &genericDistinctCursor{ctx: ctx, cancel: cancel, advance: advance}
}

// Capture only the former producer segment (preparation/decode/pattern match).
// WHERE and projection execute in scanner.next outside this failure boundary.
func (c *genericDistinctCursor) read(ctx context.Context) (out genericDistinctValue) {
	defer func() { out.failure = recover() }()
	if err := c.ctx.Err(); err != nil {
		panic(err)
	}
	var ok bool
	out.value, ok = c.advance(ctx)
	out.end = !ok
	return
}
func (c *genericDistinctCursor) next(ctx context.Context) (any, bool) {
	if err := ctx.Err(); err != nil {
		panic(err)
	}
	if c.exhausted {
		if err := ctx.Err(); err != nil {
			panic(err)
		}
		return nil, false
	}
	out := c.read(ctx)
	if out.failure != nil || out.end {
		c.exhausted = true
	}
	// The old receiving side checked the request context before delivering a
	// producer error. Preserve that precedence without wrapping consumer errors.
	if err := ctx.Err(); err != nil {
		panic(err)
	}
	if out.failure != nil {
		panic(out.failure)
	}
	return out.value, !out.end
}
func (c *genericDistinctCursor) close() {
	c.cancel()
	c.exhausted = true
	c.advance = nil
}

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

	projectionBindings map[string]any
}

func (s *genericDistinctScanner) next(ctx context.Context, selected bool) ([]any, bool) {
	local := s.e
	local.ctx = ctx
	variable := ""
	if s.plan.mainSource {
		variable = s.plan.match.Patterns[0].Nodes[0].Variable
		if s.projectionBindings == nil {
			s.projectionBindings = map[string]any{variable: nil}
		}
		// The scanner has one owner across batch/remaining tasks. Never retain a
		// borrowed candidate after returning, including panic and cancellation.
		defer s.clearProjectionBinding(variable)
	}
	if s.cursor == nil {
		s.cursor = newGenericDistinctCursor(s.e, s.source, s.plan)
	}
	for !s.exhausted {
		value, ok := s.cursor.next(ctx)
		if !ok {
			s.exhausted = true
			return nil, false
		}
		matched := s.plan.mainSource
		for _, atom := range s.plan.atoms {
			if matched {
				break
			}
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
		if s.plan.mainSource {
			s.projectionBindings[variable] = value
		}
		get := func(item int) any {
			local.check()
			if s.plan.mainSource {
				return freezeCandidate(local.eval(s.plan.projection.Items[item].Expression, s.projectionBindings))
			}
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
func (s *genericDistinctScanner) clearProjectionBinding(variable string) {
	s.projectionBindings[variable] = nil
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
	p.sourceCount = len(sources)
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
	if !e.cross || len(sources) < 2 || p.projection.Skip != nil || !p.parallelSafe || p.mainSource && !e.mainStringParallel(sources, p) {
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
