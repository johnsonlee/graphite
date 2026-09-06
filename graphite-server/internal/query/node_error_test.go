package query

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// Java exceptions belong at query/HTTP boundaries. Direct Store reads retain
// their diagnostic contract, but the load/cache/consumption lifecycle is shared.
func TestNodeTagLoadLifecycleMainOracle(t *testing.T) {
	var records []map[string]any
	data, err := os.ReadFile("../server/testdata/node-tag/main-lifecycle.json")
	if err != nil {
		t.Fatal(err)
	}
	if err = json.Unmarshal(data, &records); err != nil {
		t.Fatal(err)
	}
	if len(records) != 72 {
		t.Fatalf("incomplete oracle: %d", len(records))
	}
	checked := 0
	for _, tag := range []byte{16, 127, 128, 255} {
		for _, mode := range []string{"MAPPED", "EAGER", "AUTO"} {
			for _, phase := range []string{"before", "after"} {
				t.Run(fmt.Sprintf("%d/%s/%s", tag, mode, phase), func(t *testing.T) {
					fixture := t.TempDir()
					entries, err := os.ReadDir("testdata/traversal")
					if err != nil {
						t.Fatal(err)
					}
					for _, entry := range entries {
						data, err := os.ReadFile(filepath.Join("testdata/traversal", entry.Name()))
						if err != nil {
							t.Fatal(err)
						}
						if err = os.WriteFile(filepath.Join(fixture, entry.Name()), data, 0600); err != nil {
							t.Fatal(err)
						}
					}
					index, err := os.ReadFile("../server/testdata/node-tag/prepared.nodeindex")
					if err != nil {
						t.Fatal(err)
					}
					if err = os.WriteFile(filepath.Join(fixture, "graph.nodeindex"), index, 0600); err != nil {
						t.Fatal(err)
					}
					mutate := func() {
						file, err := os.OpenFile(filepath.Join(fixture, "graph.nodedata"), os.O_WRONLY, 0)
						if err != nil {
							t.Fatal(err)
						}
						_, err = file.WriteAt([]byte{tag}, 75)
						closeErr := file.Close()
						if err != nil {
							t.Fatal(err)
						}
						if closeErr != nil {
							t.Fatal(closeErr)
						}
					}
					if phase == "before" {
						mutate()
					}
					graph, loadErr := store.OpenMode(fixture, mode)
					if graph != nil {
						defer graph.Close()
					}
					if phase == "after" {
						mutate()
					}
					for _, record := range records {
						if record["tag"] != float64(tag) || record["mode"] != mode || record["phase"] != phase {
							continue
						}
						checked++
						operation := record["operation"].(string)
						switch operation {
						case "load", "node":
							actualErr := loadErr
							var node store.Node
							if operation == "node" {
								if graph == nil {
									t.Fatal("missing graph")
								}
								node, actualErr = graph.Node(7)
							}
							if record["error"] != nil {
								var typed *store.UnknownNodeTagError
								if !errors.As(actualErr, &typed) || typed.Tag != tag {
									t.Fatalf("%s expected consumed tag %d: %v", operation, tag, actualErr)
								}
								if typed.Error() != fmt.Sprintf("unknown node tag %d", tag) {
									t.Fatal("Store diagnostic changed", typed)
								}
								if record["error"] != "IllegalArgumentException" || record["message"] != fmt.Sprintf("Unknown node tag: %d", int8(tag)) {
									t.Fatal("unexpected main oracle", record)
								}
							} else {
								if actualErr != nil {
									t.Fatal(operation, actualErr)
								}
								if operation == "node" && (node.ID != 7 || node.Kind != "IntConstant" || node.Value != int32(107)) {
									t.Fatalf("cached node changed: %#v", node)
								}
								if operation == "load" && (graph == nil || record["loaded"] != true) {
									t.Fatal("load failed", record)
								}
							}
						case "query", "cross":
							if graph == nil {
								t.Fatal("missing graph")
							}
							var result Result
							var err error
							source := "MATCH (n:IntConstant) RETURN n.id AS id LIMIT 8"
							if operation == "query" {
								result, err = Execute(context.Background(), graph, source, nil, -1)
							} else {
								result, err = ExecuteCross(context.Background(), []Graph{{ID: "a", Store: graph}, {ID: "b", Store: graph}}, source, nil, -1)
							}
							actual := map[string]any{"tag": float64(tag), "mode": mode, "phase": phase, "operation": operation}
							if err != nil {
								actual["message"] = err.Error()
								var qe *Error
								if !errors.As(err, &qe) {
									t.Fatal("unclassified error", err)
								}
								actual["error"] = qe.Class
							} else {
								actual["columns"] = result.Columns
								actual["rows"] = result.Rows
							}
							encoded, err := json.Marshal(actual)
							if err != nil {
								t.Fatal(err)
							}
							var normalized map[string]any
							if err = json.Unmarshal(encoded, &normalized); err != nil {
								t.Fatal(err)
							}
							if !reflect.DeepEqual(normalized, record) {
								t.Fatalf("main=%#v\nnative=%s", record, encoded)
							}
						default:
							t.Fatal("unknown operation", operation)
						}
					}
				})
			}
		}
	}
	if checked != 72 {
		t.Fatalf("checked %d/72", checked)
	}
}
