package query

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"reflect"
	"runtime"
	"strings"
	"sync"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// Observe the Done access at the existing cancellation check at scalar-call entry. This
// test context does not replace clocks, RNGs, evaluator functions or parameters.
type scalarCallTraceContext struct {
	context.Context
	mu     sync.Mutex
	stages []string
}

func (c *scalarCallTraceContext) Done() <-chan struct{} {
	pcs := make([]uintptr, 48)
	frames := runtime.CallersFrames(pcs[:runtime.Callers(2, pcs)])
	call, stage := false, ""
	for {
		frame, more := frames.Next()
		call = call || strings.HasSuffix(frame.Function, ".evaluator.call")
		if strings.HasSuffix(frame.Function, ".evaluator.computeEarlyLimit") {
			stage = "early"
		}
		if strings.HasSuffix(frame.Function, ".evaluator.count") {
			stage = "projected"
		}
		if !more {
			break
		}
	}
	if call {
		c.mu.Lock()
		c.stages = append(c.stages, stage)
		c.mu.Unlock()
	}
	return c.Context.Done()
}

func TestEarlyMatchScalarEvaluationCount(t *testing.T) {
	for _, mode := range []string{"MAPPED", "EAGER"} {
		graph, err := store.OpenMode("testdata/traversal", mode)
		if err != nil {
			t.Fatal(err)
		}
		defer graph.Close()
		for _, function := range []string{"rand", "timestamp"} {
			for _, cross := range []bool{false, true} {
				t.Run(fmt.Sprintf("%s/%s/cross=%v", mode, function, cross), func(t *testing.T) {
					path := fmt.Sprintf("testdata/early-match/trace-%s-%s-%v.json", mode, function, cross)
					data, err := os.ReadFile(path)
					if err != nil {
						t.Fatal(err)
					}
					var oracle struct {
						Calls []struct {
							Function string
							Stack    []string
						}
						ExitCode int
					}
					if err = json.Unmarshal(data, &oracle); err != nil {
						t.Fatal(err)
					}
					if oracle.ExitCode != 0 || len(oracle.Calls) != 2 {
						t.Fatal("incomplete main JDI trace")
					}
					for index, call := range oracle.Calls {
						early := false
						for _, frame := range call.Stack {
							early = early || strings.HasSuffix(frame, ".computeEarlyLimit")
						}
						if call.Function != function || early != (index == 0) {
							t.Fatal("wrong main call stage", call)
						}
					}
					ctx := &scalarCallTraceContext{Context: context.Background()}
					query := "MATCH (n:IntConstant) RETURN n.id AS id LIMIT " + function + "()*0+$l"
					var result Result
					if cross {
						result, err = ExecuteCross(ctx, []Graph{{"a", graph}, {"b", graph}}, query, map[string]any{"l": 1}, -1)
					} else {
						result, err = Execute(ctx, graph, query, map[string]any{"l": 1}, -1)
					}
					if err != nil {
						t.Fatal(err)
					}
					if len(result.Rows) != 1 {
						t.Fatalf("rows %#v", result.Rows)
					}
					id, valid := integer(result.Rows[0]["id"])
					if !valid || id != 0 {
						t.Fatalf("rows %#v", result.Rows)
					}
					if !reflect.DeepEqual(ctx.stages, []string{"early", "projected"}) {
						t.Fatalf("dispatch stages %v", ctx.stages)
					}
				})
			}
		}
	}
}

func TestEarlyMatchTraversalCancellation(t *testing.T) {
	graph, err := store.Open("testdata/traversal")
	if err != nil {
		t.Fatal(err)
	}
	defer graph.Close()
	parsed, err := cypher.Parse("MATCH p=(a {id:0})-[r*0..4]->(b) RETURN p LIMIT $l")
	if err != nil {
		t.Fatal(err)
	}
	ctx := newTraversalCancelContext(t, context.Background(), 30)
	e := evaluator{ctx: ctx, parameters: map[string]any{"l": 100}}
	defer func() {
		if recovered := recover(); recovered != context.Canceled {
			t.Errorf("got %v, want cancellation", recovered)
		}
		if ctx.checks != 30 {
			t.Errorf("checks %d", ctx.checks)
		}
	}()
	e.branch(graph, parsed.Branches[0])
	t.Fatal("traversal did not cancel")
}
