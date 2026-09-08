package query

import (
	"context"
	"runtime"
	"sync"
	"time"
)

// A worker retains its interrupt state across successive source tasks. Normal
// shutdown must release the parent bridge without canceling an observed task
// context. Unlike WithCancel(parent), this context has no parent registration or
// timer of its own. The coordinator owns the single AfterFunc bridge.
type mainFixedWorkerContext struct {
	parent context.Context
	values context.Context
	done   chan struct{}
	mu     sync.Mutex
	err    error
}

func (c *mainFixedWorkerContext) Deadline() (time.Time, bool) { return c.parent.Deadline() }
func (c *mainFixedWorkerContext) Done() <-chan struct{}       { return c.done }
func (c *mainFixedWorkerContext) Err() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.err
}
func (c *mainFixedWorkerContext) Value(key any) any { return c.values.Value(key) }
func (c *mainFixedWorkerContext) interrupt(err error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.err == nil {
		c.err = err
		close(c.done)
	}
}

// The prepared path shares main's process-wide fixed executor capacity. The
// legacy runner is deliberately not migrated by this change.
type mainFixedExecutor struct {
	mu     sync.Mutex
	ready  *sync.Cond
	queue  []*mainFixedFuture
	closed bool
	exited sync.WaitGroup
}
type mainFixedFuture struct {
	mu                          sync.Mutex
	started, canceled, complete bool
	worker                      *mainFixedWorkerContext
	run                         func()
	done                        chan struct{}
}

func (f *mainFixedFuture) setRun(run func()) {
	f.mu.Lock()
	defer f.mu.Unlock()
	// Parent cancellation can run before the coordinator installs the closure.
	// A canceled future must never regain a reference to the request task.
	if !f.canceled && !f.complete {
		f.run = run
	}
}
func (f *mainFixedFuture) finish() {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.run = nil
	f.worker = nil
	if !f.complete {
		f.complete = true
		close(f.done)
	}
}
func (f *mainFixedFuture) interrupt(err error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	// Future.cancel cannot interrupt a worker instance that already completed.
	if f.complete || f.canceled {
		return
	}
	f.canceled = true
	f.worker.interrupt(err)
	if !f.started {
		f.run = nil
		f.worker = nil
		f.complete = true
		close(f.done)
	}
}
func (f *mainFixedFuture) execute() {
	f.mu.Lock()
	if f.canceled {
		f.mu.Unlock()
		return
	}
	f.started = true
	run := f.run
	f.mu.Unlock()
	defer f.finish()
	run()
}
func newMainFixedExecutor(capacity int) *mainFixedExecutor {
	e := &mainFixedExecutor{capacity: max(1, capacity)}
	e.ready = sync.NewCond(&e.mu)
	return e
}

// Like ThreadPoolExecutor.execute, a submission below core capacity starts a
// worker with that future as firstTask, even if an existing core is idle.
// Only later submissions enter the FIFO queue; no core is prestarted.
func (e *mainFixedExecutor) submit(future *mainFixedFuture) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.closed {
		panic("submit to closed main fixed executor")
	}
	if e.cores < e.capacity {
		e.cores++
		e.exited.Add(1)
		go e.runWorker(future)
		return
	}
	e.queue = append(e.queue, future)
	e.ready.Signal()
}

func (e *mainFixedExecutor) runWorker(firstTask *mainFixedFuture) {
	defer e.exited.Done()
	firstTask.execute()
	// Do not retain a completed first future while the executor worker is idle.
	firstTask = nil
	for {
		e.mu.Lock()
		for len(e.queue) == 0 && !e.closed {
			e.ready.Wait()
		}
		if len(e.queue) == 0 {
			e.mu.Unlock()
			return
		}
		future := e.queue[0]
		e.queue[0] = nil
		e.queue = e.queue[1:]
		e.mu.Unlock()
		future.execute()
	}
}

// Used only to release private test executors after all their futures join.
func (e *mainFixedExecutor) close() {
	e.mu.Lock()
	e.closed = true
	e.ready.Broadcast()
	e.mu.Unlock()
	e.exited.Wait()
}

var mainPreparedExecutor = sync.OnceValue(func() *mainFixedExecutor {
	available := max(1, runtime.NumCPU())
	return newMainFixedExecutor(max(min(available, 8), max(1, available/2)))
})

// runMainFixedWorkersInOrderUntil follows main's prepared direct-string runner.
// Only a merged contiguous source prefix admits more work. This is separate
// from the legacy per-source runner and adds no worker startup/completion gate.
func runMainFixedWorkersInOrderUntil[T any](ctx context.Context, count, parallel int, task func(context.Context, int) T, consume func(int, T) bool) {
	if count <= 0 {
		return
	}
	runMainFixedWorkersWithExecutor(ctx, count, parallel, task, consume, mainPreparedExecutor())
}

func runMainFixedWorkersWithExecutor[T any](ctx context.Context, count, parallel int, task func(context.Context, int) T, consume func(int, T) bool, executor *mainFixedExecutor) {
	if count <= 0 {
		return
	}
	type outcome struct {
		index   int
		value   T
		failure any
	}
	workerCount := min(count, max(1, parallel))
	indexes := make(chan int, workerCount)
	// Main publishes to an unbounded queue even if the worker is interrupted.
	// At most count outcomes exist, so this capacity provides the same guarantee.
	outcomes := make(chan outcome, count)
	workers := make([]*mainFixedWorkerContext, workerCount)
	for i := range workers {
		workers[i] = &mainFixedWorkerContext{parent: ctx, values: context.WithoutCancel(ctx), done: make(chan struct{})}
	}
	futures := make([]*mainFixedFuture, workerCount)
	for i, worker := range workers {
		futures[i] = &mainFixedFuture{worker: worker, done: make(chan struct{})}
	}
	interruptWorkers := func(err error) {
		for _, future := range futures {
			future.interrupt(err)
		}
	}
	invoke := func(worker context.Context, index int) (out outcome) {
		out.index = index
		defer func() { out.failure = recover() }()
		out.value = task(worker, index)
		return
	}
	for _, future := range futures {
		worker := future.worker
		future.setRun(func() {
			for {
				// LinkedBlockingQueue.take is interruptible, including on an already
				// interrupted worker. Do not consume another queued task in that state.
				select {
				case <-worker.Done():
					return
				default:
				}
				var index int
				select {
				case <-worker.Done():
					return
				case index = <-indexes:
				}
				if index < 0 {
					return
				}
				out := invoke(worker, index)
				outcomes <- out
				if out.failure != nil {
					return
				}
			}
		})
	}
	// Install every closure before cancellation can clear its future's worker.
	bridgeDone := make(chan struct{})
	stopBridge := context.AfterFunc(ctx, func() {
		defer close(bridgeDone)
		interruptWorkers(ctx.Err())
	})
	normal := false
	defer func() {
		if !normal {
			interruptWorkers(context.Canceled)
		}
		for _, future := range futures {
			<-future.done
		}
		// AfterFunc stop does not wait for a callback that has already started.
		if !stopBridge() {
			<-bridgeDone
		}
	}()
	for _, future := range futures {
		executor.submit(future)
	}
	nextTask := 0
	for nextTask < workerCount {
		indexes <- nextTask
		nextTask++
	}
	completed := make([]*outcome, count)
	nextResult := 0
	for nextResult < count {
		// The coordinator's LinkedBlockingQueue.take is also interruptible before
		// returning an already queued outcome.
		select {
		case <-ctx.Done():
			panic(ctx.Err())
		default:
		}
		var out outcome
		select {
		case <-ctx.Done():
			panic(ctx.Err())
		case out = <-outcomes:
		}
		completed[out.index] = &out
		for nextResult < count && completed[nextResult] != nil {
			ordered := completed[nextResult]
			if ordered.failure != nil {
				panic(ordered.failure)
			}
			if consume(nextResult, ordered.value) {
				return
			}
			nextResult++
			if nextTask < count {
				indexes <- nextTask
				nextTask++
			}
		}
	}
	for range workers {
		indexes <- -1
	}
	normal = true
}
