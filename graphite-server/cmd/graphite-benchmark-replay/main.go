// Full main64 correctness replay. This command intentionally reports no latency.
package main

import (
	"bufio"
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
	"github.com/johnsonlee/graphite/graphite-server/internal/query"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

const main64SHA256 = "378c200c5ab3053c53962f9d87c59924f732d0c012fcaff6009842a58e547023"

type graphSource struct{ ID, Path string }

func graphSources(path string, order []string) ([]graphSource, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	scanner := bufio.NewScanner(f)
	sources := []graphSource{}
	paths := map[string]bool{}
	for scanner.Scan() {
		line := scanner.Text()
		if strings.HasPrefix(line, "#") || strings.TrimSpace(line) == "" {
			continue
		}
		cols := strings.Split(line, "\t")
		if len(cols) != 6 {
			return nil, fmt.Errorf("graph manifest requires six columns")
		}
		for _, v := range cols {
			if strings.TrimSpace(v) == "" {
				return nil, fmt.Errorf("empty graph manifest field")
			}
		}
		if len(sources) >= len(order) || cols[0] != order[len(sources)] {
			return nil, fmt.Errorf("source order differs from main at %d", len(sources))
		}
		if !filepath.IsAbs(cols[1]) {
			return nil, fmt.Errorf("graph path must be absolute")
		}
		path, err := filepath.EvalSymlinks(cols[1])
		if err != nil {
			return nil, err
		}
		if paths[path] {
			return nil, fmt.Errorf("repeated physical graph path %s", path)
		}
		paths[path] = true
		sources = append(sources, graphSource{cols[0], path})
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}
	if len(sources) != 64 {
		return nil, fmt.Errorf("expected 64 graphs, got %d", len(sources))
	}
	return sources, nil
}

type replay struct {
	w      benchmarkcase.Workload
	graphs []query.Graph
	byID   map[string]query.Graph
	output *json.Encoder
}

func (r *replay) emit(value any) error { return r.output.Encode(value) }
func (r *replay) states() (any, error) {
	states := make([]map[string]any, 0, len(r.graphs))
	for _, g := range r.graphs {
		s, err := g.Store.StringPropertyIndexes(context.Background())
		if err != nil {
			return nil, err
		}
		states = append(states, map[string]any{"id": g.ID, "retained": s.Retained, "mappedView": s.MappedView, "trigrams": s.Trigrams, "loadedFromPersistence": s.LoadedFromPersistence, "mappedRangeCount": s.MappedRangeCount})
	}
	return states, nil
}
func (r *replay) run(ctx context.Context, phase string) error {
	failures := 0
	for i, c := range r.w.Cases {
		if err := ctx.Err(); err != nil {
			return err
		}
		input, err := r.w.Input(c, 60000)
		if err != nil {
			return err
		}
		sources := make([]query.Graph, len(input.GraphIDs))
		for j, id := range input.GraphIDs {
			sources[j] = r.byID[id]
		}
		before, err := r.states()
		if err != nil {
			return err
		}
		qctx, cancel := context.WithTimeout(ctx, time.Duration(input.TimeoutMillis)*time.Millisecond)
		result, queryErr := query.ExecuteCrossWithOptions(qctx, sources, input.Query, input.Parameters, -1, query.ExecutionOptions{SourceScopeApplied: input.SourceScopeApplied, WorkTrackingEnabled: true})
		timedOut := errors.Is(qctx.Err(), context.DeadlineExceeded)
		cancel() // ExecuteCross joins all source tasks before it returns.
		after, err := r.states()
		if err != nil {
			return err
		}
		record := map[string]any{"kind": "case", "phase": phase, "index": i, "id": c.ID, "before": before, "after": after}
		if timedOut {
			record["error"] = "TimeoutException"
			record["message"] = nil
			failures++
		} else if queryErr != nil {
			var qe *query.Error
			if errors.As(queryErr, &qe) {
				record["error"] = qe.Class
				record["message"] = qe.JavaMessage()
			} else {
				record["error"] = fmt.Sprintf("%T", queryErr)
				record["message"] = queryErr.Error()
			}
			failures++
		} else {
			record["columns"] = result.Columns
			record["rows"] = result.Rows
			canonical, err := benchmarkcase.CanonicalResult(result)
			if err != nil {
				record["canonicalError"] = err.Error()
				failures++
			} else {
				record["canonical"] = canonical
			}
			if c.ExpectZeroRows && len(result.Rows) != 0 {
				record["validationError"] = "expected zero rows"
			}
			if bounds := c.ExpectedRowCountRange; bounds != nil && (int64(len(result.Rows)) < bounds.First || int64(len(result.Rows)) > bounds.Last) {
				record["validationError"] = fmt.Sprintf("row count %d outside [%d,%d]", len(result.Rows), bounds.First, bounds.Last)
			}
		}
		if err := r.emit(record); err != nil {
			return err
		}
		fmt.Fprintf(os.Stderr, "%s %d/%d %s rows=%d error=%v\n", phase, i+1, len(r.w.Cases), c.ID, len(result.Rows), record["error"])
		if validation, ok := record["validationError"]; ok {
			return fmt.Errorf("%s: %s", c.ID, validation)
		}
	}
	if failures != 0 {
		return fmt.Errorf("%s had %d failed cases", phase, failures)
	}
	return nil
}

func run() error {
	manifest := flag.String("workload", "internal/benchmarkcase/testdata/main64.json", "exact pinned-main workload export")
	graphs := flag.String("graphs", "", "six-column graph manifest for a fresh writable real64 clone")
	state := flag.String("state", "cold", "cold, warm, or startup-prepared")
	output := flag.String("output", "", "new JSONL output file; never overwritten")
	flag.Parse()
	if flag.NArg() != 0 || *graphs == "" || *output == "" {
		return fmt.Errorf("--graphs and --output are required")
	}
	if *state != "cold" && *state != "warm" && *state != "startup-prepared" {
		return fmt.Errorf("invalid index state %q", *state)
	}
	data, err := os.ReadFile(*manifest)
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
	sources, err := graphSources(*graphs, w.SourceOrder)
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
	r := &replay{w: w, byID: map[string]query.Graph{}, output: json.NewEncoder(f)}
	r.output.SetEscapeHTML(false)
	defer func() {
		for i := len(r.graphs) - 1; i >= 0; i-- {
			r.graphs[i].Store.Close()
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
	loaded, err := r.states()
	if err != nil {
		return err
	}
	if err := r.emit(map[string]any{"kind": "header", "state": *state, "caseCount": 1267, "graphCount": 64, "performanceMeasurement": false, "loaded": loaded, "workloadSHA256": main64SHA256, "unavailableStateCounters": []string{"rawMatchCount", "rawProjectionCount"}}); err != nil {
		return err
	}
	if *state != "startup-prepared" {
		for _, g := range r.graphs {
			if err := g.Store.ClearStringPropertyIndexes(ctx); err != nil {
				return err
			}
		}
	}
	if *state == "warm" {
		if err := r.run(ctx, "warmup"); err != nil {
			return err
		}
	}
	// Main requests three collections with 100ms pauses before the replay. These
	// are setup operations, not observations used to claim latency equivalence.
	for attempt := 0; attempt < 3; attempt++ {
		runtime.GC()
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(100 * time.Millisecond):
		}
	}
	prepared, err := r.states()
	if err != nil {
		return err
	}
	if err := r.emit(map[string]any{"kind": "prepared", "state": *state, "sources": prepared}); err != nil {
		return err
	}
	if err := r.run(ctx, "replay"); err != nil {
		return err
	}
	responses := 1267
	if *state == "warm" {
		responses *= 2
	}
	return r.emit(map[string]any{"kind": "complete", "responses": responses, "performanceMeasurement": false})
}
func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
