package query

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// These observations come from two actual JVM runs. Constructor failures beat
// maxRows validation, which beats parsing, which beats an existing cancellation.
func TestExecutionContextMainConstructorOracle(t *testing.T) {
	dir := "../../../docs/go-server-baseline/native-work-context/checks/constructor-oracle"
	var oracle struct {
		Cases []struct {
			Spec struct {
				Name    string `json:"name"`
				Mode    string `json:"mode"`
				Query   string `json:"query"`
				MaxRows int    `json:"maxRows"`
			} `json:"spec"`
			Outcome         string               `json:"outcome"`
			Error           string               `json:"error"`
			Message         string               `json:"message"`
			Columns         any                  `json:"columns"`
			Rows            any                  `json:"rows"`
			CancelAccepted  bool                 `json:"cancelAccepted"`
			Cancelled       bool                 `json:"cancelled"`
			ReasonPreserved bool                 `json:"reasonPreserved"`
			Diagnostics     ExecutionDiagnostics `json:"diagnostics"`
		} `json:"cases"`
	}
	workReadJSON(t, filepath.Join(dir, "main.json"), &oracle)
	if len(oracle.Cases) != 10 {
		t.Fatalf("actual main constructor cases = %d, want 10", len(oracle.Cases))
	}
	first, err := os.ReadFile(filepath.Join(dir, "main-capture/main.json"))
	if err != nil {
		t.Fatal(err)
	}
	repeat, err := os.ReadFile(filepath.Join(dir, "repeat-capture/main.json"))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(first, repeat) {
		t.Fatal("complete repeated actual JVM observations differ")
	}
	fixtures := genericDisjunctionFixtures(t, filepath.Join(dir, "fixtures.tar.gz"), 16)
	seen := map[string]bool{}
	for _, record := range oracle.Cases {
		if seen[record.Spec.Name] {
			t.Fatalf("duplicate constructor oracle case %q", record.Spec.Name)
		}
		seen[record.Spec.Name] = true
		t.Run(record.Spec.Name, func(t *testing.T) {
			work, err := NewExecutionContext(4)
			if err != nil {
				t.Fatal(err)
			}
			reason := &Error{Class: "CypherQueryCancelledException", Message: "constructor oracle cancellation"}
			workCompare(t, "cancel accepted", work.Cancel(reason), record.CancelAccepted)
			options := ExecutionOptions{ExecutionContext: work}
			var result Result
			switch record.Spec.Mode {
			case "single-null":
				result, err = ExecuteWithMaxRows(context.Background(), nil, record.Spec.Query, nil, record.Spec.MaxRows, options)
			case "cross-null":
				result, err = ExecuteCrossWithMaxRows(context.Background(), []Graph{{ID: "g"}}, record.Spec.Query, nil, record.Spec.MaxRows, options)
			case "cross-duplicate":
				graph, loadErr := store.OpenMode(ordinaryCopyFixture(t, filepath.Join(fixtures, "locals")), "MAPPED")
				if loadErr != nil {
					t.Fatal(loadErr)
				}
				t.Cleanup(func() { graph.Close() })
				result, err = ExecuteCrossWithMaxRows(context.Background(), []Graph{{ID: "g", Store: graph}, {ID: "g", Store: graph}}, record.Spec.Query, nil, record.Spec.MaxRows, options)
			case "cross-empty":
				result, err = ExecuteCrossWithMaxRows(context.Background(), nil, record.Spec.Query, nil, record.Spec.MaxRows, options)
			default:
				t.Fatalf("unknown oracle constructor mode %q", record.Spec.Mode)
			}
			if record.Outcome != "FAILED" || err == nil {
				t.Fatalf("main outcome %q; Go result %#v, error %v", record.Outcome, result, err)
			}
			workCompare(t, "public error", workErrorValue(err), map[string]any{"error": record.Error, "message": record.Message})
			// Failed executions must not expose partially populated public or raw
			// result state. The JVM result variable was never assigned either.
			if record.Columns != nil || record.Rows != nil || !reflect.DeepEqual(result, Result{}) {
				t.Errorf("failed result was not atomic-empty: main columns %#v rows %#v; Go %#v", record.Columns, record.Rows, result)
			}
			workCompare(t, "diagnostics", work.Diagnostics(), record.Diagnostics)
			workCompare(t, "cancelled", work.IsCancelled(), record.Cancelled)
			workCompare(t, "first reason identity", work.CancellationException() == reason, record.ReasonPreserved)
		})
	}
}
