package query

import (
	"errors"
	"fmt"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func TestExecutionContextStoreWorkFailureIdentity(t *testing.T) {
	if got := (evaluator{}).storeWorkConsumer(); got != nil {
		t.Fatal("untracked execution installed a storage work consumer")
	}
	work, err := NewExecutionContext(3)
	if err != nil {
		t.Fatal(err)
	}
	consume := (evaluator{work: work}).storeWorkConsumer()
	for _, units := range []int64{1, 2} {
		if err := consume(units); err != nil {
			t.Fatalf("exact remaining budget rejected: %v", err)
		}
	}
	budget := consume(1)
	var failure *Error
	if !errors.As(budget, &failure) || failure.Class != "CypherBudgetExceededException" || failure.Message != "Cypher work budget exceeded after 3 graph work units; add a selective label/filter or use a metadata-backed query" {
		t.Fatalf("storage callback lost budget error: %#v", budget)
	}
	if work.Diagnostics().WorkUnitsConsumed != 3 || work.IsCancelled() {
		t.Fatalf("storage callback changed tracker/cancellation: %#v / %v", work.Diagnostics(), work.IsCancelled())
	}
	if err := consume(0); err != nil {
		t.Fatalf("zero work after exact exhaustion: %v", err)
	}
	cancelled, err := NewExecutionContext(3)
	if err != nil {
		t.Fatal(err)
	}
	reason := &Error{Class: "CypherQueryTimeoutException", Message: "original request deadline"}
	if !cancelled.Cancel(reason) {
		t.Fatal("first cancellation was not retained")
	}
	cancelError := (evaluator{work: cancelled}).storeWorkConsumer()(1)
	if cancelError != reason || cancelled.Diagnostics().WorkUnitsConsumed != 0 {
		t.Fatalf("storage callback replaced cancellation identity or charged canceled work: %v", cancelError)
	}
	for _, tc := range []struct {
		name  string
		cause error
	}{{"budget", budget}, {"cancellation", cancelError}} {
		for name, bridge := range map[string]func(error){"projection": failProjectionRead, "main-source": failMainStringRead} {
			t.Run(tc.name+"/"+name, func(t *testing.T) {
				wrapped := fmt.Errorf("retained loader: %w", &store.WorkAbortedError{Cause: tc.cause})
				_, got := workAttempt(func() (any, error) { bridge(wrapped); return nil, nil })
				if got != tc.cause {
					t.Fatalf("storage bridge changed original error identity: got %#v, want %#v", got, tc.cause)
				}
			})
		}
	}
}
