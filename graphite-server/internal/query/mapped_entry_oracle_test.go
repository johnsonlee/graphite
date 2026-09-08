package query

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

type mappedEntryMap = map[string]any

// This opt-in artifact capture targets mainCandidateIterator's complete storage
// nodes, never Execute/RETURN projection. hasNext does one real candidate pull
// and buffers it; next returns that buffer, matching Kotlin mapNotNull demand.
// Iterator-construction is explicitly an adapter initialization phase.
func TestMappedEntryCapture(t *testing.T) {
	out := os.Getenv("GRAPHITE_MAPPED_ENTRY_OUTPUT")
	if out == "" {
		t.Skip("requires checks/mapped-entry-capture/run.py; not part of ordinary module acceptance")
	}
	const base = "../../../docs/go-server-baseline/native-persisted-work-accounting/mapped-entry-oracle"
	var records []mappedEntryMap
	var excluded []string
	totalDiffs := 0
	for _, group := range []string{base, filepath.Join(base, "warm-range-supplement")} {
		var main, repeat mappedEntryMap
		workReadJSON(t, filepath.Join(group, "main.json"), &main)
		workReadJSON(t, filepath.Join(group, "repeat-capture/main.json"), &repeat)
		if !reflect.DeepEqual(main, repeat) {
			t.Fatal("actual JVM original repeat differs")
		}
		fixtures := genericDisjunctionFixtures(t, filepath.Join(group, "fixtures.tar.gz"), 17)
		for _, raw := range main["cases"].([]any) {
			want := raw.(map[string]any)
			spec := want["spec"].(map[string]any)
			name := spec["name"].(string)
			if !mappedEntryInstrumented && spec["action"] != "none" {
				excluded = append(excluded, name)
				continue
			}
			t.Run(name, func(t *testing.T) {
				dir := ordinaryCopyFixture(t, filepath.Join(fixtures, "hit64"))
				graph, err := store.OpenMode(dir, "MAPPED")
				if err != nil {
					t.Fatal(err)
				}
				got := mappedEntryMap{"name": name, "spec": spec, "fixtureDirectory": dir}
				var diffs []mappedEntryMap
				if warm, ok := want["warmup"].(map[string]any); ok {
					actual := mappedEntryLookup(t, graph, warm)
					got["warmup"] = actual
					diffs = append(diffs, mappedEntryDifferences("warmup", mappedEntryComparableLookup(actual, false, mappedEntryInstrumented), mappedEntryComparableLookup(warm, true, mappedEntryInstrumented))...)
				}
				actual := mappedEntryLookup(t, graph, want["probe"].(map[string]any))
				got["probe"] = actual
				diffs = append(diffs, mappedEntryDifferences("probe", mappedEntryComparableLookup(actual, false, mappedEntryInstrumented), mappedEntryComparableLookup(want["probe"].(map[string]any), true, mappedEntryInstrumented))...)
				got["beforeClose"] = mappedEntryStorage(graph)
				diffs = append(diffs, mappedEntryDifferences("beforeClose", got["beforeClose"], mappedEntryExpectedStorage(want["beforeClose"].(map[string]any)))...)
				closeErr := graph.Close()
				got["closeError"] = mappedEntryNativeError(closeErr)
				got["afterClose"] = mappedEntryStorage(graph)
				got["jvmObserverOnlyAfterClose"] = want["afterClose"]
				got["differences"] = diffs
				records = append(records, got)
				totalDiffs += len(diffs)
				if len(diffs) > 0 {
					t.Errorf("%d actual-main differences; first %v", len(diffs), diffs[0])
				}
			})
		}
	}
	result := mappedEntryMap{"scope": "storage-node adapter; no public Cypher execution", "instrumented": mappedEntryInstrumented, "cases": records, "excludedActionCases": excluded, "differences": totalDiffs, "availability": mappedEntryMap{"iteratorConstruction": "adapter initialization, not a native Go Sequence API", "workCallbacks": mappedEntryInstrumented, "javaStackAndQualifiedClass": "retained raw JVM evidence; native raw type recorded separately", "fullJavaNodeShape": "unavailable; actual native Node archived and nodeId/kind compared", "lookupCounters": "callSiteParallelScanCount/callSiteStringLookupEntryCount/callSiteStringIndexLookupCount unavailable", "afterClosePrivateState": "unavailable in Go; public observer ErrStoreClosed retained"}}
	b, err := json.MarshalIndent(result, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(out, append(b, '\n'), 0600); err != nil {
		t.Fatal(err)
	}
}

func mappedEntryStorage(graph *store.Store) mappedEntryMap {
	index, err := graph.StringPropertyIndexes(context.Background())
	if err != nil {
		return mappedEntryMap{"observerError": err.Error()}
	}
	persisted, err := graph.PersistedIndexState(context.Background())
	if err != nil {
		return mappedEntryMap{"observerError": err.Error()}
	}
	return mappedEntryMap{"isCallSiteStringIndexInitialized": index.Retained, "isMappedCallSiteStringIndexViewInitialized": index.MappedView, "mappedPostingRangeValidationCount": index.MappedRangeCount, "rawStringMatchStateCount": index.RawMatchCount, "rawProjectionMatchCount": index.RawProjectionCount, "isCallSiteTrigramIndexInitialized": index.Trigrams, "isCallSiteStringIndexLoadedFromPersistence": index.LoadedFromPersistence, "mappedViewUnavailable": persisted.MappedViewUnavailable, "retainedPreference": persisted.RetainPreference, "matchingStringIdsCount": persisted.MatchingStringIDsCount, "matchingNodeIdsCount": persisted.MatchingNodeIDsCount, "projectedRowsCount": persisted.ProjectedRowsCount}
}
func mappedEntryExpectedStorage(raw mappedEntryMap) mappedEntryMap {
	out := mappedEntryMap{}
	for k, v := range raw {
		switch k {
		case "callSiteParallelScanCount", "callSiteStringLookupEntryCount", "callSiteStringIndexLookupCount":
		default:
			out[k] = v
		}
	}
	return out
}
func mappedEntryNativeError(err error) any {
	if err == nil {
		return nil
	}
	class := fmt.Sprintf("%T", err)
	var qe *Error
	if errors.As(err, &qe) {
		class = qe.Class
	} else if errors.Is(err, context.Canceled) {
		class = "CancellationException"
	} else if errors.Is(err, context.DeadlineExceeded) {
		class = "CancellationException"
	}
	return mappedEntryMap{"class": class, "message": err.Error(), "nativeType": fmt.Sprintf("%T", err)}
}
func mappedEntrySnapshot(work *ExecutionContext, worker context.Context) mappedEntryMap {
	var reason any
	if work.IsCancelled() {
		reason = mappedEntryNativeError(work.CancellationException())
	}
	return mappedEntryMap{"diagnostics": work.Diagnostics(), "remaining": strconv.FormatInt(work.tracker.remaining.Load(), 10), "signalCancelled": work.IsCancelled(), "signalReason": reason, "threadInterrupted": worker.Err() != nil}
}
func mappedEntryLookup(t *testing.T, graph *store.Store, input mappedEntryMap) mappedEntryMap {
	t.Helper()
	budget, err := strconv.ParseInt(input["budget"].(string), 10, 64)
	if err != nil {
		t.Fatal(err)
	}
	work, err := NewExecutionContext(budget)
	if err != nil {
		t.Fatal(err)
	}
	worker, cancel := context.WithCancel(context.Background())
	defer cancel()
	// Deliberately do not bind worker context to the request cancellation signal.
	// Main's signal cancellation and Thread.interrupted are independent here.
	reason := &Error{Class: "CypherQueryCancelledException", Message: "mapped-entry callback cancellation"}
	callbacks := []any{}
	phase := "initial"
	var callbackFailure any
	callbackOrdinal := 0
	mappedEntryInstallObserver(func(observed *ExecutionContext, units int64, when string, failure any) {
		if observed != work {
			return
		}
		if when == "before" {
			callbackOrdinal++
			callbacks = append(callbacks, mappedEntryMap{"phase": phase, "ordinal": callbackOrdinal, "units": units, "before": mappedEntrySnapshot(work, worker)})
			return
		}
		row := callbacks[len(callbacks)-1].(map[string]any)
		if failure == nil {
			row["outcome"] = "SUCCESS"
			if callbackOrdinal == 1 {
				switch input["callbackAction"] {
				case "interrupt-after-first":
					cancel()
				case "signal-after-first":
					row["signalCancelAccepted"] = work.Cancel(reason)
				}
			}
		} else {
			callbackFailure = failure
			row["outcome"] = "FAILED"
			if err, ok := failure.(error); ok {
				row["nativeError"] = mappedEntryNativeError(err)
			} else {
				row["nativeError"] = fmt.Sprint(failure)
			}
		}
		row["after"] = mappedEntrySnapshot(work, worker)
	})
	defer mappedEntryInstallObserver(nil)
	got := mappedEntryMap{"term": input["term"], "limit": input["limit"], "budget": input["budget"], "preInterrupted": input["preInterrupted"], "callbackAction": input["callbackAction"], "before": mappedEntrySnapshot(work, worker), "storageBefore": mappedEntryStorage(graph)}
	if input["preInterrupted"].(bool) {
		cancel()
	}
	steps := []any{}
	var failure error
	step := func(name string, action func() any) any {
		phase = name
		row := mappedEntryMap{"phase": name, "before": mappedEntrySnapshot(work, worker), "storageBefore": mappedEntryStorage(graph)}
		value, err := workAttempt(func() (any, error) { return action(), nil })
		if err != nil {
			failure = err
			row["outcome"] = "FAILED"
			row["nativeError"] = mappedEntryNativeError(err)
			row["sameAsSignalReason"] = work.IsCancelled() && err == work.CancellationException()
			if mappedEntryInstrumented {
				row["sameAsCallbackFailure"] = err == callbackFailure
			}
		} else {
			row["outcome"] = "SUCCESS"
			row["value"] = value
		}
		row["after"] = mappedEntrySnapshot(work, worker)
		row["storageAfter"] = mappedEntryStorage(graph)
		steps = append(steps, row)
		return value
	}
	persisted := strings.Contains(input["consumerClass"].(string), "PreferredPersisted")
	plan := &mainStringSourceSpec{atoms: []distinctStringAtom{{property: "caller_name", op: "CONTAINS", term: input["term"].(string)}}, sourceCount: 40, lazyMain: true, forcePersisted: persisted}
	e := evaluator{ctx: worker, work: work}
	var next mainNodeNext
	step("sequence-construction", func() any {
		next = e.mainCandidateIterator(Graph{ID: "source0", Store: graph}, plan, mappedEntryLimit(input["limit"]))
		return mappedEntryMap{"sequenceReturned": next != nil}
	})
	var buffered store.Node
	hasBuffered := false
	if failure == nil && next != nil {
		step("iterator-construction", func() any { return mappedEntryMap{"iteratorReturned": true} })
		for i := 0; i < 8 && failure == nil; i++ {
			value := step(fmt.Sprintf("hasNext-%d", i), func() any {
				if !hasBuffered {
					buffered, hasBuffered = next(worker)
				}
				return hasBuffered
			})
			if failure != nil || value != true {
				break
			}
			step(fmt.Sprintf("next-%d", i), func() any {
				node := buffered
				hasBuffered = false
				return mappedEntryMap{"nodeId": node.ID, "nodeClass": node.Kind, "nativeNode": node}
			})
		}
	}
	if failure != nil {
		got["outcome"] = "FAILED"
		got["nativeError"] = mappedEntryNativeError(failure)
		got["sameAsSignalReason"] = work.IsCancelled() && failure == work.CancellationException()
		if mappedEntryInstrumented {
			got["sameAsCallbackFailure"] = failure == callbackFailure
		}
	} else {
		got["outcome"] = "SUCCESS"
	}
	got["steps"] = steps
	got["callbacks"] = callbacks
	got["after"] = mappedEntrySnapshot(work, worker)
	got["storageAfter"] = mappedEntryStorage(graph)
	got["clearedThreadInterruptedForIsolation"] = worker.Err() != nil
	return got
}

func mappedEntrySimpleClass(s string) string {
	if i := strings.LastIndex(s, "."); i >= 0 {
		return s[i+1:]
	}
	return s
}
func mappedEntryComparableError(raw mappedEntryMap, jvm bool) any {
	if jvm {
		return mappedEntryMap{"class": mappedEntrySimpleClass(raw["errorClass"].(string)), "message": raw["message"]}
	}
	if value, ok := raw["nativeError"].(map[string]any); ok {
		return mappedEntryMap{"class": value["class"], "message": value["message"]}
	}
	return nil
}
func mappedEntryComparableSnapshot(raw mappedEntryMap, jvm bool) mappedEntryMap {
	out := mappedEntryMap{}
	for _, key := range []string{"diagnostics", "remaining", "signalCancelled", "threadInterrupted"} {
		out[key] = raw[key]
	}
	out["signalReason"] = nil
	if value, ok := raw["signalReason"].(map[string]any); ok {
		if jvm {
			out["signalReason"] = mappedEntryMap{"class": mappedEntrySimpleClass(value["errorClass"].(string)), "message": value["message"]}
		} else {
			out["signalReason"] = mappedEntryMap{"class": value["class"], "message": value["message"]}
		}
	}
	return out
}
func mappedEntryComparableLookup(raw mappedEntryMap, jvm, callbacks bool) mappedEntryMap {
	out := mappedEntryMap{}
	for _, key := range []string{"term", "limit", "budget", "preInterrupted", "callbackAction", "outcome", "clearedThreadInterruptedForIsolation"} {
		out[key] = raw[key]
	}
	for _, key := range []string{"before", "after"} {
		out[key] = mappedEntryComparableSnapshot(raw[key].(map[string]any), jvm)
	}
	for _, key := range []string{"storageBefore", "storageAfter"} {
		if jvm {
			out[key] = mappedEntryExpectedStorage(raw[key].(map[string]any))
		} else {
			out[key] = raw[key]
		}
	}
	if raw["outcome"] == "FAILED" {
		out["error"] = mappedEntryComparableError(raw, jvm)
		out["sameAsSignalReason"] = raw["sameAsSignalReason"]
		if callbacks {
			out["sameAsCallbackFailure"] = raw["sameAsCallbackFailure"]
		}
	}
	steps := []any{}
	for _, value := range raw["steps"].([]any) {
		s := value.(map[string]any)
		row := mappedEntryMap{"phase": s["phase"], "outcome": s["outcome"]}
		for _, key := range []string{"before", "after"} {
			row[key] = mappedEntryComparableSnapshot(s[key].(map[string]any), jvm)
		}
		for _, key := range []string{"storageBefore", "storageAfter"} {
			if jvm {
				row[key] = mappedEntryExpectedStorage(s[key].(map[string]any))
			} else {
				row[key] = s[key]
			}
		}
		if s["outcome"] == "FAILED" {
			row["error"] = mappedEntryComparableError(s, jvm)
			row["sameAsSignalReason"] = s["sameAsSignalReason"]
			if callbacks {
				row["sameAsCallbackFailure"] = s["sameAsCallbackFailure"]
			}
		} else {
			v := s["value"]
			if node, ok := v.(map[string]any); ok && node["nodeId"] != nil {
				class := node["nodeClass"].(string)
				v = mappedEntryMap{"nodeId": node["nodeId"], "nodeClass": mappedEntrySimpleClass(class)}
			}
			row["value"] = v
		}
		steps = append(steps, row)
	}
	out["steps"] = steps
	if callbacks {
		list := []any{}
		for _, value := range raw["callbacks"].([]any) {
			s := value.(map[string]any)
			r := mappedEntryMap{}
			for _, key := range []string{"phase", "ordinal", "units", "outcome"} {
				r[key] = s[key]
			}
			for _, key := range []string{"before", "after"} {
				r[key] = mappedEntryComparableSnapshot(s[key].(map[string]any), jvm)
			}
			if s["outcome"] == "FAILED" {
				r["error"] = mappedEntryComparableError(s, jvm)
			}
			if v, ok := s["signalCancelAccepted"]; ok {
				r["signalCancelAccepted"] = v
			}
			list = append(list, r)
		}
		out["callbacks"] = list
	}
	return out
}
func mappedEntryDifferences(path string, got, want any) []mappedEntryMap {
	canonical := func(value any) any {
		b, err := json.Marshal(value)
		if err != nil {
			panic(err)
		}
		var v any
		if err = json.Unmarshal(b, &v); err != nil {
			panic(err)
		}
		return v
	}
	var compare func(string, any, any) []mappedEntryMap
	compare = func(path string, g, w any) []mappedEntryMap {
		if reflect.DeepEqual(g, w) {
			return nil
		}
		var out []mappedEntryMap
		if gm, ok := g.(map[string]any); ok {
			if wm, ok := w.(map[string]any); ok {
				keys := map[string]bool{}
				for k := range gm {
					keys[k] = true
				}
				for k := range wm {
					keys[k] = true
				}
				for k := range keys {
					out = append(out, compare(path+"."+k, gm[k], wm[k])...)
				}
				return out
			}
		}
		if ga, ok := g.([]any); ok {
			if wa, ok := w.([]any); ok {
				if len(ga) != len(wa) {
					out = append(out, mappedEntryMap{"path": path + ".length", "go": len(ga), "main": len(wa)})
				}
				for i := 0; i < len(ga) && i < len(wa); i++ {
					out = append(out, compare(fmt.Sprintf("%s[%d]", path, i), ga[i], wa[i])...)
				}
				return out
			}
		}
		return []mappedEntryMap{{"path": path, "go": g, "main": w}}
	}
	return compare(path, canonical(got), canonical(want))
}

func mappedEntryLimit(value any) int {
	n, err := strconv.Atoi(fmt.Sprint(value))
	if err != nil {
		panic(err)
	}
	return n
}
