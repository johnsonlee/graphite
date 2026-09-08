package query

import (
	"context"
	"errors"
	"reflect"
	"sync/atomic"
	"testing"
	"time"
)

func fixedRecovered(fn func()) (failure any) {
	defer func() { failure = recover() }()
	fn()
	return
}
func awaitFixed[T any](t *testing.T, ch <-chan T) T {
	t.Helper()
	select {
	case value := <-ch:
		return value
	case <-time.After(10 * time.Second):
		t.Fatal("fixed worker watchdog expired")
		var zero T
		return zero
	}
}
func privateFixedExecutor(t *testing.T, capacity int) *mainFixedExecutor {
	t.Helper()
	executor := newMainFixedExecutor(capacity)
	t.Cleanup(executor.close)
	return executor
}

func TestMainFixedWorkersReuseAndNormalContextLifetime(t *testing.T) {
	executor := privateFixedExecutor(t, 1)
	type valueKey struct{}
	parent, cancel := context.WithDeadline(context.WithValue(context.Background(), valueKey{}, "owned"), time.Now().Add(time.Hour))
	defer cancel()
	var observed []context.Context
	var results []int
	runMainFixedWorkersWithExecutor(parent, 4, 1, func(ctx context.Context, index int) int {
		observed = append(observed, ctx)
		return index * 3
	}, func(index, value int) bool { results = append(results, value); return false }, executor)
	if !reflect.DeepEqual(results, []int{0, 3, 6, 9}) || len(observed) != 4 {
		t.Fatal(results, len(observed))
	}
	deadline, _ := parent.Deadline()
	for _, ctx := range observed {
		actual, ok := ctx.Deadline()
		if ctx != observed[0] || ctx.Err() != nil || ctx.Value(valueKey{}) != "owned" || !ok || actual != deadline {
			t.Fatal("worker context not reused/preserved")
		}
	}
	cancel()
	select {
	case <-observed[0].Done():
		t.Fatal("normal exit retained parent cancellation bridge")
	default:
	}
	// A later worker future uses fresh cancellation state on the same executor.
	runMainFixedWorkersWithExecutor(context.Background(), 1, 1, func(ctx context.Context, index int) int {
		if ctx == observed[0] || ctx.Err() != nil {
			panic("worker instance leaked across calls")
		}
		return 7
	}, func(_ int, value int) bool {
		if value != 7 {
			panic(value)
		}
		return false
	}, executor)
}

func TestMainFixedWorkersReplenishOnlyMergedPrefix(t *testing.T) {
	executor := privateFixedExecutor(t, 2)
	secondFinished := make(chan struct{})
	var merged atomic.Int32
	var premature atomic.Bool
	var values []int
	runMainFixedWorkersWithExecutor(context.Background(), 8, 2, func(_ context.Context, index int) int {
		if index == 0 {
			<-secondFinished
		}
		if index == 1 {
			close(secondFinished)
		}
		if index >= 2 && merged.Load() < int32(index-1) {
			premature.Store(true)
		}
		return index + 10
	}, func(index, value int) bool {
		if index != len(values) {
			panic("merge not source ordered")
		}
		values = append(values, value)
		merged.Add(1)
		return false
	}, executor)
	if premature.Load() || !reflect.DeepEqual(values, []int{10, 11, 12, 13, 14, 15, 16, 17}) {
		t.Fatal(premature.Load(), values)
	}
}

func TestMainFixedWorkersLimitDiscardsSuffixError(t *testing.T) {
	executor := privateFixedExecutor(t, 2)
	suffix := errors.New("unconsumed suffix")
	ready := make(chan struct{})
	var rows []int
	failure := fixedRecovered(func() {
		runMainFixedWorkersWithExecutor(context.Background(), 3, 2, func(_ context.Context, index int) int {
			if index == 1 {
				close(ready)
				panic(suffix)
			}
			if index != 0 {
				panic("unadmitted task executed")
			}
			<-ready
			return 42
		}, func(index, value int) bool { rows = append(rows, value); return true }, executor)
	})
	if failure != nil || !reflect.DeepEqual(rows, []int{42}) {
		t.Fatal(failure, rows)
	}
}

func TestMainFixedWorkersCancelAndJoinOnConsumerOrProducerFailure(t *testing.T) {
	for _, producer := range []bool{false, true} {
		t.Run(map[bool]string{false: "consumer", true: "producer"}[producer], func(t *testing.T) {
			executor := privateFixedExecutor(t, 2)
			cause := errors.New("ordered failure")
			suffixStarted := make(chan struct{})
			suffixExited := make(chan struct{})
			failure := fixedRecovered(func() {
				runMainFixedWorkersWithExecutor(context.Background(), 4, 2, func(ctx context.Context, index int) int {
					if index == 1 {
						defer close(suffixExited)
						close(suffixStarted)
						<-ctx.Done()
						panic(ctx.Err())
					}
					if index != 0 {
						panic("unadmitted source")
					}
					<-suffixStarted
					if producer {
						panic(cause)
					}
					return 1
				}, func(_, _ int) bool { panic(cause) }, executor)
			})
			if failure != cause {
				t.Fatal(failure)
			}
			select {
			case <-suffixExited:
			default:
				t.Fatal("returned before canceled task exited")
			}
		})
	}
}

func TestMainFixedWorkersParentCancellationPublishesAndJoins(t *testing.T) {
	executor := privateFixedExecutor(t, 2)
	parent, cancel := context.WithCancel(context.Background())
	defer cancel()
	started := make(chan struct{}, 2)
	var exited atomic.Int32
	finished := make(chan any, 1)
	go func() {
		finished <- fixedRecovered(func() {
			runMainFixedWorkersWithExecutor(parent, 3, 2, func(ctx context.Context, _ int) int {
				defer exited.Add(1)
				started <- struct{}{}
				<-ctx.Done()
				panic(ctx.Err())
			}, func(_, _ int) bool { return false }, executor)
		})
	}()
	awaitFixed(t, started)
	awaitFixed(t, started)
	cancel()
	if failure := awaitFixed(t, finished); failure != context.Canceled || exited.Load() != 2 {
		t.Fatal(failure, exited.Load())
	}
}

func TestMainFixedExecutorQueuedFutureCancellationDoesNotWaitForCapacity(t *testing.T) {
	executor := privateFixedExecutor(t, 1)
	occupied := make(chan struct{})
	release := make(chan struct{})
	firstFinished := make(chan struct{})
	go func() {
		defer close(firstFinished)
		runMainFixedWorkersWithExecutor(context.Background(), 1, 1, func(context.Context, int) int { close(occupied); <-release; return 1 }, func(int, int) bool { return false }, executor)
	}()
	awaitFixed(t, occupied)
	parent, cancel := context.WithCancel(context.Background())
	cancel()
	var invoked atomic.Bool
	failure := fixedRecovered(func() {
		runMainFixedWorkersWithExecutor(parent, 2, 2, func(context.Context, int) int { invoked.Store(true); return 0 }, func(int, int) bool { return false }, executor)
	})
	if failure != context.Canceled || invoked.Load() {
		t.Fatal(failure, invoked.Load())
	}
	// The capacity-one executor is still held by the first invocation, so
	// both canceled futures remain in its FIFO queue. Inspect actual closures
	// after helper join: no GC or scheduling delay is part of this assertion.
	executor.mu.Lock()
	queued := append([]*mainFixedFuture(nil), executor.queue...)
	executor.mu.Unlock()
	if len(queued) != 2 {
		t.Fatalf("queued futures = %d, want 2", len(queued))
	}
	for _, future := range queued {
		future.mu.Lock()
		retainedRun, retainedWorker, started, canceled, complete := future.run != nil, future.worker != nil, future.started, future.canceled, future.complete
		future.mu.Unlock()
		if retainedRun || retainedWorker || started || !canceled || !complete {
			t.Fatalf("canceled queued future retained task: run=%v worker=%v started=%v canceled=%v complete=%v", retainedRun, retainedWorker, started, canceled, complete)
		}
	}
	// The queued futures have joined while another invocation still owns the
	// only executor worker. Releasing it must skip both canceled future bodies.
	close(release)
	awaitFixed(t, firstFinished)
	runMainFixedWorkersWithExecutor(context.Background(), 1, 1, func(context.Context, int) int { return 9 }, func(_ int, value int) bool {
		if value != 9 {
			panic(value)
		}
		return false
	}, executor)
	if invoked.Load() {
		t.Fatal("queued canceled future subsequently ran")
	}
}

func TestMainFixedWorkersLimitCancelsAndJoinsActiveSuffix(t *testing.T) {
	executor := privateFixedExecutor(t, 2)
	started := make(chan struct{})
	exited := make(chan struct{})
	var retained context.Context
	runMainFixedWorkersWithExecutor(context.Background(), 3, 2, func(ctx context.Context, index int) int {
		if index == 1 {
			retained = ctx
			close(started)
			<-ctx.Done()
			close(exited)
			return 22
		}
		if index != 0 {
			panic("unadmitted task")
		}
		<-started
		return 11
	}, func(index, value int) bool {
		if index != 0 || value != 11 {
			panic("wrong merged value")
		}
		return true
	}, executor)
	select {
	case <-exited:
	default:
		t.Fatal("LIMIT returned without joining active suffix")
	}
	if retained.Err() != context.Canceled {
		t.Fatal(retained.Err())
	}
}

func TestMainFixedExecutorFIFOUsesDistinctFutureContexts(t *testing.T) {
	executor := privateFixedExecutor(t, 1)
	held := make(chan struct{})
	release := make(chan struct{})
	done := make(chan struct{})
	go func() {
		defer close(done)
		runMainFixedWorkersWithExecutor(context.Background(), 1, 1, func(context.Context, int) int { close(held); <-release; return 1 }, func(int, int) bool { return false }, executor)
	}()
	awaitFixed(t, held)
	order := make(chan int, 2)
	futures := make([]*mainFixedFuture, 2)
	for i := range futures {
		worker := &mainFixedWorkerContext{parent: context.Background(), values: context.Background(), done: make(chan struct{})}
		futures[i] = &mainFixedFuture{worker: worker, done: make(chan struct{}), run: func() { order <- i }}
		executor.submit(futures[i])
	}
	if futures[0].worker == futures[1].worker {
		t.Fatal("future contexts shared")
	}
	close(release)
	awaitFixed(t, done)
	for _, future := range futures {
		awaitFixed(t, future.done)
	}
	if first, second := awaitFixed(t, order), awaitFixed(t, order); first != 0 || second != 1 {
		t.Fatal(first, second)
	}
}

func TestMainFixedFutureCannotRestoreCanceledClosure(t *testing.T) {
	worker := &mainFixedWorkerContext{parent: context.Background(), values: context.Background(), done: make(chan struct{})}
	future := &mainFixedFuture{worker: worker, done: make(chan struct{})}
	future.interrupt(context.Canceled)
	called := false
	future.setRun(func() { called = true })
	future.execute()
	if future.run != nil || future.worker != nil || called || !future.complete || future.started {
		t.Fatal("late assignment restored canceled closure")
	}
	select {
	case <-future.done:
	default:
		t.Fatal("never-started cancel did not join")
	}
}

func TestMainFixedFutureCompletionClearsRunningClosure(t *testing.T) {
	worker := &mainFixedWorkerContext{parent: context.Background(), values: context.Background(), done: make(chan struct{})}
	future := &mainFixedFuture{worker: worker, done: make(chan struct{})}
	called := false
	future.setRun(func() { called = true })
	future.execute()
	if !called || future.run != nil || future.worker != nil || !future.complete || !future.started {
		t.Fatal("finished future retained closure")
	}
	if worker.Err() != nil {
		t.Fatal("normal completion canceled context")
	}
}

func mainFixedTestFuture(run func()) *mainFixedFuture {
	worker := &mainFixedWorkerContext{parent: context.Background(), values: context.Background(), done: make(chan struct{})}
	future := &mainFixedFuture{worker: worker, done: make(chan struct{})}
	future.setRun(run)
	return future
}

func TestMainFixedExecutorLazyCoreGrowthPerSubmission(t *testing.T) {
	executor := privateFixedExecutor(t, 3)
	if executor.cores != 0 || executor.capacity != 3 || len(executor.queue) != 0 {
		t.Fatal("constructor prestarted cores", executor.cores, executor.capacity)
	}
	observed := make(chan int, 3)
	for index := 0; index < 3; index++ {
		future := mainFixedTestFuture(func() { observed <- index })
		executor.submit(future)
		awaitFixed(t, future.done)
		executor.mu.Lock()
		cores, queued := executor.cores, len(executor.queue)
		executor.mu.Unlock()
		// Previous futures have completed. An available/idle core must not prevent
		// growth to the configured core capacity on the next submission.
		if cores != index+1 || queued != 0 {
			t.Fatal("submission did not get a new firstTask core", index, cores, queued)
		}
		if value := awaitFixed(t, observed); value != index {
			t.Fatal(value, index)
		}
	}
}

func TestMainFixedExecutorFirstTaskBypassesQueueAndCanceledCoreIsReusable(t *testing.T) {
	executor := privateFixedExecutor(t, 1)
	var canceledInvoked atomic.Bool
	canceled := mainFixedTestFuture(func() { canceledInvoked.Store(true) })
	canceled.interrupt(context.Canceled)
	executor.submit(canceled)
	executor.mu.Lock()
	cores, queued := executor.cores, len(executor.queue)
	executor.mu.Unlock()
	if cores != 1 || queued != 0 {
		t.Fatal("firstTask was queued or no core was created", cores, queued)
	}
	actual := make(chan int, 1)
	next := mainFixedTestFuture(func() { actual <- 73 })
	executor.submit(next)
	awaitFixed(t, next.done)
	if canceledInvoked.Load() || awaitFixed(t, actual) != 73 {
		t.Fatal("canceled firstTask ran or poisoned its core")
	}
	executor.mu.Lock()
	cores = executor.cores
	executor.mu.Unlock()
	if cores != 1 {
		t.Fatal("replacement unexpectedly created another core", cores)
	}
}

func TestMainFixedExecutorCloseDrainsAcceptedQueueAndRejectsNewWork(t *testing.T) {
	executor := privateFixedExecutor(t, 1)
	started := make(chan struct{})
	release := make(chan struct{})
	defer func() {
		select {
		case <-release:
		default:
			close(release)
		}
	}()
	order := make(chan int, 2)
	first := mainFixedTestFuture(func() { close(started); <-release; order <- 1 })
	second := mainFixedTestFuture(func() { order <- 2 })
	executor.submit(first)
	awaitFixed(t, started)
	executor.submit(second)
	executor.mu.Lock()
	queued := len(executor.queue)
	executor.mu.Unlock()
	if queued != 1 {
		t.Fatal("full core did not queue subsequent task", queued)
	}
	closeStarted := make(chan struct{})
	closed := make(chan struct{})
	go func() { close(closeStarted); executor.close(); close(closed) }()
	awaitFixed(t, closeStarted)
	select {
	case <-closed:
		t.Fatal("close returned while its firstTask was active")
	default:
	}
	close(release)
	awaitFixed(t, closed)
	awaitFixed(t, first.done)
	awaitFixed(t, second.done)
	if a, b := awaitFixed(t, order), awaitFixed(t, order); a != 1 || b != 2 {
		t.Fatal(a, b)
	}
	unsubmitted := mainFixedTestFuture(func() { panic("closed executor ran new work") })
	if failure := fixedRecovered(func() { executor.submit(unsubmitted) }); failure != "submit to closed main fixed executor" {
		t.Fatal(failure)
	}
}

func TestMainFixedExecutorCloseBeforeAnySubmission(t *testing.T) {
	executor := newMainFixedExecutor(4)
	executor.close()
	if executor.cores != 0 || len(executor.queue) != 0 {
		t.Fatal("closing unused pool created work")
	}
	if failure := fixedRecovered(func() { executor.submit(mainFixedTestFuture(func() {})) }); failure != "submit to closed main fixed executor" {
		t.Fatal(failure)
	}
}
