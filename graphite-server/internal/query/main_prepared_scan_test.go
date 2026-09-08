package query

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

func TestMainPreparedWideSourceGate(t *testing.T) {
	open := func(missing bool) *store.Store {
		t.Helper()
		dir := ordinaryCopyFixture(t, "testdata/candidate-index/clean")
		if missing {
			if err := os.Remove(filepath.Join(dir, "graph.callsite-string-index")); err != nil {
				t.Fatal(err)
			}
		}
		graph, err := store.OpenMode(dir, "MAPPED")
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { graph.Close() })
		return graph
	}
	prepared, missing := open(false), open(true)
	plan := &ordinaryProjectionPlan{indexedDistinctPlan: &indexedDistinctPlan{atoms: []distinctStringAtom{{property: "caller_class", op: "CONTAINS", term: "java"}}}}
	// Repeated pointers are intentional: this tests the complete source-list
	// predicate, not execution or scheduling of forty independent graphs.
	sources := make([]Graph, 40)
	for i := range sources {
		sources[i] = Graph{ID: fmt.Sprintf("source%d", i), Store: prepared}
	}
	if !mainPreparedWide(plan, sources) {
		t.Fatal("all prepared sources declined")
	}
	for _, at := range []int{0, 19, 39} {
		t.Run(fmt.Sprintf("missing-source-%d", at), func(t *testing.T) {
			copy := append([]Graph(nil), sources...)
			copy[at].Store = missing
			if mainPreparedWide(plan, copy) {
				t.Fatalf("unprepared source %d was ignored", at)
			}
			copy[at].Store = nil
			if mainPreparedWide(plan, copy) {
				t.Fatalf("absent source %d was ignored", at)
			}
		})
	}
	for _, count := range []int{0, 1, 39} {
		if mainPreparedWide(plan, make([]Graph, count)) {
			t.Fatalf("unbalanced source count %d accepted", count)
		}
	}
	for _, atoms := range [][]distinctStringAtom{nil, {{property: "bogus", op: "CONTAINS", term: "java"}}, {plan.atoms[0], {property: "name", op: "CONTAINS", term: "java"}}} {
		unsupported := *plan
		unsupported.indexedDistinctPlan = &indexedDistinctPlan{atoms: atoms}
		if mainPreparedWide(&unsupported, sources) {
			t.Fatalf("unsupported ordinary predicate list accepted: %#v", atoms)
		}
	}
	// Main's capability is about supported properties, not CONTAINS-only
	// matcher eligibility. Other compiled string modes remain prepared.
	for _, op := range []string{"=", "STARTS WITH", "ENDS WITH"} {
		other := &ordinaryProjectionPlan{indexedDistinctPlan: &indexedDistinctPlan{atoms: []distinctStringAtom{{property: "callee_name", op: op, term: "a"}}}}
		if !mainPreparedWide(other, sources) {
			t.Fatalf("supported string mode %q declined", op)
		}
	}
	for _, graph := range []*store.Store{prepared, missing} {
		state, err := graph.StringPropertyIndexes(context.Background())
		if err != nil || !reflect.DeepEqual(state, store.StringPropertyIndexState{}) {
			t.Fatal("wide capability initialized storage", state, err)
		}
	}
	// Reuse the actual writer's Annotation fixture. A deliberately malformed
	// regular sidecar in this private copy suffices for file-presence admission.
	annotationDir := ordinaryCopyFixture(t, "testdata/indexed-distinct/annotation-after")
	if err := os.WriteFile(filepath.Join(annotationDir, "graph.callsite-string-index"), []byte("presence only"), 0600); err != nil {
		t.Fatal(err)
	}
	annotations, err := store.OpenMode(annotationDir, "MAPPED")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { annotations.Close() })
	if len(annotations.NodesOfKind("AnnotationNode")) == 0 {
		t.Fatal("writer fixture requires a nonempty Annotation type")
	}
	mixed := append([]Graph(nil), sources...)
	mixed[39].Store = annotations
	if !mainPreparedWide(plan, mixed) {
		t.Fatal("CallSite label must ignore Annotation capability")
	}
	generic := *plan
	generic.indexedDistinctPlan = &indexedDistinctPlan{atoms: plan.atoms, generic: true}
	if mainPreparedWide(&generic, mixed) {
		t.Fatal("generic query ignored the final source's Annotation capability")
	}
	state, err := annotations.StringPropertyIndexes(context.Background())
	if err != nil || !reflect.DeepEqual(state, store.StringPropertyIndexState{}) {
		t.Fatal("Annotation gate initialized malformed sidecar", state, err)
	}
}
