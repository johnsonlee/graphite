package query

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// The expected rows were captured from unmodified remote main JVM classes.
// TraversalOracle.java recreates the persisted correctness fixture and oracle.
func TestTraversalMainJVMOracle(t *testing.T) {
	graph, err := store.Open("testdata/traversal")
	if err != nil {
		t.Fatal(err)
	}
	defer graph.Close()
	data, err := os.ReadFile("testdata/traversal-jvm-oracle.json")
	if err != nil {
		t.Fatal(err)
	}
	var corpus struct {
		Cases []struct {
			Query   string
			Cross   bool
			Columns []string
			Rows    []map[string]any
		}
	}
	if err = json.Unmarshal(data, &corpus); err != nil {
		t.Fatal(err)
	}
	if len(corpus.Cases) != 62 {
		t.Fatalf("unexpected oracle size %d", len(corpus.Cases))
	}
	for index, c := range corpus.Cases {
		t.Run(fmt.Sprintf("%02d", index), func(t *testing.T) {
			var result Result
			if c.Cross {
				result, err = ExecuteCross(context.Background(), []Graph{{"orders", graph}, {"billing", graph}}, c.Query, nil, -1)
			} else {
				result, err = Execute(context.Background(), graph, c.Query, nil, -1)
			}
			if err != nil {
				t.Fatalf("%s: %v", c.Query, err)
			}
			raw, err := json.Marshal(result.Rows)
			if err != nil {
				t.Fatal(err)
			}
			var rows []map[string]any
			if err = json.Unmarshal(raw, &rows); err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(result.Columns, c.Columns) || !reflect.DeepEqual(rows, c.Rows) {
				t.Fatalf("%s\ncolumns got %#v want %#v\nrows got %s\nwant %s", c.Query, result.Columns, c.Columns, raw, mustJSON(c.Rows))
			}
		})
	}
}
func mustJSON(v any) string { b, _ := json.Marshal(v); return string(b) }

// Cancel deterministically inside traversal, after parsing and start-node
// lookup have finished. A deadline-only test could pass by cancelling parsing.
type traversalCancelContext struct {
	context.Context
	checks   int
	cancelAt int
}

func (c *traversalCancelContext) Err() error {
	c.checks++
	if c.checks >= c.cancelAt {
		return context.Canceled
	}
	return nil
}
func TestVariableTraversalCancellation(t *testing.T) {
	graph, err := store.Open("testdata/traversal")
	if err != nil {
		t.Fatal(err)
	}
	defer graph.Close()
	parsed, err := cypher.Parse("MATCH p=(a {id:0})-[r*]->(b) RETURN p")
	if err != nil {
		t.Fatal(err)
	}
	match := parsed.Branches[0].Clauses[0].(cypher.MatchClause)
	node, err := graph.Node(0)
	if err != nil {
		t.Fatal(err)
	}
	ctx := &traversalCancelContext{Context: context.Background(), cancelAt: 30}
	e := evaluator{ctx: ctx}
	defer func() {
		recovered := recover()
		if recovered != context.Canceled {
			t.Errorf("traversal cancellation got %v, want context.Canceled", recovered)
		}
		if ctx.checks != 30 {
			t.Errorf("cancellation checks got %d want 30", ctx.checks)
		}
	}()
	e.matchRelationship(graph, matchState{row: map[string]any{"a": node}, current: node, nodes: []any{node}}, match.Patterns[0].Relationships[0], match.Patterns[0].Nodes[1], nil)
	t.Fatal("uncancelled traversal returned")
}
