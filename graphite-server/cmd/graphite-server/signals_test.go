package main

import (
	"os"
	"syscall"
	"testing"
	"time"
)

func TestShutdownContextPreservesFirstSignalAndCancels(t *testing.T) {
	for _, first := range []os.Signal{os.Interrupt, syscall.SIGTERM} {
		channel := make(chan os.Signal, 2)
		ctx, finish := shutdownContext(channel)
		channel <- first
		select {
		case <-ctx.Done():
		case <-time.After(5 * time.Second):
			t.Fatal("shutdown did not cancel command context")
		}
		channel <- syscall.SIGTERM
		if got := finish(); got != first {
			t.Fatalf("first signal = %v, got %v", first, got)
		}
		if got := finish(); got != first {
			t.Fatalf("idempotent finish changed signal: %v", got)
		}
	}
}

func TestShutdownContextNormalCompletionAndQueuedSignal(t *testing.T) {
	channel := make(chan os.Signal, 1)
	ctx, finish := shutdownContext(channel)
	if got := finish(); got != nil {
		t.Fatalf("normal completion fabricated %v", got)
	}
	if ctx.Err() == nil {
		t.Fatal("normal completion left listener context open")
	}

	// The signal is buffered before creating the listener, so finish must not
	// lose it when the stop and notification select arms are both ready.
	channel <- os.Interrupt
	_, finish = shutdownContext(channel)
	if got := finish(); got != os.Interrupt {
		t.Fatalf("lost queued signal: %v", got)
	}
}

func TestSignalExitStatusMatchesHotSpotAndKeepsUnsignaledFailures(t *testing.T) {
	cases := []struct {
		code   int
		signal os.Signal
		want   int
	}{
		{0, nil, 0}, {0, os.Interrupt, 130}, {0, syscall.SIGTERM, 143},
		{1, os.Interrupt, 130}, {1, syscall.SIGTERM, 143}, {2, syscall.SIGTERM, 143}, {1, nil, 1}, {2, nil, 2},
	}
	for _, item := range cases {
		if got := signalExitStatus(item.code, item.signal); got != item.want {
			t.Fatalf("code=%d signal=%v: got %d want %d", item.code, item.signal, got, item.want)
		}
	}
}
