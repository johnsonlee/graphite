package query

import (
	"context"
	"errors"
	"reflect"
	"sync/atomic"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func TestLazyFilteredAdmissionLeavesPaginationSeparate(t *testing.T) {
	for _, q := range []string{
		"MATCH (n) WHERE true RETURN n.id SKIP 0 LIMIT 1",
		"MATCH (n) WHERE true RETURN n.id ORDER BY n.id LIMIT 1",
		"OPTIONAL MATCH (n) WHERE true RETURN n.id LIMIT 1",
		"MATCH (n) WHERE true RETURN count(n) LIMIT 1",
		"MATCH (n:Method) WHERE true RETURN n.name LIMIT 1",
		"MATCH (n:Unknown) WHERE true RETURN n.id LIMIT 1",
		"MATCH p=(n) WHERE true RETURN n.id LIMIT 1",
		"MATCH (n)-->(m) WHERE true RETURN n.id LIMIT 1",
		"MATCH (n) WHERE true RETURN DISTINCT n.id",
	} {
		ast, err := cypher.Parse(q)
		if err != nil {
			t.Fatal(err)
		}
		e := evaluator{ctx: context.Background()}
		if e.compileLazyFiltered(ast.Branches[0]) != nil {
			t.Errorf("unexpected admission: %s", q)
		}
	}
}

func TestLazyNecessaryCandidateConcretePlan(t *testing.T) {
	cases := []struct{ q, property, op, term string }{
		{"n.caller_name CONTAINS 'other' AND true", "caller_name", "CONTAINS", "other"},
		{"true AND n.caller_name =~ '.*\\\\Qother\\\\E.*'", "caller_name", "CONTAINS", "other"},
		{"(n.caller_name CONTAINS 'a' AND true) AND (n.callee_name='b' AND true)", "callee_name", "=", "b"},
		{"(n.caller_name='a' AND true) AND (n.callee_name='b' AND true)", "caller_name", "=", "a"},
		{"((n.caller_name='other' OR n.caller_name='other') AND true) AND (n.callee_name='invoke' AND true)", "callee_name", "=", "invoke"},
		{"n.absent='x' AND n.caller_name CONTAINS 'other'", "absent", "=", "x"},
	}
	for _, c := range cases {
		ast, err := cypher.Parse("MATCH (n) WHERE " + c.q + " RETURN n.id LIMIT 1")
		if err != nil {
			t.Fatal(err)
		}
		e := evaluator{ctx: context.Background()}
		p := e.compileLazyFiltered(ast.Branches[0])
		if p == nil || len(p.atoms) != 1 || p.atoms[0].property != c.property || p.atoms[0].op != c.op || p.atoms[0].term != c.term {
			t.Errorf("%s: %#v", c.q, p)
		}
	}
}

type lazyCancellationContext struct {
	context.Context
	cancel context.CancelFunc
	at     int
	checks atomic.Int64
}

func (c *lazyCancellationContext) Err() error {
	count := c.checks.Add(1)
	if c.at > 0 && count == int64(c.at) {
		c.cancel()
	}
	return c.Context.Err()
}
func TestLazyFilteredCancellationAndClose(t *testing.T) {
	for _, q := range []string{
		"MATCH (n:CallSiteNode) WHERE n.id>=0 RETURN n.id AS x",
		"MATCH (n) WHERE n.caller_name CONTAINS 'other' AND true RETURN n.id AS x LIMIT 1",
		"MATCH (n) WHERE n.caller_name CONTAINS 'other' AND true RETURN DISTINCT n.id AS x LIMIT 1",
	} {
		for _, at := range []int{1, 10, 25, 60, 120, 250} {
			g, err := store.OpenMode(ordinaryCopyFixture(t, "testdata/candidate-index/clean"), "MAPPED")
			if err != nil {
				t.Fatal(err)
			}
			inner, cancel := context.WithCancel(context.Background())
			ctx := &lazyCancellationContext{Context: inner, cancel: cancel, at: at}
			_, err = Execute(ctx, g, q, nil, -1)
			triggered := inner.Err() != nil
			if triggered && !errors.Is(err, context.Canceled) {
				t.Errorf("at %d / %s: swallowed cancellation: %v", at, q, err)
			}
			cancel()
			g.Close()
		}
	}
	g, err := store.OpenMode(ordinaryCopyFixture(t, "testdata/candidate-index/clean"), "MAPPED")
	if err != nil {
		t.Fatal(err)
	}
	result, err := Execute(context.Background(), g, "MATCH (n) WHERE n.id>=0 RETURN [n.id,n.caller_name] AS x LIMIT 3", nil, -1)
	if err != nil {
		t.Fatal(err)
	}
	expected := []map[string]any{{"x": []any{int32(17), "caller"}}, {"x": []any{int32(2), "other"}}, {"x": []any{int32(41), "other"}}}
	g.Close()
	if !reflect.DeepEqual(result.Rows, expected) {
		t.Fatalf("retained owned values changed: %#v", result.Rows)
	}
	_, err = Execute(context.Background(), g, "MATCH (n) WHERE n.id>=0 RETURN n.id AS x", nil, -1)
	if err == nil {
		t.Fatal("closed mapped source accepted a full-node read")
	}
}
