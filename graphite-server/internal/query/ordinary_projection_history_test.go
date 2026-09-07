package query

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func ordinaryHistoryState(t *testing.T, sources []Graph) []map[string]any {
	t.Helper()
	out := []map[string]any{}
	for _, source := range sources {
		retained, err := source.Store.RetainedDistinctStringIndex(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		_, mapped, err := source.Store.InitializedProjectionView(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		out = append(out, map[string]any{"id": source.ID, "retained": retained, "mappedView": mapped})
	}
	return out
}
func ordinaryHistoryResult(t *testing.T, sources []Graph, cross bool, q string) map[string]any {
	t.Helper()
	out := map[string]any{"query": q, "before": ordinaryHistoryState(t, sources)}
	var result Result
	var err error
	if cross {
		result, err = ExecuteCross(context.Background(), sources, q, nil, -1)
	} else {
		result, err = Execute(context.Background(), sources[0].Store, q, nil, -1)
	}
	out = distinctOracleResult(out, result, err)
	out["after"] = ordinaryHistoryState(t, sources)
	return out
}
func TestOrdinaryProjectionHistoryMain(t *testing.T) {
	var cases []map[string]any
	readDistinctJSON(t, "testdata/ordinary-projection/history-main-0.json", &cases)
	outputs := []map[string]any{}
	for _, spec := range cases {
		scenario := spec["scenario"].(string)
		warm := spec["warm"].(string)
		t.Run(scenario+"/"+warm, func(t *testing.T) {
			cross := scenario != "scoped-bad"
			cleanFirst := scenario == "cross-clean-bad" || scenario == "graphscope-clean-bad"
			a, b := candidateGraph(t, "bad-matched"), candidateGraph(t, "clean")
			if cleanFirst {
				a, b = b, a
			}
			sources := []Graph{{"single", a}}
			if cross {
				sources = []Graph{{"a", a}, {"b", b}}
			}
			actual := map[string]any{"scenario": scenario, "warm": warm, "initial": ordinaryHistoryState(t, sources)}
			if input, ok := spec["warmResult"].(map[string]any); ok {
				actual["warmResult"] = ordinaryHistoryResult(t, sources, cross, input["query"].(string))
			}
			targets := []map[string]any{}
			for _, target := range spec["targets"].([]any) {
				targets = append(targets, ordinaryHistoryResult(t, sources, cross, target.(map[string]any)["query"].(string)))
			}
			actual["targets"] = targets
			raw, _ := json.Marshal(actual)
			var normalized map[string]any
			_ = json.Unmarshal(raw, &normalized)
			outputs = append(outputs, normalized)
			if !reflect.DeepEqual(normalized, spec) {
				t.Errorf("main=%s\nnative=%s", mustJSON(spec), mustJSON(normalized))
			}
		})
	}
	if dir := os.Getenv("ORDINARY_HISTORY_OUTPUT"); dir != "" {
		if err := os.MkdirAll(dir, 0700); err != nil {
			t.Fatal(err)
		}
		raw, err := json.MarshalIndent(outputs, "", "  ")
		if err != nil {
			t.Fatal(err)
		}
		if err = os.WriteFile(filepath.Join(dir, "history-native.json"), append(raw, '\n'), 0600); err != nil {
			t.Fatal(err)
		}
	}
}

func TestOrdinaryProjectionWideHistoryMain(t *testing.T) {
	var cases []map[string]any
	readDistinctJSON(t, "testdata/ordinary-projection/wide-history-main-0.json", &cases)
	output := []map[string]any{}
	for _, spec := range cases {
		t.Run(spec["scenario"].(string)+"/"+spec["warm"].(string), func(t *testing.T) {
			scenario := spec["scenario"].(string)
			sources := make([]Graph, 40)
			for i := range sources {
				bad := i == 0
				if scenario == "bad-last" {
					bad = i == 39
				}
				fixture := "clean"
				if bad {
					fixture = "bad-matched"
				}
				sources[i] = Graph{fmt.Sprintf("g%d", i), candidateGraph(t, fixture)}
			}
			actual := map[string]any{"scenario": scenario, "warm": spec["warm"], "initial": ordinaryHistoryState(t, sources)}
			if warm, ok := spec["warmResult"].(map[string]any); ok {
				actual["warmResult"] = ordinaryHistoryResult(t, sources, true, warm["query"].(string))
			}
			targets := []map[string]any{}
			for _, target := range spec["targets"].([]any) {
				targets = append(targets, ordinaryHistoryResult(t, sources, true, target.(map[string]any)["query"].(string)))
			}
			actual["targets"] = targets
			raw, _ := json.Marshal(actual)
			var normalized map[string]any
			_ = json.Unmarshal(raw, &normalized)
			output = append(output, normalized)
			if !reflect.DeepEqual(normalized, spec) {
				t.Errorf("main=%s\nnative=%s", mustJSON(spec), mustJSON(normalized))
			}
		})
	}
	if dir := os.Getenv("ORDINARY_HISTORY_OUTPUT"); dir != "" {
		if err := os.MkdirAll(dir, 0700); err != nil {
			t.Fatal(err)
		}
		raw, err := json.MarshalIndent(output, "", "  ")
		if err != nil {
			t.Fatal(err)
		}
		if err = os.WriteFile(filepath.Join(dir, "wide-history-native.json"), append(raw, '\n'), 0600); err != nil {
			t.Fatal(err)
		}
	}
}
func TestOrdinaryProjectionNilGraph(t *testing.T) {
	result, err := Execute(context.Background(), nil, "MATCH (n) WHERE n.caller_name='other' RETURN n.caller_name AS x LIMIT 1", nil, -1)
	if err != nil || !reflect.DeepEqual(result.Columns, []string{"x"}) || len(result.Rows) != 0 {
		t.Fatalf("result=%#v err=%v", result, err)
	}
}
