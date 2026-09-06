package query

import (
	"context"
	"encoding/json"
	"errors"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"os"
	"reflect"
	"testing"
)

func execute(t *testing.T, source string, params map[string]any) Result {
	t.Helper()
	r, e := Execute(context.Background(), nil, source, params, -1)
	if e != nil {
		t.Fatal(e)
	}
	return r
}
func assertResult(t *testing.T, result Result, columns []string, rows []map[string]any) {
	t.Helper()
	want := Result{columns, rows}
	if !reflect.DeepEqual(result, want) {
		t.Fatalf("got %#v\nwant %#v", result, want)
	}
}
func TestLiteralNullAndThreeValuedLogic(t *testing.T) {
	r := execute(t, "RETURN 1 + 2 * 3 AS value, null AS missing, [1, null] AS list, false AND null AS a, true OR null AS b, [1,null] = [1,null] AS eq, null IN [] AS empty", nil)
	assertResult(t, r, []string{"value", "missing", "list", "a", "b", "eq", "empty"}, []map[string]any{{"value": 7.0, "missing": nil, "list": []any{int32(1), nil}, "a": false, "b": true, "eq": nil, "empty": false}})
}
func TestUnwindWithDistinctOrderingAndPagination(t *testing.T) {
	r := execute(t, "UNWIND $xs AS x WITH DISTINCT x WHERE x >= 2 RETURN x * 2 AS doubled ORDER BY doubled DESC SKIP 1 LIMIT 1", map[string]any{"xs": []any{int64(2), int64(3), int64(2), int64(1), nil}})
	assertResult(t, r, []string{"doubled"}, []map[string]any{{"doubled": 4.0}})
	assertResult(t, execute(t, "UNWIND 42 AS n RETURN n", nil), []string{"n"}, []map[string]any{})
}
func TestAggregatesGroupingAndUnion(t *testing.T) {
	r := execute(t, "UNWIND [1,1,2,null] AS x RETURN x AS bucket, count(*) AS total, collect(x) AS values ORDER BY bucket", nil)
	assertResult(t, r, []string{"bucket", "total", "values"}, []map[string]any{{"bucket": int32(1), "total": int64(2), "values": []any{int32(1), int32(1)}}, {"bucket": int32(2), "total": int64(1), "values": []any{int32(2)}}, {"bucket": nil, "total": int64(1), "values": []any{}}})
	assertResult(t, execute(t, "UNWIND [1,1,2] AS x RETURN count(DISTINCT x) AS n, sum(x) AS sum, avg(x) AS avg", nil), []string{"n", "sum", "avg"}, []map[string]any{{"n": int64(2), "sum": 4.0, "avg": 4.0 / 3}})
	assertResult(t, execute(t, "RETURN 1 AS x UNION RETURN 1.0 AS x UNION ALL RETURN 2 AS x", nil), []string{"x"}, []map[string]any{{"x": int32(1)}, {"x": int32(2)}})
}
func TestNilGraphAndOptionalMatch(t *testing.T) {
	assertResult(t, execute(t, "MATCH (n:StringConstant) RETURN count(n) AS n", nil), []string{"n"}, []map[string]any{{"n": int64(0)}})
	assertResult(t, execute(t, "OPTIONAL MATCH (n:StringConstant) WHERE n.value = 'x' RETURN n", nil), []string{"n"}, []map[string]any{{"n": nil}})
}
func TestCaseComprehensionsPredicatesAndUTF16Length(t *testing.T) {
	r := execute(t, "RETURN [x IN [1,2,3] WHERE x > 1 | x * 2] AS xs, all(x IN [true,null] WHERE x) AS predicate, CASE null WHEN null THEN 1 ELSE 2 END AS c, size('😀') AS size, [1,2,3][-1] AS last, [1,2,3][-1..2] AS slice", nil)
	assertResult(t, r, []string{"xs", "predicate", "c", "size", "last", "slice"}, []map[string]any{{"xs": []any{int64(4), int64(6)}, "predicate": nil, "c": int32(2), "size": int32(2), "last": int32(3), "slice": []any{int32(1), int32(2)}}})
}
func TestNumericEqualityDoesNotLoseLargeIntegers(t *testing.T) {
	r := execute(t, "RETURN 9007199254740993 = 9007199254740992.0 AS eq, 9007199254740993 > 9007199254740992 AS greater", nil)
	assertResult(t, r, []string{"eq", "greater"}, []map[string]any{{"eq": false, "greater": true}})
}
func TestUnsupportedIsRejectedEvenOnEmptyInput(t *testing.T) {
	for _, source := range []string{"MATCH (n) WHERE n.value =~ '.*' RETURN n", "MATCH (n)-->(m) RETURN m", "UNWIND [] AS n RETURN unknown(n)", "RETURN count(*) + 1", "RETURN 1 SKIP -1", "RETURN 1 / 0", "RETURN $missing"} {
		t.Run(source, func(t *testing.T) {
			_, err := Execute(context.Background(), nil, source, nil, -1)
			if err == nil {
				t.Fatalf("accepted %s", source)
			}
		})
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := Execute(ctx, nil, "RETURN 1", nil, -1)
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("got %v", err)
	}
}
func TestPropertyAliasesAndSpecificTypePrecedence(t *testing.T) {
	method := store.MethodDescriptor{DeclaringClass: "pkg.C", Name: "run", ParameterTypes: []string{"int"}, ReturnType: "void"}
	cases := []struct {
		node store.Node
		key  string
		want any
	}{{store.Node{ID: 7, Kind: "FieldNode", Name: "x", Type: "java.lang.String", DeclaringClass: "pkg.C", IsStatic: true}, "type", "java.lang.String"}, {store.Node{Kind: "ReturnNode", Method: method}, "type", "ReturnNode"}, {store.Node{Kind: "LocalVariable", Method: method}, "method", "pkg.C.run(int)"}, {store.Node{Kind: "CallSiteNode", Callee: method}, "callee_signature", "pkg.C.run(int)"}, {store.Node{Kind: "EnumConstant", EnumArguments: []any{int32(42)}}, "value", int32(42)}, {store.Node{Kind: "NullConstant"}, "value", nil}}
	for _, tt := range cases {
		if got := NodeProperty(tt.node, tt.key); !reflect.DeepEqual(got, tt.want) {
			t.Fatalf("%s.%s got %#v want %#v", tt.node.Kind, tt.key, got, tt.want)
		}
	}
	if !matchesLabel(store.Node{Kind: "IntConstant"}, "CONSTANT") || matchesLabel(store.Node{Kind: "IntConstant"}, "Missing") || !matchesLabel(store.Node{Kind: "CallSiteNode"}, "callsite") {
		t.Fatal("label hierarchy mismatch")
	}
}

// This optional test uses captured JVM responses over the real persisted Tika
// fixture. It is correctness evidence only and records no performance claims.
func TestRealGraphJVMQueryParity(t *testing.T) {
	fixture := os.Getenv("GRAPHITE_TEST_GRAPH")
	if fixture == "" {
		t.Skip("set GRAPHITE_TEST_GRAPH to the persisted Tika fixture")
	}
	graph, err := store.Open(fixture)
	if err != nil {
		t.Fatal(err)
	}
	defer graph.Close()
	data, err := os.ReadFile("../../../docs/go-server-baseline/tika-query-baseline/observations.json")
	if err != nil {
		t.Fatal(err)
	}
	var observations []struct {
		Case struct {
			Name  string `json:"name"`
			Phase string `json:"phase"`
			Body  struct {
				Query string `json:"query"`
			} `json:"body"`
		} `json:"case"`
		Baseline struct {
			Body map[string]any `json:"json"`
		} `json:"baseline"`
	}
	if err = json.Unmarshal(data, &observations); err != nil {
		t.Fatal(err)
	}
	for _, observation := range observations {
		if observation.Case.Phase != "query" {
			continue
		}
		t.Run(observation.Case.Name, func(t *testing.T) {
			r, err := Execute(context.Background(), graph, observation.Case.Body.Query, nil, 1000)
			if err != nil {
				t.Fatal(err)
			}
			for _, row := range r.Rows {
				for k, v := range row {
					if v == nil {
						delete(row, k)
					}
				}
			}
			raw, _ := json.Marshal(r)
			var got map[string]any
			json.Unmarshal(raw, &got)
			want := map[string]any{"columns": observation.Baseline.Body["columns"], "rows": observation.Baseline.Body["rows"]}
			if !reflect.DeepEqual(got, want) {
				t.Fatalf("query %s\ngot %s\nwant %#v", observation.Case.Body.Query, raw, want)
			}
		})
	}
}
