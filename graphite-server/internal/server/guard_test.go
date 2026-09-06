package server

import (
	"context"
	"errors"
	"testing"
	"time"
)

func assertQueryError(t *testing.T, err error, status int, code string) *QueryError {
	t.Helper()
	var qe *QueryError
	if !errors.As(err, &qe) || qe.Status != status || qe.Code != code {
		t.Fatalf("got %v, want %d %s", err, status, code)
	}
	return qe
}

func TestGuardTimeoutRetainsPermitUntilWorkerFinishes(t *testing.T) {
	g, _ := NewGuard(1, time.Second)
	t.Cleanup(func() { _ = g.Close() })
	timeout := 10 * time.Millisecond
	cancelled, finish := make(chan struct{}), make(chan struct{})
	done := make(chan error, 1)
	go func() {
		_, err := g.Execute(context.Background(), &timeout, func(ctx context.Context) (any, error) {
			<-ctx.Done()
			close(cancelled)
			<-finish
			return "late success", nil
		})
		done <- err
	}()
	<-cancelled
	_, err := g.Execute(context.Background(), nil, func(context.Context) (any, error) { t.Error("rejected work executed"); return nil, nil })
	assertQueryError(t, err, 429, "cypher_concurrency_limit")
	close(finish)
	qe := assertQueryError(t, <-done, 504, "cypher_query_timeout")
	if qe.TimeoutMillis != 10 || g.Active() != 0 {
		t.Fatalf("timeout/permit: %+v active=%d", qe, g.Active())
	}
	value, err := g.Execute(context.Background(), nil, func(context.Context) (any, error) { return 42, nil })
	if value != 42 || err != nil {
		t.Fatalf("recovery: %v %v", value, err)
	}
}

func TestGuardServerMaximumOverridesLongClientTimeout(t *testing.T) {
	g, _ := NewGuard(1, 5*time.Millisecond)
	long := time.Hour
	_, err := g.Execute(context.Background(), &long, func(ctx context.Context) (any, error) { <-ctx.Done(); return nil, ctx.Err() })
	if qe := assertQueryError(t, err, 504, "cypher_query_timeout"); qe.TimeoutMillis != 5 {
		t.Fatalf("effective timeout: %+v", qe)
	}
}

func TestGuardCancellationAndShutdownOverrideSuccess(t *testing.T) {
	for _, shutdown := range []bool{false, true} {
		t.Run(map[bool]string{true: "shutdown", false: "client"}[shutdown], func(t *testing.T) {
			g, _ := NewGuard(1, time.Minute)
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			value, err := g.Execute(ctx, nil, func(ctx context.Context) (any, error) {
				if shutdown {
					_ = g.Close()
				} else {
					cancel()
				}
				<-ctx.Done()
				return "must not publish", nil
			})
			assertQueryError(t, err, 503, "cypher_query_cancelled")
			if value != nil || g.Active() != 0 {
				t.Fatal("cancelled query published output or leaked slot")
			}
			_ = g.Close()
			_, err = g.Execute(context.Background(), nil, func(context.Context) (any, error) { t.Error("closed guard executed"); return nil, nil })
			assertQueryError(t, err, 503, "cypher_query_cancelled")
		})
	}
}

func TestGuardRejectsInvalidTimeoutBeforeAdmission(t *testing.T) {
	g, _ := NewGuard(1, time.Second)
	for _, timeout := range []time.Duration{0, -1} {
		_, err := g.Execute(context.Background(), &timeout, func(context.Context) (any, error) { t.Error("invalid request executed"); return nil, nil })
		assertQueryError(t, err, 400, "cypher_query_failed")
	}
	if g.Active() != 0 {
		t.Fatal("invalid request consumed slot")
	}
}
