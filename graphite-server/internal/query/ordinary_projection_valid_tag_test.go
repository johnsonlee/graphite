package query

import (
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func TestOrdinaryProjectionValidTagHistoryMain(t *testing.T) {
	var specs []map[string]any
	readDistinctJSON(t, "testdata/ordinary-projection/valid-tag-main.json", &specs)
	for _, spec := range specs {
		t.Run(fmt.Sprintf("%v/%s", spec["count"], spec["operator"]), func(t *testing.T) {
			dir := ordinaryCopyFixture(t, "testdata/candidate-index/clean")
			p := filepath.Join(dir, "graph.nodedata")
			data, err := os.ReadFile(p)
			if err != nil {
				t.Fatal(err)
			}
			data[69] = 0
			if err = os.WriteFile(p, data, 0600); err != nil {
				t.Fatal(err)
			}
			count := int(spec["count"].(float64))
			sources := make([]Graph, count)
			for i := range sources {
				g, err := store.OpenMode(dir, "MAPPED")
				if err != nil {
					t.Fatal(err)
				}
				defer g.Close()
				id := "single"
				if count > 1 {
					id = fmt.Sprintf("g%d", i)
				}
				sources[i] = Graph{id, g}
			}
			for _, target := range spec["targets"].([]any) {
				expected := target.(map[string]any)
				actual := ordinaryHistoryResult(t, sources, count > 1, expected["query"].(string))
				if !reflect.DeepEqual(ordinaryObservedResponse(expected), ordinaryObservedResponse(actual)) {
					t.Errorf("main=%s native=%s", mustJSON(expected), mustJSON(actual))
				}
			}
		})
	}
}
