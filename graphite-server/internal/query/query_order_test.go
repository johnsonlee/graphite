package query

import (
	"context"
	"encoding/json"
	"os"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func TestEagerEncounterOrderMainJVMOracle(t *testing.T) {
	for _, fixture := range []string{"mixed-sparse", "all-kinds"} {
		t.Run(fixture, func(t *testing.T) {
			graph, err := store.OpenMode("testdata/node-encounter/"+fixture, "EAGER")
			if err != nil {
				t.Fatal(err)
			}
			defer graph.Close()
			raw, err := os.ReadFile("testdata/node-encounter/" + fixture + "-eager-query-oracle.json")
			if err != nil {
				t.Fatal(err)
			}
			var corpus struct {
				Cases []struct {
					Name, Query string
					Cross       bool
					Columns     []string
					Rows        []map[string]any
				}
			}
			if err = json.Unmarshal(raw, &corpus); err != nil {
				t.Fatal(err)
			}
			for _, c := range corpus.Cases {
				t.Run(c.Name, func(t *testing.T) {
					var result Result
					var err error
					if c.Cross {
						result, err = ExecuteCross(context.Background(), []Graph{{"b", graph}, {"a", graph}}, c.Query, nil, -1)
					} else {
						result, err = Execute(context.Background(), graph, c.Query, nil, -1)
					}
					if err != nil {
						t.Fatal(err)
					}
					data, err := json.Marshal(result.Rows)
					if err != nil {
						t.Fatal(err)
					}
					var rows []map[string]any
					if err = json.Unmarshal(data, &rows); err != nil {
						t.Fatal(err)
					}
					if !reflect.DeepEqual(result.Columns, c.Columns) || !reflect.DeepEqual(rows, c.Rows) {
						t.Fatalf("%s: columns got %v want %v; rows got %s want %s", c.Query, result.Columns, c.Columns, data, mustJSON(c.Rows))
					}
				})
			}
		})
	}
}

func TestQueryOrderPreservesPersistedAndTypedAccessors(t *testing.T) {
	persisted := []int32{90, 2, 17, 41, 4, 77, 22, 6, 103}
	grouped := []int32{90, 41, 22, 2, 4, 6, 17, 77, 103}
	for _, mode := range []string{"EAGER", "MAPPED"} {
		t.Run(mode, func(t *testing.T) {
			graph, err := store.OpenMode("testdata/node-encounter/mixed-sparse", mode)
			if err != nil {
				t.Fatal(err)
			}
			defer graph.Close()
			want := persisted
			if mode == "EAGER" {
				want = grouped
			}
			got := graph.QueryNodeIDs()
			if !reflect.DeepEqual(got, want) {
				t.Fatalf("query IDs got %v, want %v", got, want)
			}
			got[0] = -100
			if !reflect.DeepEqual(graph.QueryNodeIDs(), want) {
				t.Fatal("caller mutation escaped into query source")
			}
			if !reflect.DeepEqual(graph.NodeIDs(), persisted) {
				t.Fatalf("persisted IDs changed: %v", graph.NodeIDs())
			}
			for kind, ids := range map[string][]int32{"IntConstant": {90, 41, 22}, "StringConstant": {2, 4, 6}, "CallSiteNode": {17, 77, 103}} {
				if got := graph.NodesOfKind(kind); !reflect.DeepEqual(got, ids) {
					t.Fatalf("%s got %v, want %v", kind, got, ids)
				}
			}
		})
	}
}
