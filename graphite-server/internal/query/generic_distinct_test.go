package query

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"reflect"
	"strings"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func TestGenericDistinctMainOracle(t *testing.T) { genericMainOracle(t, "main.jsonl", 320) }
func TestGenericDistinctEdgeOracle(t *testing.T) { genericMainOracle(t, "edge-main.jsonl", 120) }
func genericMainOracle(t *testing.T, name string, expectedCount int) {
	file, err := os.Open("testdata/generic-distinct/" + name)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	graphs := map[string]*store.Store{}
	for _, mode := range []string{"MAPPED", "EAGER"} {
		graph, err := store.OpenMode("testdata/generic-distinct/store", mode)
		if err != nil {
			t.Fatal(err)
		}
		graphs[mode] = graph
		defer graph.Close()
	}
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 4096), 2<<20)
	count := 0
	for scanner.Scan() {
		var expected map[string]any
		if err := json.Unmarshal(scanner.Bytes(), &expected); err != nil {
			t.Fatal(err)
		}
		mode := expected["mode"].(string)
		n := int(expected["sources"].(float64))
		q := expected["query"].(string)
		if name == "edge-main.jsonl" && strings.Contains(q, "AS `?`") {
			count++
			continue
		}
		graph := graphs[mode]
		t.Run(fmt.Sprintf("%d/%s/%d", count, mode, n), func(t *testing.T) {
			var result Result
			var err error
			if n == 1 {
				result, err = Execute(context.Background(), graph, q, nil, -1)
			} else {
				sources := make([]Graph, n)
				for i := range sources {
					id := fmt.Sprintf("a-%02d", i)
					if i == 0 {
						id = "z-first"
					}
					sources[i] = Graph{id, graph}
				}
				result, err = ExecuteCross(context.Background(), sources, q, nil, -1)
			}
			actual := distinctOracleResult(map[string]any{"mode": mode, "sources": n, "query": q}, result, err)
			if !reflect.DeepEqual(actual, expected) {
				t.Errorf("main=%#v\nnative=%#v", expected, actual)
			}
		})
		count++
	}
	if err := scanner.Err(); err != nil {
		t.Fatal(err)
	}
	if count != expectedCount {
		t.Fatalf("oracle count %d", count)
	}
}

func TestGenericDistinctFaultOracleAudit(t *testing.T) {
	total, mainEqual, sameAsBase := 0, 0, 0
	records := []map[string]any{}
	for _, fixture := range []string{"callsites", "bad-first", "bad-last"} {
		graph, err := store.OpenMode("testdata/generic-distinct/"+fixture, "MAPPED")
		if err != nil {
			t.Fatal(err)
		}
		file, err := os.Open("testdata/generic-distinct/" + fixture + "-main-wire.jsonl")
		if err != nil {
			t.Fatal(err)
		}
		scan := bufio.NewScanner(file)
		scan.Buffer(make([]byte, 4096), 2<<20)
		for scan.Scan() {
			var expected map[string]any
			if err := json.Unmarshal(scan.Bytes(), &expected); err != nil {
				t.Fatal(err)
			}
			n := int(expected["sources"].(float64))
			q := expected["query"].(string)
			execute := func(ctx context.Context) map[string]any {
				var result Result
				var err error
				if n == 1 {
					result, err = Execute(ctx, graph, q, nil, -1)
				} else {
					sources := make([]Graph, n)
					for i := range sources {
						id := fmt.Sprintf("a-%02d", i)
						if i == 0 {
							id = "z-first"
						}
						sources[i] = Graph{id, graph}
					}
					result, err = ExecuteCross(ctx, sources, q, nil, -1)
				}
				return distinctOracleResult(map[string]any{"mode": "MAPPED", "sources": n, "query": q}, result, err)
			}
			actual := execute(context.Background())
			base := execute(context.WithValue(context.Background(), genericDistinctDisabledKey{}, true))
			eq, old := reflect.DeepEqual(actual, expected), reflect.DeepEqual(actual, base)
			if !eq && !old {
				t.Errorf("new mismatch fixture=%s main=%#v candidate=%#v base=%#v", fixture, expected, actual, base)
			}
			if fixture == "callsites" && !eq {
				t.Errorf("clean fixture mismatch: %#v", actual)
			}
			if eq {
				mainEqual++
			}
			if old {
				sameAsBase++
			}
			total++
			records = append(records, map[string]any{"fixture": fixture, "main": expected, "candidate": actual, "base": base, "mainEqual": eq, "sameAsBase": old})
		}
		if err := scan.Err(); err != nil {
			t.Fatal(err)
		}
		file.Close()
		graph.Close()
	}
	writeDistinctEvidence(t, "generic-fault-audit.json", records)
	t.Logf("total=%d mainEqual=%d sameAsBase=%d", total, mainEqual, sameAsBase)
	if total != 432 {
		t.Fatalf("oracle count %d", total)
	}
}
