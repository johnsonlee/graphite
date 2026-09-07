package query

import (
	"context"
	"errors"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/johnsonlee/graphite/graphite-server/internal/javaregex"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func TestEvaluatorCheckStandardContexts(t *testing.T) {
	canceled, cancel := context.WithCancel(context.Background())
	cancel()
	deadline, stop := context.WithDeadline(context.Background(), time.Unix(1, 0))
	defer stop()
	causeCtx, causeCancel := context.WithCancelCause(context.Background())
	cause := errors.New("custom cause")
	causeCancel(cause)
	for _, c := range []struct {
		name string
		ctx  context.Context
		want any
	}{
		{"background", context.Background(), nil}, {"todo", context.TODO(), nil},
		{"canceled", canceled, context.Canceled}, {"deadline", deadline, context.DeadlineExceeded},
		{"cause keeps Err", causeCtx, context.Canceled}, {"value", context.WithValue(canceled, struct{}{}, true), context.Canceled},
		{"without cancel", context.WithoutCancel(canceled), nil},
	} {
		t.Run(c.name, func(t *testing.T) {
			if p := findIDCaught(func() { evaluator{ctx: c.ctx}.check() }); p != c.want {
				t.Fatalf("got %v want %v", p, c.want)
			}
		})
	}
	if context.Cause(causeCtx) != cause {
		t.Fatal("check replaced cancellation cause")
	}
}

func TestEvaluatorCheckUsesReboundChildContext(t *testing.T) {
	parent, cancelParent := context.WithCancel(context.Background())
	defer cancelParent()
	e := evaluator{ctx: parent}
	e.check()
	child, cancelChild := context.WithCancel(parent)
	local := e
	local.ctx = child
	cancelChild()
	e.check() // Parent remains live while the worker-local context is canceled.
	if p := findIDCaught(local.check); p != context.Canceled {
		t.Fatal(p)
	}
	if parent.Err() != nil {
		t.Fatal("worker canceled parent")
	}
}

func TestEvaluatorCheckpointObserverMixedComponents(t *testing.T) {
	t.Run("query", func(t *testing.T) {
		ctx := newTraversalCancelContext(t, context.Background(), 3)
		p := findIDCaught(func() { evaluator{ctx: ctx}.call("range", []any{int64(0), int64(100)}) })
		if p != context.Canceled || ctx.doneChecks != 3 || ctx.errChecks != 0 {
			t.Fatalf("failure%v Done%d Err%d", p, ctx.doneChecks, ctx.errChecks)
		}
	})
	t.Run("query then Store", func(t *testing.T) {
		g := candidateGraph(t, "clean")
		ctx := newTraversalCancelContext(t, context.Background(), 3)
		evaluator{ctx: ctx}.check()
		_, err := g.CandidateNode(ctx, 17)
		if err != context.Canceled || ctx.doneChecks != 1 || ctx.errChecks != 2 {
			t.Fatalf("error%v Done%d Err%d", err, ctx.doneChecks, ctx.errChecks)
		}
		// Cancellation must not corrupt the graph or survive in a subsequent request.
		if _, err := g.CandidateNode(context.Background(), 17); err != nil {
			t.Fatal(err)
		}
	})
	t.Run("query then regex backtracking", func(t *testing.T) {
		pattern := "(a+)+$"
		compiled, err := javaregex.Compile(pattern)
		if err != nil {
			t.Fatal(err)
		}
		ctx := newTraversalCancelContext(t, context.Background(), 80)
		e := evaluator{ctx: ctx, regexes: &regexLRU{entries: map[string]compiledRegex{pattern: {pattern: compiled}}, order: []string{pattern}}}
		p := findIDCaught(func() { e.regexMatch(pattern, strings.Repeat("a", 32)+"!") })
		if p != context.Canceled || ctx.doneChecks != 1 || ctx.errChecks != 79 {
			t.Fatalf("failure%v Done%d Err%d", p, ctx.doneChecks, ctx.errChecks)
		}
		e.ctx = context.Background()
		if !e.regexMatch(pattern, "aaa") {
			t.Fatal("canceled regex poisoned reusable expression")
		}
	})
}

func TestEvaluatorCheckCloseAndCancellationOrder(t *testing.T) {
	g := candidateGraph(t, "clean")
	if err := g.Close(); err != nil {
		t.Fatal(err)
	}
	e := evaluator{ctx: context.Background()}
	e.check()
	if _, err := g.CandidateNode(e.ctx, 17); err != store.ErrStoreClosed {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	e.ctx = ctx
	if p := findIDCaught(func() { e.check(); _, err := g.CandidateNode(ctx, 17); panic(err) }); p != context.Canceled {
		t.Fatal("checkpoint moved past closed Store", p)
	}
}

// This is an intentional invalid-context counterexample, not a cancellation
// injector for a successful query: Canceled with inherited nil Done violates
// Context's documented invariant. Preserve the distinction as evidence.
type errOnlyCancellationCounterexample struct{ context.Context }

func (errOnlyCancellationCounterexample) Err() error { return context.Canceled }
func TestEvaluatorCheckRejectsErrOnlyInjectionAsCancellationEvidence(t *testing.T) {
	ctx := errOnlyCancellationCounterexample{context.Background()}
	if ctx.Done() != nil || ctx.Err() != context.Canceled {
		t.Fatal("counterexample changed")
	}
	if p := findIDCaught(func() { evaluator{ctx: ctx}.check() }); p != nil {
		t.Fatalf("unexpected panic %v", p)
	}
}

func TestEvaluatorCheckOrderedTaskCancellationStillJoins(t *testing.T) {
	var completed atomic.Int32
	started := make(chan struct{})
	runDistinctTasks(context.Background(), 3, 2, true, func(ctx context.Context, i int) int {
		defer completed.Add(1)
		if i == 0 {
			<-started
			return 42
		}
		if i != 1 {
			t.Error("replenished after limit")
			return 0
		}
		close(started)
		<-ctx.Done()
		evaluator{ctx: ctx}.check()
		t.Error("canceled worker continued")
		return 0
	}, func(i, value int) bool {
		if i != 0 || value != 42 {
			t.Errorf("consumed %d/%d", i, value)
		}
		return true
	})
	if completed.Load() != 2 {
		t.Fatalf("returned before joining all %d started workers", completed.Load())
	}
}
func TestEvaluatorCheckUnorderedFailureStillJoins(t *testing.T) {
	var completed atomic.Int32
	started := make(chan struct{})
	bad := errors.New("required source failure")
	p := findIDCaught(func() {
		runDistinctTasks(context.Background(), 2, 2, false, func(ctx context.Context, i int) int {
			defer completed.Add(1)
			if i == 0 {
				close(started)
				<-ctx.Done()
				evaluator{ctx: ctx}.check()
				return 0
			}
			<-started
			panic(bad)
		}, func(i, value int) bool { t.Error("partial result consumed"); return false })
	})
	if p != bad || completed.Load() != 2 {
		t.Fatalf("failure%v joined%d", p, completed.Load())
	}
}
