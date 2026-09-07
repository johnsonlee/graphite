package query

import (
	"context"
	"errors"
	"fmt"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func targetStringTestPlan(properties, columns []string) *indexedDistinctPlan {
	return &indexedDistinctPlan{properties: properties, columns: columns}
}
func targetStringPanic(f func()) (caught any) { defer func() { caught = recover() }(); f(); return nil }
func targetStringFirst(table []string, text string) int32 {
	for i, s := range table {
		if s == text {
			return int32(i)
		}
	}
	return -1
}

func TestDistinctTargetStringsFirstSIDWTF8AndWantedOnly(t *testing.T) {
	hi := "\xed\xa0\x80"
	lo := "\xed\xb0\x80"
	table := []string{"", "dup", "dup", "?", hi, lo, "😀", "İ", "i", "unwanted"}
	targets := []string{"dup", hi, "?", lo, "😀", "missing", "", "İ", "i", "dup"}
	rows := []map[string]any{}
	for _, s := range targets {
		rows = append(rows, map[string]any{"x": s})
	}
	plan := targetStringTestPlan([]string{"caller_class"}, []string{"x"})
	checks := 0
	for _, mask := range []int{0, 1023} {
		resolver := newDistinctTargetStrings(table, plan, rows, func() { checks++ }, mask)
		if checks != 0 || resolver.next != 0 {
			t.Fatal("constructor inspected table or called cancellation")
		}
		for _, target := range targets {
			got := resolver.find(target)
			want := targetStringFirst(table, target)
			if got != want {
				t.Fatalf("mask%d %q got%d want%d", mask, target, got, want)
			}
		}
		if resolver.wanted["dup"] != 1 || resolver.wanted[""] != 0 || resolver.wanted[hi] != 4 || resolver.wanted[lo] != 5 || resolver.wanted["?"] != 3 {
			t.Fatal(resolver.wanted)
		}
		if _, kept := resolver.wanted["unwanted"]; kept {
			t.Fatal("entire table cached")
		}
		if resolver.wanted["missing"] != -1 {
			t.Fatal("missing SID")
		}
		checks = 0
	}
}
func TestDistinctTargetStringsAliasesNullAndSourceLocality(t *testing.T) {
	plan := targetStringTestPlan([]string{"caller_class", "callee_name", "graphId", "class", "caller_name"}, []string{"x", "x", "graph", "nullable", "empty"})
	rows := []map[string]any{{"x": "last-wins", "graph": "source", "nullable": "invalid-but-not-prevalidated", "empty": ""}, {"x": nil, "empty": 42}}
	resolver := newDistinctTargetStrings([]string{"discarded-first", "last-wins", "", "source"}, plan, rows, func() {}, 1023)
	if !reflect.DeepEqual(resolver.wanted, map[string]int32{"last-wins": -1, "": -1}) {
		t.Fatalf("wanted=%v", resolver.wanted)
	}
	// Both duplicate columns demand the row map's final value; field order stays.
	got := []int32{resolver.find(rows[0][plan.columns[0]].(string)), resolver.find(rows[0][plan.columns[1]].(string)), resolver.find(rows[0][plan.columns[4]].(string))}
	if !reflect.DeepEqual(got, []int32{1, 1, 2}) {
		t.Fatal(got)
	}
	other := newDistinctTargetStrings([]string{"last-wins", ""}, plan, rows, func() {}, 1023)
	if other.find("last-wins") != 0 || resolver.find("last-wins") != 1 {
		t.Fatal("SIDs shared across sources")
	}
}
func TestDistinctTargetStringsCachedAndMissingCancellation(t *testing.T) {
	plan := targetStringTestPlan([]string{"caller_class"}, []string{"x"})
	rows := []map[string]any{{"x": "a"}, {"x": "b"}, {"x": "missing"}}
	for _, mask := range []int{0, 1023} {
		ctx, cancel := context.WithCancel(context.Background())
		e := evaluator{ctx: ctx}
		resolver := newDistinctTargetStrings([]string{"a", "b"}, plan, rows, e.check, mask)
		if resolver.find("b") != 1 || resolver.find("missing") != -1 {
			t.Fatal("prepare")
		}
		cancel()
		for _, text := range []string{"a", "b", "missing"} {
			caught := targetStringPanic(func() { resolver.find(text) })
			err, ok := caught.(error)
			if !ok || !errors.Is(err, context.Canceled) {
				t.Fatalf("mask%d text%s panic=%v", mask, text, caught)
			}
		}
	}
}
func TestDistinctTargetStringsEmptyTableAndNoRawColumns(t *testing.T) {
	plan := targetStringTestPlan([]string{"caller_class"}, []string{"x"})
	for _, table := range [][]string{nil, {}} {
		checks := 0
		resolver := newDistinctTargetStrings(table, plan, []map[string]any{{"x": ""}, {"x": "missing"}}, func() { checks++; panic(context.Canceled) }, 0)
		if resolver.find("") != -1 || resolver.find("missing") != -1 || checks != 0 {
			t.Fatal("empty original scans must not add polls")
		}
	}
	checks := 0
	resolver := newDistinctTargetStrings([]string{"a"}, targetStringTestPlan([]string{"graphId", "class", "name"}, []string{"a", "b", "c"}), []map[string]any{{"a": "g", "b": nil, "c": "x"}}, func() { checks++ }, 1023)
	if len(resolver.wanted) != 0 || resolver.next != 0 || checks != 0 {
		t.Fatal("nonraw columns triggered lookup")
	}
}
func TestDistinctTargetStringsLateLookupDoesNotPreemptActualPostingsError(t *testing.T) {
	graph, err := store.Open("../store/testdata/callsite-index/store")
	if err != nil {
		t.Fatal(err)
	}
	index, ok, err := graph.PrepareDistinctStringIndex(context.Background(), store.DistinctProjectionOptions{SourceCount: 1, Limit: 1})
	if err != nil || !ok {
		t.Fatal(ok, err)
	}
	if err := graph.Close(); err != nil {
		t.Fatal(err)
	}
	// Actual index.Postings must still observe Close at its original call point.
	table := make([]string, 1025)
	for i := range table {
		table[i] = "filler"
	}
	table[0] = "early"
	table[1024] = "late"
	plan := targetStringTestPlan([]string{"caller_class"}, []string{"x"})
	rows := []map[string]any{{"x": "early"}, {"x": "late"}}
	for _, mask := range []int{0, 1023} {
		polls := 0
		check := func() {
			polls++
			if polls == 2 {
				panic(context.Canceled)
			}
		}
		resolver := newDistinctTargetStrings(table, plan, rows, check, mask)
		if polls != 0 {
			t.Fatal("wanted collection polled")
		}
		sid := resolver.find("early")
		if sid != 0 || resolver.next != 1 || polls != 1 {
			t.Fatalf("premature scan sid%d next%d polls%d", sid, resolver.next, polls)
		}
		_, err := index.Postings(context.Background(), store.CallerClass, sid)
		if !errors.Is(err, store.ErrStoreClosed) {
			t.Fatalf("actual first Postings error changed: %v", err)
		}
		// A later lookup would encounter the scheduled cancellation, but it cannot
		// mask the storage error from the earlier operation above.
		caught := targetStringPanic(func() { resolver.find("late") })
		if caught != context.Canceled {
			t.Fatal(caught)
		}
		t.Logf("mask%d: first Postings=%v; later lookup=%v", mask, err, caught)
	}
}
func TestDistinctTargetStringsPollingContinuesAcrossForwardCursor(t *testing.T) {
	table := make([]string, 2050)
	for i := range table {
		table[i] = fmt.Sprint(i)
	}
	plan := targetStringTestPlan([]string{"caller_class"}, []string{"x"})
	rows := []map[string]any{{"x": "1023"}, {"x": "2049"}}
	polls := 0
	resolver := newDistinctTargetStrings(table, plan, rows, func() { polls++ }, 1023)
	if resolver.find("1023") != 1023 || polls != 1 {
		t.Fatal("first boundary", polls)
	}
	if resolver.find("2049") != 2049 || polls != 3 {
		t.Fatal("resumed boundary", polls)
	}
	// Cache hit polls once and leaves the source cursor untouched.
	before := resolver.next
	if resolver.find("1023") != 1023 || polls != 4 || resolver.next != before {
		t.Fatal("cached boundary", polls)
	}
}
