package query

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// These are query/storage integration controls, not captured JVM results.
// LeadingWorkFixture.java writes hit64 with one matching node (ID 10+64),
// whose caller_name and callee_name are both "hit-64". Main's mapped sequence
// charges each heap removal, flushes before yield, and flushes duplicates at EOF.
// Keep the separate actual-main public oracle as the acceptance evidence.
func TestMainMappedIterationBoundaries(t *testing.T) {
	fixtures := genericDisjunctionFixtures(t, "../../../docs/go-server-baseline/native-persisted-work-accounting/fixtures.tar.gz", 352)
	for _, mode := range []string{"exhaust", "limit", "budget", "cancel"} {
		t.Run(mode, func(t *testing.T) {
			graph, index, sid := mappedIterationFixture(t, fixtures, true)
			work, err := NewExecutionContext(10000)
			if err != nil {
				t.Fatal(err)
			}
			e := evaluator{ctx: context.Background(), work: work}
			plan := &mainStringSourceSpec{atoms: []distinctStringAtom{
				{property: "caller_name", op: "CONTAINS", term: "hit"},
				{property: "callee_name", op: "CONTAINS", term: "hit"},
			}}
			limit := 2
			if mode == "limit" {
				limit = 1
			}
			next, valid := e.mainSelectedMappedIDs(Graph{ID: "source0", Store: graph}, index, plan, []map[int32]bool{{sid: true}, {sid: true}}, limit)
			if !valid || next == nil {
				t.Fatal("real writer fixture declined mapped iteration")
			}
			if mode == "budget" {
				// Leave exactly the first yield's unit after eager range work.
				work.consume(10000 - work.Diagnostics().WorkUnitsConsumed - 1)
			}
			before := work.Diagnostics().WorkUnitsConsumed
			if id, ok := next(context.Background()); !ok || id != 74 {
				t.Fatalf("first mapped ID = %d/%t, want 74/true", id, ok)
			}
			if got := work.Diagnostics().WorkUnitsConsumed - before; got != 1 {
				t.Fatalf("first yield consumed %d units, want 1", got)
			}
			var reason *Error
			if mode == "cancel" {
				reason = &Error{Class: "CypherQueryCancelledException", Message: "cancel after mapped yield"}
				work.Cancel(reason)
			}
			_, failure := workAttempt(func() (any, error) {
				if id, ok := next(context.Background()); ok {
					t.Fatalf("duplicate escaped at EOF: %d", id)
				}
				return nil, nil
			})
			switch mode {
			case "budget":
				var queryError *Error
				if !errors.As(failure, &queryError) || queryError.Class != "CypherBudgetExceededException" {
					t.Fatalf("duplicate EOF flush must exceed budget: %v", failure)
				}
			case "cancel":
				if failure != reason {
					t.Fatalf("duplicate EOF flush lost cancellation: %v", failure)
				}
			default:
				if failure != nil {
					t.Fatal(failure)
				}
				want := int64(2)
				if mode == "limit" {
					want = 1 // take(1) never resumes the pending duplicate.
				}
				if got := work.Diagnostics().WorkUnitsConsumed - before; got != want {
					t.Fatalf("mapped iteration consumed %d units, want %d", got, want)
				}
			}
		})
	}
}

func TestMainMappedMatchingRetainedFallback(t *testing.T) {
	fixtures := genericDisjunctionFixtures(t, "../../../docs/go-server-baseline/native-persisted-work-accounting/fixtures.tar.gz", 352)
	graph, index, sid := mappedIterationFixture(t, fixtures, false)
	ready, err := index.HasProjectionTrigrams(context.Background())
	if err != nil || ready {
		t.Fatalf("fixture must begin with unprepared retained trigrams: %t, %v", ready, err)
	}
	work, err := NewExecutionContext(100000)
	if err != nil {
		t.Fatal(err)
	}
	e := evaluator{ctx: context.Background(), work: work}
	plan := &mainStringSourceSpec{atoms: []distinctStringAtom{{property: "caller_name", op: "CONTAINS", term: "hit"}}}
	want := []map[int32]bool{{sid: true}}
	for attempt := 0; attempt < 2; attempt++ {
		before := work.Diagnostics().WorkUnitsConsumed
		got, supported := e.mainExactMatches(Graph{ID: "source0", Store: graph}, index, plan)
		if !supported || !reflect.DeepEqual(got, want) {
			t.Fatalf("retained attempt %d: got %v/%t, want %v/true", attempt, got, supported, want)
		}
		used := work.Diagnostics().WorkUnitsConsumed - before
		if attempt == 0 && used <= 0 || attempt == 1 && used != 0 {
			t.Fatalf("retained attempt %d consumed %d: cold prepares/matches, warm matching-string cache is free", attempt, used)
		}
	}
}

func mappedIterationFixture(t *testing.T, fixtures string, mapped bool) (*store.Store, *store.DistinctStringIndex, int32) {
	t.Helper()
	dir := ordinaryCopyFixture(t, filepath.Join(fixtures, "hit64"))
	if !mapped {
		// A cold retained builder requires an absent sidecar. The archive and
		// mapped controls retain the actual writer's original persisted bytes.
		if err := os.Remove(filepath.Join(dir, "graph.callsite-string-index")); err != nil {
			t.Fatal(err)
		}
	}
	graph, err := store.OpenMode(dir, "MAPPED")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { graph.Close() })
	options := store.DistinctProjectionOptions{SourceCount: 40, Limit: 2, InitializeMappedView: mapped, RetainPersisted: !mapped}
	if !mapped {
		// One source with no prepared preference selects main's genuine builder,
		// rather than a cross-source raw fallback or the strict mapped reader.
		options.MainSource = true
		options.SourceCount = 1
		options.SkipPreparedPreference = true
	}
	index, ok, err := graph.PrepareDistinctStringIndex(context.Background(), options)
	if err != nil || !ok || index == nil || index.MainMappedCapability() != mapped {
		t.Fatalf("fixture preparation mapped=%t: %v/%t/%v", mapped, index, ok, err)
	}
	for sid, value := range graph.Strings {
		if value == "hit-64" {
			return graph, index, int32(sid)
		}
	}
	t.Fatal("actual writer fixture lost hit-64 string")
	return nil, nil, 0
}
