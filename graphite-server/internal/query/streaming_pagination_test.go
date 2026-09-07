package query

import (
	"context"
	"fmt"
	"math"
	"reflect"
	"sync"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func streamingTestResult(e evaluator, result Result, failure any) map[string]any {
	if failure != nil {
		if err, ok := failure.(error); ok {
			return distinctOracleResult(nil, Result{}, err)
		}
		panic(failure)
	}
	for _, row := range result.Rows {
		ids := provenance(row)
		delete(row, provenanceKey)
		for k, v := range row {
			row[k] = e.materialize(v)
		}
		if e.cross || len(ids) > 0 {
			if _, present := row["$metadata"]; len(ids) > 0 || !present {
				row["$metadata"] = map[string]any{"graphIds": ids}
			}
		}
	}
	return distinctOracleResult(nil, result, nil)
}

// The fixture is actual-main-written. Only the outer node factory observes
// fetches/injects the same producer error as StreamingOracle's Graph proxy.
// Admission, WHERE, projection, ranking, task joining and output are production.
func TestStreamingPaginationDesignOracle(t *testing.T) {
	var records []map[string]any
	var specs []map[string]any
	readDistinctJSON(t, "testdata/streaming-pagination/design-main.json", &records)
	readDistinctJSON(t, "testdata/streaming-pagination/design-cases.json", &specs)
	if len(records) != 53 {
		t.Fatal("denominator", len(records))
	}
	byName := map[string]map[string]any{}
	for _, s := range specs {
		byName[s["name"].(string)] = s
	}
	outputs := []map[string]any{}
	for _, expected := range records {
		t.Run(fmt.Sprintf("%s/%v", expected["name"], expected["cross"]), func(t *testing.T) {
			spec := byName[expected["name"].(string)]
			cross := expected["cross"].(bool)
			capability, _ := expected["capabilities"].(bool)
			g := candidateGraph(t, "clean")
			sources := []Graph{{ID: "local", Store: g}}
			if cross {
				sources = []Graph{{ID: "a", Store: g}, {ID: "b", Store: g}}
			}
			e := evaluator{ctx: context.Background(), cross: cross, graphs: sources, parameters: expected["params"].(map[string]any), regexes: &regexLRU{entries: map[string]compiledRegex{}}}
			ast, err := cypher.Parse(expected["query"].(string))
			if err != nil {
				t.Fatal(err)
			}
			p := e.compileStreamingPagination(ast.Branches[0])
			admitted := p != nil
			if p == nil {
				m := ast.Branches[0].Clauses[0].(cypher.MatchClause)
				r := ast.Branches[0].Clauses[1].(cypher.ProjectionClause)
				p = &streamingPaginationPlan{match: m, node: m.Patterns[0].Nodes[0], projection: r, columns: []string{"x"}, limit: math.MaxInt32, retained: math.MaxInt32}
				if spec["name"] != "retained-overflow-fallback" && spec["name"] != "unbounded-projects-before-later-read" {
					t.Fatal("unexpected decline", spec)
				}
			}
			if capability {
				p.atoms = e.lazyNecessaryCandidates(p.match.Where, p.node.Variable)
			}
			type trace struct {
				Next int
				IDs  []int32
			}
			traces := make([]trace, len(sources))
			for i := range traces {
				traces[i].IDs = []int32{}
			}
			parallel := e.streamingOrderParallelism(p, sources) > 1
			var ready sync.WaitGroup
			if parallel {
				ready.Add(len(sources))
			}
			factory := func(local evaluator, source Graph, node cypher.NodePattern, atoms []distinctStringAtom, count int) mainNodeNext {
				i := 0
				if source.ID == "b" {
					i = 1
				}
				tr := &traces[i]
				if capability {
					next := realStreamingNodes(local, source, node, atoms, count)
					first := true
					return func(ctx context.Context) (store.Node, bool) {
						n, ok := next(ctx)
						if ok {
							tr.Next++
							tr.IDs = append(tr.IDs, n.ID)
						}
						if first && parallel {
							first = false
							ready.Done()
							ready.Wait()
						}
						return n, ok
					}
				}
				ids := source.Store.NodesOfKind("CallSiteNode")
				position := 0
				return func(ctx context.Context) (store.Node, bool) {
					if position == len(ids) {
						return store.Node{}, false
					}
					tr.Next++
					if fail, ok := spec["failAt"].(float64); ok && tr.Next == int(fail) && source.ID != "a" {
						functionError("IllegalStateException", fmt.Sprintf("AUDIT_FETCH_%s_%d", source.ID, tr.Next))
					}
					n, err := source.Store.CandidateNode(ctx, ids[position])
					if err != nil {
						failNodeRead(err)
					}
					position++
					tr.IDs = append(tr.IDs, n.ID)
					return n, true
				}
			}
			var result Result
			failure := findIDCaught(func() {
				if admitted && p.limit <= 0 {
					result = Result{Columns: p.columns, Rows: []map[string]any{}}
					return
				}
				if spec["name"] == "retained-overflow-fallback" {
					// Admission declines: retain the existing general MATCH -> project
					// operators, including full input consumption before pagination.
					next := e.streamingBindings(p, sources, factory)
					rows := []map[string]any{}
					for {
						b, ok := next(e.ctx)
						if !ok {
							break
						}
						if e.eval(p.match.Where, b) == true {
							rows = append(rows, b)
						}
					}
					result.Rows, result.Columns = e.project(rows, p.projection)
					return
				}
				result = e.streamingPaginationSources(p, sources, factory)
			})
			actual := streamingTestResult(e, result, failure)
			want := map[string]any{}
			for _, key := range []string{"columns", "rows", "error", "message"} {
				if v, ok := expected[key]; ok {
					want[key] = v
				}
			}
			if !reflect.DeepEqual(actual, want) {
				t.Errorf("main=%s native=%s", mustJSON(want), mustJSON(actual))
			}
			for i, value := range expected["traces"].([]any) {
				tr := value.(map[string]any)
				if traces[i].Next != int(tr["next"].(float64)) {
					t.Errorf("source %d fetch count main=%v native=%d", i, tr["next"], traces[i].Next)
				}
				wantIDs := []int32{}
				for _, id := range tr["ids"].([]any) {
					wantIDs = append(wantIDs, int32(id.(float64)))
				}
				if !reflect.DeepEqual(wantIDs, traces[i].IDs) {
					t.Errorf("source %d ids main=%v native=%v", i, wantIDs, traces[i].IDs)
				}
			}
			outputs = append(outputs, map[string]any{"name": expected["name"], "cross": cross, "admitted": admitted, "response": actual, "traces": traces})
		})
	}
	writeDistinctEvidence(t, "streaming-design-native.json", outputs)
}

func TestStreamingPaginationActualMainCorpus(t *testing.T) {
	for _, test := range []struct{ fixture, mode string }{{"clean", "MAPPED"}, {"clean", "EAGER"}, {"bad-first-unmatched", "MAPPED"}, {"bad-last-unmatched", "MAPPED"}, {"bad-matched", "MAPPED"}} {
		t.Run(test.fixture+"/"+test.mode, func(t *testing.T) {
			var records []map[string]any
			readDistinctJSON(t, "testdata/streaming-pagination/"+test.fixture+"-"+test.mode+"-wire.json", &records)
			if len(records) != 96 {
				t.Fatal("denominator", len(records))
			}
			dir := ordinaryCopyFixture(t, "testdata/candidate-index/"+test.fixture)
			output := []map[string]any{}
			for _, record := range records {
				t.Run(fmt.Sprintf("%s/%v", record["name"], record["cross"]), func(t *testing.T) {
					g, err := store.OpenMode(dir, test.mode)
					if err != nil {
						t.Fatal(err)
					}
					defer g.Close()
					var result Result
					params := record["params"].(map[string]any)
					q := record["query"].(string)
					if record["cross"].(bool) {
						result, err = ExecuteCross(context.Background(), []Graph{{ID: "a", Store: g}, {ID: "b", Store: g}}, q, params, -1)
					} else {
						result, err = Execute(context.Background(), g, q, params, -1)
					}
					spec := map[string]any{}
					for _, k := range []string{"name", "query", "cross", "mode", "params"} {
						spec[k] = record[k]
					}
					actual := distinctOracleResult(spec, result, err)
					output = append(output, actual)
					if !reflect.DeepEqual(record, actual) {
						t.Errorf("main=%s native=%s", mustJSON(record), mustJSON(actual))
					}
				})
			}
			writeDistinctEvidence(t, "streaming-"+test.fixture+"-"+test.mode+"-native.json", output)
		})
	}
}

// Unknown labels dispatch to main's empty general node source, where suffix
// expressions still execute. They must not acquire streaming SKIP semantics.
func TestStreamingPaginationUnknownLabelMain(t *testing.T) {
	for _, fixture := range []string{"clean", "bad-matched"} {
		var records []map[string]any
		readDistinctJSON(t, "testdata/streaming-pagination/"+fixture+"-unknown-wire.json", &records)
		if len(records) != 12 {
			t.Fatal("denominator", len(records))
		}
		outputs := []map[string]any{}
		for _, record := range records {
			t.Run(fmt.Sprintf("%s/%s/%v", fixture, record["name"], record["cross"]), func(t *testing.T) {
				g := candidateGraph(t, fixture)
				var result Result
				var err error
				if record["cross"] == true {
					result, err = ExecuteCross(context.Background(), []Graph{{ID: "a", Store: g}, {ID: "b", Store: g}}, record["query"].(string), record["params"].(map[string]any), -1)
				} else {
					result, err = Execute(context.Background(), g, record["query"].(string), record["params"].(map[string]any), -1)
				}
				spec := map[string]any{}
				for _, key := range []string{"name", "query", "cross", "mode", "params"} {
					spec[key] = record[key]
				}
				actual := distinctOracleResult(spec, result, err)
				outputs = append(outputs, actual)
				if !reflect.DeepEqual(actual, record) {
					t.Errorf("main=%s native=%s", mustJSON(record), mustJSON(actual))
				}
			})
		}
		writeDistinctEvidence(t, "streaming-unknown-"+fixture+"-native.json", outputs)
	}
}
