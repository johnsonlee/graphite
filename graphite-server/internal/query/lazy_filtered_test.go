package query

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func TestLazyFilteredOriginalCDE22(t *testing.T) {
	var ledger []struct {
		ID           int
		Group        string
		Main         map[string]any
		MainResponse map[string]any
	}
	readDistinctJSON(t, "testdata/lazy-filtered/original-remaining82.json", &ledger)
	count := 0
	for _, entry := range ledger {
		if !strings.HasPrefix(entry.Group, "C-") && !strings.HasPrefix(entry.Group, "D-") && !strings.HasPrefix(entry.Group, "E-") {
			continue
		}
		count++
		t.Run(fmt.Sprint(entry.ID), func(t *testing.T) {
			dir := ordinaryCopyFixture(t, "testdata/candidate-index/"+entry.Main["fixture"].(string))
			g, err := store.OpenMode(dir, "MAPPED")
			if err != nil {
				t.Fatal(err)
			}
			defer g.Close()
			var result Result
			if entry.Main["cross"].(bool) {
				result, err = ExecuteCross(context.Background(), []Graph{{"a", g}, {"b", g}}, entry.Main["query"].(string), nil, -1)
			} else {
				result, err = Execute(context.Background(), g, entry.Main["query"].(string), nil, -1)
			}
			observed := distinctOracleResult(nil, result, err)
			if !reflect.DeepEqual(observed, entry.MainResponse) {
				t.Errorf("main=%s native=%s", mustJSON(entry.MainResponse), mustJSON(observed))
			}
		})
	}
	if count != 22 {
		t.Fatalf("expected complete C8/D8/E6, got %d", count)
	}
}

func TestLazyFilteredMain480(t *testing.T) {
	count := 0
	output := []map[string]any{}
	for _, pair := range [][2]string{{"clean", "MAPPED"}, {"bad-first-unmatched", "MAPPED"}, {"bad-last-unmatched", "MAPPED"}, {"bad-matched", "MAPPED"}, {"clean", "EAGER"}} {
		var oracle []map[string]any
		readDistinctJSON(t, "testdata/lazy-filtered/"+pair[0]+"-"+pair[1]+"-wire.json", &oracle)
		for _, spec := range oracle {
			count++
			t.Run(pair[0]+"/"+pair[1]+"/"+spec["name"].(string)+"/"+fmt.Sprint(spec["cross"]), func(t *testing.T) {
				dir := ordinaryCopyFixture(t, "testdata/lazy-filtered/fixtures/"+pair[0])
				g, err := store.OpenMode(dir, pair[1])
				if err != nil {
					t.Fatal(err)
				}
				defer g.Close()
				query := spec["query"].(string)
				params := spec["params"].(map[string]any)
				var result Result
				if spec["cross"].(bool) {
					result, err = ExecuteCross(context.Background(), []Graph{{"a", g}, {"b", g}}, query, params, -1)
				} else {
					result, err = Execute(context.Background(), g, query, params, -1)
				}
				actual := distinctOracleResult(map[string]any{"query": query}, result, err)
				expected := ordinaryObservedResponse(spec)
				output = append(output, map[string]any{"fixture": pair[0], "mode": pair[1], "name": spec["name"], "cross": spec["cross"], "actual": actual, "expected": expected})
				if !reflect.DeepEqual(actual, expected) {
					t.Errorf("main=%s native=%s", mustJSON(expected), mustJSON(actual))
				}
			})
		}
	}
	writeDistinctEvidence(t, "lazy-filtered-480.json", output)
	if count != 480 {
		t.Fatalf("full denominator 480, got %d", count)
	}
}

func TestLazyFilteredConjunctionEdges96(t *testing.T) {
	count := 0
	for _, fixture := range []string{"clean", "bad-first-unmatched", "bad-last-unmatched", "bad-matched"} {
		var oracle []map[string]any
		readDistinctJSON(t, "testdata/lazy-filtered/"+fixture+"-edge-wire.json", &oracle)
		for _, spec := range oracle {
			count++
			t.Run(fixture+"/"+spec["name"].(string)+"/"+fmt.Sprint(spec["cross"]), func(t *testing.T) {
				g, err := store.OpenMode(ordinaryCopyFixture(t, "testdata/lazy-filtered/fixtures/"+fixture), "MAPPED")
				if err != nil {
					t.Fatal(err)
				}
				defer g.Close()
				q := spec["query"].(string)
				var result Result
				if spec["cross"].(bool) {
					result, err = ExecuteCross(context.Background(), []Graph{{"a", g}, {"b", g}}, q, nil, -1)
				} else {
					result, err = Execute(context.Background(), g, q, nil, -1)
				}
				actual := distinctOracleResult(map[string]any{"query": q}, result, err)
				expected := ordinaryObservedResponse(spec)
				if !reflect.DeepEqual(actual, expected) {
					t.Errorf("main=%s native=%s", mustJSON(expected), mustJSON(actual))
				}
			})
		}
	}
	if count != 96 {
		t.Fatalf("full edge denominator 96, got %d", count)
	}
}

func TestLazyFilteredSourceWaves36(t *testing.T) {
	var oracle []map[string]any
	readDistinctJSON(t, "testdata/lazy-filtered/sources-main.json", &oracle)
	for i, spec := range oracle {
		t.Run(fmt.Sprint(i), func(t *testing.T) {
			count := int(spec["count"].(float64))
			sources := []Graph{}
			for j := 0; j < count; j++ {
				fixture := "clean"
				if j == 1 {
					fixture = "bad-matched"
				}
				dir := ordinaryCopyFixture(t, "testdata/lazy-filtered/fixtures/"+fixture)
				if spec["missingIndex"].(bool) {
					if err := os.Remove(filepath.Join(dir, "graph.callsite-string-index")); err != nil {
						t.Fatal(err)
					}
				}
				g, err := store.OpenMode(dir, "MAPPED")
				if err != nil {
					t.Fatal(err)
				}
				defer g.Close()
				sources = append(sources, Graph{fmt.Sprintf("g%d", j), g})
			}
			q := spec["query"].(string)
			result, err := ExecuteCross(context.Background(), sources, q, nil, -1)
			actual := distinctOracleResult(map[string]any{"query": q}, result, err)
			expected := ordinaryObservedResponse(spec)
			if !reflect.DeepEqual(actual, expected) {
				t.Errorf("main=%s native=%s", mustJSON(expected), mustJSON(actual))
			}
		})
	}
	if len(oracle) != 36 {
		t.Fatalf("full source denominator 36, got %d", len(oracle))
	}
}

// Independent review found that the earlier bounded fast path precedes root
// graph-scope rewriting. In its nonqualified generic fallback nodeCandidates
// ignores candidateSources, including a nonempty route that excludes "single".
func TestLazyFilteredIndependentScopedRouting8(t *testing.T) {
	var oracle []map[string]any
	readDistinctJSON(t, "testdata/lazy-filtered/independent-route-wire.json", &oracle)
	for _, spec := range oracle {
		t.Run(spec["name"].(string)+"/"+fmt.Sprint(spec["cross"]), func(t *testing.T) {
			g, err := store.OpenMode(ordinaryCopyFixture(t, "testdata/lazy-filtered/fixtures/clean"), "MAPPED")
			if err != nil {
				t.Fatal(err)
			}
			defer g.Close()
			q := spec["query"].(string)
			var result Result
			if spec["cross"].(bool) {
				result, err = ExecuteCross(context.Background(), []Graph{{"a", g}, {"b", g}}, q, nil, -1)
			} else {
				result, err = Execute(context.Background(), g, q, nil, -1)
			}
			actual := distinctOracleResult(map[string]any{"query": q}, result, err)
			expected := ordinaryObservedResponse(spec)
			if !reflect.DeepEqual(actual, expected) {
				t.Errorf("main=%s native=%s", mustJSON(expected), mustJSON(actual))
			}
		})
	}
	if len(oracle) != 8 {
		t.Fatalf("full independent route denominator 8, got %d", len(oracle))
	}
}
