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

func TestCountConversionMainOracle(t *testing.T) {
	const dir = "testdata/count-conversion/"
	read := func(name string, dst any) {
		t.Helper()
		data, err := os.ReadFile(dir + name)
		if err != nil {
			t.Fatal(err)
		}
		if err = json.Unmarshal(data, dst); err != nil {
			t.Fatal(err)
		}
	}
	var cases []struct {
		Name, Query string
		Empty       bool
		Params      map[string]any
	}
	var oracle []map[string]any
	read("cases.json", &cases)
	read("main-oracle.json", &oracle)
	if len(cases) != 160 || len(oracle) != 320 {
		t.Fatal("incomplete main oracle")
	}
	graph, err := store.Open("../store/testdata/jvm-v3")
	if err != nil {
		t.Fatal(err)
	}
	defer graph.Close()
	for index, spec := range cases {
		for mode, cross := range []bool{false, true} {
			t.Run(fmt.Sprintf("%s/cross=%v", spec.Name, cross), func(t *testing.T) {
				selected := graph
				if spec.Empty {
					selected = &store.Store{}
				}
				var result Result
				var err error
				if cross {
					result, err = ExecuteCross(context.Background(), []Graph{{"a", selected}, {"b", selected}}, spec.Query, spec.Params, -1)
				} else {
					result, err = Execute(context.Background(), selected, spec.Query, spec.Params, -1)
				}
				actual := map[string]any{"name": spec.Name, "query": spec.Query, "cross": cross, "empty": spec.Empty}
				if err != nil {
					actual["message"] = err.Error()
					if queryError, ok := err.(*Error); ok {
						actual["error"] = queryError.Class
					}
				} else {
					actual["columns"] = result.Columns
					actual["rows"] = result.Rows
				}
				data, err := json.Marshal(actual)
				if err != nil {
					t.Fatal(err)
				}
				var normalized map[string]any
				if err = json.Unmarshal(data, &normalized); err != nil {
					t.Fatal(err)
				}
				if !reflect.DeepEqual(normalized, oracle[2*index+mode]) {
					t.Fatalf("main=%#v\nnative=%s", oracle[2*index+mode], data)
				}
			})
		}
	}
}
