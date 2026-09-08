package store

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func TestMainOrdinaryColdIdentityBeforeWorkerPoll(t *testing.T) {
	for _, entry := range []string{"main", "legacy"} {
		t.Run(entry, func(t *testing.T) {
			s := openIndexFixture(t, copyIndexFixture(t), "MAPPED")
			ctx, cancel := context.WithCancel(context.Background())
			cancel()
			calls := []int64{}
			options := DistinctProjectionOptions{MainSource: true, SourceCount: 40, InitializeMappedView: true, ConsumeWork: func(n int64) error { calls = append(calls, n); return nil }}
			prepare := s.PrepareMainOrdinaryStringIndex
			want := []int64{1} // Actual main E01: persisted identity before View.load's first poll.
			if entry == "legacy" {
				prepare = s.PrepareDistinctStringIndex
				want = []int64{}
			}
			index, ok, err := prepare(ctx, options)
			if index != nil || ok || !errors.Is(err, context.Canceled) || !reflect.DeepEqual(calls, want) {
				t.Fatal(index, ok, err, calls, want)
			}
			state, err := s.PersistedIndexState(context.Background())
			if err != nil || state.MappedViewUnavailable {
				t.Fatal("interrupted load published unavailable", state, err)
			}
			views, err := s.StringPropertyIndexes(context.Background())
			if err != nil || views.MappedView || views.Retained || views.MappedRangeCount != 0 {
				t.Fatal("interrupted load published state", views, err)
			}
		})
	}
}

func TestMainOrdinaryLegacyIdentityHasNoWorkerPoll(t *testing.T) {
	dir := copyIndexFixture(t)
	for _, name := range []string{"graph.callsite-string-content.identity", "graph.strings.identity"} {
		if err := os.Remove(filepath.Join(dir, name)); err != nil {
			t.Fatal(err)
		}
	}
	s := openIndexFixture(t, dir, "MAPPED")
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	calls := []int64{}
	index, ok, err := s.PrepareMainOrdinaryStringIndex(ctx, DistinctProjectionOptions{SourceCount: 40, InitializeMappedView: true, ConsumeWork: func(n int64) error { calls = append(calls, n); return nil }})
	// Main StringTable.semanticContentIdentity has work but no worker poll;
	// the following raw-node identity loop first polls at ordinal zero.
	if index != nil || ok || !errors.Is(err, context.Canceled) || !reflect.DeepEqual(calls, []int64{int64(len(s.Strings))}) {
		t.Fatal(index, ok, err, calls)
	}
	state, err := s.PersistedIndexState(context.Background())
	if err != nil || !state.StringIdentityCached || state.MappedViewUnavailable {
		t.Fatal("completed semantic identity must survive later raw interruption", state, err)
	}
}

func TestMainOrdinaryEntryCallbackAndWarmLifetime(t *testing.T) {
	s := openIndexFixture(t, copyIndexFixture(t), "MAPPED")
	cause := errors.New("request rejected identity")
	index, ok, err := s.PrepareMainOrdinaryStringIndex(context.Background(), DistinctProjectionOptions{SourceCount: 40, InitializeMappedView: true, ConsumeWork: func(int64) error {
		// A public lifetime getter inside the callback proves no Store lock is held.
		if _, _, err := s.MainRetainedProjectionIndex(); err != nil {
			return err
		}
		return cause
	}})
	var aborted *WorkAbortedError
	if index != nil || ok || !errors.As(err, &aborted) || aborted.Cause != cause {
		t.Fatal(index, ok, err)
	}
	options := DistinctProjectionOptions{SourceCount: 40, InitializeMappedView: true}
	index, ok, err = s.PrepareMainOrdinaryStringIndex(context.Background(), options)
	if err != nil || !ok || index == nil {
		t.Fatal("retry failed", index, ok, err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	options.ConsumeWork = func(int64) error { t.Fatal("warm metadata unexpectedly charged work"); return nil }
	warm, ok, err := s.PrepareMainOrdinaryStringIndex(ctx, options)
	if err != nil || !ok || warm == nil || !warm.MainMappedCapability() {
		t.Fatal("warm entry polled worker cancellation", warm, ok, err)
	}
	if _, _, err := s.PrepareDistinctStringIndex(ctx, options); !errors.Is(err, context.Canceled) {
		t.Fatal("legacy preparation lost context check", err)
	}
	if _, _, err := s.RetainedProjectionIndex(ctx); !errors.Is(err, context.Canceled) {
		t.Fatal("legacy retained getter lost context check", err)
	}
	if retained, ok, err := s.MainRetainedProjectionIndex(); err != nil || ok || retained != nil {
		t.Fatal("mapped preparation became retained", retained, ok, err)
	}
	if present, err := s.MainPreparedProjectionFile(); err != nil || !present {
		t.Fatal(present, err)
	}
	if err := index.MainCacheProjectionIDs(ProjectionNodeMatches, "entry-control", []int32{17, 2}, 152); err != nil {
		t.Fatal(err)
	}
	ids, hit, err := index.MainProjectionCachedIDs(ProjectionNodeMatches, "entry-control")
	if err != nil || !hit || !reflect.DeepEqual(ids, []int32{17, 2}) {
		t.Fatal(ids, hit, err)
	}
	ids[0] = 999
	if again, _, _ := index.MainProjectionCachedIDs(ProjectionNodeMatches, "entry-control"); !reflect.DeepEqual(again, []int32{17, 2}) {
		t.Fatal("cache getter leaked mutable storage", again)
	}
	if _, _, err := index.ProjectionCachedIDs(ctx, ProjectionNodeMatches, "entry-control"); !errors.Is(err, context.Canceled) {
		t.Fatal("legacy cache getter lost context check", err)
	}
	node, present, err := s.MainProjectionCandidateNode(17)
	if err != nil || !present || node.ID != 17 || node.Kind != "CallSiteNode" {
		t.Fatal(node, present, err)
	}
	if order, err := s.MainProjectionNodeOrder(17); err != nil || order < 0 {
		t.Fatal(order, err)
	}
	if _, _, err := s.ProjectionCandidateNode(ctx, 17); !errors.Is(err, context.Canceled) {
		t.Fatal("legacy decoder lost context check", err)
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	if _, _, err := s.MainRetainedProjectionIndex(); !errors.Is(err, ErrStoreClosed) {
		t.Fatal(err)
	}
	if _, err := s.MainPreparedProjectionFile(); !errors.Is(err, ErrStoreClosed) {
		t.Fatal(err)
	}
	if _, _, err := index.MainProjectionCachedIDs(ProjectionNodeMatches, "entry-control"); !errors.Is(err, ErrStoreClosed) {
		t.Fatal(err)
	}
	if err := index.MainCacheProjectionIDs(ProjectionNodeMatches, "entry-control", nil, 0); !errors.Is(err, ErrStoreClosed) {
		t.Fatal(err)
	}
	if _, _, err := s.MainProjectionCandidateNode(17); !errors.Is(err, ErrStoreClosed) {
		t.Fatal(err)
	}
	if _, err := s.MainProjectionNodeOrder(17); !errors.Is(err, ErrStoreClosed) {
		t.Fatal(err)
	}
}

func TestMainOrdinaryWarmMappedFileAdmission(t *testing.T) {
	dir := copyIndexFixture(t)
	path := filepath.Join(dir, callSiteIndexFile)
	original, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	s := openIndexFixture(t, dir, "MAPPED")
	options := DistinctProjectionOptions{SourceCount: 40, InitializeMappedView: true}
	index, ok, err := s.PrepareMainOrdinaryStringIndex(context.Background(), options)
	if index == nil || !ok || err != nil {
		t.Fatal(index, ok, err)
	}
	options.ConsumeWork = func(int64) error { t.Fatal("cached view admission reloaded storage"); return nil }
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	for _, directory := range []bool{false, true} {
		if directory {
			if err := os.Mkdir(path, 0700); err != nil {
				t.Fatal(err)
			}
		}
		got, available, err := s.PrepareMainOrdinaryStringIndex(context.Background(), options)
		if got != nil || available || err != nil {
			t.Fatal("nonregular path bypassed graph-level admission", directory, got, available, err)
		}
		// The lower loader owner independently preserves the same admission
		// before its cached state.view fast path.
		ctx := context.WithValue(context.Background(), mainOrdinaryEntryKey{}, true)
		view, available, err := s.tryMainCallSiteStringIndexWithWork(ctx, true, options.ConsumeWork)
		if view != nil || available || err != nil {
			t.Fatal("nonregular path bypassed loader-owner admission", directory, view, available, err)
		}
		state, err := s.StringPropertyIndexes(context.Background())
		if err != nil || !state.MappedView || state.Retained {
			t.Fatal("file admission cleared/published cache", state, err)
		}
		persistent, err := s.PersistedIndexState(context.Background())
		if err != nil || persistent.MappedViewUnavailable {
			t.Fatal("file admission published unavailable", persistent, err)
		}
	}
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, original, 0600); err != nil {
		t.Fatal(err)
	}
	got, available, err := s.PrepareMainOrdinaryStringIndex(context.Background(), options)
	if got == nil || !available || err != nil || got.view != index.view {
		t.Fatal("restored regular path did not reuse original view", got, available, err)
	}
}
