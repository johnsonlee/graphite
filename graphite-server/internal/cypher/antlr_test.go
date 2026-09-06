package cypher

import "testing"

// These cases exercise ambiguities resolved by the shared grammar's adaptive
// prediction, rather than a parallel handwritten interpretation of Cypher.
func TestSharedGrammarKeywordFunctionsAndQualifiedNames(t *testing.T) {
	q := mustParse(t, "MATCH (match:RETURN) RETURN count(DISTINCT match.id) AS `count`, exists(match.value) AS exists")
	assertEqual(t, q.Branches[0].Clauses[0], MatchClause{Patterns: []Pattern{{Nodes: []NodePattern{{Variable: "match", Labels: []string{"RETURN"}}}}}})
	assertEqual(t, q.Branches[0].Clauses[1].(ProjectionClause).Items, []ReturnItem{{Expression: Call{Name: "count", Distinct: true, Arguments: []Expr{Property{Object: Variable{Name: "match"}, Key: "id"}}}, Alias: "count", Text: "count(DISTINCT match.id)"}, {Expression: Call{Name: "exists", Arguments: []Expr{Property{Object: Variable{Name: "match"}, Key: "value"}}}, Alias: "exists", Text: "exists(match.value)"}})
	assertEqual(t, mustExpr(t, "`a.b`.`return`($`weird name`)"), Call{Name: "a.b.return", Arguments: []Expr{Parameter{Name: "weird name"}}})
}
func TestSharedGrammarPreservesOrderedMapEntriesAndExpressionText(t *testing.T) {
	assertEqual(t, mustExpr(t, "{z: 1, a: 2, z: 3}"), Map{Entries: map[string]Expr{"z": Literal{Value: int32(3)}, "a": Literal{Value: int32(2)}}, Keys: []string{"z", "a"}})
	q := mustParse(t, "RETURN '中文' + /* gap */ '😀' AS message")
	assertEqual(t, q.Branches[0].Clauses[0].(ProjectionClause).Items, []ReturnItem{{Expression: Binary{Op: "+", Left: Literal{Value: "中文"}, Right: Literal{Value: "😀"}}, Alias: "message", Text: "'中文' + /* gap */ '😀'"}})
}

func TestInlinePropertiesPreserveFirstInsertionOrderAndLastValue(t *testing.T) {
	q := mustParse(t, "MATCH (a {z:1,a:2,z:3})-[r {y:4,b:5,y:6}]->(b) RETURN a")
	p := q.Branches[0].Clauses[0].(MatchClause).Patterns[0]
	assertEqual(t, p.Nodes[0], NodePattern{
		Variable: "a", Properties: map[string]Expr{"z": Literal{Value: int32(3)}, "a": Literal{Value: int32(2)}},
		PropertyKeys: []string{"z", "a"},
	})
	assertEqual(t, p.Relationships[0], RelationshipPattern{
		Variable: "r", Direction: Outgoing,
		Properties:   map[string]Expr{"y": Literal{Value: int32(6)}, "b": Literal{Value: int32(5)}},
		PropertyKeys: []string{"y", "b"},
	})
}
func TestSharedGrammarChainedPredicatesAndPrecedence(t *testing.T) {
	assertEqual(t, mustExpr(t, "a < b < c"), Binary{Op: "<", Left: Binary{Op: "<", Left: Variable{Name: "a"}, Right: Variable{Name: "b"}}, Right: Variable{Name: "c"}})
	assertEqual(t, mustExpr(t, "a NOT STARTS WITH 'x' IS NOT NULL"), Unary{Op: "IS NOT NULL", Operand: Binary{Op: "NOT STARTS WITH", Left: Variable{Name: "a"}, Right: Literal{Value: "x"}}})
	assertEqual(t, mustExpr(t, "any(x IN [true, null])"), Predicate{Name: "any", Variable: "x", List: List{Elements: []Expr{Literal{Value: true}, Literal{}}}})
}
func TestLexerErrorsCannotRecoverIntoAcceptedQuery(t *testing.T) {
	for _, source := range []string{"RETURN 1 @", "RETURN 'ok' !", "RETURN 01", "RETURN `unfinished", "RETURN 1 # trailing", "RETURN 0x"} {
		t.Run(source, func(t *testing.T) {
			q, err := Parse(source)
			if err == nil {
				t.Fatalf("invalid input accepted: %#v", q)
			}
		})
	}
}
