package query

import (
	"context"
	"encoding/json"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"os"
	"reflect"
	"testing"
)

func TestReviewMainTypeHeadOracle(t *testing.T) {
	var records []struct {
		Name, Query, Error, Message string
		Cross                       bool
		Columns                     []string
		Rows                        []map[string]any
	}
	data, err := os.ReadFile("/tmp/graphite-main-source-independent-review/main.json")
	if err != nil {
		t.Fatal(err)
	}
	if err = json.Unmarshal(data, &records); err != nil {
		t.Fatal(err)
	}
	if len(records) != 8 {
		t.Fatal(len(records))
	}
	for _, want := range records {
		t.Run(want.Name+map[bool]string{false: "-scoped", true: "-cross"}[want.Cross], func(t *testing.T) {
			g, err := store.OpenMode(ordinaryCopyFixture(t, "/tmp/graphite-main-source-independent-review/fixture"), "MAPPED")
			if err != nil {
				t.Fatal(err)
			}
			defer g.Close()
			var r Result
			if want.Cross {
				r, err = ExecuteCross(context.Background(), []Graph{{ID: "a", Store: g}, {ID: "b", Store: g}}, want.Query, nil, -1)
			} else {
				r, err = Execute(context.Background(), g, want.Query, nil, -1)
			}
			if want.Error != "" {
				q, ok := err.(*Error)
				if !ok || q.Class != want.Error || q.Error() != want.Message {
					t.Fatalf("actual=%#v expected=%s %s", err, want.Error, want.Message)
				}
			} else if err != nil || !reflect.DeepEqual(r.Columns, want.Columns) || !reflect.DeepEqual(r.Rows, want.Rows) {
				t.Fatalf("response=%#v error=%v want=%#v", r, err, want)
			}
		})
	}
}
func TestReviewMainSourceWaveContextAndOwnedHead(t *testing.T) {
	g := candidateGraph(t, "clean")
	root := context.Background()
	e := evaluator{ctx: root}
	index, ok, err := g.PrepareDistinctStringIndex(root, store.DistinctProjectionOptions{SourceCount: 1, Limit: 1000, SkipPreparedPreference: true})
	if !ok || err != nil {
		t.Fatal(ok, err)
	}
	plan := &mainStringSourceSpec{atoms: []distinctStringAtom{{property: "caller_name", op: "CONTAINS", term: "other"}}, sourceCount: 1}
	next := e.mainCandidateIterator(Graph{Store: g}, plan, 4)
	wave, cancel := context.WithCancel(root)
	first, ok := next(wave)
	if !ok || first.ID != 2 {
		t.Fatal(first, ok)
	}
	cancel()
	secondWave, secondCancel := context.WithCancel(root)
	defer secondCancel()
	second, ok := next(secondWave)
	if !ok || second.ID != 41 {
		t.Fatal("old wave poisoned next", second, ok)
	}
	if first.Caller.Name != "other" || first.Callee.Name != "other" || second.Callee.Name != "invoke" {
		t.Fatal("first node alias or payload", first, second)
	}
	cached, hit, err := index.ProjectionCachedIDs(root, store.ProjectionNodeMatches, mainNodeKey(plan, 4))
	if err != nil || hit {
		t.Fatal("cache before final EOF", cached, hit, err)
	}
	if _, ok = next(root); ok {
		t.Fatal("extra result")
	}
	cached, hit, err = index.ProjectionCachedIDs(root, store.ProjectionNodeMatches, mainNodeKey(plan, 4))
	if err != nil || !hit || !reflect.DeepEqual(cached, []int32{2, 41}) {
		t.Fatal("cache missing on exhaustion", cached, hit, err)
	}
	if err = g.Close(); err != nil {
		t.Fatal(err)
	}
	if first.Caller.Name != "other" || second.Callee.Name != "invoke" {
		t.Fatal("closed store invalidated node payload")
	}
}
func TestReviewMainDecodeFailureDoesNotPublishNodeCache(t *testing.T) {
	g := candidateGraph(t, "bad-matched")
	ctx := context.Background()
	e := evaluator{ctx: ctx}
	index, ok, err := g.PrepareDistinctStringIndex(ctx, store.DistinctProjectionOptions{SourceCount: 1, Limit: 1000, SkipPreparedPreference: true})
	if !ok || err != nil {
		t.Fatal(ok, err)
	}
	plan := &mainStringSourceSpec{atoms: []distinctStringAtom{{property: "caller_name", op: "CONTAINS", term: "other"}}, sourceCount: 1}
	next := e.mainCandidateIterator(Graph{Store: g}, plan, 4)
	failure := findIDCaught(func() { next(ctx) })
	q, ok := failure.(*Error)
	if !ok || q.Class != "IndexOutOfBoundsException" {
		t.Fatal("expected consumed full-node error", failure)
	}
	if ids, hit, err := index.ProjectionCachedIDs(ctx, store.ProjectionNodeMatches, mainNodeKey(plan, 4)); err != nil || hit {
		t.Fatal("failed consumer published cache", ids, hit, err)
	}
}
