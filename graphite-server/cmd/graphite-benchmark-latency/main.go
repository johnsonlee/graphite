// Observer-free, dispatch-inclusive real64 diagnostic latency replay.
package main

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"time"

	"github.com/johnsonlee/graphite/graphite-server/internal/benchmarkcase"
	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/query"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

const main64SHA256 = "378c200c5ab3053c53962f9d87c59924f732d0c012fcaff6009842a58e547023"
const cancellationGrace = 5 * time.Second // Original main CANCELLATION_GRACE_SECONDS.

type executor func(context.Context, []query.Graph, string, map[string]any, int, query.ExecutionOptions) (query.Result, error)
type reply struct {
	result query.Result
	err    error
}
type task struct {
	ctx     context.Context
	sources []query.Graph
	input   benchmarkcase.ExecutionInput
	done    chan reply
}
type worker struct {
	jobs    chan task
	stopped chan struct{}
}

func newWorker(execute executor) *worker {
	w := &worker{make(chan task, 1), make(chan struct{})}
	go func() {
		defer close(w.stopped)
		for t := range w.jobs {
			result, err := execute(t.ctx, t.sources, t.input.Query, t.input.Parameters, -1, query.ExecutionOptions{SourceScopeApplied: t.input.SourceScopeApplied, WorkTrackingEnabled: true})
			t.done <- reply{result, err}
		}
	}()
	return w
}

type caseRecord struct {
	Kind               string `json:"kind"`
	Phase              string `json:"phase"`
	Index              int    `json:"index"`
	ID                 string `json:"id"`
	Outcome            string `json:"outcome"`
	LatencyNanos       int64  `json:"latencyNanos"`
	RowCount           int    `json:"rowCount"`
	ResponseBytes      int    `json:"responseBytes"`
	Digest             string `json:"digest"`
	Error              string `json:"error,omitempty"`
	Message            any    `json:"message"`
	CensoredTimeout    bool   `json:"censoredTimeout"`
	TimeoutMillis      int64  `json:"timeoutMillis"`
	InputSourceCount   int    `json:"inputSourceCount"`
	SourceScopeApplied bool   `json:"sourceScopeApplied"`
	ValidationError    string `json:"validationError,omitempty"`
}
type replay struct {
	inputs        []benchmarkcase.ExecutionInput
	w             benchmarkcase.Workload
	graphs        []query.Graph
	byID          map[string]query.Graph
	worker        *worker
	now           func() time.Time
	canonical     func(query.Result) (string, error)
	grace         time.Duration
	unsafeToClose bool
}

// measure starts before context creation and source selection. The timeout
// begins after enqueue, matching Future.get(timeout) rather than the case clock.
// No canonicalization, graph observation, or output occurs in this interval.
func (r *replay) measure(ctx context.Context, input benchmarkcase.ExecutionInput) (reply, int64, bool, error) {
	started := r.now()
	qctx, cancel := context.WithCancel(ctx)
	sources := r.graphs
	if input.SourceScopeApplied {
		sources = make([]query.Graph, len(input.GraphIDs))
		for i, id := range input.GraphIDs {
			sources[i] = r.byID[id]
		}
	}
	done := make(chan reply, 1)
	select {
	case r.worker.jobs <- task{qctx, sources, input, done}:
	case <-ctx.Done():
		cancel()
		return reply{}, 0, false, ctx.Err()
	}
	timer := time.NewTimer(time.Duration(input.TimeoutMillis) * time.Millisecond)
	defer timer.Stop()
	var result reply
	timedOut := false
	select {
	case result = <-done:
	case <-timer.C:
		// Future.get returns an already completed result even at its deadline.
		select {
		case result = <-done:
		default:
			timedOut = true
		}
	case <-ctx.Done():
		cancel()
		if err := r.join(done); err != nil {
			return reply{}, 0, false, err
		}
		return reply{}, 0, false, ctx.Err()
	}
	if timedOut {
		cancel()
		if err := r.join(done); err != nil {
			return reply{}, 0, true, err
		}
		return reply{}, input.TimeoutMillis * int64(time.Millisecond), true, nil
	}
	elapsed := r.now().Sub(started).Nanoseconds()
	cancel()
	return result, elapsed, false, nil
}
func (r *replay) join(done <-chan reply) error {
	timer := time.NewTimer(r.grace)
	defer timer.Stop()
	select {
	case <-done:
		return nil
	case <-timer.C:
		r.unsafeToClose = true
		return fmt.Errorf("query did not stop within cancellation grace %s; replay aborted", r.grace)
	}
}
func qualifiedError(class string) string {
	if strings.Contains(class, ".") {
		return class
	}
	switch class {
	case "CypherException", "CypherAggregationException", "CypherParseException", "CypherQueryCancelledException", "CypherQueryTimeoutException", "CypherBudgetExceededException":
		return "io.johnsonlee.graphite.cypher." + class
	case "NoSuchElementException", "ConcurrentModificationException":
		return "java.util." + class
	default:
		return "java.lang." + class
	}
}
func (r *replay) prepareInputs() error {
	r.inputs = make([]benchmarkcase.ExecutionInput, len(r.w.Cases))
	for i, c := range r.w.Cases {
		input, err := r.w.Input(c, 60000)
		if err != nil {
			return err
		}
		r.inputs[i] = input
	}
	return nil
}
func (r *replay) run(ctx context.Context) ([]caseRecord, error) {
	inputs := r.inputs
	if len(inputs) != len(r.w.Cases) {
		return nil, fmt.Errorf("static inputs not prepared before replay")
	}
	records := make([]caseRecord, 0, len(inputs))
	failures := 0
	for i, input := range inputs {
		if err := ctx.Err(); err != nil {
			return records, err
		}
		c := r.w.Cases[i]
		response, elapsed, timedOut, err := r.measure(ctx, input)
		if err != nil {
			return records, fmt.Errorf("%s: %w", c.ID, err)
		}
		rec := caseRecord{Kind: "case", Phase: "replay", Index: i, ID: c.ID, Outcome: "SUCCESS", LatencyNanos: elapsed, TimeoutMillis: input.TimeoutMillis, InputSourceCount: len(input.GraphIDs), SourceScopeApplied: input.SourceScopeApplied}
		if timedOut {
			rec.Outcome = "TIMEOUT"
			rec.Error = "TimeoutException"
			rec.Digest = "timeout"
			rec.CensoredTimeout = true
			failures++
		} else if response.err != nil {
			rec.Outcome = "FAILED"
			failures++
			var qe *query.Error
			if errors.As(response.err, &qe) {
				rec.Error = qe.Class
				rec.Message = qe.JavaMessage()
				rec.Digest = qualifiedError(qe.Class)
			} else {
				rec.Error = fmt.Sprintf("%T", response.err)
				rec.Message = response.err.Error()
				rec.Digest = rec.Error
			}
		} else {
			canonical, err := r.canonical(response.result)
			if err != nil {
				return records, fmt.Errorf("%s canonicalization: %w", c.ID, err)
			}
			rec.RowCount = len(response.result.Rows)
			rec.ResponseBytes = len(canonical)
			rec.Digest = fmt.Sprintf("%x", sha256.Sum256([]byte(canonical)))
			if c.ExpectZeroRows && rec.RowCount != 0 {
				rec.ValidationError = "expected zero rows"
			}
			if bounds := c.ExpectedRowCountRange; bounds != nil && (int64(rec.RowCount) < bounds.First || int64(rec.RowCount) > bounds.Last) {
				rec.ValidationError = fmt.Sprintf("row count %d outside [%d,%d]", rec.RowCount, bounds.First, bounds.Last)
			}
		}
		records = append(records, rec)
		if rec.ValidationError != "" {
			return records, fmt.Errorf("%s: %s", c.ID, rec.ValidationError)
		}
	}
	if failures != 0 {
		return records, fmt.Errorf("replay had %d failed cases", failures)
	}
	return records, nil
}
func run() error {
	workload := flag.String("workload", "internal/benchmarkcase/testdata/main64.json", "exact pinned-main workload export")
	manifest := flag.String("graphs", "", "six-column manifest for fresh writable real64 clone")
	state := flag.String("state", "cold", "cold, startup-prepared, or warm-after-failed-prewarm (diagnostic continuation)")
	output := flag.String("output", "", "new JSONL output file; never overwritten")
	flag.Parse()
	if flag.NArg() != 0 || *manifest == "" || *output == "" {
		return fmt.Errorf("--graphs and --output are required")
	}
	if *state != "cold" && *state != "startup-prepared" && *state != "warm-after-failed-prewarm" {
		return fmt.Errorf("unsupported state %q", *state)
	}
	data, err := os.ReadFile(*workload)
	if err != nil {
		return err
	}
	if fmt.Sprintf("%x", sha256.Sum256(data)) != main64SHA256 {
		return fmt.Errorf("workload SHA256 differs from pinned main64 export")
	}
	w, err := benchmarkcase.Decode(data)
	if err != nil {
		return err
	}
	if len(w.Cases) != 1267 {
		return fmt.Errorf("incomplete case matrix")
	}
	for _, c := range w.Cases {
		if _, err := cypher.Parse(c.Query); err != nil {
			return fmt.Errorf("setup parse %s: %w", c.ID, err)
		}
	}
	sources, err := graphSources(*manifest, w.SourceOrder)
	if err != nil {
		return err
	}
	f, err := os.OpenFile(*output, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	defer f.Close()
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	r := &replay{w: w, byID: map[string]query.Graph{}, now: time.Now, canonical: benchmarkcase.CanonicalResult, grace: cancellationGrace}
	if err := r.prepareInputs(); err != nil {
		return err
	}
	defer func() {
		// After an uncooperative cancellation the process must exit without closing
		// stores still used by its worker. No further case is dispatched.
		if r.worker != nil {
			close(r.worker.jobs)
			if !r.unsafeToClose {
				<-r.worker.stopped
			}
		}
		if !r.unsafeToClose {
			for i := len(r.graphs) - 1; i >= 0; i-- {
				r.graphs[i].Store.Close()
			}
		}
	}()
	for i, source := range sources {
		g, err := store.OpenModeWithOptions(ctx, source.Path, "MAPPED", store.OpenOptions{PrepareCallSiteStringIndex: *state == "startup-prepared"})
		if err != nil {
			return fmt.Errorf("load %s: %w", source.ID, err)
		}
		graph := query.Graph{ID: source.ID, Store: g}
		r.graphs = append(r.graphs, graph)
		r.byID[source.ID] = graph
		fmt.Fprintf(os.Stderr, "loaded %d/64 %s\n", i+1, source.ID)
	}
	r.worker = newWorker(query.ExecuteCrossWithOptions)
	if *state == "cold" || *state == "warm-after-failed-prewarm" {
		for _, g := range r.graphs {
			if err := g.Store.ClearStringPropertyIndexes(ctx); err != nil {
				return err
			}
		}
	}
	var warmupRecords []caseRecord
	var warmupErr error
	if *state == "warm-after-failed-prewarm" {
		warmupRecords, warmupErr = r.run(ctx)
		for i := range warmupRecords {
			warmupRecords[i].Phase = "warmup"
		}
		if !knownOriginalWarmupFailure(warmupRecords, w) {
			enc := json.NewEncoder(f)
			for _, record := range warmupRecords {
				if err := enc.Encode(record); err != nil {
					return err
				}
			}
			if err := enc.Encode(map[string]any{"kind": "completion", "completeReplay": false,
				"originalGatePassed": false, "diagnosticWarmupContinued": false,
				"error": "warmup differs from the pinned original failure; no timed replay dispatched"}); err != nil {
				return err
			}
			return fmt.Errorf("warmup differs from the pinned original failure: %v", warmupErr)
		}
		// Match main's prewarm frame lifetime: persist compact observations before
		// the setup GC boundary and release them before the measured invocation.
		warmFile, err := os.OpenFile(filepath.Join(filepath.Dir(*output), "warmup-observations.jsonl"), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
		if err != nil {
			return err
		}
		warmEncoder := json.NewEncoder(warmFile)
		for _, record := range warmupRecords {
			if err := warmEncoder.Encode(record); err != nil {
				warmFile.Close()
				return err
			}
		}
		if err := warmFile.Close(); err != nil {
			return err
		}
		warmupRecords = nil

	}
	for i := 0; i < 3; i++ {
		runtime.GC()
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(100 * time.Millisecond):
		}
	}
	records, replayErr := r.run(ctx)
	// Retain only compact signatures while replaying. All JSON writes are after
	// the entire replay, including when the original all-success gate fails.
	enc := json.NewEncoder(f)
	enc.SetEscapeHTML(false)
	header := map[string]any{"kind": "header", "protocol": "real64-dispatch-inclusive-sampling-v1", "state": *state, "caseCount": len(w.Cases), "graphCount": len(r.graphs), "sourceOrder": w.SourceOrder, "workloadSHA256": main64SHA256, "mainRevision": w.MainRevision, "performanceMeasurement": true, "diagnosticOnly": true, "sampleCountPerCase": 1, "timingBoundary": "context-source-selection-worker-dispatch-parse-execute-materialize-reply", "workTrackingEnabled": true, "workBudget": "unlimited", "cancellationGraceMillis": cancellationGrace.Milliseconds(), "runtime": runtime.Version(), "goos": runtime.GOOS, "goarch": runtime.GOARCH, "gomaxprocs": runtime.GOMAXPROCS(0), "unavailableParity": []string{"finite work accounting", "main resource sampler and execution metrics"}}
	header["diagnosticWarmupContinued"] = *state == "warm-after-failed-prewarm"
	header["formalWarmPrepared"] = false
	if warmupErr != nil {
		header["originalWarmupGateError"] = warmupErr.Error()
	}
	if err := enc.Encode(header); err != nil {
		return err
	}
	for _, record := range records {
		if err := enc.Encode(record); err != nil {
			return err
		}
	}
	failureText := ""
	if replayErr != nil {
		failureText = replayErr.Error()
	}
	if err := enc.Encode(map[string]any{"kind": "completion", "caseCount": len(records), "expectedCaseCount": len(w.Cases), "completeReplay": len(records) == len(w.Cases), "originalGatePassed": replayErr == nil && warmupErr == nil, "replayGatePassed": replayErr == nil, "diagnosticWarmupContinued": *state == "warm-after-failed-prewarm", "error": failureText}); err != nil {
		return err
	}
	if err := f.Sync(); err != nil {
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	if replayErr != nil {
		return replayErr
	}
	return warmupErr
}
func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
