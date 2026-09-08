package store

import (
	"context"
	"encoding/binary"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"sync"
	"testing"
	"time"
)

func mappedFixtureBatches(data []byte) []int64 {
	result := []int64{1, 1, 1, 1, 1, 32, 1, 1, 1, 1, 1, 1}
	appendArray := func(n, width int64) {
		chunk := int64(mainViewChecksumChunkBytes) / width
		for n > 0 {
			units := min(n, chunk)
			result = append(result, units)
			n -= units
		}
	}
	for p := 0; p < 4; p++ {
		n := int64(binary.BigEndian.Uint32(data[48+p*4:]))
		appendArray(n, 4)
		appendArray(n, 4)
		appendArray(int64(binary.BigEndian.Uint32(data[12:])), 4)
	}
	appendArray(int64(binary.BigEndian.Uint32(data[8:])), 8)
	appendArray(int64(binary.BigEndian.Uint32(data[64:])), 8)
	return result
}

func TestMainMappedWorkValidationFailureCacheAndRetry(t *testing.T) {
	for _, tc := range []struct {
		name   string
		mutate func([]byte)
		prefix int
	}{
		{"header", func(b []byte) { b[0] ^= 1 }, 1},
		{"identity", func(b []byte) { b[16] ^= 1 }, 1},
		{"first-directory", func(b []byte) { binary.BigEndian.PutUint32(b[76:], ^uint32(0)) }, 12},
		{"checksum", func(b []byte) { b[len(b)-1] ^= 1 }, -1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			dir := copyIndexFixture(t)
			original := persistentFixtureBytes(t, dir)
			bad := append([]byte(nil), original...)
			tc.mutate(bad)
			if err := os.WriteFile(filepath.Join(dir, callSiteIndexFile), bad, 0600); err != nil {
				t.Fatal(err)
			}
			s := openIndexFixture(t, dir, "MAPPED")
			var calls []int64
			view, ok, err := s.tryMainCallSiteStringIndexWithWork(context.Background(), true, func(n int64) error { calls = append(calls, n); return nil })
			if err != nil || ok || view != nil {
				t.Fatal(view, ok, err)
			}
			want := mappedFixtureBatches(original)
			if tc.prefix >= 0 {
				want = want[:tc.prefix]
			}
			if !reflect.DeepEqual(calls, want) {
				t.Fatalf("validated-chunk charges %v, want %v", calls, want)
			}
			state, err := s.PersistedIndexState(context.Background())
			if err != nil || !state.MappedViewUnavailable {
				t.Fatal(state, err)
			}
			if err := os.WriteFile(filepath.Join(dir, callSiteIndexFile), original, 0600); err != nil {
				t.Fatal(err)
			}
			_, ok, err = s.tryMainCallSiteStringIndexWithWork(context.Background(), true, func(int64) error { t.Error("cached rejection recharged"); return nil })
			if err != nil || ok {
				t.Fatal(ok, err)
			}
			if err := s.ClearStringPropertyIndexes(context.Background()); err != nil {
				t.Fatal(err)
			}
			if _, ok, err = s.tryMainCallSiteStringIndex(context.Background(), true); err != nil || !ok {
				t.Fatal("clear did not allow retry", ok, err)
			}
		})
	}
}

func TestMainMappedWorkRejectedCallbackDoesNotPublishOrPoison(t *testing.T) {
	for _, call := range []int{1, 2, 6, 13} {
		t.Run(string(rune('A'+call)), func(t *testing.T) {
			s := openIndexFixture(t, copyIndexFixture(t), "MAPPED")
			cause := errors.New("reject exact mapped callback")
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			count := 0
			_, ok, err := s.tryMainCallSiteStringIndexWithWork(ctx, true, func(int64) error {
				count++
				if count == call {
					cancel()
					return cause
				}
				return nil
			})
			var aborted *WorkAbortedError
			if ok || !errors.As(err, &aborted) || aborted.Cause != cause {
				t.Fatal("late cancellation replaced callback cause", ok, err)
			}
			state, err := s.PersistedIndexState(context.Background())
			if err != nil || state.MappedViewUnavailable {
				t.Fatal(state, err)
			}
			if _, ok, err = s.tryMainCallSiteStringIndex(context.Background(), true); err != nil || !ok {
				t.Fatal("failure poisoned retry", ok, err)
			}
		})
	}
}

func TestMainMappedWorkChunkFailureDoesNotChargePartialChunk(t *testing.T) {
	for _, width := range []int{4, 8} {
		for _, bad := range []bool{false, true} {
			count := mainViewChecksumChunkBytes/width + 1
			data := make([]byte, count*width)
			for i := 0; i < count; i++ {
				if width == 4 {
					binary.BigEndian.PutUint32(data[i*width:], uint32(i))
				} else {
					binary.BigEndian.PutUint64(data[i*width:], uint64(i))
				}
			}
			var charged []int64
			v := mainViewValidator{ctx: context.Background(), data: data, consumer: func(n int64) error { charged = append(charged, n); return nil }}
			var failure any
			func() {
				defer func() { failure = recover() }()
				v.array(0, count, width, func(value int64) { v.require(!bad || value < int64(count-1)) })
			}()
			want := []int64{int64(count - 1)}
			if !bad {
				want = append(want, 1)
			}
			if (failure != nil) != bad || !reflect.DeepEqual(charged, want) {
				t.Fatalf("width=%d bad=%v charges=%v want=%v failure=%v", width, bad, charged, want, failure)
			}
		}
	}
}

func TestMainPersistentIdentityOwnerSharedAcrossMappedAndRetained(t *testing.T) {
	dir := copyIndexFixture(t)
	for _, name := range []string{"graph.strings.identity", "graph.callsite-string-content.identity"} {
		if err := os.Remove(filepath.Join(dir, name)); err != nil && !os.IsNotExist(err) {
			t.Fatal(err)
		}
	}
	s := openIndexFixture(t, dir, "MAPPED")
	data := persistentFixtureBytes(t, dir)
	entered, release := make(chan struct{}), make(chan struct{})
	var once sync.Once
	defer once.Do(func() { close(release) })
	owner := make(chan error, 1)
	var ownerUnits, waiterUnits int64
	go func() {
		first := true
		_, _, err := s.tryMainCallSiteStringIndexWithWork(context.Background(), false, func(n int64) error {
			ownerUnits += n
			if first {
				first = false
				close(entered)
				<-release
			}
			return nil
		})
		owner <- err
	}()
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("identity owner did not start")
	}
	ctx := &persistentWaitingContext{Context: context.Background(), waiting: make(chan struct{})}
	waiter := make(chan error, 1)
	go func() {
		_, _, err := s.tryMainCallSiteStringIndexWithWork(ctx, true, func(n int64) error { waiterUnits += n; return nil })
		waiter <- err
	}()
	select {
	case <-ctx.waiting:
	case <-time.After(5 * time.Second):
		t.Fatal("mapped loader did not wait for shared identity")
	}
	once.Do(func() { close(release) })
	for _, done := range []chan error{owner, waiter} {
		select {
		case err := <-done:
			if err != nil {
				t.Fatal(err)
			}
		case <-time.After(5 * time.Second):
			t.Fatal("loader did not finish")
		}
	}
	sCount, nCount := int64(len(s.Strings)), int64(len(s.byKind["CallSiteNode"]))
	wantOwner := sCount + nCount + persistentReaderUnits(data) + 1
	wantWaiter := nCount + persistentReaderUnits(data) - 1
	if ownerUnits != wantOwner || waiterUnits != wantWaiter {
		t.Fatalf("identity must charge only its owner: %d/%d want %d/%d", ownerUnits, waiterUnits, wantOwner, wantWaiter)
	}
	state, err := s.PersistedIndexState(context.Background())
	if err != nil || !state.StringIdentityCached || state.MappedViewUnavailable {
		t.Fatal(state, err)
	}
}

func TestMainPersistentSnapshotObservesOwnedStateWithoutLRUMutation(t *testing.T) {
	s := openIndexFixture(t, copyIndexFixture(t), "MAPPED")
	ctx := context.Background()
	before, err := s.PersistedIndexState(ctx)
	if err != nil || !before.StringIdentityCached || before.MatchingStringIDsCount != nil || before.PersistenceBudgetDenied != nil {
		t.Fatal(before, err)
	}
	index, ok, err := s.PrepareDistinctStringIndex(ctx, DistinctProjectionOptions{MainSource: true, SourceCount: 1, Limit: 2, RetainPersisted: true})
	if err != nil || !ok {
		t.Fatal(ok, err)
	}
	for _, key := range []string{"first", "second"} {
		if err := index.CacheProjectionIDs(ctx, ProjectionStringMatches, key, []int32{0}, 100); err != nil {
			t.Fatal(err)
		}
	}
	if err := index.CacheProjectionRows(ctx, "row", [][]string{{"owned"}}, 100); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		state, err := s.PersistedIndexState(ctx)
		if err != nil || !state.RetainPreference || !state.LoadedFromPersistence || state.MatchingStringIDsCount == nil || *state.MatchingStringIDsCount != 2 || state.MatchingNodeIDsCount == nil || *state.MatchingNodeIDsCount != 0 || state.ProjectedRowsCount == nil || *state.ProjectedRowsCount != 1 || state.PersistenceBudgetDenied != nil {
			t.Fatal(state, err)
		}
		// Returned counts are owned values, not pointers into mutable caches.
		*state.MatchingStringIDsCount = 99
	}
	s.callSiteIndex.mu.RLock()
	entries := index.ordinary.caches[ProjectionStringMatches].entries
	first, second := entries[0].key, entries[1].key
	s.callSiteIndex.mu.RUnlock()
	if first != "first" || second != "second" {
		t.Fatal("snapshot changed LRU order", first, second)
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err := s.PersistedIndexState(ctx); !errors.Is(err, ErrStoreClosed) {
		t.Fatal("closed snapshot", err)
	}
}
