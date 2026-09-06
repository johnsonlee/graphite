package server

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"
)

type QueryError struct {
	Message       string `json:"error"`
	Code          string `json:"code"`
	TimeoutMillis int64  `json:"timeoutMs,omitempty"`
	Status        int    `json:"-"`
}

func (e *QueryError) Error() string { return e.Message }

func cancelledError() *QueryError {
	return &QueryError{Message: "Cypher query cancelled", Code: "cypher_query_cancelled", Status: 503}
}

// Guard holds admission until execution AND response serialization finish.
// A timed-out worker must actually exit before its slot is reused.
type Guard struct {
	mu            sync.Mutex
	maxConcurrent int
	maxTimeout    time.Duration
	active        map[uint64]context.CancelCauseFunc
	next          uint64
	closed        bool
}

func NewGuard(maxConcurrent int, maxTimeout time.Duration) (*Guard, error) {
	if maxConcurrent <= 0 || maxTimeout <= 0 {
		return nil, errors.New("Cypher concurrency and maximum timeout must be positive")
	}
	return &Guard{maxConcurrent: maxConcurrent, maxTimeout: maxTimeout, active: make(map[uint64]context.CancelCauseFunc)}, nil
}

// Execute passes cooperative cancellation to every query phase. A nil client
// timeout uses the server maximum. The caller must not write an HTTP response
// inside work, so a cancellation during serialization can still change status.
func (g *Guard) Execute(parent context.Context, clientTimeout *time.Duration, work func(context.Context) (any, error)) (value any, err error) {
	timeout := g.maxTimeout
	if clientTimeout != nil {
		if *clientTimeout <= 0 {
			return nil, &QueryError{Message: "clientTimeoutMillis must be positive", Code: "cypher_query_failed", Status: 400}
		}
		if *clientTimeout < timeout {
			timeout = *clientTimeout
		}
	}
	g.mu.Lock()
	if g.closed {
		g.mu.Unlock()
		return nil, cancelledError()
	}
	if len(g.active) == g.maxConcurrent {
		g.mu.Unlock()
		return nil, &QueryError{Message: fmt.Sprintf("Cypher concurrency limit reached (%d active queries); retry later", g.maxConcurrent), Code: "cypher_concurrency_limit", Status: 429}
	}
	ctx, cancel := context.WithCancelCause(parent)
	timeoutError := &QueryError{Message: fmt.Sprintf("Cypher query timed out after %d ms", timeout.Milliseconds()), Code: "cypher_query_timeout", TimeoutMillis: timeout.Milliseconds(), Status: 504}
	deadline, stop := context.WithTimeoutCause(ctx, timeout, timeoutError)
	g.next++
	id := g.next
	g.active[id] = cancel
	g.mu.Unlock()
	defer func() {
		// Cancellation wins over success, including cancellation during encode.
		if cause := context.Cause(deadline); cause != nil {
			value = nil
			var qe *QueryError
			if errors.As(cause, &qe) {
				err = qe
			} else {
				err = cancelledError()
			}
		}
		stop()
		cancel(nil)
		g.mu.Lock()
		delete(g.active, id)
		g.mu.Unlock()
	}()
	if deadline.Err() != nil {
		return nil, context.Cause(deadline)
	}
	return work(deadline)
}

func (g *Guard) Close() error {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.closed = true
	for _, cancel := range g.active {
		cancel(cancelledError())
	}
	return nil
}

func (g *Guard) Active() int {
	g.mu.Lock()
	defer g.mu.Unlock()
	return len(g.active)
}
