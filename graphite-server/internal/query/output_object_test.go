package query

import (
	"context"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"reflect"
	"testing"
)

func TestMaterializedAnnotationKeepsCollidingJavaKeys(t *testing.T) {
	high0, high1 := "\xed\xa0\x80", "\xed\xa0\x81"
	node := store.Node{ID: 7, Kind: "AnnotationNode", Name: "A", Values: map[string]any{high0: int32(1), high1: int32(2), "?": int32(3)}, ValueOrder: []string{high1, "?", high0}}
	e := evaluator{ctx: context.Background()}
	for _, input := range []any{node, qualifiedNode{GraphID: "a", Node: node}} {
		result, ok := e.materialize(input).(OutputObject)
		if !ok {
			t.Fatalf("lost ordered members: %#v", result)
		}
		keys := []string{}
		values := []any{}
		for _, key := range result.Keys {
			if javaWireString(key) == "?" {
				keys = append(keys, key)
				values = append(values, result.Values[key])
			}
		}
		if !reflect.DeepEqual(keys, node.ValueOrder) || !reflect.DeepEqual(values, []any{int32(2), int32(3), int32(1)}) {
			t.Fatalf("members %#v %#v", keys, values)
		}
		if _, qualified := input.(qualifiedNode); qualified {
			if result.Values["graphId"] != "a" || result.Values["id"] != node.ID {
				t.Fatalf("qualified identity %#v", result.Values)
			}
		}
	}
	if len(node.Values) != 3 || !reflect.DeepEqual(node.ValueOrder, []string{high1, "?", high0}) {
		t.Fatal("mutated source annotation")
	}
}
func TestOutputObjectOrdinaryMapCompatibility(t *testing.T) {
	e := evaluator{ctx: context.Background()}
	for _, input := range []any{orderedMap{Values: map[string]any{"x": int32(1)}, Keys: []string{"x"}}, map[string]any{"x": int32(1)}} {
		if got := e.materialize(input); !reflect.DeepEqual(got, map[string]any{"x": int32(1)}) {
			t.Fatalf("ordinary map API changed: %#v", got)
		}
	}
}
