package store

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// This wrapper leaves cancellation and Done semantics to a real standard
// context. The gate only permits deterministic scheduling inside a build.
type reviewTupleGate struct {
	context.Context
	checks  atomic.Int32
	at      int32
	entered chan struct{}
	release chan struct{}
}

func (c *reviewTupleGate) Err() error {
	if c.checks.Add(1) == c.at {
		close(c.entered)
		<-c.release
	}
	return c.Context.Err()
}

func TestReviewTupleConcurrentPublicationClearAndProbe(t *testing.T) {
	s, i := openTupleFixture(t, "n4096")
	ctx := context.Background()
	expected := tupleStrings(t, s, 0)
	before, err := i.ProjectionPlannerBytes(ctx)
	if err != nil {
		t.Fatal(err)
	}
	start := make(chan struct{})
	errs := make(chan error, 16)
	var wg sync.WaitGroup
	for worker := 0; worker < 16; worker++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			if ok, err := i.PrepareExactProjectionTuples(ctx, 256); !ok || err != nil {
				errs <- fmt.Errorf("prepare %v %v", ok, err)
				return
			}
			p, err := i.ExactProjectionProbe(ctx, expected)
			if err != nil {
				errs <- err
				return
			}
			if err = i.ClearProjectionQueryCaches(ctx); err != nil {
				errs <- err
				return
			}
			id, ok, err := p.Next(ctx)
			if err != nil || !ok {
				errs <- fmt.Errorf("probe %d %v %v", id, ok, err)
				return
			}
			if _, ok, err = p.Next(ctx); err != nil || ok {
				errs <- fmt.Errorf("duplicate %v %v", ok, err)
			}
		}()
	}
	close(start)
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Error(err)
	}
	after, err := i.ProjectionPlannerBytes(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if after-before != 98464 {
		t.Fatalf("published growth=%d want98464", after-before)
	}
	table := i.ordinary.exactTuple
	if ok, err := i.PrepareExactProjectionTuples(ctx, 0); !ok || err != nil || table != i.ordinary.exactTuple {
		t.Fatalf("warm pointer changed: %v %v", ok, err)
	}
}

func TestReviewTupleCloseWaitsForCanceledBuilder(t *testing.T) {
	s, i := openTupleFixture(t, "n4096")
	parent, cancel := context.WithCancel(context.Background())
	defer cancel()
	ctx := &reviewTupleGate{Context: parent, at: 80, entered: make(chan struct{}), release: make(chan struct{})}
	built := make(chan error, 1)
	go func() {
		ok, err := i.PrepareExactProjectionTuples(ctx, 256)
		if ok {
			err = fmt.Errorf("canceled builder published")
		}
		built <- err
	}()
	select {
	case <-ctx.entered:
	case <-time.After(5 * time.Second):
		t.Fatal("build never reached raw loop")
	}
	closed := make(chan error, 1)
	go func() { closed <- s.Close() }()
	cancel()
	close(ctx.release)
	select {
	case err := <-built:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("build=%v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("build did not cancel")
	}
	select {
	case err := <-closed:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("close did not join lock holder")
	}
	if i.ordinary.exactTuple != nil {
		t.Fatal("canceled or closed table published")
	}
	if _, err := i.ExactProjectionProbe(context.Background(), [4]string{}); !errors.Is(err, ErrStoreClosed) {
		t.Fatalf("post-close probe=%v", err)
	}
}

func TestReviewTupleReloadDoesNotPersistTable(t *testing.T) {
	s, i := openTupleFixture(t, "n4096")
	ctx := context.Background()
	before, err := i.ProjectionPlannerBytes(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if ok, err := i.PrepareExactProjectionTuples(ctx, 256); !ok || err != nil {
		t.Fatal(ok, err)
	}
	dir := s.dir
	if err = s.Close(); err != nil {
		t.Fatal(err)
	}
	reopened, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	next, ok, err := reopened.PrepareDistinctStringIndex(ctx, DistinctProjectionOptions{SourceCount: 1, Limit: 1, SkipPreparedPreference: true})
	if !ok || err != nil {
		t.Fatal(ok, err)
	}
	if next.ordinary.exactTuple != nil {
		t.Fatal("tuple serialized into sidecar")
	}
	after, err := next.ProjectionPlannerBytes(ctx)
	if err != nil || before != after {
		t.Fatalf("fresh bytes=%d want%d err=%v", after, before, err)
	}
	if ok, err = next.PrepareExactProjectionTuples(ctx, 255); ok || err != nil {
		t.Fatalf("reload retains warm threshold %v %v", ok, err)
	}
}
