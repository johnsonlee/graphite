package query

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func TestCandidateSlotMainJVMOracle(t *testing.T) {
	testFunctionsOracle(t, "testdata/candidate-slot-jvm-oracle.json", "../store/testdata/jvm-v3")
}

func candidateExpr(t *testing.T, text string) cypher.Expr {
	t.Helper()
	ast, err := cypher.Parse("RETURN " + text)
	if err != nil {
		t.Fatal(err)
	}
	return ast.Branches[0].Clauses[0].(cypher.ProjectionClause).Items[0].Expression
}

func assertNoCandidate(t *testing.T, value any) {
	t.Helper()
	switch v := value.(type) {
	case *candidateSlot:
		t.Fatal("borrowed candidate escaped its evaluation scope")
	case []any:
		for _, item := range v {
			assertNoCandidate(t, item)
		}
	case map[string]any:
		for _, item := range v {
			assertNoCandidate(t, item)
		}
	case orderedMap:
		assertNoCandidate(t, v.Values)
	case pathValue:
		assertNoCandidate(t, v.Nodes)
		assertNoCandidate(t, v.Edges)
	}
}

func TestCandidateWholeValueOperationsMatchOfficialValues(t *testing.T) {
	graph, err := store.OpenMode("../store/testdata/jvm-v3", "EAGER")
	if err != nil {
		t.Fatal(err)
	}
	defer graph.Close()
	texts := []string{
		"id(n)", "labels(n)", "properties(n)", "keys(n)", "toString(n)", "toString([n,{x:n}])",
		"graphId(n)", "elementId(n)", "qualifiedId(n)", "n=n", "n<coalesce(n)", "n IN [n]",
		"{x:n}={x:n}", "n+[]", "[]+n", "[x IN [1] | n]", "[x IN [n] | {x:x}]",
		"nodes([n])", "relationships([n])", "head([n])", "last([n])", "coalesce(null,n)",
		"CASE WHEN true THEN n ELSE null END", "[n][0]", "[n][0..1]", "sqrt(n)",
		"substring(n,0)", "range(n,1)", "left('x',n)", "reverse(n)", "size(n)", "toInteger(n)",
	}
	expressions := make([]cypher.Expr, len(texts))
	for i, text := range texts {
		expressions[i] = candidateExpr(t, text)
	}
	type observation struct {
		Value   any
		Failure string
	}
	observe := func(e evaluator, expr cypher.Expr, value any) (result observation) {
		defer func() {
			if p := recover(); p != nil {
				if err, ok := p.(*Error); ok {
					result.Failure = err.Class + ":" + err.Message
				} else {
					result.Failure = fmt.Sprintf("%T:%v", p, p)
				}
			}
		}()
		value = freezeCandidate(e.eval(expr, map[string]any{"n": value}))
		assertNoCandidate(t, value)
		result.Value = e.materialize(value)
		return result
	}
	kinds := map[string]bool{}
	slots := []candidateSlot{}
	for _, id := range graph.NodeIDs() {
		node, err := graph.Node(id)
		if err != nil {
			t.Fatal(err)
		}
		kinds[node.Kind] = true
		slots = append(slots, candidateSlot{graph: graph, node: node})
	}
	if len(kinds) != 16 {
		t.Fatalf("fixture covers %d kinds, want 16", len(kinds))
	}
	for _, method := range graph.Metadata.MethodList {
		slots = append(slots, candidateSlot{graph: graph, isMethod: true, method: method})
	}
	if len(slots) == 16 {
		t.Fatal("fixture must exercise Method values")
	}
	for _, qualified := range []bool{false, true} {
		for i := range slots {
			slot := &slots[i]
			slot.qualified = qualified
			if qualified {
				slot.graphID = "orders"
			}
			e := evaluator{ctx: context.Background(), rowOrders: map[string]rowOrder{}}
			for j, expr := range expressions {
				actual, want := observe(e, expr, slot), observe(e, expr, freezeCandidate(slot))
				if !reflect.DeepEqual(actual, want) {
					t.Fatalf("candidate %d qualified=%v expression %s: got %#v want %#v", i, qualified, texts[j], actual, want)
				}
			}
			for _, order := range e.rowOrders {
				assertNoCandidate(t, order.row)
			}
		}
	}
}

func TestCandidateRowsAndOrderBookkeepingNeverRetainBorrowedPointers(t *testing.T) {
	graph, err := store.OpenMode("../store/testdata/jvm-v3", "MAPPED")
	if err != nil {
		t.Fatal(err)
	}
	defer graph.Close()
	ast, err := cypher.Parse("MATCH (n) WHERE head([x IN [1] | CASE WHEN true THEN n ELSE null END])=n AND head([]+n)=n RETURN *")
	if err != nil {
		t.Fatal(err)
	}
	clause := ast.Branches[0].Clauses[0].(cypher.MatchClause)
	e := evaluator{ctx: context.Background(), rowOrders: map[string]rowOrder{}}
	rows := e.matchSingleNode(graph, []map[string]any{{"z": int32(7)}}, clause)
	if len(rows) != 16 {
		t.Fatalf("got %d rows, want 16 distinct nodes", len(rows))
	}
	for i, row := range rows {
		assertNoCandidate(t, row)
		node, ok := row["n"].(store.Node)
		if !ok || node.ID != int32(2*i) || row["z"] != int32(7) {
			t.Fatalf("published row %d changed: %#v", i, row)
		}
	}
	for _, order := range e.rowOrders {
		assertNoCandidate(t, order.row)
	}
}

func TestCandidateReadErrorsPrecedePredicateAndLabelFiltering(t *testing.T) {
	dir := t.TempDir()
	entries, err := os.ReadDir("testdata/traversal")
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range entries {
		data, err := os.ReadFile(filepath.Join("testdata/traversal", entry.Name()))
		if err != nil {
			t.Fatal(err)
		}
		if entry.Name() == "graph.nodedata" {
			data[12] = 255
		}
		if err = os.WriteFile(filepath.Join(dir, entry.Name()), data, 0600); err != nil {
			t.Fatal(err)
		}
	}
	index := make([]byte, 8+8*13)
	binary.BigEndian.PutUint32(index, 0x47524903)
	binary.BigEndian.PutUint32(index[4:], 8)
	for i := 0; i < 8; i++ {
		p := 8 + i*13
		binary.BigEndian.PutUint32(index[p:], uint32(i))
		binary.BigEndian.PutUint64(index[p+5:], uint64(8+i*9))
	}
	if err = os.WriteFile(filepath.Join(dir, "graph.nodeindex"), index, 0600); err != nil {
		t.Fatal(err)
	}
	graph, err := store.OpenMode(dir, "MAPPED")
	if err != nil {
		t.Fatal(err)
	}
	defer graph.Close()
	for _, source := range []string{"MATCH (n) WHERE false RETURN n", "MATCH (n:NoSuchLabel) WHERE true RETURN n", "MATCH (n) WHERE 1/0=0 RETURN n"} {
		_, err := Execute(context.Background(), graph, source, nil, -1)
		var qe *Error
		if !errors.As(err, &qe) || qe.Class != "IllegalArgumentException" || qe.Message != "Unknown node tag: -1" {
			t.Errorf("%s: got %v", source, err)
		}
	}
}

func TestCandidateCancellationClearsScratchAndRetainsOrderBehavior(t *testing.T) {
	graph, err := store.OpenMode("../store/testdata/jvm-v3", "MAPPED")
	if err != nil {
		t.Fatal(err)
	}
	defer graph.Close()
	ast, err := cypher.Parse("MATCH (n) WHERE all(x IN [n] WHERE x=n) RETURN *")
	if err != nil {
		t.Fatal(err)
	}
	ctx := &traversalCancelContext{Context: context.Background(), cancelAt: 9}
	e := evaluator{ctx: ctx, rowOrders: map[string]rowOrder{}}
	defer func() {
		if got := recover(); got != context.Canceled {
			t.Errorf("got %v, want cancellation", got)
		}
		for _, order := range e.rowOrders {
			assertNoCandidate(t, order.row)
		}
	}()
	e.matchSingleNode(graph, []map[string]any{{}}, ast.Branches[0].Clauses[0].(cypher.MatchClause))
	t.Fatal("candidate scan returned without cancellation")
}
