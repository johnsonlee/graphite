package query

import (
	"context"
	"errors"
	"fmt"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func TestRawProjectionProviderMain(t *testing.T) {
	var oracle []map[string]any
	readDistinctJSON(t, "testdata/raw-projection-cache/main.json", &oracle)
	var source Graph
	ctx := context.Background()
	queries := 0
	for _, want := range oracle {
		if fixture, ok := want["fixture"].(string); ok {
			path := "testdata/candidate-index/clean"
			if fixture == "split-clean" {
				path = "testdata/indexed-distinct/split-clean"
			}
			g, err := store.OpenMode(ordinaryCopyFixture(t, path), "MAPPED")
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { g.Close() })
			source = Graph{ID: fixture, Store: g}
			if len(g.NodesOfKind("CallSiteNode")) != int(want["callSiteCount"].(float64)) {
				t.Fatal("main fixture node count")
			}
			continue
		}
		name, ok := want["case"].(string)
		if !ok {
			continue
		}
		if name == "empty-hit-after-release" {
			if err := source.Store.ReleaseDistinctStringIndex(ctx); err != nil {
				t.Fatal(err)
			}
		}
		if name == "refill-after-clear" {
			if err := source.Store.ClearStringPropertyIndexes(ctx); err != nil {
				t.Fatal(err)
			}
		}
		if name == "interrupted-before-first-node" {
			canceled, cancel := context.WithCancel(ctx)
			cancel()
			func() {
				defer func() {
					err, ok := recover().(error)
					if !ok || !errors.Is(err, context.Canceled) {
						t.Fatalf("cancellation=%v", err)
					}
				}()
				plan := &ordinaryProjectionPlan{leading: true, indexedDistinctPlan: &indexedDistinctPlan{atoms: []distinctStringAtom{{property: "caller_name", op: "CONTAINS", term: ""}}, limit: 2}}
				evaluator{ctx: canceled}.ordinaryLeadingRows(source, plan, true)
			}()
			state, err := source.Store.StringPropertyIndexes(ctx)
			if err != nil || state.RawProjectionCount != 0 {
				t.Fatalf("cancellation published cache: %+v %v", state, err)
			}
			continue
		}
		queries++
		plan := &ordinaryProjectionPlan{leading: true, indexedDistinctPlan: &indexedDistinctPlan{
			atoms: []distinctStringAtom{{property: "caller_name", op: "CONTAINS", term: want["term"].(string)}}, limit: int(want["limit"].(float64)),
		}}
		for i, p := range want["properties"].([]any) {
			plan.properties = append(plan.properties, p.(string))
			plan.columns = append(plan.columns, fmt.Sprint(i))
		}
		rows, complete := (evaluator{ctx: ctx}).ordinaryLeadingRows(source, plan, true)
		if complete != (want["rows"] != nil) {
			t.Fatalf("%s completeness differs", name)
		}
		if complete {
			values := []any{}
			for _, row := range rows {
				cells := []any{}
				for _, column := range plan.columns {
					cells = append(cells, row[column])
				}
				values = append(values, map[string]any{"values": cells})
			}
			if !reflect.DeepEqual(values, want["rows"]) {
				t.Fatalf("%s rows=%v want=%v", name, values, want["rows"])
			}
		}
		state, err := source.Store.StringPropertyIndexes(ctx)
		entries := want["after"].([]any)
		if err != nil || state.RawProjectionCount != len(entries) || state.RawMatchCount != int(want["rawMatchCount"].(float64)) {
			t.Fatalf("%s state=%+v err=%v", name, state, err)
		}
		ids, hit, err := source.Store.RawProjectionMatches(ctx, ordinaryNodeKey(plan, plan.limit))
		if err != nil || hit != complete {
			t.Fatalf("%s published=%v err=%v", name, hit, err)
		}
		if complete {
			values := []any{}
			for _, id := range ids {
				values = append(values, float64(id))
			}
			if !reflect.DeepEqual(values, entries[len(entries)-1].(map[string]any)["ids"]) {
				t.Fatalf("%s IDs=%v", name, ids)
			}
		}
	}
	if queries != 7 {
		t.Fatalf("incomplete main query coverage: %d", queries)
	}
}

func TestRawProjectionKeyUsesMainPredicateIdentity(t *testing.T) {
	a := distinctStringAtom{property: "caller_name", op: "CONTAINS", term: "term"}
	b := distinctStringAtom{property: "callee_name", op: "CONTAINS", term: "term"}
	plan := &ordinaryProjectionPlan{indexedDistinctPlan: &indexedDistinctPlan{
		atoms: []distinctStringAtom{a, b}, properties: []string{"caller_name"}, columns: []string{"x"}, limit: 10,
	}}
	base := ordinaryNodeKey(plan, 10)
	plan.properties, plan.columns = []string{"callee_class"}, []string{"different"}
	if ordinaryNodeKey(plan, 10) != base {
		t.Fatal("projection must not participate in match identity")
	}
	if ordinaryNodeKey(plan, 11) == base {
		t.Fatal("limit must participate in match identity")
	}
	plan.atoms = []distinctStringAtom{b, a}
	if ordinaryNodeKey(plan, 10) == base {
		t.Fatal("predicate order must participate in match identity")
	}
	for _, variant := range []distinctStringAtom{
		{property: "caller_class", op: a.op, term: a.term},
		{property: a.property, op: "STARTS WITH", term: a.term},
		{property: a.property, op: a.op, term: "TERM"},
		{property: a.property, op: a.op, term: a.term, lower: true},
	} {
		plan.atoms = []distinctStringAtom{variant, b}
		if ordinaryNodeKey(plan, 10) == base {
			t.Fatalf("predicate identity collapsed: %+v", variant)
		}
	}
	a.term = "\U0001f600"
	plan.atoms = []distinctStringAtom{a}
	utf8 := ordinaryNodeKey(plan, 10)
	plan.atoms[0].term = "\xed\xa0\xbd\xed\xb8\x80"
	if ordinaryNodeKey(plan, 10) != utf8 {
		t.Fatal("equivalent Java UTF16 strings must share a cache entry")
	}
	plan.atoms[0].term = "\xed\xa0\xbd"
	if ordinaryNodeKey(plan, 10) == utf8 {
		t.Fatal("lone surrogate collapsed into surrogate pair")
	}
}
