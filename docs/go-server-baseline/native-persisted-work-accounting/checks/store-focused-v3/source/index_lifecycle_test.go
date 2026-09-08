package store

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"sync"
	"testing"
)

func TestClearStringIndexesKeepsGraphAndRebuildsCaches(t *testing.T) {
	s := openIndexFixture(t, copyIndexFixture(t), "MAPPED")
	ctx := context.Background()
	node, err := s.Node(17)
	if err != nil {
		t.Fatal(err)
	}
	view := requireIndex(t, s)
	if valid, err := s.CertifyCallSiteTrigrams(ctx, view); err != nil || !valid {
		t.Fatalf("initial certificates %v %v", valid, err)
	}
	mapped, ok, err := s.PrepareDistinctStringIndex(ctx, DistinctProjectionOptions{MainSource: true, SourceCount: 40, Limit: 1, InitializeMappedView: true})
	if err != nil || !ok {
		t.Fatalf("mapped view %v %v", ok, err)
	}
	directory, err := mapped.Directory(ctx, CallerName)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, valid, err := mapped.MainMappedProjectionRange(ctx, CallerName, directory[0].StringID); err != nil || !valid {
		t.Fatalf("mapped validation %v %v", valid, err)
	}
	prepared, err := s.PrepareCallSiteStringIndex(ctx)
	if err != nil || !prepared {
		t.Fatalf("prepare %v %v", prepared, err)
	}
	index, ok, err := s.RetainedProjectionIndex(ctx)
	if err != nil || !ok {
		t.Fatal(err)
	}
	for _, kind := range []ProjectionCacheKind{ProjectionStringMatches, ProjectionNodeMatches} {
		if err := index.CacheProjectionIDs(ctx, kind, "cached", []int32{17}, 100); err != nil {
			t.Fatal(err)
		}
	}
	if err := index.CacheProjectionRows(ctx, "cached", [][]string{{"owned"}}, 100); err != nil {
		t.Fatal(err)
	}
	before, err := s.StringPropertyIndexes(ctx)
	if err != nil || !before.Retained || !before.MappedView || !before.Trigrams || before.MappedRangeCount != 1 {
		t.Fatalf("prepared state %+v %v", before, err)
	}
	for repeat := 0; repeat < 2; repeat++ {
		if err := s.ClearStringPropertyIndexes(ctx); err != nil {
			t.Fatal(err)
		}
		state, err := s.StringPropertyIndexes(ctx)
		if err != nil || state != (StringPropertyIndexState{}) {
			t.Fatalf("clear state %+v %v", state, err)
		}
		actual, err := s.Node(17)
		if err != nil || !reflect.DeepEqual(actual, node) {
			t.Fatalf("clear damaged graph: %v %v", actual, err)
		}
	}
	if s.candidateProof.completed || s.trigramProof.completed {
		t.Fatal("cold clear retained candidate certificates")
	}
	if prepared, err := s.PrepareCallSiteStringIndex(ctx); err != nil || !prepared {
		t.Fatalf("reprepare %v %v", prepared, err)
	}
	index, _, err = s.RetainedProjectionIndex(ctx)
	if err != nil {
		t.Fatal(err)
	}
	for _, kind := range []ProjectionCacheKind{ProjectionStringMatches, ProjectionNodeMatches} {
		if _, hit, err := index.ProjectionCachedIDs(ctx, kind, "cached"); err != nil || hit {
			t.Fatalf("stale ID cache %v %v", hit, err)
		}
	}
	if _, hit, err := index.ProjectionCachedRows(ctx, "cached"); err != nil || hit {
		t.Fatalf("stale row cache %v %v", hit, err)
	}
	if valid, err := s.CertifyCallSiteTrigrams(ctx, requireIndex(t, s)); err != nil || !valid {
		t.Fatalf("fresh certificate %v %v", valid, err)
	}
}

func TestIndexLifecycleCancellationAndClosedStore(t *testing.T) {
	s, index, dir := buildOrdinaryTestIndex(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	before, err := s.StringPropertyIndexes(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if err := s.ClearStringPropertyIndexes(ctx); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	if _, err := s.PrepareCallSiteStringIndex(ctx); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	if _, err := OpenModeWithOptions(ctx, "missing", "MAPPED", OpenOptions{PrepareCallSiteStringIndex: true}); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	after, err := s.StringPropertyIndexes(context.Background())
	if err != nil || before != after {
		t.Fatalf("canceled lifecycle mutated state: %+v %+v %v", before, after, err)
	}
	if _, err := os.Stat(filepath.Join(dir, callSiteIndexFile)); !os.IsNotExist(err) {
		t.Fatalf("canceled preparation wrote sidecar: %v", err)
	}
	if err := index.PrepareProjectionTrigrams(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := s.ClearStringPropertyIndexes(context.Background()); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(dir, callSiteIndexFile)); err != nil {
		t.Fatal("clear did not persist completed preparation", err)
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	if err := s.ClearStringPropertyIndexes(context.Background()); !errors.Is(err, ErrStoreClosed) {
		t.Fatal(err)
	}
	if _, err := s.PrepareCallSiteStringIndex(context.Background()); !errors.Is(err, ErrStoreClosed) {
		t.Fatal(err)
	}
}

type lifecyclePoll struct {
	context.Context
	once    sync.Once
	entered chan struct{}
}

func (c *lifecyclePoll) Err() error { c.once.Do(func() { close(c.entered) }); return c.Context.Err() }

func TestClearStringIndexesWaitsForFirstLoader(t *testing.T) {
	for _, cancelWait := range []bool{false, true} {
		t.Run(map[bool]string{false: "join", true: "cancel-wait"}[cancelWait], func(t *testing.T) {
			s := openIndexFixture(t, copyIndexFixture(t), "MAPPED")
			loadContext := &pausePoll{Context: context.Background(), entered: make(chan struct{}), release: make(chan struct{})}
			loaded := make(chan error, 1)
			go func() { _, _, err := s.TryCallSiteStringIndex(loadContext); loaded <- err }()
			<-loadContext.entered
			base, cancel := context.WithCancel(context.Background())
			defer cancel()
			clearContext := &lifecyclePoll{Context: base, entered: make(chan struct{})}
			cleared := make(chan error, 1)
			go func() { cleared <- s.ClearStringPropertyIndexes(clearContext) }()
			<-clearContext.entered
			if cancelWait {
				cancel()
				if err := <-cleared; !errors.Is(err, context.Canceled) {
					t.Fatalf("waiting clear %v", err)
				}
			} else {
				select {
				case err := <-cleared:
					t.Fatalf("clear returned while loader blocked: %v", err)
				default:
				}
			}
			close(loadContext.release)
			if err := <-loaded; err != nil {
				t.Fatal(err)
			}
			if !cancelWait {
				if err := <-cleared; err != nil {
					t.Fatal(err)
				}
				if s.callSiteIndex.view != nil {
					t.Fatal("loader published after clear")
				}
			}
			requireIndex(t, s)
		})
	}
}
