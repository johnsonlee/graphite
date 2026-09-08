package query

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestExecutionContextCancellationReachesStorage(t *testing.T) {
	for _, alreadyCancelled := range []bool{false, true} {
		t.Run(map[bool]string{false: "during-call", true: "before-call"}[alreadyCancelled], func(t *testing.T) {
			work, err := NewExecutionContext(4)
			if err != nil {
				t.Fatal(err)
			}
			reason := &Error{Class: "CypherQueryTimeoutException", Message: "Cypher query timed out after 17 ms"}
			if alreadyCancelled {
				work.Cancel(reason)
			}
			ctx, release := work.bind(context.Background())
			defer release()
			if !alreadyCancelled {
				work.Cancel(reason)
			}
			select {
			case <-ctx.Done():
			case <-time.After(5 * time.Second):
				t.Fatal("request cancellation did not reach the storage context")
			}
			cause := context.Cause(ctx)
			if !errors.Is(cause, reason) || !errors.Is(cause, context.Canceled) {
				t.Fatalf("storage lost request cancellation identity: %v", cause)
			}
			if work.Cancel(nil) || work.Diagnostics().WorkUnitsConsumed != 0 {
				t.Fatal("cancellation replaced the first reason or consumed graph work")
			}
		})
	}
}

func TestExecutionContextLocalCancellationDoesNotPoisonNextCall(t *testing.T) {
	for _, cancelParent := range []bool{false, true} {
		t.Run(map[bool]string{false: "completed-child", true: "cancelled-parent"}[cancelParent], func(t *testing.T) {
			work, err := NewExecutionContext(4)
			if err != nil {
				t.Fatal(err)
			}
			parent, cancel := context.WithCancel(context.Background())
			defer cancel()
			child, release := work.bind(parent)
			if cancelParent {
				cancel()
			} else {
				release()
			}
			<-child.Done()
			release()
			if work.IsCancelled() {
				t.Fatal("local cancellation escaped into the reusable request signal")
			}
			result, err := ExecuteCrossWithOptions(context.Background(), nil, "RETURN 7 AS value", nil, -1, ExecutionOptions{ExecutionContext: work})
			if err != nil || len(result.Rows) != 1 || result.Rows[0]["value"] != int32(7) {
				t.Fatalf("next sequential execution failed: %#v, %v", result, err)
			}
			if work.Diagnostics().WorkUnitsConsumed != 0 || work.Diagnostics().GeneralFallbackExecutions != 1 {
				t.Fatalf("graph-free execution accounting: %+v", work.Diagnostics())
			}
		})
	}
}
