package query

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"
)

// No Err/Done overrides: cancellation is external to the evaluator checkpoint.
func TestIndependentContextConcurrentExternalCancel(t *testing.T) {
	ctx, cancel := context.WithCancelCause(context.Background())
	defer cancel(nil)
	var ready, finished sync.WaitGroup
	ready.Add(16)
	finished.Add(16)
	released := make(chan struct{})
	failures := make(chan any, 16)
	for i := 0; i < 16; i++ {
		go func() {
			defer finished.Done()
			local := evaluator{ctx: context.WithValue(ctx, struct{}{}, true)}
			for j := 0; j < 128; j++ {
				if p := findIDCaught(local.check); p != nil {
					failures <- p
					ready.Done()
					return
				}
			}
			ready.Done()
			<-released
			for j := 0; j < 128; j++ {
				if p := findIDCaught(local.check); p != context.Canceled {
					failures <- p
					return
				}
			}
		}()
	}
	ready.Wait()
	cause := errors.New("review external cause")
	cancel(cause)
	close(released)
	finished.Wait()
	close(failures)
	for p := range failures {
		t.Errorf("checkpoint result %v", p)
	}
	if context.Cause(ctx) != cause {
		t.Fatal("cause changed")
	}
}

func TestIndependentContextRepeatedRebindAndTimer(t *testing.T) {
	parent, cancelParent := context.WithCancel(context.Background())
	defer cancelParent()
	e := evaluator{ctx: parent}
	for i := 0; i < 32; i++ {
		child, cancel := context.WithCancel(parent)
		e.ctx = child
		if p := findIDCaught(e.check); p != nil {
			t.Fatal(p)
		}
		cancel()
		if p := findIDCaught(e.check); p != context.Canceled {
			t.Fatal(p)
		}
		e.ctx = parent
		if p := findIDCaught(e.check); p != nil {
			t.Fatal("stale child", p)
		}
	}
	deadline, stop := context.WithTimeout(parent, time.Millisecond)
	defer stop()
	<-deadline.Done()
	e.ctx = deadline
	if p := findIDCaught(e.check); p != context.DeadlineExceeded {
		t.Fatal(p)
	}
	e.ctx = context.WithoutCancel(deadline)
	if p := findIDCaught(e.check); p != nil {
		t.Fatal("without cancel", p)
	}
	if parent.Err() != nil {
		t.Fatal("parent unexpectedly canceled")
	}
}
