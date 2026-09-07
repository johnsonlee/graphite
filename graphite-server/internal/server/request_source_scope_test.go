package server

import (
	"context"
	"encoding/json"
	"path/filepath"
	"reflect"
	"testing"
	"time"
)

// Request-selected all sources still carries main's preselected scope marker.
// These cases expose it through both storage lifecycle and a later read error.
func TestRequestSourceScopeHTTP(t *testing.T) {
	for _, selection := range []string{"root", "allGraphs", "selected"} {
		for _, shape := range []string{"ordinary", "and-route"} {
			t.Run(selection+"/"+shape, func(t *testing.T) {
				r := registryForTest(t, OpenNativeGraph)
				for i, name := range []string{"clean", "bad-matched"} {
					path, err := filepath.Abs("../query/testdata/candidate-index/" + name)
					if err != nil {
						t.Fatal(err)
					}
					id := []string{"g0", "g1"}[i]
					if _, err := r.Load(id, path, ""); err != nil {
						t.Fatal(err)
					}
				}
				guard, err := NewGuard(4, 5*time.Second)
				if err != nil {
					t.Fatal(err)
				}
				defer guard.Close()
				h := (&Server{Registry: r, Guard: guard}).Handler()
				q := "MATCH (n) WHERE n.caller_name CONTAINS 'other' RETURN n.caller_name AS x LIMIT 1"
				if shape == "and-route" {
					q = "MATCH (n) WHERE (n.graphId='g0' OR n.graphId='missing') AND n.caller_name CONTAINS 'other' RETURN n.caller_name AS x ORDER BY x LIMIT 1"
				}
				body := map[string]any{"query": q}
				endpoint := "/api/cypher"
				if selection != "root" {
					endpoint += "/graphs"
					if selection == "selected" {
						body["graphs"] = []string{"g0", "g1"}
					} else {
						body["allGraphs"] = true
					}
				}
				raw, err := json.Marshal(body)
				if err != nil {
					t.Fatal(err)
				}
				status := 200
				if selection == "selected" && shape == "and-route" {
					status = 400
				}
				response := responseJSON(t, request(t, h, "POST", endpoint, string(raw), status))
				if status == 400 {
					want := map[string]any{"error": "Index (2147483647) is greater than or equal to list size (9)", "code": "cypher_query_failed"}
					if !reflect.DeepEqual(response, want) {
						t.Fatalf("error body: %v", response)
					}
				} else {
					want := map[string]any{"columns": []any{"x"}, "rows": []any{map[string]any{"x": "other", "$metadata": map[string]any{"graphIds": []any{"g0"}}}}, "rowCount": float64(1), "graphCount": float64(2)}
					if selection != "root" {
						want["mode"] = "cross-graph"
						want["graphs"] = []any{"g0", "g1"}
						want["limit"] = float64(1000)
					}
					if !reflect.DeepEqual(response, want) {
						t.Fatalf("body got %v want %v", response, want)
					}
				}
				leases, err := r.AcquireSelected(nil)
				if err != nil {
					t.Fatal(err)
				}
				defer closeLeases(leases)
				for i, lease := range leases {
					g := lease.Graph.(*NativeGraph).Store
					retained, err := g.RetainedDistinctStringIndex(context.Background())
					if err != nil {
						t.Fatal(err)
					}
					_, mapped, err := g.InitializedProjectionView(context.Background())
					if err != nil {
						t.Fatal(err)
					}
					wantRetained := i == 0 && ((shape == "ordinary" && selection == "selected") || (shape == "and-route" && selection != "selected"))
					if retained != wantRetained || mapped {
						t.Fatalf("source %s retained=%v mapped=%v wantRetained=%v", lease.ID, retained, mapped, wantRetained)
					}
				}
			})
		}
	}
}
