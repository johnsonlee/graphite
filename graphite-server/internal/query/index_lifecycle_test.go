package query

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func lifecycleFixture(t *testing.T, name string) string {
	t.Helper()
	base := "clean"
	if strings.HasPrefix(name, "bad-") {
		base = "bad-matched"
	}
	source := filepath.Join("testdata", "candidate-index", base)
	dir := lifecycleCopyFixture(t, source)
	index := filepath.Join(dir, "graph.callsite-string-index")
	if strings.HasSuffix(name, "missing") {
		if err := os.Remove(index); err != nil {
			t.Fatal(err)
		}
	}
	if name == "corrupt" {
		data, err := os.ReadFile(index)
		if err != nil {
			t.Fatal(err)
		}
		data[len(data)-1] ^= 1
		if err := os.WriteFile(index, data, 0600); err != nil {
			t.Fatal(err)
		}
	}
	return dir
}

func lifecycleCopyFixture(t *testing.T, source string) string {
	t.Helper()
	dir := t.TempDir()
	entries, err := os.ReadDir(source)
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range entries {
		data, err := os.ReadFile(filepath.Join(source, entry.Name()))
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, entry.Name()), data, 0600); err != nil {
			t.Fatal(err)
		}
	}
	return dir
}

func lifecycleIndexFile(t *testing.T, dir string) any {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(dir, "graph.callsite-string-index"))
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		t.Fatal(err)
	}
	return map[string]any{"bytes": len(data), "sha256": fmt.Sprintf("%x", sha256.Sum256(data))}
}

func lifecycleState(t *testing.T, g *store.Store, dir string) map[string]any {
	t.Helper()
	state, err := g.StringPropertyIndexes(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	raw, err := json.Marshal(state)
	if err != nil {
		t.Fatal(err)
	}
	var out map[string]any
	if err := json.Unmarshal(raw, &out); err != nil {
		t.Fatal(err)
	}
	out["indexFile"] = lifecycleIndexFile(t, dir)
	return out
}

func TestIndexLifecycleMain(t *testing.T) {
	var expected []map[string]any
	readDistinctJSON(t, "testdata/index-lifecycle/main.json", &expected)
	if len(expected) != 10 {
		t.Fatal("incomplete actual-main lifecycle matrix")
	}
	outputs := []map[string]any{}
	for _, want := range expected {
		name := want["fixture"].(string)
		startup := want["startup"].(bool)
		t.Run(fmt.Sprintf("%s/startup%v", name, startup), func(t *testing.T) {
			dir := lifecycleFixture(t, name)
			actual := map[string]any{"fixture": name, "startup": startup, "initialIndexFile": lifecycleIndexFile(t, dir)}
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
			// Compare all seven state fields, including both raw-cache counters.
			comparison := lifecycleComparableMain(t, want)
			if !reflect.DeepEqual(normalized, comparison) {
				t.Errorf("main lifecycle differs; complete candidate retained in INDEX_LIFECYCLE_OUTPUT")
			}
		})
	}
	if path := os.Getenv("INDEX_LIFECYCLE_OUTPUT"); path != "" {
		raw, err := json.MarshalIndent(outputs, "", "  ")
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, append(raw, '\n'), 0600); err != nil {
			t.Fatal(err)
		}
	}
}

func lifecycleComparableMain(t *testing.T, record map[string]any) map[string]any {
	t.Helper()
	raw, err := json.Marshal(record)
	if err != nil {
		t.Fatal(err)
	}
	var out map[string]any
	if err := json.Unmarshal(raw, &out); err != nil {
		t.Fatal(err)
	}
	return out
}

func TestIndexLifecycleBoundariesMain(t *testing.T) {
	var records []map[string]any
	readDistinctJSON(t, "testdata/index-lifecycle/boundary-main.json", &records)
	if len(records) != 2 {
		t.Fatal("missing actual-main index boundary")
	}
	for _, want := range records {
		name := want["fixture"].(string)
		t.Run(name, func(t *testing.T) {
			dir := lifecycleCopyFixture(t, filepath.Join("testdata/index-lifecycle", name))
			g, err := store.OpenMode(dir, "MAPPED")
			if err != nil {
				t.Fatal(err)
			}
			defer g.Close()
			actual := map[string]any{"fixture": name, "loaded": lifecycleState(t, g, dir)}
			prepared, err := g.PrepareCallSiteStringIndex(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			actual["prepared"] = prepared
			actual["afterPrepare"] = lifecycleState(t, g, dir)
			if err := g.ClearStringPropertyIndexes(context.Background()); err != nil {
				t.Fatal(err)
			}
			actual["afterClear"] = lifecycleState(t, g, dir)
			raw, err := json.Marshal(actual)
			if err != nil {
				t.Fatal(err)
			}
			var normalized map[string]any
			if err := json.Unmarshal(raw, &normalized); err != nil {
				t.Fatal(err)
			}
			if expected := lifecycleComparableMain(t, want); !reflect.DeepEqual(normalized, expected) {
				t.Fatalf("native %s main %s", mustJSON(normalized), mustJSON(expected))
			}
		})
	}
}
