// Diagnostic command overlay only. Never part of the production query engine.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"runtime/pprof"
	"syscall"
	"time"

	"github.com/johnsonlee/graphite/graphite-server/internal/benchmarkcase"
)

func saveDiagnosticProfile(dir, filename, kind string) error {
	f, err := os.OpenFile(filepath.Join(dir, filename), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	if err := pprof.Lookup(kind).WriteTo(f, 0); err != nil {
		f.Close()
		return err
	}
	return f.Close()
}

func (r *replay) profileMeasure(ctx context.Context, input benchmarkcase.ExecutionInput, id string) (reply, int64, bool, error) {
	if id != "regex-or-zero" && id != "regex-or-targeted" && id != "regex-or-dense" {
		return r.measure(ctx, input)
	}
	dir := filepath.Join(os.Getenv("GRAPHITE_DIAGNOSTIC_PROFILE_DIR"), id)
	if err := os.Mkdir(dir, 0700); err != nil {
		return reply{}, 0, false, err
	}
	for _, kind := range []string{"allocs", "heap"} {
		if err := saveDiagnosticProfile(dir, "before-"+kind+".pprof", kind); err != nil {
			return reply{}, 0, false, err
		}
	}
	f, err := os.OpenFile(filepath.Join(dir, "cpu.pprof"), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return reply{}, 0, false, err
	}
	if err := pprof.StartCPUProfile(f); err != nil {
		f.Close()
		return reply{}, 0, false, err
	}
	var before, after runtime.MemStats
	var usageBefore, usageAfter syscall.Rusage
	runtime.ReadMemStats(&before)
	if err := syscall.Getrusage(syscall.RUSAGE_SELF, &usageBefore); err != nil {
		pprof.StopCPUProfile()
		f.Close()
		return reply{}, 0, false, err
	}
	started := time.Now()
	response, elapsed, timedOut, queryErr := r.measure(ctx, input)
	finished := time.Now()
	if err := syscall.Getrusage(syscall.RUSAGE_SELF, &usageAfter); err != nil {
		pprof.StopCPUProfile()
		f.Close()
		return reply{}, 0, false, err
	}
	runtime.ReadMemStats(&after)
	pprof.StopCPUProfile()
	if err := f.Close(); err != nil {
		return reply{}, 0, false, err
	}
	for _, kind := range []string{"allocs", "heap"} {
		if err := saveDiagnosticProfile(dir, "after-"+kind+".pprof", kind); err != nil {
			return reply{}, 0, false, err
		}
	}
	data := map[string]any{"id": id, "diagnosticProfiling": true, "performanceMeasurement": false,
		"allRunLatenciesProfileContaminated": true, "forcedGCAdded": false,
		"startedAt": started.UTC().Format(time.RFC3339Nano), "finishedAt": finished.UTC().Format(time.RFC3339Nano),
		"originalMeasureElapsedNanos": elapsed, "timedOut": timedOut,
		"memProfileRate": runtime.MemProfileRate, "memStatsBefore": before, "memStatsAfter": after,
		"rusageBefore": usageBefore, "rusageAfter": usageAfter,
		"totalAllocDelta": after.TotalAlloc - before.TotalAlloc, "mallocsDelta": after.Mallocs - before.Mallocs,
		"freesDelta": after.Frees - before.Frees, "numGCDelta": after.NumGC - before.NumGC,
		"pauseTotalNsDelta": after.PauseTotalNs - before.PauseTotalNs,
		"notes":             "CPU profile covers all process goroutines. Profile writes are outside original measure; snapshots and profiler bookkeeping contaminate diagnostics. Sampled memory profiles may lag last GC; no GC was added. MemStats deltas include concurrent runtime/profiler allocation."}
	encoded, err := json.MarshalIndent(data, "", "  ")
	if err != nil {
		return reply{}, 0, false, err
	}
	if err := os.WriteFile(filepath.Join(dir, "counters.json"), append(encoded, '\n'), 0600); err != nil {
		return reply{}, 0, false, err
	}
	fmt.Fprintf(os.Stderr, "diagnostic profile completed %s; allocation delta %d\n", id, after.TotalAlloc-before.TotalAlloc)
	return response, elapsed, timedOut, queryErr
}
