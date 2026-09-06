package query

import (
	"context"
	"encoding/json"
	"errors"
	"math"
	"os"
	"reflect"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

var graphIdentityPattern = regexp.MustCompile(`(io\.johnsonlee\.graphite\.webgraph\.WebGraphBackedGraph@)[0-9a-f]+`)

func normalizeFunctionOracle(v any) any {
	switch x := v.(type) {
	case float64:
		if math.IsNaN(x) {
			return map[string]any{"$number": "NaN"}
		}
		if math.IsInf(x, 1) {
			return map[string]any{"$number": "Infinity"}
		}
		if math.IsInf(x, -1) {
			return map[string]any{"$number": "-Infinity"}
		}
	case float32:
		return normalizeFunctionOracle(float64(x))
	case string:
		return graphIdentityPattern.ReplaceAllString(x, "${1}<identity>")
	case []any:
		r := make([]any, len(x))
		for i, v := range x {
			r[i] = normalizeFunctionOracle(v)
		}
		return r
	case []map[string]any:
		r := make([]any, len(x))
		for i, v := range x {
			r[i] = normalizeFunctionOracle(v)
		}
		return r
	case map[string]any:
		r := map[string]any{}
		for k, v := range x {
			r[k] = normalizeFunctionOracle(v)
		}
		return r
	}
	return v
}
func TestFunctionsMainJVMOracle(t *testing.T) {
	testFunctionsOracle(t, "testdata/functions-jvm-oracle.json", "testdata/traversal")
}
func TestNodeFunctionsMainJVMOracle(t *testing.T) {
	testFunctionsOracle(t, "testdata/functions-node-jvm-oracle.json", "../store/testdata/jvm-v3")
}
func TestPropertyEvaluationOrderMainJVMOracle(t *testing.T) {
	testFunctionsOracle(t, "testdata/property-order-jvm-oracle.json", "../store/testdata/jvm-v3")
}
func testFunctionsOracle(t *testing.T, corpusPath, fixture string) {
	graph, err := store.OpenMode(fixture, "EAGER")
	if err != nil {
		t.Fatal(err)
	}
	defer graph.Close()
	data, err := os.ReadFile(corpusPath)
	if err != nil {
		t.Fatal(err)
	}
	var corpus struct {
		Cases []struct {
			Name, Query, Error, Message string
			Cross                       bool
			Columns                     []string
			Rows                        []map[string]any
		}
	}
	if err = json.Unmarshal(data, &corpus); err != nil {
		t.Fatal(err)
	}
	for _, c := range corpus.Cases {
		t.Run(c.Name, func(t *testing.T) {
			var result Result
			var err error
			if c.Cross {
				result, err = ExecuteCross(context.Background(), []Graph{{"orders", graph}, {"billing", graph}}, c.Query, nil, -1)
			} else {
				result, err = Execute(context.Background(), graph, c.Query, nil, -1)
			}
			if c.Error != "" {
				var failure *Error
				if !errors.As(err, &failure) {
					t.Fatalf("%s: want %s, got %v", c.Query, c.Error, err)
				}
				if failure.Class != c.Error || failure.Message != c.Message {
					t.Fatalf("%s: want %s (%s), got %s (%s)", c.Query, c.Error, c.Message, failure.Class, failure.Message)
				}
				return
			}
			if err != nil {
				t.Fatalf("%s: %v", c.Query, err)
			}
			raw, err := json.Marshal(normalizeFunctionOracle(result.Rows))
			if err != nil {
				t.Fatal(err)
			}
			var actual any
			if err = json.Unmarshal(raw, &actual); err != nil {
				t.Fatal(err)
			}
			expected := normalizeFunctionOracle(c.Rows)
			if !reflect.DeepEqual(result.Columns, c.Columns) || !reflect.DeepEqual(actual, expected) {
				t.Fatalf("%s\ncolumns got %v want %v\nrows got %s\nwant %s", c.Query, result.Columns, c.Columns, raw, mustJSON(expected))
			}
		})
	}
}

func TestCompleteScalarDispatchMatrix(t *testing.T) {
	expected := strings.Fields("id elementid qualifiedid graphid coalesce timestamp tointeger toint tofloat toboolean tostring properties keys labels type tolower tolowercase toupper touppercase trim ltrim rtrim replace substring split size length left right reverse head tail last range nodes relationships abs ceil floor round sign rand sqrt exp log log10 e sin cos tan asin acos atan atan2 cot pi degrees radians exists")
	if len(expected) != 59 || len(scalarFunctionNames) != len(expected) {
		t.Fatalf("scalar dispatch count got %d want 59", len(scalarFunctionNames))
	}
	for _, name := range expected {
		if !scalarFunctionNames[name] {
			t.Errorf("missing dispatch %s", name)
		}
	}
}
func TestNondeterministicFunctionsAndValueTypes(t *testing.T) {
	before := time.Now().UnixMilli()
	result := execute(t, "RETURN timestamp() AS time,rand() AS random,pi() AS pi,e() AS e", nil)
	after := time.Now().UnixMilli()
	row := result.Rows[0]
	timestamp, ok := row["time"].(int64)
	if !ok || timestamp < before || timestamp > after {
		t.Fatalf("timestamp %#v outside [%d,%d]", row["time"], before, after)
	}
	random, ok := row["random"].(float64)
	if !ok || random < 0 || random >= 1 {
		t.Fatalf("rand %#v", row["random"])
	}
	if row["pi"] != math.Pi || row["e"] != math.E {
		t.Fatalf("constants %#v", row)
	}
	e := evaluator{ctx: context.Background()}
	for _, c := range []struct {
		name        string
		value, want any
	}{{"abs", int32(math.MinInt32), int32(math.MinInt32)}, {"abs", int64(math.MinInt64), int64(math.MinInt64)}, {"abs", float32(-1.5), float32(1.5)}, {"abs", float64(-1.5), float64(1.5)}, {"sign", float64(-1), int32(-1)}, {"tofloat", int32(3), float64(3)}, {"tointeger", float64(3.9), int64(3)}} {
		if got := e.call(c.name, []any{c.value}); !reflect.DeepEqual(got, c.want) {
			t.Errorf("%s(%T) got %#v (%T), want %#v (%T)", c.name, c.value, got, got, c.want, c.want)
		}
	}
}
func TestCollectionFunctionsCancelInsideExecution(t *testing.T) {
	cases := []struct {
		name string
		run  func(evaluator)
	}{
		{"range", func(e evaluator) { e.call("range", []any{int64(0), int64(math.MaxInt64)}) }},
		{"reverse", func(e evaluator) { e.call("reverse", []any{strings.Repeat("x", 1000)}) }},
		{"tail", func(e evaluator) { e.call("tail", []any{make([]any, 1000)}) }},
		{"toString", func(e evaluator) { e.call("toString", []any{make([]any, 1000)}) }},
		{"regex-literal", func(e evaluator) { e.regexMatch("x.*", strings.Repeat("x", 1000)) }},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			ctx := &traversalCancelContext{Context: context.Background(), cancelAt: 12}
			defer func() {
				if got := recover(); got != context.Canceled {
					t.Errorf("got %v, want context.Canceled", got)
				}
			}()
			c.run(evaluator{ctx: ctx})
			t.Fatal("function returned without cancellation")
		})
	}
}
func TestAnnotationAttributeInsertionOrder(t *testing.T) {
	node := store.Node{ID: 7, Kind: "AnnotationNode", Name: "A", ClassName: "C", MemberName: "m", Values: map[string]any{"z": int32(1), "a": int32(2), "id": int32(3)}, ValueOrder: []string{"z", "a", "id"}}
	e := evaluator{ctx: context.Background()}
	want := []any{"id", "name", "class", "member", "z", "a"}
	if got := e.propertyFunction(node, true); !reflect.DeepEqual(got, want) {
		t.Fatalf("keys got %#v want %#v", got, want)
	}
	if got := e.stringify(e.propertyFunction(node, false)); got != "{id=3, name=A, class=C, member=m, z=1, a=2}" {
		t.Fatalf("properties %q", got)
	}
	if NodeProperty(node, "id") != int32(7) || NodeProperty(node, "values") != "{z=1, a=2, id=3}" {
		t.Fatalf("property precedence or order changed")
	}
}

func TestRawWTF8SourceKeepsOneSurrogate(t *testing.T) {
	result := execute(t, "RETURN '\xed\xa0\x80' AS x,size('\xed\xa0\x80') AS size", nil)
	assertResult(t, result, []string{"x", "size"}, []map[string]any{{"x": "?", "size": int32(1)}})
	result = execute(t, "RETURN '\xed\xa0\xb4\xed\xb4\x9e' AS x", nil)
	assertResult(t, result, []string{"x"}, []map[string]any{{"x": "𝄞"}})
}
