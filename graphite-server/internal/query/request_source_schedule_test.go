package query

import (
	"context"
	"encoding/json"
	"fmt"
	"reflect"
	"strconv"
	"sync/atomic"
	"testing"
	"time"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
)

func TestRequestSourceScopeCancellationScheduleMain(t *testing.T) {
	var records []struct {
		Count   int              `json:"count"`
		Shape   int              `json:"shape"`
		Active  bool             `json:"active"`
		Scoped  bool             `json:"scoped"`
		Query   string           `json:"query"`
		Error   string           `json:"error"`
		Message string           `json:"message"`
		After   []map[string]any `json:"after"`
	}
	readDistinctJSON(t, "testdata/request-source-scope/schedule-main.json", &records)
	if len(records) != 12 {
		t.Fatal("incomplete scheduling matrix")
	}
	for _, record := range records {
		t.Run(fmt.Sprintf("%d/shape%d/active%v", record.Count, record.Shape, record.Active), func(t *testing.T) {
			sources := make([]Graph, record.Count)
			for i := range sources {
				name := "clean"
				if i == len(sources)-1 {
					name = "bad-matched"
				}
				sources[i] = Graph{fmt.Sprintf("g%d", i), candidateGraph(t, name)}
			}
			// Match the main oracle's eight-worker wave. The timeout is a watchdog;
			// only the production scheduler may cancel the delayed sibling tasks.
			ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
			defer cancel()
			e := evaluator{ctx: ctx, cross: true, graphs: sources, sourceScopeApplied: record.Scoped, workTrackingEnabled: true, parameters: map[string]any{"term": "other"}, regexes: &regexLRU{entries: map[string]compiledRegex{}}}
			ast, err := cypher.Parse(record.Query)
			if err != nil {
				t.Fatal(err)
			}
			plan := e.compileStreamingPagination(ast.Branches[0])
			if plan == nil {
				t.Fatal("main ORDER query declined")
			}
			plan.atoms = e.lazyNecessaryCandidates(plan.match.Where, plan.node.Variable)
			if plan.atoms == nil {
				t.Fatal("missing direct string source")
			}
			ready := make(chan struct{}, 7)
			var joined atomic.Int32
			factory := func(local evaluator, source Graph, node cypher.NodePattern, atoms []distinctStringAtom, count int) mainNodeNext {
				i, err := strconv.Atoi(source.ID[1:])
				if err != nil {
					panic(err)
				}
				if record.Active && i >= count-8 {
					if i < count-1 {
						ready <- struct{}{}
						<-local.ctx.Done()
						joined.Add(1)
						panic(local.ctx.Err())
					}
					for n := 0; n < 7; n++ {
						select {
						case <-ready:
						case <-ctx.Done():
							panic("scheduling watchdog fired")
						}
					}
				}
				return realStreamingNodes(local, source, node, atoms, count)
			}
			var result Result
			var failure any
			before := ordinaryHistoryState(t, sources)
			func() {
				defer func() { failure = recover() }()
				defer ordinarySourceFailure()
				result = e.streamingParallelOrder(plan, sources, factory, 8)
			}()
			actual := streamingTestResult(e, result, failure)
			want := map[string]any{"error": record.Error, "message": record.Message}
			if !reflect.DeepEqual(actual, want) {
				t.Fatalf("public result: got %s want %s", mustJSON(actual), mustJSON(want))
			}
			if ctx.Err() != nil {
				t.Fatal("parent watchdog canceled the query", ctx.Err())
			}
			if record.Active && joined.Load() != 7 {
				t.Fatalf("returned before siblings joined: %d", joined.Load())
			}
			after := ordinaryHistoryState(t, sources)
			if record.Active {
				if !reflect.DeepEqual(after, record.After) {
					t.Fatalf("forced schedule state got %s want %s", mustJSON(after), mustJSON(record.After))
				}
			} else {
				actual["before"], actual["after"] = before, after
				want["before"], want["after"] = before, record.After
				normalize := func(v map[string]any) map[string]any {
					b, err := json.Marshal(v)
					if err != nil {
						t.Fatal(err)
					}
					var out map[string]any
					if err := json.Unmarshal(b, &out); err != nil {
						t.Fatal(err)
					}
					return out
				}
				if err := requestScopeComparison(normalize(actual), normalize(want), nil, record.Count, 8, true); err != nil {
					t.Fatal(err)
				}
			}
		})
	}
}
