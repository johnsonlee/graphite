package query

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func TestOrdinaryMappedRangeMain(t *testing.T) {
	var expected []map[string]any
	readDistinctJSON(t, "testdata/ordinary-mapped-ranges/main.json", &expected)
	if len(expected) != 6 {
		t.Fatal("incomplete actual-main mapped range matrix")
	}
	outputs := []map[string]any{}
	for _, want := range expected {
		name := want["fixture"].(string)
		t.Run(name, func(t *testing.T) {
			var sources []Graph
			var firstDir string
			for i := 0; i < 64; i++ {
				fixture := "clean"
				if i == 0 {
					fixture = name
				}
				dir := mainSourceFixture(t, fixture)
				g, err := store.OpenMode(dir, "MAPPED")
				if err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { g.Close() })
				sources = append(sources, Graph{ID: fmt.Sprintf("g%02d", i), Store: g})
				if i == 0 {
					firstDir = dir
				}
			}
			state := func() map[string]any { return lifecycleState(t, sources[0].Store, firstDir) }
			actual := map[string]any{"fixture": name, "loaded": state()}
			steps := []map[string]any{}
			for _, raw := range want["steps"].([]any) {
				q := raw.(map[string]any)["query"].(string)
				step := map[string]any{"query": q, "before": state()}
				if q == "clear" {
					for _, source := range sources {
						if err := source.Store.ClearStringPropertyIndexes(context.Background()); err != nil {
							t.Fatal(err)
						}
					}
				} else {
					result, err := ExecuteCrossWithOptions(context.Background(), sources, q, nil, -1, ExecutionOptions{WorkTrackingEnabled: true})
					step = distinctOracleResult(step, result, err)
				}
				step["after"] = state()
				steps = append(steps, step)
			}
			actual["steps"] = steps
			raw, err := json.Marshal(actual)
			if err != nil {
				t.Fatal(err)
			}
			var normalized map[string]any
			if err := json.Unmarshal(raw, &normalized); err != nil {
				t.Fatal(err)
			}
			outputs = append(outputs, normalized)
			if !reflect.DeepEqual(normalized, lifecycleComparableMain(t, want)) {
				t.Error("main public response or first-source structural state differs; full capture in ORDINARY_MAPPED_RANGE_OUTPUT")
			}
		})
	}
	if path := os.Getenv("ORDINARY_MAPPED_RANGE_OUTPUT"); path != "" {
		raw, err := json.MarshalIndent(outputs, "", "  ")
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, append(raw, '\n'), 0600); err != nil {
			t.Fatal(err)
		}
	}
}

func TestMappedEmptyCallSitesPreserveAnnotationProvenance(t *testing.T) {
	var expected []map[string]any
	readDistinctJSON(t, "testdata/ordinary-mapped-ranges/annotation-main.json", &expected)
	if len(expected) != 3 {
		t.Fatal("incomplete annotation history")
	}
	var sources []Graph
	var dirs []string
	for i := 0; i < 64; i++ {
		fixture := "clean"
		if i == 0 || i == 63 {
			fixture = "annotation"
		}
		dir := mainSourceFixture(t, fixture)
		g, err := store.OpenMode(dir, "MAPPED")
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { g.Close() })
		sources = append(sources, Graph{ID: fmt.Sprintf("g%02d", i), Store: g})
		dirs = append(dirs, dir)
	}
	states := func() []map[string]any {
		return []map[string]any{lifecycleState(t, sources[0].Store, dirs[0]), lifecycleState(t, sources[63].Store, dirs[63])}
	}
	var outputs []map[string]any
	for i, want := range expected {
		q := want["query"].(string)
		step := map[string]any{"query": q, "before": states()}
		result, err := ExecuteCrossWithOptions(context.Background(), sources, q, nil, -1, ExecutionOptions{WorkTrackingEnabled: true})
		step = distinctOracleResult(step, result, err)
		step["after"] = states()
		raw, err := json.Marshal(step)
		if err != nil {
			t.Fatal(err)
		}
		var normalized map[string]any
		if err := json.Unmarshal(raw, &normalized); err != nil {
			t.Fatal(err)
		}
		outputs = append(outputs, normalized)
		if !reflect.DeepEqual(normalized, want) {
			t.Errorf("step %d response or endpoint state differs from main", i)
		}
		if i > 0 {
			if len(result.Rows) != 1 {
				t.Fatalf("expected one annotation row, got %d", len(result.Rows))
			}
			row := normalized["rows"].([]any)[0].(map[string]any)
			if !reflect.DeepEqual(row["$metadata"], map[string]any{"graphIds": []any{"g00", "g63"}}) {
				t.Fatalf("selected annotation metadata=%v", row["$metadata"])
			}
		}
	}
	if path := os.Getenv("MAPPED_ANNOTATION_OUTPUT"); path != "" {
		raw, err := json.MarshalIndent(outputs, "", "  ")
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, append(raw, '\n'), 0600); err != nil {
			t.Fatal(err)
		}
	}
}
