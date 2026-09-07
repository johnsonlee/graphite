package query

import (
	"context"
	"sync/atomic"
	"testing"
)

func TestGenericDistinctCursorDoesNotReadPastDemand(t *testing.T) {
	var consumed atomic.Int32
	c := newGenericValueCursor(context.Background(), func(ctx context.Context, yield func(any)) {
		consumed.Add(1)
		yield("first")
		consumed.Add(1)
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
	c := newGenericValueCursor(context.Background(), func(ctx context.Context, yield func(any)) {
		for _, value := range []string{"first", "duplicate", "other"} {
			consumed.Add(1)
			yield(value)
		}
		consumed.Add(1)
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
func TestGenericDistinctCursorCancellationJoinsProducer(t *testing.T) {
	entered := make(chan struct{})
	finished := make(chan struct{})
	ctx, cancel := context.WithCancel(context.Background())
	c := newGenericValueCursor(ctx, func(ctx context.Context, yield func(any)) {
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
		t.Fatal("producer still active after close")
	}
}
