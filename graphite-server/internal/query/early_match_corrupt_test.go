package query

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func TestEarlyMatchStopsBeforeCorruptTail(t *testing.T) {
	fixture := t.TempDir()
	entries, err := os.ReadDir("testdata/traversal")
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range entries {
		data, err := os.ReadFile(filepath.Join("testdata/traversal", entry.Name()))
		if err != nil {
			t.Fatal(err)
		}
		if err = os.WriteFile(filepath.Join(fixture, entry.Name()), data, 0600); err != nil {
			t.Fatal(err)
		}
	}
	graph, err := store.OpenMode(fixture, "MAPPED")
	if err != nil {
		t.Fatal(err)
	}
	defer graph.Close()
	file, err := os.OpenFile(filepath.Join(fixture, "graph.nodedata"), os.O_WRONLY, 0)
	if err != nil {
		t.Fatal(err)
	}
	_, err = file.WriteAt([]byte{127}, 75)
	closeErr := file.Close()
	if err != nil {
		t.Fatal(err)
	}
	if closeErr != nil {
		t.Fatal(closeErr)
	}
	var cases []struct {
		Name, Query string
		Params      map[string]any
	}
	var oracle []map[string]any
	for name, dst := range map[string]any{"corrupt-cases.json": &cases, "corrupt-main.json": &oracle} {
		data, err := os.ReadFile("testdata/early-match/" + name)
		if err != nil {
			t.Fatal(err)
		}
		if err = json.Unmarshal(data, dst); err != nil {
			t.Fatal(err)
		}
	}
	if len(cases) != 6 || len(oracle) != 12 {
		t.Fatal("incomplete corrupt-tail oracle")
	}
	outputs := []map[string]any{}
	for index, spec := range cases {
		for mode, cross := range []bool{false, true} {
			t.Run(fmt.Sprintf("%s/cross=%v", spec.Name, cross), func(t *testing.T) {
				var result Result
				var err error
				if cross {
					result, err = ExecuteCross(context.Background(), []Graph{{"a", graph}, {"b", graph}}, spec.Query, spec.Params, -1)
				} else {
					result, err = Execute(context.Background(), graph, spec.Query, spec.Params, -1)
				}
				actual := map[string]any{"name": spec.Name, "query": spec.Query, "cross": cross}
				if err != nil {
					actual["message"] = err.Error()
					if queryError, ok := err.(*Error); ok {
						actual["error"] = queryError.Class
					}
				} else {
					actual["columns"] = result.Columns
					actual["rows"] = result.Rows
				}
				data, encodeErr := json.Marshal(actual)
				if encodeErr != nil {
					t.Fatal(encodeErr)
				}
				var normalized map[string]any
				if encodeErr = json.Unmarshal(data, &normalized); encodeErr != nil {
					t.Fatal(encodeErr)
				}
				outputs = append(outputs, normalized)
				if !reflect.DeepEqual(normalized, oracle[2*index+mode]) {
					t.Fatalf("main=%#v\nnative=%s", oracle[2*index+mode], data)
				}
			})
		}
	}
	if output := os.Getenv("EARLY_CORRUPT_OUTPUT"); output != "" {
		data, err := json.MarshalIndent(outputs, "", "  ")
		if err != nil {
			t.Fatal(err)
		}
		if err = os.WriteFile(output, append(data, '\n'), 0600); err != nil {
			t.Fatal(err)
		}
	}
}
