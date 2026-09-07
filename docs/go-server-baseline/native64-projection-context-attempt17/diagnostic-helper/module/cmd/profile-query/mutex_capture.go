package main

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"runtime/pprof"
	"sync"
	"sync/atomic"
)

// Diagnostic-only process-global ownership. It does not wrap/replace the query
// context, participate in worker scheduling, or touch Store/evaluator state.
var mutexWindowActive atomic.Bool

type mutexCapture struct {
	directory string
	rate      int
	stop      sync.Once
}

func writeMutexSnapshot(path string) error {
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	if err = pprof.Lookup("mutex").WriteTo(f, 0); err != nil {
		f.Close()
		return err
	}
	return f.Close()
}

// Preparation and baseline serialization occur with sampling disabled and
// outside the request interval. Start must immediately precede ExecuteCross.
func prepareMutexCapture(directory string, rate int) (*mutexCapture, error) {
	if rate < 0 {
		return nil, fmt.Errorf("mutex rate must be nonnegative")
	}
	if !mutexWindowActive.CompareAndSwap(false, true) {
		return nil, fmt.Errorf("another mutex window owns this process")
	}
	fail := true
	defer func() {
		if fail {
			mutexWindowActive.Store(false)
		}
	}()
	if old := runtime.SetMutexProfileFraction(-1); old != 0 {
		return nil, fmt.Errorf("mutex profiling already enabled at rate %d", old)
	}
	if err := writeMutexSnapshot(filepath.Join(directory, "mutex-before.pprof")); err != nil {
		return nil, err
	}
	fail = false
	return &mutexCapture{directory: directory, rate: rate}, nil
}
func (m *mutexCapture) Start() {
	runtime.SetMutexProfileFraction(m.rate)
}
func (m *mutexCapture) Stop() {
	m.stop.Do(func() { runtime.SetMutexProfileFraction(0) })
}
func (m *mutexCapture) Write() error {
	m.Stop()
	return writeMutexSnapshot(filepath.Join(m.directory, "mutex-after.pprof"))
}

// Panic paths still restore the global runtime setting without swallowing the
// query panic. Normal paths serialize only after snapshots/request deltas.
func (m *mutexCapture) Release() {
	m.Stop()
	mutexWindowActive.Store(false)
}
