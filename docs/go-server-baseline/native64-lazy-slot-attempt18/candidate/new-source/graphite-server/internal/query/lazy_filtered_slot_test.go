package query

import (
	"context"
	"errors"
	"fmt"
	"reflect"
	"runtime"
	"strings"
	"sync"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// The nonnil-registry execution remains the original official-value control.
// Compare owned pre-materialization rows, including whole Nodes and graph identity.
func lazySlotRun(t *testing.T, e evaluator, graph *store.Store, q string) (result Result, failure any) {
	t.Helper()
	ast, err := cypher.Parse(q)
	if err != nil {
		t.Fatal(err)
	}
	plan := e.compileLazyFiltered(ast.Branches[0])
	if plan == nil || plan.atoms != nil {
		t.Fatalf("test must reach complete generic loop: %s", q)
	}
	failure = findIDCaught(func() {
		var ok bool
		result, ok = e.lazyFiltered(graph, ast.Branches[0])
		if !ok {
			panic("generic admission changed")
		}
	})
	for _, row := range result.Rows {
		assertNoCandidate(t, row)
	}
	for _, order := range e.rowOrders {
		assertNoCandidate(t, order.row)
	}
	return
}

func lazySlotFailure(value any) any {
	if v, ok := value.(*Error); ok {
		return *v
	}
	if v, ok := value.(error); ok {
		return fmt.Sprintf("%T:%s", v, v.Error())
	}
	return value
}

func TestLazySlotWholeNestedProjectionMatchesOwnedControl(t *testing.T) {
	expressions := []string{
		"n", "coalesce(null,n)", "CASE WHEN true THEN n ELSE null END", "+n",
		"[n,{a:[n,{b:coalesce(n)}]}]", "[x IN [1] | {a:coalesce(n)}]", "[x IN [n] | [x,n]]",
		"[n][0]", "[n][0..1]", "[]+n", "n+[]", "[n]+[n]", "head([n])", "last([n])", "tail([0,n])", "reverse([n,0])",
		"properties(n)", "keys(n)", "toString([n,{n:n}])", "nodes([n])", "relationships([n])", "labels(n)",
		"[n.graphId,n.elementId,graphId(n),qualifiedId(n)]", "n=n", "n IN [n]", "{x:n}={x:n}", "any(x IN [n] WHERE x=n)",
		"n.value", "n.caller_signature", "sqrt(n)", "left('x',n)", "coalesce(n,left('x','bad'))", "unknownFunction(left('x','bad'))",
		"reverse(n)", "size(n)", "toInteger(n)", "toFloat(n)", "n<coalesce(n)",
	}
	for _, mode := range []string{"MAPPED", "EAGER"} {
		g, err := store.OpenMode("../store/testdata/jvm-v3", mode)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { g.Close() })
		kinds := map[string]bool{}
		for _, id := range g.NodeIDs() {
			n, err := g.Node(id)
			if err != nil {
				t.Fatal(err)
			}
			kinds[n.Kind] = true
		}
		if len(kinds) != 16 {
			t.Fatal("sixteen kind fixture", len(kinds))
		}
		for _, cross := range []bool{false, true} {
			for _, expr := range expressions {
				t.Run(mode+fmt.Sprint(cross)+"/"+expr, func(t *testing.T) {
					q := "MATCH (n) WHERE n.id>=0 RETURN DISTINCT " + expr + " AS x LIMIT 100"
					e := evaluator{ctx: context.Background(), cross: cross, graphs: []Graph{{"a", g}, {"b", g}}}
					actual, af := lazySlotRun(t, e, g, q)
					control := e
					control.rowOrders = map[string]rowOrder{}
					expected, ef := lazySlotRun(t, control, g, q)
					if !reflect.DeepEqual(lazySlotFailure(af), lazySlotFailure(ef)) || !reflect.DeepEqual(actual, expected) {
						t.Fatalf("actual=%#v/%#v expected=%#v/%#v", actual, af, expected, ef)
					}
				})
			}
		}
	}
}

func TestLazySlotProjectionDoesNotRetainRefilledValue(t *testing.T) {
	g := candidateGraph(t, "clean")
	first, err := g.Node(17)
	if err != nil {
		t.Fatal(err)
	}
	second, err := g.Node(2)
	if err != nil {
		t.Fatal(err)
	}
	e := evaluator{ctx: context.Background(), cross: true, graphs: []Graph{{"a", g}, {"b", g}}}
	slot := &candidateSlot{graph: g, graphID: "a", qualified: true, node: first}
	texts := []string{"n", "coalesce(n)", "[n,{a:[n]}]", "[x IN [1] | {a:n}]", "reverse([n])", "head([n])", "properties(n)"}
	for _, text := range texts {
		ast, err := cypher.Parse("MATCH (n) WHERE n.id>=0 RETURN " + text + " AS x LIMIT 1")
		if err != nil {
			t.Fatal(err)
		}
		p := e.compileLazyFiltered(ast.Branches[0])
		if p == nil || p.atoms != nil {
			t.Fatal("generic plan", text)
		}
		*slot = candidateSlot{graph: g, graphID: "a", qualified: true, node: first}
		actual := e.lazyProject(p, slot, "a", false)
		want := e.lazyProject(p, freezeCandidate(slot), "a", false)
		assertNoCandidate(t, actual)
		*slot = candidateSlot{graph: g, graphID: "b", qualified: true, node: second}
		if !reflect.DeepEqual(actual, want) {
			t.Fatal("previous projected value changed after refill", text, actual, want)
		}
	}
	rows, failure := lazySlotRun(t, e, g, "MATCH (n) WHERE n.id>=0 RETURN [n,{x:n}] AS x LIMIT 3")
	if failure != nil || len(rows.Rows) != 3 {
		t.Fatal(rows, failure)
	}
	before := fmt.Sprintf("%#v", rows.Rows)
	if err = g.Close(); err != nil {
		t.Fatal(err)
	}
	if fmt.Sprintf("%#v", rows.Rows) != before {
		t.Fatal("owned rows changed on Close")
	}
	for _, row := range rows.Rows {
		assertNoCandidate(t, row)
		_ = e.materialize(row)
	}
}

func TestLazySlotLaterDiscardedRowsAndReadErrors(t *testing.T) {
	for _, cross := range []bool{false, true} {
		g := candidateGraph(t, "clean")
		e := evaluator{ctx: context.Background(), cross: cross, graphs: []Graph{{"a", g}, {"b", g}}}
		// The duplicate alias makes every final row x=1, but its overwritten
		// earlier expression must still execute and throw before deduplication.
		for _, q := range []string{"MATCH (n) WHERE n.id>=0 RETURN DISTINCT CASE WHEN n.id=17 THEN 1 ELSE left('x','late') END AS x LIMIT 1", "MATCH (n) WHERE n.id>=0 RETURN DISTINCT CASE WHEN n.id=17 THEN 1 ELSE left('x','late') END AS x, 1 AS x LIMIT 1"} {
			result, failure := lazySlotRun(t, e, g, q)
			if cross {
				if failure == nil {
					t.Fatal("qualified DISTINCT must consume late projection after full LIMIT", result)
				}
			} else if failure != nil || len(result.Rows) != 1 {
				t.Fatal("scoped early stop changed", result, failure)
			}
			control := e
			control.rowOrders = map[string]rowOrder{}
			want, wf := lazySlotRun(t, control, g, q)
			if !reflect.DeepEqual(result, want) || !reflect.DeepEqual(lazySlotFailure(failure), lazySlotFailure(wf)) {
				t.Fatal(result, failure, want, wf)
			}
		}
	}
	for _, fixture := range []string{"bad-first-unmatched", "bad-last-unmatched", "bad-matched"} {
		g, err := store.OpenMode(ordinaryCopyFixture(t, "testdata/lazy-filtered/fixtures/"+fixture), "MAPPED")
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { g.Close() })
		e := evaluator{ctx: context.Background()}
		result, failure := lazySlotRun(t, e, g, "MATCH (n) WHERE n.id<0 RETURN n.id AS x LIMIT 1")
		if failure == nil {
			t.Fatal("full read cannot be hidden by rejected WHERE", fixture, result)
		}
		e.rowOrders = map[string]rowOrder{}
		want, wf := lazySlotRun(t, e, g, "MATCH (n) WHERE n.id<0 RETURN n.id AS x LIMIT 1")
		if !reflect.DeepEqual(result, want) || !reflect.DeepEqual(lazySlotFailure(failure), lazySlotFailure(wf)) {
			t.Fatal(fixture, result, failure, want, wf)
		}
	}
}

// Observe a real standard cancellation at an actual expression/projection stack.
// Done and Err have their standard state; this never fabricates cancellation.
type lazySlotCancelContext struct {
	context.Context
	phase  string
	cancel context.CancelFunc
	hit    bool
}

func (c *lazySlotCancelContext) observe() {
	if c.hit || c.Context.Err() != nil {
		return
	}
	pcs := make([]uintptr, 24)
	frames := runtime.CallersFrames(pcs[:runtime.Callers(2, pcs)])
	for {
		frame, more := frames.Next()
		if strings.HasSuffix(frame.Function, c.phase) {
			c.hit = true
			c.cancel()
			return
		}
		if !more {
			return
		}
	}
}
func (c *lazySlotCancelContext) Done() <-chan struct{} { c.observe(); return c.Context.Done() }
func (c *lazySlotCancelContext) Err() error            { c.observe(); return c.Context.Err() }
func TestLazySlotRealCancellationAndIndependentRequests(t *testing.T) {
	g := candidateGraph(t, "clean")
	for _, registry := range []bool{false, true} {
		for _, phase := range []string{".lazyProject", ".ProjectionCandidateNode.func1"} {
			base, cancel := context.WithCancel(context.Background())
			ctx := &lazySlotCancelContext{Context: base, phase: phase, cancel: cancel}
			e := evaluator{ctx: ctx, cross: true, graphs: []Graph{{"a", g}, {"b", g}}}
			if registry {
				e.rowOrders = map[string]rowOrder{}
			}
			_, failure := lazySlotRun(t, e, g, "MATCH (n) WHERE n.id>=0 RETURN DISTINCT [n,{a:n}] AS x LIMIT 2")
			err, _ := failure.(error)
			if !ctx.hit || base.Err() != context.Canceled || !errors.Is(err, context.Canceled) {
				t.Fatal("actual cancellation boundary", phase, registry, ctx.hit, failure)
			}
			cancel()
		}
	}
	q := "MATCH (n) WHERE n.id>=0 RETURN [n.id,n.caller_name] AS x LIMIT 3"
	expected, err := Execute(context.Background(), g, q, nil, -1)
	if err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	failures := make(chan error, 8)
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			actual, err := Execute(context.Background(), g, q, nil, -1)
			if err != nil {
				failures <- err
			} else if !reflect.DeepEqual(actual, expected) {
				failures <- fmt.Errorf("independent result changed: %#v", actual)
			}
		}()
	}
	wg.Wait()
	close(failures)
	for err := range failures {
		t.Error(err)
	}
}

func TestLazySlotRegistryUnionAndSurrogateOutputFallback(t *testing.T) {
	g := candidateGraph(t, "clean")
	prefix := "MATCH (n) WHERE n.id>=0 RETURN [n,{a:n}] AS x LIMIT 3"
	union := prefix + " UNION ALL RETURN collect() AS x"
	ast, err := cypher.Parse(union)
	if err != nil {
		t.Fatal(err)
	}
	if !needsRowOrder(ast) {
		t.Fatal("UNION control must enable registry globally")
	}
	e := evaluator{ctx: context.Background(), rowOrders: map[string]rowOrder{}}
	raw, failure := lazySlotRun(t, e, g, prefix)
	if failure != nil {
		t.Fatal(failure)
	}
	if len(raw.Rows) != 3 || len(e.rowOrders) == 0 {
		t.Fatal("registry-enabled generic path not exercised", len(raw.Rows), len(e.rowOrders))
	}
	whole, err := Execute(context.Background(), g, union, nil, -1)
	if err != nil {
		t.Fatal(err)
	}
	if len(whole.Rows) != 4 || len(whole.Rows[3]["x"].([]any)) != 1 {
		t.Fatal("UNION whole binding aggregate changed", whole)
	}
	for i, row := range raw.Rows {
		expected := e.materialize(row["x"])
		if !reflect.DeepEqual(whole.Rows[i]["x"], expected) {
			t.Fatal("UNION first branch changed", i, whole.Rows[i], expected)
		}
	}
	// Actual WTF-8 aliases are distinct Java UTF16 keys which collide on wire.
	a, b := javaFromUTF16([]uint16{0xd800}), javaFromUTF16([]uint16{0xd801})
	q := "MATCH (n) WHERE n.id>=0 RETURN n.id AS `" + a + "`, n.caller_name AS `" + b + "`, n AS `?` LIMIT 3"
	ast, err = cypher.Parse(q)
	if err != nil {
		t.Fatal(err)
	}
	if !needsJavaOutputOrder(ast) {
		t.Fatal("Java alias keys must enable output registry")
	}
	observed, err := Execute(context.Background(), g, q, nil, -1)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(observed.Columns, []string{"?", "?", "?"}) || !reflect.DeepEqual(observed.ColumnKeys(), []string{a, b, "?"}) {
		t.Fatal("column identities collapsed", observed.Columns, observed.ColumnKeys())
	}
	ids := []int32{17, 2, 41}
	names := []string{"caller", "other", "other"}
	for i, id := range ids {
		object, ok := observed.ResponseRow(i).(OutputObject)
		if !ok || !reflect.DeepEqual(object.Keys, []string{a, b, "?"}) || object.Values[a] != id || object.Values[b] != names[i] {
			t.Fatal("ordered colliding output", i, object)
		}
		node, ok := object.Values["?"].(map[string]any)
		if !ok || node["id"] != id {
			t.Fatal("whole projected node lost ownership", node)
		}
	}
}
