package query

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func TestOrdinaryProjectionCacheThresholdMain(t *testing.T) {
	var specs []map[string]any
	readDistinctJSON(t, "testdata/ordinary-projection/cache-threshold-main.json", &specs)
	a, err := store.OpenMode(ordinaryCopyFixture(t, "testdata/ordinary-projection/cache-fixtures/clean"), "MAPPED")
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close()
	b, err := store.OpenMode(ordinaryCopyFixture(t, "testdata/ordinary-projection/cache-fixtures/bad"), "MAPPED")
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	both := []Graph{{"a", a}, {"b", b}}
	single := []Graph{{"single", a}}
	outputs := []map[string]any{}
	for i, spec := range specs {
		cross := len(spec["before"].([]any)) == 2
		sources := single
		if cross {
			sources = both
		}
		observed := ordinaryHistoryResult(t, sources, cross, spec["query"].(string))
		sizes := []int64{}
		for _, source := range sources {
			index, ok, err := source.Store.RetainedProjectionIndex(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			var bytes int64
			if ok {
				bytes, err = index.ProjectionPlannerBytes(context.Background())
				if err != nil {
					t.Fatal(err)
				}
			}
			sizes = append(sizes, bytes)
		}
		observed["retainedBytes"] = sizes
		raw, _ := json.Marshal(observed)
		var normalized map[string]any
		_ = json.Unmarshal(raw, &normalized)
		outputs = append(outputs, normalized)
		if !reflect.DeepEqual(normalized, spec) {
			t.Errorf("case %d query %s main bytes %v native bytes %v main error %v native error %v; response equal %v", i, spec["query"], spec["retainedBytes"], sizes, spec["error"], observed["error"], reflect.DeepEqual(ordinaryObservedResponse(normalized), ordinaryObservedResponse(spec)))
		}
	}
	if dir := os.Getenv("ORDINARY_HISTORY_OUTPUT"); dir != "" {
		if err := os.MkdirAll(dir, 0700); err != nil {
			t.Fatal(err)
		}
		data, err := json.MarshalIndent(outputs, "", "  ")
		if err != nil {
			t.Fatal(err)
		}
		if err = os.WriteFile(filepath.Join(dir, "cache-threshold-native.json"), append(data, '\n'), 0600); err != nil {
			t.Fatal(err)
		}
	}
}
