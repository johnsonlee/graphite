package query

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func TestOrdinaryProjectionRequiredHistoryMain(t *testing.T) {
	var mutations []struct{ Name string }
	readDistinctJSON(t, "testdata/indexed-distinct/required-mutations.json", &mutations)
	for _, mutation := range mutations {
		for _, missing := range []bool{false, true} {
			name := mutation.Name
			if missing {
				name += "-missing"
			}
			t.Run(name, func(t *testing.T) {
				dir := ordinaryCopyFixture(t, "testdata/indexed-distinct/required/"+mutation.Name)
				if missing {
					if err := os.Remove(filepath.Join(dir, "graph.callsite-string-index")); err != nil {
						t.Fatal(err)
					}
				}
				var specs []map[string]any
				readDistinctJSON(t, "testdata/ordinary-projection/required/"+name+"-main.json", &specs)
				output := []map[string]any{}
				for _, spec := range specs {
					count := int(spec["count"].(float64))
					sources := make([]Graph, count)
					for i := range sources {
						g, err := store.OpenMode(dir, "MAPPED")
						if err != nil {
							t.Fatal(err)
						}
						id := "single"
						if count > 1 {
							id = fmt.Sprintf("g%d", i)
						}
						sources[i] = Graph{id, g}
					}
					actual := map[string]any{"count": spec["count"], "property": spec["property"]}
					targets := []map[string]any{}
					for _, target := range spec["targets"].([]any) {
						expected := target.(map[string]any)
						observed := ordinaryHistoryResult(t, sources, count > 1, expected["query"].(string))
						targets = append(targets, observed)
						if !reflect.DeepEqual(ordinaryObservedResponse(expected), ordinaryObservedResponse(observed)) {
							t.Errorf("count=%d main=%s native=%s", count, mustJSON(ordinaryObservedResponse(expected)), mustJSON(ordinaryObservedResponse(observed)))
						}
						if count == 1 && mustJSON(expected) != mustJSON(observed) {
							t.Errorf("single-source history main=%s native=%s", mustJSON(expected), mustJSON(observed))
						}
					}
					actual["targets"] = targets
					output = append(output, actual)
					for _, source := range sources {
						if err := source.Store.Close(); err != nil {
							t.Fatal(err)
						}
					}
				}
				if dir := os.Getenv("ORDINARY_HISTORY_OUTPUT"); dir != "" {
					dir = filepath.Join(dir, "required")
					if err := os.MkdirAll(dir, 0700); err != nil {
						t.Fatal(err)
					}
					data, _ := json.MarshalIndent(output, "", "  ")
					if err := os.WriteFile(filepath.Join(dir, name+"-native.json"), append(data, '\n'), 0600); err != nil {
						t.Fatal(err)
					}
				}
			})
		}
	}
}
