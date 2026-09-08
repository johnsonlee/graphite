package query

import (
	"context"
	"reflect"
	"slices"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// The frozen actual-main E07/E08 storage captures establish the cached miss/hit
// contract. Prefix/suffix and the whole-predicate gate below are source-derived
// correctness controls, not additional JVM captures or performance fixtures.
func TestMainRetainedEntryCachedPreflight(t *testing.T) {
	fixtures := genericDisjunctionFixtures(t, "../../../docs/go-server-baseline/native-persisted-work-accounting/fixtures.tar.gz", 352)
	for _, tc := range []struct {
		name, op, term string
		want           []int32
		work           int64
	}{
		{"miss", "CONTAINS", "absentzz", []int32{}, 0},
		{"hit", "CONTAINS", "hit", []int32{74}, 1},
		{"prefix", "STARTS WITH", "hit", []int32{74}, 1},
		{"suffix", "ENDS WITH", "-64", []int32{74}, 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			graph, index, _ := mappedIterationFixture(t, fixtures, false)
			source := Graph{Store: graph}
			plan := &mainStringSourceSpec{lazyMain: true, sourceCount: 40, forcePersisted: true,
				atoms: []distinctStringAtom{{property: "caller_name", op: tc.op, term: tc.term}}}
			collect := func(next mainNodeNext, ctx context.Context) []int32 {
				ids := []int32{}
				for node, ok := next(ctx); ok; node, ok = next(ctx) {
					ids = append(ids, node.ID)
				}
				return ids
			}
			warm := evaluator{ctx: context.Background()}
			if got := collect(warm.mainCandidateIterator(source, plan, 2), warm.ctx); !reflect.DeepEqual(got, tc.want) {
				t.Fatalf("preferred-retained warmup IDs %v, want %v", got, tc.want)
			}
			cached, ok, err := index.MainProjectionCachedIDs(store.ProjectionNodeMatches, mainNodeKey(plan, 2))
			if err != nil || !ok || !slices.Equal(cached, tc.want) {
				t.Fatalf("warmup must publish the real node cache: %v/%t/%v", cached, ok, err)
			}
			ctx, cancel := context.WithCancel(context.Background())
			cancel()
			work, err := NewExecutionContext(100)
			if err != nil {
				t.Fatal(err)
			}
			plan.forcePersisted = false
			e := evaluator{ctx: ctx, work: work}
			if got := collect(e.mainCandidateIterator(source, plan, 2), ctx); !reflect.DeepEqual(got, tc.want) {
				t.Fatalf("interrupted cached IDs %v, want %v", got, tc.want)
			}
			if got := work.Diagnostics().WorkUnitsConsumed; got != tc.work || work.IsCancelled() || ctx.Err() != context.Canceled {
				t.Fatalf("cached work=%d, want %d; requestCanceled=%t worker=%v", got, tc.work, work.IsCancelled(), ctx.Err())
			}
		})
	}
}

func TestMainRetainedEntryChecksAllPredicatesBeforeLookup(t *testing.T) {
	fixtures := genericDisjunctionFixtures(t, "../../../docs/go-server-baseline/native-persisted-work-accounting/fixtures.tar.gz", 352)
	graph, index, _ := mappedIterationFixture(t, fixtures, false)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	for _, unsupported := range []distinctStringAtom{
		{property: "caller_name", op: "=", term: "hit-64"},
		{property: "caller_name", op: "CONTAINS", term: "hi"},
		{property: "caller_name", op: "CONTAINS", term: "日本語"},
	} {
		for _, first := range []bool{false, true} {
			atoms := []distinctStringAtom{{property: "caller_name", op: "CONTAINS", term: "hit"}, unsupported}
			if first {
				atoms[0], atoms[1] = atoms[1], atoms[0]
			}
			work, err := NewExecutionContext(100)
			if err != nil {
				t.Fatal(err)
			}
			e := evaluator{ctx: ctx, work: work}
			sets, supported := e.mainRetainedExactMatches(Graph{Store: graph}, index, &mainStringSourceSpec{atoms: atoms})
			if sets != nil || supported || work.Diagnostics().WorkUnitsConsumed != 0 {
				t.Fatalf("unsupported disjunction touched exact lookup: %v/%t/%+v", sets, supported, work.Diagnostics())
			}
			eligible := distinctStringAtom{property: "caller_name", op: "CONTAINS", term: "hit"}
			if _, cached, err := index.MainProjectionCachedIDs(store.ProjectionStringMatches, ordinaryStringKey(eligible)); err != nil || cached {
				t.Fatalf("unsupported predicate list populated eligible cache: cached=%t err=%v", cached, err)
			}
		}
	}
}
