package query

import (
	"context"
	"encoding/json"
	"errors"
	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"os"
	"reflect"
	"strings"
	"sync/atomic"
	"testing"
)

func TestReviewLazyOldCorpusMigrationHasExactOracle(t *testing.T) {
	var cases []map[string]any
	b, err := os.ReadFile("testdata/candidate-index/corrupt-native.json")
	if err != nil {
		t.Fatal(err)
	}
	if err = json.Unmarshal(b, &cases); err != nil {
		t.Fatal(err)
	}
	oldMain := []map[string]any{}
	for _, fixture := range []string{"clean", "bad-first-unmatched", "bad-last-unmatched", "bad-matched"} {
		var rows []map[string]any
		b, err := os.ReadFile("testdata/candidate-index/" + fixture + "-main.json")
		if err != nil {
			t.Fatal(err)
		}
		if err = json.Unmarshal(b, &rows); err != nil {
			t.Fatal(err)
		}
		for _, row := range rows {
			row["fixture"] = fixture
			oldMain = append(oldMain, row)
		}
	}
	changed, admitted := 0, 0
	for _, c := range cases {
		ast, err := cypher.Parse(c["query"].(string))
		if err != nil {
			t.Fatal(err)
		}
		e := evaluator{ctx: context.Background(), cross: c["cross"].(bool)}
		if e.compileIndexedDistinct(ast.Branches[0]) != nil || e.compileOrdinaryProjection(ast.Branches[0]) != nil || e.compileLazyFiltered(ast.Branches[0]) == nil {
			continue
		}
		admitted++
		// Apply the typed SID mapping already present in the frozen provider test.
		if c["error"] == "CypherException" {
			if message, ok := c["message"].(string); ok && strings.Contains(message, "string index 2147483647 outside table of 9") {
				c["error"] = "IndexOutOfBoundsException"
				c["message"] = "Index (2147483647) is greater than or equal to list size (9)"
			}
		}
		found := 0
		for _, m := range oldMain {
			if c["fixture"] == m["fixture"] && c["name"] == m["name"] && c["cross"] == m["cross"] {
				found++
				if c["query"] != m["query"] {
					t.Fatal("query identity changed", c, m)
				}
				same := true
				for _, k := range []string{"columns", "rows", "error", "message"} {
					same = same && reflect.DeepEqual(c[k], m[k])
				}
				if !same {
					changed++
				}
			}
		}
		if found != 1 {
			t.Fatal("main lookup is not exact/unique", found, c)
		}
	}
	t.Logf("full original=%d newly admitted=%d response differences=%d", len(cases), admitted, changed)
	if len(cases) != 80 || changed != 8 {
		t.Fatal("unexpected denominator/migration", len(cases), admitted, changed)
	}
}

type reviewLazyCancel struct {
	context.Context
	cancel context.CancelFunc
	checks atomic.Int64
	at     int64
}

func (c *reviewLazyCancel) Err() error {
	if c.checks.Add(1) == c.at {
		c.cancel()
	}
	return c.Context.Err()
}
func TestReviewLazyParallelCancellationJoinsAndFreshRequest(t *testing.T) {
	for _, query := range []string{
		"MATCH (n) WHERE n.caller_name CONTAINS 'other' AND true RETURN n.id AS x LIMIT 1",
		"MATCH (n) WHERE n.caller_name CONTAINS 'other' AND true RETURN DISTINCT n.id AS x LIMIT 1",
	} {
		for _, at := range []int64{30, 80, 150} {
			dir := ordinaryCopyFixture(t, "testdata/candidate-index/clean")
			g, err := store.OpenMode(dir, "MAPPED")
			if err != nil {
				t.Fatal(err)
			}
			sources := []Graph{}
			for _, id := range []string{"a", "b", "c", "d", "e", "f", "g", "h"} {
				sources = append(sources, Graph{ID: id, Store: g})
			}
			base, cancel := context.WithCancel(context.Background())
			ctx := &reviewLazyCancel{Context: base, cancel: cancel, at: at}
			_, err = ExecuteCross(ctx, sources, query, nil, -1)
			if at == 30 && base.Err() == nil {
				t.Fatal("required cancellation checkpoint was not reached", ctx.checks.Load())
			}
			if base.Err() != nil && !errors.Is(err, context.Canceled) {
				t.Fatalf("at=%d canceled parent suppressed: %v", at, err)
			}
			cancel()
			fresh, err := ExecuteCross(context.Background(), sources, query, nil, -1)
			if err != nil || len(fresh.Rows) != 1 || fresh.Rows[0]["x"] != int32(2) {
				t.Fatal("old task cancellation poisoned fresh request", fresh, err)
			}
			if err = g.Close(); err != nil {
				t.Fatal(err)
			}
			if fresh.Rows[0]["x"] != int32(2) {
				t.Fatal("retained result changed after Close")
			}
		}
	}
}

func TestReviewLazyScopedGenericRouteStillConsumes(t *testing.T) {
	observations := []map[string]any{}
	t.Cleanup(func() { writeDistinctEvidence(t, "review-route-native.json", observations) })
	var specs []map[string]any
	readDistinctJSON(t, "/tmp/graphite-lazy-cde-independent-review/route-oracle/main-wire.json", &specs)
	if len(specs) != 8 {
		t.Fatal(len(specs))
	}
	for _, spec := range specs {
		t.Run(spec["name"].(string)+map[bool]string{true: "-cross", false: "-scoped"}[spec["cross"].(bool)], func(t *testing.T) {
			g, err := store.OpenMode(ordinaryCopyFixture(t, "testdata/lazy-filtered/fixtures/clean"), "MAPPED")
			if err != nil {
				t.Fatal(err)
			}
			defer g.Close()
			var r Result
			if spec["cross"].(bool) {
				r, err = ExecuteCross(context.Background(), []Graph{{ID: "a", Store: g}, {ID: "b", Store: g}}, spec["query"].(string), nil, -1)
			} else {
				r, err = Execute(context.Background(), g, spec["query"].(string), nil, -1)
			}
			got := distinctOracleResult(map[string]any{"query": spec["query"]}, r, err)
			want := ordinaryObservedResponse(spec)
			observations = append(observations, map[string]any{"name": spec["name"], "cross": spec["cross"], "actual": got, "expected": want})
			if !reflect.DeepEqual(got, want) {
				t.Fatalf("main=%s native=%s", mustJSON(want), mustJSON(got))
			}
		})
	}
}
