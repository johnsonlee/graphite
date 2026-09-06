package query

import "github.com/johnsonlee/graphite/graphite-server/internal/cypher"

// filteredLiteralEmpty reproduces main's filtered-match literal-limit guard.
// This is intentionally branch-shaped, not a general LIMIT pushdown: main's
// general pipeline still evaluates projections before applying a zero limit.
func filteredLiteralEmpty(branch cypher.SingleQuery) (Result, bool) {
	if len(branch.Clauses) != 2 {
		return Result{}, false
	}
	match, ok := branch.Clauses[0].(cypher.MatchClause)
	if !ok || match.Optional || match.Where == nil || len(match.Patterns) != 1 {
		return Result{}, false
	}
	projection, ok := branch.Clauses[1].(cypher.ProjectionClause)
	if !ok || projection.With || projection.All || projection.Where != nil {
		return Result{}, false
	}
	pattern := match.Patterns[0]
	if pattern.PathVariable != "" {
		return Result{}, false
	}
	for _, item := range projection.Items {
		if containsAggregate(item.Expression) {
			return Result{}, false
		}
	}
	limit, literal := plannerLiteralCount(projection.Limit)
	if !literal || limit > 0 {
		return Result{}, false
	}
	// Main's streaming filtered-match path treats a nonliteral SKIP as zero
	// during eligibility, without evaluating it. A negative literal declines.
	if skip, literal := plannerLiteralCount(projection.Skip); literal && skip < 0 {
		return Result{}, false
	}
	columns := make([]string, len(projection.Items))
	for i, item := range projection.Items {
		columns[i] = item.Alias
		if columns[i] == "" {
			columns[i] = columnName(item.Expression)
		}
	}
	return Result{Columns: columns, Rows: []map[string]any{}}, true
}

// Only a Literal qualifies in main's literalLimitCount. In particular, a
// parameter, arithmetic expression and unary -1 must retain general evaluation.
// Conversion is local to the planner guard; it does not alter general SKIP/LIMIT.
func plannerLiteralCount(expr cypher.Expr) (int32, bool) {
	literal, ok := expr.(cypher.Literal)
	if !ok {
		return 0, false
	}
	return cypherCountValue(literal.Value), true
}
