package query

import (
	"context"
	"errors"
	"math"
	"path/filepath"
	"reflect"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// These are source-protocol controls against main MappedWebGraphBackedGraph
// 535-618/1619-1656, using its archived writer fixtures. They are not newly
// observed public DISTINCT JVM results, nor performance measurements.
func TestDistinctSplitRawWorker(t *testing.T) {
	fixtures := genericDisjunctionFixtures(t, "../../../docs/go-server-baseline/native-raw-work-batches/fixtures.tar.gz", 329)
	for _, tc := range []struct {
		name, fixture, class string
		budget, used         int64
		want                 []string
	}{
		{"early-limit", "call-first", "", 1, 1, []string{"hit-0"}},
		{"last-hit", "call-last", "", 1025, 1025, []string{"hit-1024"}},
		{"miss", "call-miss", "", 1025, 1025, nil},
		{"last-flush-budget", "call-last", "CypherBudgetExceededException", 1024, 1024, nil},
		{"bad-first-SID", "call-bad0", "ArrayIndexOutOfBoundsException", 1025, 1, nil},
		{"bad-last-SID", "call-bad1024", "ArrayIndexOutOfBoundsException", 1025, 1025, nil},
		{"finally-budget-overrides-bad-SID", "call-bad1024", "CypherBudgetExceededException", 1024, 1024, nil},
		{"full-batch-budget-before-bad-SID", "call-bad1023", "CypherBudgetExceededException", 1023, 1023, nil},
	} {
		t.Run(tc.name, func(t *testing.T) {
			source := distinctSplitTestSource(t, filepath.Join(fixtures, tc.fixture))
			work, _ := NewExecutionContext(tc.budget)
			e := evaluator{ctx: context.Background(), work: work}
			plan := distinctSplitTestPlan("caller_name", "hit-")
			states := newDistinctSplitMatchStates(plan.atoms, len(source.Store.Strings))
			var abort atomic.Bool
			var got []distinctProjectedRow
			failure := findIDCaught(func() {
				got = e.distinctSplitRawRange(source, plan, source.Store.NodesOfKind("CallSiteNode"), nil, nil, 1, states, &abort)
			})
			distinctSplitTestError(t, failure, tc.class)
			if work.Diagnostics().WorkUnitsConsumed != tc.used || abort.Load() != (failure != nil) {
				t.Fatalf("work=%+v abort=%t", work.Diagnostics(), abort.Load())
			}
			var values []string
			for _, row := range got {
				values = append(values, row.row["value"].(string))
				if !reflect.DeepEqual(provenance(row.row), []string{"fixture"}) {
					t.Fatalf("provenance lost: %#v", row.row)
				}
			}
			if !reflect.DeepEqual(values, tc.want) {
				t.Fatalf("rows=%v want %v", values, tc.want)
			}
		})
	}
	for _, tc := range []struct {
		name    string
		reject  int64
		budget  int64
		used    int64
		aborted bool
	}{
		{"preinterrupt", 1, 1025, 0, false},
		{"next-poll-before-last-bad-SID", 2, 1025, 1024, false},
		{"sibling-abort-before-worker-poll", 0, 1025, 0, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			source := distinctSplitTestSource(t, filepath.Join(fixtures, "call-bad1024"))
			ctx := &distinctSplitTestContext{Context: context.Background(), reject: tc.reject}
			work, _ := NewExecutionContext(tc.budget)
			plan := distinctSplitTestPlan("caller_name", "absent")
			var abort atomic.Bool
			abort.Store(tc.aborted)
			failure := findIDCaught(func() {
				(evaluator{ctx: ctx, work: work}).distinctSplitRawRange(source, plan, source.Store.NodesOfKind("CallSiteNode"), nil, nil, 1, newDistinctSplitMatchStates(plan.atoms, len(source.Store.Strings)), &abort)
			})
			err := distinctSplitTestError(t, failure, "CancellationException")
			if err.Message != "Mapped string-property scan interrupted" || !errors.Is(err, context.Canceled) || work.Diagnostics().WorkUnitsConsumed != tc.used || ctx.polls.Load() != tc.reject {
				t.Fatalf("failure=%#v work=%+v polls=%d", err, work.Diagnostics(), ctx.polls.Load())
			}
		})
	}
	t.Run("unused-bad-SID-and-selected-filter", func(t *testing.T) {
		source := distinctSplitTestSource(t, filepath.Join(fixtures, "call-bad0"))
		id := source.Store.NodesOfKind("CallSiteNode")[0]
		sids, err := source.Store.MainDistinctProjectionStringIDs(id)
		if err != nil || sids[1] != math.MaxInt32 {
			t.Fatalf("bad caller_name fixture identity: %v/%v", sids, err)
		}
		plan := distinctSplitTestPlan("callee_name", "miss-")
		exact := map[int]map[int32]bool{3: {sids[3]: true}}
		work, _ := NewExecutionContext(3)
		e := evaluator{ctx: context.Background(), work: work}
		rows := e.distinctSplitRawRange(source, plan, []int32{id}, exact, nil, 1, nil, nil)
		if len(rows) != 1 || rows[0].row["value"] != "miss-0" {
			t.Fatalf("unused caller SID must not be validated: %#v", rows)
		}
		plan.properties[0] = "caller_name"
		rows = e.distinctSplitRawRange(source, plan, []int32{id}, exact, map[string]bool{key([]any{int32(-1)}): true}, 1, nil, nil)
		if len(rows) != 0 || work.Diagnostics().WorkUnitsConsumed != 2 {
			t.Fatalf("tuple exclusion must precede bad projected SID: %#v/%+v", rows, work.Diagnostics())
		}
		rows = e.distinctSplitRawRange(source, plan, []int32{id}, map[int]map[int32]bool{}, nil, 1, nil, nil)
		if len(rows) != 0 || work.Diagnostics().WorkUnitsConsumed != 3 {
			t.Fatal("an exact miss read a projected/unused SID")
		}
	})
	t.Run("duplicate-alias-preserves-storage-tuple", func(t *testing.T) {
		source := distinctSplitTestSource(t, filepath.Join(fixtures, "call-firstlast"))
		plan := distinctSplitTestPlan("caller_name", "hit-")
		plan.properties = []string{"caller_name", "caller_class"}
		plan.columns = []string{"value", "value"}
		work, _ := NewExecutionContext(1025)
		rows := (evaluator{ctx: context.Background(), work: work}).distinctSplitRawRange(source, plan, source.Store.NodesOfKind("CallSiteNode"), nil, nil, 2, newDistinctSplitMatchStates(plan.atoms, len(source.Store.Strings)), nil)
		if len(rows) != 2 || rows[0].row["value"] != rows[1].row["value"] || rows[0].storageKey == rows[1].storageKey {
			t.Fatalf("duplicate aliases discarded the distinct storage tuple: %#v", rows)
		}
		for i, name := range []string{"hit-0", "hit-1024"} {
			if rows[i].storageKey != key([]any{name, rows[i].row["value"]}) {
				t.Fatalf("storage tuple %d: %q", i, rows[i].storageKey)
			}
		}
	})
	t.Run("callback-first-cause-overrides-decode", func(t *testing.T) {
		source := distinctSplitTestSource(t, filepath.Join(fixtures, "call-bad0"))
		work, _ := NewExecutionContext(1)
		cause := &Error{Class: "CustomCancellation", Message: "original callback"}
		work.Cancel(cause)
		plan := distinctSplitTestPlan("caller_name", "miss-")
		failure := findIDCaught(func() {
			(evaluator{ctx: context.Background(), work: work}).distinctSplitRawRange(source, plan, source.Store.NodesOfKind("CallSiteNode"), nil, nil, 1, newDistinctSplitMatchStates(plan.atoms, len(source.Store.Strings)), nil)
		})
		if failure != cause || work.Diagnostics().WorkUnitsConsumed != 0 {
			t.Fatalf("finally did not preserve original callback cause: %#v", failure)
		}
	})
	t.Run("shared-states-concurrent-segments", func(t *testing.T) {
		source := distinctSplitTestSource(t, filepath.Join(fixtures, "call-firstlast"))
		plan := distinctSplitTestPlan("caller_name", "hit-")
		plan.atoms = append(plan.atoms, distinctStringAtom{property: "callee_name", op: "CONTAINS", term: "hit-"})
		states := newDistinctSplitMatchStates(plan.atoms, len(source.Store.Strings))
		ids := source.Store.NodesOfKind("CallSiteNode")
		work, _ := NewExecutionContext(8 * 1025)
		var wg sync.WaitGroup
		var abort atomic.Bool
		for range 8 {
			wg.Add(1)
			go func() {
				defer wg.Done()
				rows := (evaluator{ctx: context.Background(), work: work}).distinctSplitRawRange(source, plan, ids, nil, nil, 2, states, &abort)
				if len(rows) != 2 || rows[0].row["value"] != "hit-0" || rows[1].row["value"] != "hit-1024" {
					t.Errorf("shared-state rows: %#v", rows)
				}
			}()
		}
		wg.Wait()
		if abort.Load() || work.Diagnostics().WorkUnitsConsumed != 8*1025 {
			t.Fatalf("shared worker outcome: abort=%t work=%+v", abort.Load(), work.Diagnostics())
		}
	})
}

func TestDistinctSplitCannotMatch(t *testing.T) {
	source := distinctSplitTestSource(t, "testdata/indexed-distinct/split-clean")
	atoms := []distinctStringAtom{{property: "caller_name", op: "CONTAINS", term: "absent-long-needle-for-preflight"}, {property: "callee_name", op: "CONTAINS", term: "absent-long-needle-for-preflight"}}
	work, _ := NewExecutionContext(int64(len(source.Store.Strings)))
	ctx := &distinctSplitTestContext{Context: context.Background()}
	cannot, eligible := (evaluator{ctx: ctx, work: work}).distinctSplitCannotMatch(source, atoms)
	if !cannot || !eligible || work.Diagnostics().WorkUnitsConsumed != int64(len(source.Store.Strings)) {
		t.Fatalf("one shared predicate must prove absence: %t/%t/%+v", cannot, eligible, work.Diagnostics())
	}
	for _, bad := range []distinctStringAtom{{property: "callee_name", op: "=", term: atoms[0].term}, {property: "callee_name", op: "CONTAINS", term: "short"}} {
		work, _ := NewExecutionContext(1)
		ctx := &distinctSplitTestContext{Context: context.Background(), reject: 1}
		cannot, eligible := (evaluator{ctx: ctx, work: work}).distinctSplitCannotMatch(source, append(atoms[:1:1], bad))
		if cannot || eligible || ctx.polls.Load() != 0 || work.Diagnostics().WorkUnitsConsumed != 0 {
			t.Fatal("whole-predicate admission touched a rejected preflight")
		}
	}
	work, _ = NewExecutionContext(int64(len(source.Store.Strings)))
	ctx = &distinctSplitTestContext{Context: context.Background(), reject: 2}
	failure := findIDCaught(func() { (evaluator{ctx: ctx, work: work}).distinctSplitCannotMatch(source, atoms) })
	distinctSplitTestError(t, failure, "CancellationException")
	if work.Diagnostics().WorkUnitsConsumed != 0 || ctx.polls.Load() != 2 {
		t.Fatal("predicate-entry and SID-zero polls must both precede first consume")
	}
}

func TestDistinctSplitStringUTF16(t *testing.T) {
	for _, tc := range []struct {
		op, text, term string
		lower, want    bool
	}{
		{"CONTAINS", "ABC", "bc", true, true}, {"CONTAINS", "ABC", "BC", true, false},
		{"STARTS WITH", "😀x", "\xed\xa0\xbd", false, true}, {"ENDS WITH", "x😀", "\xed\xb8\x80", false, true},
		{"=", "😀", "\xed\xa0\xbd\xed\xb8\x80", false, true}, {"CONTAINS", "", "", false, true},
		{"STARTS WITH", "ΟΣ", "ος", true, true}, {"ENDS WITH", "ABC", "bc", true, true},
	} {
		if got := distinctSplitStringMatches(distinctStringAtom{op: tc.op, term: tc.term, lower: tc.lower}, tc.text); got != tc.want {
			t.Errorf("%q %s %q lower=%t: %t", tc.text, tc.op, tc.term, tc.lower, got)
		}
	}
}

type distinctSplitTestContext struct {
	context.Context
	polls  atomic.Int64
	reject int64
}

func (c *distinctSplitTestContext) Err() error {
	count := c.polls.Add(1)
	if c.reject > 0 && count >= c.reject {
		return context.Canceled
	}
	return nil
}

func distinctSplitTestSource(t *testing.T, fixture string) Graph {
	t.Helper()
	g, err := store.OpenMode(ordinaryCopyFixture(t, fixture), "MAPPED")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { g.Close() })
	return Graph{ID: "fixture", Store: g}
}

func distinctSplitTestPlan(property, term string) *indexedDistinctPlan {
	return &indexedDistinctPlan{sourceCount: 40, limit: 200, atoms: []distinctStringAtom{{property: property, op: "CONTAINS", term: term}}, properties: []string{property}, columns: []string{"value"}}
}

func distinctSplitTestError(t *testing.T, failure any, class string) *Error {
	t.Helper()
	if class == "" {
		if failure != nil {
			t.Fatalf("unexpected failure %#v", failure)
		}
		return nil
	}
	err, ok := failure.(*Error)
	if !ok || err.Class != class {
		t.Fatalf("failure=%#v want %s", failure, class)
	}
	return err
}
