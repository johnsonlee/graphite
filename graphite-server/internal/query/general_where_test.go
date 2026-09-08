package query

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

// Main fully materializes non-optional MATCH candidates before evaluating WHERE.
// The malformed final IntConstant distinguishes that ordering from per-row WHERE.
func TestUnknownLabelGeneralWhereActualMain(t *testing.T) {
	root := "../../../docs/go-server-baseline/native-unknown-label-routing"
	if input := os.Getenv("UNKNOWN_LABEL_INPUT_DIR"); input != "" {
		root = input
	}
	var specs, oracle, mutationRecords []map[string]any
	var fixture map[string]any
	readDistinctJSON(t, filepath.Join(root, "general-where-cases.json"), &specs)
	readDistinctJSON(t, filepath.Join(root, "general-where-main.json"), &oracle)
	readDistinctJSON(t, filepath.Join(root, "general-where-mutations.json"), &mutationRecords)
	readDistinctJSON(t, filepath.Join(root, "general-where-source-fixture.json"), &fixture)
	if len(specs) != 8 || len(oracle) != 8 || len(mutationRecords) != 4 {
		t.Fatal("incomplete general WHERE corpus")
	}
	files := map[string]map[string]any{}
	for _, file := range fixture["files"].([]any) {
		entry := file.(map[string]any)
		if files[entry["file"].(string)] != nil {
			t.Fatal("duplicate fixture file", entry)
		}
		files[entry["file"].(string)] = entry
	}
	mutations := map[string]map[string]any{}
	for _, entry := range mutationRecords {
		file := entry["file"].(string)
		if mutations[file] != nil {
			t.Fatal("duplicate mutation", file)
		}
		mutations[file] = entry
	}
	seen := map[string]bool{}
	outputs := []map[string]any{}
	for i, spec := range specs {
		want := oracle[i]
		name := spec["name"].(string)
		if seen[name] || want["name"] != name || !reflect.DeepEqual(want["spec"], spec) || spec["fixture"] != "node-encounter/mixed-sparse" || spec["cross"] != false {
			t.Fatal("oracle inventory/order mismatch", i)
		}
		seen[name] = true
		t.Run(name, func(t *testing.T) {
			got := unknownLabelObserve(t, spec, false, files, mutations, root, nil)
			outputs = append(outputs, got)
			unknownLabelAssert(t, got, want)
			aux := unknownLabelObserve(t, spec, true, files, mutations, root, nil)
			got["matchedContextBoundary"] = aux
			unknownLabelAssert(t, aux, want)
		})
	}
	if len(outputs) != 8 {
		t.Fatal("incomplete native general WHERE observations", len(outputs))
	}
	if output := os.Getenv("GENERAL_WHERE_OUTPUT"); output != "" {
		data, err := json.MarshalIndent(outputs, "", "  ")
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(output, append(data, '\n'), 0600); err != nil {
			t.Fatal(err)
		}
	}
}
