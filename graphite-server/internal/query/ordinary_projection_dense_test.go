package query

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func ordinaryCopyFixture(t *testing.T, from string) string {
	t.Helper()
	to := t.TempDir()
	entries, err := os.ReadDir(from)
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		data, err := os.ReadFile(filepath.Join(from, entry.Name()))
		if err != nil {
			t.Fatal(err)
		}
		if err = os.WriteFile(filepath.Join(to, entry.Name()), data, 0600); err != nil {
			t.Fatal(err)
		}
	}
	return to
}
func TestOrdinaryProjectionDenseHistoryMain(t *testing.T) {
	for _, name := range []string{"clean", "clean-missing", "return-sid", "return-sid-missing", "callee-sid", "callee-sid-missing"} {
		t.Run(name, func(t *testing.T) {
			dir := ordinaryCopyFixture(t, "testdata/indexed-distinct/split-clean")
			if strings.HasSuffix(name, "-missing") {
				if err := os.Remove(filepath.Join(dir, "graph.callsite-string-index")); err != nil {
					t.Fatal(err)
				}
			}
			if !strings.HasPrefix(name, "clean") {
				offsets, err := os.ReadFile(filepath.Join(dir, "graph.nodeoffsets"))
				if err != nil {
					t.Fatal(err)
				}
				data, err := os.ReadFile(filepath.Join(dir, "graph.nodedata"))
				if err != nil {
					t.Fatal(err)
				}
				offset := int(binary.BigEndian.Uint64(offsets[16:])) - 1
				field := 33
				if strings.HasPrefix(name, "callee") {
					field = 21
				}
				binary.BigEndian.PutUint32(data[offset+field:], 2147483647)
				if err = os.WriteFile(filepath.Join(dir, "graph.nodedata"), data, 0600); err != nil {
					t.Fatal(err)
				}
			}
			var specs []map[string]any
			readDistinctJSON(t, "testdata/ordinary-projection/dense-"+name+"-main.json", &specs)
			outputs := []map[string]any{}
			for _, spec := range specs {
				g, err := store.OpenMode(dir, "MAPPED")
				if err != nil {
					t.Fatal(err)
				}
				sources := []Graph{{"single", g}}
				actual := map[string]any{"mode": spec["mode"], "initial": ordinaryHistoryState(t, sources)}
				targets := []map[string]any{}
				for _, target := range spec["targets"].([]any) {
					targets = append(targets, ordinaryHistoryResult(t, sources, false, target.(map[string]any)["query"].(string)))
				}
				actual["targets"] = targets
				exists, err := g.PreparedProjectionFile(context.Background())
				if err != nil {
					t.Fatal(err)
				}
				actual["indexFileAfter"] = exists
				if err = g.Close(); err != nil {
					t.Fatal(err)
				}
				if output := os.Getenv("ORDINARY_FIXTURE_OUTPUT"); output != "" && spec["mode"] == "complete-miss" {
					to := filepath.Join(output, name)
					if err := os.MkdirAll(to, 0700); err != nil {
						t.Fatal(err)
					}
					entries, err := os.ReadDir(dir)
					if err != nil {
						t.Fatal(err)
					}
					for _, entry := range entries {
						data, err := os.ReadFile(filepath.Join(dir, entry.Name()))
						if err != nil {
							t.Fatal(err)
						}
						if err = os.WriteFile(filepath.Join(to, entry.Name()), data, 0600); err != nil {
							t.Fatal(err)
						}
					}
				}
				raw, _ := json.Marshal(actual)
				var normalized map[string]any
				_ = json.Unmarshal(raw, &normalized)
				outputs = append(outputs, normalized)
				if !reflect.DeepEqual(spec, normalized) {
					t.Errorf("mode %s main=%s native=%s", spec["mode"], mustJSON(spec), mustJSON(normalized))
				}
			}
			if output := os.Getenv("ORDINARY_HISTORY_OUTPUT"); output != "" {
				if err := os.MkdirAll(output, 0700); err != nil {
					t.Fatal(err)
				}
				data, _ := json.MarshalIndent(outputs, "", "  ")
				if err := os.WriteFile(filepath.Join(output, "dense-"+name+"-native.json"), append(data, '\n'), 0600); err != nil {
					t.Fatal(err)
				}
			}
		})
	}
}
