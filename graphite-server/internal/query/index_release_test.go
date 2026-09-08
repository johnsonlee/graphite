package query

import (
	"context"
	"encoding/json"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"os"
	"reflect"
	"testing"
)

func TestIndexReleaseMain(t *testing.T) {
	var expected []map[string]any
	readDistinctJSON(t, "testdata/index-release/main.json", &expected)
	if len(expected) != 3 {
		t.Fatal("incomplete actual-main lifecycle matrix")
	}
	outputs := []map[string]any{}
	for _, want := range expected {
		scenario := want["scenario"].(string)
		name := "clean"
		if scenario == "built-prepared" {
			name = "missing"
		}
		startup := false
		t.Run(scenario, func(t *testing.T) {
			dir := lifecycleFixture(t, name)
			actual := map[string]any{"scenario": scenario}
			g, err := store.OpenModeWithOptions(context.Background(), dir, "MAPPED", store.OpenOptions{PrepareCallSiteStringIndex: startup})
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { g.Close() })
			actual["loaded"] = lifecycleState(t, g, dir)
			steps := []map[string]any{}
			for _, rawStep := range want["steps"].([]any) {
				action := rawStep.(map[string]any)["action"].(string)
				step := map[string]any{"action": action, "before": lifecycleState(t, g, dir)}
				switch action {
				case "query":
					result, err := ExecuteCrossWithOptions(context.Background(), []Graph{{"g", g}}, "MATCH (n) WHERE n.caller_name CONTAINS $term RETURN n.caller_name AS x LIMIT 1", map[string]any{"term": "other"}, -1, ExecutionOptions{SourceScopeApplied: true, WorkTrackingEnabled: true})
					step = distinctOracleResult(step, result, err)
				case "release":
					if err := g.ReleaseDistinctStringIndex(context.Background()); err != nil {
						t.Fatal(err)
					}
				case "clear":
					if err := g.ClearStringPropertyIndexes(context.Background()); err != nil {
						t.Fatal(err)
					}
				case "prepare":
					prepared, err := g.PrepareCallSiteStringIndex(context.Background())
					if err != nil {
						t.Fatal(err)
					}
					step["prepared"] = prepared
				default:
					t.Fatal("unknown action", action)
				}
				step["after"] = lifecycleState(t, g, dir)
				steps = append(steps, step)
			}
			actual["steps"] = steps
			if err := g.Close(); err != nil {
				t.Fatal(err)
			}
			actual["finalIndexFile"] = lifecycleIndexFile(t, dir)
			raw, err := json.Marshal(actual)
			if err != nil {
				t.Fatal(err)
			}
			var normalized map[string]any
			if err := json.Unmarshal(raw, &normalized); err != nil {
				t.Fatal(err)
			}
			outputs = append(outputs, normalized)
			// Go has no corresponding raw-match/projection-cache counters. Preserve
			// the original oracle, and explicitly limit this comparison to its five
			// implemented structural observations plus all public values/file bytes.
			comparison := lifecycleComparableMain(t, want)
			if !reflect.DeepEqual(normalized, comparison) {
				t.Errorf("main lifecycle differs; complete candidate retained in INDEX_RELEASE_OUTPUT")
			}
		})
	}
	if path := os.Getenv("INDEX_RELEASE_OUTPUT"); path != "" {
		raw, err := json.MarshalIndent(outputs, "", "  ")
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, append(raw, '\n'), 0600); err != nil {
			t.Fatal(err)
		}
	}
}
