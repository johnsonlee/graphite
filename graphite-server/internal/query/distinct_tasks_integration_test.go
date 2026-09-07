package query

import (
	"context"
	"reflect"
	"sync/atomic"
	"testing"
)

func TestDistinctIntegrationPrefixCancelsAndJoinsSuffix(t *testing.T) {
	var completed atomic.Int32
	started := make(chan struct{}, 2)
	consumed := []int{}
	runDistinctTasks(context.Background(), 40, 3, true, func(ctx context.Context, i int) int {
		defer completed.Add(1)
		if i == 0 {
			<-started
			<-started
			return 42
		}
		started <- struct{}{}
		if i == 2 {
			panic("speculative suffix failure")
		}
		<-ctx.Done()
		panic(ctx.Err())
	}, func(i, value int) bool {
		if value != 42 {
			t.Fatalf("prefix value %d", value)
		}
		consumed = append(consumed, i)
		return true
	})
	if !reflect.DeepEqual(consumed, []int{0}) || completed.Load() != 3 {
		t.Fatalf("consumed %v, joined %d tasks", consumed, completed.Load())
	}
}

func TestDistinctIntegrationUnorderedFailureJoinsStartedTasks(t *testing.T) {
	var completed atomic.Int32
	started := make(chan struct{}, 2)
	defer func() {
		if got := recover(); got != "required source failure" || completed.Load() != 3 {
			t.Fatalf("failure %v, joined %d tasks", got, completed.Load())
		}
	}()
	runDistinctTasks(context.Background(), 40, 3, false, func(ctx context.Context, i int) int {
		defer completed.Add(1)
		if i == 0 {
			<-started
			<-started
			panic("required source failure")
		}
		started <- struct{}{}
		<-ctx.Done()
		panic(ctx.Err())
	}, func(i, value int) bool { t.Fatalf("partial result consumed %d/%d", i, value); return false })
	t.Fatal("required failure swallowed")
}
