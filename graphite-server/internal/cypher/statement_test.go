package cypher

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"reflect"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestRawSemicolonStatementsSharePipeline(t *testing.T) {
	q := mustParse(t, "; UNWIND [1,2] AS x; ;RETURN x + 1 AS y;")
	assertEqual(t, q, &Query{Branches: []SingleQuery{{Clauses: []Clause{UnwindClause{Expression: List{Elements: []Expr{Literal{Value: int32(1)}, Literal{Value: int32(2)}}}, Variable: "x"}, ProjectionClause{Items: []ReturnItem{{Expression: Binary{Op: "+", Left: Variable{Name: "x"}, Right: Literal{Value: int32(1)}}, Alias: "y", Text: "x + 1"}}}}}}})
	q = mustParse(t, "RETURN 1 AS a UNION ALL RETURN 2 AS a; RETURN a+1 AS b")
	assertEqual(t, q.UnionAll, []bool{true})
	if len(q.Branches) != 2 || len(q.Branches[0].Clauses) != 1 || len(q.Branches[1].Clauses) != 2 {
		t.Fatalf("statement boundary incorrectly created a UNION branch: %#v", q)
	}
	assertEqual(t, q.Branches[1].Clauses[1], ProjectionClause{Items: []ReturnItem{{Expression: Binary{Op: "+", Left: Variable{Name: "a"}, Right: Literal{Value: int32(1)}}, Alias: "b", Text: "a+1"}}})
}
func TestKotlinBlankAndRawSplitOddities(t *testing.T) {
	for _, source := range []string{"", " \t\r\n", "\u001c", "\u00a0", "\u2007"} {
		assertEqual(t, mustParse(t, source), &Query{Branches: []SingleQuery{{}}})
	}
	for _, source := range []string{"\u0085", "; RETURN 1;", "RETURN 1;;", "RETURN ';' AS value", "RETURN 'a;b' AS value", "RETURN 1 /* ; comment */"} {
		if _, err := Parse(source); err == nil {
			t.Fatalf("accepted text rejected by Kotlin raw split: %q", source)
		}
	}
}
func TestNoInventedBracketNestingLimit(t *testing.T) {
	q := mustParse(t, "RETURN "+strings.Repeat("(", 600)+"1"+strings.Repeat(")", 600)+" AS value")
	item := q.Branches[0].Clauses[0].(ProjectionClause).Items[0]
	assertEqual(t, item.Expression, Literal{Value: int32(1)})
	if item.Alias != "value" {
		t.Fatalf("lost alias: %#v", item)
	}
}
func TestMutationASTFollowsKotlinAdapter(t *testing.T) {
	q := mustParse(t, "CREATE (n:Thing {name:'x'})")
	assertEqual(t, q.Branches[0].Clauses[0], CreateClause{Patterns: []Pattern{{Nodes: []NodePattern{{Variable: "n", Labels: []string{"Thing"}, Properties: map[string]Expr{"name": Literal{Value: "x"}}}}}}})
	assertEqual(t, mustParse(t, "MERGE (n:Thing)").Branches[0].Clauses[0], CreateClause{Patterns: []Pattern{{Nodes: []NodePattern{{Variable: "n", Labels: []string{"Thing"}}}}}})
	assertEqual(t, mustParse(t, "DETACH DELETE n,n.x").Branches[0].Clauses[0], DeleteClause{Detach: true, Expressions: []Expr{Variable{Name: "n"}, Property{Object: Variable{Name: "n"}, Key: "x"}}})
	assertEqual(t, mustParse(t, "SET n.x=1,n += {a:2},n={b:3},n:A:B").Branches[0].Clauses[0], SetClause{Items: []SetItem{{Kind: SetProperty, Variable: "n", Property: "x", Expression: Literal{Value: int32(1)}}, {Kind: SetMergeProperties, Variable: "n", Expression: Map{Entries: map[string]Expr{"a": Literal{Value: int32(2)}}, Keys: []string{"a"}}}, {Kind: SetAllProperties, Variable: "n", Expression: Map{Entries: map[string]Expr{"b": Literal{Value: int32(3)}}, Keys: []string{"b"}}}, {Kind: SetLabels, Variable: "n", Labels: []string{"A", "B"}}}})
	assertEqual(t, mustParse(t, "REMOVE n.x,n:A:B").Branches[0].Clauses[0], RemoveClause{Items: []RemoveItem{{Kind: RemoveProperty, Variable: "n", Property: "x"}, {Kind: RemoveLabels, Variable: "n", Labels: []string{"A", "B"}}}})
}
func TestStatementASTMatchesSourceOnlyJVMOracle(t *testing.T) {
	data, err := os.ReadFile("testdata/statement-jvm-oracle.json")
	if err != nil {
		t.Fatal(err)
	}
	var corpus struct {
		Cases []struct {
			Query, ParseError string
			ClauseTypes       []string
		} `json:"cases"`
	}
	if err = json.Unmarshal(data, &corpus); err != nil {
		t.Fatal(err)
	}
	for i, c := range corpus.Cases {
		q, err := Parse(c.Query)
		if c.ParseError != "" {
			if err == nil {
				t.Fatalf("case%d expected parse rejection", i)
			}
			continue
		}
		if err != nil {
			t.Fatalf("case%d: %v", i, err)
		}
		types := []string{}
		for b, branch := range q.Branches {
			if b > 0 {
				types = append(types, "Union")
			}
			for _, clause := range branch.Clauses {
				switch x := clause.(type) {
				case ProjectionClause:
					if x.With {
						types = append(types, "With")
					} else {
						types = append(types, "Return")
					}
				case MatchClause:
					types = append(types, "Match")
				case UnwindClause:
					types = append(types, "Unwind")
				case CreateClause:
					types = append(types, "Create")
				case DeleteClause:
					types = append(types, "Delete")
				case SetClause:
					types = append(types, "Set")
				case RemoveClause:
					types = append(types, "Remove")
				}
			}
		}
		if !reflect.DeepEqual(types, c.ClauseTypes) {
			t.Fatalf("case%d got%v want%v", i, types, c.ClauseTypes)
		}
	}
}

type cancelAfterChecks struct {
	context.Context
	cancel context.CancelFunc
	checks atomic.Int64
	after  int64
}

func (c *cancelAfterChecks) Err() error {
	if c.checks.Add(1) == c.after {
		c.cancel()
	}
	return c.Context.Err()
}
func TestParseContextInterruptsLongTokens(t *testing.T) {
	for _, source := range []string{"RETURN '" + strings.Repeat("x", 200000) + "' AS value", "/*" + strings.Repeat("x", 200000) + "*/ RETURN 1", "RETURN " + strings.Repeat("identifier", 30000)} {
		base, cancel := context.WithCancel(context.Background())
		ctx := &cancelAfterChecks{Context: base, cancel: cancel, after: 2000}
		q, err := ParseContext(ctx, source)
		cancel()
		if !errors.Is(err, context.Canceled) || q != nil {
			t.Fatalf("long token cancellation got query%#v error%v", q, err)
		}
	}
}
func TestParseContextCancellationDuringTokenPrediction(t *testing.T) {
	base, cancel := context.WithCancel(context.Background())
	defer cancel()
	ctx := &cancelAfterChecks{Context: base, cancel: cancel}
	source := "RETURN [" + strings.Repeat("1,", 10000) + "1] AS values"
	parser, tokens, _ := antlrParser(ctx, source)
	tokens.Fill() // Finish lexing before activating cancellation.
	ctx.after = ctx.checks.Load() + 100
	err := func() (err error) { defer recovered(&err); parser.Script(); return nil }()
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("prediction cancellation changed: %v", err)
	}
	expired, cancelDeadline := context.WithDeadline(context.Background(), time.Unix(0, 0))
	defer cancelDeadline()
	if _, err := ParseContext(expired, "RETURN 1"); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("deadline error changed: %v", err)
	}
}
