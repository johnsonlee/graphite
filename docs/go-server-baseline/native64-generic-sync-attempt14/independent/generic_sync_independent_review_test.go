package query

import (
	"context"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"sync/atomic"
	"testing"
)

type independentPrepareObserver struct {
	context.Context
	calls atomic.Int32
}

func (c *independentPrepareObserver) Value(k any) any {
	if _, ok := k.(candidateScanOnlyKey); ok {
		c.calls.Add(1)
	}
	return c.Context.Value(k)
}

func TestIndependentGenericSyncLazyPrepareOnceAcrossRequests(t *testing.T) {
	parent := &independentPrepareObserver{Context: context.Background()}
	g := candidateGraph(t, "clean")
	e := evaluator{ctx: parent}
	p := syncGenericPlan(t, e, "MATCH (n) WHERE n.caller_name='other' RETURN DISTINCT n.graph_id LIMIT 1")
	c := newGenericDistinctCursor(e, Graph{Store: g}, p)
	if parent.calls.Load() != 0 {
		t.Fatal("prepared before demand")
	}
	c.close()
	if parent.calls.Load() != 0 {
		t.Fatal("close initialized an unused source")
	}
	c = newGenericDistinctCursor(e, Graph{Store: g}, p)
	defer c.close()
	first, stop := context.WithCancel(parent)
	v, ok := c.next(first)
	if !ok || v.(*candidateSlot).node.ID != 2 || parent.calls.Load() != 1 {
		t.Fatal(v, ok, parent.calls.Load())
	}
	stop()
	second, stopSecond := context.WithCancel(parent)
	defer stopSecond()
	v, ok = c.next(second)
	if !ok || v.(*candidateSlot).node.ID != 41 || parent.calls.Load() != 1 {
		t.Fatal("reinitialized or stale task context", v, ok, parent.calls.Load())
	}
	if _, ok = c.next(second); ok || parent.calls.Load() != 1 {
		t.Fatal("extra position/preparation", ok, parent.calls.Load())
	}
}

func TestIndependentGenericSyncIndexedStoreCloseBetweenRequests(t *testing.T) {
	ctx := context.Background()
	g := candidateGraph(t, "clean")
	e := evaluator{ctx: ctx}
	p := syncGenericPlan(t, e, "MATCH (n) WHERE n.caller_name='other' RETURN DISTINCT n.graph_id LIMIT 1")
	c := newGenericDistinctCursor(e, Graph{Store: g}, p)
	defer c.close()
	v, ok := c.next(ctx)
	if !ok || v.(*candidateSlot).node.ID != 2 {
		t.Fatal(v, ok)
	}
	if err := g.Close(); err != nil {
		t.Fatal(err)
	}
	if got := findIDCaught(func() { c.next(ctx) }); got != store.ErrStoreClosed {
		t.Fatalf("prepared positions hid Store close: %v", got)
	}
}

// After the owner returns, no advance may still be touching the Store/cursor.
func TestIndependentGenericSyncQueryParentCancellationJoinsAllSources(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	entered := make(chan struct{}, 3)
	var finished atomic.Int32
	cursors := make([]*genericDistinctCursor, 3)
	for i := range cursors {
		cursors[i] = newGenericValueCursor(ctx, func(request context.Context) (any, bool) {
			defer finished.Add(1)
			entered <- struct{}{}
			<-request.Done()
			panic(request.Err())
		})
	}
	done := make(chan any, 1)
	go func() {
		done <- findIDCaught(func() {
			runDistinctTasks(ctx, 3, 3, false, func(request context.Context, i int) int { cursors[i].next(request); return 0 }, func(int, int) bool { t.Error("partial result after cancellation"); return false })
		})
	}()
	for i := 0; i < 3; i++ {
		<-entered
	}
	cancel()
	if got := <-done; got != context.Canceled {
		t.Fatal(got)
	}
	if finished.Load() != 3 {
		t.Fatalf("returned with %d/3 sources joined", finished.Load())
	}
	for _, c := range cursors {
		c.close()
	}
	if finished.Load() != 3 {
		t.Fatal("owner close advanced source")
	}
}

func TestIndependentGenericSyncLegacyWalkerRestartsAfterConsumerPanic(t *testing.T) {
	e := evaluator{ctx: context.Background(), indexFirst: true}
	g := candidateGraph(t, "clean")
	clause := candidateClause(t, "MATCH (n) WHERE n.caller_name='other' RETURN n")
	walk := e.indexedNodeWalker(g, clause, &candidateSlot{})
	if walk == nil {
		t.Fatal("unavailable")
	}
	marker := &struct{}{}
	if got := findIDCaught(func() { walk(func(any) { panic(marker) }) }); got != marker {
		t.Fatal(got)
	}
	ids := []int32{}
	walk(func(v any) { ids = append(ids, v.(*candidateSlot).node.ID) })
	if len(ids) != 2 || ids[0] != 2 || ids[1] != 41 {
		t.Fatal("consumer panic retained legacy position", ids)
	}
}
