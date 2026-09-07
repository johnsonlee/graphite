package query

import (
	"context"
	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"runtime"
)

type lazyFilteredScanner struct {
	e           evaluator
	plan        *lazyFilteredPlan
	source      Graph
	sourceCount int
	nextNode    mainNodeNext
	ended       bool
}

func (s *lazyFilteredScanner) next(ctx context.Context, selected bool) (map[string]any, bool) {
	local := s.e
	local.ctx = ctx
	if s.ended {
		return nil, false
	}
	if s.nextNode == nil {
		s.nextNode = local.mainStringCandidatesWithPolicy(s.source, s.plan.node, s.plan.atoms, s.sourceCount, !s.plan.projection.Distinct && s.plan.graphIDs != nil)
	}
	for {
		local.check()
		node, ok := s.nextNode(ctx)
		if !ok {
			s.ended = true
			return nil, false
		}
		candidate := local.nodeValue(s.source.Store, s.source.ID, node)
		if s.plan.directPredicate != nil {
			if !s.plan.directPredicate(local, candidate) {
				continue
			}
		} else if local.eval(s.plan.residual, map[string]any{s.plan.node.Variable: candidate}) != true {
			continue
		}
		return local.lazyProject(s.plan, candidate, s.source.ID, selected), true
	}
}
func (s *lazyFilteredScanner) batch(ctx context.Context) []map[string]any {
	rows := []map[string]any{}
	seen := newGenericDistinctRows(true)
	for len(rows) < s.plan.limit {
		row, ok := s.next(ctx, false)
		if !ok {
			break
		}
		if s.plan.projection.Distinct {
			values := lazyVisibleValues(s.plan, row)
			if _, exists := seen.find(values); exists {
				continue
			}
			seen.add(values, row)
		}
		rows = append(rows, row)
	}
	return rows
}
func lazyVisibleValues(p *lazyFilteredPlan, row map[string]any) []any {
	values := []any{}
	seen := map[string]bool{}
	for _, column := range p.columns {
		if !seen[column] {
			seen[column] = true
			values = append(values, row[column])
		}
	}
	return values
}
func (e evaluator) lazyCandidateFiltered(p *lazyFilteredPlan, sources []Graph) Result {
	result := Result{Columns: p.columns, Rows: []map[string]any{}}
	if len(sources) == 0 {
		return result
	}
	scanners := make([]*lazyFilteredScanner, len(sources))
	for i, source := range sources {
		local := e
		local.rowOrders = nil
		local.regexes = &regexLRU{entries: map[string]compiledRegex{}}
		scanners[i] = &lazyFilteredScanner{e: local, plan: p, source: source, sourceCount: len(sources)}
	}
	selected := newGenericDistinctRows(true)
	merge := func(rows []map[string]any) {
		for _, row := range rows {
			if p.projection.Distinct {
				values := lazyVisibleValues(p, row)
				if i, exists := selected.find(values); exists {
					mergeProvenance(selected.rows[i], row)
				} else if len(selected.rows) < p.limit {
					selected.add(values, row)
				}
			} else if len(result.Rows) < p.limit {
				result.Rows = append(result.Rows, row)
			}
		}
	}
	size := func() int {
		if p.projection.Distinct {
			return len(selected.rows)
		}
		return len(result.Rows)
	}
	parallelSafe := true
	for _, item := range p.projection.Items {
		switch x := item.Expression.(type) {
		case cypher.Literal:
		case cypher.Property:
			v, ok := x.Object.(cypher.Variable)
			parallelSafe = parallelSafe && ok && v.Name == p.node.Variable
		default:
			parallelSafe = false
		}
	}
	capability := e.mainStringParallel(sources, &genericDistinctPlan{match: p.match, atoms: p.atoms})
	parallel := min(len(sources), runtime.NumCPU(), 8)
	if len(sources) >= 40 {
		parallel = min(len(sources), max(1, runtime.NumCPU()/2))
	}
	useParallel := e.cross && parallel > 1 && capability && parallelSafe
	if !useParallel {
		for _, scanner := range scanners {
			for {
				row, ok := scanner.next(e.ctx, false)
				if !ok {
					break
				}
				merge([]map[string]any{row})
				if size() >= p.limit && (!p.projection.Distinct || !e.cross) {
					break
				}
			}
			if size() >= p.limit && (!p.projection.Distinct || !e.cross) {
				break
			}
		}
	} else {
		start := 0
		// Ordinary balanced residual scans perform the leading-source probe before
		// admitting the remaining source tasks. DISTINCT instead scans full waves.
		if !p.projection.Distinct && len(sources) >= 40 {
			merge(scanners[0].batch(e.ctx))
			start = 1
		}
		if !p.projection.Distinct && len(sources) >= 40 && size() < p.limit {
			runDistinctTasks(e.ctx, len(scanners)-start, parallel, true, func(ctx context.Context, i int) []map[string]any { return scanners[start+i].batch(ctx) }, func(_ int, rows []map[string]any) bool { merge(rows); return size() >= p.limit })
			start = len(scanners)
		}
		for start < len(scanners) && size() < p.limit {
			end := min(len(scanners), start+parallel)
			batches := make([][]map[string]any, end-start)
			runDistinctTasks(e.ctx, end-start, parallel, false, func(ctx context.Context, i int) []map[string]any { return scanners[start+i].batch(ctx) }, func(i int, rows []map[string]any) bool { batches[i] = rows; return false })
			for i, batch := range batches {
				merge(batch)
				if p.projection.Distinct {
					for size() < p.limit && !scanners[start+i].ended {
						merge(scanners[start+i].batch(e.ctx))
					}
				}
			}
			start = end
		}
		if p.projection.Distinct && size() >= p.limit {
			runDistinctTasks(e.ctx, len(scanners), parallel, false, func(ctx context.Context, i int) map[int]bool {
				hits := map[int]bool{}
				for {
					row, ok := scanners[i].next(ctx, true)
					if !ok {
						return hits
					}
					if j, exists := selected.find(lazyVisibleValues(p, row)); exists {
						hits[j] = true
					}
				}
			}, func(i int, hits map[int]bool) bool {
				for j := range hits {
					addProvenance(selected.rows[j], sources[i].ID)
				}
				return false
			})
		}
	}
	if p.projection.Distinct {
		result.Rows = selected.rows
	}
	// Workers do not share rowOrders. Publish the original column insertion order
	// only after joining; aliases may overwrite values without changing position.
	for i, row := range result.Rows {
		ordered := map[string]any{}
		for _, column := range p.columns {
			e.bind(ordered, column, row[column])
		}
		mergeProvenance(ordered, row)
		result.Rows[i] = ordered
	}
	return result
}
