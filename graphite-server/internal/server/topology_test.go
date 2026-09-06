package server

import (
	"context"
	"math"
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"testing"
)

func TestTopologyAggregationAndTransactionalUnload(t *testing.T) {
	r := nativeRegistryForTest(t, "z", "a")
	rules := []TopologyQuery{{"calls.cypher", `UNWIND [
{s:'a',t:'z',p:' http ',w:2,o:'GET /z',e:'B'},
{s:'a',t:'z',p:'http',w:3,o:'GET /z',e:'A'},
{s:'z',t:'a',p:null,w:null,o:null,e:null},
{s:'a',t:'a',p:null,w:-1,o:null,e:null}
] AS r RETURN r.s AS source, r.t AS target, r.p AS protocol, r.w AS weight, r.o AS operation, r.e AS evidence`}}
	topology, err := NewTopologyService(r, rules)
	if err != nil {
		t.Fatal(err)
	}
	got := topology.current.Load()
	wantEdges := []topologyEdge{
		{From: "a", To: "z", Type: "TopologyCall", Protocol: "http", Weight: 5, Operations: []string{"GET /z"}, Evidence: []string{"A", "B"}},
		{From: "z", To: "a", Type: "TopologyCall", Protocol: "call", Weight: 1, Operations: []string{}, Evidence: []string{}},
	}
	if !reflect.DeepEqual(got.Edges, wantEdges) || got.MatchedRows != 4 || !reflect.DeepEqual(got.Rules, []string{"calls.cypher"}) {
		t.Fatalf("wrong topology: %+v", got)
	}
	if got.Nodes[0].ID != "a" || got.Nodes[0].Nodes == 0 || got.Nodes[1].ID != "z" {
		t.Fatalf("nodes: %+v", got.Nodes)
	}
	h := (&Server{Registry: r, Topology: topology}).Handler()
	response := request(t, h, "GET", "/api/topology", "", 200)
	if response.Header().Get("Content-Type") != "application/json;charset=utf-8" || response.Header().Get("Content-Length") != strconv.Itoa(response.Body.Len()) {
		t.Fatalf("topology stream headers: %v", response.Header())
	}
	before := responseJSON(t, response)
	if before["stale"] != false || before["matchedRows"] != float64(4) {
		t.Fatalf("response: %v", before)
	}
	// Removing a referenced graph fails the topology build. Neither publication
	// changes, and the original graph remains usable through the same generation.
	removed, err := r.Unload("z")
	if removed || err == nil || !strings.Contains(err.Error(), "unknown graph 'z'") {
		t.Fatalf("unload: %v %v", removed, err)
	}
	after := responseJSON(t, request(t, h, "GET", "/api/topology", "", 200))
	if !reflect.DeepEqual(before, after) {
		t.Fatal("failed topology rebuild changed published snapshot")
	}
	if d, _ := r.Describe("z"); d == nil {
		t.Fatal("failed unload removed graph")
	}
}

func TestNodesOnlyTopologyTracksReplacement(t *testing.T) {
	r := nativeRegistryForTest(t, "a")
	topology, err := NewTopologyService(r, nil)
	if err != nil {
		t.Fatal(err)
	}
	old := topology.current.Load()
	d, _ := r.Describe("a")
	if _, err = r.Load("a", d.Path, ""); err != nil {
		t.Fatal(err)
	}
	next := topology.current.Load()
	if old == next || old.versions["a"] == next.versions["a"] || len(next.Edges) != 0 || next.MatchedRows != 0 {
		t.Fatalf("replacement not rebuilt: %+v", next)
	}
	if removed, err := r.Unload("a"); !removed || err != nil {
		t.Fatalf("unload: %v %v", removed, err)
	}
	got := responseJSON(t, request(t, (&Server{Registry: r, Topology: topology}).Handler(), "GET", "/api/topology", "", 200))
	if got["graphCount"] != float64(0) || got["stale"] != false || !reflect.DeepEqual(got["nodes"], []any{}) {
		t.Fatalf("empty topology: %v", got)
	}
}

func TestTopologyValidationAndDetailBounds(t *testing.T) {
	r := nativeRegistryForTest(t, "a", "z")
	for _, tc := range []struct{ q, want string }{
		{`RETURN 1 AS x`, "must return 'source' and 'target'"},
		{`RETURN null AS source, 'z' AS target`, "returned a blank 'source'"},
		{`RETURN 'missing' AS source, 'z' AS target`, "returned unknown graph 'missing'"},
		{`RETURN 'a' AS source, 'z' AS target, 0 AS weight`, "non-positive or fractional weight: 0"},
		{`UNWIND [9223372036854775807,1] AS w RETURN 'a' AS source, 'z' AS target, w AS weight`, "long overflow"},
	} {
		_, err := buildTopology(context.Background(), r.catalog(), []TopologyQuery{{"rule", tc.q}})
		if err == nil || !strings.Contains(err.Error(), tc.want) {
			t.Errorf("%s: %v", tc.q, err)
		}
	}
	for _, tc := range []struct {
		value any
		want  int64
		bad   bool
	}{
		{nil, 1, false}, {int32(3), 3, false}, {"2", 2, false}, {" 2 ", 0, true}, {true, 0, true}, {1.5, 0, true}, {math.NaN(), 0, true}, {float64(math.MaxInt64), math.MaxInt64, false},
	} {
		got, err := topologyWeight("rule", tc.value)
		if (err != nil) != tc.bad || !tc.bad && got != tc.want {
			t.Errorf("weight %v: %d %v", tc.value, got, err)
		}
	}
	values := []string{}
	for i := 0; i < 110; i++ {
		values = addTopologyDetail(values, strings.Repeat("x", i+1))
	}
	if len(values) != 100 || len(values[99]) != 100 {
		t.Fatalf("detail bound: %v", values)
	}
	if !javaTextLess("𐀀", "\ue000") {
		t.Fatal("topology detail sorting must use UTF-16 order")
	}
}

func TestTopologyQuerySource(t *testing.T) {
	dir := t.TempDir()
	for name, body := range map[string]string{"z.cypher": " RETURN 2 ", "a.cypher": "RETURN 1", "ignored.txt": "RETURN 3"} {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(body), 0600); err != nil {
			t.Fatal(err)
		}
	}
	got, err := LoadTopologyQueries(dir)
	if err != nil || !reflect.DeepEqual(got, []TopologyQuery{{"a.cypher", "RETURN 1"}, {"z.cypher", "RETURN 2"}}) {
		t.Fatalf("queries: %v %v", got, err)
	}
	// A directly named regular file is accepted regardless of its extension.
	got, err = LoadTopologyQueries(filepath.Join(dir, "ignored.txt"))
	if err != nil || len(got) != 1 || got[0].Cypher != "RETURN 3" {
		t.Fatalf("direct file: %v %v", got, err)
	}
	if err = os.WriteFile(filepath.Join(dir, "blank.cypher"), []byte(" \n"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err = LoadTopologyQueries(dir); err == nil || !strings.Contains(err.Error(), "Topology query is empty:") {
		t.Fatalf("blank: %v", err)
	}
}
