package query

import (
	"context"
	"errors"
	"fmt"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// The final eight edge-main.jsonl records cannot use encoding/json to recover
// Java key identity: lone surrogate keys would collapse to U+FFFD during decode.
// These assertions retain original UTF16 identities and the actual main order.
func TestGenericDistinctJavaAliasOrder(t *testing.T) {
	high, low := javaFromUTF16([]uint16{0xd800}), javaFromUTF16([]uint16{0xdc00})
	q := "MATCH (n:Annotation) WHERE n.caller_class STARTS WITH 'example' RETURN DISTINCT n.value AS `z`, 1 AS `?`, 2 AS `" + high + "`, 3 AS `a`, 4 AS `" + low + "`, 5 AS `z` LIMIT 1"
	for _, mode := range []string{"MAPPED", "EAGER"} {
		graph, err := store.OpenMode("testdata/generic-distinct/store", mode)
		if err != nil {
			t.Fatal(err)
		}
		for _, count := range []int{1, 2, 9, 40} {
			t.Run(fmt.Sprintf("%s/%d", mode, count), func(t *testing.T) {
				var r Result
				var err error
				if count == 1 {
					r, err = Execute(context.Background(), graph, q, nil, -1)
				} else {
					sources := make([]Graph, count)
					for i := range sources {
						id := fmt.Sprintf("a-%02d", i)
						if i == 0 {
							id = "z-first"
						}
						sources[i] = Graph{id, graph}
					}
					r, err = ExecuteCross(context.Background(), sources, q, nil, -1)
				}
				if err != nil {
					t.Fatal(err)
				}
				if !reflect.DeepEqual(r.ColumnKeys(), []string{"z", "?", high, "a", low, "z"}) {
					t.Fatalf("columns=%#v", r.ColumnKeys())
				}
				if len(r.Rows) != 1 {
					t.Fatalf("rows=%#v", r.Rows)
				}
				obj, ok := r.ResponseRow(0).(OutputObject)
				if !ok {
					t.Fatalf("object=%#v", r.ResponseRow(0))
				}
				keys := []string{"z", "?", high, "a", low}
				if count > 1 {
					keys = append(keys, "$metadata")
				}
				if !reflect.DeepEqual(obj.Keys, keys) {
					t.Fatalf("keys=%#v want=%#v", obj.Keys, keys)
				}
				for k, want := range map[string]any{"z": int32(5), "?": int32(1), high: int32(2), "a": int32(3), low: int32(4)} {
					if !reflect.DeepEqual(obj.Values[k], want) {
						t.Fatalf("%q=%#v want=%#v", k, obj.Values[k], want)
					}
				}
			})
		}
		graph.Close()
	}
}

func TestGenericDistinctCloseAtDemandBoundary(t *testing.T) {
	graph, err := store.OpenMode("testdata/generic-distinct/store", "MAPPED")
	if err != nil {
		t.Fatal(err)
	}
	defer graph.Close()
	ast, err := cypher.Parse("MATCH (n:Annotation) WHERE n.caller_class STARTS WITH 'example' RETURN DISTINCT n.value AS v LIMIT 2")
	if err != nil {
		t.Fatal(err)
	}
	e := evaluator{ctx: context.Background()}
	p := e.compileGenericDistinct(ast.Branches[0])
	if p == nil {
		t.Fatal("plan declined")
	}
	c := newGenericDistinctCursor(e, Graph{Store: graph}, p)
	defer c.close()
	v, ok := c.next(e.ctx)
	if !ok || v.(*candidateSlot).property("number") != int32(0) {
		t.Fatalf("first=%#v", v)
	}
	if err := graph.Close(); err != nil {
		t.Fatal(err)
	}
	defer func() {
		r := recover()
		err, ok := r.(error)
		if !ok || !errors.Is(err, store.ErrStoreClosed) {
			t.Fatalf("next after close=%#v", r)
		}
	}()
	c.next(e.ctx)
	t.Fatal("closed Store advanced")
}

func TestGenericDistinctEligibility(t *testing.T) {
	cases := []struct {
		q      string
		accept bool
	}{
		{"MATCH (n) WHERE n.caller_class STARTS WITH 'x' RETURN DISTINCT n.arbitrary AS a, null AS b LIMIT 2", true},
		{"MATCH (n:Annotation) WHERE n.name IN ['x','y'] RETURN DISTINCT n.value LIMIT 2", true},
		{"MATCH (n) WHERE toLower(coalesce(n.caller_class,'')) CONTAINS '' RETURN DISTINCT n.arbitrary LIMIT 2", false},
		{"MATCH (n) WHERE n.caller_class STARTS WITH 'x' RETURN DISTINCT auditUnknown(n) LIMIT 2", true},
		{"MATCH (n) WHERE n.caller_class STARTS WITH 'x' RETURN DISTINCT n.arbitrary ORDER BY n.arbitrary LIMIT 2", false},
		{"OPTIONAL MATCH (n) WHERE n.caller_class STARTS WITH 'x' RETURN DISTINCT n.arbitrary LIMIT 2", false},
		{"MATCH (n {x:1}) WHERE n.caller_class STARTS WITH 'x' RETURN DISTINCT n.arbitrary LIMIT 2", false},
		{"MATCH (n)-[r]->(m) WHERE n.caller_class STARTS WITH 'x' RETURN DISTINCT n.arbitrary LIMIT 2", false},
		{"MATCH (n) WHERE n.caller_class STARTS WITH 'x' RETURN DISTINCT n LIMIT 2", true},
	}
	for _, c := range cases {
		t.Run(c.q, func(t *testing.T) {
			ast, err := cypher.Parse(c.q)
			if err != nil {
				t.Fatal(err)
			}
			e := evaluator{ctx: context.Background()}
			got := e.compileGenericDistinct(ast.Branches[0]) != nil
			if got != c.accept {
				t.Fatalf("accept=%v want=%v", got, c.accept)
			}
		})
	}
}
