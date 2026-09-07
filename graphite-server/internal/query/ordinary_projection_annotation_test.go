package query

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func TestOrdinaryProjectionAnnotationMain(t *testing.T) {
	var cases []struct {
		Name, Query string
		Params      map[string]any
		Empty       bool
	}
	readDistinctJSON(t, "testdata/ordinary-projection/annotation-cases.json", &cases)
	for _, name := range []string{"annotation", "mixed", "annotation-after"} {
		t.Run(name, func(t *testing.T) {
			fixture := "testdata/candidate-index/" + name
			if name == "annotation-after" {
				fixture = "testdata/indexed-distinct/" + name
			}
			graph, err := store.OpenMode(ordinaryCopyFixture(t, fixture), "MAPPED")
			if err != nil {
				t.Fatal(err)
			}
			defer graph.Close()
			var oracle []map[string]any
			readDistinctJSON(t, "testdata/ordinary-projection/"+name+"-main.json", &oracle)
			output := []map[string]any{}
			position := 0
			classification := []map[string]any{}
			for _, spec := range cases {
				for _, cross := range []bool{false, true} {
					expected := oracle[position]
					position++
					var result Result
					var err error
					if cross {
						result, err = ExecuteCross(context.Background(), []Graph{{"a", graph}, {"b", graph}}, spec.Query, spec.Params, -1)
					} else {
						result, err = Execute(context.Background(), graph, spec.Query, spec.Params, -1)
					}
					actual := distinctOracleResult(map[string]any{"name": spec.Name, "query": spec.Query, "cross": cross, "empty": false}, result, err)
					output = append(output, actual)
					ast, parseErr := cypher.Parse(spec.Query)
					if parseErr != nil {
						t.Fatal(parseErr)
					}
					e := evaluator{ctx: context.Background(), cross: cross, parameters: spec.Params}
					eligible := e.compileOrdinaryProjection(ast.Branches[0]) != nil
					equal := reflect.DeepEqual(expected, actual)
					classification = append(classification, map[string]any{"name": spec.Name, "cross": cross, "eligible": eligible, "equal": equal})
					if eligible && !equal {
						t.Errorf("%s cross %v main=%s native=%s", spec.Name, cross, mustJSON(expected), mustJSON(actual))
					}
				}
			}
			if dir := os.Getenv("ORDINARY_HISTORY_OUTPUT"); dir != "" {
				if err := os.MkdirAll(dir, 0700); err != nil {
					t.Fatal(err)
				}
				for file, value := range map[string]any{name + "-native.json": output, name + "-classification.json": classification} {
					data, _ := json.MarshalIndent(value, "", "  ")
					if err := os.WriteFile(filepath.Join(dir, file), append(data, '\n'), 0600); err != nil {
						t.Fatal(err)
					}
				}
			}
		})
	}
}
