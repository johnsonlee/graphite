package cypher_test

import (
	"context"
	"errors"
	"fmt"
	"reflect"
	"sync"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/query"
)

func cachedExecution(t *testing.T, source string, parameters map[string]any) query.Result {
	t.Helper()
	result, err := query.Execute(context.Background(), nil, source, parameters, -1)
	if err != nil {
		t.Fatalf("execute %q: %v", source, err)
	}
	return result
}

func requireCachedResult(t *testing.T, got query.Result, columns []string, rows []map[string]any) {
	t.Helper()
	if !reflect.DeepEqual(got.Columns, columns) || !reflect.DeepEqual(got.Rows, rows) {
		t.Fatalf("result = %#v; want columns=%#v rows=%#v", got, columns, rows)
	}
}

func TestParsedCachePublicASTMutationDoesNotChangeExecution(t *testing.T) {
	const source = "RETURN {items:[1,2], nested:{value:'original'}} AS cache_payload, $value AS cache_actual"
	want := []map[string]any{{
		"cache_payload": map[string]any{
			"items":  []any{int32(1), int32(2)},
			"nested": map[string]any{"value": "original"},
		},
		"cache_actual": int32(7),
	}}
	// Exercise both the first returned AST and an AST obtained after execution
	// has reused the same source. Neither belongs to the private cache.
	for attempt := 0; attempt < 2; attempt++ {
		ast, err := cypher.Parse(source)
		if err != nil {
			t.Fatal(err)
		}
		projection := ast.Branches[0].Clauses[0].(cypher.ProjectionClause)
		payload := projection.Items[0].Expression.(cypher.Map)
		items := payload.Entries["items"].(cypher.List)
		items.Elements[0] = cypher.Literal{Value: int32(999)}
		payload.Entries["nested"].(cypher.Map).Entries["value"] = cypher.Literal{Value: "changed"}
		payload.Keys[0] = "changed_key"
		delete(payload.Entries, "items")
		projection.Items[0].Alias = "changed_alias"
		projection.Items[1].Expression = cypher.Literal{Value: int32(-1)}
		projection.Limit = cypher.Literal{Value: int32(0)}
		ast.Branches[0].Clauses[0] = projection

		result := cachedExecution(t, source, map[string]any{"value": int32(7)})
		requireCachedResult(t, result, []string{"cache_payload", "cache_actual"}, want)
		// Returned collection values must not become cached AST values either.
		returned := result.Rows[0]["cache_payload"].(map[string]any)
		returned["items"].([]any)[0] = int32(-2)
		returned["nested"].(map[string]any)["value"] = "changed result"
		requireCachedResult(t, cachedExecution(t, source, map[string]any{"value": int32(7)}),
			[]string{"cache_payload", "cache_actual"}, want)
	}
}

func TestParsedCacheExecutionKeepsParametersRequestScoped(t *testing.T) {
	// The 7/8 results also match the pinned-main parameters-and-distinct-graphs
	// event in docs/go-server-baseline/native-ast-cache/main.json.
	const source = "RETURN $value AS x"
	for _, value := range []any{int32(7), int32(8), "other request", nil, int32(7)} {
		requireCachedResult(t, cachedExecution(t, source, map[string]any{"value": value}),
			[]string{"x"}, []map[string]any{{"x": value}})
	}
	requireCachedResult(t, cachedExecution(t, source, nil), []string{"x"}, []map[string]any{{"x": nil}})
}

func TestParsedCacheConcurrentExecutionOwnsASTAndParameters(t *testing.T) {
	const source = "UNWIND $values AS n RETURN n AS value, $tag AS tag ORDER BY value"
	if _, err := cypher.Parse(source); err != nil {
		t.Fatal(err)
	}
	const workers = 12
	start := make(chan struct{})
	failures := make(chan error, workers)
	var finished sync.WaitGroup
	for worker := 0; worker < workers; worker++ {
		finished.Add(1)
		go func(worker int) {
			defer finished.Done()
			<-start
			for round := 0; round < 3; round++ {
				ast, err := cypher.Parse(source)
				if err != nil {
					failures <- err
					return
				}
				projection := ast.Branches[0].Clauses[1].(cypher.ProjectionClause)
				projection.OrderBy[0].Descending = true
				projection.Items[1].Expression = cypher.Literal{Value: "foreign AST"}
				base := int64(worker*100 + round*10)
				tag := fmt.Sprintf("request-%d-%d", worker, round)
				parameters := map[string]any{"values": []any{base + 2, base, base + 1}, "tag": tag}
				result, err := query.Execute(context.Background(), nil, source, parameters, -1)
				want := []map[string]any{{"value": base, "tag": tag}, {"value": base + 1, "tag": tag}, {"value": base + 2, "tag": tag}}
				if err != nil || !reflect.DeepEqual(result.Columns, []string{"value", "tag"}) || !reflect.DeepEqual(result.Rows, want) {
					failures <- fmt.Errorf("worker %d round %d: result=%#v err=%v; want=%#v", worker, round, result, err, want)
					return
				}
			}
		}(worker)
	}
	close(start)
	finished.Wait()
	close(failures)
	for err := range failures {
		t.Error(err)
	}
}

func TestParsedCacheCancelledExecutionDoesNotPoisonLaterCalls(t *testing.T) {
	const source = "RETURN $value AS x"
	for _, value := range []int32{7, 8} {
		ctx, cancel := context.WithCancel(context.Background())
		cancel()
		result, err := query.Execute(ctx, nil, source, map[string]any{"value": int32(-1)}, -1)
		var failure *query.Error
		if !errors.Is(err, context.Canceled) || !errors.As(err, &failure) || failure.Class != "CypherQueryCancelledException" || failure.Message != "Cypher query cancelled" {
			t.Fatalf("cancelled execution: result=%#v error=%T %v", result, err, err)
		}
		if !reflect.DeepEqual(result, query.Result{}) {
			t.Fatalf("cancelled execution published a result: %#v", result)
		}
		requireCachedResult(t, cachedExecution(t, source, map[string]any{"value": value}),
			[]string{"x"}, []map[string]any{{"x": value}})
	}
}

func TestParsedCacheParseErrorsStillPrecedeExecutionCancellation(t *testing.T) {
	// Exact queries/classes/messages are independently captured in the pinned
	// main oracle's error-first and literal-error-first events.
	for _, tc := range []struct {
		source, class, message string
	}{
		{"RETURN 999999999999999999999999", "NumberFormatException", "For input string: \"999999999999999999999999\""},
		{"MATCH RETURN n", "CypherParseException", "Syntax error at position 13: mismatched input 'n' expecting '='"},
	} {
		t.Run(tc.class, func(t *testing.T) {
			_, ordinaryError := query.Execute(context.Background(), nil, tc.source, nil, -1)
			var ordinary *query.Error
			if !errors.As(ordinaryError, &ordinary) || ordinary.Class != tc.class || ordinary.Message == "" {
				t.Fatalf("ordinary parsing: %T %v", ordinaryError, ordinaryError)
			}
			if ordinary.Message != tc.message {
				t.Fatalf("parse message = %q; want %q", ordinary.Message, tc.message)
			}
			for attempt := 0; attempt < 2; attempt++ {
				ctx, cancel := context.WithCancel(context.Background())
				cancel()
				_, err := query.Execute(ctx, nil, tc.source, nil, -1)
				var failure *query.Error
				if errors.Is(err, context.Canceled) || !errors.As(err, &failure) || failure.Class != tc.class || failure.Message != ordinary.Message {
					t.Fatalf("parse error lost precedence over cancellation: %T %v; ordinary=%v", err, err, ordinaryError)
				}
			}
		})
	}
}
