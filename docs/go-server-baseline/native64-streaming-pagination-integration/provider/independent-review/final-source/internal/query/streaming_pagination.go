package query

import (
	"context"
	"math"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// The node-only admission of QueryPipeline.tryStreamingFilteredMatchLimit.
// Relationship patterns require their own lazy traversal and WHERE push rules.
type streamingPaginationPlan struct {
	match                 cypher.MatchClause
	node                  cypher.NodePattern
	projection            cypher.ProjectionClause
	columns               []string
	skip, limit, retained int
	atoms                 []distinctStringAtom
	graphIDs              map[string]bool
	unknownLabel          bool
}

func hasStreamingPagination(branch cypher.SingleQuery) bool {
	if len(branch.Clauses) != 2 {
		return false
	}
	r, ok := branch.Clauses[1].(cypher.ProjectionClause)
	return ok && r.Skip != nil
}

func (e evaluator) compileStreamingPagination(branch cypher.SingleQuery) *streamingPaginationPlan {
	if len(branch.Clauses) != 2 {
		return nil
	}
	m, ok := branch.Clauses[0].(cypher.MatchClause)
	if !ok || m.Optional || m.Where == nil || len(m.Patterns) != 1 {
		return nil
	}
	r, ok := branch.Clauses[1].(cypher.ProjectionClause)
	if !ok || r.With || r.All || r.Where != nil {
		return nil
	}
	pattern := m.Patterns[0]
	if pattern.PathVariable != "" || len(pattern.Nodes) != 1 || len(pattern.Relationships) != 0 {
		return nil
	}
	for _, item := range r.Items {
		if containsAggregate(item.Expression) {
			return nil
		}
	}
	limit, literal := plannerLiteralCount(r.Limit)
	if !literal {
		return nil
	}
	skip, _ := plannerLiteralCount(r.Skip)
	if skip < 0 {
		return nil
	}
	p := &streamingPaginationPlan{match: m, node: pattern.Nodes[0], projection: r, skip: int(skip), limit: int(limit)}
	for _, label := range p.node.Labels {
		if strings.EqualFold(label, "Method") {
			return nil
		}
		p.unknownLabel = p.unknownLabel || !lazyKnownLabel(label)
	}
	for _, item := range r.Items {
		column := item.Alias
		if column == "" {
			column = columnName(item.Expression)
		}
		p.columns = append(p.columns, column)
	}
	if limit <= 0 {
		return p
	}
	if int64(skip)+int64(limit) > math.MaxInt32 {
		return nil
	}
	p.retained = int(skip) + int(limit)
	return p
}

type streamingNodeFactory func(evaluator, Graph, cypher.NodePattern, []distinctStringAtom, int) mainNodeNext

func realStreamingNodes(e evaluator, source Graph, node cypher.NodePattern, atoms []distinctStringAtom, count int) mainNodeNext {
	if atoms != nil {
		return e.mainStringCandidates(source, node, atoms, count)
	}
	return e.lazyGenericNodes(source, node)
}

func (e evaluator) streamingPagination(graph *store.Store, branch cypher.SingleQuery) (Result, bool) {
	defer ordinarySourceFailure()
	p := e.compileStreamingPagination(branch)
	if p == nil {
		return Result{}, false
	}
	result := Result{Columns: p.columns, Rows: []map[string]any{}}
	if p.unknownLabel {
		// Main selects general clauses before streaming dispatch for an unknown
		// label. Its node source is empty, but pagination expressions still run.
		rows, columns := e.project(nil, p.projection)
		return Result{Rows: rows, Columns: columns}, true
	}
	if p.limit <= 0 {
		return result, true
	}
	if p.node.Variable != "" {
		p.graphIDs = e.streamingGraphConstraint(p.match.Where, p.node.Variable)
		if value, ok := e.distinctStringConstant(p.node.Properties["graphId"]); ok {
			p.graphIDs = streamingGraphIntersection(p.graphIDs, map[string]bool{value: true})
		}
		if len(p.node.Labels) <= 1 && len(p.node.Properties) == 0 {
			p.atoms = e.lazyNecessaryCandidates(p.match.Where, p.node.Variable)
		}
	}
	sources := e.graphs
	if !e.cross {
		sources = nil
		if graph != nil {
			sources = []Graph{{ID: "single", Store: graph}}
		}
	}
	selected := []Graph{}
	for _, source := range sources {
		if p.graphIDs == nil || p.graphIDs[source.ID] {
			selected = append(selected, source)
		}
	}
	return e.streamingPaginationSources(p, selected, realStreamingNodes), true
}

// Each source constructs its own iterator only when demanded. The consumer
// owns filtering and projection; SKIP cannot advance through projected rows.
func (e evaluator) streamingBindings(p *streamingPaginationPlan, sources []Graph, factory streamingNodeFactory) func(context.Context) (map[string]any, bool) {
	position := 0
	var next mainNodeNext
	return func(ctx context.Context) (map[string]any, bool) {
		local := e
		local.ctx = ctx
		for position < len(sources) {
			source := sources[position]
			if next == nil {
				next = factory(local, source, p.node, p.atoms, len(sources))
			}
			node, ok := next(ctx)
			if !ok {
				position++
				next = nil
				continue
			}
			candidate := local.nodeValue(source.Store, source.ID, node)
			if p.atoms == nil && !local.matches(candidate, p.node, nil) {
				continue
			}
			bindings := map[string]any{}
			if p.node.Variable != "" {
				local.bind(bindings, p.node.Variable, candidate)
				if local.cross {
					addProvenance(bindings, source.ID)
				}
			}
			return bindings, true
		}
		return nil, false
	}
}

func (e evaluator) streamingProject(p *streamingPaginationPlan, bindings map[string]any) map[string]any {
	row := map[string]any{}
	for i, item := range p.projection.Items {
		e.bind(row, p.columns[i], freezeCandidate(e.eval(item.Expression, bindings)))
	}
	mergeProvenance(row, bindings)
	return row
}

func (e evaluator) streamingPaginationSources(p *streamingPaginationPlan, sources []Graph, factory streamingNodeFactory) Result {
	result := Result{Columns: p.columns, Rows: []map[string]any{}}
	if len(p.projection.OrderBy) > 0 {
		if parallel := e.streamingOrderParallelism(p, sources); parallel > 1 {
			return e.streamingParallelOrder(p, sources, factory, parallel)
		}
	}
	next := e.streamingBindings(p, sources, factory)
	if len(p.projection.OrderBy) > 0 {
		return e.streamingOrderedRows(p, next)
	}
	seen := map[string]int{}
	matched := 0
	for {
		bindings, ok := next(e.ctx)
		if !ok {
			break
		}
		if e.eval(p.match.Where, bindings) != true {
			continue
		}
		if !p.projection.Distinct {
			if matched < p.skip {
				matched++
				continue
			}
		}
		row := e.streamingProject(p, bindings)
		if p.projection.Distinct {
			identity := distinctVisibleKey(row)
			if i, exists := seen[identity]; exists {
				mergeProvenance(result.Rows[i], row)
			} else if len(result.Rows) < p.retained {
				seen[identity] = len(result.Rows)
				result.Rows = append(result.Rows, row)
			}
			if !e.cross && len(result.Rows) >= p.retained {
				break
			}
		} else {
			result.Rows = append(result.Rows, row)
			if len(result.Rows) >= p.limit {
				break
			}
		}
	}
	if p.projection.Distinct {
		result.Rows = result.Rows[min(p.skip, len(result.Rows)):]
	}
	return result
}

// graphSourceScope precedes B dispatch. Conjuncts intersect every provable
// finite route, while OR may prune only when both branches prove a route.
// Keep the original WHERE: pagination's consumer still evaluates it in full.
func (e evaluator) streamingGraphConstraint(expr cypher.Expr, variable string) map[string]bool {
	if b, ok := expr.(cypher.Binary); ok {
		switch b.Op {
		case "AND":
			return streamingGraphIntersection(e.streamingGraphConstraint(b.Left, variable), e.streamingGraphConstraint(b.Right, variable))
		case "OR":
			left, right := e.streamingGraphConstraint(b.Left, variable), e.streamingGraphConstraint(b.Right, variable)
			if left == nil || right == nil {
				return nil
			}
			for id := range right {
				left[id] = true
			}
			return left
		}
	}
	return e.distinctPureGraphConstraint(expr, variable)
}
func streamingGraphIntersection(left, right map[string]bool) map[string]bool {
	if left == nil {
		return right
	}
	if right == nil {
		return left
	}
	for id := range left {
		if !right[id] {
			delete(left, id)
		}
	}
	return left
}
