package query

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"reflect"
	"runtime"
	"testing"
)

func TestRequestSourceScopeMain(t *testing.T) {
	var cases []struct {
		Count      int              `json:"count"`
		Scoped     bool             `json:"scoped"`
		BadLast    bool             `json:"badLast"`
		Name       string           `json:"name"`
		Query      string           `json:"query"`
		Parameters map[string]any   `json:"parameters"`
		Repeats    []map[string]any `json:"repeats"`
	}
	readDistinctJSON(t, "testdata/request-source-scope/main.json", &cases)
	outputs := []map[string]any{}
	if len(cases) != 160 {
		t.Fatal("incomplete actual-main scenario matrix")
	}
	for _, c := range cases {
		t.Run(fmt.Sprintf("%d/scoped=%v/badLast=%v/%s", c.Count, c.Scoped, c.BadLast, c.Name), func(t *testing.T) {
			sources := make([]Graph, c.Count)
			for i := range sources {
				name := "clean"
				if c.BadLast && i == len(sources)-1 {
					name = "bad-matched"
				}
				sources[i] = Graph{fmt.Sprintf("g%d", i), candidateGraph(t, name)}
			}
			record := map[string]any{"count": c.Count, "scoped": c.Scoped, "badLast": c.BadLast, "name": c.Name, "query": c.Query, "parameters": c.Parameters}
			repeats := []map[string]any{}
			var previous map[string]any
			for i, want := range c.Repeats {
				actual := map[string]any{"before": ordinaryHistoryState(t, sources)}
				result, err := ExecuteCrossWithOptions(context.Background(), sources, c.Query, c.Parameters, -1, ExecutionOptions{SourceScopeApplied: c.Scoped, WorkTrackingEnabled: true})
				actual = distinctOracleResult(actual, result, err)
				actual["after"] = ordinaryHistoryState(t, sources)
				raw, _ := json.Marshal(actual)
				var normalized map[string]any
				if err := json.Unmarshal(raw, &normalized); err != nil {
					t.Fatal(err)
				}
				repeats = append(repeats, normalized)
				cancelledWave := c.BadLast && (c.Count == 40 || c.Count == 64) && (c.Name == "ordered" || c.Scoped && c.Name == "and-route")
				parallel := min(c.Count, max(1, runtime.NumCPU()/2))
				if err := requestScopeComparison(normalized, want, previous, c.Count, parallel, cancelledWave); err != nil {
					t.Errorf("repeat%d: %v; full values retained in REQUEST_SCOPE_OUTPUT", i, err)
				}
				previous = normalized
			}
			record["repeats"] = repeats
			outputs = append(outputs, record)
		})
	}
	if path := os.Getenv("REQUEST_SCOPE_OUTPUT"); path != "" {
		raw, err := json.MarshalIndent(outputs, "", "  ")
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, append(raw, '\n'), 0600); err != nil {
			t.Fatal(err)
		}
	}
}

// The main scheduling oracle separately proves that an ORDER failure cancels
// unfinished siblings in the final wave. Their retained bit is a lifecycle
// outcome, not a fixed snapshot. All public fields remain exact; all other
// scenarios retain the complete original state comparison.
func requestScopeComparison(actual, want, previous map[string]any, count, parallel int, cancelledWave bool) error {
	if !cancelledWave {
		if !reflect.DeepEqual(actual, want) {
			return fmt.Errorf("response or deterministic source state differs from main")
		}
		return nil
	}
	public := func(record map[string]any) map[string]any {
		out := map[string]any{}
		for key, value := range record {
			if key != "before" && key != "after" {
				out[key] = value
			}
		}
		return out
	}
	if !reflect.DeepEqual(public(actual), public(want)) || want["error"] != "IndexOutOfBoundsException" {
		return fmt.Errorf("public response differs from main's original source failure")
	}
	before, bok := actual["before"].([]any)
	after, aok := actual["after"].([]any)
	if !bok || !aok || len(before) != count || len(after) != count || len(actual) != len(want) {
		return fmt.Errorf("incomplete source state")
	}
	if previous == nil {
		if !reflect.DeepEqual(before, want["before"]) {
			return fmt.Errorf("initial source state differs from main")
		}
	} else if !reflect.DeepEqual(before, previous["after"]) {
		return fmt.Errorf("source state changed between joined queries")
	}
	waveStart := ((count - 1) / parallel) * parallel
	for i := range after {
		b, bok := before[i].(map[string]any)
		a, aok := after[i].(map[string]any)
		if !bok || !aok || len(b) != 3 || len(a) != 3 || a["id"] != fmt.Sprintf("g%d", i) || b["id"] != a["id"] || a["mappedView"] != false || b["mappedView"] != false {
			return fmt.Errorf("source%d identity or mapped-view policy differs", i)
		}
		retained, rok := a["retained"].(bool)
		wasRetained, wok := b["retained"].(bool)
		if !rok || !wok || wasRetained && !retained {
			return fmt.Errorf("source%d lost an initialized retained index", i)
		}
		if (i < waveStart || i == count-1) && !retained {
			return fmt.Errorf("source%d required retained index missing", i)
		}
	}
	return nil
}

func TestRequestSourceScopeStateComparisonRejectsUnprovenChanges(t *testing.T) {
	var cases []struct {
		Count   int              `json:"count"`
		Scoped  bool             `json:"scoped"`
		BadLast bool             `json:"badLast"`
		Name    string           `json:"name"`
		Repeats []map[string]any `json:"repeats"`
	}
	readDistinctJSON(t, "testdata/request-source-scope/main.json", &cases)
	var want map[string]any
	for _, c := range cases {
		if c.Count == 64 && c.Scoped && c.BadLast && c.Name == "ordered" {
			want = c.Repeats[0]
		}
	}
	if want == nil {
		t.Fatal("missing actual-main control")
	}
	clone := func(v map[string]any) map[string]any {
		raw, err := json.Marshal(v)
		if err != nil {
			t.Fatal(err)
		}
		var out map[string]any
		if err := json.Unmarshal(raw, &out); err != nil {
			t.Fatal(err)
		}
		return out
	}
	for _, test := range []struct {
		name     string
		mutate   func(map[string]any)
		accepted bool
	}{
		{"exact", func(map[string]any) {}, true},
		{"canceled-sibling", func(v map[string]any) { v["after"].([]any)[56].(map[string]any)["retained"] = false }, true},
		{"completed-prefix", func(v map[string]any) { v["after"].([]any)[55].(map[string]any)["retained"] = false }, false},
		{"failing-source", func(v map[string]any) { v["after"].([]any)[63].(map[string]any)["retained"] = false }, false},
		{"mapped-view", func(v map[string]any) { v["after"].([]any)[56].(map[string]any)["mappedView"] = true }, false},
		{"wrong-source", func(v map[string]any) { v["after"].([]any)[56].(map[string]any)["id"] = "g57" }, false},
		{"wrong-error", func(v map[string]any) { v["error"] = "CypherQueryCancelledException" }, false},
		{"wrong-message", func(v map[string]any) { v["message"] = "canceled" }, false},
		{"partial-result", func(v map[string]any) { v["rows"] = []any{} }, false},
		{"missing-state", func(v map[string]any) { delete(v, "after") }, false},
		{"nonboolean-state", func(v map[string]any) { v["after"].([]any)[56].(map[string]any)["retained"] = nil }, false},
		{"initial-state", func(v map[string]any) { v["before"].([]any)[56].(map[string]any)["retained"] = true }, false},
	} {
		t.Run(test.name, func(t *testing.T) {
			actual := clone(want)
			test.mutate(actual)
			err := requestScopeComparison(actual, want, nil, 64, 8, true)
			if (err == nil) != test.accepted {
				t.Fatalf("accepted=%v err=%v", test.accepted, err)
			}
			if test.name == "canceled-sibling" && requestScopeComparison(actual, want, nil, 64, 8, false) == nil {
				t.Fatal("deterministic case accepted a changed state")
			}
		})
	}
	previous := clone(want)
	next := clone(want)
	next["before"] = clone(previous)["after"]
	if err := requestScopeComparison(next, want, previous, 64, 8, true); err != nil {
		t.Fatal(err)
	}
	next["after"].([]any)[56].(map[string]any)["retained"] = false
	if requestScopeComparison(next, want, previous, 64, 8, true) == nil {
		t.Fatal("accepted lost retained index on repeat")
	}
	next = clone(want)
	if requestScopeComparison(next, want, previous, 64, 8, true) == nil {
		t.Fatal("accepted state change between queries")
	}
}
