package store

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"testing"
)

func TestDistinctProjectionRetainedLifecycle(t *testing.T) {
	for _, raw := range []bool{false, true} {
		dir := copyIndexFixture(t)
		if raw {
			if err := os.Remove(filepath.Join(dir, "graph.callsite-string-index")); err != nil {
				t.Fatal(err)
			}
		}
		s := openIndexFixture(t, dir, "MAPPED")
		ctx := context.Background()
		if yes, err := s.RetainedDistinctStringIndex(ctx); yes || err != nil {
			t.Fatalf("fresh=%v %v", yes, err)
		}
		index, ok, err := s.PrepareDistinctStringIndex(ctx, DistinctProjectionOptions{SourceCount: map[bool]int{false: 1, true: 2}[raw], Limit: 1})
		if err != nil || !ok || index.Raw != raw {
			t.Fatalf("prepare=%v %v %#v", ok, err, index)
		}
		if yes, err := s.RetainedDistinctStringIndex(ctx); yes == raw || err != nil {
			t.Fatalf("prepared=%v %v", yes, err)
		}
		ids, err := s.ProjectionStringIDs(ctx, 17)
		if err != nil {
			t.Fatal(err)
		}
		value, err := s.ProjectionString(ctx, ids[CallerName])
		if err != nil || value != "caller" {
			t.Fatalf("caller=%q %v", value, err)
		}
		if err = s.ReleaseDistinctStringIndex(ctx); err != nil {
			t.Fatal(err)
		}
		if yes, err := s.RetainedDistinctStringIndex(ctx); yes || err != nil {
			t.Fatalf("released=%v %v", yes, err)
		}
		if !raw {
			if _, err = index.Directory(ctx, CallerName); err != nil {
				t.Fatalf("active handle invalidated: %v", err)
			}
		}
		if err = s.Close(); err != nil {
			t.Fatal(err)
		}
		for _, read := range []func() error{
			func() error { _, err := s.ProjectionStringIDs(ctx, 17); return err },
			func() error { _, err := s.ProjectionStringID(ctx, 17, CallerName); return err },
			func() error { _, err := s.ProjectionString(ctx, 0); return err },
			func() error { _, err := s.ProjectionNodeOrder(ctx, 17); return err },
			func() error {
				_, _, err := s.PrepareDistinctStringIndex(ctx, DistinctProjectionOptions{SourceCount: map[bool]int{false: 1, true: 2}[raw], Limit: 1})
				return err
			},
			func() error { _, err := index.Directory(ctx, CallerName); return err },
		} {
			if err = read(); !errors.Is(err, ErrStoreClosed) {
				t.Fatalf("closed=%v", err)
			}
		}
	}
}
func TestDistinctProjectionCanceledInitializationIsRetryable(t *testing.T) {
	for _, at := range []int32{1, 3, 8, 15, 25} {
		s := openIndexFixture(t, copyIndexFixture(t), "MAPPED")
		base, cancel := context.WithCancel(context.Background())
		ctx := &cancelAtPoll{Context: base, cancel: cancel, at: at}
		if _, ok, err := s.PrepareDistinctStringIndex(ctx, DistinctProjectionOptions{SourceCount: 1, Limit: 1}); ok || !errors.Is(err, context.Canceled) {
			t.Fatalf("at %d: %v %v", at, ok, err)
		}
		if yes, err := s.RetainedDistinctStringIndex(context.Background()); yes || err != nil {
			t.Fatalf("published canceled initialization %v %v", yes, err)
		}
		if _, ok, err := s.PrepareDistinctStringIndex(context.Background(), DistinctProjectionOptions{SourceCount: 1, Limit: 1}); !ok || err != nil {
			t.Fatalf("retry=%v %v", ok, err)
		}
		cancel()
	}
}
func TestDistinctProjectionConcurrentClose(t *testing.T) {
	s := openIndexFixture(t, copyIndexFixture(t), "MAPPED")
	var wg sync.WaitGroup
	start := make(chan struct{})
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			for j := 0; j < 40; j++ {
				_, _, err := s.PrepareDistinctStringIndex(context.Background(), DistinctProjectionOptions{SourceCount: 1, Limit: 1})
				if err != nil && !errors.Is(err, ErrStoreClosed) {
					t.Errorf("prepare=%v", err)
				}
				_, err = s.ProjectionStringIDs(context.Background(), 17)
				if err != nil && !errors.Is(err, ErrStoreClosed) {
					t.Errorf("read=%v", err)
				}
			}
		}()
	}
	close(start)
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	wg.Wait()
}
