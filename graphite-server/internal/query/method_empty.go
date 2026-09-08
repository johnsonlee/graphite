package query

import (
	"runtime"
	"slices"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// referencesMethod mirrors MethodQueryExecutor.referencesMethod, including
// Method labels in patterns that the dedicated executor subsequently declines.
func referencesMethod(branch cypher.SingleQuery) bool {
	for _, clause := range branch.Clauses {
		if match, ok := clause.(cypher.MatchClause); ok {
			for _, pattern := range match.Patterns {
				for _, node := range pattern.Nodes {
					for _, label := range node.Labels {
						if strings.EqualFold(label, "Method") {
							return true
						}
					}
				}
			}
		}
	}
	return false
}

// methodEmpty implements only MethodQueryExecutor's provably non-scanning
// successes. It does not evaluate WHERE, ORDER BY, projection, or node data.
// Its caller records the ordinary fast path after this function succeeds.
func (e evaluator) methodEmpty(graph *store.Store, branch cypher.SingleQuery) (Result, bool) {
	if len(e.parameters) != 0 || len(branch.Clauses) != 2 {
		return Result{}, false
	}
	m, ok := branch.Clauses[0].(cypher.MatchClause)
	if !ok || m.Optional || len(m.Patterns) != 1 {
		return Result{}, false
	}
	pattern := m.Patterns[0]
	if pattern.PathVariable != "" || len(pattern.Nodes) != 1 || len(pattern.Relationships) != 0 {
		return Result{}, false
	}
	node := pattern.Nodes[0]
	if node.Variable == "" || len(node.Labels) != 1 || !strings.EqualFold(node.Labels[0], "Method") {
		return Result{}, false
	}
	r, ok := branch.Clauses[1].(cypher.ProjectionClause)
	if !ok || r.With || r.Where != nil {
		return Result{}, false
	}
	for _, item := range r.Items {
		if containsAggregate(item.Expression) {
			return Result{}, false
		}
	}
	skip, ok := methodEmptyCount(r.Skip, 0)
	if !ok {
		return Result{}, false
	}
	limit, ok := methodEmptyCount(r.Limit, 1<<31-1)
	if !ok || limit != 0 {
		return Result{}, false
	}
	// scanPlan validates inline properties before its requested==0 guard.
	// Preserve insertion order: signature can set three independently checked
	// fields, and a subsequent incompatible equality makes admission fail.
	predicate := methodEmptyPredicate{values: map[string]string{}}
	visited := map[string]bool{}
	for _, property := range node.PropertyKeys {
		value, exists := node.Properties[property]
		if !exists || visited[property] {
			continue
		}
		visited[property] = true
		if !predicate.addEquality(property, value) {
			return Result{}, false
		}
	}
	// Hand-built ASTs can omit PropertyKeys. Equality constraints commute for
	// successful admission; still validate every property in such an AST.
	for property, value := range node.Properties {
		if !visited[property] && !predicate.addEquality(property, value) {
			return Result{}, false
		}
	}
	if skip != 0 {
		// requested==skip here. Ordered and DISTINCT consumers can scan even
		// with LIMIT 0, so they remain outside this helper.
		if r.Distinct || len(r.OrderBy) != 0 {
			return Result{}, false
		}
		predicate.addWhereGraphIDs(m.Where, node.Variable)
		sources := e.graphs
		if !e.cross {
			sources = nil
			if graph != nil {
				sources = []Graph{{ID: "single", Store: graph}}
			}
		}
		var route map[string]bool
		if !e.sourceScopeApplied {
			route = methodEmptyRoute(m.Where, node.Variable)
			if id, ok := methodEmptyLiteral(node.Properties["graphId"]).(string); ok {
				route = streamingGraphIntersection(route, map[string]bool{methodEmptyString(id): true})
			}
		}
		selected := 0
		for _, source := range sources {
			if route != nil && !route[methodEmptyString(source.ID)] {
				continue
			}
			if id, constrained := predicate.values["graphId"]; constrained && methodEmptyString(source.ID) != id {
				continue
			}
			selected++
		}
		// An empty parallel source wave also returns without inspecting data.
		if selected > 1 && skip > runtime.NumCPU() && skip <= 5000 {
			return Result{}, false
		}
		// Sequential execution checks cancellation before its first source,
		// then sees rows.size >= limitCount and breaks before methods().
		if selected != 0 {
			e.check()
		}
		// MethodQueryExecutor also checks after executeStreamingRows returns.
		e.check()
	}
	columns := make([]string, 0, len(r.Items)+1)
	if r.All {
		columns = append(columns, "*")
	}
	for _, item := range r.Items {
		column := item.Alias
		if column == "" {
			column = columnName(item.Expression)
		}
		columns = append(columns, column)
	}
	return Result{Columns: columns, Rows: []map[string]any{}}, true
}

// Unlike the general pipeline, Method accepts only Literal Number counts.
// Number.toLong followed by [0, Int.MAX_VALUE] clamping has the same result
// as javaInt followed by a zero lower bound, including NaN and infinities.
func methodEmptyCount(expr cypher.Expr, fallback int) (int, bool) {
	if expr == nil {
		return fallback, true
	}
	literal, ok := expr.(cypher.Literal)
	if !ok {
		return 0, false
	}
	n, ok := number(literal.Value)
	if !ok {
		return 0, false
	}
	return max(0, int(javaInt(n))), true
}

type methodEmptyPredicate struct {
	values        map[string]string
	parameters    []string
	hasParameters bool
}

func methodEmptyString(value string) string { return javaFromUTF16(javaUTF16(value)) }

// Kotlin Regex.escape delegates to Java Pattern.quote, not RE2 QuoteMeta.
func methodEmptyQuote(value string) string {
	return "\\Q" + strings.ReplaceAll(methodEmptyString(value), "\\E", "\\E\\\\E\\Q") + "\\E"
}

func (p *methodEmptyPredicate) set(property, value string) bool {
	value = methodEmptyString(value)
	if previous, present := p.values[property]; present && previous != value {
		return false
	}
	p.values[property] = value
	return true
}

func (p *methodEmptyPredicate) setParameters(values []string) bool {
	if p.hasParameters && !slices.Equal(p.parameters, values) {
		return false
	}
	p.parameters, p.hasParameters = values, true
	return true
}

func (p *methodEmptyPredicate) addEquality(property string, expression cypher.Expr) bool {
	value := methodEmptyLiteral(expression)
	if property == "parameter_types" {
		var parameters []string
		switch values := value.(type) {
		case []any:
			for _, value := range values {
				text, ok := value.(string)
				if !ok {
					return false
				}
				parameters = append(parameters, methodEmptyQuote(text))
			}
		case []string:
			for _, text := range values {
				parameters = append(parameters, methodEmptyQuote(text))
			}
		default:
			return false
		}
		return p.setParameters(parameters)
	}
	text, ok := value.(string)
	if !ok {
		return false
	}
	switch property {
	case "graphId":
		return p.set(property, text)
	case "class", "name", "return_type":
		return p.set(property, methodEmptyQuote(text))
	case "signature":
		open, close := strings.LastIndex(text, "("), strings.LastIndex(text, ")")
		if open <= 0 || close != len(text)-1 {
			return false
		}
		separator := strings.LastIndex(text[:open], ".")
		if separator <= 0 {
			return false
		}
		var parameters []string
		if raw := text[open+1 : close]; raw != "" {
			for _, parameter := range strings.Split(raw, ",") {
				parameters = append(parameters, methodEmptyQuote(parameter))
			}
		}
		return p.set("class", methodEmptyQuote(text[:separator])) &&
			p.set("name", methodEmptyQuote(text[separator+1:open])) && p.setParameters(parameters)
	}
	return false
}

func methodEmptyLiteral(expression cypher.Expr) any {
	switch value := expression.(type) {
	case cypher.Literal:
		return value.Value
	case cypher.List:
		items := make([]any, len(value.Elements))
		for i, element := range value.Elements {
			items[i] = methodEmptyLiteral(element)
			if items[i] == nil {
				return nil
			}
		}
		return items
	}
	return nil
}

// WHERE failing to push down is residual, never a scanPlan rejection. Only
// its graphId mutations affect this helper's non-scanning source-count gate.
// addConjuncts visits both sides even when the first side cannot be pushed.
func (p *methodEmptyPredicate) addWhereGraphIDs(expression cypher.Expr, variable string) {
	b, ok := expression.(cypher.Binary)
	if !ok {
		return
	}
	if b.Op == "AND" {
		p.addWhereGraphIDs(b.Left, variable)
		p.addWhereGraphIDs(b.Right, variable)
		return
	}
	if b.Op != "=" {
		return
	}
	var value cypher.Expr
	if distinctGraphReference(b.Left, variable) {
		value = b.Right
	} else if distinctGraphReference(b.Right, variable) {
		value = b.Left
	} else {
		return
	}
	if text, ok := methodEmptyLiteral(value).(string); ok {
		p.set("graphId", text)
	}
}

// Root graphSourceScope is distinct from MethodPredicate's partial setOnce
// route: AND intersects every known constraint, and OR needs both sides.
// Parameters cannot resolve here because methodEmpty requires an empty map.
func methodEmptyRoute(expression cypher.Expr, variable string) map[string]bool {
	b, ok := expression.(cypher.Binary)
	if !ok {
		return nil
	}
	switch b.Op {
	case "AND":
		return streamingGraphIntersection(methodEmptyRoute(b.Left, variable), methodEmptyRoute(b.Right, variable))
	case "OR":
		left, right := methodEmptyRoute(b.Left, variable), methodEmptyRoute(b.Right, variable)
		if left == nil || right == nil {
			return nil
		}
		for id := range right {
			left[id] = true
		}
		return left
	case "=":
		if distinctGraphReference(b.Left, variable) {
			if literal, ok := b.Right.(cypher.Literal); ok {
				if text, ok := literal.Value.(string); ok {
					return map[string]bool{methodEmptyString(text): true}
				}
			}
		}
		if distinctGraphReference(b.Right, variable) {
			if literal, ok := b.Left.(cypher.Literal); ok {
				if text, ok := literal.Value.(string); ok {
					return map[string]bool{methodEmptyString(text): true}
				}
			}
		}
	case "IN":
		if !distinctGraphReference(b.Left, variable) {
			return nil
		}
		list, ok := b.Right.(cypher.List)
		if !ok {
			return nil
		}
		ids := map[string]bool{}
		for _, element := range list.Elements {
			literal, ok := element.(cypher.Literal)
			if !ok {
				return nil
			}
			text, ok := literal.Value.(string)
			if !ok {
				return nil
			}
			ids[methodEmptyString(text)] = true
		}
		return ids
	}
	return nil
}
