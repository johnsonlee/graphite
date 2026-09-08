package query

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestPublicCancellationPreservesGoErrorIdentity(t *testing.T) {
	for _, reason := range []error{nil, errors.New("transport closed"),
		&Error{Class: "CypherQueryCancelledException", Message: "request withdrawn"},
		&Error{Class: "CypherQueryTimeoutException", Message: "Cypher query timed out after 17 ms"},
	} {
		ctx, cancel := context.WithCancelCause(context.Background())
		cancel(reason)
		originalCause := context.Cause(ctx)
		_, err := ExecuteCross(ctx, nil, "RETURN 1 AS x", nil, -1)
		var failure *Error
		if !errors.Is(err, context.Canceled) || !errors.As(err, &failure) {
			t.Fatalf("public cancellation lost Go identity or typed error: %T %v", err, err)
		}
		if expected, ok := reason.(*Error); ok {
			if failure.Class != expected.Class || failure.Message != expected.Message || !errors.Is(err, reason) || failure == expected {
				t.Fatalf("lost or mutated original typed cancellation: got=%#v original=%#v", failure, expected)
			}
		} else if failure.Class != "CypherQueryCancelledException" || failure.Message != "Cypher query cancelled" {
			t.Fatalf("ordinary cancellation contract: %#v", failure)
		}
		if context.Cause(ctx) != originalCause {
			t.Fatal("public conversion replaced the caller's cancellation cause")
		}
	}
}

func TestPublicDeadlineDoesNotInventTimeoutDuration(t *testing.T) {
	ctx, cancel := context.WithDeadline(context.Background(), time.Unix(0, 0))
	defer cancel()
	_, err := ExecuteCross(ctx, nil, "RETURN 1 AS x", nil, -1)
	if !errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) {
		t.Fatalf("deadline classification: %T %v", err, err)
	}
	var queryError *Error
	if errors.As(err, &queryError) {
		t.Fatalf("invented JVM timeout without a duration-bearing cause: %#v", queryError)
	}
}
