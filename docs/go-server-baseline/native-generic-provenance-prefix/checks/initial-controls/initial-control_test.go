package query

import (
	"context"
	"encoding/json"
	"errors"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// Actual JVM public queries on small persisted correctness graphs distinguish
// the generic prefix consumed by selected-row provenance from full projection.
// Forty independently copied sources enter main's balanced source scheduling.
// These fixtures establish behavior only, never performance.
func TestGenericProvenanceMainPrefix(t *testing.T) {
	base := "/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite/docs/go-server-baseline/native-generic-provenance-prefix/initial-missing-callsite-control"
	var oracle struct {
		Cases []struct {
			Name string
			Spec struct {
				Query      string
				Parameters map[string]any
				Scoped     bool
				Sources    []struct{ Fixture, ID string }
			}
			Outcome string
			Columns []string
			Rows    []map[string]any
			Error   string
			Message *string
		}
	}
	readDistinctJSON(t, filepath.Join(base, "main.json"), &oracle)
	if len(oracle.Cases) != 13 {
		t.Fatalf("original JVM case count = %d, want 13", len(oracle.Cases))
	}
	fixtures := genericDisjunctionFixtures(t, filepath.Join(base, "fixtures.tar.gz"), 451)
	outputs := []map[string]any{}
	defer func() { writeDistinctEvidence(t, "generic-provenance-prefix.json", outputs) }()
	seen := map[string]bool{}
	for _, record := range oracle.Cases {
		if seen[record.Name] {
			t.Fatalf("duplicate original case %q", record.Name)
		}
		seen[record.Name] = true
		t.Run(record.Name, func(t *testing.T) {
			if len(record.Spec.Sources) != 40 {
				t.Fatalf("original sources = %d, want 40", len(record.Spec.Sources))
			}
			sources := make([]Graph, len(record.Spec.Sources))
			for i, source := range record.Spec.Sources {
				dir := ordinaryCopyFixture(t, filepath.Join(fixtures, source.Fixture))
				graph, err := store.OpenMode(dir, "MAPPED")
				if err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { graph.Close() })
				sources[i] = Graph{ID: source.ID, Store: graph}
			}
			result, err := ExecuteCrossWithOptions(context.Background(), sources, record.Spec.Query,
				record.Spec.Parameters, -1, ExecutionOptions{SourceScopeApplied: record.Spec.Scoped, WorkTrackingEnabled: true})
			outputs = append(outputs, distinctOracleResult(map[string]any{"name": record.Name}, result, err))
			if record.Outcome == "FAILED" {
				var failure *Error
				if !errors.As(err, &failure) {
					t.Fatalf("main error %s; Go result %#v, error %v", record.Error, result, err)
				}
				var message any
				if record.Message != nil {
					message = *record.Message
				}
				if failure.Class != record.Error || !reflect.DeepEqual(failure.JavaMessage(), message) {
					t.Fatalf("main error %s / %#v; Go %s / %#v", record.Error, message, failure.Class, failure.JavaMessage())
				}
				if result.Columns != nil || result.Rows != nil {
					t.Fatalf("failed response contains partial output: %#v", result)
				}
				return
			}
			if record.Outcome != "SUCCESS" || err != nil {
				t.Fatalf("main outcome %s; Go error %v", record.Outcome, err)
			}
			data, marshalErr := json.Marshal(result)
			if marshalErr != nil {
				t.Fatal(marshalErr)
			}
			var normalized Result
			if unmarshalErr := json.Unmarshal(data, &normalized); unmarshalErr != nil {
				t.Fatal(unmarshalErr)
			}
			if !reflect.DeepEqual(normalized.Columns, record.Columns) || !reflect.DeepEqual(normalized.Rows, record.Rows) {
				t.Fatalf("main columns %#v rows %#v; Go %s", record.Columns, record.Rows, data)
			}
		})
	}
}
