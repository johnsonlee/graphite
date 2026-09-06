package server

import (
	"context"
	"errors"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

type tinyTraversal struct{ edges []store.Edge }

func (g tinyTraversal) Node(id int32) (map[string]any, error) {
	if id < 1 || id > 3 {
		return nil, store.ErrNodeNotFound
	}
	return map[string]any{"id": id, "type": "IntConstant", "value": id * 10}, nil
}
func (g tinyTraversal) Outgoing(id int32) []store.Edge {
	var out []store.Edge
	for _, e := range g.edges {
		if e.From == id {
			out = append(out, e)
		}
	}
	return out
}
func (g tinyTraversal) Incoming(id int32) []store.Edge {
	var out []store.Edge
	for _, e := range g.edges {
		if e.To == id {
			out = append(out, e)
		}
	}
	return out
}

func TestSubgraphDepthDirectionAndCycleWireOrder(t *testing.T) {
	g := tinyTraversal{[]store.Edge{
		{From: 1, To: 2, Family: "DataFlowEdge", Kind: "ASSIGN"},
		{From: 2, To: 3, Family: "CallEdge", IsVirtual: true},
		{From: 3, To: 1, Family: "TypeEdge", Kind: "EXTENDS"},
	}}
	for _, tc := range []struct {
		direction string
		depth     int
		ids       []int32
		edgePairs [][2]int32
	}{
		{"outgoing", 1, []int32{1, 2}, [][2]int32{{1, 2}}},
		{"incoming", 1, []int32{1, 3}, [][2]int32{{3, 1}}},
		{"both", 2, []int32{1, 2, 3}, [][2]int32{{1, 2}, {2, 3}, {1, 2}, {3, 1}}},
		{"both", 0, []int32{1}, [][2]int32{}},
		{"both", -1, []int32{}, [][2]int32{}},
	} {
		r, err := buildSubgraph(context.Background(), g, 1, tc.depth, tc.direction)
		if err != nil {
			t.Fatal(err)
		}
		ids := []int32{}
		pairs := [][2]int32{}
		for _, n := range r["nodes"].([]map[string]any) {
			ids = append(ids, n["id"].(int32))
			if n["value"] != n["id"].(int32)*10 {
				t.Fatal("wrong node value")
			}
		}
		for _, e := range r["edges"].([]map[string]any) {
			pairs = append(pairs, [2]int32{e["from"].(int32), e["to"].(int32)})
		}
		if !reflect.DeepEqual(ids, tc.ids) || !reflect.DeepEqual(pairs, tc.edgePairs) {
			t.Fatalf("%s/%d: ids=%v edges=%v", tc.direction, tc.depth, ids, pairs)
		}
	}
	if got := edgeMap(g.edges[1]); !reflect.DeepEqual(got, map[string]any{"from": int32(2), "to": int32(3), "type": "Call", "virtual": true, "dynamic": false}) {
		t.Fatalf("edge wire: %v", got)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := buildSubgraph(ctx, g, 1, 4, "both"); !errors.Is(err, context.Canceled) {
		t.Fatal("traversal ignored cancellation")
	}
}

func TestJSONOmitsObjectNullsRetainsArrayNulls(t *testing.T) {
	var profile *string
	source := map[string]any{"profile": profile, "missing": nil, "nested": map[string]any{"x": nil, "y": int64(5)}, "array": []any{nil, map[string]any{"z": nil}}}
	want := map[string]any{"nested": map[string]any{"y": int64(5)}, "array": []any{nil, map[string]any{}}}
	if got := omitNullFields(source); !reflect.DeepEqual(got, want) {
		t.Fatalf("Gson null parity: %#v", got)
	}
	if _, ok := source["missing"]; !ok {
		t.Fatal("serializer mutated input")
	}
}

func TestJavaFloatingLabels(t *testing.T) {
	for _, tc := range []struct {
		value float64
		want  string
	}{
		{9.223372036854776e18, "9.223372036854776E18"},
		{0.001, "0.001"}, {0.0001, "1.0E-4"}, {10000000, "1.0E7"}, {2, "2.0"},
	} {
		if got := javaFloat(tc.value, 64); got != tc.want {
			t.Errorf("%v: %s != %s", tc.value, got, tc.want)
		}
	}
}
