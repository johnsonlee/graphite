package query

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func distinctOracleResult(spec map[string]any, result Result, err error) map[string]any {
	out := map[string]any{}
	for k, v := range spec {
		out[k] = v
	}
	if err != nil {
		var e *Error
		if errors.As(err, &e) {
			out["error"], out["message"] = e.Class, e.JavaMessage()
		} else {
			out["error"], out["message"] = "GoError", err.Error()
		}
	} else {
		out["columns"], out["rows"] = result.Columns, result.Rows
	}
	raw, _ := json.Marshal(out)
	var normalized map[string]any
	_ = json.Unmarshal(raw, &normalized)
	return normalized
}
func readDistinctJSON(t *testing.T, path string, out any) {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if err = json.Unmarshal(data, out); err != nil {
		t.Fatal(err)
	}
}
func writeDistinctEvidence(t *testing.T, name string, values any) {
	t.Helper()
	if dir := os.Getenv("INDEXED_DISTINCT_OUTPUT"); dir != "" {
		if err := os.MkdirAll(dir, 0700); err != nil {
			t.Fatal(err)
		}
		data, err := json.MarshalIndent(values, "", "  ")
		if err != nil {
			t.Fatal(err)
		}
		if err = os.WriteFile(filepath.Join(dir, name), append(data, '\n'), 0600); err != nil {
			t.Fatal(err)
		}
	}
}
func TestIndexedDistinctRawAudit(t *testing.T) {
	for _, suite := range []string{"primary", "supplementary"} {
		t.Run(suite, func(t *testing.T) {
			dir := "testdata/indexed-distinct/audit-" + suite
			var cases []struct {
				Name, Query string
				Params      map[string]any
			}
			var oracle []map[string]any
			readDistinctJSON(t, dir+"/cases.json", &cases)
			readDistinctJSON(t, dir+"/main.json", &oracle)
			outputs := []map[string]any{}
			eligibility := []map[string]any{}
			position := 0
			for _, fixture := range []string{"clean", "bad-first-unmatched", "bad-last-unmatched", "bad-matched"} {
				graph := candidateGraph(t, fixture)
				for _, spec := range cases {
					for _, cross := range []bool{false, true} {
						expected := oracle[position]
						position++
						t.Run(fmt.Sprintf("%s/%s/%v", fixture, spec.Name, cross), func(t *testing.T) {
							var result Result
							var err error
							if cross {
								result, err = ExecuteCross(context.Background(), []Graph{{"a", graph}, {"b", graph}}, spec.Query, spec.Params, -1)
							} else {
								result, err = Execute(context.Background(), graph, spec.Query, spec.Params, -1)
							}
							actual := distinctOracleResult(map[string]any{"name": spec.Name, "query": spec.Query, "cross": cross, "empty": false, "fixture": fixture}, result, err)
							outputs = append(outputs, actual)
							ast, parseErr := cypher.Parse(spec.Query)
							if parseErr != nil {
								t.Fatal(parseErr)
							}
							e := evaluator{ctx: context.Background(), parameters: spec.Params, cross: cross}
							eligible := e.compileIndexedDistinct(ast.Branches[0]) != nil
							eligibility = append(eligibility, map[string]any{"fixture": fixture, "name": spec.Name, "cross": cross, "eligible": eligible, "ordinaryEligible": e.compileOrdinaryProjection(ast.Branches[0]) != nil})
							if eligible && !reflect.DeepEqual(actual, expected) {
								t.Fatalf("main=%#v\nnative=%#v", expected, actual)
							}
						})
					}
				}
			}
			writeDistinctEvidence(t, suite+"-native.json", outputs)
			writeDistinctEvidence(t, suite+"-eligibility.json", eligibility)
		})
	}
}
func TestIndexedDistinctRequiredReads(t *testing.T) {
	for _, suffix := range []string{"", "-missing-index", "-missing-identity"} {
		missing := suffix != ""
		var mutations []struct{ Name string }
		var cases []struct{ Name, Query string }
		var oracle []map[string]any
		readDistinctJSON(t, "testdata/indexed-distinct/required-mutations.json", &mutations)
		readDistinctJSON(t, "testdata/indexed-distinct/required-cases.json", &cases)
		readDistinctJSON(t, "testdata/indexed-distinct/required-main"+suffix+".json", &oracle)
		position := 0
		outputs := []map[string]any{}
		for _, mutation := range mutations {
			fixture := filepath.Join("testdata/indexed-distinct/required", mutation.Name)
			if missing {
				copyDir := t.TempDir()
				entries, err := os.ReadDir(fixture)
				if err != nil {
					t.Fatal(err)
				}
				for _, entry := range entries {
					if suffix == "-missing-index" && entry.Name() == "graph.callsite-string-index" || suffix == "-missing-identity" && entry.Name() == "graph.callsite-string-content.identity" {
						continue
					}
					data, err := os.ReadFile(filepath.Join(fixture, entry.Name()))
					if err != nil {
						t.Fatal(err)
					}
					if err = os.WriteFile(filepath.Join(copyDir, entry.Name()), data, 0600); err != nil {
						t.Fatal(err)
					}
				}
				fixture = copyDir
			}
			graph, err := store.OpenMode(fixture, "MAPPED")
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { graph.Close() })
			for _, spec := range cases {
				for _, cross := range []bool{false, true} {
					expected := oracle[position]
					position++
					t.Run(fmt.Sprintf("%s%s/%s/%v", mutation.Name, suffix, spec.Name, cross), func(t *testing.T) {
						var result Result
						var err error
						if cross {
							result, err = ExecuteCross(context.Background(), []Graph{{"a", graph}, {"b", graph}}, spec.Query, nil, -1)
						} else {
							result, err = Execute(context.Background(), graph, spec.Query, nil, -1)
						}
						actual := distinctOracleResult(map[string]any{"name": spec.Name, "query": spec.Query, "cross": cross, "empty": false, "mutation": mutation.Name}, result, err)
						outputs = append(outputs, actual)
						if !reflect.DeepEqual(actual, expected) {
							t.Fatalf("main=%#v\nnative=%#v", expected, actual)
						}
					})
				}
			}
		}
		writeDistinctEvidence(t, "required"+suffix+"-native.json", outputs)
	}
}

func TestIndexedDistinctAdditionalMain(t *testing.T) {
	var cases []struct {
		Name, Query string
		Params      map[string]any
		Eligible    bool
	}
	readDistinctJSON(t, "testdata/indexed-distinct/additional-cases.json", &cases)
	for _, fixture := range []string{"annotation", "mixed", "annotation-after"} {
		var g *store.Store
		if fixture == "annotation-after" {
			var err error
			g, err = store.OpenMode(ordinaryCopyFixture(t, "testdata/indexed-distinct/annotation-after"), "MAPPED")
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { g.Close() })
		} else {
			g = candidateGraph(t, fixture)
		}
		var oracle []map[string]any
		readDistinctJSON(t, "testdata/indexed-distinct/"+fixture+"-wire-main.json", &oracle)
		outputs := []map[string]any{}
		for i, c := range cases {
			for j, cross := range []bool{false, true} {
				t.Run(fmt.Sprintf("%s/%s/%v", fixture, c.Name, cross), func(t *testing.T) {
					ast, err := cypher.Parse(c.Query)
					if err != nil {
						t.Fatal(err)
					}
					e := evaluator{ctx: context.Background(), parameters: c.Params, cross: cross}
					eligible := e.compileIndexedDistinct(ast.Branches[0]) != nil
					if eligible != c.Eligible {
						t.Fatalf("eligible=%v want %v", eligible, c.Eligible)
					}
					var result Result
					if cross {
						result, err = ExecuteCross(context.Background(), []Graph{{"a", g}, {"b", g}}, c.Query, c.Params, -1)
					} else {
						result, err = Execute(context.Background(), g, c.Query, c.Params, -1)
					}
					actual := distinctOracleResult(map[string]any{"name": c.Name, "query": c.Query, "cross": cross, "empty": false}, result, err)
					outputs = append(outputs, actual)
					if c.Eligible && !reflect.DeepEqual(actual, oracle[i*2+j]) {
						t.Fatalf("main=%#v\nnative=%#v", oracle[i*2+j], actual)
					}
				})
			}
		}
		writeDistinctEvidence(t, fixture+"-native.json", outputs)
	}
}

func TestIndexedDistinctSourcePairAudit(t *testing.T) {
	var oracle []map[string]any
	readDistinctJSON(t, "testdata/indexed-distinct/audit-source-pairs/main.json", &oracle)
	good, bad := candidateGraph(t, "clean"), candidateGraph(t, "bad-matched")
	output := []map[string]any{}
	eligibility := []map[string]any{}
	for _, spec := range oracle {
		q := spec["query"].(string)
		badFirst := spec["badFirst"].(bool)
		sources := []Graph{{"a", good}, {"b", bad}}
		if badFirst {
			sources = []Graph{{"a", bad}, {"b", good}}
		}
		result, err := ExecuteCross(context.Background(), sources, q, nil, -1)
		actual := distinctOracleResult(map[string]any{"name": spec["name"], "query": q, "badFirst": badFirst}, result, err)
		output = append(output, actual)
		ast, parseErr := cypher.Parse(q)
		if parseErr != nil {
			t.Fatal(parseErr)
		}
		e := evaluator{ctx: context.Background(), cross: true}
		eligibility = append(eligibility, map[string]any{"eligible": e.compileIndexedDistinct(ast.Branches[0]) != nil, "ordinaryEligible": e.compileOrdinaryProjection(ast.Branches[0]) != nil})
		if e.compileIndexedDistinct(ast.Branches[0]) != nil && !reflect.DeepEqual(actual, spec) {
			t.Errorf("main=%#v\nnative=%#v", spec, actual)
		}
	}
	writeDistinctEvidence(t, "source-pairs-native.json", output)
	writeDistinctEvidence(t, "source-pairs-eligibility.json", eligibility)
}

func TestIndexedDistinctSplitStorageMain(t *testing.T) {
	for _, mode := range []string{"clean", "bad-count", "bad-tag", "bad-callee", "missing-index"} {
		dir := t.TempDir()
		entries, err := os.ReadDir("testdata/indexed-distinct/split-clean")
		if err != nil {
			t.Fatal(err)
		}
		for _, entry := range entries {
			data, err := os.ReadFile(filepath.Join("testdata/indexed-distinct/split-clean", entry.Name()))
			if err != nil {
				t.Fatal(err)
			}
			if err = os.WriteFile(filepath.Join(dir, entry.Name()), data, 0600); err != nil {
				t.Fatal(err)
			}
		}
		if mode == "missing-index" {
			if err = os.Remove(filepath.Join(dir, "graph.callsite-string-index")); err != nil {
				t.Fatal(err)
			}
		}
		if strings.HasPrefix(mode, "bad-") {
			data, err := os.ReadFile(filepath.Join(dir, "graph.nodedata"))
			if err != nil {
				t.Fatal(err)
			}
			offsets, err := os.ReadFile(filepath.Join(dir, "graph.nodeoffsets"))
			if err != nil {
				t.Fatal(err)
			}
			offset := int(binary.BigEndian.Uint64(offsets[8+3649*8:])) - 1
			switch mode {
			case "bad-tag":
				data[offset+4] = 255
			case "bad-count":
				binary.BigEndian.PutUint32(data[offset+13:], 2147483640)
			case "bad-callee":
				binary.BigEndian.PutUint32(data[offset+21:], 2147483647)
			}
			if err = os.WriteFile(filepath.Join(dir, "graph.nodedata"), data, 0600); err != nil {
				t.Fatal(err)
			}
		}
		graph, err := store.OpenMode(dir, "MAPPED")
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { graph.Close() })
		var oracle []map[string]any
		readDistinctJSON(t, "testdata/indexed-distinct/split-"+mode+"-main.json", &oracle)
		output := []map[string]any{}
		for _, expected := range oracle {
			count := int(expected["sources"].(float64))
			q := expected["query"].(string)
			t.Run(fmt.Sprintf("%s/%s/%d", mode, expected["name"], count), func(t *testing.T) {
				sources := make([]Graph, count)
				for i := range sources {
					sources[i] = Graph{fmt.Sprintf("g%d", i), graph}
				}
				result, err := ExecuteCross(context.Background(), sources, q, nil, -1)
				actual := distinctOracleResult(map[string]any{"name": expected["name"], "query": q, "sources": count}, result, err)
				output = append(output, actual)
				if !reflect.DeepEqual(actual, expected) {
					t.Fatalf("main=%#v\nnative=%#v", expected, actual)
				}
			})
		}
		writeDistinctEvidence(t, "split-"+mode+"-native.json", output)
	}
}

func TestDistinctOrderedTasksDoNotConsumeSpeculativeFailure(t *testing.T) {
	speculative := make(chan struct{})
	joined := make(chan struct{})
	consumed := []int{}
	runDistinctTasks(context.Background(), 3, 2, true, func(ctx context.Context, i int) int {
		switch i {
		case 0:
			<-speculative
			return 17
		case 1:
			defer close(joined)
			close(speculative)
			panic("later unconsumed source")
		default:
			t.Error("replenished after LIMIT")
			return 0
		}
	}, func(i, value int) bool { consumed = append(consumed, value); return true })
	<-joined
	if !reflect.DeepEqual(consumed, []int{17}) {
		t.Fatal(consumed)
	}
}
func TestDistinctUnorderedTasksConsumeSpeculativeFailure(t *testing.T) {
	ready := make(chan struct{})
	defer func() {
		if value := recover(); value != "later source" {
			t.Fatalf("failure=%v", value)
		}
	}()
	runDistinctTasks(context.Background(), 2, 2, false, func(ctx context.Context, i int) int {
		if i == 0 {
			<-ready
			return 17
		}
		close(ready)
		panic("later source")
	}, func(i, value int) bool { t.Error("merged before all wave tasks succeeded"); return false })
}

func TestIndexedDistinctLateSourceGraphExclusionMain(t *testing.T) {
	good := candidateGraph(t, "clean")
	dir := t.TempDir()
	base := "testdata/indexed-distinct/required/caller-count-outside"
	entries, err := os.ReadDir(base)
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range entries {
		if entry.Name() == "graph.callsite-string-content.identity" {
			continue
		}
		data, err := os.ReadFile(filepath.Join(base, entry.Name()))
		if err != nil {
			t.Fatal(err)
		}
		if err = os.WriteFile(filepath.Join(dir, entry.Name()), data, 0600); err != nil {
			t.Fatal(err)
		}
	}
	bad, err := store.OpenMode(dir, "MAPPED")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { bad.Close() })
	var oracle []map[string]any
	readDistinctJSON(t, "testdata/indexed-distinct/late-main.json", &oracle)
	output := []map[string]any{}
	for _, expected := range oracle {
		count := int(expected["sources"].(float64))
		sources := make([]Graph, count)
		for i := range sources {
			sources[i] = Graph{fmt.Sprintf("g%d", i), good}
		}
		sources[count-1].Store = bad
		q := expected["query"].(string)
		result, err := ExecuteCross(context.Background(), sources, q, nil, -1)
		actual := distinctOracleResult(map[string]any{"name": expected["name"], "query": q, "sources": count}, result, err)
		output = append(output, actual)
		if !reflect.DeepEqual(actual, expected) {
			t.Errorf("main=%#v\nnative=%#v", expected, actual)
		}
	}
	writeDistinctEvidence(t, "late-native.json", output)
}

func TestIndexedDistinctNullFailureMessage(t *testing.T) {
	defer func() {
		failure, ok := recover().(*Error)
		if !ok || failure.Class != "IndexOutOfBoundsException" || failure.JavaMessage() != nil || failure.Error() != "Query execution failed" {
			t.Fatalf("failure=%#v", failure)
		}
	}()
	failProjectionRead(&store.ProjectionReadError{})
}
