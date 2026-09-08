package store

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"sync"
	"testing"
	"time"
)

func TestMainBuildWorkBothRawPassesMustComplete(t *testing.T) {
	for _, reject := range []int{1, 2, 0} {
		dir := copyIndexFixture(t)
		if err := os.Remove(filepath.Join(dir, callSiteIndexFile)); err != nil {
			t.Fatal(err)
		}
		s := openIndexFixture(t, dir, "MAPPED")
		var calls []int64
		cause := errors.New("raw pass work rejected")
		options := DistinctProjectionOptions{MainSource: true, SourceCount: 1, Limit: 2, SkipPreparedPreference: true, ConsumeWork: func(n int64) error {
			if _, err := s.PersistedIndexState(context.Background()); err != nil {
				return err
			}
			calls = append(calls, n)
			if len(calls) == reject {
				return cause
			}
			return nil
		}}
		index, ok, err := s.PrepareDistinctStringIndex(context.Background(), options)
		if reject > 0 {
			var aborted *WorkAbortedError
			if ok || index != nil || !errors.As(err, &aborted) || aborted.Cause != cause {
				t.Fatal(index, ok, err)
			}
			state, err := s.PersistedIndexState(context.Background())
			if err != nil || state.MatchingNodeIDsCount != nil {
				t.Fatal("partial raw pass published", state, err)
			}
		} else if err != nil || !ok || index.Raw {
			t.Fatal(index, ok, err)
		}
		want := []int64{int64(len(s.byKind["CallSiteNode"]))}
		if reject != 1 {
			want = append(want, want[0])
		}
		if !reflect.DeepEqual(calls, want) {
			t.Fatalf("real raw pass boundaries %v want %v", calls, want)
		}
		if reject > 0 {
			options.ConsumeWork = nil
			if _, ok, err := s.PrepareDistinctStringIndex(context.Background(), options); err != nil || !ok {
				t.Fatal("failed owner poisoned retry", ok, err)
			}
		}
	}
}

func TestMainBuildSecondPassActuallyReadsRawFields(t *testing.T) {
	dir := copyIndexFixture(t)
	if err := os.Remove(filepath.Join(dir, callSiteIndexFile)); err != nil {
		t.Fatal(err)
	}
	s := openIndexFixture(t, dir, "MAPPED")
	ids := s.byKind["CallSiteNode"]
	defer func() { s.byKind["CallSiteNode"] = ids }()
	pass := 0
	// A controlled source mutation at the first pass's completed callback is
	// test instrumentation. The second pass must read it, not replay first-pass
	// decoded fields or merely charge a second total.
	_, ok, err := s.PrepareDistinctStringIndex(context.Background(), DistinctProjectionOptions{MainSource: true, SourceCount: 1, Limit: 2, SkipPreparedPreference: true, ConsumeWork: func(int64) error {
		pass++
		if pass == 1 {
			s.byKind["CallSiteNode"] = []int32{1 << 30}
		}
		return nil
	}})
	var raw *ProjectionReadError
	if ok || !errors.As(err, &raw) || pass != 2 {
		t.Fatalf("second raw read missing or failure not finally charged: ok=%v err=%v passes=%d", ok, err, pass)
	}
}

func TestMainTrigramWorkMetadataAndPostingsRetryStages(t *testing.T) {
	s, index, _ := buildOrdinaryTestIndex(t)
	ctx := context.Background()
	cause := errors.New("trigram work rejected")
	used := int64(len(index.usedProjectionTrigramStrings()))
	for _, step := range []struct {
		reject           int
		metadata, arrays bool
		calls            []int64
	}{
		{1, false, false, []int64{used}},
		{2, true, true, []int64{used, used}},
		{0, true, false, []int64{used}},
	} {
		var calls []int64
		err := index.PrepareProjectionTrigramsWithWork(ctx, func(n int64) error {
			if _, err := s.PersistedIndexState(ctx); err != nil {
				return err
			}
			calls = append(calls, n)
			if len(calls) == step.reject {
				return cause
			}
			return nil
		})
		if (err != nil) != (step.reject != 0) || err != nil && !errors.Is(err, cause) {
			t.Fatal(err)
		}
		if !reflect.DeepEqual(calls, step.calls) {
			t.Fatal("retry recomputed completed stage", calls, step.calls)
		}
		state, err := s.PersistedIndexState(ctx)
		if err != nil || state.TrigramMetadataReady == nil || *state.TrigramMetadataReady != step.metadata || *state.TrigramMetadataArraysPresent != step.arrays || *state.TrigramPostingsReady != (step.reject == 0) {
			t.Fatal(state, err)
		}
	}
	if err := index.PrepareProjectionTrigramsWithWork(ctx, func(int64) error { t.Error("warm trigram owner charged work"); return nil }); err != nil {
		t.Fatal(err)
	}
}

func TestMainTrigramDetachedGenerationCloseJoinsOwner(t *testing.T) {
	s, index, _ := buildOrdinaryTestIndex(t)
	entered, release := make(chan struct{}), make(chan struct{})
	var once sync.Once
	defer once.Do(func() { close(release) })
	result := make(chan error, 1)
	go func() {
		result <- index.PrepareProjectionTrigramsWithWork(context.Background(), func(int64) error { close(entered); <-release; return nil })
	}()
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("metadata owner did not start")
	}
	if err := s.ReleaseDistinctStringIndex(context.Background()); err != nil {
		t.Fatal(err)
	}
	closed := make(chan error, 1)
	go func() { closed <- s.Close() }()
	s.callSiteIndex.mu.RLock()
	closing := s.callSiteIndex.closing
	s.callSiteIndex.mu.RUnlock()
	select {
	case <-closing:
	case <-time.After(5 * time.Second):
		t.Fatal("Close did not signal owner")
	}
	select {
	case <-closed:
		t.Fatal("Close failed to join detached generation")
	default:
	}
	once.Do(func() { close(release) })
	select {
	case err := <-result:
		if !errors.Is(err, ErrStoreClosed) {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("owner ticket leaked")
	}
	select {
	case err := <-closed:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Close did not finish")
	}
}
