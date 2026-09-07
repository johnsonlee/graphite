package query

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func exactTupleGraph(t *testing.T, name string) *store.Store {
	t.Helper()
	dir := t.TempDir()
	source := filepath.Join("testdata/exact-tuple/fixtures", name)
	files, err := os.ReadDir(source)
	if err != nil {
		t.Fatal(err)
	}
	for _, file := range files {
		if file.IsDir() {
			continue
		}
		data, err := os.ReadFile(filepath.Join(source, file.Name()))
		if err != nil {
			t.Fatal(err)
		}
		if err = os.WriteFile(filepath.Join(dir, file.Name()), data, 0600); err != nil {
			t.Fatal(err)
		}
	}
	g, err := store.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { g.Close() })
	return g
}
func TestExactTupleMainCrossQuery(t *testing.T) { runExactTupleQueries(t, "query-main-nine.json") }
func TestExactTupleProjectionMain(t *testing.T) { runExactTupleQueries(t, "projection-main.json") }
func runExactTupleQueries(t *testing.T, file string) {
	var cases []struct {
		Query, Second, ErrorClass, Error string
		Parameters                       map[string]any
		Columns                          []string
		Rows                             []map[string]any
		B                                struct{ Bytes int64 }
	}
	readDistinctJSON(t, "testdata/exact-tuple/"+file, &cases)
	for at, want := range cases {
		t.Run(fmt.Sprint(at), func(t *testing.T) {
			ctx := context.Background()
			a, b := exactTupleGraph(t, "n4096"), exactTupleGraph(t, want.Second)
			for _, g := range []*store.Store{a, b} {
				if _, err := Execute(ctx, g, "MATCH (n) WHERE n.caller_name CONTAINS 'other' RETURN DISTINCT n.caller_name AS x LIMIT 1", nil, -1); err != nil {
					t.Fatal(err)
				}
			}
			sources := []Graph{}
			for n := 0; n < 8; n++ {
				id := "a"
				if n > 0 {
					id = fmt.Sprintf("a%d", n+1)
				}
				sources = append(sources, Graph{ID: id, Store: a})
			}
			sources = append(sources, Graph{ID: "b", Store: b})
			result, err := ExecuteCross(ctx, sources, want.Query, want.Parameters, -1)
			if want.ErrorClass != "" {
				var q *Error
				if !errors.As(err, &q) || q.Class != want.ErrorClass || q.Error() != want.Error {
					t.Fatalf("error=%#v want=%s %s", err, want.ErrorClass, want.Error)
				}
			} else if err != nil || !reflect.DeepEqual(result.Columns, want.Columns) || !tupleJSONEqual(result.Rows, want.Rows) {
				t.Fatalf("full response differs: columns=%v rows=%d err=%v", result.Columns, len(result.Rows), err)
			}
			index, ok, err := b.RetainedProjectionIndex(ctx)
			if err != nil || !ok {
				t.Fatal(ok, err)
			}
			bytes, err := index.ProjectionPlannerBytes(ctx)
			if err != nil || bytes != want.B.Bytes {
				t.Fatalf("retained=%d want=%d err=%v", bytes, want.B.Bytes, err)
			}
		})
	}
}

func tupleJSONEqual(a, b any) bool {
	left, e1 := json.Marshal(a)
	right, e2 := json.Marshal(b)
	return e1 == nil && e2 == nil && bytes.Equal(left, right)
}

func TestExactTupleOrdinaryCacheThresholdMain(t *testing.T) {
	var want struct {
		Query                                    string
		Columns                                  []string
		Rows                                     []map[string]any
		Before, After, Cleared                   struct{ Bytes int64 }
		SerialBefore, SerialAfter, SerialCleared bool
	}
	readDistinctJSON(t, "testdata/exact-tuple/cache-main.json", &want)
	ctx := context.Background()
	g := exactTupleGraph(t, "n4096")
	if _, err := Execute(ctx, g, "MATCH (n) WHERE n.caller_name CONTAINS 'other' RETURN DISTINCT n.caller_name AS x LIMIT 1", nil, -1); err != nil {
		t.Fatal(err)
	}
	index, ok, err := g.RetainedProjectionIndex(ctx)
	if !ok || err != nil {
		t.Fatal(ok, err)
	}
	if err = index.ClearProjectionQueryCaches(ctx); err != nil {
		t.Fatal(err)
	}
	result, err := Execute(ctx, g, want.Query, nil, -1)
	if err != nil || !reflect.DeepEqual(result.Columns, want.Columns) || !tupleJSONEqual(result.Rows, want.Rows) {
		t.Fatalf("ordinary full response differs err=%v", err)
	}
	assertState := func(bytes int64, serial bool) {
		t.Helper()
		got, err := index.ProjectionPlannerBytes(ctx)
		if err != nil || got != bytes {
			t.Fatalf("retained=%d want=%d err=%v", got, bytes, err)
		}
		hint, err := index.PrefersSerialProjectionScan(ctx)
		if err != nil || hint != serial {
			t.Fatalf("serial=%v want=%v err=%v", hint, serial, err)
		}
	}
	assertState(want.Before.Bytes, want.SerialBefore)
	if ok, err := index.PrepareExactProjectionTuples(ctx, 256); !ok || err != nil {
		t.Fatal(ok, err)
	}
	assertState(want.After.Bytes, want.SerialAfter)
	if err = index.ClearProjectionQueryCaches(ctx); err != nil {
		t.Fatal(err)
	}
	assertState(want.Cleared.Bytes, want.SerialCleared)
}
