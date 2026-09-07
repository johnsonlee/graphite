package main

import (
	"context"
	"io"
	"os"
	"os/signal"
	"sync"
	"syscall"
)

// Keep the signal identity until graceful shutdown and profile flushing finish.
// HotSpot exposes 128+signal for these CLI termination signals, including when
// an agent reports a flush error. The error is still printed before main exits.
func executeWithSignals(args []string, stdout, stderr io.Writer, getenv func(string) string) int {
	notifications := make(chan os.Signal, 1)
	signal.Notify(notifications, os.Interrupt, syscall.SIGTERM)
	ctx, finish := shutdownContext(notifications)
	defer func() {
		signal.Stop(notifications)
		finish()
	}()
	code := executeProfiled(ctx, args, stdout, stderr, getenv)
	signal.Stop(notifications)
	return signalExitStatus(code, finish())
}

func signalExitStatus(code int, received os.Signal) int {
	switch received {
	case os.Interrupt:
		return 130
	case syscall.SIGTERM:
		return 143
	default:
		return code
	}
}

// finish is called only after signal delivery has been unregistered. It joins
// the listener before reading its state and retains a notification already
// queued at normal completion, even if the listener has not been scheduled yet.
func shutdownContext(notifications <-chan os.Signal) (context.Context, func() os.Signal) {
	ctx, cancel := context.WithCancel(context.Background())
	stopped := make(chan struct{})
	done := make(chan struct{})
	var received os.Signal
	go func() {
		defer close(done)
		select {
		case received = <-notifications:
			cancel()
		case <-stopped:
		}
	}()
	var once sync.Once
	return ctx, func() os.Signal {
		once.Do(func() {
			close(stopped)
			<-done
			if received == nil {
				select {
				case received = <-notifications:
				default:
				}
			}
			cancel()
		})
		return received
	}
}
