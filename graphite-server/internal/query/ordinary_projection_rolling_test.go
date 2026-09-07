package query

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func ordinaryNoHitFixture(t *testing.T) string {
	t.Helper()
	dir := ordinaryCopyFixture(t, "testdata/candidate-index/clean")
	data, err := os.ReadFile(filepath.Join(dir, "graph.nodedata"))
	if err != nil {
		t.Fatal(err)
	}
	offsets, err := os.ReadFile(filepath.Join(dir, "graph.nodeoffsets"))
	if err != nil {
		t.Fatal(err)
	}
	for at := 8; at < len(offsets); at += 8 {
		offset := int64(binary.BigEndian.Uint64(offsets[at:])) - 1
		if offset >= 0 && data[offset+4] == 12 {
			binary.BigEndian.PutUint32(data[offset+9:], 0)
		}
	}
	if err = os.WriteFile(filepath.Join(dir, "graph.nodedata"), data, 0600); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"graph.callsite-string-index", "graph.callsite-string-content.identity"} {
		if err = os.Remove(filepath.Join(dir, name)); err != nil {
			t.Fatal(err)
		}
	}
	return dir
}
func ordinaryObservedResponse(observation map[string]any) map[string]any {
	out := map[string]any{}
	for _, key := range []string{"query", "columns", "rows", "error", "message"} {
		if value, ok := observation[key]; ok {
			out[key] = value
		}
	}
	return out
}
func TestOrdinaryProjectionRollingSourcesMain(t *testing.T) {
	// Full diagnostic states remain in the main JSON. Speculative suffix state
	// may differ between valid schedules; every repeated response must be exact.
	var first []map[string]any
	readDistinctJSON(t, "testdata/ordinary-projection/rolling-main-0.json", &first)
	for repeat := 1; repeat < 3; repeat++ {
		var later []map[string]any
		readDistinctJSON(t, fmt.Sprintf("testdata/ordinary-projection/rolling-main-%d.json", repeat), &later)
		for i, spec := range later {
			for j, target := range spec["targets"].([]any) {
				if !reflect.DeepEqual(ordinaryObservedResponse(target.(map[string]any)), ordinaryObservedResponse(first[i]["targets"].([]any)[j].(map[string]any))) {
					t.Fatalf("main response not stable: process %d case %d request %d", repeat, i, j)
				}
			}
		}
	}
	dirs := map[string]string{"clean": ordinaryCopyFixture(t, "testdata/candidate-index/clean"), "bad": ordinaryCopyFixture(t, "testdata/candidate-index/bad-matched"), "nohit": ordinaryNoHitFixture(t)}
	output := []map[string]any{}
	for _, spec := range first {
		t.Run(fmt.Sprintf("%v/%s/%s", spec["count"], spec["scenario"], spec["projection"]), func(t *testing.T) {
			sources := []Graph{}
			for i, name := range spec["fixtures"].([]any) {
				g, err := store.OpenMode(dirs[name.(string)], "MAPPED")
				if err != nil {
					t.Fatal(err)
				}
				sources = append(sources, Graph{fmt.Sprintf("g%d", i), g})
			}
			actual := map[string]any{"count": spec["count"], "scenario": spec["scenario"], "projection": spec["projection"], "fixtures": spec["fixtures"]}
			targets := []map[string]any{}
			for _, target := range spec["targets"].([]any) {
				expected := target.(map[string]any)
				observed := ordinaryHistoryResult(t, sources, true, expected["query"].(string))
				targets = append(targets, observed)
				if !reflect.DeepEqual(ordinaryObservedResponse(observed), ordinaryObservedResponse(expected)) {
					t.Errorf("main=%s native=%s", mustJSON(expected), mustJSON(observed))
				}
			}
			actual["targets"] = targets
			output = append(output, actual)
			for _, source := range sources {
				if err := source.Store.Close(); err != nil {
					t.Fatal(err)
				}
				if _, _, err := source.Store.RetainedProjectionIndex(context.Background()); err != store.ErrStoreClosed {
					t.Errorf("closed source still readable: %v", err)
				}
			}
		})
	}
	if dir := os.Getenv("ORDINARY_HISTORY_OUTPUT"); dir != "" {
		if err := os.MkdirAll(dir, 0700); err != nil {
			t.Fatal(err)
		}
		data, _ := json.MarshalIndent(output, "", "  ")
		if err := os.WriteFile(filepath.Join(dir, "rolling-native.json"), append(data, '\n'), 0600); err != nil {
			t.Fatal(err)
		}
	}
}
