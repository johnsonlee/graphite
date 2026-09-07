package query

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"os"
	"path/filepath"
	"reflect"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestReviewG8RetainedAllowedSchedule(t *testing.T) {
	var original []map[string]any
	readDistinctJSON(t, "testdata/ordinary-projection/rolling-main-0.json", &original)
	spec := original[8]
	if spec["count"] != float64(40) {
		t.Fatal(spec["count"])
	}
	for _, controlled := range []bool{false, true} {
		t.Run(fmt.Sprintf("barrier=%v", controlled), func(t *testing.T) {
			dirs := map[string]string{"clean": ordinaryCopyFixture(t, "testdata/candidate-index/clean"), "bad": ordinaryCopyFixture(t, "testdata/candidate-index/bad-matched"), "nohit": ordinaryNoHitFixture(t)}
			var sources []Graph
			for i, name := range spec["fixtures"].([]any) {
				g, err := store.OpenMode(dirs[name.(string)], "MAPPED")
				if err != nil {
					t.Fatal(err)
				}
				sources = append(sources, Graph{fmt.Sprintf("g%d", i), g})
			}
			defer func() {
				for _, s := range sources {
					s.Store.Close()
				}
			}()
			if sources[8].ID != "g8" {
				t.Fatal(sources[8].ID)
			}
			parent, cancel := context.WithCancel(context.Background())
			defer cancel()
			var fired atomic.Bool
			var active atomic.Bool
			var watchdog atomic.Bool
			active.Store(true)
			var mu sync.Mutex
			var events []map[string]any
			event := func(phase string, count, index int, canceled bool) {
				mu.Lock()
				events = append(events, map[string]any{"phase": phase, "count": count, "index": index, "graphId": "g8", "childCanceled": canceled})
				mu.Unlock()
			}
			abort := make(chan struct{})
			if controlled {
				reviewG8ScheduleHook = func(ctx context.Context, count, index int) func() {
					if !active.Load() || count != 39 || index != 7 {
						return func() {}
					}
					if !fired.CompareAndSwap(false, true) {
						panic("g8 scheduling gate entered twice")
					}
					event("before-original-source-task", count, index, ctx.Err() != nil)
					select {
					case <-ctx.Done():
						event("scheduler-child-done", count, index, ctx.Err() == context.Canceled)
					case <-abort:
						event("watchdog-abort", count, index, false)
					}
					return func() { event("original-source-task-returned", count, index, ctx.Err() == context.Canceled) }
				}
			}
			defer func() { reviewG8ScheduleHook = nil }()
			results := make(chan []map[string]any, 1)
			go func() {
				var observed []map[string]any
				for i, target := range spec["targets"].([]any) {
					expected := target.(map[string]any)
					q := expected["query"].(string)
					out := map[string]any{"query": q, "before": ordinaryHistoryState(t, sources)}
					result, err := ExecuteCross(parent, sources, q, nil, -1)
					out = distinctOracleResult(out, result, err)
					out["after"] = ordinaryHistoryState(t, sources)
					observed = append(observed, out)
					if i == 0 {
						active.Store(false)
					}
				}
				results <- observed
			}()
			var observed []map[string]any
			select {
			case observed = <-results:
			case <-time.After(10 * time.Second):
				watchdog.Store(true)
				close(abort)
				cancel()
				select {
				case <-results:
				case <-time.After(5 * time.Second):
				}
				t.Fatal("watchdog fired")
			}
			if watchdog.Load() || parent.Err() != nil {
				t.Fatal("watchdog or parent cancellation")
			}
			for i, target := range spec["targets"].([]any) {
				if !reflect.DeepEqual(ordinaryObservedResponse(observed[i]), ordinaryObservedResponse(target.(map[string]any))) {
					t.Fatalf("response%d main=%s actual=%s", i, mustJSON(target), mustJSON(observed[i]))
				}
			}
			if controlled {
				if !fired.Load() {
					t.Fatal("actual index7 gate did not run")
				}
				for _, state := range []map[string]any{observed[0]["after"].([]map[string]any)[8], observed[1]["before"].([]any)[8].(map[string]any)} {
					if state["id"] != "g8" || state["retained"] != false {
						t.Fatal(state)
					}
				}
				mu.Lock()
				if len(events) != 3 || events[1]["phase"] != "scheduler-child-done" || events[1]["childCanceled"] != true || events[2]["phase"] != "original-source-task-returned" {
					t.Fatal(events)
				}
				mu.Unlock()
			}
			// ExecuteCross returned only after all launched source tasks joined; the
			// post-task event is already present. Fresh history and Close remain usable.
			output := map[string]any{"controlled": controlled, "taskIndex": 7, "sourceIndex": 8, "sourceId": "g8", "watchdogFired": watchdog.Load(), "parentCanceled": parent.Err() != nil, "events": events, "observations": observed}
			if dir := os.Getenv("G8_SCHEDULE_OUTPUT"); dir != "" {
				if err := os.MkdirAll(dir, 0700); err != nil {
					t.Fatal(err)
				}
				raw, err := json.MarshalIndent(output, "", "  ")
				if err != nil {
					t.Fatal(err)
				}
				if err = os.WriteFile(filepath.Join(dir, fmt.Sprintf("controlled-%v.json", controlled)), append(raw, '\n'), 0600); err != nil {
					t.Fatal(err)
				}
			}
		})
	}
}
