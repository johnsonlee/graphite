package store

import (
	"context"
	"encoding/binary"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func persistentFixtureBytes(t *testing.T, dir string) []byte {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(dir, callSiteIndexFile))
	if err != nil {
		t.Fatal(err)
	}
	return data
}
func persistentReaderUnits(data []byte) int64 {
	u := int64(0)
	for at := 48; at < 64; at += 4 {
		u += int64(binary.BigEndian.Uint32(data[at:]))
	}
	return 43 + 2*u + 4*int64(binary.BigEndian.Uint32(data[12:])) + int64(binary.BigEndian.Uint32(data[8:])) + int64(binary.BigEndian.Uint32(data[64:]))
}
func persistentBatches(units int64) []int64 {
	var result []int64
	for units >= 1024 {
		result = append(result, 1024)
		units -= 1024
	}
	if units != 0 {
		result = append(result, units)
	}
	return result
}
func requireMainPreparationIdle(t *testing.T, s *Store) {
	t.Helper()
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	state := s.callSiteIndex.main[0]
	if state.loading != nil || state.view != nil || state.unavailable {
		t.Fatalf("failed attempt published/poisoned preparation: %+v", state)
	}
}

// The fixture was written and accepted by actual main. The formula is only a
// valid-read cross-check; malformed prefixes below assert execution order.
func TestMainPersistentWorkValidWarmAndPolicyIsolation(t *testing.T) {
	dir := copyIndexFixture(t)
	data := persistentFixtureBytes(t, dir)
	s := openIndexFixture(t, dir, "MAPPED")
	var calls []int64
	consumer := func(n int64) error {
		// A callback must not run under the lifetime lock.
		s.callSiteIndex.mu.Lock()
		s.callSiteIndex.mu.Unlock()
		calls = append(calls, n)
		return nil
	}
	view, ok, err := s.tryMainCallSiteStringIndexWithWork(context.Background(), false, consumer)
	if err != nil || !ok || view == nil {
		t.Fatalf("load: %v %v", ok, err)
	}
	want := append([]int64{1}, persistentBatches(persistentReaderUnits(data))...)
	want = append(want, 1)
	if !reflect.DeepEqual(calls, want) {
		t.Fatalf("batches %v, want identity + reader + EOF %v", calls, want)
	}
	if _, ok, err := s.tryMainCallSiteStringIndexWithWork(context.Background(), false, func(int64) error {
		t.Fatal("warm retained hit charged persistent read")
		return nil
	}); !ok || err != nil {
		t.Fatal(ok, err)
	}
	calls = nil
	if _, ok, err := s.tryMainCallSiteStringIndexWithWork(context.Background(), true, consumer); !ok || err != nil {
		t.Fatal(ok, err)
	}
	if !reflect.DeepEqual(calls, mappedFixtureBatches(data)) {
		t.Fatalf("mapped policy must use its own validator batches: %v", calls)
	}
	requireIndex(t, s) // The independent strict candidate reader remains valid.
}

func TestMainPersistentWorkMalformedPrefixAndFinally(t *testing.T) {
	for _, tc := range []struct {
		name   string
		mutate func([]byte) []byte
		units  int64
	}{
		{"magic", func(b []byte) []byte { b[0] ^= 1; return b }, 1},
		{"version", func(b []byte) []byte { b[7] ^= 1; return b }, 2},
		{"partial-first-int", func(b []byte) []byte { return b[:2] }, 1},
		{"partial-fourth-int", func(b []byte) []byte { return b[:15] }, 4},
		{"partial-identity", func(b []byte) []byte { return b[:47] }, 4},
		{"complete-identity-then-eof", func(b []byte) []byte { return b[:48] }, 37},
		{"invalid-unique-count", func(b []byte) []byte { binary.BigEndian.PutUint32(b[48:], ^uint32(0)); return b }, 40},
	} {
		t.Run(tc.name, func(t *testing.T) {
			dir := copyIndexFixture(t)
			if err := os.WriteFile(filepath.Join(dir, callSiteIndexFile), tc.mutate(persistentFixtureBytes(t, dir)), 0600); err != nil {
				t.Fatal(err)
			}
			s := openIndexFixture(t, dir, "MAPPED")
			var calls []int64
			for attempt := 0; attempt < 2; attempt++ {
				_, ok, err := s.tryMainCallSiteStringIndexWithWork(context.Background(), false, func(n int64) error { calls = append(calls, n); return nil })
				if ok || err != nil {
					t.Fatalf("optional malformed file: ok=%v err=%v", ok, err)
				}
				requireMainPreparationIdle(t, s)
			}
			want := []int64{1, tc.units, 1, tc.units}
			if !reflect.DeepEqual(calls, want) {
				t.Fatalf("read prefix/retry batches=%v, want %v", calls, want)
			}
			rejection := errors.New("reject final malformed-read work")
			_, ok, err := s.tryMainCallSiteStringIndexWithWork(context.Background(), false, func(n int64) error {
				if n == tc.units && len(calls) == 5 {
					return rejection
				}
				calls = append(calls, n)
				return nil
			})
			var aborted *WorkAbortedError
			if ok || !errors.As(err, &aborted) || !errors.Is(err, rejection) {
				t.Fatalf("finally rejection swallowed as malformed fallback: %v %v", ok, err)
			}
			requireMainPreparationIdle(t, s)
		})
	}
}

func TestMainPersistentWorkEOFAndAbortRetry(t *testing.T) {
	for _, trailing := range []bool{false, true} {
		t.Run(map[bool]string{false: "EOF-budget-rejection", true: "trailing-byte"}[trailing], func(t *testing.T) {
			dir := copyIndexFixture(t)
			data := persistentFixtureBytes(t, dir)
			body := persistentReaderUnits(data)
			if trailing {
				if err := os.WriteFile(filepath.Join(dir, callSiteIndexFile), append(data, 42), 0600); err != nil {
					t.Fatal(err)
				}
			}
			s := openIndexFixture(t, dir, "MAPPED")
			var total int64
			rejection := errors.New("EOF work rejected")
			_, ok, err := s.tryMainCallSiteStringIndexWithWork(context.Background(), false, func(n int64) error {
				total += n
				if total > body+1 {
					return rejection
				}
				return nil
			})
			if ok || trailing && err != nil || !trailing && !errors.Is(err, rejection) {
				t.Fatal(ok, err)
			}
			want := body + 2
			if trailing {
				want-- // No finally flush on the failed separate EOF check.
			}
			if total != want {
				t.Fatalf("attempted units=%d, want %d", total, want)
			}
			requireMainPreparationIdle(t, s)
			if err := os.WriteFile(filepath.Join(dir, callSiteIndexFile), data, 0600); err != nil {
				t.Fatal(err)
			}
			if _, ok, err := s.tryMainCallSiteStringIndex(context.Background(), false); !ok || err != nil {
				t.Fatalf("fresh retry poisoned: %v %v", ok, err)
			}
		})
	}
}

func TestMainPersistentLegacyStringIdentitySurvivesFailedRead(t *testing.T) {
	dir := copyIndexFixture(t)
	for _, file := range []string{"graph.strings.identity", "graph.callsite-string-content.identity"} {
		if err := os.Remove(filepath.Join(dir, file)); err != nil {
			t.Fatal(err)
		}
	}
	data := persistentFixtureBytes(t, dir)
	data[0] ^= 1
	if err := os.WriteFile(filepath.Join(dir, callSiteIndexFile), data, 0600); err != nil {
		t.Fatal(err)
	}
	s := openIndexFixture(t, dir, "MAPPED")
	var totals []int64
	for attempt := 0; attempt < 2; attempt++ {
		var units int64
		if _, ok, err := s.tryMainCallSiteStringIndexWithWork(context.Background(), false, func(n int64) error { units += n; return nil }); ok || err != nil {
			t.Fatal(ok, err)
		}
		totals = append(totals, units)
		requireMainPreparationIdle(t, s)
		if !s.callSiteIndex.semanticStringIdentityReady {
			t.Fatal("successful string identity lost after bad sidecar")
		}
		if err := s.ClearStringPropertyIndexes(context.Background()); err != nil {
			t.Fatal(err)
		}
	}
	nodes := int64(len(s.byKind["CallSiteNode"]))
	want := []int64{int64(len(s.Strings)) + nodes + 1, nodes + 1}
	if !reflect.DeepEqual(totals, want) {
		t.Fatalf("legacy identity history=%v, want %v", totals, want)
	}
}

type persistentWaitingContext struct {
	context.Context
	waiting chan struct{}
	once    sync.Once
}

func (c *persistentWaitingContext) Done() <-chan struct{} {
	c.once.Do(func() { close(c.waiting) })
	return c.Context.Done()
}

func TestMainPersistentOwnerWaiterCancellationAndClose(t *testing.T) {
	s := openIndexFixture(t, copyIndexFixture(t), "MAPPED")
	entered, release := make(chan struct{}), make(chan struct{})
	owner := make(chan error, 1)
	go func() {
		first := true
		_, _, err := s.tryMainCallSiteStringIndexWithWork(context.Background(), false, func(int64) error {
			if first {
				first = false
				close(entered)
				<-release
			}
			return nil
		})
		owner <- err
	}()
	<-entered
	base, cancel := context.WithCancel(context.Background())
	ctx := &persistentWaitingContext{Context: base, waiting: make(chan struct{})}
	var waiterCalls atomic.Int32
	waiter := make(chan error, 1)
	go func() {
		_, _, err := s.tryMainCallSiteStringIndexWithWork(ctx, false, func(int64) error { waiterCalls.Add(1); return nil })
		waiter <- err
	}()
	select {
	case <-ctx.waiting:
	case <-time.After(5 * time.Second):
		t.Fatal("waiter never entered preparation wait")
	}
	cancel()
	select {
	case err := <-waiter:
		if !errors.Is(err, context.Canceled) || waiterCalls.Load() != 0 {
			t.Fatal("waiter charged owner work", err, waiterCalls.Load())
		}
	case <-time.After(5 * time.Second):
		t.Fatal("cancelled waiter did not leave")
	}
	closed := make(chan error, 1)
	go func() { closed <- s.Close() }()
	// Observe Store's real Close signal, not a sleep-based scheduling guess.
	s.callSiteIndex.mu.RLock()
	closing := s.callSiteIndex.closing
	s.callSiteIndex.mu.RUnlock()
	<-closing
	close(release)
	select {
	case err := <-owner:
		if !errors.Is(err, ErrStoreClosed) {
			t.Fatal("owner published after Close", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("owner failed to release its ticket")
	}
	select {
	case err := <-closed:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Close did not join preparation")
	}
	requireMainPreparationIdle(t, s)
}

func TestMainPersistentCallbackPanicReleasesTicket(t *testing.T) {
	s := openIndexFixture(t, copyIndexFixture(t), "MAPPED")
	marker := errors.New("unexpected callback panic")
	func() {
		defer func() {
			if recover() != marker {
				t.Error("callback panic identity lost")
			}
		}()
		calls := 0
		s.tryMainCallSiteStringIndexWithWork(context.Background(), false, func(int64) error {
			calls++
			if calls == 2 {
				panic(marker) // After mapping, at the reader's final/batched work.
			}
			return nil
		})
	}()
	requireMainPreparationIdle(t, s)
	if _, ok, err := s.tryMainCallSiteStringIndex(context.Background(), false); !ok || err != nil {
		t.Fatal("panic poisoned retry", ok, err)
	}
}
