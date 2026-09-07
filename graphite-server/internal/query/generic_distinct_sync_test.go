package query

import (
	"context"
	"errors"
	"reflect"
	"runtime"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func syncGenericPlan(t *testing.T, e evaluator, q string) *genericDistinctPlan {
	t.Helper()
	ast, err := cypher.Parse(q)
	if err != nil {
		t.Fatal(err)
	}
	p := e.compileGenericDistinct(ast.Branches[0])
	if p == nil {
		t.Fatal("declined", q)
	}
	return p
}
func syncGenericStore(t *testing.T, fixture string) *store.Store {
	t.Helper()
	g, err := store.OpenMode("testdata/generic-distinct/"+fixture, "MAPPED")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { g.Close() })
	return g
}

func TestGenericSyncPreparedPositionsAndLegacyReplay(t *testing.T) {
	e := evaluator{ctx: context.Background(), indexFirst: true}
	g := candidateGraph(t, "clean")
	clause := candidateClause(t, "MATCH (n) WHERE n.caller_name='other' OR n.callee_name='invoke' RETURN n")
	slot := &candidateSlot{}
	walk := e.indexedNodeWalker(g, clause, slot)
	if walk == nil {
		t.Fatal("unavailable")
	}
	for repeat := 0; repeat < 2; repeat++ {
		var ids []int32
		walk(func(v any) { ids = append(ids, v.(*candidateSlot).node.ID) })
		if !reflect.DeepEqual(ids, []int32{17, 2, 41, 90}) {
			t.Fatal("legacy order/replay", ids)
		}
	}
	prepared, ok := e.prepareIndexedNodePositions(g, clause)
	if !ok {
		t.Fatal("unavailable")
	}
	positions := candidateNodePositions{groups: append([]candidateGraphNodes{{}}, prepared...)}
	for _, want := range []int32{17, 2, 41, 90} {
		source, id, ok := positions.next()
		if !ok || id != want || source.Store != g {
			t.Fatal(id, ok)
		}
	}
	if _, _, ok := positions.next(); ok {
		t.Fatal("extra prepared node")
	}
}

func TestGenericSyncBatchesResumeAfterCompletedWave(t *testing.T) {
	g := syncGenericStore(t, "store")
	parent := context.Background()
	e := evaluator{ctx: parent, cross: true}
	p := syncGenericPlan(t, e, "MATCH (n:Annotation) WHERE n.caller_class STARTS WITH 'example' RETURN DISTINCT n.number AS v LIMIT 1")
	s := &genericDistinctScanner{e: e, source: Graph{"g", g}, plan: p, scratch: make([]any, len(p.unique)), local: newGenericDistinctRows(true)}
	defer func() {
		if s.cursor != nil {
			s.cursor.close()
		}
	}()
	var first []map[string]any
	runDistinctTasks(parent, 2, 2, false, func(ctx context.Context, i int) []map[string]any {
		if i == 0 {
			return s.batch(ctx)
		}
		return nil
	}, func(i int, rows []map[string]any) bool {
		if i == 0 {
			first = rows
		}
		return false
	})
	if len(first) != 1 || first[0]["v"] != int32(0) || s.exhausted {
		t.Fatal(first, s.exhausted)
	}
	retained := first[0]
	// The wave's local context is canceled by runDistinctTasks before this resume.
	second := s.batch(parent)
	nextNode, err := g.CandidateNode(parent, g.QueryNodeIDs()[1])
	if err != nil {
		t.Fatal(err)
	}
	if len(second) != 1 || second[0]["v"] != nextNode.Values["number"] || retained["v"] != int32(0) {
		t.Fatal(first, second)
	}
	selected := newGenericDistinctRows(true)
	selected.add([]any{int32(17)}, map[string]any{"v": int32(17)})
	hits := s.remaining(parent, selected)
	if !hits[0] || !s.exhausted || len(hits) != 1 || retained["v"] != int32(0) {
		t.Fatal(hits, s.exhausted, retained)
	}
}

func TestGenericSyncExactBatchBoundaryDoesNotProbeEOF(t *testing.T) {
	ctx := context.Background()
	e := evaluator{ctx: ctx}
	p := syncGenericPlan(t, e, "MATCH (n) WHERE n.caller_class STARTS WITH 'example' RETURN DISTINCT n.number AS v LIMIT 1")
	reads := 0
	slot := &candidateSlot{node: store.Node{Kind: "AnnotationNode", Values: map[string]any{"caller_class": "example.X", "number": int32(7)}}}
	c := newGenericValueCursor(ctx, func(context.Context) (any, bool) {
		reads++
		if reads == 1 {
			return slot, true
		}
		return nil, false
	})
	defer c.close()
	s := &genericDistinctScanner{e: e, plan: p, cursor: c, scratch: make([]any, len(p.unique)), local: newGenericDistinctRows(true)}
	rows := s.batch(ctx)
	if reads != 1 || s.exhausted || len(rows) != 1 || rows[0]["v"] != int32(7) {
		t.Fatal(reads, s.exhausted, rows)
	}
	if rows = s.batch(ctx); reads != 2 || !s.exhausted || len(rows) != 0 {
		t.Fatal(reads, s.exhausted, rows)
	}
}

// Observe projection checks only; the underlying standard context is never
// canceled here. This is access-order instrumentation, not cancellation state.
type syncProjectionObserver struct {
	context.Context
	gets int
}

func (c *syncProjectionObserver) observe() {
	pcs := make([]uintptr, 20)
	frames := runtime.CallersFrames(pcs[:runtime.Callers(2, pcs)])
	for {
		f, more := frames.Next()
		if strings.Contains(f.Function, "genericDistinctScanner).next.func1") {
			c.gets++
			break
		}
		if !more {
			break
		}
	}
}
func (c *syncProjectionObserver) Err() error            { c.observe(); return c.Context.Err() }
func (c *syncProjectionObserver) Done() <-chan struct{} { c.observe(); return c.Context.Done() }
func TestGenericSyncProjectionModesAndBorrowedScratch(t *testing.T) {
	for _, selected := range []bool{false, true} {
		ctx := &syncProjectionObserver{Context: context.Background()}
		e := evaluator{ctx: ctx}
		p := syncGenericPlan(t, e, "MATCH (n) WHERE n.caller_class STARTS WITH 'example' RETURN DISTINCT n.number AS x, 99 AS x LIMIT 1")
		slot := &candidateSlot{node: store.Node{Kind: "AnnotationNode", Values: map[string]any{"caller_class": "example.X", "number": int32(7)}}}
		c := newGenericValueCursor(ctx, func(context.Context) (any, bool) { return slot, true })
		defer c.close()
		s := &genericDistinctScanner{e: e, plan: p, cursor: c, scratch: make([]any, len(p.unique)), local: newGenericDistinctRows(true)}
		values, ok := s.next(ctx, selected)
		want := 2
		if selected {
			want = 1
		}
		if !ok || values[0] != int32(99) || ctx.gets != want {
			t.Fatalf("selected%v values%v projections%d want%d", selected, values, ctx.gets, want)
		}
		retained := newGenericDistinctRows(true)
		retained.add(values, s.row(values))
		values[0] = int32(123)
		if retained.values[0][0] != int32(99) || retained.rows[0]["x"] != int32(99) {
			t.Fatal("retained scratch aliased")
		}
	}
}

func TestGenericSyncProducerVersusConsumerFailurePriority(t *testing.T) {
	producer := errors.New("producer failure")
	consumer := errors.New("consumer failure")
	ctx, cancel := context.WithCancel(context.Background())
	c := newGenericValueCursor(ctx, func(context.Context) (any, bool) { cancel(); panic(producer) })
	defer c.close()
	if p := findIDCaught(func() { c.next(ctx) }); p != context.Canceled {
		t.Fatal("producer delivery lost cancellation priority", p)
	}
	live, stop := context.WithCancel(context.Background())
	defer stop()
	d := newGenericValueCursor(live, func(context.Context) (any, bool) { return "value", true })
	defer d.close()
	if p := findIDCaught(func() { d.next(live); stop(); panic(consumer) }); p != consumer {
		t.Fatal("consumer error gained a cancellation override", p)
	}
}

func TestGenericSyncCurrentRequestCancelsBlockedAdvanceAndJoinsTask(t *testing.T) {
	parent := context.Background()
	entered := make(chan struct{})
	finished := make(chan struct{})
	var touched atomic.Int32
	c := newGenericValueCursor(parent, func(ctx context.Context) (any, bool) {
		defer close(finished)
		close(entered)
		<-ctx.Done()
		touched.Add(1)
		panic(ctx.Err())
	})
	defer c.close()
	bad := errors.New("required source")
	p := findIDCaught(func() {
		runDistinctTasks(parent, 2, 2, false, func(ctx context.Context, i int) int {
			if i == 0 {
				c.next(ctx)
				return 1
			}
			<-entered
			panic(bad)
		}, func(int, int) bool { t.Error("partial merge"); return false })
	})
	if p != bad || touched.Load() != 1 {
		t.Fatal(p, touched.Load())
	}
	select {
	case <-finished:
	default:
		t.Fatal("task returned while advance active")
	}
	c.close()
	if touched.Load() != 1 {
		t.Fatal("close resumed an advance")
	}
}

// This standard cancellation observer drives real index preparation. Both
// methods remain concurrency safe and Done/Err obey the Context contract.
type syncPreparationCancel struct {
	context.Context
	cancel context.CancelFunc
	polls  atomic.Int32
	at     int32
}

func (c *syncPreparationCancel) Err() error {
	if c.polls.Add(1) == c.at {
		c.cancel()
	}
	return c.Context.Err()
}
func TestGenericSyncPreparationCancellationAndPublishedIndex(t *testing.T) {
	for _, warm := range []bool{false, true} {
		g := candidateGraph(t, "clean")
		var previous *store.CallSiteStringIndex
		if warm {
			var ok bool
			var err error
			previous, ok, err = g.TryCallSiteStringIndex(context.Background())
			if err != nil || !ok {
				t.Fatal(err)
			}
			if valid, err := g.CertifyCallSiteCandidates(context.Background(), previous); err != nil || !valid {
				t.Fatal(valid, err)
			}
		}
		base, cancel := context.WithCancel(context.Background())
		defer cancel()
		ctx := &syncPreparationCancel{Context: base, cancel: cancel, at: 6}
		e := evaluator{ctx: context.Background()}
		p := syncGenericPlan(t, e, "MATCH (n) WHERE n.caller_name='other' RETURN DISTINCT n.graph_id LIMIT 1")
		c := newGenericDistinctCursor(e, Graph{Store: g}, p)
		if failure := findIDCaught(func() { c.next(ctx) }); failure != context.Canceled {
			t.Fatal("prepare did not cancel", failure)
		}
		c.close()
		view, available, err := g.TryCallSiteStringIndex(context.Background())
		if err != nil || !available {
			t.Fatal("cancellation poisoned index", available, err)
		}
		if warm && view != previous {
			t.Fatal("successful publication rolled back")
		}
		if valid, err := g.CertifyCallSiteCandidates(context.Background(), view); err != nil || !valid {
			t.Fatal("fresh proof failed", valid, err)
		}
		fresh := newGenericDistinctCursor(e, Graph{Store: g}, p)
		value, ok := fresh.next(context.Background())
		fresh.close()
		if !ok || value.(*candidateSlot).node.ID != 2 {
			t.Fatal("fresh query failed", value, ok)
		}
	}
}

func TestGenericSyncQualifiedSingleSourceConsumesLateError(t *testing.T) {
	g := syncGenericStore(t, "bad-last")
	q := "MATCH (n) WHERE n.caller_class STARTS WITH 'example.' RETURN DISTINCT n.graph_id,n.id AS id LIMIT 1"
	scoped, err := Execute(context.Background(), g, q, nil, -1)
	if err != nil || len(scoped.Rows) != 1 {
		t.Fatal("scoped prefix", scoped, err)
	}
	_, err = ExecuteCross(context.Background(), []Graph{{"only", g}}, q, nil, -1)
	if err == nil {
		t.Fatal("qualified single source skipped corrupt tail")
	}
}

func TestGenericSyncCanceledRequestLeavesPositionForFreshRequest(t *testing.T) {
	parent := context.Background()
	reads := 0
	c := newGenericValueCursor(parent, func(context.Context) (any, bool) { reads++; return reads, true })
	defer c.close()
	request, cancel := context.WithCancel(parent)
	cancel()
	if failure := findIDCaught(func() { c.next(request) }); failure != context.Canceled || reads != 0 {
		t.Fatal(failure, reads)
	}
	value, ok := c.next(parent)
	if !ok || value != 1 {
		t.Fatal(value, ok)
	}
	if failure := findIDCaught(func() { c.next(request) }); failure != context.Canceled || reads != 1 {
		t.Fatal(failure, reads)
	}
	value, ok = c.next(parent)
	if !ok || value != 2 {
		t.Fatal(value, ok)
	}
}
