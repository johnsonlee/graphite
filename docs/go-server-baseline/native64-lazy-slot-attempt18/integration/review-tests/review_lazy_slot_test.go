package query

import (
	"context"
	"reflect"
	"testing"
)

// Two distinct Stores have overlapping IDs. Inline properties and comprehension
// bindings must filter the current slot, and retained whole values must preserve
// their source Store and identity after a later refill and both Stores close.
func TestReviewLazySlotInlineBindingsAcrossDistinctStores(t *testing.T) {
	a, b := candidateGraph(t, "clean"), candidateGraph(t, "clean")
	e := evaluator{ctx: context.Background(), cross: true, graphs: []Graph{{"a", a}, {"b", b}}}
	q := "MATCH (n:CallSite {caller_name:'other'}) WHERE any(x IN [n] WHERE x.id=n.id) RETURN DISTINCT [n,{copy:head([n])}] AS x LIMIT 10"
	actual, failure := lazySlotRun(t, e, a, q)
	if failure != nil {
		t.Fatal(failure)
	}
	control := e
	control.rowOrders = map[string]rowOrder{}
	expected, ef := lazySlotRun(t, control, a, q)
	if ef != nil || !reflect.DeepEqual(actual, expected) {
		t.Fatal(actual, failure, expected, ef)
	}
	if len(actual.Rows) != 4 {
		t.Fatalf("expected two matching IDs in each Store: %#v", actual)
	}
	for i, row := range actual.Rows {
		values, ok := row["x"].([]any)
		if !ok || len(values) != 2 {
			t.Fatal(row)
		}
		n, ok := values[0].(qualifiedNode)
		if !ok {
			t.Fatal(values[0])
		}
		source := e.graphs[i/2]
		id := []int32{2, 41}[i%2]
		if n.GraphID != source.ID || n.Graph != source.Store || n.Node.ID != id {
			t.Fatalf("row %d lost source/node identity: %#v", i, n)
		}
		assertNoCandidate(t, row)
	}
	if err := a.Close(); err != nil {
		t.Fatal(err)
	}
	if err := b.Close(); err != nil {
		t.Fatal(err)
	}
	for i, row := range actual.Rows {
		if !reflect.DeepEqual(e.materialize(row), e.materialize(expected.Rows[i])) {
			t.Fatal("owned output changed after Close", i)
		}
		assertNoCandidate(t, row)
	}
}
