// Package query executes the native Go read-only Cypher pipeline.
package query

import (
	"context"
	"errors"
	"fmt"
	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"sort"
	"strings"
	"unicode/utf8"
)

type Result struct {
	Columns    []string         `json:"columns"`
	Rows       []map[string]any `json:"rows"`
	rawColumns []string
	rawRowKeys [][]string
}

// Execute evaluates a single-store query. A negative limit means unlimited;
// limit zero returns no rows. It does not truncate before ORDER/aggregation.
func Execute(ctx context.Context, graph *store.Store, source string, parameters map[string]any, limit int) (result Result, err error) {
	return ExecuteWithOptions(ctx, graph, source, parameters, limit, ExecutionOptions{})
}

// ExecuteWithOptions evaluates one graph with an optional request context.
func ExecuteWithOptions(ctx context.Context, graph *store.Store, source string, parameters map[string]any, limit int, options ExecutionOptions) (Result, error) {
	return executeSources(ctx, graph, nil, false, source, parameters, limit, options)
}

// ExecuteWithMaxRows preserves main's explicit maxRows overload, which rejects
// negative bounds before parsing or consulting cancellation. ExecuteWithOptions
// retains the existing negative-limit sentinel for the unbounded overload.
func ExecuteWithMaxRows(ctx context.Context, graph *store.Store, source string, parameters map[string]any, maxRows int, options ExecutionOptions) (Result, error) {
	if graph == nil {
		return Result{}, &Error{Class: "NullPointerException", Message: "Parameter specified as non-null is null: method io.johnsonlee.graphite.cypher.CypherExecutor.<init>, parameter graph"}
	}
	if maxRows < 0 {
		return Result{}, &Error{Class: "IllegalArgumentException", Message: "maxRows must be non-negative"}
	}
	options.maxRows = &maxRows
	return executeSources(ctx, graph, nil, false, source, parameters, -1, options)
}

// ExecutionOptions preserves the distinction between main's plain executor and
// its execution-context/request-selected paths. Work counters and budget
// accounting are independent of these planner policy switches.
type ExecutionOptions struct {
	SourceScopeApplied  bool
	WorkTrackingEnabled bool
	// ExecutionContext shares cancellation and work across sequential calls.
	// WorkBudget creates a fresh context per call when nonzero and no explicit
	// context is supplied. Zero preserves the plain executor's untracked path.
	// Accounting integration currently covers generic scans and literal ID seeks;
	// optimized storage and traversal routes still need their own work consumers.
	ExecutionContext *ExecutionContext
	WorkBudget       int64
	maxRows          *int
}

func ExecuteCross(ctx context.Context, graphs []Graph, source string, parameters map[string]any, limit int) (Result, error) {
	return ExecuteCrossWithOptions(ctx, graphs, source, parameters, limit, ExecutionOptions{})
}

// ExecuteCrossWithMaxRows is the explicit nonnegative row-bound overload.
func ExecuteCrossWithMaxRows(ctx context.Context, graphs []Graph, source string, parameters map[string]any, maxRows int, options ExecutionOptions) (Result, error) {
	if err := validateCrossGraphs(graphs); err != nil {
		return Result{}, err
	}
	if maxRows < 0 {
		return Result{}, &Error{Class: "IllegalArgumentException", Message: "maxRows must be non-negative"}
	}
	options.maxRows = &maxRows
	return executeSources(ctx, nil, graphs, true, source, parameters, -1, options)
}

// ExecuteCrossWithOptions keeps source order and qualification unchanged. A
// preselected list, even if it contains all graphs, must carry its scope marker.
func ExecuteCrossWithOptions(ctx context.Context, graphs []Graph, source string, parameters map[string]any, limit int, options ExecutionOptions) (Result, error) {
	if err := validateCrossGraphs(graphs); err != nil {
		return Result{}, err
	}
	return executeSources(ctx, nil, graphs, true, source, parameters, limit, options)
}

func validateCrossGraphs(graphs []Graph) error {
	// Main constructs each non-null CypherGraph before the pipeline checks the
	// complete namespace. An empty string is a valid graph ID.
	for _, g := range graphs {
		if g.Store == nil {
			return &Error{Class: "NullPointerException", Message: "Parameter specified as non-null is null: method io.johnsonlee.graphite.cypher.CypherGraph.<init>, parameter graph"}
		}
	}
	seen := map[string]bool{}
	for _, g := range graphs {
		if seen[g.ID] {
			return &Error{Class: "IllegalArgumentException", Message: "Graph ids must be unique"}
		}
		seen[g.ID] = true
	}
	return nil
}

func executeSources(ctx context.Context, graph *store.Store, graphs []Graph, cross bool, source string, parameters map[string]any, limit int, options ExecutionOptions) (result Result, err error) {
	defer func() {
		if v := recover(); v != nil {
			switch x := v.(type) {
			case *Error:
				result = Result{}
				err = x
			case error:
				if errors.Is(x, context.Canceled) {
					result = Result{}
					err = publicCancellationError(ctx, x)
				} else if errors.Is(x, context.DeadlineExceeded) {
					result = Result{}
					err = x
				} else {
					panic(v)
				}
			default:
				panic(v)
			}
		}
	}()
	work := options.ExecutionContext
	if work == nil && options.WorkBudget != 0 {
		work, err = NewExecutionContext(options.WorkBudget)
		if err != nil {
			return Result{}, err
		}
	}
	// The original executor finishes DSL parsing before consulting the query
	// cancellation signal. ParseContext remains available to parser API callers.
	ast, err := cypher.Parse(source)
	if err != nil {
		var literalError *cypher.ParseError
		if errors.As(err, &literalError) {
			if strings.HasPrefix(literalError.Message, "For input string:") {
				return Result{}, &Error{Class: "NumberFormatException", Message: literalError.Message}
			}
			if strings.HasPrefix(literalError.Message, "Syntax error at position ") {
				return Result{}, &Error{Class: "CypherParseException", Message: javaWireString(literalError.Message)}
			}
		}

		return Result{}, err
	}
	if work != nil {
		var release func()
		ctx, release = work.bind(ctx)
		defer release()
	}
	e := evaluator{ctx: ctx, work: work, parameters: parameters, graphs: graphs, cross: cross, sourceScopeApplied: options.SourceScopeApplied, workTrackingEnabled: options.WorkTrackingEnabled || work != nil, regexes: &regexLRU{entries: map[string]compiledRegex{}}}
	e.check()
	validate(ast)
	javaSource := !utf8.ValidString(source) || (strings.Contains(source, `\u`) && needsJavaOutputOrder(ast))
	if needsRowOrder(ast) || javaSource {
		e.rowOrders = map[string]rowOrder{}
	}
	if options.maxRows != nil {
		result = e.executeBounded(graph, ast, *options.maxRows)
	} else {
		result = Result{Columns: []string{}, Rows: []map[string]any{}}
		for i, branch := range ast.Branches {
			e.check()
			r := e.branch(graph, branch)
			if i == 0 {
				result.Columns = r.Columns
			}
			result.Rows = append(result.Rows, r.Rows...)
			if i > 0 && !ast.UnionAll[i-1] {
				result.Rows = distinctRows(result.Rows, result.Columns)
			}
		}
	}
	if limit >= 0 && len(result.Rows) > limit {
		result.Rows = result.Rows[:limit]
	}
	if hasNonUTF8Keys(result.Columns) {
		result.rawColumns = append([]string(nil), result.Columns...)
	}
	for index, row := range result.Rows {
		if e.work != nil && index&1023 == 0 {
			e.check()
		}
		ids := provenance(row)
		delete(row, provenanceKey)
		var keys []string
		if javaSource {
			keys = e.rowKeys(row)
		}
		if wireKeyCollision(keys) {
			if result.rawRowKeys == nil {
				result.rawRowKeys = make([][]string, len(result.Rows))
			}
			result.rawRowKeys[index] = keys
			for k, v := range row {
				row[k] = e.materialize(v)
			}
		} else {
			// Write renamed keys into a fresh map rather than mutating a map while
			// iterating over it (new entries may otherwise be visited again).
			var renamed map[string]any
			if javaSource {
				for k := range row {
					if !utf8.ValidString(k) {
						renamed = make(map[string]any, len(row))
						break
					}
				}
			}
			for k, v := range row {
				if renamed != nil {
					renamed[javaWireString(k)] = e.materialize(v)
				} else {
					row[k] = e.materialize(v)
				}
			}
			if renamed != nil {
				row = renamed
				result.Rows[index] = row
			}
		}
		if cross || len(ids) > 0 {
			if _, present := row["$metadata"]; len(ids) > 0 || !present {
				row["$metadata"] = map[string]any{"graphIds": ids}
			}
		}
	}
	for i, column := range result.Columns {
		result.Columns[i] = javaWireString(column)
	}
	// Main's zero-bound UNION DISTINCT returns column discovery directly;
	// all materialized tracked results perform a final cancellation check.
	directEmptyUnion := options.maxRows != nil && *options.maxRows == 0 && len(ast.Branches) > 1 && !ast.UnionAll[len(ast.UnionAll)-1]
	if e.work != nil && !directEmptyUnion {
		e.check()
	}
	return result, nil
}

// executeBounded mirrors the explicit maxRows overload. In main the last UNION
// token selects the policy for the complete chain. UNION ALL stops at the global
// bound; DISTINCT still executes later bounded segments for retained provenance.
// The legacy entry points keep their existing post-execution truncation above.
func (e evaluator) executeBounded(graph *store.Store, ast *cypher.Query, maxRows int) Result {
	if len(ast.Branches) == 1 {
		return e.boundedBranch(graph, ast.Branches[0], maxRows)
	}
	result := Result{Columns: []string{}, Rows: []map[string]any{}}
	unionAll := len(ast.UnionAll) > 0 && ast.UnionAll[len(ast.UnionAll)-1]
	if !unionAll && maxRows == 0 {
		if len(ast.Branches) > 0 {
			result.Columns = e.boundedBranch(graph, ast.Branches[0], 0).Columns
		}
		return result
	}
	retained := map[string]map[string]any{}
	for _, branch := range ast.Branches {
		e.check()
		bound := maxRows
		if unionAll {
			bound -= len(result.Rows)
			if bound <= 0 {
				if len(result.Columns) == 0 {
					result.Columns = e.boundedBranch(graph, branch, 0).Columns
				}
				break
			}
		}
		rows := e.boundedBranch(graph, branch, bound)
		if len(result.Columns) == 0 {
			result.Columns = rows.Columns
		}
		if unionAll {
			result.Rows = append(result.Rows, rows.Rows[:min(bound, len(rows.Rows))]...)
			continue
		}
		for _, row := range rows.Rows {
			e.check()
			visible := e.cloneRow(row)
			delete(visible, provenanceKey)
			delete(visible, "$metadata")
			identity := key(visible)
			if previous := retained[identity]; previous != nil {
				mergeProvenance(previous, row)
			} else if len(result.Rows) < maxRows {
				mergeProvenance(visible, row)
				retained[identity] = visible
				result.Rows = append(result.Rows, visible)
			}
		}
	}
	return result
}

// boundedBranch replaces the last literal LIMIT or appends one to the final
// projection. An existing nonliteral LIMIT remains a second, separate operation:
// main inserts a literal LIMIT immediately before it, rather than evaluating min.
func (e evaluator) boundedBranch(graph *store.Store, branch cypher.SingleQuery, maxRows int) Result {
	clauses := append([]cypher.Clause(nil), branch.Clauses...)
	lastLimit, lastProjection := -1, -1
	for index, clause := range clauses {
		if projection, ok := clause.(cypher.ProjectionClause); ok {
			lastProjection = index
			if projection.Limit != nil {
				lastLimit = index
			}
		}
	}
	index := lastLimit
	if index < 0 {
		index = lastProjection
	}
	if index < 0 || (lastLimit < 0 && index != len(clauses)-1) {
		// Clause-only inputs have no folded projection to carry the appended
		// LIMIT. Preserve their existing bindings and columns while bounding MATCH.
		return e.generalBranchWithBound(graph, branch, -1, &maxRows, true)
	}
	projection := clauses[index].(cypher.ProjectionClause)
	if lastLimit >= 0 {
		if count, literal := boundedLiteralLimit(projection.Limit); literal {
			if int64(count) <= int64(maxRows) {
				return e.branch(graph, branch)
			}
		} else {
			// Two consecutive LIMIT clauses match no optimized main admission.
			// The generic evaluator applies the inserted bound before evaluating
			// the original expression in the already bounded row set.
			return e.generalBranchWithBound(graph, branch, index, &maxRows, false)
		}
	}
	projection.Limit = cypher.Literal{Value: int64(maxRows)}
	clauses[index] = projection
	return e.branch(graph, cypher.SingleQuery{Clauses: clauses})
}

func boundedLiteralLimit(expression cypher.Expr) (int32, bool) {
	literal, ok := expression.(cypher.Literal)
	if !ok {
		return 0, false
	}
	if _, numeric := number(literal.Value); numeric {
		return cypherCountValue(literal.Value), true
	}
	if text, ok := literal.Value.(string); ok {
		if _, err := parseJavaLong(text); err == nil {
			return cypherCountValue(text), true
		}
	}
	return 0, false
}

func (e evaluator) branch(graph *store.Store, branch cypher.SingleQuery) Result {
	e.check()
	if hasUnknownNodeLabel(branch) {
		return e.generalBranch(graph, branch)
	}
	if result, ok := e.methodEmpty(graph, branch); ok {
		if e.work != nil {
			e.work.recordFastPath()
		}
		return result
	}
	if empty, ok := filteredLiteralEmpty(branch); ok {
		// Method dispatch precedes filtered/streaming admission in main.
		// A declined Method request must retain general evaluation semantics.
		if referencesMethod(branch) {
			return e.generalBranch(graph, branch)
		}
		if e.work != nil {
			match := branch.Clauses[0].(cypher.MatchClause)
			projection := branch.Clauses[1].(cypher.ProjectionClause)
			pattern := match.Patterns[0]
			if len(pattern.Nodes) == 1 && len(pattern.Relationships) == 0 && pattern.Nodes[0].Variable != "" && projection.Skip == nil && len(projection.OrderBy) == 0 {
				e.work.recordFilteredNodeLimitFastPath()
			} else {
				e.work.recordFastPath()
			}
		}
		return empty
	}
	if result, ok := e.filteredStringCount(graph, branch); ok {
		return result
	}
	if result, ok := e.ordinaryProjection(graph, branch); ok {
		if e.work != nil {
			e.work.recordFilteredNodeLimitFastPath()
		}
		return result
	}
	if result, ok := e.indexedDistinct(graph, branch); ok {
		return result
	}
	if !hasStreamingPagination(branch) {
		if result, ok := e.genericDistinct(graph, branch); ok {
			return result
		}
	}
	if result, ok := e.lazyFiltered(graph, branch); ok {
		return result
	}
	if result, ok := e.streamingPagination(graph, branch); ok {
		// This is the node-only admission of tryStreamingFilteredMatchLimit.
		// Main records the fast result after its iterator and ranking succeed.
		if e.work != nil {
			e.work.recordFastPath()
		}
		return result
	}
	if result, ok := e.orderedPropertyLimit(graph, branch); ok {
		if e.work != nil {
			e.work.recordFastPath()
		}
		return result
	}
	return e.generalBranch(graph, branch)
}

func (e evaluator) generalBranch(graph *store.Store, branch cypher.SingleQuery) Result {
	return e.generalBranchWithBound(graph, branch, -1, nil, false)
}

func (e evaluator) generalBranchWithBound(graph *store.Store, branch cypher.SingleQuery, projectionIndex int, maxRows *int, trailing bool) Result {
	if e.work != nil {
		e.work.recordGeneralFallback()
	}
	earlyBranch := branch
	if maxRows != nil {
		earlyBranch.Clauses = append([]cypher.Clause(nil), branch.Clauses...)
		if trailing {
			earlyBranch.Clauses = append(earlyBranch.Clauses, cypher.ProjectionClause{Limit: cypher.Literal{Value: int64(*maxRows)}})
		} else {
			projection := earlyBranch.Clauses[projectionIndex].(cypher.ProjectionClause)
			projection.Limit = cypher.Literal{Value: int64(*maxRows)}
			earlyBranch.Clauses[projectionIndex] = projection
		}
	}
	earlyLimit := e.computeEarlyLimit(earlyBranch)
	rows := []map[string]any{{}}
	columns := []string{}
	for index, clause := range branch.Clauses {
		e.check()
		switch c := clause.(type) {
		case cypher.MatchClause:
			if sought, ok := e.tryGeneralElementIDSeek(graph, rows, c); ok {
				rows = sought
			} else if earlyLimit > 0 && !c.Optional {
				rows = e.matchWithLimit(graph, rows, c, earlyLimit)
			} else {
				rows = e.match(graph, rows, c)
			}
		case cypher.UnwindClause:
			next := []map[string]any{}
			for _, r := range rows {
				value := e.eval(c.Expression, r)
				if value == nil {
					continue
				}
				list, ok := value.([]any)
				if !ok {
					continue
				}
				for _, v := range list {
					e.check()
					n := e.cloneRow(r)
					e.bind(n, c.Variable, v)
					next = append(next, n)
				}
			}
			rows = next
		case cypher.ProjectionClause:
			if index == projectionIndex && maxRows != nil {
				rows, columns = e.projectWithBound(rows, c, maxRows)
			} else {
				rows, columns = e.project(rows, c)
			}
		}
	}
	if trailing && maxRows != nil && len(rows) > *maxRows {
		rows = rows[:*maxRows]
	}
	return Result{Columns: columns, Rows: rows}
}
func (e evaluator) matches(value any, n cypher.NodePattern, row map[string]any) bool {
	var property func(string) any
	switch v := value.(type) {
	case *candidateSlot:
		for _, label := range n.Labels {
			if !v.matchesLabel(label) {
				return false
			}
		}
		property = v.property
	case qualifiedNode:
		for _, label := range n.Labels {
			if !matchesLabel(v.Node, label) {
				return false
			}
		}
		property = func(k string) any { return qualifiedProperty(v, k) }
	case qualifiedMethod:
		for _, label := range n.Labels {
			if !strings.EqualFold(label, "Method") {
				return false
			}
		}
		property = func(k string) any { return qualifiedProperty(v, k) }
	case store.Node:
		for _, label := range n.Labels {
			if !matchesLabel(v, label) {
				return false
			}
		}
		property = func(k string) any { return NodeProperty(v, k) }
	case store.MethodDescriptor:
		for _, label := range n.Labels {
			if !strings.EqualFold(label, "Method") {
				return false
			}
		}
		property = func(k string) any { return methodProperty(v, k) }
	default:
		return false
	}
	for _, k := range n.PropertyKeys {
		if equal(property(k), e.eval(n.Properties[k], row)) != true {
			return false
		}
	}
	return true
}
func methodProperty(m store.MethodDescriptor, key string) any {
	switch key {
	case "signature":
		return m.Signature()
	case "class":
		return m.DeclaringClass
	case "name":
		return m.Name
	case "return_type":
		return m.ReturnType
	case "parameter_types":
		r := make([]any, len(m.ParameterTypes))
		for i, v := range m.ParameterTypes {
			r[i] = v
		}
		return r
	}
	return nil
}
func (e evaluator) project(rows []map[string]any, c cypher.ProjectionClause) ([]map[string]any, []string) {
	return e.projectWithBound(rows, c, nil)
}

func (e evaluator) projectWithBound(rows []map[string]any, c cypher.ProjectionClause, maxRows *int) ([]map[string]any, []string) {
	columns := []string{}
	if c.All {
		if len(rows) > 0 {
			for _, key := range e.rowKeys(rows[0]) {
				if key != provenanceKey {
					columns = append(columns, key)
				}
			}
		}
	} else {
		for _, item := range c.Items {
			name := item.Alias
			if name == "" {
				name = columnName(item.Expression)
			}
			columns = append(columns, name)
		}
	}
	hasAggregate := false
	for _, item := range c.Items {
		hasAggregate = hasAggregate || containsAggregate(item.Expression)
	}
	type projected struct {
		row, source map[string]any
		sort        []any
	}
	out := []projected{}
	add := func(row, source map[string]any) {
		mergeProvenance(row, source)
		scope := e.cloneRow(source)
		for k, v := range row {
			e.bind(scope, k, v)
		}
		if c.Where != nil && e.eval(c.Where, scope) != true {
			return
		}
		var sortKeys []any
		precompute := false
		if !hasAggregate && !c.Distinct {
			for _, item := range c.OrderBy {
				variable, ok := item.Expression.(cypher.Variable)
				if _, present := row[variable.Name]; !ok || !present {
					precompute = true
					break
				}
			}
		}
		if precompute {
			sortKeys = make([]any, len(c.OrderBy))
			for i, item := range c.OrderBy {
				sortKeys[i] = e.eval(item.Expression, scope)
			}
		}

		out = append(out, projected{row, source, sortKeys})
	}
	if hasAggregate {
		groups := map[string][]map[string]any{}
		order := []string{}
		groupItems := []int{}
		for i, item := range c.Items {
			if !containsAggregate(item.Expression) {
				groupItems = append(groupItems, i)
			}
		}
		for _, row := range rows {
			values := make([]any, len(groupItems))
			for j, i := range groupItems {
				values[j] = e.eval(c.Items[i].Expression, row)
			}
			k := key(values)
			if _, ok := groups[k]; !ok {
				order = append(order, k)
			}
			groups[k] = append(groups[k], row)
		}
		if len(rows) == 0 && len(groupItems) == 0 {
			order = append(order, "")
			groups[""] = nil
		}
		for _, k := range order {
			group := groups[k]
			source := map[string]any{}
			if len(group) > 0 {
				source = e.cloneRow(group[0])
				for _, r := range group {
					mergeProvenance(source, r)
				}
			}
			row := map[string]any{}
			for i, item := range c.Items {
				if containsAggregate(item.Expression) {
					e.bind(row, columns[i], e.evaluateAggregate(item.Expression, group))
				} else {
					e.bind(row, columns[i], e.eval(item.Expression, source))
				}
			}
			add(row, source)
		}
	} else {
		for _, source := range rows {
			row := map[string]any{}
			if c.All {
				row = e.cloneRow(source)
			} else {
				for i, item := range c.Items {
					e.bind(row, columns[i], e.eval(item.Expression, source))
				}
			}
			add(row, source)
		}
	}
	if c.Distinct {
		seen := map[string]map[string]any{}
		unique := out[:0]
		for _, r := range out {
			k := rowKey(r.row, columns)
			if previous, ok := seen[k]; !ok {
				seen[k] = r.row
				unique = append(unique, r)
			} else {
				mergeProvenance(previous, r.row)
			}
		}
		out = unique
	}
	if len(c.OrderBy) > 0 {
		sort.SliceStable(out, func(i, j int) bool {
			e.check()
			for k, item := range c.OrderBy {
				left, right := any(nil), any(nil)
				if out[i].sort != nil {
					left = out[i].sort[k]
				} else {
					left = e.eval(item.Expression, out[i].row)
				}
				if out[j].sort != nil {
					right = out[j].sort[k]
				} else {
					right = e.eval(item.Expression, out[j].row)
				}
				cmp := compare(left, right)
				if cmp != 0 {
					if item.Descending {
						return cmp > 0
					}
					return cmp < 0
				}
			}
			return false
		})
	}
	bindingsAt := func(index int) map[string]any {
		if index < len(out) {
			return out[index].row
		}
		return map[string]any{}
	}
	start, end := 0, len(out)
	if c.Skip != nil {
		start = min(end, e.count(c.Skip, bindingsAt(0)))
	}
	if maxRows != nil {
		end = start + min(end-start, *maxRows)
	}
	if c.Limit != nil {
		bindings := bindingsAt(start)
		if maxRows != nil && start == end {
			bindings = map[string]any{}
		}
		end = start + min(end-start, e.count(c.Limit, bindings))
	}
	result := make([]map[string]any, 0, end-start)
	for _, r := range out[start:end] {
		result = append(result, r.row)
	}
	return result, columns
}
func (e evaluator) count(expr cypher.Expr, bindings map[string]any) int {
	n := cypherCountValue(e.eval(expr, bindings))
	if n < 0 {
		functionError("IllegalArgumentException", fmt.Sprintf("Requested element count %d is less than zero.", n))
	}
	return int(n)
}
func rowKey(row map[string]any, columns []string) string {
	v := make([]any, len(columns))
	for i, k := range columns {
		v[i] = row[k]
	}
	return key(v)
}
func distinctRows(rows []map[string]any, columns []string) []map[string]any {
	seen := map[string]map[string]any{}
	out := rows[:0]
	for _, r := range rows {
		visible := clone(r)
		delete(visible, provenanceKey)
		k := key(visible)
		if previous, ok := seen[k]; !ok {
			seen[k] = r
			out = append(out, r)
		} else {
			mergeProvenance(previous, r)
		}
	}
	return out
}
func validate(q *cypher.Query) {
	var expression func(cypher.Expr, bool)
	expression = func(expr cypher.Expr, allowAggregate bool) {
		switch x := expr.(type) {
		case nil, cypher.Literal, cypher.Variable, cypher.Parameter:
		case cypher.Property:
			expression(x.Object, false)
		case cypher.Unary:
			expression(x.Operand, false)
		case cypher.Binary:
			expression(x.Left, false)
			expression(x.Right, false)
		case cypher.Call:
			for _, a := range x.Arguments {
				expression(a, false)
			}
		case cypher.List:
			for _, a := range x.Elements {
				expression(a, false)
			}
		case cypher.Map:
			for _, a := range x.Entries {
				expression(a, false)
			}
		case cypher.Case:
			expression(x.Test, false)
			expression(x.Else, false)
			for _, w := range x.Whens {
				expression(w.Condition, false)
				expression(w.Result, false)
			}
		case cypher.ListComprehension:
			expression(x.List, false)
			expression(x.Where, false)
			expression(x.Projection, false)
		case cypher.Predicate:
			expression(x.List, false)
			expression(x.Where, false)
		case cypher.Index:
			expression(x.Object, false)
			expression(x.Index, false)
		case cypher.Slice:
			expression(x.Object, false)
			expression(x.From, false)
			expression(x.To, false)
		default:
			fail(fmt.Sprintf("unsupported expression %T", expr))
		}
	}
	for _, b := range q.Branches {
		for _, clause := range b.Clauses {
			switch c := clause.(type) {
			case cypher.MatchClause:
				for _, p := range c.Patterns {
					for _, rel := range p.Relationships {
						for _, value := range rel.Properties {
							expression(value, false)
						}
					}
					for _, n := range p.Nodes {
						for _, v := range n.Properties {
							expression(v, false)
						}
					}
				}
				expression(c.Where, false)
			case cypher.UnwindClause:
				expression(c.Expression, false)
			case cypher.ProjectionClause:
				for _, item := range c.Items {
					expression(item.Expression, true)
				}
				expression(c.Where, false)
				for _, s := range c.OrderBy {
					expression(s.Expression, false)
				}
				expression(c.Skip, false)
				expression(c.Limit, false)
			default:
				fail(fmt.Sprintf("unsupported Cypher clause %T", clause))
			}
		}
	}
}
