package query

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func TestStreamingPaginationSourceHistories(t *testing.T) {
	var records []map[string]any
	readDistinctJSON(t, "testdata/streaming-pagination/sources-wire.json", &records)
	if len(records) != 28 {
		t.Fatal("denominator", len(records))
	}
	outputs := []map[string]any{}
	for _, record := range records {
		t.Run(record["name"].(string), func(t *testing.T) {
			count := int(record["count"].(float64))
			badAt := -1
			if n, ok := record["badAt"].(float64); ok {
				badAt = int(n)
			}
			mode := "MAPPED"
			if s, ok := record["mode"].(string); ok {
				mode = s
			}
			sources := []Graph{}
			for i := 0; i < count; i++ {
				fixture := "clean"
				if i == badAt {
					fixture = record["fixture"].(string)
				}
				dir := mainSourceFixture(t, fixture)
				if record["missingIndex"] == true {
					if err := os.Remove(filepath.Join(dir, "graph.callsite-string-index")); err != nil {
						t.Fatal(err)
					}
				}
				g, err := store.OpenMode(dir, mode)
				if err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { g.Close() })
				id := i
				if record["reverseIDs"] == true {
					id = count - 1 - i
				}
				sources = append(sources, Graph{ID: fmt.Sprintf("g%d", id), Store: g})
			}
			expected := record["history"].([]any)
			history := []any{}
			for range expected {
				result, err := ExecuteCross(context.Background(), sources, record["query"].(string), nil, -1)
				history = append(history, distinctOracleResult(nil, result, err))
			}
			actual := map[string]any{}
			for k, v := range record {
				actual[k] = v
			}
			actual["history"] = history
			outputs = append(outputs, actual)
			if !reflect.DeepEqual(record, actual) {
				t.Errorf("main=%s native=%s", mustJSON(record), mustJSON(actual))
			}
		})
	}
	writeDistinctEvidence(t, "streaming-sources-native.json", outputs)
}
