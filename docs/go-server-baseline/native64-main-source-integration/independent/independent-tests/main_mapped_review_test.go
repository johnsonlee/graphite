package query

import (
	"context"
	"errors"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"testing"
)

// This is a lower-level iterator lifetime check, not a claim that a full query
// can read node payloads after Store.Close. Cold IDs have owned pre-read orders;
// a hot iterator must consult the Store only when it advances to a new order.
func TestReviewMainMappedColdHotAdvanceBoundary(t *testing.T) {
	for _, tc := range []struct {
		name  string
		warm  bool
		limit int
	}{{"cold", false, 2}, {"hot", true, 2}, {"hot-take-one", true, 1}} {
		t.Run(tc.name, func(t *testing.T) {
			ctx := context.Background()
			g := candidateGraph(t, "clean")
			index, ok, err := g.PrepareDistinctStringIndex(ctx, store.DistinctProjectionOptions{MainSource: true, SourceCount: 40, InitializeMappedView: true})
			if err != nil || !ok {
				t.Fatal(ok, err)
			}
			if tc.warm {
				_, _, valid, err := index.MainMappedProjectionRange(ctx, store.CallerName, 7)
				if err != nil || !valid {
					t.Fatal(valid, err)
				}
			}
			plan := &mainStringSourceSpec{atoms: []distinctStringAtom{{property: "caller_name", op: "CONTAINS", term: "other"}}, sourceCount: 40, lazyMain: true}
			next, valid := (evaluator{ctx: ctx}).mainSelectedMappedIDs(Graph{Store: g}, index, plan, []map[int32]bool{{7: true}}, tc.limit)
			if !valid {
				t.Fatal("unavailable")
			}
			if id, ok := next(ctx); !ok || id != 2 {
				t.Fatal(id, ok)
			}
			if err := g.Close(); err != nil {
				t.Fatal(err)
			}
			if tc.limit == 1 {
				if id, ok := next(ctx); ok {
					t.Fatal("take advanced", id)
				}
				return
			}
			if !tc.warm {
				if id, ok := next(ctx); !ok || id != 41 {
					t.Fatal("cold owned order", id, ok)
				}
				return
			}
			caught := findIDCaught(func() { next(ctx) })
			if err, ok := caught.(error); !ok || !errors.Is(err, store.ErrStoreClosed) {
				t.Fatal("hot did not read next order", caught)
			}
		})
	}
}
func TestReviewMainMappedCompletedWaveDoesNotPoisonNext(t *testing.T) {
	ctx := context.Background()
	g := candidateGraph(t, "clean")
	index, ok, err := g.PrepareDistinctStringIndex(ctx, store.DistinctProjectionOptions{MainSource: true, SourceCount: 40, InitializeMappedView: true})
	if err != nil || !ok {
		t.Fatal(ok, err)
	}
	_, _, valid, err := index.MainMappedProjectionRange(ctx, store.CallerName, 7)
	if err != nil || !valid {
		t.Fatal(valid, err)
	}
	plan := &mainStringSourceSpec{atoms: []distinctStringAtom{{property: "caller_name", op: "CONTAINS", term: "other"}}, sourceCount: 40, lazyMain: true}
	wave, cancel := context.WithCancel(ctx)
	next, valid := (evaluator{ctx: wave}).mainSelectedMappedIDs(Graph{Store: g}, index, plan, []map[int32]bool{{7: true}}, 2)
	if !valid {
		t.Fatal("unavailable")
	}
	if id, ok := next(wave); !ok || id != 2 {
		t.Fatal(id, ok)
	}
	cancel()
	if id, ok := next(ctx); !ok || id != 41 {
		t.Fatal("old context poisoned next wave", id, ok)
	}
}
