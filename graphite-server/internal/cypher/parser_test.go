package cypher

import (
	"reflect"
	"testing"
)

func mustParse(t *testing.T, s string) *Query {
	t.Helper()
	q, e := Parse(s)
	if e != nil {
		t.Fatal(e)
	}
	return q
}
func mustExpr(t *testing.T, s string) Expr {
	t.Helper()
	e, err := ParseExpression(s)
	if err != nil {
		t.Fatal(err)
	}
	return e
}
func assertEqual(t *testing.T, got, want any) {
	t.Helper()
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %#v\nwant %#v", got, want)
	}
}
func TestMatchTraversalAndOptionalPredicate(t *testing.T) {
	q := mustParse(t, "MATCH p=(a:Class {name: $name})-[r:EXTENDS|:IMPLEMENTS*1..3]->(b:Class) OPTIONAL MATCH (b)<-[:CALLS]-(c) WHERE c.name STARTS WITH 'x' RETURN p, a.name AS name")
	if len(q.Branches) != 1 || len(q.Branches[0].Clauses) != 3 {
		t.Fatalf("unexpected clauses: %#v", q)
	}
	first := q.Branches[0].Clauses[0].(MatchClause)
	one, three := 1, 3
	assertEqual(t, first, MatchClause{Patterns: []Pattern{{PathVariable: "p", Nodes: []NodePattern{{Variable: "a", Labels: []string{"Class"}, Properties: map[string]Expr{"name": Parameter{Name: "name"}}, PropertyKeys: []string{"name"}}, {Variable: "b", Labels: []string{"Class"}}}, Relationships: []RelationshipPattern{{Variable: "r", Types: []string{"EXTENDS", "IMPLEMENTS"}, Direction: Outgoing, VariableLength: true, MinHops: &one, MaxHops: &three}}}}})
	second := q.Branches[0].Clauses[1].(MatchClause)
	if !second.Optional {
		t.Fatal("lost OPTIONAL")
	}
	assertEqual(t, second.Where, Binary{Op: "STARTS WITH", Left: Property{Object: Variable{Name: "c"}, Key: "name"}, Right: Literal{Value: "x"}})
	assertEqual(t, second.Patterns[0].Relationships[0], RelationshipPattern{Types: []string{"CALLS"}, Direction: Incoming})
	assertEqual(t, q.Branches[0].Clauses[2].(ProjectionClause).Items, []ReturnItem{{Expression: Variable{Name: "p"}, Text: "p"}, {Expression: Property{Object: Variable{Name: "a"}, Key: "name"}, Alias: "name", Text: "a.name"}})
}
func TestProjectionUnwindUnion(t *testing.T) {
	q := mustParse(t, "UNWIND $items AS x WITH DISTINCT x AS y WHERE y > 2 ORDER BY y DESC SKIP 1 LIMIT $limit RETURN y UNION ALL RETURN 42 AS y;")
	assertEqual(t, q.UnionAll, []bool{true})
	assertEqual(t, q.Branches[0].Clauses[0], UnwindClause{Expression: Parameter{Name: "items"}, Variable: "x"})
	assertEqual(t, q.Branches[0].Clauses[1], ProjectionClause{With: true, Distinct: true, Items: []ReturnItem{{Expression: Variable{Name: "x"}, Alias: "y", Text: "x"}}, Where: Binary{Op: ">", Left: Variable{Name: "y"}, Right: Literal{Value: int32(2)}}, OrderBy: []SortItem{{Expression: Variable{Name: "y"}, Descending: true}}, Skip: Literal{Value: int32(1)}, Limit: Parameter{Name: "limit"}})
	assertEqual(t, q.Branches[1].Clauses[0], ProjectionClause{Items: []ReturnItem{{Expression: Literal{Value: int32(42)}, Alias: "y", Text: "42"}}})
}
func TestOperatorPrecedence(t *testing.T) {
	assertEqual(t, mustExpr(t, "NOT a = 1 OR b + 2 * 3 >= 9 AND c IS NOT NULL"), Binary{Op: "OR", Left: Unary{Op: "NOT", Operand: Binary{Op: "=", Left: Variable{Name: "a"}, Right: Literal{Value: int32(1)}}}, Right: Binary{Op: "AND", Left: Binary{Op: ">=", Left: Binary{Op: "+", Left: Variable{Name: "b"}, Right: Binary{Op: "*", Left: Literal{Value: int32(2)}, Right: Literal{Value: int32(3)}}}, Right: Literal{Value: int32(9)}}, Right: Unary{Op: "IS NOT NULL", Operand: Variable{Name: "c"}}}})
	assertEqual(t, mustExpr(t, "-2 ^ 3 ^ 4"), Binary{Op: "^", Left: Unary{Op: "-", Operand: Literal{Value: int32(2)}}, Right: Binary{Op: "^", Left: Literal{Value: int32(3)}, Right: Literal{Value: int32(4)}}})
}
func TestCaseCollectionsCallsAndSubscripts(t *testing.T) {
	assertEqual(t, mustExpr(t, "CASE n.x WHEN 1 THEN {name: 'one'} ELSE {name: 'other'} END"), Case{Test: Property{Object: Variable{Name: "n"}, Key: "x"}, Whens: []When{{Condition: Literal{Value: int32(1)}, Result: Map{Entries: map[string]Expr{"name": Literal{Value: "one"}}, Keys: []string{"name"}}}}, Else: Map{Entries: map[string]Expr{"name": Literal{Value: "other"}}, Keys: []string{"name"}}})
	assertEqual(t, mustExpr(t, "[x IN $xs WHERE x > 1 | x * 2]"), ListComprehension{Variable: "x", List: Parameter{Name: "xs"}, Where: Binary{Op: ">", Left: Variable{Name: "x"}, Right: Literal{Value: int32(1)}}, Projection: Binary{Op: "*", Left: Variable{Name: "x"}, Right: Literal{Value: int32(2)}}})
	assertEqual(t, mustExpr(t, "all(x IN [1, 2] WHERE x > 0)"), Predicate{Name: "all", Variable: "x", List: List{Elements: []Expr{Literal{Value: int32(1)}, Literal{Value: int32(2)}}}, Where: Binary{Op: ">", Left: Variable{Name: "x"}, Right: Literal{Value: int32(0)}}})
	assertEqual(t, mustExpr(t, "foo.bar(DISTINCT xs[1..3])[0].name"), Property{Object: Index{Object: Call{Name: "foo.bar", Distinct: true, Arguments: []Expr{Slice{Object: Variable{Name: "xs"}, From: Literal{Value: int32(1)}, To: Literal{Value: int32(3)}}}}, Index: Literal{Value: int32(0)}}, Key: "name"})
	assertEqual(t, mustExpr(t, "count(*)"), Call{Name: "count", Star: true})
}
func TestLiteralDecodingCommentsAndNames(t *testing.T) {
	assertEqual(t, mustExpr(t, "[0xFF, 0o17, .5, 1e3, true, NULL, 'it''s', '\\n\\u0041\\q', '\\uD83D\\uDE00']"), List{Elements: []Expr{Literal{Value: int64(255)}, Literal{Value: int64(15)}, Literal{Value: 0.5}, Literal{Value: 1000.0}, Literal{Value: true}, Literal{}, Literal{Value: "it's"}, Literal{Value: "\nA\\q"}, Literal{Value: "😀"}}})
	q := mustParse(t, "/* comment */ mAtCh (`MATCH`:`class name`) // line\n RETURN `MATCH`.name AS `return value`")
	assertEqual(t, q.Branches[0].Clauses[0], MatchClause{Patterns: []Pattern{{Nodes: []NodePattern{{Variable: "MATCH", Labels: []string{"class name"}}}}}})
	assertEqual(t, q.Branches[0].Clauses[1].(ProjectionClause).Items, []ReturnItem{{Expression: Property{Object: Variable{Name: "MATCH"}, Key: "name"}, Alias: "return value", Text: "`MATCH`.name"}})
}
func TestRejectInvalidAndUnsupportedQueries(t *testing.T) {
	for _, s := range []string{"RETURN", "MATCH (a", "MATCH (a)-[:X*1.2]->(b) RETURN b", "RETURN count(DISTINCT *)", "RETURN sum(*)", "RETURN 1 UNION", "CALL db.labels()", "RETURN 1 != 2", "RETURN 'unterminated", "RETURN /* unterminated", "RETURN 1e", "RETURN 0o9", "RETURN [1,]", "RETURN xs[..]", "RETURN CASE ELSE 1 END"} {
		t.Run(s[:min(len(s), 45)], func(t *testing.T) {
			_, e := Parse(s)
			if e == nil {
				t.Fatalf("accepted invalid/unsupported query %q", s)
			}
			if _, ok := e.(*ParseError); !ok {
				t.Fatalf("not a ParseError: %T", e)
			}
		})
	}
}
func TestRelationshipRangesAndDirections(t *testing.T) {
	tests := []struct {
		pattern  string
		dir      Direction
		min, max *int
		variable bool
	}{{"(a)--(b)", Both, nil, nil, false}, {"(a)<-->(b)", Both, nil, nil, false}, {"(a)-[*]->(b)", Outgoing, nil, nil, true}, {"(a)<-[*..4]-(b)", Incoming, nil, intPointer(4), true}, {"(a)-[*2..]-(b)", Both, intPointer(2), nil, true}, {"(a)-[*3]-(b)", Both, intPointer(3), intPointer(3), true}}
	for _, tt := range tests {
		t.Run(tt.pattern, func(t *testing.T) {
			p := mustParse(t, "MATCH "+tt.pattern+" RETURN a").Branches[0].Clauses[0].(MatchClause).Patterns[0]
			assertEqual(t, p.Relationships[0], RelationshipPattern{Direction: tt.dir, MinHops: tt.min, MaxHops: tt.max, VariableLength: tt.variable})
		})
	}
}
func intPointer(n int) *int { return &n }
