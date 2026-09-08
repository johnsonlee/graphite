package query

import (
	"context"
	"fmt"
	"sync/atomic"
)

// ExecutionContext shares graph work and cancellation across sequential queries
// in one request. Separate executions must not use it concurrently; workers
// within one execution may share its atomic tracker. The zero value is invalid:
// construct a context with NewExecutionContext.
type ExecutionContext struct {
	tracker *executionWorkTracker
	signal  context.Context
	cancel  context.CancelCauseFunc
}

// ExecutionDiagnostics contains cumulative planner and graph-work counters.
// A snapshot reads each atomic counter independently, as the original tracker
// does; it is not a transaction across concurrently running workers.
type ExecutionDiagnostics struct {
	GraphIdSourceSelections             int64 `json:"graphIdSourceSelections"`
	GraphIdSourcePruningExecutions      int64 `json:"graphIdSourcePruningExecutions"`
	GraphIdSourcesPruned                int64 `json:"graphIdSourcesPruned"`
	GraphIdSourceConflicts              int64 `json:"graphIdSourceConflicts"`
	FastPathExecutions                  int64 `json:"fastPathExecutions"`
	FilteredNodeLimitFastPathExecutions int64 `json:"filteredNodeLimitFastPathExecutions"`
	GeneralFallbackExecutions           int64 `json:"generalFallbackExecutions"`
	WorkUnitsConsumed                   int64 `json:"workUnitsConsumed"`
}

type executionWorkTracker struct {
	maxWorkUnits                        int64
	remaining                           atomic.Int64
	cancellation                        atomic.Pointer[Error]
	graphIdSourceSelections             atomic.Int64
	graphIdSourcePruningExecutions      atomic.Int64
	graphIdSourcesPruned                atomic.Int64
	graphIdSourceConflicts              atomic.Int64
	fastPathExecutions                  atomic.Int64
	filteredNodeLimitFastPathExecutions atomic.Int64
	generalFallbackExecutions           atomic.Int64
}

// NewExecutionContext creates a request context with a positive graph-work
// budget. A budget-only executor creates a new context for each execution;
// callers that need cumulative accounting reuse the returned context.
func NewExecutionContext(maxWorkUnits int64) (*ExecutionContext, error) {
	if maxWorkUnits <= 0 {
		return nil, &Error{Class: "IllegalArgumentException", Message: "maxWorkUnits must be positive"}
	}
	tracker := &executionWorkTracker{maxWorkUnits: maxWorkUnits}
	tracker.remaining.Store(maxWorkUnits)
	signal, cancel := context.WithCancelCause(context.Background())
	return &ExecutionContext{tracker: tracker, signal: signal, cancel: cancel}, nil
}

// Cancel records the first cancellation reason and returns whether it won.
// A nil reason means ordinary query cancellation. Supplied cancellation or
// timeout errors retain their class and message; errors.Is recognizes both
// context.Canceled and the supplied reason. Callers must not mutate that reason.
func (c *ExecutionContext) Cancel(reason *Error) bool {
	if c.IsCancelled() {
		return false
	}
	stored := &Error{Class: "CypherQueryCancelledException", Message: "Cypher query cancelled", cause: context.Canceled}
	if reason != nil {
		stored = reason
	}
	if !c.tracker.cancellation.CompareAndSwap(nil, stored) {
		return false
	}
	c.cancel(stored)
	return true
}

// IsCancelled reports whether a cancellation reason has been recorded.
func (c *ExecutionContext) IsCancelled() bool {
	return c.tracker.cancellation.Load() != nil
}

// CancellationException returns the retained first reason, or a new default
// cancellation error without cancelling the context. Treat the error as immutable.
func (c *ExecutionContext) CancellationException() *Error {
	if reason := c.tracker.cancellation.Load(); reason != nil {
		return reason
	}
	return &Error{Class: "CypherQueryCancelledException", Message: "Cypher query cancelled", cause: context.Canceled}
}

func (c *ExecutionContext) checkCancelled() {
	if reason := c.tracker.cancellation.Load(); reason != nil {
		panic(reason)
	}
}

// bind forwards request cancellation into storage and worker contexts. A local
// early stop or cleanup only cancels the child, never this reusable request.
func (c *ExecutionContext) bind(parent context.Context) (context.Context, func()) {
	child, cancel := context.WithCancelCause(parent)
	stop := context.AfterFunc(c.signal, func() { cancel(c.CancellationException()) })
	if c.IsCancelled() {
		cancel(c.CancellationException())
	}
	return child, func() {
		stop()
		cancel(nil)
	}
}

// Test-only observer declaration: exists exclusively in the frozen-copy overlay.
var mappedEntryConsumeObserver func(*ExecutionContext, int64, string, any)

func (c *ExecutionContext) consume(units int64) {
	// Test-only observation: preserve actual algorithm and panic instance below.
	if observer := mappedEntryConsumeObserver; observer != nil {
		observer(c, units, "before", nil)
		defer func() {
			if failure := recover(); failure != nil {
				observer(c, units, "after", failure)
				panic(failure)
			}
			observer(c, units, "after", nil)
		}()
	}
	if units < 0 {
		panic(&Error{Class: "IllegalArgumentException", Message: "workUnits must be non-negative"})
	}
	c.checkCancelled()
	tracker := c.tracker
	for {
		available := tracker.remaining.Load()
		if units > available {
			if tracker.remaining.CompareAndSwap(available, 0) {
				panic(&Error{
					Class: "CypherBudgetExceededException",
					Message: fmt.Sprintf("Cypher work budget exceeded after %d graph work units; "+
						"add a selective label/filter or use a metadata-backed query", tracker.maxWorkUnits),
				})
			}
			continue
		}
		if tracker.remaining.CompareAndSwap(available, available-units) {
			return
		}
	}
}

func (c *ExecutionContext) recordGraphIdSourceSelection(initial, selected int64, conflicting bool) {
	if initial < 0 || selected < 0 || selected > initial {
		panic(&Error{Class: "IllegalArgumentException", Message: "Failed requirement."})
	}
	tracker := c.tracker
	tracker.graphIdSourceSelections.Add(1)
	if selected < initial {
		tracker.graphIdSourcePruningExecutions.Add(1)
		tracker.graphIdSourcesPruned.Add(initial - selected)
	}
	if conflicting {
		tracker.graphIdSourceConflicts.Add(1)
	}
}

func (c *ExecutionContext) recordFastPath() {
	c.tracker.fastPathExecutions.Add(1)
}

func (c *ExecutionContext) recordFilteredNodeLimitFastPath() {
	c.tracker.fastPathExecutions.Add(1)
	c.tracker.filteredNodeLimitFastPathExecutions.Add(1)
}

func (c *ExecutionContext) recordGeneralFallback() {
	c.tracker.generalFallbackExecutions.Add(1)
}

// Diagnostics returns the counters accumulated by this request so far.
func (c *ExecutionContext) Diagnostics() ExecutionDiagnostics {
	tracker := c.tracker
	return ExecutionDiagnostics{
		GraphIdSourceSelections:             tracker.graphIdSourceSelections.Load(),
		GraphIdSourcePruningExecutions:      tracker.graphIdSourcePruningExecutions.Load(),
		GraphIdSourcesPruned:                tracker.graphIdSourcesPruned.Load(),
		GraphIdSourceConflicts:              tracker.graphIdSourceConflicts.Load(),
		FastPathExecutions:                  tracker.fastPathExecutions.Load(),
		FilteredNodeLimitFastPathExecutions: tracker.filteredNodeLimitFastPathExecutions.Load(),
		GeneralFallbackExecutions:           tracker.generalFallbackExecutions.Load(),
		WorkUnitsConsumed:                   tracker.maxWorkUnits - tracker.remaining.Load(),
	}
}
