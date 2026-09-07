package query

import (
	"context"
	"sync/atomic"
	"testing"
)

func TestGenericDistinctCursorDoesNotReadPastDemand(t *testing.T) {
	var consumed atomic.Int32
	c := newGenericValueCursor(context.Background(), func(ctx context.Context) (any, bool) {
		if consumed.Add(1) == 1 {
			return "first", true
		}
		panic("late decode")
	})
	value, ok := c.next(context.Background())
	if !ok || value != "first" {
		t.Fatalf("first=%v/%v", value, ok)
	}
	c.close()
	if consumed.Load() != 1 {
		t.Fatalf("read beyond LIMIT: %d", consumed.Load())
	}
}
func TestGenericDistinctCursorConsumesRequiredLateFailure(t *testing.T) {
	var consumed atomic.Int32
	c := newGenericValueCursor(context.Background(), func(ctx context.Context) (any, bool) {
		i := consumed.Add(1) - 1
		values := []string{"first", "duplicate", "other"}
		if i < int32(len(values)) {
			return values[i], true
		}
		panic("late decode")
	})
	defer c.close()
	defer func() {
		if got := recover(); got != "late decode" || consumed.Load() != 4 {
			t.Fatalf("failure=%v consumed=%d", got, consumed.Load())
		}
	}()
	for i := 0; i < 4; i++ {
		c.next(context.Background())
	}
	t.Fatal("late failure swallowed")
}

// With no producer goroutine, cancellation returns only after the active
// synchronous advance has unwound. The old producer-join test is retained in
// the review evidence; this asserts its no-work-after-return obligation.
func TestGenericDistinctCursorCancellationUnwindsAdvance(t *testing.T) {
	entered := make(chan struct{})
	finished := make(chan struct{})
	ctx, cancel := context.WithCancel(context.Background())
	c := newGenericValueCursor(ctx, func(ctx context.Context) (any, bool) {
		defer close(finished)
		close(entered)
		<-ctx.Done()
		panic(ctx.Err())
	})
	result := make(chan any, 1)
	go func() { defer func() { result <- recover() }(); c.next(ctx) }()
	<-entered
	cancel()
	if got := <-result; got != context.Canceled {
		t.Fatalf("cancel=%v", got)
	}
	c.close()
	select {
	case <-finished:
	default:
		t.Fatal("advance still active after return and close")
	}
}
