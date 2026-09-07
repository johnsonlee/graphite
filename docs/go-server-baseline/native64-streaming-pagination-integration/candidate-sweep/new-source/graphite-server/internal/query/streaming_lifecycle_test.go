package query

import (
	"context"
	"errors"
	"fmt"
	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"math"
	"reflect"
	"sync/atomic"
	"testing"
	"time"
)

func streamingTestPlan(t *testing.T, e evaluator, q string) *streamingPaginationPlan {
	t.Helper()
	ast, err := cypher.Parse(q)
	if err != nil {
		t.Fatal(err)
	}
	p := e.compileStreamingPagination(ast.Branches[0])
	if p == nil {
		t.Fatal("not admitted", q)
	}
	p.atoms = e.lazyNecessaryCandidates(p.match.Where, p.node.Variable)
	return p
}
func TestStreamingPaginationAdmissionAndCounts(t *testing.T) {
	e := evaluator{ctx: context.Background(), parameters: map[string]any{"s": int32(2), "l": int64(1)}}
	for _, test := range []struct {
		suffix      string
		admitted    bool
		skip, limit int
	}{
		{"SKIP 2 LIMIT 3", true, 2, 3}, {"SKIP $s LIMIT 3", true, 0, 3}, {"SKIP 1+1 LIMIT 3", true, 0, 3}, {"SKIP -1 LIMIT 3", true, 0, 3}, {"SKIP 'bad' LIMIT 3", true, 0, 3}, {"SKIP substring(1,0) LIMIT 3", true, 0, 3}, {"SKIP 1.9 LIMIT 2.8", true, 1, 2}, {"SKIP 1 LIMIT 2147483648", false, 0, 0}, {"SKIP 0 LIMIT 2147483648", true, 0, math.MaxInt32}, {"SKIP 2147483647 LIMIT 0", true, math.MaxInt32, 0}, {"SKIP 0 LIMIT $l", false, 0, 0}, {"SKIP 0 LIMIT -1", false, 0, 0},
	} {
		t.Run(test.suffix, func(t *testing.T) {
			ast, err := cypher.Parse("MATCH (n:CallSiteNode) WHERE n.id > 0 RETURN n.id AS x " + test.suffix)
			if err != nil {
				t.Fatal(err)
			}
			p := e.compileStreamingPagination(ast.Branches[0])
			if (p != nil) != test.admitted {
				t.Fatal("admission", p != nil, test.admitted)
			}
			if p != nil {
				where, ok := p.match.Where.(cypher.Binary)
				if !ok || where.Op != ">" {
					t.Fatal("WHERE AST", p.match.Where)
				}
				property, ok := where.Left.(cypher.Property)
				if !ok || property.Key != "id" || property.Object != (cypher.Variable{Name: "n"}) {
					t.Fatal("WHERE property", where.Left)
				}
				if p.skip != test.skip || p.limit != test.limit || p.node.Variable != "n" || !reflect.DeepEqual(p.columns, []string{"x"}) {
					t.Fatalf("plan: %+v", p)
				}
			}
		})
	}
	for _, q := range []string{
		"MATCH (n:Method) WHERE true RETURN n.id ORDER BY n.id LIMIT 1", "MATCH p=(n) WHERE true RETURN n.id ORDER BY n.id LIMIT 1", "MATCH (n)-[]->(m) WHERE true RETURN n.id ORDER BY n.id LIMIT 1", "MATCH (n),(m) WHERE true RETURN n.id ORDER BY n.id LIMIT 1", "OPTIONAL MATCH (n) WHERE true RETURN n.id ORDER BY n.id LIMIT 1", "MATCH (n) WHERE true RETURN count(n) ORDER BY count(n) LIMIT 1",
	} {
		ast, err := cypher.Parse(q)
		if err != nil {
			t.Fatal(err)
		}
		if e.compileStreamingPagination(ast.Branches[0]) != nil {
			t.Fatal("captured separate dispatcher", q)
		}
	}
}
func TestStreamingPaginationJavaHardCapacityBeforeSource(t *testing.T) {
	g := candidateGraph(t, "clean")
	sources := []Graph{{ID: "a", Store: g}, {ID: "b", Store: g}}
	e := evaluator{ctx: context.Background(), cross: true, graphs: sources}
	for _, limit := range []int{math.MaxInt32 - 1, math.MaxInt32} {
		p := streamingTestPlan(t, e, fmt.Sprintf("MATCH (n) WHERE n.caller_name CONTAINS 'other' RETURN n.id AS x ORDER BY x SKIP 0 LIMIT %d", limit))
		called := false
		failure := findIDCaught(func() {
			e.streamingPaginationSources(p, sources, func(evaluator, Graph, cypher.NodePattern, []distinctStringAtom, int) mainNodeNext {
				called = true
				panic("unexpected construction")
			})
		})
		got := streamingTestResult(e, Result{}, failure)
		want := map[string]any{"error": "OutOfMemoryError", "message": "Requested array size exceeds VM limit"}
		if !reflect.DeepEqual(got, want) || called {
			t.Fatal(got, called)
		}
	}
	// Serial main uses a small default initial capacity, not retainedCount.
	e.cross = false
	p := streamingTestPlan(t, e, "MATCH (n) WHERE n.caller_name CONTAINS 'other' RETURN n.id AS x ORDER BY x SKIP 0 LIMIT 2147483647")
	got := e.streamingPaginationSources(p, sources[:1], realStreamingNodes)
	if len(got.Rows) != 2 || got.Rows[0]["x"] != int32(2) || got.Rows[1]["x"] != int32(41) {
		t.Fatal(got)
	}
}
func TestStreamingPaginationWaveFailureCancellationJoin(t *testing.T) {
	for _, kind := range []string{"source", "request", "projection"} {
		t.Run(kind, func(t *testing.T) {
			g := candidateGraph(t, "clean")
			sources := []Graph{{ID: "a", Store: g}, {ID: "b", Store: g}, {ID: "never", Store: g}}
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			e := evaluator{ctx: ctx, cross: true, graphs: sources}
			projection := "n.id"
			if kind == "projection" {
				projection = "substring('x','bad')"
			}
			p := streamingTestPlan(t, e, "MATCH (n) WHERE n.caller_name CONTAINS 'other' RETURN "+projection+" AS x ORDER BY x LIMIT 1")
			ready := make(chan struct{}, 2)
			release := make(chan struct{})
			done := make(chan any, 1)
			var exited, unexpected atomic.Int32
			marker := errors.New("required source failure")
			factory := func(local evaluator, source Graph, _ cypher.NodePattern, _ []distinctStringAtom, _ int) mainNodeNext {
				if source.ID == "never" {
					unexpected.Add(1)
					panic("future wave started")
				}
				return func(worker context.Context) (store.Node, bool) {
					defer exited.Add(1)
					ready <- struct{}{}
					<-release
					if kind == "source" && source.ID == "b" {
						panic(marker)
					}
					if kind == "projection" && source.ID == "a" {
						n, err := source.Store.CandidateNode(worker, 2)
						if err != nil {
							panic(err)
						}
						return n, true
					}
					<-worker.Done()
					// The owner must join this cancelled Store access before returning.
					_, err := source.Store.CandidateNode(worker, 2)
					if !errors.Is(err, context.Canceled) {
						panic(fmt.Errorf("post-cancel read: %v", err))
					}
					panic(err)
				}
			}
			go func() { done <- findIDCaught(func() { e.streamingParallelOrder(p, sources, factory, 2) }) }()
			for i := 0; i < 2; i++ {
				select {
				case <-ready:
				case <-time.After(5 * time.Second):
					t.Fatal("workers did not start")
				}
			}
			close(release)
			if kind == "request" {
				cancel()
			}
			var failure any
			select {
			case failure = <-done:
			case <-time.After(5 * time.Second):
				t.Fatal("owner failed to join")
			}
			if exited.Load() != 2 || unexpected.Load() != 0 {
				t.Fatal("unjoined or future task", exited.Load(), unexpected.Load())
			}
			switch kind {
			case "source":
				if failure != marker {
					t.Fatal("source failure replaced", failure)
				}
			case "request":
				if failure != context.Canceled {
					t.Fatal("cancellation lost", failure)
				}
			case "projection":
				if got := streamingTestResult(e, Result{}, failure); got["error"] != "ClassCastException" {
					t.Fatal("consumer error replaced", got)
				}
			}
			if err := g.Close(); err != nil {
				t.Fatal(err)
			}
			if exited.Load() != 2 {
				t.Fatal("close resumed worker")
			}
		})
	}
}
func TestStreamingPaginationOwnedRowsAfterClose(t *testing.T) {
	g := candidateGraph(t, "clean")
	e := evaluator{ctx: context.Background()}
	p := streamingTestPlan(t, e, "MATCH (n:CallSiteNode) WHERE n.caller_name CONTAINS 'other' RETURN [n,{owned:n}] AS x ORDER BY n.id SKIP 1 LIMIT 1")
	result := e.streamingPaginationSources(p, []Graph{{Store: g}}, realStreamingNodes)
	if err := g.Close(); err != nil {
		t.Fatal(err)
	}
	got := streamingTestResult(e, result, nil)
	rows := got["rows"].([]any)
	x := rows[0].(map[string]any)["x"].([]any)
	node := x[0].(map[string]any)
	owned := x[1].(map[string]any)["owned"]
	if node["id"] != float64(41) || !reflect.DeepEqual(node, owned) {
		t.Fatal(got)
	}
}

func TestStreamingPaginationRetainedCacheRequiresExhaustion(t *testing.T) {
	for _, kind := range []string{"ordinary-stop", "distinct-stop", "cancel"} {
		t.Run(kind, func(t *testing.T) {
			g := candidateGraph(t, "clean")
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			e := evaluator{ctx: ctx}
			projection := "n.id AS x"
			suffix := "SKIP 0 LIMIT 1"
			if kind == "distinct-stop" {
				projection = "DISTINCT n.id AS x"
				suffix = "SKIP 1 LIMIT 1"
			}
			p := streamingTestPlan(t, e, "MATCH (n) WHERE n.caller_name CONTAINS 'other' RETURN "+projection+" "+suffix)
			factory := realStreamingNodes
			if kind == "cancel" {
				factory = func(local evaluator, source Graph, node cypher.NodePattern, atoms []distinctStringAtom, count int) mainNodeNext {
					next := realStreamingNodes(local, source, node, atoms, count)
					return func(request context.Context) (store.Node, bool) {
						n, ok := next(request)
						if ok {
							cancel()
						}
						return n, ok
					}
				}
			}
			failure := findIDCaught(func() { e.streamingPaginationSources(p, []Graph{{Store: g}}, factory) })
			if kind == "cancel" {
				if failure != context.Canceled {
					t.Fatal(failure)
				}
			} else if failure != nil {
				t.Fatal(failure)
			}
			live := context.Background()
			index, ok, err := g.RetainedProjectionIndex(live)
			if err != nil || !ok {
				t.Fatal("retained source not prepared", ok, err)
			}
			key := mainNodeKey(&mainStringSourceSpec{atoms: p.atoms}, 4)
			if ids, cached, err := index.ProjectionCachedIDs(live, store.ProjectionNodeMatches, key); err != nil || cached {
				t.Fatal("partial source published", ids, cached, err)
			}
			// Fresh request after partial/cancelled consumption owns a new iterator.
			e.ctx = live
			ordered := streamingTestPlan(t, e, "MATCH (n) WHERE n.caller_name CONTAINS 'other' RETURN n.id AS x ORDER BY x LIMIT 1")
			result := e.streamingPaginationSources(ordered, []Graph{{Store: g}}, realStreamingNodes)
			if len(result.Rows) != 1 || result.Rows[0]["x"] != int32(2) {
				t.Fatal(result)
			}
			if ids, cached, err := index.ProjectionCachedIDs(live, store.ProjectionNodeMatches, key); err != nil || !cached || !reflect.DeepEqual(ids, []int32{2, 41}) {
				t.Fatal("full source not published", ids, cached, err)
			}
			again := e.streamingPaginationSources(ordered, []Graph{{Store: g}}, realStreamingNodes)
			if !reflect.DeepEqual(result, again) {
				t.Fatal("fresh cached query changed", result, again)
			}
		})
	}
}

// Main's budgeted comparator polls every 1024 comparisons. The Go API has no
// unbudgeted comparator mode; use a real context and keep each counter/closure
// local to its owning heap (worker or joining request).
func TestStreamingPaginationHeapAndFinalSortCancellation(t *testing.T) {
	for _, phase := range []string{"heap", "sort"} {
		t.Run(phase, func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			e := evaluator{ctx: ctx}
			h := &streamingRowHeap{items: []cypher.SortItem{{Expression: cypher.Variable{Name: "x"}}}, check: e.check}
			for i := 0; i < 8; i++ {
				h.add(&streamingRankedRow{row: map[string]any{"x": int64(i)}, values: []any{int64(7 - i)}, encounter: int64(i)}, 8)
			}
			h.comparisons = 1023 // The next comparison reaches a real cadence boundary.
			h.compare(h.rows[0], h.rows[1])
			if h.comparisons != 1024 {
				t.Fatal(h.comparisons)
			}
			cancel()
			failure := findIDCaught(func() {
				if phase == "heap" {
					h.add(&streamingRankedRow{values: []any{int64(-1)}}, 8)
				} else {
					h.result([]string{"x"}, 0)
				}
			})
			if failure != context.Canceled {
				t.Fatal("cancellation during "+phase, failure)
			}
		})
	}
}
