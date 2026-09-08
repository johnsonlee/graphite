package main

import (
	"context"
	"crypto/sha256"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/johnsonlee/graphite/graphite-server/internal/benchmarkcase"
	"github.com/johnsonlee/graphite/graphite-server/internal/query"
)

func newTestReplay(t *testing.T, cases []benchmarkcase.Case, execute executor) *replay {
	t.Helper()
	r := &replay{w: benchmarkcase.Workload{SourceOrder: []string{"a", "b"}, Cases: cases}, graphs: []query.Graph{{ID: "a"}, {ID: "b"}}, byID: map[string]query.Graph{"a": {ID: "a"}, "b": {ID: "b"}}, worker: newWorker(execute), now: time.Now, canonical: benchmarkcase.CanonicalResult, grace: time.Second}
	if err := r.prepareInputs(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { close(r.worker.jobs); <-r.worker.stopped })
	return r
}

func TestDispatchBoundaryAndCanonicalSignature(t *testing.T) {
	// A deterministic event clock asserts ordering; these are correctness tests,
	// and none of their durations are performance evidence.
	var mu sync.Mutex
	events := []string{}
	event := func(s string) { mu.Lock(); defer mu.Unlock(); events = append(events, s) }
	c := benchmarkcase.Case{ID: "selected", Query: "RETURN $x", Parameters: map[string]any{"x": int64(7)}, RequestGraphIDs: []string{"b", "a"}}
	result := query.Result{Columns: []string{"x"}, Rows: []map[string]any{{"x": int64(7)}}}
	r := newTestReplay(t, []benchmarkcase.Case{c}, func(ctx context.Context, graphs []query.Graph, q string, p map[string]any, limit int, options query.ExecutionOptions) (query.Result, error) {
		event("execute")
		if ctx.Done() == nil || q != c.Query || p["x"] != int64(7) || limit != -1 || !options.SourceScopeApplied || !options.WorkTrackingEnabled || graphs[0].ID != "b" || graphs[1].ID != "a" {
			t.Errorf("execution input lost: graphs=%v q=%q p=%v limit=%d options=%+v", graphs, q, p, limit, options)
		}
		return result, nil
	})
	clockCalls := 0
	r.now = func() time.Time { event("clock"); clockCalls++; return time.Unix(0, int64(clockCalls)*37) }
	r.canonical = func(got query.Result) (string, error) {
		event("canonical")
		if !reflect.DeepEqual(got, result) {
			t.Errorf("materialized result changed: %#v", got)
		}
		return benchmarkcase.CanonicalResult(got)
	}
	records, err := r.run(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(events, []string{"clock", "execute", "clock", "canonical"}) {
		t.Fatalf("wrong timing boundary %v", events)
	}
	canonical, _ := benchmarkcase.CanonicalResult(result)
	got := records[0]
	if got.LatencyNanos != 37 || got.Outcome != "SUCCESS" || got.RowCount != 1 || got.ResponseBytes != len(canonical) || got.Digest != fmt.Sprintf("%x", sha256.Sum256([]byte(canonical))) || got.InputSourceCount != 2 || !got.SourceScopeApplied || got.CensoredTimeout {
		t.Fatalf("wrong observation %+v", got)
	}
}

func TestErrorDoesNotDropLaterCases(t *testing.T) {
	cases := []benchmarkcase.Case{{ID: "before", Query: "before"}, {ID: "failed", Query: "failed"}, {ID: "after", Query: "after"}}
	queries := []string{}
	r := newTestReplay(t, cases, func(_ context.Context, g []query.Graph, q string, _ map[string]any, _ int, options query.ExecutionOptions) (query.Result, error) {
		queries = append(queries, q)
		if options.SourceScopeApplied || len(g) != 2 {
			t.Errorf("unscoped selection changed")
		}
		if q == "failed" {
			return query.Result{}, &query.Error{Class: "IllegalStateException", Message: "Unsafe expression reached parallel string projection"}
		}
		return query.Result{Columns: []string{"q"}, Rows: []map[string]any{{"q": q}}}, nil
	})
	records, err := r.run(context.Background())
	if err == nil || err.Error() != "replay had 1 failed cases" || len(records) != 3 || !reflect.DeepEqual(queries, []string{"before", "failed", "after"}) {
		t.Fatalf("failure truncated replay: records=%v queries=%v err=%v", records, queries, err)
	}
	if got := records[1]; got.Outcome != "FAILED" || got.Digest != "java.lang.IllegalStateException" || got.Message != "Unsafe expression reached parallel string projection" || got.ResponseBytes != 0 || got.RowCount != 0 || got.CensoredTimeout {
		t.Fatalf("wrong failure record %+v", got)
	}
	if records[2].Outcome != "SUCCESS" {
		t.Fatalf("last case not completed: %+v", records[2])
	}
}

func TestTimeoutCancelsAndJoinsBeforeNextCase(t *testing.T) {
	timeout := int64(1)
	cases := []benchmarkcase.Case{{ID: "slow", Query: "slow", ConfiguredTimeoutMillis: &timeout}, {ID: "next", Query: "next"}}
	cancelled := make(chan struct{})
	allowReturn := make(chan struct{})
	next := make(chan struct{})
	r := newTestReplay(t, cases, func(ctx context.Context, _ []query.Graph, q string, _ map[string]any, _ int, _ query.ExecutionOptions) (query.Result, error) {
		if q == "slow" {
			<-ctx.Done()
			close(cancelled)
			<-allowReturn
			return query.Result{}, ctx.Err()
		}
		close(next)
		return query.Result{}, nil
	})
	type outcome struct {
		records []caseRecord
		err     error
	}
	finished := make(chan outcome, 1)
	go func() { records, err := r.run(context.Background()); finished <- outcome{records, err} }()
	select {
	case <-cancelled:
	case <-time.After(time.Second):
		t.Fatal("deadline did not cancel worker")
	}
	select {
	case <-next:
		t.Fatal("next case started before canceled worker joined")
	case <-finished:
		t.Fatal("replay returned before join")
	default:
	}
	close(allowReturn)
	select {
	case got := <-finished:
		if got.err == nil || len(got.records) != 2 || got.records[0].LatencyNanos != int64(time.Millisecond) || !got.records[0].CensoredTimeout || got.records[0].Digest != "timeout" || got.records[0].Outcome != "TIMEOUT" || got.records[1].Outcome != "SUCCESS" {
			t.Fatalf("wrong timeout/join records %+v err=%v", got.records, got.err)
		}
	case <-time.After(time.Second):
		t.Fatal("joined worker failed to continue")
	}
}

func TestCancellationGraceAbortsReplay(t *testing.T) {
	timeout := int64(1)
	release := make(chan struct{})
	calls := 0
	r := newTestReplay(t, []benchmarkcase.Case{{ID: "stuck", ConfiguredTimeoutMillis: &timeout}, {ID: "must-not-run"}}, func(ctx context.Context, _ []query.Graph, _ string, _ map[string]any, _ int, _ query.ExecutionOptions) (query.Result, error) {
		calls++
		<-ctx.Done()
		<-release
		return query.Result{}, ctx.Err()
	})
	r.grace = time.Millisecond
	records, err := r.run(context.Background())
	close(release)
	if err == nil || !strings.Contains(err.Error(), "cancellation grace") || len(records) != 0 || !r.unsafeToClose {
		t.Fatalf("unjoined query was accepted: %v %+v unsafe=%v", err, records, r.unsafeToClose)
	}
	// Worker receives no new tasks once the cancellation barrier fails.
	close(r.worker.jobs)
	<-r.worker.stopped
	if calls != 1 {
		t.Fatalf("dispatched %d cases after failed join", calls)
	}
	// Cleanup expects ownership of a live idle worker.
	r.worker = newWorker(func(context.Context, []query.Graph, string, map[string]any, int, query.ExecutionOptions) (query.Result, error) {
		return query.Result{}, nil
	})
}

func TestRowValidationAborts(t *testing.T) {
	calls := 0
	r := newTestReplay(t, []benchmarkcase.Case{{ID: "zero", ExpectZeroRows: true}, {ID: "not-run"}}, func(context.Context, []query.Graph, string, map[string]any, int, query.ExecutionOptions) (query.Result, error) {
		calls++
		return query.Result{Rows: []map[string]any{{"n": int64(1)}}}, nil
	})
	records, err := r.run(context.Background())
	if err == nil || len(records) != 1 || records[0].ValidationError != "expected zero rows" || calls != 1 {
		t.Fatalf("row constraint not enforced: %v %+v calls=%d", err, records, calls)
	}
}

func TestPhysicalGraphManifestRejectsAliasAndOrder(t *testing.T) {
	dir := t.TempDir()
	order := make([]string, 64)
	lines := make([]string, 64)
	for i := range order {
		order[i] = fmt.Sprintf("g%02d", i)
		p := filepath.Join(dir, order[i])
		if err := os.Mkdir(p, 0700); err != nil {
			t.Fatal(err)
		}
		lines[i] = strings.Join([]string{order[i], p, "absent", "target", "dense", "identity"}, "\t")
	}
	manifest := filepath.Join(dir, "graphs.tsv")
	write := func() {
		if err := os.WriteFile(manifest, []byte(strings.Join(lines, "\n")), 0600); err != nil {
			t.Fatal(err)
		}
	}
	write()
	sources, err := graphSources(manifest, order)
	if err != nil || len(sources) != 64 {
		t.Fatalf("valid manifest: %v", err)
	}
	alias := filepath.Join(dir, "alias")
	if err := os.Symlink(sources[0].Path, alias); err != nil {
		t.Fatal(err)
	}
	original := lines[1]
	cols := strings.Split(lines[1], "\t")
	cols[1] = alias
	lines[1] = strings.Join(cols, "\t")
	write()
	if _, err := graphSources(manifest, order); err == nil || !strings.Contains(err.Error(), "repeated physical") {
		t.Fatalf("accepted physical alias: %v", err)
	}
	lines[1] = original
	lines[0], lines[1] = lines[1], lines[0]
	write()
	if _, err := graphSources(manifest, order); err == nil || !strings.Contains(err.Error(), "source order") {
		t.Fatalf("accepted changed source order: %v", err)
	}
}
