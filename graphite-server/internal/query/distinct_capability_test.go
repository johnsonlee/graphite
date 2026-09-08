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

// Public JVM observations distinguish an unavailable structural projection
// capability from a supported empty serial scan. All fixtures are correctness
// inputs, never latency or resource benchmarks.
func TestDistinctCapabilityMain(t *testing.T) {
	base := "../../../docs/go-server-baseline/native-distinct-capability-boundary"
	t.Run("primary", func(t *testing.T) { assertDistinctCapabilityOracle(t, base, 46, 83) })
	t.Run("supplement", func(t *testing.T) { assertDistinctCapabilityOracle(t, filepath.Join(base, "supplement"), 8, 66) })
}

func TestDistinctCapabilityInitialMain(t *testing.T) {
	assertDistinctCapabilityOracle(t, "../../../docs/go-server-baseline/native-generic-provenance-prefix/initial-missing-callsite-control", 13, 451)
}

func assertDistinctCapabilityOracle(t *testing.T, base string, caseCount, fixtureFiles int) {
	t.Helper()
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
	if len(oracle.Cases) != caseCount {
		t.Fatalf("original JVM case count = %d, want %d", len(oracle.Cases), caseCount)
	}
	fixtures := genericDisjunctionFixtures(t, filepath.Join(base, "fixtures.tar.gz"), fixtureFiles)
	outputs := []map[string]any{}
	defer func() { writeDistinctEvidence(t, "capability-"+filepath.Base(base)+".json", outputs) }()
	seen := map[string]bool{}
	for _, record := range oracle.Cases {
		if seen[record.Name] {
			t.Fatalf("duplicate original case %q", record.Name)
		}
		seen[record.Name] = true
		t.Run(record.Name, func(t *testing.T) {
			if len(record.Spec.Sources) < 1 || len(record.Spec.Sources) > 40 {
				t.Fatalf("invalid original source count %d", len(record.Spec.Sources))
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
			options := ExecutionOptions{SourceScopeApplied: record.Spec.Scoped, WorkTrackingEnabled: true}
			var result Result
			var err error
			// Match the actual JVM harness's single/cross executor selection.
			if len(sources) == 1 {
				result, err = executeSources(context.Background(), sources[0].Store, nil, false,
					record.Spec.Query, record.Spec.Parameters, -1, options)
			} else {
				result, err = ExecuteCrossWithOptions(context.Background(), sources, record.Spec.Query,
					record.Spec.Parameters, -1, options)
			}
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
